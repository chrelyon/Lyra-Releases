package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog

/**
 * Motore locale della sezione Scopri -> Per te.
 * Non usa servizi cloud aggiuntivi: lavora esclusivamente sui segnali salvati da Lyra.
 */
object PersonalizedMixEngine {
    const val DAILY_ID = "daily"
    const val RECENT_FAVORITES_ID = "recent-favorites"
    const val REDISCOVER_ID = "rediscover"
    private const val GENRE_PREFIX = "genre:"

    data class MixSummary(
        val id: String,
        val title: String,
        val subtitle: String,
        val automatic: Boolean,
    )

    fun summaries(): List<MixSummary> = listOf(
        MixSummary(DAILY_ID, "Mix quotidiano", "Radio dai tuoi ascolti migliori", automatic = true),
        MixSummary(RECENT_FAVORITES_ID, "Preferiti recenti", "Canzoni apprezzate di recente", automatic = false),
        MixSummary(REDISCOVER_ID, "Riscoperte", "Brani da riscoprire", automatic = false),
    )

    fun localSongs(mixId: String): List<Song> = when (mixId) {
        RECENT_FAVORITES_ID -> ListeningProfileStore.recentLiked()
        REDISCOVER_ID -> ListeningProfileStore.rediscover()
        else -> emptyList()
    }

    fun automaticSeed(mixId: String): Pair<Song, String>? = when {
        mixId == DAILY_ID -> ListeningProfileStore.topSongs(limit = 20)
            .filterNot { it.isLocal }
            .take(12)
            .let { songs ->
                if (songs.isEmpty()) null
                else {
                    val day = System.currentTimeMillis() / (24L * 60L * 60L * 1000L)
                    songs[(day % songs.size).toInt()] to "Il tuo mix quotidiano"
                }
            }

        mixId.startsWith(GENRE_PREFIX) -> {
            val tag = mixId.removePrefix(GENRE_PREFIX)
            AutomaticPlaylistCatalog.preset(tag)?.let { preset ->
                ListeningProfileStore.bestSeedForTag(tag)
                    ?.let { seed -> seed to "Il tuo ${preset.title}" }
            }
        }

        else -> null
    }
}
