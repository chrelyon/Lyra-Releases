package app.lyra.music.core.model

data class YouTubeLibrarySnapshot(
    val channel: YouTubeChannel?,
    val playlists: List<Playlist>,
    val syncedAtEpochMs: Long,
    val accessibleChannelCount: Int = if (channel == null) 0 else 1,
)
