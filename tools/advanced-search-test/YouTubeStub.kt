package com.metrolist.innertube
import com.metrolist.innertube.models.*
object YouTube {
    object SearchFilter { const val FILTER_ARTIST = "artist"; const val FILTER_ALBUM = "album" }
    data class SearchPage(val items: List<YTItem>)
    data class AlbumPage(val album: AlbumItem, val songs: List<SongItem>)
    var searchItems: List<YTItem> = emptyList()
    var albumPage = AlbumPage(AlbumItem("a", "Album"), emptyList())
    var lastFilter = ""
    suspend fun search(query: String, filter: String): Result<SearchPage> { lastFilter=filter;return Result.success(SearchPage(searchItems)) }
    suspend fun album(browseId: String, withSongs: Boolean): Result<AlbumPage> = Result.success(albumPage)
}
