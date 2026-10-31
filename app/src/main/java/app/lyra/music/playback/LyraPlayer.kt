package app.lyra.music.playback

import android.content.Context
import android.net.ConnectivityManager
import app.lyra.music.diagnostics.LyraLog as Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.lyra.music.audio.AudioBufferPolicy
import app.lyra.music.audio.AudioSettings

/**
 * Factory del solo motore audio di Lyra.
 *
 * Il ciclo di vita del player e' gestito da [PlaybackService].
 */
object LyraPlayer {

    @OptIn(UnstableApi::class)
    fun create(
        context: Context,
        playbackRepository: PlaybackRepository,
        onSourceResolved: (PlaybackSource) -> Unit = {},
        onResolverReady: (LyraPlaybackDataSpecResolver) -> Unit = {},
    ): ExoPlayer {
        // Profilo scelto all'avvio del servizio; la variazione della rete
        // richiede un nuovo player per aggiornare il LoadControl.
        val metered = runCatching {
            context.getSystemService(ConnectivityManager::class.java)
                ?.isActiveNetworkMetered ?: false
        }.getOrDefault(false)
        val profile = AudioBufferPolicy.select(
            adaptive = AudioSettings.adaptiveBufferingEnabled.value,
            metered = metered,
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                profile.minBufferMs,
                profile.maxBufferMs,
                profile.startBufferMs,
                profile.rebufferMs,
            )
            .setBufferDurationsMsForLocalPlayback(2_000, 12_000, 500, 1_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val appContext = context.applicationContext
        val resolver = LyraPlaybackDataSpecResolver(playbackRepository, onSourceResolved)
        onResolverReady(resolver)
        val resolvingDataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(appContext),
            resolver,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(resolvingDataSourceFactory)

        return ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                // true = Media3 gestisce automaticamente l'audio focus.
                setAudioAttributes(audioAttributes, true)

                // Pausa automatica quando viene scollegata l'uscita audio
                // (es. jack/Bluetooth), evitando che la musica parta dallo speaker.
                setHandleAudioBecomingNoisy(true)

                // Media3 puo' precaricare PCM/byte dello stream successivo della
                // playlist nativa, senza competere con il buffer corrente.
                // Solo Wi-Fi/rete non a consumo per contenere traffico mobile.
                preloadConfiguration = if (
                    AudioSettings.preloadAudioEnabled.value && !metered
                ) ExoPlayer.PreloadConfiguration(5_000_000L)
                else ExoPlayer.PreloadConfiguration.DEFAULT

                addListener(LyraPlayerLogListener)
            }
    }
}

private object LyraPlayerLogListener : Player.Listener {

    override fun onPlaybackStateChanged(playbackState: Int) {
        val state = when (playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> playbackState.toString()
        }

        Log.d("LYRA_PLAYER", "state=$state")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        Log.d("LYRA_PLAYER", "isPlaying=$isPlaying")
    }

    override fun onPlayerError(error: PlaybackException) {
        Log.e(
            "LYRA_PLAYER",
            "errorCode=${error.errorCode} errorName=${error.errorCodeName} message=${error.message}",
            error
        )
    }
}
