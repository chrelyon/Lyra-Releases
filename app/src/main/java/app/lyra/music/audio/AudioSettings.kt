package app.lyra.music.audio

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferenze audio persistenti di Lyra. */
object AudioSettings {

    enum class StreamingQuality { AUTOMATIC, HIGH, DATA_SAVER }
    enum class NormalizationMode { TRACK, ALBUM, AUTOMATIC }

    private const val PREFS = "lyra_audio_settings"
    private const val KEY_NORMALIZATION = "normalization_enabled"
    private const val KEY_STREAMING_NORMALIZATION = "streaming_normalization_enabled"
    private const val KEY_LOCAL_NORMALIZATION = "local_normalization_enabled"
    private const val KEY_PREFER_REPLAY_GAIN = "prefer_replay_gain"
    private const val KEY_R128_ANALYSIS = "r128_analysis_enabled"
    private const val KEY_TRUE_PEAK = "true_peak_protection"
    private const val KEY_NORMALIZATION_MODE = "normalization_mode"
    private const val KEY_TARGET_LUFS = "target_lufs"
    private const val KEY_MAX_GAIN_DB = "max_gain_db"
    private const val KEY_MAX_ATTENUATION_DB = "max_attenuation_db"
    private const val KEY_BACKGROUND_SCAN = "background_scan_enabled"
    private const val KEY_SCAN_CHARGING = "scan_only_while_charging"
    private const val KEY_STREAMING_QUALITY = "streaming_quality"
    private const val KEY_PREFETCH_SOURCES = "prefetch_sources_enabled"
    private const val KEY_ADAPTIVE_BUFFERING = "adaptive_buffering_enabled"
    private const val KEY_GAPLESS = "gapless_enabled"
    private const val KEY_CROSSFADE = "crossfade_enabled"
    private const val KEY_CROSSFADE_DURATION = "crossfade_duration_ms"
    private const val KEY_PRELOAD_AUDIO = "preload_audio_enabled"

    private lateinit var appContext: Context

    private val _normalizationEnabled = MutableStateFlow(false)
    val normalizationEnabled: StateFlow<Boolean> = _normalizationEnabled.asStateFlow()
    private val _streamingNormalizationEnabled = MutableStateFlow(true)
    val streamingNormalizationEnabled: StateFlow<Boolean> = _streamingNormalizationEnabled.asStateFlow()
    private val _localNormalizationEnabled = MutableStateFlow(true)
    val localNormalizationEnabled: StateFlow<Boolean> = _localNormalizationEnabled.asStateFlow()
    private val _preferReplayGain = MutableStateFlow(true)
    val preferReplayGain: StateFlow<Boolean> = _preferReplayGain.asStateFlow()
    private val _r128AnalysisEnabled = MutableStateFlow(true)
    val r128AnalysisEnabled: StateFlow<Boolean> = _r128AnalysisEnabled.asStateFlow()
    private val _truePeakProtection = MutableStateFlow(true)
    val truePeakProtection: StateFlow<Boolean> = _truePeakProtection.asStateFlow()
    private val _normalizationMode = MutableStateFlow(NormalizationMode.AUTOMATIC)
    val normalizationMode: StateFlow<NormalizationMode> = _normalizationMode.asStateFlow()
    private val _targetLufs = MutableStateFlow(-14.0)
    val targetLufs: StateFlow<Double> = _targetLufs.asStateFlow()
    private val _maxGainDb = MutableStateFlow(6.0)
    val maxGainDb: StateFlow<Double> = _maxGainDb.asStateFlow()
    private val _maxAttenuationDb = MutableStateFlow(8.0)
    val maxAttenuationDb: StateFlow<Double> = _maxAttenuationDb.asStateFlow()
    private val _backgroundScanEnabled = MutableStateFlow(true)
    val backgroundScanEnabled: StateFlow<Boolean> = _backgroundScanEnabled.asStateFlow()
    private val _scanOnlyWhileCharging = MutableStateFlow(false)
    val scanOnlyWhileCharging: StateFlow<Boolean> = _scanOnlyWhileCharging.asStateFlow()
    private val _streamingQuality = MutableStateFlow(StreamingQuality.HIGH)
    val streamingQuality: StateFlow<StreamingQuality> = _streamingQuality.asStateFlow()
    private val _prefetchSourcesEnabled = MutableStateFlow(true)
    val prefetchSourcesEnabled: StateFlow<Boolean> = _prefetchSourcesEnabled.asStateFlow()
    private val _adaptiveBufferingEnabled = MutableStateFlow(true)
    val adaptiveBufferingEnabled: StateFlow<Boolean> = _adaptiveBufferingEnabled.asStateFlow()

