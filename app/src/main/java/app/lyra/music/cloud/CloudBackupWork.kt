package app.lyra.music.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Persistenza del backup anche quando il processo Lyra viene terminato. */
class CloudBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        if (CloudProfileSync.performBackgroundBackup()) Result.success() else Result.retry()
}

object CloudBackupWork {
    private const val ONE_SHOT_NAME = "lyra-cloud-backup-v2-now"
    private const val PERIODIC_NAME = "lyra-cloud-backup-v2-periodic"

    private val networkConstraint = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleOneShot(context: Context, delayMs: Long = 4_000L) {
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(networkConstraint)
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONE_SHOT_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<CloudBackupWorker>(6, TimeUnit.HOURS)
            .setConstraints(networkConstraint)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelOneShot(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(ONE_SHOT_NAME)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(PERIODIC_NAME)
    }
}
