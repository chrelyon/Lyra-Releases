package app.lyra.music.audio

import android.content.Context
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.log10
import kotlin.math.pow

/** Cache persistente dei risultati di analisi dei file audio locali. */
object LocalLoudnessStore {
    data class Measurement(
        val songId: String,
        val playbackUri: String,
        val albumId: Long?,
        val durationSeconds: Long,
        val integratedLufs: Double?,
        val truePeakDbfs: Double?,
        val replayGainDb: Double?,
        val replayPeak: Double?,
        val analyzedAtMs: Long,
    )

    data class ScanStatus(
        val running: Boolean = false,
        val analyzed: Int = 0,
        val total: Int = 0,
        val currentTitle: String? = null,
        val lastError: String? = null,
    ) {
        val pending: Int get() = (total - analyzed).coerceAtLeast(0)
    }

    private const val PREFS = "lyra_local_loudness"
    private const val KEY_RECORDS = "records_json"
    private const val MAX_RECORDS = 4000

    private lateinit var appContext: Context
    private val records = LinkedHashMap<String, Measurement>()
    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        load()
        _status.value = _status.value.copy(analyzed = records.size)
    }

    @Synchronized
    fun get(song: Song): Measurement? {
        val record = records[song.id] ?: return null
        return record.takeIf { it.playbackUri == song.playbackUri.orEmpty() }
    }

    @Synchronized
    fun hasFreshMeasurement(song: Song): Boolean = get(song) != null

    @Synchronized
    fun albumMeasurement(song: Song): Measurement? {
        val albumId = song.albumId ?: return null
        val albumRecords = records.values.filter {
            it.albumId == albumId && it.integratedLufs != null
        }
        if (albumRecords.isEmpty()) return null

        var weightedEnergy = 0.0
        var totalWeight = 0.0
        var peak: Double? = null
        albumRecords.forEach { record ->
            val lufs = record.integratedLufs ?: return@forEach
            val weight = record.durationSeconds.coerceAtLeast(1L).toDouble()
            val energy = 10.0.pow((lufs + 0.691) / 10.0)
            weightedEnergy += energy * weight
            totalWeight += weight
            val candidatePeak = record.truePeakDbfs
            if (candidatePeak != null && (peak == null || candidatePeak > peak)) peak = candidatePeak
        }
        if (totalWeight <= 0.0 || weightedEnergy <= 0.0) return null
        val albumLufs = -0.691 + 10.0 * log10(weightedEnergy / totalWeight)
        return Measurement(
            songId = song.id,
            playbackUri = song.playbackUri.orEmpty(),
            albumId = albumId,
            durationSeconds = albumRecords.sumOf { it.durationSeconds.coerceAtLeast(1L) },
            integratedLufs = albumLufs,
            truePeakDbfs = peak,
            replayGainDb = null,
            replayPeak = null,
            analyzedAtMs = albumRecords.maxOfOrNull { it.analyzedAtMs } ?: 0L,
        )
    }

    @Synchronized
    fun put(song: Song, measurement: Measurement) {
        records[song.id] = measurement
        while (records.size > MAX_RECORDS) {
            records.remove(records.entries.first().key)
        }
        save()
        val current = _status.value
        _status.value = current.copy(analyzed = records.size)
    }

    @Synchronized
    fun clear() {
        records.clear()
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORDS).apply()
        }
        _status.value = ScanStatus()
    }

    fun updateScanStatus(
        running: Boolean,
        analyzed: Int,
        total: Int,
        currentTitle: String? = null,
        lastError: String? = null,
    ) {
        _status.value = ScanStatus(running, analyzed, total, currentTitle, lastError)
    }

    @Synchronized
    fun cachedCount(): Int = records.size

    private fun load() {
        records.clear()
        val raw = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return
        runCatching {
            val array = JSONArray(raw)
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val id = obj.optString("songId")
                val uri = obj.optString("playbackUri")
                if (id.isBlank() || uri.isBlank()) continue
                records[id] = Measurement(
                    songId = id,
                    playbackUri = uri,
                    albumId = obj.optLongOrNull("albumId"),
                    durationSeconds = obj.optLong("durationSeconds", 0L),
                    integratedLufs = obj.optDoubleOrNull("integratedLufs"),
                    truePeakDbfs = obj.optDoubleOrNull("truePeakDbfs"),
                    replayGainDb = obj.optDoubleOrNull("replayGainDb"),
                    replayPeak = obj.optDoubleOrNull("replayPeak"),
                    analyzedAtMs = obj.optLong("analyzedAtMs", 0L),
                )
            }
        }.onFailure { error ->
            Log.w("LYRA_AUDIO", "localLoudnessCacheReadFailed message=${error.message}")
            records.clear()
        }
    }

    private fun save() {
        if (!::appContext.isInitialized) return
        val array = JSONArray()
        records.values.forEach { record ->
            array.put(JSONObject().apply {
                put("songId", record.songId)
                put("playbackUri", record.playbackUri)
                record.albumId?.let { put("albumId", it) }
                put("durationSeconds", record.durationSeconds)
                record.integratedLufs?.let { put("integratedLufs", it) }
                record.truePeakDbfs?.let { put("truePeakDbfs", it) }
                record.replayGainDb?.let { put("replayGainDb", it) }
                record.replayPeak?.let { put("replayPeak", it) }
                put("analyzedAtMs", record.analyzedAtMs)
            })
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        takeIf { has(key) && !isNull(key) }?.optDouble(key)?.takeIf { it.isFinite() }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        takeIf { has(key) && !isNull(key) }?.optLong(key)
}
