package app.lyra.music.core.model

data class DiscoverySnapshot(
    val topSongs: List<Song>,
    val playlists: List<Playlist>,
    val genres: List<DiscoveryGenreSection> = emptyList(),
    val regionCode: String,
)

data class DiscoveryGenreSection(
    val id: String,
    val title: String,
    val playlists: List<Playlist>,
)

data class DiscoveryGroupSummary(
    val id: String,
    val title: String,
    val playlistCount: Int? = null,
)

data class DiscoveryGroupSection(
    val id: String,
    val title: String,
    val playlists: List<Playlist>,
)
