package app.lyra.music.genre

import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Non-blocking app-wide server monitoring. No networking on the main thread. */
object LyraServerMonitor {
    enum class Status { CHECKING, ONLINE, OFFLINE, DISABLED }
    private const val POLLING_MS = 5 * 60 * 1000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val _status = MutableStateFlow(Status.CHECKING)
    val status: StateFlow<Status> = _status.asStateFlow()
    private var started = false

    @Synchronized fun start() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                if (!GenreClassifierSettings.connectionEnabled.value) {
                    change(Status.DISABLED)
                } else {
                    change(Status.CHECKING)
                    val checked = GenreClassifierClient.probeHealth()
                    change(if (checked is GenreClassifierClient.ConnectionCheck.Ready)
                        Status.ONLINE else Status.OFFLINE)
                }
                // Wake immediately when the user changes the setting; otherwise recheck every 5 minutes.
                withTimeoutOrNull(POLLING_MS) { requests.receive() }
            }
        }
    }

    fun refresh() {
        if (!GenreClassifierSettings.connectionEnabled.value) change(Status.DISABLED)
        else change(Status.CHECKING)
        requests.trySend(Unit)
    }

    private fun change(next: Status) {
        if (_status.value != next) {
            _status.value = next
            Log.d("LYRA_SERVER", "availability=$next")
        }
    }
}
