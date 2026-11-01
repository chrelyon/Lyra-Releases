package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Song
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
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
    suspend fun suggestions(query: String, kind: PlaylistSearchKind): List<PlaylistSearchTarget> = withTimeout(12_000) {
        val filter = if (kind == PlaylistSearchKind.ARTIST) YouTube.SearchFilter.FILTER_ARTIST else YouTube.SearchFilter.FILTER_ALBUM
        YouTube.search(query = query, filter = filter).getOrThrow().items.mapNotNull { item ->
            when {
                kind == PlaylistSearchKind.ARTIST && item is ArtistItem && !item.isProfile ->
                    PlaylistSearchTarget(item.id, item.title, kind, "Artista", item.thumbnail)
                kind == PlaylistSearchKind.ALBUM && item is AlbumItem ->
                    PlaylistSearchTarget(item.browseId, item.title, kind,
                        listOfNotNull(item.artists?.joinToString(", ") { it.name }?.takeIf { it.isNotBlank() }, item.year?.toString()).joinToString(" · ").ifBlank { "Album" }, item.thumbnail)
                else -> null
            }
        }.distinctBy { it.id }.take(8)
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
