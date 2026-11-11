package app.lyra.music.diagnostics

import android.content.Context

object DiagnosticsSettings {
    private const val PREFS_NAME = "lyra_diagnostics"
    private const val KEY_LOGGING_ENABLED = "logging_enabled"
    private const val KEY_ENABLED_SINCE = "logging_enabled_since"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_exit_timestamp"

    fun initialize(context: Context) {
        val preferences = prefs(context)
        if (!preferences.contains(KEY_LOGGING_ENABLED)) {
            preferences.edit().putBoolean(KEY_LOGGING_ENABLED, true)
                .putLong(KEY_ENABLED_SINCE, System.currentTimeMillis()).commit()
        }
    }

    fun isLoggingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGING_ENABLED, true)

    fun loggingEnabledSince(context: Context): Long =
        prefs(context).getLong(KEY_ENABLED_SINCE, 0L)

    fun setLoggingEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        val wasEnabled = isLoggingEnabled(appContext)

        if (enabled == wasEnabled) return

        if (enabled) {
            LyraLog.clearStoredLogs(appContext)
            prefs(appContext).edit()
                .putBoolean(KEY_LOGGING_ENABLED, true)
                .putLong(KEY_ENABLED_SINCE, System.currentTimeMillis())
                .apply()
            LyraLog.d("LYRA_DIAGNOSTICS", "logging enabled=true")
        } else {
            prefs(appContext).edit()
                .putBoolean(KEY_LOGGING_ENABLED, false)
                .remove(KEY_ENABLED_SINCE)
                .apply()
            LyraLog.clearStoredLogs(appContext)
            DiagnosticsUploadWorker.cancel(appContext)
        }
    }

    fun lastExitTimestamp(context: Context): Long =
        prefs(context).getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)

    fun setLastExitTimestamp(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_EXIT_TIMESTAMP, timestamp).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
