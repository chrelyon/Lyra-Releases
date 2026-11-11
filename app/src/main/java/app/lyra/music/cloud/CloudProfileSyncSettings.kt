package app.lyra.music.cloud

import android.content.Context
import app.lyra.music.genre.GenreClassifierSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Backup del profilo musicale esclusivamente sul server Ubuntu gia configurato. */
object CloudProfileSyncSettings {
    // Preserve preference names and anonymous identity across app upgrades.
    private const val PREFS_NAME = "lyra_cloud_profile_sync"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_V2_INITIALIZED = "backup_v2_initialized"
    private const val KEY_UBUNTU_URL = "verified_ubuntu_sync_url"
    private const val KEY_READY_URL = "ubuntu_profile_bootstrapped_url_v1"
    private const val KEY_LOCAL_ONLY = "ubuntu_only_initialized_v1"

    private lateinit var appContext: Context
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val _ubuntuUrl = MutableStateFlow("")
    val ubuntuUrl: StateFlow<String> = _ubuntuUrl.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** No embedded URL or fallback to the old Cloudflare Worker. */
    val backendEndpoint: String get() = _ubuntuUrl.value
    val usingUbuntu: Boolean get() = backendEndpoint.isNotBlank()
    val endpointConfigured: Boolean get() = usingUbuntu

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val configured = UbuntuOnlyCutoverPolicy.endpoint(
            GenreClassifierSettings.endpoint.value,
            prefs.getString(KEY_UBUNTU_URL, "").orEmpty(),
        )
        _ubuntuUrl.value = configured
        _ready.value = configured.isNotBlank() && prefs.getString(KEY_READY_URL, "") == configured
        // First upgrade activates Ubuntu backup automatically; later keep the user's ON/OFF choice.
        val previouslyInitialized = prefs.getBoolean(KEY_LOCAL_ONLY, false)
        val desired = !previouslyInitialized || prefs.getBoolean(KEY_ENABLED, true)
        val effective = configured.isNotBlank() && GenreClassifierSettings.isConfigured() && desired
        prefs.edit()
            .putBoolean(KEY_LOCAL_ONLY, true)
            .putBoolean(KEY_V2_INITIALIZED, true)
            .putBoolean(KEY_ENABLED, desired)
            .apply()
        _enabled.value = effective
    }

    /** Retain the hardcoded Funnel endpoint after an app update. */
    fun refreshFromClassifier() {
        if (!::appContext.isInitialized) return
        val configured = UbuntuOnlyCutoverPolicy.endpoint(GenreClassifierSettings.endpoint.value, "")
        if (configured.isBlank()) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _ubuntuUrl.value = configured
        _ready.value = prefs.getString(KEY_READY_URL, "") == configured
        prefs.edit().putString(KEY_UBUNTU_URL, configured).apply()
        if (_enabled.value) CloudProfileSync.retryBootstrapOrSync()
    }

    /** Suspend/resume background backups without changing the user backup preference. */
    fun onServerConnectionChanged() {
        if (!::appContext.isInitialized) return
        val shouldRun = endpointConfigured && GenreClassifierSettings.isConfigured() &&
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        _enabled.value = shouldRun
        if (shouldRun) {
            CloudBackupWork.schedulePeriodic(appContext)
            CloudProfileSync.retryBootstrapOrSync()
        } else {
            CloudBackupWork.cancelOneShot(appContext)
            CloudBackupWork.cancelPeriodic(appContext)
        }
    }

    fun markUbuntuReady(url: String): Boolean {
        if (!::appContext.isInitialized || backendEndpoint != url ||
            UbuntuOnlyCutoverPolicy.endpoint(url, "") != url) return false
        val saved = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_UBUNTU_URL, url)
            .putString(KEY_READY_URL, url)
            .commit()
        if (saved) _ready.value = true
        return saved
    }

    fun clearUbuntuReady() {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_READY_URL).apply()
        _ready.value = false
    }

    fun setEnabled(value: Boolean) {
        if (!::appContext.isInitialized) return
        val effective = value && endpointConfigured && GenreClassifierSettings.isConfigured()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, effective).apply()
        _enabled.value = effective
        if (effective) {
            CloudBackupWork.schedulePeriodic(appContext)
            CloudProfileSync.retryBootstrapOrSync()
        } else {
            CloudBackupWork.cancelOneShot(appContext)
            CloudBackupWork.cancelPeriodic(appContext)
        }
    }
}
