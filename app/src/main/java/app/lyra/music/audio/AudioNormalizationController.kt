package app.lyra.music.audio

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.exoplayer.ExoPlayer
import app.lyra.music.diagnostics.LyraLog as Log
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/** Applica il gain calcolato senza alterare i file originali. */
class AudioNormalizationController(
    private val player: ExoPlayer,
) {
    private enum class Source { NONE, STREAMING, LOCAL }

    private var enhancer: LoudnessEnhancer? = null
    private var audioSessionId: Int = 0
    private var source = Source.NONE
    private var streamingLoudnessDb: Double? = null
    private var localMeasurement: LocalLoudnessStore.Measurement? = null
    private var baseVolume = 1f
    private var transitionMultiplier = 1f

    /** Transizioni audio: preserva il gain ReplayGain/R128 gia' applicato. */
    fun setTransitionMultiplier(multiplier: Float) {
        transitionMultiplier = multiplier.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        player.volume = (baseVolume * transitionMultiplier).coerceIn(0f, 1f)
    }

    fun onAudioSessionIdChanged(newAudioSessionId: Int) {
        if (newAudioSessionId <= 0 || newAudioSessionId == audioSessionId) return
        audioSessionId = newAudioSessionId
        releaseEnhancer()
        enhancer = runCatching { LoudnessEnhancer(newAudioSessionId) }
            .onFailure { error ->
                Log.w("LYRA_AUDIO", "loudnessEnhancerUnavailable message=${error.message}")
            }
            .getOrNull()
        applyCurrent()
    }

    /** YouTube/streaming: il valore e' il delta loudness fornito dal player endpoint. */
    fun onTrackLoudnessChanged(loudnessDb: Double?) {
        source = Source.STREAMING
        streamingLoudnessDb = loudnessDb
        localMeasurement = null
        applyCurrent()
    }

    /** File locale: usa ReplayGain quando disponibile, altrimenti LUFS R128. */
    fun onLocalTrackMeasurement(measurement: LocalLoudnessStore.Measurement?) {
        source = Source.LOCAL
        localMeasurement = measurement
        streamingLoudnessDb = null
        applyCurrent()
    }

    fun onNormalizationSettingChanged() = applyCurrent()

    fun resetForTrackWithoutLoudness() {
        source = Source.NONE
        streamingLoudnessDb = null
        localMeasurement = null
        resetProcessing()
    }

    fun release() {
        resetProcessing()
        releaseEnhancer()
    }

    private fun applyCurrent() {
        if (!AudioSettings.normalizationEnabled.value) {
            resetProcessing()
            return
        }

        val requestedGainDb = when (source) {
            Source.STREAMING -> {
                if (!AudioSettings.streamingNormalizationEnabled.value) null
                else streamingLoudnessDb?.takeIf { it.isFinite() }?.let { -it }
            }
            Source.LOCAL -> {
                if (!AudioSettings.localNormalizationEnabled.value) null
                else localRequestedGain(localMeasurement)
            }
            Source.NONE -> null
        } ?: run {
            resetProcessing()
            return
        }

        val minGain = -AudioSettings.maxAttenuationDb.value
        val maxGain = AudioSettings.maxGainDb.value
        var safeGain = requestedGainDb.coerceIn(minGain, maxGain)

        if (source == Source.LOCAL && AudioSettings.truePeakProtection.value && safeGain > 0.0) {
            val peakDbfs = localPeakDbfs(localMeasurement)
            if (peakDbfs != null) {
                safeGain = minOf(safeGain, (TRUE_PEAK_CEILING_DBFS - peakDbfs).coerceAtLeast(0.0))
            }
        }

        if (abs(safeGain) < DEAD_BAND_DB) {
            resetProcessing()
            return
        }

        if (safeGain < 0.0) {
            enhancer?.setEnabled(false)
            baseVolume = 10.0.pow(safeGain / 20.0).toFloat()
            applyVolume()
        } else {
            baseVolume = 1f
            applyVolume()
            val targetGainMb = (safeGain * 100.0).toInt()
            enhancer?.let { effect ->
                runCatching {
                    effect.setTargetGain(targetGainMb)
                    effect.setEnabled(targetGainMb > 0)
                }.onFailure { error ->
                    Log.w("LYRA_AUDIO", "normalizationApplyFailed message=${error.message}")
                }
            }
        }

        Log.d(
            "LYRA_AUDIO",
            "normalization source=$source requestedDb=$requestedGainDb appliedDb=$safeGain " +
                "targetLufs=${AudioSettings.targetLufs.value}"
        )
    }

    private fun localRequestedGain(measurement: LocalLoudnessStore.Measurement?): Double? {
        measurement ?: return null
        if (AudioSettings.preferReplayGain.value) {
            measurement.replayGainDb?.takeIf { it.isFinite() }?.let { return it }
        }
        val lufs = measurement.integratedLufs?.takeIf { it.isFinite() } ?: return null
        return AudioSettings.targetLufs.value - lufs
    }

    private fun localPeakDbfs(measurement: LocalLoudnessStore.Measurement?): Double? {
        measurement ?: return null
        measurement.truePeakDbfs?.takeIf { it.isFinite() }?.let { return it }
        val replayPeak = measurement.replayPeak?.takeIf { it > 0.0 && it.isFinite() } ?: return null
        return 20.0 * log10(replayPeak)
    }

    private fun resetProcessing() {
        baseVolume = 1f
        applyVolume()
        enhancer?.let { effect ->
            runCatching {
                effect.setEnabled(false)
                effect.setTargetGain(0)
            }
        }
    }

    private fun releaseEnhancer() {
        enhancer?.let { effect -> runCatching { effect.release() } }
        enhancer = null
    }

    private companion object {
        const val DEAD_BAND_DB = 0.5
        const val TRUE_PEAK_CEILING_DBFS = -1.0
    }
}
