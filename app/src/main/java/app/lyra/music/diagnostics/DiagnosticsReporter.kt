package app.lyra.music.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DiagnosticsReporter {
    fun scheduleUpload(context: Context) {
        if (DiagnosticsSettings.isLoggingEnabled(context) && hasPendingCrashReport(context)) {
            DiagnosticsUploadWorker.schedule(context)
        }
    }

    fun captureHistoricalExitIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            captureHistoricalExitApi30(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun captureHistoricalExitApi30(context: Context) {
        if (!DiagnosticsSettings.isLoggingEnabled(context)) return

        val appContext = context.applicationContext
        val enabledSince = DiagnosticsSettings.loggingEnabledSince(appContext)
        val alreadySeen = DiagnosticsSettings.lastExitTimestamp(appContext)
        val activityManager = appContext.getSystemService(ActivityManager::class.java) ?: return

        val exit = runCatching {
            activityManager.getHistoricalProcessExitReasons(appContext.packageName, 0, 5)
                .firstOrNull { info ->
                    info.timestamp >= enabledSince &&
                        info.timestamp > alreadySeen &&
                        info.reason in setOf(
                            ApplicationExitInfo.REASON_CRASH,
                            ApplicationExitInfo.REASON_CRASH_NATIVE,
                            ApplicationExitInfo.REASON_ANR,
                        )
                }
        }.getOrNull() ?: return

        DiagnosticsSettings.setLastExitTimestamp(appContext, exit.timestamp)

        // Se l'handler Java/Kotlin ha gia' prodotto il crash report, conserviamo
        // quello perche' contiene lo stack trace completo.
        val pending = DiagnosticFiles.pendingCrash(appContext)
        if (pending.exists() && pending.length() > 0L) return

        val details = buildString {
            appendLine("timestamp=${formatTimestamp(exit.timestamp)}")
            appendLine("source=ApplicationExitInfo")
            appendLine("reason=${reasonName(exit.reason)}")
            appendLine("status=${exit.status}")
            appendLine("importance=${exit.importance}")
            appendLine("pss=${exit.pss}")
            appendLine("rss=${exit.rss}")
            appendLine("description=${LyraLog.sanitize(exit.description.orEmpty())}")
        }
        synchronized(DiagnosticFiles.lock) { runCatching { pending.writeText(details) } }
        LyraLog.w("LYRA_CRASH", "historical process exit detected reason=${reasonName(exit.reason)}")
    }

    fun hasPendingCrashReport(context: Context): Boolean =
        DiagnosticFiles.pendingCrash(context).let { it.exists() && it.length() > 0L }

    fun markPendingCrashHandled(context: Context) {
        runCatching { DiagnosticFiles.pendingCrash(context).delete() }
    }

    internal fun buildReport(context: Context): File {
        val appContext = context.applicationContext
        val report = DiagnosticFiles.exportedReport(appContext)
        val packageInfo = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        }.getOrNull()
        val versionCode = packageInfo?.let { info ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } ?: 0L

        report.bufferedWriter().use { writer ->
            writer.appendLine("LYRA DIAGNOSTIC REPORT")
            writer.appendLine("generated=${formatTimestamp(System.currentTimeMillis())}")
            writer.appendLine("package=${appContext.packageName}")
            writer.appendLine("versionName=${packageInfo?.versionName.orEmpty()}")
            writer.appendLine("versionCode=$versionCode")
            writer.appendLine("android=${Build.VERSION.RELEASE} api=${Build.VERSION.SDK_INT}")
            writer.appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            writer.appendLine()

            appendFile(writer, "CRASH / PROCESS EXIT", DiagnosticFiles.pendingCrash(appContext))
            appendFile(writer, "PREVIOUS APP LOG", DiagnosticFiles.previousLog(appContext))
            appendFile(writer, "CURRENT APP LOG", DiagnosticFiles.currentLog(appContext))
        }

        return report
    }

    private fun appendFile(writer: java.io.BufferedWriter, title: String, file: File) {
        writer.appendLine("===== $title =====")
        if (!file.exists() || file.length() == 0L) {
            writer.appendLine("<none>")
        } else {
            file.forEachLine { line -> writer.appendLine(LyraLog.sanitize(line)) }
        }
        writer.appendLine()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        else -> "UNKNOWN_$reason"
    }

    private fun formatTimestamp(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(timestamp))
    }
}
