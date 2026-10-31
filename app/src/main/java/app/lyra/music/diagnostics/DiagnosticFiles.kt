package app.lyra.music.diagnostics

import android.content.Context
import java.io.File

internal object DiagnosticFiles {
    private const val DIRECTORY = "diagnostics"

    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY).apply { mkdirs() }

    fun currentLog(context: Context): File = File(directory(context), "lyra.log")
    fun previousLog(context: Context): File = File(directory(context), "lyra.1.log")
    fun pendingCrash(context: Context): File = File(directory(context), "pending-crash.txt")
    fun exportedReport(context: Context): File = File(directory(context), "lyra-diagnostic-report.txt")

    fun clearAll(context: Context) {
        directory(context).listFiles()?.forEach { file ->
            runCatching { file.delete() }
        }
    }
}
