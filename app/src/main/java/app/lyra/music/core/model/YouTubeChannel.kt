package app.lyra.music.core.model

data class YouTubeChannel(
    val id: String,
    val title: String,
    val handle: String? = null,
    val thumbnailUrl: String? = null,
    val likesPlaylistId: String? = null,
)
