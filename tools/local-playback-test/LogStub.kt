package app.lyra.music.diagnostics
import android.content.Context
object LyraLog {
    fun d(tag: String,message: String) {}
    fun e(tag: String,message: String,error: Throwable? = null) {}
    fun w(tag: String,message: String,error: Throwable? = null) {}
    fun clearStoredLogs(context: Context) {}
}

object DiagnosticsUploadWorker { fun cancel(context: Context) {} }
