package app.lyra.music.playback

import app.lyra.music.core.model.Song

data class PlaybackQueueUiState(
    val title: String = "",
    val songs: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val isChangingTrack: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.OFF,
    val resumePositionMs: Long = 0L,
) {
    val hasQueue: Boolean
        get() = songs.isNotEmpty() && currentIndex in songs.indices

    val hasPrevious: Boolean
        get() = hasQueue && (
            currentIndex > 0 ||
                (repeatMode == PlaybackRepeatMode.ALL && songs.size > 1)
            )

    val hasNext: Boolean
        get() = hasQueue && (
            currentIndex < songs.lastIndex ||
                (repeatMode == PlaybackRepeatMode.ALL && songs.size > 1)
            )

    val currentSong: Song?
        get() = songs.getOrNull(currentIndex)
}
