package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Song
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

enum class PlaylistSearchKind { ARTIST, ALBUM }

data class PlaylistSearchTarget(
    val id: String,
    val title: String,
    val kind: PlaylistSearchKind,
    val subtitle: String,
    val thumbnail: String? = null,
)

class AdvancedPlaylistSearchRepository {
    /** Un solo campo: artisti e album nella stessa lista di completamento. */
    suspend fun suggestions(query: String): List<PlaylistSearchTarget> = coroutineScope {
        val artists = async { runCatching { search(query, PlaylistSearchKind.ARTIST) } }
        val albums = async { runCatching { search(query, PlaylistSearchKind.ALBUM) } }
        val (artistResult, albumResult) = artists.await() to albums.await()
        if (artistResult.isFailure && albumResult.isFailure) throw artistResult.exceptionOrNull()!!
        artistResult.getOrDefault(emptyList()).take(4) + albumResult.getOrDefault(emptyList()).take(4)
    }

    private suspend fun search(query: String, kind: PlaylistSearchKind): List<PlaylistSearchTarget> = withTimeout(12_000) {
        val filter = if (kind == PlaylistSearchKind.ARTIST) YouTube.SearchFilter.FILTER_ARTIST else YouTube.SearchFilter.FILTER_ALBUM
        YouTube.search(query = query, filter = filter).getOrThrow().items.mapNotNull { item ->
            when {
                kind == PlaylistSearchKind.ARTIST && item is ArtistItem && !item.isProfile ->
                    PlaylistSearchTarget(item.id, item.title, kind, "Artista", item.thumbnail)
                kind == PlaylistSearchKind.ALBUM && item is AlbumItem ->
                    PlaylistSearchTarget(item.browseId, item.title, kind,
                        listOfNotNull("Album", item.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }, item.year?.toString()).joinToString(" · "), item.thumbnail)
                else -> null
            }
        }.distinctBy { it.id }
    }

    suspend fun albumSongs(browseId: String): List<Song> = withTimeout(15_000) {
        val page = YouTube.album(browseId, withSongs = true).getOrThrow()
        check(page.album.browseId == browseId) { "Album restituito diverso da quello selezionato" }
        page.songs.map { item ->
            Song(item.id, item.title, item.artists.joinToString(", ") { it.name },
                item.duration?.toLong() ?: 0L, item.thumbnail, album = page.album.title)
        }.distinctBy { it.id }.also { check(it.isNotEmpty()) { "Nessun brano disponibile per questo album" } }
    }
}
