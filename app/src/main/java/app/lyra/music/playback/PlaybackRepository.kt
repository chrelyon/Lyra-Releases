package app.lyra.music.playback

interface PlaybackRepository {

    suspend fun getPlaybackSource(
        videoId: String
    ): PlaybackSource
}
