package app.lyra.music.personalization

import android.content.Context
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.sqrt

/**
 * Diario locale compatto degli eventi che alimentano il profilo musicale.
 * Nessun token, URL di stream o dato Google viene scritto qui.
 */
object ListeningEventStore {
    private const val PREFS_NAME = "lyra_listening_events"
    private const val KEY_EVENTS = "events"
    private const val MAX_EVENTS = 2_000

    enum class Type {
        STARTED,
        OUTCOME,
        LIKED,
        UNLIKED,
        BLOCK_TRACK,
        REDUCE_ARTIST,
        BOOST_SIMILAR,
    }

    data class Event(
        val id: String,
        val type: Type,
        val songId: String,
        val title: String,
        val artist: String,
        val timestampMs: Long,
        val listenedFraction: Double? = null,
        val listenedMs: Long? = null,
        val durationMs: Long? = null,
        val completed: Boolean? = null,
        val manualSkip: Boolean? = null,
        val earlySkip: Boolean? = null,
        val contextTag: String? = null,
        val queueTitle: String? = null,
        val synced: Boolean = false,
    )

    private lateinit var appContext: Context
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        _events.value = load()
    }

    @Synchronized
    fun record(
        type: Type,
        song: Song,
        listenedFraction: Double? = null,
        listenedMs: Long? = null,
        durationMs: Long? = null,
        completed: Boolean? = null,
        manualSkip: Boolean? = null,
        earlySkip: Boolean? = null,
        contextTag: String? = null,
        queueTitle: String? = null,
    ) {
        if (!::appContext.isInitialized || song.id.isBlank()) return
        val event = Event(
            id = UUID.randomUUID().toString(),
            type = type,
            songId = song.id,
            title = song.title,
            artist = song.artist,
            timestampMs = System.currentTimeMillis(),
            listenedFraction = listenedFraction,
            listenedMs = listenedMs,
            durationMs = durationMs,
            completed = completed,
            manualSkip = manualSkip,
            earlySkip = earlySkip,
            contextTag = contextTag,
            queueTitle = queueTitle,
        )
        val updated = trimForRetention(_events.value + event)
        _events.value = updated
        save(updated)
        CloudProfileSync.scheduleSync()
    }

    fun pendingForCloud(limit: Int = Int.MAX_VALUE): List<Event> = _events.value
        .asSequence()
        .filterNot { it.synced }
        .take(limit)
        .toList()

    /** Segnale recente separato dal profilo storico: base per Recommendation Engine V2. */
    fun recentTrackAffinity(songId: String, nowMs: Long = System.currentTimeMillis()): Double =
        recentAffinity(nowMs) { it.songId == songId }

    fun recentArtistAffinity(artist: String, nowMs: Long = System.currentTimeMillis()): Double {
        val key = artist.trim().lowercase()
        if (key.isBlank()) return 0.0
        return recentAffinity(nowMs) { it.artist.trim().lowercase() == key }
    }

    fun recentContextAffinity(
        songId: String,
        contextTag: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        val key = contextTag?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return 0.0
        return recentAffinity(nowMs) { event ->
            event.songId == songId && event.contextTag?.trim()?.lowercase() == key
        }
    }

    /**
     * Snapshot dei soli skip precoci manuali nelle ultime 24 ore. Gli eventi
     * arrivano anche dal ripristino Ubuntu: nessuna nuova tabella o API.
     * I conteggi sono calcolati una volta per ranking, non per ogni candidato.
     */
    data class RecentRejections(
        val bySong: Map<String, Int>,
        val byArtist: Map<String, Int>,
    )

    fun recentRejections(
        nowMs: Long = System.currentTimeMillis(),
        artistKey: (String) -> String,
    ): RecentRejections {
        val songs = mutableMapOf<String, Int>()
        val artists = mutableMapOf<String, Int>()
        _events.value.asSequence()
            .filter { it.type == Type.OUTCOME && it.earlySkip == true }
            .filter { it.timestampMs > 0L && (nowMs - it.timestampMs) in 0L..86_400_000L }
            .forEach { event ->
                songs[event.songId] = (songs[event.songId] ?: 0) + 1
                val key = artistKey(event.artist)
                if (key.isNotBlank()) artists[key] = (artists[key] ?: 0) + 1
            }
        return RecentRejections(songs, artists)
    }

    /**
     * Ripristina dal cloud gli eventi recenti come gia' sincronizzati. Gli eventi
     * locali non ancora inviati hanno precedenza in caso di ID duplicato.
     */
    @Synchronized
    fun restoreCloudEventsJson(array: JSONArray?, replaceSynced: Boolean = false, discardPending: Boolean = false) {
        if (!::appContext.isInitialized || array == null) return
        val cloud = buildList {
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val type = runCatching { Type.valueOf(obj.optString("event_type")) }.getOrNull()
                    ?: continue
                val songId = obj.optString("song_id")
                if (songId.isBlank()) continue
                add(
                    Event(
                        id = obj.optString("event_id").ifBlank { UUID.randomUUID().toString() },
                        type = type,
                        songId = songId,
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        timestampMs = obj.optLong("created_at_ms", 0L),
                        listenedFraction = obj.optNullableDouble("listened_fraction"),
                        listenedMs = obj.optNullableLong("listened_ms"),
                        durationMs = obj.optNullableLong("duration_ms"),
                        completed = obj.optNullableIntBoolean("completed"),
                        manualSkip = obj.optNullableIntBoolean("manual_skip"),
                        earlySkip = obj.optNullableIntBoolean("early_skip"),
                        contextTag = obj.optNullableString("context_tag"),
                        queueTitle = obj.optNullableString("queue_title"),
                        synced = true,
                    )
                )
            }
        }
        val merged = LinkedHashMap<String, Event>()
        cloud.forEach { merged[it.id] = it }
        _events.value.forEach { if (!discardPending && (!replaceSynced || !it.synced)) merged[it.id] = it }
        val updated = trimForRetention(merged.values.sortedBy { it.timestampMs })
        _events.value = updated
        save(updated)
    }

    private fun recentAffinity(nowMs: Long, predicate: (Event) -> Boolean): Double {
        var weightedScore = 0.0
        var evidence = 0.0
        _events.value.asSequence()
            .filter(predicate)
            .forEach { event ->
                val weight = recencyWeight(nowMs, event.timestampMs)
                if (weight <= 0.0) return@forEach
                val signal = when (event.type) {
                    Type.STARTED -> 0.08
                    Type.OUTCOME -> when {
                        event.earlySkip == true -> -1.00
                        event.completed == true -> 1.00
                        else -> ((event.listenedFraction ?: 0.0) - 0.45) * 1.10
                    }
                    Type.LIKED -> 1.40
                    Type.UNLIKED -> -1.40
                    Type.BLOCK_TRACK -> -2.00
                    Type.REDUCE_ARTIST -> -1.20
                    Type.BOOST_SIMILAR -> 1.10
                }
                weightedScore += signal * weight
                evidence += weight
            }
        if (evidence <= 0.0) return 0.0
        return (weightedScore / sqrt(evidence.coerceAtLeast(1.0))).coerceIn(-1.0, 1.0)
    }

    private fun recencyWeight(nowMs: Long, eventAtMs: Long): Double {
        if (eventAtMs <= 0L) return 0.0
        val age = (nowMs - eventAtMs).coerceAtLeast(0L)
        return when {
            age <= 7L * 86_400_000L -> 1.00
            age <= 30L * 86_400_000L -> 0.65
            age <= 90L * 86_400_000L -> 0.35
            else -> 0.0
        }
    }

    @Synchronized
    fun markSynced(ids: Set<String>) {
        if (ids.isEmpty() || !::appContext.isInitialized) return
        var changed = false
        val updated = _events.value.map { event ->
            if (!event.synced && event.id in ids) {
                changed = true
                event.copy(synced = true)
            } else {
                event
            }
        }
        if (changed) {
            _events.value = updated
            save(updated)
        }
    }

    @Synchronized
    fun clear() {
        if (!::appContext.isInitialized) return
        _events.value = emptyList()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_EVENTS).apply()
    }

    private fun load(): List<Event> {
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val type = runCatching { Type.valueOf(obj.optString("type")) }.getOrNull()
                        ?: continue
                    val songId = obj.optString("songId")
                    if (songId.isBlank()) continue
                    add(
                        Event(
                            id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                            type = type,
                            songId = songId,
                            title = obj.optString("title"),
                            artist = obj.optString("artist"),
                            timestampMs = obj.optLong("timestampMs", 0L),
                            listenedFraction = obj.optNullableDouble("listenedFraction"),
                            listenedMs = obj.optNullableLong("listenedMs"),
                            durationMs = obj.optNullableLong("durationMs"),
                            completed = obj.optNullableBoolean("completed"),
                            manualSkip = obj.optNullableBoolean("manualSkip"),
                            earlySkip = obj.optNullableBoolean("earlySkip"),
                            contextTag = obj.optNullableString("contextTag"),
                            queueTitle = obj.optNullableString("queueTitle"),
                            synced = obj.optBoolean("synced", false),
                        )
                    )
                }
            }.let(::trimForRetention)
        }.getOrDefault(emptyList())
    }

    /**
     * Non eliminiamo mai eventi non ancora sincronizzati: in caso di lunga
     * assenza di rete il limite riguarda soltanto gli eventi gia' confermati
     * dal cloud. Questo evita buchi permanenti nel backup.
     */
    private fun trimForRetention(events: List<Event>): List<Event> {
        if (events.size <= MAX_EVENTS) return events
        val pending = events.filterNot { it.synced }
        val synced = events.filter { it.synced }
        val syncedBudget = (MAX_EVENTS - pending.size).coerceAtLeast(0)
        return (synced.takeLast(syncedBudget) + pending)
            .sortedBy { it.timestampMs }
    }

    private fun save(events: List<Event>) {
        val array = JSONArray()
        events.forEach { event ->
            array.put(
                JSONObject()
                    .put("id", event.id)
                    .put("type", event.type.name)
                    .put("songId", event.songId)
                    .put("title", event.title)
                    .put("artist", event.artist)
                    .put("timestampMs", event.timestampMs)
                    .put("listenedFraction", event.listenedFraction)
                    .put("listenedMs", event.listenedMs)
                    .put("durationMs", event.durationMs)
                    .put("completed", event.completed)
                    .put("manualSkip", event.manualSkip)
                    .put("earlySkip", event.earlySkip)
                    .put("contextTag", event.contextTag)
                    .put("queueTitle", event.queueTitle)
                    .put("synced", event.synced)
            )
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_EVENTS, array.toString()).apply()
    }

    fun toCloudJson(event: Event): JSONObject = JSONObject()
        .put("id", event.id)
        .put("type", event.type.name)
        .put("songId", event.songId)
        .put("title", event.title)
        .put("artist", event.artist)
        .put("timestampMs", event.timestampMs)
        .put("listenedFraction", event.listenedFraction)
        .put("listenedMs", event.listenedMs)
        .put("durationMs", event.durationMs)
        .put("completed", event.completed)
        .put("manualSkip", event.manualSkip)
        .put("earlySkip", event.earlySkip)
        .put("contextTag", event.contextTag)
        .put("queueTitle", event.queueTitle)

    private fun JSONObject.optNullableString(key: String): String? =
        takeIf { has(key) && !isNull(key) }
            ?.optString(key)
            ?.takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optNullableLong(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        takeIf { has(key) && !isNull(key) }?.optDouble(key)

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        takeIf { has(key) && !isNull(key) }?.optBoolean(key)

    private fun JSONObject.optNullableIntBoolean(key: String): Boolean? =
        takeIf { has(key) && !isNull(key) }?.let { optInt(key, 0) != 0 }
}