    private val _gaplessEnabled = MutableStateFlow(true)
    val gaplessEnabled: StateFlow<Boolean> = _gaplessEnabled.asStateFlow()
    private val _crossfadeEnabled = MutableStateFlow(false)
    val crossfadeEnabled: StateFlow<Boolean> = _crossfadeEnabled.asStateFlow()
    private val _crossfadeDurationMs = MutableStateFlow(4_000)
    val crossfadeDurationMs: StateFlow<Int> = _crossfadeDurationMs.asStateFlow()
    private val _preloadAudioEnabled = MutableStateFlow(true)
    val preloadAudioEnabled: StateFlow<Boolean> = _preloadAudioEnabled.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _normalizationEnabled.value = prefs.getBoolean(KEY_NORMALIZATION, false)
        _streamingNormalizationEnabled.value = prefs.getBoolean(KEY_STREAMING_NORMALIZATION, true)
        _localNormalizationEnabled.value = prefs.getBoolean(KEY_LOCAL_NORMALIZATION, true)
        _preferReplayGain.value = prefs.getBoolean(KEY_PREFER_REPLAY_GAIN, true)
        _r128AnalysisEnabled.value = prefs.getBoolean(KEY_R128_ANALYSIS, true)
        _truePeakProtection.value = prefs.getBoolean(KEY_TRUE_PEAK, true)
        _normalizationMode.value = runCatching {
            NormalizationMode.valueOf(prefs.getString(KEY_NORMALIZATION_MODE, NormalizationMode.AUTOMATIC.name)!!)
        }.getOrDefault(NormalizationMode.AUTOMATIC)
        _targetLufs.value = prefs.getFloat(KEY_TARGET_LUFS, -14f).toDouble().coerceIn(-23.0, -9.0)
        _maxGainDb.value = prefs.getFloat(KEY_MAX_GAIN_DB, 6f).toDouble().coerceIn(0.0, 12.0)
        _maxAttenuationDb.value = prefs.getFloat(KEY_MAX_ATTENUATION_DB, 8f).toDouble().coerceIn(0.0, 18.0)
        _backgroundScanEnabled.value = prefs.getBoolean(KEY_BACKGROUND_SCAN, true)
        _scanOnlyWhileCharging.value = prefs.getBoolean(KEY_SCAN_CHARGING, false)
        _prefetchSourcesEnabled.value = prefs.getBoolean(KEY_PREFETCH_SOURCES, true)
        _adaptiveBufferingEnabled.value = prefs.getBoolean(KEY_ADAPTIVE_BUFFERING, true)
        _gaplessEnabled.value = prefs.getBoolean(KEY_GAPLESS, true)
        _crossfadeEnabled.value = prefs.getBoolean(KEY_CROSSFADE, false)
        _crossfadeDurationMs.value = prefs.getInt(KEY_CROSSFADE_DURATION, 4_000).coerceIn(2_000, 8_000)
        _preloadAudioEnabled.value = prefs.getBoolean(KEY_PRELOAD_AUDIO, true)
        _streamingQuality.value = runCatching {
            StreamingQuality.valueOf(
                prefs.getString(KEY_STREAMING_QUALITY, StreamingQuality.HIGH.name)
                    ?: StreamingQuality.HIGH.name
            )
        }.getOrDefault(StreamingQuality.HIGH)
    }

    fun setNormalizationEnabled(enabled: Boolean) {
        setBoolean(KEY_NORMALIZATION, enabled, _normalizationEnabled)
        reconcileScan()
    }
    fun setStreamingNormalizationEnabled(enabled: Boolean) = setBoolean(KEY_STREAMING_NORMALIZATION, enabled, _streamingNormalizationEnabled)
    fun setLocalNormalizationEnabled(enabled: Boolean) {
        setBoolean(KEY_LOCAL_NORMALIZATION, enabled, _localNormalizationEnabled)
        reconcileScan()
    }
    fun setPreferReplayGain(enabled: Boolean) = setBoolean(KEY_PREFER_REPLAY_GAIN, enabled, _preferReplayGain)
    fun setR128AnalysisEnabled(enabled: Boolean) {
        setBoolean(KEY_R128_ANALYSIS, enabled, _r128AnalysisEnabled)
        reconcileScan()
    }
    fun setTruePeakProtection(enabled: Boolean) = setBoolean(KEY_TRUE_PEAK, enabled, _truePeakProtection)

    fun setNormalizationMode(mode: NormalizationMode) {
        prefs().edit().putString(KEY_NORMALIZATION_MODE, mode.name).apply()
        _normalizationMode.value = mode
    }

    fun setTargetLufs(value: Double) {
        val safe = value.coerceIn(-23.0, -9.0)
        prefs().edit().putFloat(KEY_TARGET_LUFS, safe.toFloat()).apply()
        _targetLufs.value = safe
    }

    fun setMaxGainDb(value: Double) {
        val safe = value.coerceIn(0.0, 12.0)
        prefs().edit().putFloat(KEY_MAX_GAIN_DB, safe.toFloat()).apply()
        _maxGainDb.value = safe
    }

    fun setMaxAttenuationDb(value: Double) {
        val safe = value.coerceIn(0.0, 18.0)
        prefs().edit().putFloat(KEY_MAX_ATTENUATION_DB, safe.toFloat()).apply()
        _maxAttenuationDb.value = safe
    }

    fun setBackgroundScanEnabled(enabled: Boolean) {
        setBoolean(KEY_BACKGROUND_SCAN, enabled, _backgroundScanEnabled)
        reconcileScan()
    }

    fun setScanOnlyWhileCharging(enabled: Boolean) {
        setBoolean(KEY_SCAN_CHARGING, enabled, _scanOnlyWhileCharging)
        reconcileScan()
    }

    fun setPrefetchSourcesEnabled(enabled: Boolean) =
        setBoolean(KEY_PREFETCH_SOURCES, enabled, _prefetchSourcesEnabled)

    fun setAdaptiveBufferingEnabled(enabled: Boolean) =
        setBoolean(KEY_ADAPTIVE_BUFFERING, enabled, _adaptiveBufferingEnabled)

    fun setGaplessEnabled(enabled: Boolean) {
        setBoolean(KEY_GAPLESS, enabled, _gaplessEnabled)
        if (!enabled) setCrossfadeEnabled(false)
    }

    fun setCrossfadeEnabled(enabled: Boolean) {
        if (enabled && !_gaplessEnabled.value) setGaplessEnabled(true)
        setBoolean(KEY_CROSSFADE, enabled, _crossfadeEnabled)
    }

    fun setCrossfadeDurationMs(durationMs: Int) {
        val safe = durationMs.coerceIn(2_000, 8_000)
        prefs().edit().putInt(KEY_CROSSFADE_DURATION, safe).apply()
        _crossfadeDurationMs.value = safe
    }

    fun setPreloadAudioEnabled(enabled: Boolean) =
        setBoolean(KEY_PRELOAD_AUDIO, enabled, _preloadAudioEnabled)

    fun setStreamingQuality(quality: StreamingQuality) {
        prefs().edit().putString(KEY_STREAMING_QUALITY, quality.name).apply()
        _streamingQuality.value = quality
    }

    fun requestLocalScan() {
        ensureInitialized()
        LocalAudioScanWorker.schedule(appContext, force = true)
    }

    fun clearLocalAnalysisCache() {
        LocalLoudnessStore.clear()
        reconcileScan()
    }

    private fun reconcileScan() {
        if (!::appContext.isInitialized) return
        if (_normalizationEnabled.value && _backgroundScanEnabled.value && _localNormalizationEnabled.value && _r128AnalysisEnabled.value) {
            LocalAudioScanWorker.schedule(appContext)
        } else {
            LocalAudioScanWorker.cancel(appContext)
        }
    }

    private fun setBoolean(key: String, enabled: Boolean, flow: MutableStateFlow<Boolean>) {
        prefs().edit().putBoolean(key, enabled).apply()
        flow.value = enabled
    }

    private fun prefs(): android.content.SharedPreferences {
        ensureInitialized()
        return appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun ensureInitialized() {
        check(::appContext.isInitialized) { "AudioSettings non inizializzato" }
    }
}
