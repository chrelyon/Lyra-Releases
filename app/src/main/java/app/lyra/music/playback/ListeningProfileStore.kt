package app.lyra.music.playback

import android.content.Context
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.core.model.Song
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Profilo musicale locale di Lyra.
 *
 * Dalla 0.3.15 non usa un semplice contatore: conserva completamenti, percentuale
 * ascoltata, skip precoci, Preferiti, feedback esplicito e affinita' contestuale.
 * Il profilo resta locale; la copia Ubuntu e' separata.
 */
object ListeningProfileStore {
    private const val PREFS_NAME = "lyra_listening_profile"
    private const val KEY_STATS = "track_stats"
    private const val KEY_ARTIST_PENALTIES = "artist_penalties"
    private const val KEY_ARTIST_BOOSTS = "artist_boosts"
    private const val MAX_TRACKS = 700

    data class TrackStat(
        val song: Song,
        val playCount: Int = 0,
        val completionCount: Int = 0,
        val skipCount: Int = 0,
        val partialCount: Int = 0,
        val liked: Boolean = false,
        val blocked: Boolean = false,
        val boostCount: Int = 0,
        val totalListenedFraction: Double = 0.0,
        val outcomeCount: Int = 0,
        val lastPlayedAtMs: Long = 0L,
        val contextScores: Map<String, Double> = emptyMap(),
    ) {
        val averageListenedFraction: Double
            get() = if (outcomeCount > 0) {
                (totalListenedFraction / outcomeCount.toDouble()).coerceIn(0.0, 1.0)
            } else {
                0.0
            }

        /** Compatibilita' con i mix 0.3.13/0.3.14. */
        val score: Int
            get() = (
                playCount + completionCount * 3 + (if (liked) 6 else 0) +
                    boostCount * 4 - skipCount * 3 +
                    (averageListenedFraction * 4.0).toInt()
                )
    }

    private lateinit var appContext: Context
    private val _stats = MutableStateFlow<Map<String, TrackStat>>(emptyMap())
    val stats: StateFlow<Map<String, TrackStat>> = _stats.asStateFlow()

    private var artistPenalties: MutableMap<String, Double> = mutableMapOf()
    private var artistBoosts: MutableMap<String, Double> = mutableMapOf()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val loaded = load().associateBy { it.song.id }
        artistPenalties = loadDoubleMap(KEY_ARTIST_PENALTIES).toMutableMap()
        artistBoosts = loadDoubleMap(KEY_ARTIST_BOOSTS).toMutableMap()
        if (loaded.isNotEmpty()) {
            _stats.value = loaded
            return
        }

