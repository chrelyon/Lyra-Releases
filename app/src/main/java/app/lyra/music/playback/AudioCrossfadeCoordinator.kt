package app.lyra.music.playback

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.lyra.music.audio.AudioNormalizationController
import app.lyra.music.audio.AudioSettings
import app.lyra.music.audio.AudioTransitionPolicy
import app.lyra.music.audio.LocalLoudnessStore
import app.lyra.music.diagnostics.LyraLog as Log

/**
 * Crossfade opzionale tra due decoder. Il player della MediaSession resta sempre
 * quello principale: la preview non acquisisce audio focus e non pubblica metadata.
 *
 * Se la sorgente anticipata non e' READY in tempo, la transizione ricade sul
 * normale gapless Media3. Niente skip forzati o riproduzione dalla rete nel main thread.
 */
@OptIn(UnstableApi::class)
internal class AudioCrossfadeCoordinator(
    private val context: Context,
    private val primary: ExoPlayer,
    private val repository: PlaybackRepository,
    private val store: PlaybackSessionStore,
    private val primaryNormalization: AudioNormalizationController,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var secondary: ExoPlayer? = null
    private var secondaryNormalization: AudioNormalizationController? = null
    private var fromId: String? = null
    private var toId: String? = null
    private var fading = false
    private var handoff = false
    private var handoffStartedAtMs = 0L
    private var handoffRampAtMs = 0L
    private var cachedFromId: String? = null
    private var cachedToId: String? = null
    private var cachedFrom: app.lyra.music.core.model.Song? = null
    private var cachedTo: app.lyra.music.core.model.Song? = null

    private val tick = object : Runnable {
        override fun run() {
            runCatching { refresh() }.onFailure { error ->
                Log.w("LYRA_CROSSFADE", "fallback gapless: ${error.message}")
                cancel()
            }
            handler.postDelayed(this, if (AudioSettings.crossfadeEnabled.value) 85L else 600L)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun release() {
        handler.removeCallbacks(tick)
        cancel()
    }

    fun onMainPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (!playWhenReady) secondary?.pause()
        else if (fading || handoff) secondary?.play()
    }

    fun onTransition(item: MediaItem?, reason: Int) {
        if (
            fading &&
            reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
            item?.mediaId == toId &&
            secondary?.isPlaying == true
        ) {
            handoff = true
            fading = false
            handoffStartedAtMs = SystemClock.elapsedRealtime()
            handoffRampAtMs = 0L
            primaryNormalization.setTransitionMultiplier(0f)
            // La preview ha gia' eseguito l'inizio del brano successivo.
            // Allinea il player principale mentre la preview nasconde il seek.
            val previewPosition = secondary?.currentPosition?.coerceAtLeast(0L) ?: 0L
            primary.seekTo(previewPosition)
            Log.d("LYRA_CROSSFADE", "handoff mediaId=${item?.mediaId} position=$previewPosition")
        } else {
            cancel()
        }
    }

    private fun refresh() {
        if (!AudioSettings.gaplessEnabled.value || !AudioSettings.crossfadeEnabled.value) {
            if (secondary != null || fading || handoff) cancel()
            return
        }
        if (handoff) {
            advanceHandoff()
            return
        }
        if (!primary.playWhenReady) return
        if (primary.mediaItemCount < 2 || primary.playbackState != Player.STATE_READY) {
            if (fading) cancel()
            return
        }
        val current = primary.currentMediaItem ?: return
        val nextIndex = primary.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) { cancel(); return }
        val next = primary.getMediaItemAt(nextIndex)
        // Non sovrapporre lo stesso brano ripetuto; il gapless e' sufficiente.
        if (current.mediaId == next.mediaId) { cancel(); return }
        if (fromId != null && (fromId != current.mediaId || toId != next.mediaId)) cancel()
        val duration = primary.duration
        if (duration == C.TIME_UNSET || duration <= 0) { cancel(); return }
        val remaining = duration - primary.currentPosition
        if (remaining <= 0) return
        val fadeMs = AudioSettings.crossfadeDurationMs.value
        // Un seek manuale all'indietro deve annullare una dissolvenza gia' partita.
        if (fading && remaining > fadeMs + 500L) { cancel(); return }
        // Lo store puo' contenere molte tracce: rileggilo soltanto quando
        // cambiano le due tracce candidate, non ogni 85 ms.
        if (cachedFromId != current.mediaId || cachedToId != next.mediaId) {
            val queue = store.restore()?.queueState
            cachedFromId = current.mediaId
            cachedToId = next.mediaId
            cachedFrom = queue?.songs?.firstOrNull { it.id == current.mediaId }
            cachedTo = queue?.songs?.firstOrNull { it.id == next.mediaId }
        }
        val from = cachedFrom
        val to = cachedTo
        if (from == null || to == null || !AudioTransitionPolicy.canCrossfade(
                enabled = true,
                queueHasNext = true,
                repeatOne = primary.repeatMode == Player.REPEAT_MODE_ONE,
                remainingDurationMs = remaining,
                fadeMs = fadeMs,
                firstAlbum = from.album,
                secondAlbum = to.album,
                firstAlbumId = from.albumId,
                secondAlbumId = to.albumId,
                firstDurationMs = duration,
            )
        ) {
            cancel()
            return
        }
        if (secondary == null && remaining <= fadeMs + PREPARE_AHEAD_MS && remaining > fadeMs) {
            // Su rete mobile a consumo evitiamo un secondo stream simultaneo.
            val metered = runCatching {
                context.getSystemService(ConnectivityManager::class.java)
                    ?.isActiveNetworkMetered ?: false
            }.getOrDefault(true)
            if (metered && !to.isLocal) return
            preparePreview(current.mediaId, next, to)
        }
        val preview = secondary ?: return
        if (!fading && remaining <= fadeMs && preview.playbackState == Player.STATE_READY) {
            fading = true
            preview.play()
            Log.d("LYRA_CROSSFADE", "started from=$fromId to=$toId duration=$fadeMs")
        }
        if (fading) {
            val progress = ((fadeMs - remaining).toFloat() / fadeMs).coerceIn(0f, 1f)
            primaryNormalization.setTransitionMultiplier(1f - progress)
            secondaryNormalization?.setTransitionMultiplier(progress)
        }
    }

    private fun preparePreview(currentId: String, nextItem: MediaItem, nextSong: app.lyra.music.core.model.Song) {
        fromId = currentId
        toId = nextItem.mediaId
        val preview = LyraPlayer.create(
            context = context,
            playbackRepository = repository,
            onSourceResolved = { source ->
                handler.post {
                    if (toId == source.videoId) {
                        if (source.loudnessDb != null) secondaryNormalization?.onTrackLoudnessChanged(source.loudnessDb)
                        else secondaryNormalization?.resetForTrackWithoutLoudness()
                    }
                }
            },
        )
        secondary = preview
        preview.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            false,
        )
        preview.setHandleAudioBecomingNoisy(false)
        val normalizer = AudioNormalizationController(preview)
        secondaryNormalization = normalizer
        normalizer.onAudioSessionIdChanged(preview.audioSessionId)
        if (nextSong.isLocal) {
            normalizer.onLocalTrackMeasurement(LocalLoudnessStore.get(nextSong))
        }
        normalizer.setTransitionMultiplier(0f)
        preview.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                normalizer.onAudioSessionIdChanged(audioSessionId)
            }
        })
        preview.setMediaItem(nextItem)
        preview.prepare()
    }

    private fun advanceHandoff() {
        val preview = secondary ?: run { cancel(); return }
        if (!primary.playWhenReady) { preview.pause(); return }
        val now = SystemClock.elapsedRealtime()
        if (now - handoffStartedAtMs > HANDOFF_TIMEOUT_MS) { cancel(); return }
        if (handoffRampAtMs == 0L) {
            if (primary.playbackState != Player.STATE_READY || !primary.isPlaying) return
            handoffRampAtMs = now
        }
        val progress = ((now - handoffRampAtMs).toFloat() / HANDOFF_RAMP_MS).coerceIn(0f, 1f)
        primaryNormalization.setTransitionMultiplier(progress)
        secondaryNormalization?.setTransitionMultiplier(1f - progress)
        if (progress >= 1f) cancel()
    }

    private fun cancel() {
        primaryNormalization.setTransitionMultiplier(1f)
        secondary?.run {
            volume = 0f
            pause()
        }
        secondaryNormalization?.release()
        secondaryNormalization = null
        secondary?.run {
            volume = 0f
            runCatching { stop() }
            release()
        }
        secondary = null
        fromId = null
        toId = null
        fading = false
        handoff = false
        handoffStartedAtMs = 0L
        handoffRampAtMs = 0L
        cachedFromId = null
        cachedToId = null
        cachedFrom = null
        cachedTo = null
    }

    private companion object {
        const val PREPARE_AHEAD_MS = 12_000L
        const val HANDOFF_RAMP_MS = 350L
        const val HANDOFF_TIMEOUT_MS = 5_000L
    }
}
