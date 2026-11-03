package app.lyra.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.lyra.music.diagnostics.LyraLog as Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.lyra.music.core.model.Song
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ponte leggero tra la UI e il MediaSessionService.
 *
 * Le azioni richieste mentre la connessione al servizio e' ancora in corso
 * vengono accodate e inviate appena il MediaController e' pronto.
 *
 * Lo stato osservato dalla UI viene sempre ricavato dal MediaController:
 * il servizio rimane quindi l'unica sorgente di verita' del playback.
 */
class PlaybackController(
    context: Context
) {

    private val appContext = context.applicationContext

    private val pendingActions =
        ConcurrentLinkedQueue<(MediaController) -> Unit>()

    private val _uiState = MutableStateFlow(PlaybackUiState())

    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressRunnable = object : Runnable {
        override fun run() {
            controller?.let(::publishUiState)
            mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    @Volatile
    private var controller: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishUiState(player)
        }
    }

    private val controllerFuture = MediaController.Builder(
        appContext,
        SessionToken(
            appContext,
            ComponentName(appContext, PlaybackService::class.java)
        )
    ).buildAsync()

    init {
        controllerFuture.addListener(
            {
                runCatching {
                    controllerFuture.get()
                }.onSuccess { connectedController ->
                    controller = connectedController
                    connectedController.addListener(playerListener)
                    publishUiState(connectedController)
                    mainHandler.removeCallbacks(progressRunnable)
                    mainHandler.post(progressRunnable)
                    Log.d("LYRA_PLAYER", "controller=CONNECTED")

                    while (true) {
                        val action = pendingActions.poll() ?: break
                        action(connectedController)
                    }
                }.onFailure { error ->
                    Log.e(
                        "LYRA_PLAYER",
                        "controller=CONNECTION_FAILED message=${error.message}",
                        error
                    )
                }
            },
            ContextCompat.getMainExecutor(appContext)
        )
    }

    fun play(
        source: PlaybackSource,
        song: Song,
        startPositionMs: Long = 0L,
        contextLabel: String? = null,
    ) {
        withController { mediaController ->
            val mediaItem = PlaybackMediaItemFactory.playable(
                source = source,
                song = song,
                artworkUri = AutoArtworkProvider.register(appContext, song.artworkUrlForPlayback),
                contextLabel = contextLabel,
            )

            val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)

            Log.d(
                "LYRA_PLAYER",
                "load videoId=${source.videoId} client=${source.clientName} " +
                    "mime=${source.mimeType} bitrate=${source.bitrate} " +
                    "artwork=${!song.artworkUrlForPlayback.isNullOrBlank()} " +
                    "startPositionMs=$safeStartPositionMs"
            )

            if (safeStartPositionMs > 0L) {
                mediaController.setMediaItem(mediaItem, safeStartPositionMs)
            } else {
                mediaController.setMediaItem(mediaItem)
            }
            mediaController.prepare()
            mediaController.play()
        }
    }

    fun playLocal(
        song: Song,
        startPositionMs: Long = 0L,
        contextLabel: String? = null,
    ) {
        withController { mediaController ->
            val mediaItem = PlaybackMediaItemFactory.local(
                song = song,
                artworkUri = AutoArtworkProvider.register(appContext, song.artworkUrlForPlayback),
                contextLabel = contextLabel,
            )
            val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)

            Log.d(
                "LYRA_PLAYER",
                "loadLocal mediaId=${song.id} artwork=${!song.artworkUrlForPlayback.isNullOrBlank()} " +
                    "startPositionMs=$safeStartPositionMs"
            )

            if (safeStartPositionMs > 0L) {
                mediaController.setMediaItem(mediaItem, safeStartPositionMs)
            } else {
                mediaController.setMediaItem(mediaItem)
            }
            mediaController.prepare()
            mediaController.play()
        }
    }

    /** Usa una timeline Media3 nativa: ExoPlayer prepara la transizione successiva. */
    fun playNativeQueue(
        songs: List<Song>,
        startIndex: Int,
        startPositionMs: Long,
        contextLabel: String,
        repeatMode: PlaybackRepeatMode,
    ) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        withController { mediaController ->
            val items = songs.map { song ->
                PlaybackMediaItemFactory.deferred(
                    song,
                    artworkUri = AutoArtworkProvider.register(appContext, song.artworkUrlForPlayback),
                    contextLabel = contextLabel,
                )
            }
            mediaController.setMediaItems(items, startIndex, startPositionMs.coerceAtLeast(0L))
            mediaController.repeatMode = when (repeatMode) {
                PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
            }
            // La coda viene gia' ordinata dalla UI quando shuffle e' attivo.
            mediaController.shuffleModeEnabled = false
            mediaController.prepare()
            mediaController.play()
        }
    }

    /** Edita la timeline Media3 senza interrompere il brano corrente. */
    fun insertNativeItem(index: Int, song: Song, contextLabel: String) {
        withController { mediaController ->
            val item = PlaybackMediaItemFactory.deferred(
                song,
                artworkUri = AutoArtworkProvider.register(appContext, song.artworkUrlForPlayback),
                contextLabel = contextLabel,
            )
            mediaController.addMediaItem(index.coerceIn(0, mediaController.mediaItemCount), item)
        }
    }

    fun appendNativeItemsIfMatching(oldIds: List<String>, additional: List<Song>, contextLabel: String) {
        if (additional.isEmpty()) return
        withController { mediaController ->
            if ((0 until mediaController.mediaItemCount).map { mediaController.getMediaItemAt(it).mediaId } != oldIds) return@withController
            val items = additional.map { song ->
                PlaybackMediaItemFactory.deferred(
                    song,
                    artworkUri = AutoArtworkProvider.register(appContext, song.artworkUrlForPlayback),
                    contextLabel = contextLabel,
                )
            }
            mediaController.addMediaItems(items)
        }
    }

    fun moveNativeItem(fromIndex: Int, toIndex: Int) {
        withController { mediaController ->
            if (fromIndex in 0 until mediaController.mediaItemCount && toIndex in 0 until mediaController.mediaItemCount) {
                mediaController.moveMediaItem(fromIndex, toIndex)
            }
        }
    }

    fun removeNativeItem(index: Int) {
        withController { mediaController ->
            if (index in 0 until mediaController.mediaItemCount) mediaController.removeMediaItem(index)
        }
    }

    fun seekToQueueIndex(index: Int) {
        withController { mediaController ->
            if (index in 0 until mediaController.mediaItemCount) {
                mediaController.seekTo(index, 0L)
            }
        }
    }

    fun setNativeRepeatMode(repeatMode: PlaybackRepeatMode) {
        withController { mediaController ->
            if (mediaController.mediaItemCount > 1) {
                mediaController.repeatMode = when (repeatMode) {
                    PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                    PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
                    PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                }
            }
        }
    }

    fun pause() {
        Log.d("LYRA_PLAYER", "command=PAUSE")
        withController { it.pause() }
    }

    fun resume() {
        Log.d("LYRA_PLAYER", "command=RESUME")
        withController { mediaController ->
            if (mediaController.playbackState == Player.STATE_ENDED) {
                mediaController.seekTo(0L)
            }
            mediaController.play()
        }
    }

    fun seekTo(positionMs: Long) {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        Log.d("LYRA_PLAYER", "command=SEEK positionMs=$safePositionMs")
        withController { mediaController ->
            mediaController.seekTo(safePositionMs)
            publishUiState(mediaController)
        }
    }

    fun stop() {
        Log.d("LYRA_PLAYER", "command=STOP")
        withController { mediaController ->
            mediaController.stop()
            mediaController.clearMediaItems()
        }
    }

    fun release() {
        mainHandler.removeCallbacks(progressRunnable)
        controller?.removeListener(playerListener)
        controller = null
        pendingActions.clear()
        MediaController.releaseFuture(controllerFuture)
    }

    private fun publishUiState(player: Player) {
        val metadata = player.currentMediaItem?.mediaMetadata
        val durationMs = normalizeTimeMs(player.duration)
        val positionMs = normalizeTimeMs(player.currentPosition)
            .let { position ->
                if (durationMs > 0L) position.coerceAtMost(durationMs) else position
            }

        _uiState.value = PlaybackUiState(
            connected = true,
            hasMediaItem = player.mediaItemCount > 0,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            isEnded = player.playbackState == Player.STATE_ENDED,
            mediaId = player.currentMediaItem?.mediaId.orEmpty(),
            title = metadata?.title?.toString().orEmpty(),
            artist = metadata?.artist?.toString().orEmpty(),
            // subtitle e' riservato al cantante nei player Android Auto.
            // La playlist resta separata negli extras interni Media3.
            contextLabel = metadata?.extras
                ?.getString(PlaybackMediaItemFactory.EXTRA_CONTEXT_LABEL)
                .orEmpty(),
            artworkUrl = metadata?.artworkUri?.toString(),
            positionMs = positionMs,
            durationMs = durationMs,
            mediaItemCount = player.mediaItemCount,
            mediaItemIndex = player.currentMediaItemIndex,
        )
    }

    private fun normalizeTimeMs(value: Long): Long {
        return if (value == C.TIME_UNSET || value < 0L) 0L else value
    }

    private fun withController(
        action: (MediaController) -> Unit
    ) {
        val current = controller

        if (current != null) {
            action(current)
        } else {
            pendingActions.add(action)
        }
    }
    private companion object {
        const val PROGRESS_UPDATE_INTERVAL_MS = 500L
    }
}