        // Prima inizializzazione: usa la cronologia esistente solo come segnale
        // debole. Non inventa completamenti, skip o Preferiti.
        val migrated = LinkedHashMap<String, TrackStat>()
        ListeningHistoryStore.entries.value.asReversed().forEach { entry ->
            val current = migrated[entry.song.id] ?: TrackStat(song = entry.song)
            val tag = inferContextTag(entry.queueTitle)
            migrated[entry.song.id] = current.copy(
                song = entry.song,
                playCount = current.playCount + 1,
                lastPlayedAtMs = maxOf(current.lastPlayedAtMs, entry.listenedAtMs),
                contextScores = current.contextScores.increment(tag, 0.15),
            )
        }
        _stats.value = migrated
        if (migrated.isNotEmpty()) save(migrated.values.toList())
    }

    @Synchronized
    fun recordStarted(song: Song, queueTitle: String) {
        if (!learningFromHistoryEnabled()) return
        val tag = inferContextTag(queueTitle)
        update(song) { current ->
            current.copy(
                playCount = current.playCount + 1,
                lastPlayedAtMs = System.currentTimeMillis(),
                contextScores = current.contextScores.increment(tag, 0.10),
            )
        }
        ListeningEventStore.record(
            type = ListeningEventStore.Type.STARTED,
            song = song,
            contextTag = tag,
            queueTitle = queueTitle,
        )
    }

    /** Registra il risultato reale di una permanenza sul brano. */
    @Synchronized
    fun recordOutcome(
        song: Song,
        queueTitle: String,
        listenedMs: Long,
        durationMs: Long,
        completed: Boolean,
        manualSkip: Boolean,
    ) {
        if (!learningFromHistoryEnabled()) return
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safeListened = listenedMs.coerceAtLeast(0L)
        val fraction = if (safeDuration > 0L) {
            (safeListened.toDouble() / safeDuration.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val earlySkip = manualSkip &&
            PersonalizationSettings.learnFromSkips.value &&
            safeDuration > 0L &&
            fraction < 0.30 &&
            safeListened < 45_000L
        val effectiveCompleted = completed || (safeDuration > 0L && fraction >= 0.85)
        val contextTag = inferContextTag(queueTitle)
        val contextDelta = when {
            effectiveCompleted -> 0.65
            earlySkip -> -0.70
            fraction >= 0.60 -> 0.30
            fraction >= 0.30 -> 0.05
            else -> -0.10
        }

        update(song) { current ->
            current.copy(
                completionCount = current.completionCount + if (effectiveCompleted) 1 else 0,
                skipCount = current.skipCount + if (earlySkip) 1 else 0,
                partialCount = current.partialCount + if (!effectiveCompleted && !earlySkip) 1 else 0,
                totalListenedFraction = current.totalListenedFraction + fraction,
                outcomeCount = current.outcomeCount + 1,
                contextScores = current.contextScores.increment(contextTag, contextDelta),
            )
        }
        ListeningEventStore.record(
            type = ListeningEventStore.Type.OUTCOME,
            song = song,
            listenedFraction = fraction,
            listenedMs = safeListened,
            durationMs = safeDuration,
            completed = effectiveCompleted,
            manualSkip = manualSkip,
            earlySkip = earlySkip,
            contextTag = contextTag,
            queueTitle = queueTitle,
        )
    }

    /** API compatibili con le versioni precedenti. */
    @Synchronized
    fun recordCompleted(song: Song) {
        if (!learningFromHistoryEnabled()) return
        update(song) { current ->
            current.copy(
                completionCount = current.completionCount + 1,
                totalListenedFraction = current.totalListenedFraction + 1.0,
                outcomeCount = current.outcomeCount + 1,
            )
        }
    }

    @Synchronized
    fun recordSkipped(song: Song) {
        if (!learningFromHistoryEnabled() || !PersonalizationSettings.learnFromSkips.value) return
        update(song) { current -> current.copy(skipCount = current.skipCount + 1, outcomeCount = current.outcomeCount + 1) }
    }

    @Synchronized
    fun setLiked(song: Song, liked: Boolean) {
        if (!PersonalizationSettings.enabled.value || !PersonalizationSettings.useLikes.value) return
        val current = _stats.value[song.id]
        // Le rating YouTube gia' note vengono riallineate all'avvio del brano.
        // Evitiamo di produrre eventi cloud/locali duplicati se lo stato non cambia.
        if (current?.liked == liked || (current == null && !liked)) return
        update(song) { it.copy(liked = liked) }
        ListeningEventStore.record(
            type = if (liked) ListeningEventStore.Type.LIKED else ListeningEventStore.Type.UNLIKED,
            song = song,
        )
    }

    @Synchronized
    fun blockSong(song: Song) {
        if (!PersonalizationSettings.enabled.value) return
        update(song) { it.copy(blocked = true) }
        ListeningEventStore.record(ListeningEventStore.Type.BLOCK_TRACK, song)
    }

    @Synchronized
    fun reduceArtist(song: Song) {
        if (!PersonalizationSettings.enabled.value || song.artist.isBlank()) return
        val key = normalizeArtist(song.artist)
        artistPenalties[key] = ((artistPenalties[key] ?: 0.0) - 0.35).coerceAtLeast(-1.0)
        saveDoubleMap(KEY_ARTIST_PENALTIES, artistPenalties)
        ListeningEventStore.record(ListeningEventStore.Type.REDUCE_ARTIST, song)
        CloudProfileSync.scheduleSync()
    }

    @Synchronized
    fun boostSimilar(song: Song) {
        if (!PersonalizationSettings.enabled.value) return
        update(song) { it.copy(boostCount = it.boostCount + 1) }
        if (song.artist.isNotBlank()) {
            val key = normalizeArtist(song.artist)
            artistBoosts[key] = ((artistBoosts[key] ?: 0.0) + 0.20).coerceAtMost(1.0)
            saveDoubleMap(KEY_ARTIST_BOOSTS, artistBoosts)
        }
        ListeningEventStore.record(ListeningEventStore.Type.BOOST_SIMILAR, song)
    }

    fun isBlocked(songId: String): Boolean = _stats.value[songId]?.blocked == true

    fun trackAffinity(songId: String): Double {
        val stat = _stats.value[songId] ?: return 0.0
        if (stat.blocked) return -1.0

        // Gli ascolti impliciti vengono smorzati finche' non esiste abbastanza
        // evidenza: un solo ascolto completo o un singolo skip non devono
        // etichettare definitivamente un brano. I segnali espliciti (cuore e
        // "piu' musica come questa") restano invece intenzionalmente forti.
        val historyEnabled = PersonalizationSettings.useHistory.value
        val outcomes = stat.outcomeCount.coerceAtLeast(1).toDouble()
        val evidenceConfidence = if (historyEnabled) {
            stat.outcomeCount.toDouble() / (stat.outcomeCount.toDouble() + 3.0)
        } else {
            0.0
        }
        val completionRate = stat.completionCount.toDouble() / outcomes
        val skipRate = stat.skipCount.toDouble() / outcomes
        val fractionSignal = (stat.averageListenedFraction - 0.50) * 1.10
        val completionSignal = completionRate * 0.55
        val skipSignal = if (PersonalizationSettings.learnFromSkips.value) skipRate * -0.90 else 0.0
        val implicitBehavior = (fractionSignal + completionSignal + skipSignal) * evidenceConfidence

        // Decadimento morbido: i gusti vecchi restano utili, ma pesano meno
        // rispetto a quelli recenti. Il cuore non decade con il tempo.
        val ageDays = if (stat.lastPlayedAtMs > 0L) {
            (System.currentTimeMillis() - stat.lastPlayedAtMs).coerceAtLeast(0L) / 86_400_000.0
        } else {
            365.0
        }
        val memoryWeight = (1.0 / (1.0 + ageDays / 180.0)).coerceIn(0.35, 1.0)
        val likeSignal = if (stat.liked && PersonalizationSettings.useLikes.value) 0.68 else 0.0
        val boostSignal = (stat.boostCount * 0.18).coerceAtMost(0.45)
        val familiarity = if (historyEnabled) {
            (ln(1.0 + stat.playCount.toDouble()) / 16.0).coerceAtMost(0.14)
        } else {
            0.0
        }
        return (implicitBehavior * memoryWeight + likeSignal + boostSignal + familiarity)
            .coerceIn(-1.0, 1.0)
    }

    fun artistAffinity(artist: String): Double {
        if (artist.isBlank()) return 0.0
        val key = normalizeArtist(artist)
        val related = _stats.value.values.filter { normalizeArtist(it.song.artist) == key }
        val learned = if (related.isEmpty()) {
            0.0
        } else {
            val totalWeight = related.sumOf { sqrt(it.playCount.coerceAtLeast(1).toDouble()) }
            related.sumOf { stat ->
                trackAffinity(stat.song.id) * sqrt(stat.playCount.coerceAtLeast(1).toDouble())
            } / totalWeight.coerceAtLeast(1.0)
        }
        return (learned + (artistPenalties[key] ?: 0.0) + (artistBoosts[key] ?: 0.0))
            .coerceIn(-1.0, 1.0)
    }

    fun contextAffinity(songId: String, tag: String?): Double {
        if (!PersonalizationSettings.useHistory.value || tag.isNullOrBlank()) return 0.0
        val raw = _stats.value[songId]?.contextScores?.get(tag) ?: return 0.0
        return (raw / 3.0).coerceIn(-1.0, 1.0)
    }

    fun lastPlayedAt(songId: String): Long = _stats.value[songId]?.lastPlayedAtMs ?: 0L
    fun playCount(songId: String): Int = _stats.value[songId]?.playCount ?: 0

    /**
     * Correzione automatica molto contenuta della quota di scoperta. Se negli
     * ascolti recenti prevalgono skip precoci Lyra diventa piu' conservativa;
     * se prevalgono completamenti accetta un po' piu' di novita'. La scelta
     * esplicita dell'utente resta comunque il limite principale.
     */
    fun explorationAdjustment(): Double {
        if (!PersonalizationSettings.useHistory.value) return 1.0
        val recent = _stats.value.values
            .asSequence()
            .filter { it.outcomeCount > 0 }
            .sortedByDescending { it.lastPlayedAtMs }
            .take(40)
            .toList()
        if (recent.size < 5) return 1.0
        val completions = recent.sumOf { it.completionCount }.toDouble()
        val skips = recent.sumOf { it.skipCount }.toDouble()
        val total = recent.sumOf { it.outcomeCount }.toDouble().coerceAtLeast(1.0)
        val completionRate = completions / total
        val skipRate = skips / total
        return when {
            skipRate >= 0.40 -> 0.72
            skipRate >= 0.25 -> 0.86
            completionRate >= 0.65 -> 1.18
            completionRate >= 0.48 -> 1.08
            else -> 1.0
        }
    }

    fun topSongs(limit: Int = 30): List<Song> = _stats.value.values
        .filterNot { it.blocked }
        .sortedWith(compareByDescending<TrackStat> { trackAffinity(it.song.id) }.thenByDescending { it.lastPlayedAtMs })
        .map { it.song }
        .distinctBy { it.id }
        .take(limit)

    fun recentLiked(limit: Int = 40): List<Song> = _stats.value.values
        .filter { it.liked && !it.blocked }
        .sortedByDescending { it.lastPlayedAtMs }
        .map { it.song }
        .distinctBy { it.id }
        .take(limit)

    fun rediscover(limit: Int = 40): List<Song> {
        val now = System.currentTimeMillis()
        val fourteenDays = 14L * 24L * 60L * 60L * 1000L
        val candidates = _stats.value.values
            .filter { it.playCount > 0 && !it.blocked && now - it.lastPlayedAtMs >= fourteenDays }
            .sortedWith(compareByDescending<TrackStat> { trackAffinity(it.song.id) }.thenBy { it.lastPlayedAtMs })
            .map { it.song }
            .distinctBy { it.id }
        if (candidates.isNotEmpty()) return candidates.take(limit)
        return _stats.value.values
            .filter { it.playCount > 0 && !it.blocked }
            .sortedBy { it.lastPlayedAtMs }
            .map { it.song }
            .distinctBy { it.id }
            .take(limit)
    }

    fun topContextTags(limit: Int = 4): List<String> {
        val totals = mutableMapOf<String, Double>()
        _stats.value.values.filterNot { it.blocked }.forEach { stat ->
            stat.contextScores.forEach { (tag, score) ->
                totals[tag] = (totals[tag] ?: 0.0) + score + trackAffinity(stat.song.id).coerceAtLeast(0.0)
            }
        }
        return totals.entries.sortedByDescending { it.value }.map { it.key }.take(limit)
    }

    /** Candidati pertinenti ordinati, non un unico seed fisso ad ogni avvio. */
    fun seedCandidatesForTag(tag: String, limit: Int = 20): List<Song> = _stats.value.values
        .asSequence()
        .filter { !it.blocked && !it.song.isLocal && (it.contextScores[tag] ?: 0.0) > 0.0 }
        .sortedByDescending { (it.contextScores[tag] ?: 0.0) + trackAffinity(it.song.id) }
        .take(limit.coerceIn(1, 40))
        .map { it.song }
        .toList()

    /** Compatibilita' con altri componenti che richiedono un solo candidato. */
    fun bestSeedForTag(tag: String): Song? = seedCandidatesForTag(tag, 1).firstOrNull()

    fun inferContextTag(queueTitle: String): String? {
        val normalized = queueTitle.lowercase()
        return AutomaticPlaylistCatalog.presets.firstOrNull { preset ->
            normalized.contains(preset.title.lowercase()) || normalized.contains(preset.id.lowercase())
        }?.id
    }

    @Synchronized
    internal fun applySharedSnapshot(
        shared: JSONObject, sent: JSONObject, reset: Boolean = false,
        beforeApply: (JSONObject) -> Unit, afterApply: () -> Unit,
    ) {
        val merged = if (reset) JSONObject(shared.toString()) else
            app.lyra.music.cloud.MusicSnapshotMerge.merge(shared, cloudSnapshotJson(), sent)
        beforeApply(merged)
        restoreCloudSnapshotJson(merged)
        afterApply()
    }

    @Synchronized
    fun restoreCloudSnapshotJson(snapshot: JSONObject) {
        if (!::appContext.isInitialized) return
        require(snapshot.optInt("schemaVersion", 0) in 1..2) { "Schema profilo cloud non supportato" }

        val restored = LinkedHashMap<String, TrackStat>()
        val tracks = snapshot.optJSONArray("tracks") ?: JSONArray()
        for (index in 0 until tracks.length()) {
            val obj = tracks.optJSONObject(index) ?: continue
            val songId = obj.optString("songId")
            if (songId.isBlank()) continue
            val contextScores = mutableMapOf<String, Double>()
            obj.optJSONObject("contextScores")?.let { scores ->
                scores.keys().forEach { key -> contextScores[key] = scores.optDouble(key, 0.0) }
            }
            val completionCount = obj.optInt("completionCount", 0).coerceAtLeast(0)
            val skipCount = obj.optInt("skipCount", 0).coerceAtLeast(0)
            val partialCount = obj.optInt("partialCount", 0).coerceAtLeast(0)
            val outcomeCount = (completionCount + skipCount + partialCount).coerceAtLeast(
                if (obj.optDouble("averageListenedFraction", 0.0) > 0.0) 1 else 0,
            )
            val average = obj.optDouble("averageListenedFraction", 0.0).coerceIn(0.0, 1.0)
            restored[songId] = TrackStat(
                song = Song(
                    id = songId,
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    durationSeconds = 0L,
                ),
                playCount = obj.optInt("playCount", 0).coerceAtLeast(0),
                completionCount = completionCount,
                skipCount = skipCount,
                partialCount = partialCount,
                liked = obj.optBoolean("liked", false),
                blocked = obj.optBoolean("blocked", false),
                boostCount = obj.optInt("boostCount", 0).coerceAtLeast(0),
                totalListenedFraction = average * outcomeCount.toDouble(),
                outcomeCount = outcomeCount,
                lastPlayedAtMs = obj.optLong("lastPlayedAtMs", 0L),
                contextScores = contextScores,
            )
        }

        artistPenalties = snapshot.optJSONObject("artistPenalties")?.let { values ->
            buildMap { values.keys().forEach { key -> put(key, values.optDouble(key, 0.0)) } }
        }?.toMutableMap() ?: mutableMapOf()
        artistBoosts = snapshot.optJSONObject("artistBoosts")?.let { values ->
            buildMap { values.keys().forEach { key -> put(key, values.optDouble(key, 0.0)) } }
        }?.toMutableMap() ?: mutableMapOf()

        val trimmed = restored.values
            .sortedByDescending { it.lastPlayedAtMs }
            .take(MAX_TRACKS)
            .associateByTo(LinkedHashMap()) { it.song.id }
        _stats.value = trimmed
        save(trimmed.values.toList(), durable = true)
        saveDoubleMap(KEY_ARTIST_PENALTIES, artistPenalties, durable = true)
        saveDoubleMap(KEY_ARTIST_BOOSTS, artistBoosts, durable = true)
    }

    fun cloudSnapshotJson(): JSONObject {
        val tracks = JSONArray()
        _stats.value.values
            .sortedByDescending { it.lastPlayedAtMs }
            .forEach { stat ->
                tracks.put(
                    JSONObject()
                        .put("songId", stat.song.id)
                        .put("title", stat.song.title)
                        .put("artist", stat.song.artist)
                        .put("playCount", stat.playCount)
                        .put("completionCount", stat.completionCount)
                        .put("skipCount", stat.skipCount)
                        .put("partialCount", stat.partialCount)
                        .put("liked", stat.liked)
                        .put("blocked", stat.blocked)
                        .put("boostCount", stat.boostCount)
                        .put("averageListenedFraction", stat.averageListenedFraction)
                        .put("lastPlayedAtMs", stat.lastPlayedAtMs)
                        .put("contextScores", JSONObject(stat.contextScores))
                )
            }
        return JSONObject()
            .put("schemaVersion", 2)
            .put("updatedAtMs", System.currentTimeMillis())
            .put("tracks", tracks)
            .put("artistPenalties", JSONObject(artistPenalties))
            .put("artistBoosts", JSONObject(artistBoosts))
    }

    @Synchronized
    fun resetProfile() {
        if (!::appContext.isInitialized) return
        _stats.value = emptyMap()
        artistPenalties.clear()
        artistBoosts.clear()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        ListeningEventStore.clear()
        CloudProfileSync.scheduleSync()
    }

    private fun learningFromHistoryEnabled(): Boolean =
        PersonalizationSettings.enabled.value && PersonalizationSettings.useHistory.value

    private fun update(song: Song, transform: (TrackStat) -> TrackStat) {
        if (!::appContext.isInitialized || song.id.isBlank()) return
        val map = LinkedHashMap(_stats.value)
        val current = map[song.id] ?: TrackStat(song = song)
        map[song.id] = transform(current.copy(song = song))
        val trimmed = map.values
            .sortedByDescending { it.lastPlayedAtMs }
            .take(MAX_TRACKS)
            .associateByTo(LinkedHashMap()) { it.song.id }
        _stats.value = trimmed
        save(trimmed.values.toList())
        CloudProfileSync.scheduleSync()
    }

    private fun Map<String, Double>.increment(key: String?, delta: Double): Map<String, Double> {
        if (key.isNullOrBlank()) return this
        val updated = toMutableMap()
        updated[key] = ((updated[key] ?: 0.0) + delta).coerceIn(-6.0, 8.0)
        return updated
    }

    private fun normalizeArtist(value: String): String = value.trim().lowercase()

    private fun load(): List<TrackStat> {
        if (!::appContext.isInitialized) return emptyList()
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val song = obj.optJSONObject("song")?.toSongOrNull() ?: continue
                    val contextScores = mutableMapOf<String, Double>()
                    obj.optJSONObject("contextScores")?.let { scores ->
                        scores.keys().forEach { key -> contextScores[key] = scores.optDouble(key, 0.0) }
                    }
                    // Migrazione schema 0.3.13/0.3.14: contextTag singolo.
                    obj.optString("contextTag")
                        .takeIf { it.isNotBlank() && it != "null" }
                        ?.let { tag -> if (tag !in contextScores) contextScores[tag] = 0.5 }
                    add(
                        TrackStat(
                            song = song,
                            playCount = obj.optInt("playCount", 0),
                            completionCount = obj.optInt("completionCount", 0),
                            skipCount = obj.optInt("skipCount", 0),
                            partialCount = obj.optInt("partialCount", 0),
                            liked = obj.optBoolean("liked", false),
                            blocked = obj.optBoolean("blocked", false),
                            boostCount = obj.optInt("boostCount", 0),
                            totalListenedFraction = obj.optDouble(
                                "totalListenedFraction",
                                obj.optInt("completionCount", 0).toDouble(),
                            ),
                            outcomeCount = obj.optInt(
                                "outcomeCount",
                                obj.optInt("completionCount", 0) + obj.optInt("skipCount", 0),
                            ),
                            lastPlayedAtMs = obj.optLong("lastPlayedAtMs", 0L),
                            contextScores = contextScores,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(stats: List<TrackStat>, durable: Boolean = false) {
        val array = JSONArray()
        stats.forEach { stat ->
            array.put(
                JSONObject()
                    .put("song", stat.song.toJson())
                    .put("playCount", stat.playCount)
                    .put("completionCount", stat.completionCount)
                    .put("skipCount", stat.skipCount)
                    .put("partialCount", stat.partialCount)
                    .put("liked", stat.liked)
                    .put("blocked", stat.blocked)
                    .put("boostCount", stat.boostCount)
                    .put("totalListenedFraction", stat.totalListenedFraction)
                    .put("outcomeCount", stat.outcomeCount)
                    .put("lastPlayedAtMs", stat.lastPlayedAtMs)
                    .put("contextScores", JSONObject(stat.contextScores))
            )
        }
        val editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STATS, array.toString())
        if (durable) check(editor.commit()) { "Impossibile salvare il profilo ripristinato" } else editor.apply()
    }

    private fun loadDoubleMap(key: String): Map<String, Double> {
        if (!::appContext.isInitialized) return emptyMap()
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap { obj.keys().forEach { name -> put(name, obj.optDouble(name, 0.0)) } }
        }.getOrDefault(emptyMap())
    }

    private fun saveDoubleMap(key: String, values: Map<String, Double>, durable: Boolean = false) {
        val editor = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, JSONObject(values).toString())
        if (durable) check(editor.commit()) { "Impossibile salvare i gusti ripristinati" } else editor.apply()
    }

    private fun Song.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("durationSeconds", durationSeconds)
        .put("thumbnailUrl", thumbnailUrl)
        .put("playbackUri", playbackUri)
        .put("album", album)
        .put("albumId", albumId)

    private fun JSONObject.toSongOrNull(): Song? {
        val id = optString("id")
        if (id.isBlank()) return null
        return Song(
            id = id,
            title = optString("title"),
            artist = optString("artist"),
            durationSeconds = optLong("durationSeconds", 0L),
            thumbnailUrl = optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
            playbackUri = optString("playbackUri").takeIf { it.isNotBlank() && it != "null" },
            album = optString("album").takeIf { it.isNotBlank() && it != "null" },
            albumId = takeIf { has("albumId") && !isNull("albumId") }?.optLong("albumId"),
        )
    }
}
