package app.lyra.music.audio

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.lyra.music.local.LocalMediaAccess
import app.lyra.music.local.LocalMediaRepository

/** Analisi incrementale della libreria locale, senza bloccare il playback/UI. */
class LocalAudioScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AudioSettings.normalizationEnabled.value ||
            !AudioSettings.localNormalizationEnabled.value ||
            !AudioSettings.r128AnalysisEnabled.value
        ) {
            return Result.success()
        }
        if (!LocalMediaAccess.hasPermission(applicationContext)) return Result.success()

        val songs = LocalMediaRepository(applicationContext).getSongs()
        val prioritySongId = inputData.getString(KEY_PRIORITY_SONG_ID)
        val pending = songs.filterNot(LocalLoudnessStore::hasFreshMeasurement)
            .sortedByDescending { it.id == prioritySongId }
        var analyzed = songs.count(LocalLoudnessStore::hasFreshMeasurement)
        LocalLoudnessStore.updateScanStatus(true, analyzed, songs.size)
        val analyzer = LocalLoudnessAnalyzer(applicationContext)
        var lastError: String? = null

        val limit = if (prioritySongId != null) 1 else MAX_TRACKS_PER_RUN
        val orderedPending = if (prioritySongId != null) pending else {
            // Alterna i candidati ai retry, senza riesaminare sempre i
            // primi file che potrebbero essere danneggiati/non supportati.
            val offset = AudioScanRetryPolicy.startOffset(
                pending.size, runAttemptCount, MAX_TRACKS_PER_RUN,
            )
            pending.drop(offset) + pending.take(offset)
        }
        var successThisRun = 0
        orderedPending.take(limit).forEach { song ->
            if (isStopped) return@forEach
            LocalLoudnessStore.updateScanStatus(true, analyzed, songs.size, song.title, lastError)
            val measurement = analyzer.analyze(song)
            if (measurement != null) {
                LocalLoudnessStore.put(song, measurement)
                analyzed += 1
                successThisRun += 1
            } else {
                lastError = "Analisi non riuscita: ${song.title}"
            }
        }

        val remaining = songs.count { !LocalLoudnessStore.hasFreshMeasurement(it) }
        LocalLoudnessStore.updateScanStatus(false, songs.size - remaining, songs.size, lastError = lastError)
        return if (AudioScanRetryPolicy.shouldRetry(
                remaining = remaining,
                successThisRun = successThisRun,
                runAttemptCount = runAttemptCount,
                priorityScan = prioritySongId != null,
                stopped = isStopped,
            )
        ) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK = "lyra_local_audio_scan"
        private const val UNIQUE_CURRENT_WORK = "lyra_current_audio_scan"
        private const val KEY_PRIORITY_SONG_ID = "priority_song_id"
        private const val MAX_TRACKS_PER_RUN = 12

        fun schedule(context: Context, force: Boolean = false) {
            if (!force && !AudioSettings.backgroundScanEnabled.value) return
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresCharging(!force && AudioSettings.scanOnlyWhileCharging.value)
                .build()
            val request = OneTimeWorkRequestBuilder<LocalAudioScanWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun scheduleCurrent(context: Context, songId: String) {
            val request = OneTimeWorkRequestBuilder<LocalAudioScanWorker>()
                .setInputData(workDataOf(KEY_PRIORITY_SONG_ID to songId))
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                UNIQUE_CURRENT_WORK,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).apply {
                cancelUniqueWork(UNIQUE_WORK)
                cancelUniqueWork(UNIQUE_CURRENT_WORK)
            }
        }
    }
}
