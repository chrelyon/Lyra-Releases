package app.lyra.music.playback

import android.os.Build
import androidx.media3.common.Player
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reports Media3's *actual* current item and a bounded queue to Lyra Server
 * 0.3.25. The playback service owns this reporter: screen-off, Bluetooth and
 * Android Auto do not depend on the phone's Compose ViewModel.
 *
 * The server does not receive signed audio URLs, cookies, Google account IDs,
 * raw playback events, or local file paths. If the user has disabled Ubuntu
 * profile sync we do not report telemetry at all.
 */
internal object PlaybackMonitorReporter {
    private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
    private const val MAX_QUEUE = 40

    private fun clean(value: String, limit: Int): String =
        value.filter { it.code >= 32 && it.code != 127 }.trim().take(limit)

    private fun deviceLabel(): String = clean(
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android" },
        48,
    )

    private fun trackJson(id: String, title: String, artist: String): JSONObject =
        JSONObject()
            .put("id", id)
            .put("title", clean(title, 160).ifBlank { "Brano Lyra" })
            .put("artist", clean(artist, 120).ifBlank { "Artista sconosciuto" })

    /** Main-thread snapshot; no I/O, and never introspects an unsigned URL. */
    fun snapshot(
        player: Player,
        queue: PlaybackQueueUiState?,
        source: String,
    ): JSONObject? {
        val mediaId = player.currentMediaItem?.mediaId.orEmpty()
        // Local files may have non-YouTube IDs; never transmit their paths.
        if (mediaId.isNotEmpty() && !YOUTUBE_ID.matches(mediaId)) return null

        val active = player.isPlaying && mediaId.isNotEmpty()
        val song = queue?.songs?.firstOrNull { it.id == mediaId }
        val metadata = player.currentMediaItem?.mediaMetadata
        val current = mediaId.takeIf(YOUTUBE_ID::matches)?.let {
            trackJson(
                it,
                song?.title ?: metadata?.title?.toString().orEmpty(),
                song?.artist ?: metadata?.artist?.toString().orEmpty(),
            )
        }

        val allSongs = queue?.songs.orEmpty()
        val activeIndex = allSongs.indexOfFirst { it.id == mediaId }
        val from = activeIndex.coerceAtLeast(0)
        val window = allSongs.drop(from).filter { YOUTUBE_ID.matches(it.id) }.take(MAX_QUEUE)
        val queueIndex = window.indexOfFirst { it.id == mediaId }
        val tracks = JSONArray()
        window.forEach { item ->
            tracks.put(trackJson(item.id, item.title, item.artist))
        }
        val isServer = source.startsWith("Server") &&
            (queue?.title?.startsWith("Playlist Automatica") == true ||
                queue?.title?.startsWith("Radio:") == true)
        return JSONObject()
            .put("schemaVersion", 1)
            .put("deviceLabel", deviceLabel())
            .put("playing", active)
            .put("engine", if (isServer) "server" else "local")
            .put("playlist", clean(queue?.title.orEmpty(), 120))
            .put("current", current ?: JSONObject.NULL)
            .put("queue", tracks)
            .put("currentIndex", queueIndex)
    }

    suspend fun publish(payload: JSONObject) {
        if (CloudProfileSync.playlistOwner() == null) return
        try {
            CloudProfileSync.playlistRequest(
                "POST", "/v3/playlists/playback-status", payload,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Playback must always continue even when telemetry is offline.
            Log.w(
                "LYRA_MONITOR",
                "Playback monitor unavailable: ${error.javaClass.simpleName}",
            )
        }
    }
}
