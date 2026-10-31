package app.lyra.music.core.model

data class PlaylistContent(
    val id: String,
    val title: String,
    val songs: List<Song>,
)
