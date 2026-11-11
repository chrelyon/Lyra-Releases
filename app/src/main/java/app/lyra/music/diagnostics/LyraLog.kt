package app.lyra.music.diagnostics

import android.content.Context
import android.util.Log
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Wrapper di android.util.Log che mantiene Logcat invariato e, quando la
 * diagnostica e' abilitata, salva anche gli eventi dell'app in un file locale.
 */
object LyraLog {
    private const val MAX_LOG_BYTES = 768L * 1024L
    private val lock = DiagnosticFiles.lock

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (DiagnosticsSettings.isLoggingEnabled(context)) {
            d("LYRA_DIAGNOSTICS", "logger initialized")
        }
    }

    fun d(tag: String, message: String): Int {
        val result = Log.d(tag, message)
        append("D", tag, message, null)
        return result
    }

    fun d(tag: String, message: String, throwable: Throwable): Int {
        val result = Log.d(tag, message, throwable)
        append("D", tag, message, throwable)
        return result
    }

    fun w(tag: String, message: String): Int {
        val result = Log.w(tag, message)
        append("W", tag, message, null)
        return result
    }

    fun w(tag: String, message: String, throwable: Throwable): Int {
        val result = Log.w(tag, message, throwable)
        append("W", tag, message, throwable)
        return result
    }

    fun e(tag: String, message: String): Int {
        val result = Log.e(tag, message)
        append("E", tag, message, null)
        return result
    }

    fun e(tag: String, message: String, throwable: Throwable): Int {
        val result = Log.e(tag, message, throwable)
        append("E", tag, message, throwable)
        return result
    }


    fun clearStoredLogs(context: Context) {
        synchronized(lock) {
            DiagnosticFiles.clearAll(context.applicationContext)
        }
    }

    fun writeFatal(throwable: Throwable, threadName: String) {
        val context = appContext ?: return
        if (!DiagnosticsSettings.isLoggingEnabled(context)) return

        val details = buildString {
            appendLine("timestamp=${timestamp()}")
            appendLine("thread=${sanitize(threadName)}")
            appendLine("exception=${throwable.javaClass.name}")
            appendLine("message=${sanitize(throwable.message.orEmpty())}")
            appendLine()
            append(sanitize(Log.getStackTraceString(throwable)))
        }

        synchronized(lock) {
            runCatching {
                DiagnosticFiles.pendingCrash(context).writeText(details)
                appendLocked(context, "F", "LYRA_CRASH", "uncaught exception thread=$threadName", throwable)
            }
        }
    }

    private fun append(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        val context = appContext ?: return
        if (!DiagnosticsSettings.isLoggingEnabled(context)) return

        synchronized(lock) {
            runCatching {
                appendLocked(context, level, tag, message, throwable)
            }
        }
    }

    private fun appendLocked(
        context: Context,
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        rotateIfNeeded(context)
        val line = buildString {
            append(timestamp())
            append(' ')
            append(level)
            append('/')
            append(sanitize(tag))
            append(" [")
            append(Thread.currentThread().name)
            append("] ")
            append(sanitize(message))
            if (throwable != null) {
                append('\n')
                append(sanitize(Log.getStackTraceString(throwable)))
            }
            append('\n')
        }
        FileWriter(DiagnosticFiles.currentLog(context), true).use { writer ->
            writer.append(line)
        }
    }

    private fun rotateIfNeeded(context: Context) {
        val current = DiagnosticFiles.currentLog(context)
        if (!current.exists() || current.length() < MAX_LOG_BYTES) return

        val previous = DiagnosticFiles.previousLog(context)
        if (previous.exists()) previous.delete()
        current.renameTo(previous)
    }

    internal fun sanitize(value: String): String {
        if (value.isBlank()) return value

        return value
            .replace(Regex("""(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}"""), "<redacted-email>")
            .replace(Regex("""lyra_[A-Za-z0-9_-]{40,}"""), "<redacted-access-token>")
            .replace(Regex("""LYRA-R-[A-Fa-f0-9]{24}"""), "<redacted-recovery-code>")
            .replace(Regex("""LYRA2\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""), "<redacted-recovery-code>")
            .replace(Regex("""/(?:storage|sdcard|data/user|data/data)/[^\s]+"""), "<redacted-local-path>")
            .replace(Regex("""https?://[^\s]+"""), "<redacted-url>")
            .replace(Regex("(?i)(authorization\\s*[:=]?\\s*bearer\\s+)[^\\s,;]+"), "${'$'}1<redacted>")
            .replace(Regex("(?i)(access[_-]?token\\s*[:=]\\s*)[^\\s&;,]+"), "${'$'}1<redacted>")
            .replace(Regex("(?i)(refresh[_-]?token\\s*[:=]\\s*)[^\\s&;,]+"), "${'$'}1<redacted>")
            .replace(Regex("(?i)([?&](?:sig|signature|lsig|token|expire|ip|ipbits|sparams|sp)=[^&\\s]+)"), "<redacted-param>")
            .replace(Regex("https?://[^\\s]*googlevideo\\.com[^\\s]*", RegexOption.IGNORE_CASE), "<redacted-googlevideo-url>")
    }

    private fun timestamp(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }
}
