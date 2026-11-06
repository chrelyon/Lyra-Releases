package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import app.lyra.music.playback.AutomaticPlaylistManager
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.likes.YouTubeLikesManager
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Mix per me: circa il 70% di brani molto ascoltati e dei Preferiti Google,
 * circa il 30% di proposte nuove della radio YouTube Music generata da un brano
 * apprezzato, quindi coerente con le preferenze dell'utente.
 */
object PersonalizedMixEngine {
    const val TITLE = "Mix per me"
    private const val QUEUE_SIZE = 40
    private const val FAVORITES_TIMEOUT_MS = 10_000L
    private const val KNOWN_SHARE = 0.7

    private suspend fun googleFavorites(): List<Song> {
        val id = YouTubeLibraryCache.favoritesPlaylistId() ?: return emptyList()
        val songs = runCatching {
            withTimeoutOrNull(FAVORITES_TIMEOUT_MS) { YouTubeMusicRepository().getPlaylist(id).songs }
        }.getOrNull().orEmpty()
        return YouTubeLikesManager.applyRecentChanges(songs)
    }

    /** Brani noti: alternati tra ascolti a punteggio alto e Preferiti, senza duplicati. */
    private suspend fun knownPool(): List<Song> {
        val top = ListeningProfileStore.topSongs(limit = 60).filterNot { it.isLocal }.shuffled()
        val favorites = googleFavorites().filterNot { it.isLocal }.shuffled()
        return buildList {
            for (i in 0 until maxOf(top.size, favorites.size)) {
                top.getOrNull(i)?.let(::add)
                favorites.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }
    }

    suspend fun build(): AutomaticPlaylistManager.StartResult {
        val pool = knownPool()
        if (pool.isEmpty()) {
            throw IllegalStateException("Ascolta o salva qualche brano tra i Preferiti per creare il Mix per me")
        }
        val knownCount = (QUEUE_SIZE * KNOWN_SHARE).toInt()
        val known = pool.take(knownCount)
        val knownIds = pool.mapTo(HashSet()) { it.id }

        AutomaticPlaylistManager.clear()
        val fresh = runCatching {
            AutomaticPlaylistManager.startFromSeed(known.random(), TITLE).songs
        }.getOrDefault(emptyList())
            .filter { it.id !in knownIds }
            .take(QUEUE_SIZE - known.size)

        // Una proposta nuova ogni tre brani noti circa, per mantenere il 70/30 lungo tutta la coda.
        val queue = ArrayList<Song>(known.size + fresh.size)
        val freshIterator = fresh.iterator()
        known.forEachIndexed { index, song ->
            queue.add(song)
            if ((index + 1) % 2 == 0 && freshIterator.hasNext()) queue.add(freshIterator.next())
        }
        freshIterator.forEachRemaining(queue::add)
        return AutomaticPlaylistManager.StartResult(TITLE, queue.distinctBy { it.id })
    }
}
