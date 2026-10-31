package app.lyra.music.playback

import android.content.Context
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Playlist locali create salvando una sessione Radio/Playlist Automatica. */
object SavedAutomaticPlaylistStore {
    const val PLAYLIST_PREFIX = "lyra-auto-saved:"
    private const val PREFS_NAME = "lyra_saved_automatic_playlists"
    private const val KEY_PLAYLISTS = "playlists"
    private const val MAX_PLAYLISTS = 40

    data class SavedPlaylist(
        val id: String,
        val title: String,
        val songs: List<Song>,
        val savedAtMs: Long,
    )

    private lateinit var appContext: Context
    private val _items = MutableStateFlow<List<SavedPlaylist>>(emptyList())
    val items: StateFlow<List<SavedPlaylist>> = _items.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        _items.value = load()
    }

    @Synchronized
    fun save(title: String, songs: List<Song>): SavedPlaylist? {
        if (!::appContext.isInitialized || songs.isEmpty()) return null
        val cleanSongs = songs.distinctBy { it.id }.take(250)
        if (cleanSongs.isEmpty()) return null
        val saved = SavedPlaylist(
            id = PLAYLIST_PREFIX + UUID.randomUUID().toString(),
            title = title.ifBlank { "Playlist Automatica salvata" },
            songs = cleanSongs,
            savedAtMs = System.currentTimeMillis(),
        )
        _items.value = (listOf(saved) + _items.value).take(MAX_PLAYLISTS)
        persist()
        return saved
    }

    fun asPlaylists(): List<Playlist> = _items.value.map { saved ->
        Playlist(
            id = saved.id,
            title = saved.title,
            thumbnailUrl = saved.songs.firstOrNull()?.thumbnailUrl,
            songCount = saved.songs.size,
        )
    }

    fun getContent(id: String): PlaylistContent? = _items.value
        .firstOrNull { it.id == id }
        ?.let { saved -> PlaylistContent(saved.id, saved.title, saved.songs) }

    private fun load(): List<SavedPlaylist> {
        val raw = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    val id = obj.optString("id")
                    if (id.isBlank()) continue
                    val songArray = obj.optJSONArray("songs") ?: JSONArray()
                    val songs = buildList {
                        for (songIndex in 0 until songArray.length()) {
                            songArray.optJSONObject(songIndex)?.toSongOrNull()?.let(::add)
                        }
                    }
                    if (songs.isNotEmpty()) {
                        add(
                            SavedPlaylist(
                                id = id,
                                title = obj.optString("title").ifBlank { "Playlist Automatica salvata" },
                                songs = songs,
                                savedAtMs = obj.optLong("savedAtMs", 0L),
                            )
                        )
                    }
                }
            }.take(MAX_PLAYLISTS)
        }.getOrDefault(emptyList())
    }

    private fun persist() {
        val array = JSONArray()
        _items.value.forEach { saved ->
            array.put(
                JSONObject()
                    .put("id", saved.id)
                    .put("title", saved.title)
                    .put("savedAtMs", saved.savedAtMs)
                    .put("songs", JSONArray().also { songs -> saved.songs.forEach { songs.put(it.toJson()) } })
            )
        }
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYLISTS, array.toString()).apply()
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
