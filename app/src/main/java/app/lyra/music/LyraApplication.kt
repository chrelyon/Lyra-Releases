package app.lyra.music

import android.app.Application
import app.lyra.music.genre.GenreClassifierSettings
import app.lyra.music.genre.LyraServerMonitor
import app.lyra.music.audio.AudioSettings
import app.lyra.music.audio.LocalAudioScanWorker
import app.lyra.music.audio.LocalLoudnessStore
import app.lyra.music.diagnostics.DiagnosticsReporter
import app.lyra.music.diagnostics.DiagnosticsSettings
import app.lyra.music.diagnostics.LyraLog
import app.lyra.music.playback.PlaybackResumeSettings
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.cloud.CloudProfileSyncSettings
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.playback.ListeningHistoryStore
import app.lyra.music.playback.AutomaticPlaylistVarietyStore
import app.lyra.music.playback.SavedAutomaticPlaylistStore
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.youtube.likes.YouTubeLikesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.lyra.music.youtube.repository.DiscoveryQualityStore
import app.lyra.music.youtube.repository.DiscoverySettings

class LyraApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        DiagnosticsSettings.initialize(this)
        LyraLog.initialize(this)
        AudioSettings.initialize(this)
        LocalLoudnessStore.initialize(this)
        if (AudioSettings.normalizationEnabled.value && AudioSettings.backgroundScanEnabled.value) {
            LocalAudioScanWorker.schedule(this)
        }
        PlaybackResumeSettings.initialize(this)
        app.lyra.music.playback.AutomaticPlaylistManager.configureLocalLibrary {
            app.lyra.music.local.LocalMediaRepository(this).getSongs()
        }
        PersonalizationSettings.initialize(this)
        GenreClassifierSettings.initialize(this)
        LyraServerMonitor.start()
        ListeningHistoryStore.initialize(this)
        ListeningEventStore.initialize(this)
        ListeningProfileStore.initialize(this)
        CloudProfileSyncSettings.initialize(this)
        CloudProfileSync.initialize(this)
        SavedAutomaticPlaylistStore.initialize(this)
        AutomaticPlaylistVarietyStore.initialize(this)
        DiscoverySettings.initialize(this)
        DiscoveryQualityStore.initialize(this)
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            YouTubeLikesManager.changes.collect { change ->
                ListeningProfileStore.setLiked(change.song, change.liked)
            }
        }
        LyraLog.d("LYRA_APP", "application onCreate sdk=${android.os.Build.VERSION.SDK_INT}")
        DiagnosticsReporter.captureHistoricalExitIfNeeded(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            if (DiagnosticsSettings.isLoggingEnabled(this)) {
                LyraLog.writeFatal(throwable, thread.name)
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}
