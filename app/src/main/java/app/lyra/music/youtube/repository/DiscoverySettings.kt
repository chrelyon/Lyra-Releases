package app.lyra.music.youtube.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object DiscoverySettings {
    enum class RegionMode { ITALY, ITALY_EUROPE, INTERNATIONAL }

    private const val PREFS_NAME = "lyra_discovery_settings"
    private const val KEY_REGION_MODE = "region_mode"
    private lateinit var appContext: Context

    private val _regionMode = MutableStateFlow(RegionMode.INTERNATIONAL)
    val regionMode: StateFlow<RegionMode> = _regionMode.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REGION_MODE, RegionMode.INTERNATIONAL.name)
        _regionMode.value = runCatching { RegionMode.valueOf(raw.orEmpty()) }
            .getOrDefault(RegionMode.INTERNATIONAL)
    }

    fun setRegionMode(mode: RegionMode) {
        _regionMode.value = mode
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_REGION_MODE, mode.name).apply()
        }
        YouTubeMusicRepository.clearDiscoveryCaches()
    }
}
