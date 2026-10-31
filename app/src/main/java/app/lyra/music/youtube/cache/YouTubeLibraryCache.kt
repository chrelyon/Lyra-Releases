package app.lyra.music.youtube.cache

import android.content.Context
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.YouTubeChannel
import app.lyra.music.core.model.YouTubeLibrarySnapshot
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cache locale della sola metadata della libreria YouTube.
 *
 * Non contiene token OAuth, password o cookie. Serve a mostrare subito
 * l'ultima libreria valida mentre la sincronizzazione di rete avviene in
 * background.
 */
object YouTubeLibraryCache {

    private const val TAG = "LYRA_LIBRARY"
    private const val PREFS_NAME = "lyra_youtube_library_cache"
    private const val KEY_SNAPSHOT = "snapshot_v1"

    private var context: Context? = null

    fun initialize(context: Context) {
        if (this.context == null) {
            this.context = context.applicationContext
        }
    }

    fun load(): YouTubeLibrarySnapshot? {
        val appContext = context ?: return null
        val raw = appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SNAPSHOT, null)
            ?: return null

        return runCatching {
            decode(JSONObject(raw))
        }.onFailure { error ->
            Log.w(TAG, "cacheInvalid; clearing message=${error.message}")
            clear()
        }.getOrNull()?.also { snapshot ->
            Log.d(
                TAG,
                "cacheLoaded playlists=${snapshot.playlists.size} " +
                    "channel=${snapshot.channel != null} syncedAt=${snapshot.syncedAtEpochMs}"
            )
        }
    }

    fun save(snapshot: YouTubeLibrarySnapshot) {
        val appContext = context ?: return
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SNAPSHOT, encode(snapshot).toString())
            .apply()

        Log.d(
            TAG,
            "cacheSaved playlists=${snapshot.playlists.size} " +
                "channel=${snapshot.channel != null}"
        )
    }

    fun favoritesPlaylistId(): String? =
        load()
            ?.playlists
            ?.firstOrNull { it.title.trim().equals("Preferiti", ignoreCase = true) }
            ?.id

    fun clear() {
        val appContext = context ?: return
        appContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SNAPSHOT)
            .apply()
        Log.d(TAG, "cacheCleared")
    }

    private fun encode(snapshot: YouTubeLibrarySnapshot): JSONObject {
        val root = JSONObject()
            .put("syncedAtEpochMs", snapshot.syncedAtEpochMs)
            .put("accessibleChannelCount", snapshot.accessibleChannelCount)

        snapshot.channel?.let { channel ->
            root.put(
                "channel",
                JSONObject()
                    .put("id", channel.id)
                    .put("title", channel.title)
                    .put("handle", channel.handle)
                    .put("thumbnailUrl", channel.thumbnailUrl)
                    .put("likesPlaylistId", channel.likesPlaylistId)
            )
        }

        val playlists = JSONArray()
        snapshot.playlists.forEach { playlist ->
            playlists.put(
                JSONObject()
                    .put("id", playlist.id)
                    .put("title", playlist.title)
                    .put("thumbnailUrl", playlist.thumbnailUrl)
                    .put("songCount", playlist.songCount)
            )
        }
        root.put("playlists", playlists)

        return root
    }

    private fun decode(root: JSONObject): YouTubeLibrarySnapshot {
        val channelJson = root.optJSONObject("channel")
        val channel = channelJson?.let {
            YouTubeChannel(
                id = it.getString("id"),
                title = it.optString("title").ifBlank { "Canale YouTube" },
                handle = it.optNullableString("handle"),
                thumbnailUrl = it.optNullableString("thumbnailUrl"),
                likesPlaylistId = it.optNullableString("likesPlaylistId"),
            )
        }

        val playlistsJson = root.optJSONArray("playlists") ?: JSONArray()
        val playlists = buildList {
            for (index in 0 until playlistsJson.length()) {
                val item = playlistsJson.getJSONObject(index)
                add(
                    Playlist(
                        id = item.getString("id"),
                        title = item.optString("title").ifBlank { "Playlist YouTube" },
                        thumbnailUrl = item.optNullableString("thumbnailUrl"),
                        songCount = if (item.isNull("songCount")) {
                            null
                        } else {
                            item.optInt("songCount")
                        },
                    )
                )
            }
        }

        return YouTubeLibrarySnapshot(
            channel = channel,
            playlists = playlists,
            syncedAtEpochMs = root.optLong("syncedAtEpochMs", 0L),
            accessibleChannelCount = root.optInt(
                "accessibleChannelCount",
                if (channel == null) 0 else 1,
            ),
        )
    }
}

private fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() }
}
