package app.lyra.music.playback

import android.content.Context
import android.content.SharedPreferences
import app.lyra.music.core.model.Song

/**
 * Memoria locale e limitata dei preset generati. Non entra nel profilo remoto Ubuntu,
 * non modifica lo storico degli ascolti e sopravvive ai riavvii dell'app.
 */
object AutomaticPlaylistVarietyStore {
    private const val PREFS_NAME = "lyra_automatic_variety"
    private const val MAX_SEEDS = 10
    private const val MAX_OPENERS = 10
    private const val MAX_PROPOSALS = 80
    private const val REMEMBER_PER_QUEUE = 24
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun recentSeeds(presetId: String): Set<String> = read("seeds_$presetId").toSet()

    @Synchronized
    fun recentOpeners(presetId: String): Set<String> = read("openers_$presetId").toSet()

    @Synchronized
    fun recentProposals(presetId: String): Set<String> = read("songs_$presetId").toSet()

    @Synchronized
    fun remember(presetId: String, seedIds: List<String>, songs: List<Song>, recordOpening: Boolean = true) {
        val prefs = preferences ?: return
        val seeds = merge(seedIds, read("seeds_$presetId"), MAX_SEEDS)
        val proposals = merge(songs.take(REMEMBER_PER_QUEUE).map { it.id }, read("songs_$presetId"), MAX_PROPOSALS)
        val edit = prefs.edit()
            .putString("seeds_$presetId", seeds.joinToString("\n"))
            .putString("songs_$presetId", proposals.joinToString("\n"))
        if (recordOpening) {
            val opener = songs.firstOrNull()?.id?.takeIf { it.isNotBlank() }
            if (opener != null) {
                val openers = merge(listOf(opener), read("openers_$presetId"), MAX_OPENERS)
                edit.putString("openers_$presetId", openers.joinToString("\n"))
            }
        }
        edit.apply()
    }

    private fun merge(new: List<String>, old: List<String>, limit: Int): List<String> =
        (new.asReversed() + old).filter { it.isNotBlank() }.distinct().take(limit)

    private fun read(key: String): List<String> = preferences?.getString(key, "")
        ?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
