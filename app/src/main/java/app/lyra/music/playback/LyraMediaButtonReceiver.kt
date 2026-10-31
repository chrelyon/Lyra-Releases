package app.lyra.music.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import app.lyra.music.diagnostics.LyraLog as Log

/** Avoid Android's foreground-service timeout after an explicit STOP cleared the saved queue.
 *  Keep Media3's stock receiver behavior when resumable media is actually present.
 */
@OptIn(UnstableApi::class)
class LyraMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val resumable = PlaybackResumeSettings.isRestoreLastSessionEnabled() &&
            PlaybackSessionStore(context).restore()?.queueState?.hasQueue == true
        if (!resumable) {
            Log.d("LYRA_EXTERNAL", "mediaButtonIgnored reason=noResumableSession")
        }
        return resumable
    }
}
