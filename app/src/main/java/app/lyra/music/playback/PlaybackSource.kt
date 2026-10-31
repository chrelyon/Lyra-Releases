package app.lyra.music.playback

/**
 * Risultato della risoluzione di una sorgente audio.
 *
 * Tenere questi metadati separati dall'URL e' importante: in futuro il
 * resolver potra' gestire cipher/SABR, cache con scadenza e retry senza
 * modificare il motore Media3.
 */
data class PlaybackSource(
    val videoId: String,
    val url: String,
    val mimeType: String?,
    val bitrate: Int?,
    val clientName: String,
    val expiresInSeconds: Int?,
    val loudnessDb: Double? = null,
)
