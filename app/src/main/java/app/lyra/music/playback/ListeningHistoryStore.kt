package app.lyra.music.playback

import android.content.Context
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cronologia locale delle sessioni ascoltate.
 *
 * Dalla 0.3.13 ogni voce conserva non solo il brano corrente ma anche la coda
 * completa e l'indice da cui ripartire. Questo permette di riaprire dalla
 * cronologia la stessa playlist/sessione, comprese le code automatiche già
 * costruite in quel momento.
 */
object ListeningHistoryStore {
    private const val PREFS_NAME = "lyra_listening_history"
    private const val KEY_HISTORY = "history"
    private const val MAX_ITEMS = 100

    data class Entry(
        val id: String,
        val song: Song,
        val queueTitle: String,
        val queueSongs: List<Song>,
        val currentIndex: Int,
        val automatic: Boolean,
        val listenedAtMs: Long,
        val allowSmartContinuation: Boolean = true,
    )

    private lateinit var appContext: Context
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        _entries.value = load()
    }

    @Synchronized
    fun record(
        queueState: PlaybackQueueUiState,
        automatic: Boolean,
        listenedAtMs: Long = System.currentTimeMillis(),
    ) {
        if (!::appContext.isInitialized || !queueState.hasQueue) return
        val song = queueState.currentSong ?: return
        if (song.id.isBlank()) return

        val queueSongs = queueState.songs.ifEmpty { listOf(song) }
        val safeIndex = queueState.currentIndex.coerceIn(0, queueSongs.lastIndex)
        val queueTitle = queueState.title.ifBlank { song.title }
        val stableKey = "${song.id}|${queueTitle.hashCode()}"
        val entry = Entry(
            id = "$stableKey|$listenedAtMs",
            song = song,
            queueTitle = queueTitle,
            queueSongs = queueSongs,
            currentIndex = safeIndex,
            automatic = automatic,
            listenedAtMs = listenedAtMs,
            allowSmartContinuation = queueState.allowSmartContinuation,
        )

        val updated = buildList {
            add(entry)
            addAll(
                _entries.value.filterNot { previous ->
                    previous.song.id == song.id && previous.queueTitle == queueTitle
                }
            )
        }.take(MAX_ITEMS)
        _entries.value = updated
        save(updated)
    }

    fun entry(id: String): Entry? = _entries.value.firstOrNull { it.id == id }

    fun clear() {
        if (!::appContext.isInitialized) return
        _entries.value = emptyList()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    private fun load(): List<Entry> {
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.toEntryOrNull(index)?.let(::add)
                }
            }.take(MAX_ITEMS)
        }.getOrDefault(emptyList())
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry -> array.put(entry.toJson()) }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun Entry.toJson() = JSONObject()
        .put("id", id)
        .put("song", song.toJson())
        .put("queueTitle", queueTitle)
        .put("queueSongs", JSONArray().apply { queueSongs.forEach { put(it.toJson()) } })
        .put("currentIndex", currentIndex)
        .put("automatic", automatic)
        .put("listenedAtMs", listenedAtMs)
        .put("allowSmartContinuation", allowSmartContinuation)

    /** Compatibilita' con la cronologia 0.3.12, che salvava direttamente Song. */
    private fun JSONObject.toEntryOrNull(legacyIndex: Int): Entry? {
        val nestedSong = optJSONObject("song")
        if (nestedSong == null) {
            val legacySong = toSongOrNull() ?: return null
            return Entry(
                id = "legacy:${legacySong.id}:$legacyIndex",
                song = legacySong,
                queueTitle = "Ascoltati di recente",
                queueSongs = listOf(legacySong),
                currentIndex = 0,
                automatic = false,
                listenedAtMs = 0L,
            )
        }

        val song = nestedSong.toSongOrNull() ?: return null
        val songsArray = optJSONArray("queueSongs")
        val queueSongs = buildList {
            if (songsArray != null) {
                for (index in 0 until songsArray.length()) {
                    songsArray.optJSONObject(index)?.toSongOrNull()?.let(::add)
                }
            }
        }.ifEmpty { listOf(song) }
        val requestedIndex = optInt("currentIndex", queueSongs.indexOfFirst { it.id == song.id })
        val safeIndex = requestedIndex.coerceIn(0, queueSongs.lastIndex)
        val timestamp = optLong("listenedAtMs", 0L)
        return Entry(
            id = optString("id").ifBlank { "${song.id}|${optString("queueTitle").hashCode()}|$timestamp" },
            song = queueSongs.getOrElse(safeIndex) { song },
            queueTitle = optString("queueTitle").ifBlank { song.title },
            queueSongs = queueSongs,
            currentIndex = safeIndex,
            automatic = optBoolean("automatic", false),
            listenedAtMs = timestamp,
            allowSmartContinuation = optBoolean("allowSmartContinuation", true),
        )
    }

    private fun Song.toJson() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("durationSeconds", durationSeconds)
        .put("thumbnailUrl", thumbnailUrl)
        .put("playbackUri", playbackUri)
        .put("album", album)
        .put("albumId", albumId)

    private fun JSONObject.toSongOrNull(): Song? {
        val id = optString("id")
        if (id.isBlank()) return null
        return Song(
            id = id,
            title = optString("title"),
            artist = optString("artist"),
            durationSeconds = optLong("durationSeconds", 0L),
            thumbnailUrl = optString("thumbnailUrl").takeIf { it.isNotBlank() && it != "null" },
            playbackUri = optString("playbackUri").takeIf { it.isNotBlank() && it != "null" },
            album = optString("album").takeIf { it.isNotBlank() && it != "null" },
            albumId = takeIf { has("albumId") && !isNull("albumId") }?.optLong("albumId"),
        )
    }
}
