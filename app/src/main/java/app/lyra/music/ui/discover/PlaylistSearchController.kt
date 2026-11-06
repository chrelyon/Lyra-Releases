package app.lyra.music.ui.discover

import app.lyra.music.youtube.repository.PlaylistSearchKind
import app.lyra.music.youtube.repository.PlaylistSearchTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PlaylistSearchState(
    val query: String = "",
    val kind: PlaylistSearchKind = PlaylistSearchKind.BOTH,
    val suggestions: List<PlaylistSearchTarget> = emptyList(),
    val selected: PlaylistSearchTarget? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

/** Typed results only; stale responses cannot replace a newer query or selection. */
class PlaylistSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String, PlaylistSearchKind) -> List<PlaylistSearchTarget>,
    private val debounceMs: Long = 300,
) {
    private val mutable = MutableStateFlow(PlaylistSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L

    fun queryChanged(query: String) {
        mutable.value = mutable.value.copy(query = query.take(120), selected = null)
        refresh()
    }

    fun kindChanged(kind: PlaylistSearchKind) {
        if (kind == mutable.value.kind) return
        mutable.value = mutable.value.copy(kind = kind, selected = null)
        refresh()
    }

    fun refresh(immediate: Boolean = false) {
        val expected = ++revision
        job?.cancel()
        val snapshot = mutable.value
        val query = snapshot.query.trim()
        mutable.value = snapshot.copy(suggestions = emptyList(), loading = query.length >= 2, error = null, searched = false)
        if (query.length < 2) return
        job = scope.launch {
            try {
                if (!immediate) delay(debounceMs)
                val found = search(query, snapshot.kind).filter { snapshot.kind == PlaylistSearchKind.BOTH || it.kind == snapshot.kind }.distinctBy { "${it.kind}:${it.id}" }.take(12)
                if (revision == expected) mutable.value = mutable.value.copy(suggestions = found, loading = false, searched = true)
            } catch (_: TimeoutCancellationException) {
                if (revision == expected) mutable.value = mutable.value.copy(loading = false, error = "Ricerca scaduta. Riprova.", searched = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (revision == expected) mutable.value = mutable.value.copy(loading = false, error = "Ricerca non disponibile. Riprova.", searched = true)
            }
        }
    }

    fun select(target: PlaylistSearchTarget) {
        if (target !in mutable.value.suggestions || (mutable.value.kind != PlaylistSearchKind.BOTH && target.kind != mutable.value.kind)) return
        ++revision; job?.cancel()
        mutable.value = mutable.value.copy(query = target.title, selected = target, suggestions = emptyList(), loading = false, error = null)
    }

    fun clear() {
        ++revision; job?.cancel()
        mutable.value = PlaylistSearchState()
    }
}
