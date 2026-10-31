package app.lyra.music.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Analizzatore locale orientato EBU R128.
 *
 * Decodifica in PCM tramite MediaCodec, applica il filtro K-weighting e usa
 * blocchi da 400 ms con passo 100 ms, gate assoluto -70 LUFS e gate relativo
 * -10 LU. Il true peak e' stimato in modo conservativo dal sample peak con
 * 1 dB di headroom addizionale: evita di dichiarare una precisione dBTP che
 * richiederebbe un oversampler dedicato.
 */
class LocalLoudnessAnalyzer(private val context: Context) {

    suspend fun analyze(song: Song): LocalLoudnessStore.Measurement? = withContext(Dispatchers.IO) {
        val uri = song.playbackUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
            ?: return@withContext null
        runCatching { analyzeBlocking(song, uri) }
            .onFailure { error ->
                Log.w("LYRA_AUDIO", "localAnalysisFailed id=${song.id} message=${error.message}")
            }
            .getOrNull()
    }

    private fun analyzeBlocking(song: Song, uri: Uri): LocalLoudnessStore.Measurement {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("Nessuna traccia audio")
            val inputFormat = extractor.getTrackFormat(trackIndex)
            extractor.selectTrack(trackIndex)

            val replayGainDb = readDbTag(inputFormat, REPLAY_GAIN_KEYS)
            val replayPeak = readPlainTag(inputFormat, REPLAY_PEAK_KEYS)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: error("MIME audio assente")
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var sampleRate = inputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var channelCount = inputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var meter = R128Meter(sampleRate, channelCount)
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)
                        val size = if (buffer != null) extractor.readSampleData(buffer, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outputIndex >= 0 -> {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            meter.consume(output.slice().order(ByteOrder.nativeOrder()), pcmEncoding)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channelCount = outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channelCount
                        pcmEncoding = outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                            ?: AudioFormat.ENCODING_PCM_16BIT
                        meter = R128Meter(sampleRate, channelCount)
                    }
                }
            }

            val result = meter.finish()
            return LocalLoudnessStore.Measurement(
                songId = song.id,
                playbackUri = song.playbackUri.orEmpty(),
                albumId = song.albumId,
                durationSeconds = song.durationSeconds,
                integratedLufs = result.integratedLufs,
                truePeakDbfs = result.estimatedTruePeakDbfs,
                replayGainDb = replayGainDb,
                replayPeak = replayPeak,
                analyzedAtMs = System.currentTimeMillis(),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun readDbTag(format: MediaFormat, keys: List<String>): Double? = keys.firstNotNullOfOrNull { key ->
        if (!format.containsKey(key)) return@firstNotNullOfOrNull null
        runCatching { format.getString(key) }
            .getOrNull()
            ?.let(DB_VALUE_REGEX::find)
            ?.groupValues
            ?.getOrNull(1)
            ?.toDoubleOrNull()
    }

    private fun readPlainTag(format: MediaFormat, keys: List<String>): Double? = keys.firstNotNullOfOrNull { key ->
        if (!format.containsKey(key)) return@firstNotNullOfOrNull null
        runCatching { format.getString(key) }
            .getOrNull()
            ?.let(NUMBER_REGEX::find)
            ?.value
            ?.toDoubleOrNull()
    }

    private data class Result(val integratedLufs: Double?, val estimatedTruePeakDbfs: Double?)

    private class R128Meter(sampleRate: Int, channelCount: Int) {
        private val channels = channelCount.coerceAtLeast(1)
        private val bucketFrames = (sampleRate / 10).coerceAtLeast(1)
        private val filters = Array(channels) { KWeighting(sampleRate.toDouble()) }
        private val bucketEnergies = mutableListOf<Double>()
        private var bucketEnergy = 0.0
        private var bucketFrameCount = 0
        private var channelCursor = 0
        private var currentFrameEnergy = 0.0
        private var samplePeak = 0.0

        fun consume(buffer: ByteBuffer, encoding: Int) {
            when (encoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> while (buffer.remaining() >= 4) accept(buffer.getFloat().toDouble())
                AudioFormat.ENCODING_PCM_8BIT -> while (buffer.hasRemaining()) {
                    accept(((buffer.get().toInt() and 0xff) - 128) / 128.0)
                }
                else -> while (buffer.remaining() >= 2) accept(buffer.getShort() / 32768.0)
            }
        }

        private fun accept(raw: Double) {
            val sample = raw.coerceIn(-1.0, 1.0)
            samplePeak = maxOf(samplePeak, kotlin.math.abs(sample))
            val filtered = filters[channelCursor].process(sample)
            currentFrameEnergy += filtered * filtered
            channelCursor += 1
            if (channelCursor >= channels) {
                bucketEnergy += currentFrameEnergy / channels.toDouble()
                currentFrameEnergy = 0.0
                channelCursor = 0
                bucketFrameCount += 1
                if (bucketFrameCount >= bucketFrames) flushBucket()
            }
        }

        private fun flushBucket() {
            if (bucketFrameCount > 0) {
                bucketEnergies += bucketEnergy / bucketFrameCount.toDouble()
            }
            bucketEnergy = 0.0
            bucketFrameCount = 0
        }

        fun finish(): Result {
            flushBucket()
            val blockEnergies = if (bucketEnergies.size >= 4) {
                (3 until bucketEnergies.size).map { index ->
                    (bucketEnergies[index] + bucketEnergies[index - 1] +
                        bucketEnergies[index - 2] + bucketEnergies[index - 3]) / 4.0
                }
            } else bucketEnergies

            val absoluteGated = blockEnergies.filter { energy -> loudness(energy) >= -70.0 }
            val ungated = integrated(absoluteGated)
            val relativeThreshold = ungated?.minus(10.0)
            val finalBlocks = if (relativeThreshold != null) {
                absoluteGated.filter { energy -> loudness(energy) >= relativeThreshold }
            } else emptyList()
            val integratedLufs = integrated(finalBlocks.ifEmpty { absoluteGated })
            val samplePeakDbfs = if (samplePeak > 0.0) 20.0 * log10(samplePeak) else null
            // +1 dB e' un margine conservativo per possibili picchi inter-sample.
            val estimatedTruePeak = samplePeakDbfs?.let { (it + 1.0).coerceAtMost(0.0) }
            return Result(integratedLufs, estimatedTruePeak)
        }

        private fun integrated(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val mean = values.average()
            return if (mean > 0.0) loudness(mean) else null
        }

        private fun loudness(energy: Double): Double = if (energy > 0.0) {
            -0.691 + 10.0 * log10(energy)
        } else -120.0
    }

    /** K-weighting: high-shelf + high-pass con parametri BS.1770. */
    private class KWeighting(sampleRate: Double) {
        private val shelf = Biquad.highShelf(sampleRate, 1681.974450955533, 3.999843853973347, 0.7071752369554196)
        private val highPass = Biquad.highPass(sampleRate, 38.13547087602444, 0.5003270373238773)
        fun process(sample: Double): Double = highPass.process(shelf.process(sample))
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun process(x0: Double): Double {
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            return y0
        }

        companion object {
            fun highPass(fs: Double, f0: Double, q: Double): Biquad {
                val w0 = 2.0 * PI * f0 / fs
                val alpha = sin(w0) / (2.0 * q)
                val a0 = 1.0 + alpha
                return Biquad(
                    ((1.0 + cos(w0)) / 2.0) / a0,
                    (-(1.0 + cos(w0))) / a0,
                    ((1.0 + cos(w0)) / 2.0) / a0,
                    (-2.0 * cos(w0)) / a0,
                    (1.0 - alpha) / a0,
                )
            }

            fun highShelf(fs: Double, f0: Double, gainDb: Double, q: Double): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2.0 * PI * f0 / fs
                val alpha = sin(w0) / (2.0 * q)
                val sqrtA = sqrt(a)
                val cosW = cos(w0)
                val b0 = a * ((a + 1) + (a - 1) * cosW + 2 * sqrtA * alpha)
                val b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                val b2 = a * ((a + 1) + (a - 1) * cosW - 2 * sqrtA * alpha)
                val a0 = (a + 1) - (a - 1) * cosW + 2 * sqrtA * alpha
                val a1 = 2 * ((a - 1) - (a + 1) * cosW)
                val a2 = (a + 1) - (a - 1) * cosW - 2 * sqrtA * alpha
                return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private companion object {
        const val TIMEOUT_US = 10_000L
        val DB_VALUE_REGEX = Regex("([+-]?[0-9]+(?:\\.[0-9]+)?)\\s*dB", RegexOption.IGNORE_CASE)
        val NUMBER_REGEX = Regex("[0-9]+(?:\\.[0-9]+)?")
        val REPLAY_GAIN_KEYS = listOf(
            "replaygain-track-gain", "replaygain_track_gain", "REPLAYGAIN_TRACK_GAIN",
            "replay-gain-track-gain",
        )
        val REPLAY_PEAK_KEYS = listOf(
            "replaygain-track-peak", "replaygain_track_peak", "REPLAYGAIN_TRACK_PEAK",
            "replay-gain-track-peak",
        )
    }
}
