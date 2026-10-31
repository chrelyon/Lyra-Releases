package app.lyra.music.youtube.repository

import android.content.Context

/** Evita di riproporre per alcune ore playlist Scopri che non si aprono o risultano vuote. */
object DiscoveryQualityStore {
    private const val PREFS_NAME = "lyra_discovery_quality"
    private const val FAILURE_TTL_MS = 24L * 60L * 60L * 1000L
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
        purgeExpired()
    }

    fun markFailed(playlistId: String) {
        if (!::appContext.isInitialized) return
        val id = normalize(playlistId)
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(id, System.currentTimeMillis()).apply()
    }

    fun markHealthy(playlistId: String) {
        if (!::appContext.isInitialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(normalize(playlistId)).apply()
    }

    fun isTemporarilyBlocked(playlistId: String): Boolean {
        if (!::appContext.isInitialized) return false
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val failedAt = prefs.getLong(normalize(playlistId), 0L)
        if (failedAt <= 0L) return false
        if (System.currentTimeMillis() - failedAt >= FAILURE_TTL_MS) {
            prefs.edit().remove(normalize(playlistId)).apply()
            return false
        }
        return true
    }

    private fun purgeExpired() {
        if (!::appContext.isInitialized) return
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        var changed = false
        prefs.all.forEach { (key, value) ->
            val time = value as? Long ?: return@forEach
            if (now - time >= FAILURE_TTL_MS) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun normalize(id: String): String = id
        .removePrefix(YouTubeMusicRepository.DISCOVER_MUSIC_PLAYLIST_PREFIX)
        .removePrefix("VL")
}
