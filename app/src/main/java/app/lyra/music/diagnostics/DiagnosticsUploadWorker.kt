package app.lyra.music.diagnostics

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.lyra.music.BuildConfig
import app.lyra.music.genre.GenreClassifierSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Delivery and email forwarding are handled by the authenticated Lyra server. */
class DiagnosticsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!DiagnosticsSettings.isLoggingEnabled(context) || !GenreClassifierSettings.isConfigured()) {
            return@withContext Result.success()
        }
        val token = GenreClassifierSettings.token() ?: return@withContext Result.success()
        val pending = DiagnosticFiles.pendingCrash(context)
        val snapshot = synchronized(DiagnosticFiles.lock) {
            if (!pending.isFile || pending.length() == 0L) return@withContext Result.success()
            pending.readBytes()
        }
        val id = MessageDigest.getInstance("SHA-256").digest(snapshot).joinToString("") { "%02x".format(it) }
        val report = synchronized(DiagnosticFiles.lock) {
            DiagnosticsReporter.buildReport(context).readText().take(512_000)
        }
        val connection = URL(GenreClassifierSettings.SERVER_URL + "/v2/diagnostics/crash")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Lyra-App-Version", BuildConfig.VERSION_NAME)
            connection.setRequestProperty("X-Lyra-App-Version-Code", BuildConfig.VERSION_CODE.toString())
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            if (!DiagnosticsSettings.isLoggingEnabled(context) || !GenreClassifierSettings.isConfigured() ||
                GenreClassifierSettings.token() != token) {
                return@withContext Result.success()
            }
            val body = JSONObject().put("id", id).put("versionCode", BuildConfig.VERSION_CODE)
                .put("versionName", BuildConfig.VERSION_NAME).put("report", LyraLog.sanitize(report))
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code == 401 || code == 403 || code == 426) {
                GenreClassifierSettings.recordAuthorization(token, false)
                return@withContext Result.success()
            }
            if (code == 404 || code == 405) return@withContext Result.success()
            if (code !in 200..299) return@withContext retryOrKeep()
            val accepted = connection.inputStream.bufferedReader().use { it.readText().take(16_384) }
            if (!JSONObject(accepted).optBoolean("accepted", false)) return@withContext retryOrKeep()
            synchronized(DiagnosticFiles.lock) {
                if (pending.isFile && pending.readBytes().contentEquals(snapshot)) {
                    DiagnosticsReporter.markPendingCrashHandled(context)
                }
                DiagnosticFiles.exportedReport(context).delete()
            }
            Result.success()
        } catch (_: java.io.IOException) {
            retryOrKeep()
        } catch (_: org.json.JSONException) {
            retryOrKeep()
        } finally {
            connection.disconnect()
        }
    }

    private fun retryOrKeep(): Result = if (runAttemptCount < 10) Result.retry() else Result.success()

    companion object {
        private const val NAME = "lyra-diagnostic-upload"
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<DiagnosticsUploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(NAME)
        }
    }
}
