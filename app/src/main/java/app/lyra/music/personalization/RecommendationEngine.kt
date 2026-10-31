package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import app.lyra.music.playback.ListeningProfileStore
import kotlin.math.absoluteValue

/**
 * Recommendation Engine V3.
 *
 * Mantiene YouTube Music come sorgente della similarita', ma separa tre livelli
 * di preferenza: storico persistente, comportamento recente e intento della
 * sessione corrente. Il ranking evita inoltre ripetizioni troppo ravvicinate e
 * applica una quota di esplorazione controllata, senza trasformare la coda in
 * una sequenza casuale.
 */
object RecommendationEngine {
    data class SessionContext(
        val title: String,
        val contextTag: String?,
        val recentSongs: List<Song> = emptyList(),
        val sessionSalt: Long = System.currentTimeMillis(),
        val avoidArtists: Set<String> = emptySet(),
        val positiveArtists: Set<String> = emptySet(),
        val diversificationLevel: Int = 0,
    )

    data class ScoredCandidate(
        val song: Song,
        val score: Double,
    )

    fun rank(
        candidates: List<Song>,
        context: SessionContext,
    ): List<Song> {
        val clean = candidates.distinctBy { it.id }
        if (!PersonalizationSettings.enabled.value) return clean

        val exploration = (
            PersonalizationSettings.discoveryLevel.value.exploration *
                ListeningProfileStore.explorationAdjustment()
            ).coerceIn(0.05, 0.45)
        val now = System.currentTimeMillis()
        val recent = context.recentSongs.takeLast(18)
        val recentIds = recent.map { it.id }.toSet()
        val recentArtists = recent.takeLast(10)
            .map { artistKey(it.artist) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val recentAlbums = recent.takeLast(8)
            .map { normalize(it.album) }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
        val lastArtist = recent.lastOrNull()?.artist?.let(::artistKey).orEmpty()
        val recentRejections = if (PersonalizationSettings.learnFromSkips.value) {
            ListeningEventStore.recentRejections(now, ::artistKey)
        } else null

        val scored = clean
            .asSequence()
            .filterNot { ListeningProfileStore.isBlocked(it.id) }
            .mapIndexed { index, song ->
                val sourcePosition = if (clean.size <= 1) 1.0 else {
                    1.0 - index.toDouble() / (clean.size - 1).toDouble()
                }

                // Storico persistente: stabile e resistente a una singola sessione.
                val historicalTrack = ListeningProfileStore.trackAffinity(song.id)
                val historicalArtist = ListeningProfileStore.artistAffinity(song.artist)
                val historicalContext = ListeningProfileStore.contextAffinity(song.id, context.contextTag)

                // Recente: eventi degli ultimi 7/30/90 giorni con decadimento.
                val recentTrack = ListeningEventStore.recentTrackAffinity(song.id, now)
                val recentArtist = ListeningEventStore.recentArtistAffinity(song.artist, now)
                val recentContext = ListeningEventStore.recentContextAffinity(song.id, context.contextTag, now)

                val trackAffinity = (0.68 * historicalTrack + 0.32 * recentTrack).coerceIn(-1.0, 1.0)
                val artistAffinity = (0.66 * historicalArtist + 0.34 * recentArtist).coerceIn(-1.0, 1.0)
                val contextAffinity = (0.62 * historicalContext + 0.38 * recentContext).coerceIn(-1.0, 1.0)
                val playCount = ListeningProfileStore.playCount(song.id)
                val lastPlayedAt = ListeningProfileStore.lastPlayedAt(song.id)

                val noveltyBoost = if (playCount == 0) {
                    0.30 * exploration
                } else {
                    (1.0 - exploration) * trackAffinity.coerceAtLeast(0.0) * 0.10
                }

                val recencyPenalty = if (!PersonalizationSettings.avoidRepeats.value || lastPlayedAt <= 0L) {
                    0.0
                } else {
                    val age = now - lastPlayedAt
                    when {
                        age < 6L * 60L * 60L * 1000L -> -0.62
                        age < 2L * 24L * 60L * 60L * 1000L -> -0.34
                        age < 7L * 24L * 60L * 60L * 1000L -> -0.16
                        else -> 0.0
                    }
                }

                val queueRepeatPenalty = if (song.id in recentIds) -0.78 else 0.0
                val artistKey = artistKey(song.artist)
                val albumKey = normalize(song.album)
                val artistOccurrences = recentArtists[artistKey] ?: 0
                val albumOccurrences = recentAlbums[albumKey] ?: 0
                val immediateArtistPenalty = if (
                    PersonalizationSettings.avoidRepeats.value &&
                    artistKey.isNotBlank() && artistKey == lastArtist
                ) -0.34 else 0.0
                val artistFatigue = if (PersonalizationSettings.avoidRepeats.value) {
                    when (artistOccurrences) {
                        0 -> 0.0
                        1 -> -0.12
                        2 -> -0.28
                        else -> -0.44
                    }
                } else 0.0
                val albumFatigue = if (
                    PersonalizationSettings.avoidRepeats.value && albumKey.isNotBlank()
                ) {
                    -0.10 * albumOccurrences.coerceAtMost(3)
                } else 0.0

                val explicitArtistPenalty = if (artistAffinity <= -0.30) {
                    (artistAffinity * 0.48).coerceAtLeast(-0.48)
                } else 0.0
                // V3: piu' skip manuali in poche ore non equivalgono a una
                // preferenza storica negativa permanente. Introducono invece
                // un raffreddamento deciso per 24h, superabile solo quando
                // mancano alternative adeguate.
                val recentSongSkips = recentRejections?.bySong?.get(song.id) ?: 0
                val recentArtistSkips = recentRejections?.byArtist?.get(artistKey) ?: 0
                val recentRejectionPenalty = when {
                    recentSongSkips >= 2 -> -1.40
                    recentSongSkips == 1 -> -0.65
                    else -> 0.0
                } + when {
                    recentArtistSkips >= 3 -> -1.10
                    recentArtistSkips == 2 -> -0.70
                    recentArtistSkips == 1 -> -0.20
                    else -> 0.0
                }
                val sessionArtistPenalty = if (artistKey in context.avoidArtists) {
                    -0.46 - 0.09 * context.diversificationLevel.coerceAtMost(3)
                } else 0.0
                val sessionArtistBoost = if (artistKey in context.positiveArtists) 0.10 else 0.0

                // Variabilita' deterministica: stessa sessione = decisioni stabili.
                val jitterSeed = (song.id.hashCode().toLong() xor context.sessionSalt).absoluteValue
                val jitterUnit = (jitterSeed % 10_000L).toDouble() / 10_000.0 - 0.5
                val explorationJitter = jitterUnit * (0.22 * exploration)

                val sourceWeight = (0.31 - 0.05 * context.diversificationLevel.coerceAtMost(3))
                    .coerceAtLeast(0.15)
                val score =
                    sourceWeight * sourcePosition +
                        0.29 * trackAffinity +
                        0.18 * artistAffinity +
                        0.12 * contextAffinity +
                        noveltyBoost +
                        recencyPenalty +
                        queueRepeatPenalty +
                        immediateArtistPenalty +
                        artistFatigue +
                        albumFatigue +
                        explicitArtistPenalty +
                        recentRejectionPenalty +
                        sessionArtistPenalty +
                        sessionArtistBoost +
                        explorationJitter

                ScoredCandidate(song, score)
            }
            .sortedByDescending { it.score }
            .toList()

        val cooledArtists = context.avoidArtists + recentRejections.orEmptyAvoidedArtists()
        return spreadRepeatedArtists(applySessionArtistCooldown(applyExplorationQuota(scored, exploration), cooledArtists))
    }

    private fun ListeningEventStore.RecentRejections?.orEmptyAvoidedArtists(): Set<String> =
        this?.byArtist?.filterValues { it >= 2 }?.keys.orEmpty()

    /**
     * Inserisce una quota minima di brani mai ascoltati, ma solo tra candidati
     * che hanno gia' superato i filtri e il ranking. La modalita' Conservativa
     * resta quindi principalmente familiare, quella Esplora aumenta gradualmente
     * la frequenza delle novita'.
     */
    private fun applyExplorationQuota(
        scored: List<ScoredCandidate>,
        exploration: Double,
    ): List<Song> {
        if (scored.size < 4) return scored.map { it.song }
        val interval = when {
            exploration >= 0.34 -> 3
            exploration >= 0.24 -> 4
            exploration >= 0.14 -> 5
            else -> return scored.map { it.song }
        }
        val remaining = scored.toMutableList()
        val result = ArrayList<Song>(scored.size)
        var position = 1
        while (remaining.isNotEmpty()) {
            val pickIndex = if (position % interval == 0) {
                // La novita' deve comunque essere competitiva: cerchiamo solo
                // nella fascia alta del ranking, non in fondo alla lista.
                remaining.take(8)
                    .indexOfFirst { ListeningProfileStore.playCount(it.song.id) == 0 }
                    .takeIf { it >= 0 } ?: 0
            } else {
                0
            }
            result += remaining.removeAt(pickIndex).song
            position += 1
        }
        return result
    }

    /**
     * Dopo uno skip precoce l artista viene raffreddato per la sessione.
     * Rimane disponibile solo come fallback se mancano alternative.
     */
    private fun applySessionArtistCooldown(
        songs: List<Song>,
        avoidArtists: Set<String>,
    ): List<Song> {
        if (avoidArtists.isEmpty() || songs.size < 2) return songs

        val avoided = avoidArtists
            .map(::artistKey)
            .filter { it.isNotBlank() }
            .toSet()

        if (avoided.isEmpty()) return songs

        val alternatives = songs.filter {
            artistKey(it.artist) !in avoided
        }

        if (alternatives.isEmpty()) return songs

        val cooled = songs.filter {
            artistKey(it.artist) in avoided
        }

        return alternatives + cooled
    }

    /**
     * Evita due brani consecutivi dello stesso artista
     * quando esistono alternative competitive.
     */
    private fun spreadRepeatedArtists(
        songs: List<Song>,
    ): List<Song> {
        if (songs.size < 2) return songs
        val remaining = songs.toMutableList()
        val result = ArrayList<Song>(songs.size)
        while (remaining.isNotEmpty()) {
            val previous = result.takeLast(2).map { artistKey(it.artist) }.toSet()
            // Diversificazione entro i prossimi 8 candidati: non sacrifica
            // l'intero ranking per cercare un artista in fondo alla coda.
            val window = remaining.take(8)
            val candidate = window.indexOfFirst {
                artistKey(it.artist).let { artist -> artist.isNotBlank() && artist !in previous }
            }.takeIf { it >= 0 } ?: window.indexOfFirst {
                artistKey(it.artist) != result.lastOrNull()?.artist?.let(::artistKey)
            }.takeIf { it >= 0 } ?: 0
            result += remaining.removeAt(candidate)
        }
        return result
    }

    /** Stesso artista principale anche quando YouTube aggiunge feat./duetti. */
    fun artistKey(value: String?): String = value.orEmpty()
        .trim()
        .lowercase()
        .split(Regex("\\s+(?:feat\\.?|featuring|ft\\.?|with|x)\\s+|\\s*[,&]\\s*"), limit = 2)
        .firstOrNull()
        .orEmpty()
        .trim()

    private fun normalize(value: String?): String = value.orEmpty().trim().lowercase()
}
