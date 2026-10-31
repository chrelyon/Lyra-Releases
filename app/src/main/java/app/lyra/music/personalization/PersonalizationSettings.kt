package app.lyra.music.personalization

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferenze del motore di personalizzazione locale di Lyra. */
object PersonalizationSettings {
    private const val PREFS_NAME = "lyra_personalization_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_DISCOVERY_LEVEL = "discovery_level"
    private const val KEY_USE_HISTORY = "use_history"
    private const val KEY_USE_LIKES = "use_likes"
    private const val KEY_LEARN_SKIPS = "learn_skips"
    private const val KEY_AVOID_REPEATS = "avoid_repeats"
    private const val KEY_CONTINUE_SIMILAR = "continue_similar"

    enum class DiscoveryLevel(val exploration: Double) {
        CONSERVATIVE(0.10),
        BALANCED(0.20),
        EXPLORATIVE(0.35),
    }

    private lateinit var appContext: Context

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _discoveryLevel = MutableStateFlow(DiscoveryLevel.BALANCED)
    val discoveryLevel: StateFlow<DiscoveryLevel> = _discoveryLevel.asStateFlow()

    private val _useHistory = MutableStateFlow(true)
    val useHistory: StateFlow<Boolean> = _useHistory.asStateFlow()

    private val _useLikes = MutableStateFlow(true)
    val useLikes: StateFlow<Boolean> = _useLikes.asStateFlow()

    private val _learnFromSkips = MutableStateFlow(true)
    val learnFromSkips: StateFlow<Boolean> = _learnFromSkips.asStateFlow()

    private val _avoidRepeats = MutableStateFlow(true)
    val avoidRepeats: StateFlow<Boolean> = _avoidRepeats.asStateFlow()

    private val _continueSimilar = MutableStateFlow(true)
    val continueSimilar: StateFlow<Boolean> = _continueSimilar.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_ENABLED, true)
        _discoveryLevel.value = runCatching {
            DiscoveryLevel.valueOf(
                prefs.getString(KEY_DISCOVERY_LEVEL, DiscoveryLevel.BALANCED.name)
                    ?: DiscoveryLevel.BALANCED.name
            )
        }.getOrDefault(DiscoveryLevel.BALANCED)
        _useHistory.value = prefs.getBoolean(KEY_USE_HISTORY, true)
        _useLikes.value = prefs.getBoolean(KEY_USE_LIKES, true)
        _learnFromSkips.value = prefs.getBoolean(KEY_LEARN_SKIPS, true)
        _avoidRepeats.value = prefs.getBoolean(KEY_AVOID_REPEATS, true)
        _continueSimilar.value = prefs.getBoolean(KEY_CONTINUE_SIMILAR, true)
    }

    fun setEnabled(value: Boolean) = update(KEY_ENABLED, value) { _enabled.value = value }
    fun setUseHistory(value: Boolean) = update(KEY_USE_HISTORY, value) { _useHistory.value = value }
    fun setUseLikes(value: Boolean) = update(KEY_USE_LIKES, value) { _useLikes.value = value }
    fun setLearnFromSkips(value: Boolean) = update(KEY_LEARN_SKIPS, value) { _learnFromSkips.value = value }
    fun setAvoidRepeats(value: Boolean) = update(KEY_AVOID_REPEATS, value) { _avoidRepeats.value = value }
    fun setContinueSimilar(value: Boolean) = update(KEY_CONTINUE_SIMILAR, value) { _continueSimilar.value = value }

    fun setDiscoveryLevel(value: DiscoveryLevel) {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISCOVERY_LEVEL, value.name).apply()
        _discoveryLevel.value = value
    }

    private fun update(key: String, value: Boolean, applyFlow: () -> Unit) {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        applyFlow()
    }
}
