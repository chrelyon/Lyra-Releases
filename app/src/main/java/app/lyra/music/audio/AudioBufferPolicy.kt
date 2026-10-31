package app.lyra.music.audio

/** Soglie in millisecondi per ExoPlayer; sempre max >= min >= rebuffer. */
internal data class AudioBufferProfile(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val startBufferMs: Int,
    val rebufferMs: Int,
)

/** Profili selezionati quando il player viene creato.
 * Non riconfiguriamo un ExoPlayer attivo durante una chiamata Bluetooth/Auto.
 */
internal object AudioBufferPolicy {
    fun select(adaptive: Boolean, metered: Boolean): AudioBufferProfile = when {
        !adaptive -> AudioBufferProfile(25_000, 120_000, 2_500, 5_000)
        metered -> AudioBufferProfile(14_000, 65_000, 1_800, 3_000)
        else -> AudioBufferProfile(25_000, 120_000, 1_500, 3_500)
    }
}
