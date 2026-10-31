package app.lyra.music.core.model

/**
 * Modello unico dei brani Lyra.
 *
 * I brani YouTube hanno [playbackUri] nullo e vengono risolti tramite
 * InnerTube/YouTubePlaybackRepository. I brani locali valorizzano invece
 * [playbackUri] con il content:// di MediaStore e possono essere riprodotti
 * direttamente da Media3 senza passare dal resolver YouTube.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String? = null,
    val playbackUri: String? = null,
    val album: String? = null,
    val albumId: Long? = null,
) {
    val isLocal: Boolean
        get() = !playbackUri.isNullOrBlank()
}
