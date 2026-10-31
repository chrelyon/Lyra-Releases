package app.lyra.music.playback

enum class PlaybackRepeatMode {
    OFF,
    ALL,
    ONE;

    fun next(): PlaybackRepeatMode = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}
