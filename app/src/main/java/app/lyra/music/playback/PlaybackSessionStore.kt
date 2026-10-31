package app.lyra.music.playback

import android.content.Context
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.core.model.Song
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistenza locale minimale dello stato di riproduzione.
 *
 * Vengono salvati solo metadata della queue e posizione. Gli URL audio
 * googlevideo non vengono mai persistiti: al resume vengono risolti di nuovo.
 */
class PlaybackSessionStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun restore(): RestoredPlaybackSession? {
        val raw = preferences.getString(KEY_SESSION, null) ?: return null

        return runCatching {
            val root = JSONObject(raw)
            val version = root.optInt("version", 0)
            require(version in 1..SCHEMA_VERSION) {
                "Unsupported playback session schema=$version"
            }

            val songs = root.getJSONArray("songs").toSongs()
            val currentIndex = root.getInt("currentIndex")
            require(songs.isNotEmpty() && currentIndex in songs.indices) {
                "Invalid persisted queue"
            }

            val unshuffledSongs = restoreUnshuffledOrder(
                songs = songs,
                order = root.optJSONArray("unshuffledOrder"),
            )

            val repeatMode = runCatching {
                PlaybackRepeatMode.valueOf(
                    root.optString("repeatMode", PlaybackRepeatMode.OFF.name)
                )
            }.getOrDefault(PlaybackRepeatMode.OFF)

            val storedPositionMs = preferences
                .getLong(KEY_POSITION_MS, 0L)
                .coerceAtLeast(0L)

            val durationMs = songs[currentIndex].durationSeconds
                .coerceAtLeast(0L) * 1_000L
            val safePositionMs = if (durationMs > 0L) {
                storedPositionMs.coerceAtMost(durationMs)
            } else {
                storedPositionMs
            }

            RestoredPlaybackSession(
                queueState = PlaybackQueueUiState(
                    title = root.optString("title"),
                    songs = songs,
                    currentIndex = currentIndex,
                    isChangingTrack = false,
                    shuffleEnabled = root.optBoolean("shuffleEnabled", false),
                    repeatMode = repeatMode,
                    resumePositionMs = safePositionMs,
                ),
                unshuffledSongs = unshuffledSongs,
                positionMs = safePositionMs,
            )
        }.onFailure { error ->
            Log.w(
                "LYRA_RESTORE",
                "sessionRestoreFailed message=${error.message}; clearing persisted session"
            )
            clear()
        }.getOrNull()
    }

    fun saveQueue(
        queueState: PlaybackQueueUiState,
        unshuffledSongs: List<Song>,
        positionMs: Long,
    ) {
        if (!queueState.hasQueue) {
            clear()
            return
        }

        val root = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("title", queueState.title)
            .put("currentIndex", queueState.currentIndex)
            .put("shuffleEnabled", queueState.shuffleEnabled)
            .put("repeatMode", queueState.repeatMode.name)
            .put("songs", queueState.songs.toJsonArray())
            .put(
                "unshuffledOrder",
                buildUnshuffledOrder(
                    queueSongs = queueState.songs,
                    unshuffledSongs = unshuffledSongs
                        .ifEmpty { queueState.songs },
                ),
            )

        val committed = preferences.edit()
            .putString(KEY_SESSION, root.toString())
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))
            .commit()
        if (!committed) {
            Log.w("LYRA_RESTORE", "queueSnapshotCommitFailed")
        }
    }

    fun savePosition(
        positionMs: Long,
        synchronous: Boolean = false,
    ) {
        val editor = preferences.edit()
            .putLong(KEY_POSITION_MS, positionMs.coerceAtLeast(0L))

        if (synchronous) {
            if (!editor.commit()) {
                Log.w("LYRA_RESTORE", "positionCommitFailed")
            }
        } else {
            editor.apply()
        }
    }

    fun clear() {
        if (
            !preferences.edit()
                .remove(KEY_SESSION)
                .remove(KEY_POSITION_MS)
                .commit()
        ) {
            Log.w("LYRA_RESTORE", "sessionClearCommitFailed")
        }
    }

    private fun restoreUnshuffledOrder(
        songs: List<Song>,
        order: JSONArray?,
    ): List<Song> {
        if (order == null || order.length() != songs.size) return songs

        val indices = buildList(order.length()) {
            for (index in 0 until order.length()) {
                add(order.optInt(index, -1))
            }
        }

        if (
            indices.any { it !in songs.indices } ||
            indices.distinct().size != songs.size
        ) {
            return songs
        }

        // Riutilizziamo gli stessi oggetti Song della queue. In questo modo
        // anche dopo un restore la logica shuffle mantiene l'identita' delle
        // occorrenze duplicate dello stesso video.
        return indices.map(songs::get)
    }

    private fun buildUnshuffledOrder(
        queueSongs: List<Song>,
        unshuffledSongs: List<Song>,
    ): JSONArray {
        val used = BooleanArray(queueSongs.size)
        val indices = mutableListOf<Int>()

        unshuffledSongs.forEach { target ->
            val identityIndex = queueSongs.indices.firstOrNull { index ->
                !used[index] && queueSongs[index] === target
            }

            val matchingIndex = identityIndex ?: queueSongs.indices.firstOrNull { index ->
                !used[index] && queueSongs[index] == target
            }

            if (matchingIndex != null) {
                used[matchingIndex] = true
                indices += matchingIndex
            }
        }

        queueSongs.indices
            .filterNot { used[it] }
            .forEach { index -> indices.add(index) }

        return JSONArray().also { array ->
            indices.forEach { index -> array.put(index) }
        }
    }

    private fun JSONArray.toSongs(): List<Song> {
        return buildList(length()) {
            for (index in 0 until length()) {
                val item = getJSONObject(index)
                val id = item.getString("id")
                if (id.isBlank()) continue

                add(
                    Song(
                        id = id,
                        title = item.optString("title"),
                        artist = item.optString("artist"),
                        durationSeconds = item.optLong("durationSeconds", 0L)
                            .coerceAtLeast(0L),
                        thumbnailUrl = item
                            .optString("thumbnailUrl")
                            .takeIf { it.isNotBlank() },
                        playbackUri = item
                            .optString("playbackUri")
                            .takeIf { it.isNotBlank() },
                        album = item
                            .optString("album")
                            .takeIf { it.isNotBlank() },
                        albumId = item
                            .takeIf { it.has("albumId") }
                            ?.optLong("albumId"),
                    )
                )
            }
        }
    }

    private fun List<Song>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { song ->
                array.put(
                    JSONObject()
                        .put("id", song.id)
                        .put("title", song.title)
                        .put("artist", song.artist)
                        .put("durationSeconds", song.durationSeconds)
                        .apply {
                            song.thumbnailUrl
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("thumbnailUrl", it) }
                            song.playbackUri
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("playbackUri", it) }
                            song.album
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put("album", it) }
                            song.albumId?.let { put("albumId", it) }
                        },
                )
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "lyra_playback_session"
        const val KEY_SESSION = "session_json"
        const val KEY_POSITION_MS = "position_ms"
        const val SCHEMA_VERSION = 2
    }
}

data class RestoredPlaybackSession(
    val queueState: PlaybackQueueUiState,
    val unshuffledSongs: List<Song>,
    val positionMs: Long,
)
