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
    private const val BACKGROUND_POLLING_MS = 5 * 60 * 1000L
    private const val PLAYER_POLLING_MS = 15_000L
    private const val PLAYER_RETRY_MS = 5_000L
    @Volatile private var playerVisible = false
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
                    val checked = GenreClassifierClient.probeHealth()
                    change(when {
                        !GenreClassifierSettings.connectionEnabled.value -> Status.DISABLED
                        checked is GenreClassifierClient.ConnectionCheck.Ready -> Status.ONLINE
                        else -> Status.OFFLINE
                    })
                }
                val interval = when {
                    !playerVisible || _status.value == Status.DISABLED -> BACKGROUND_POLLING_MS
                    _status.value == Status.OFFLINE -> PLAYER_RETRY_MS
                    else -> PLAYER_POLLING_MS
                }
                withTimeoutOrNull(interval) { requests.receive() }
            }
        }
    }

    fun setPlayerVisible(visible: Boolean) {
        playerVisible = visible
        if (visible) requests.trySend(Unit)
    }

    fun refresh() {
        if (!GenreClassifierSettings.connectionEnabled.value) change(Status.DISABLED)
        else if (_status.value == Status.DISABLED) change(Status.CHECKING)
        requests.trySend(Unit)
    }

    private fun change(next: Status) {
        if (_status.value != next) {
            _status.value = next
            Log.d("LYRA_SERVER", "availability=$next")
        }
    }
}
