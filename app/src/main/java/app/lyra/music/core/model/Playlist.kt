package app.lyra.music.core.model

data class Playlist(
    val id: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val songCount: Int? = null
)