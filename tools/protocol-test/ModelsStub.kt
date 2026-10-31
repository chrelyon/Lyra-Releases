package com.metrolist.innertube.models

data class WatchEndpoint(val videoId: String)
data class Artist(val name: String)
data class SongItem(val id: String,val title: String,val artists: List<Artist>,val duration: Int?,val thumbnail: String?)
