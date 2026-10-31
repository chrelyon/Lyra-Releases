package app.lyra.music.playback

/**
 * Stato del player esposto alla UI.
 *
 * La sorgente di verita' resta sempre MediaController/MediaSessionService:
 * Compose osserva questo stato, ma non decide autonomamente se il player
 * sta suonando, quale brano e' caricato o a che punto e' arrivato.
 */
data class PlaybackUiState(
    val connected: Boolean = false,
    val hasMediaItem: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isEnded: Boolean = false,
    val mediaId: String = "",
    val title: String = "",
    val artist: String = "",
    val contextLabel: String = "",
    val artworkUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val mediaItemCount: Int = 0,
    val mediaItemIndex: Int = -1,
)
