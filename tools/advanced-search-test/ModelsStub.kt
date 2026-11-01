package com.metrolist.innertube.models
sealed class YTItem
class Artist(val name: String)
data class ArtistItem(val id: String, val title: String, val isProfile: Boolean = false, val thumbnail: String? = null) : YTItem()
data class AlbumItem(val browseId: String, val title: String, val artists: List<Artist>? = null, val year: Int? = null, val thumbnail: String = "") : YTItem()
data class SongItem(val id: String, val title: String, val artists: List<Artist> = emptyList(), val duration: Int? = null, val thumbnail: String = "") : YTItem()
