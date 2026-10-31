package app.lyra.music.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferenze persistenti per il ripristino dell'ultima sessione di ascolto.
 *
 * L'opzione controlla esclusivamente il comportamento dopo un nuovo avvio del
 * processo/app. Durante la sessione corrente Lyra continua a salvare la queue
 * per garantire NEXT/PREV e controlli esterni affidabili.
 */
object PlaybackResumeSettings {
    private const val PREFS = "lyra_playback_resume_settings"
    private const val KEY_RESTORE_LAST_SESSION = "restore_last_session"

    private lateinit var appContext: Context

    private val _restoreLastSession = MutableStateFlow(true)
    val restoreLastSession: StateFlow<Boolean> = _restoreLastSession.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _restoreLastSession.value = prefs.getBoolean(KEY_RESTORE_LAST_SESSION, true)
    }

    fun setRestoreLastSession(enabled: Boolean) {
        ensureInitialized()
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RESTORE_LAST_SESSION, enabled)
            .apply()
        _restoreLastSession.value = enabled
    }

    fun isRestoreLastSessionEnabled(): Boolean {
        ensureInitialized()
        return _restoreLastSession.value
    }

    private fun ensureInitialized() {
        check(::appContext.isInitialized) {
            "PlaybackResumeSettings non inizializzato"
        }
    }
}
