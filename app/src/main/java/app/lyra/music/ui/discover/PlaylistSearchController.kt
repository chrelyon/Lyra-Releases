package app.lyra.music.ui.discover

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
    val suggestions: List<PlaylistSearchTarget> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
)

/** Completamento automatico: una risposta datata non sostituisce mai una query piu' recente. */
class PlaylistSearchController(
    private val scope: CoroutineScope,
    private val search: suspend (String) -> List<PlaylistSearchTarget>,
    private val debounceMs: Long = 300,
) {
    private val mutable = MutableStateFlow(PlaylistSearchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var revision = 0L

    fun queryChanged(query: String) {
        mutable.value = mutable.value.copy(query = query.take(120))
        refresh()
    }

    fun refresh(immediate: Boolean = false) {
        val expected = ++revision
        job?.cancel()
        val query = mutable.value.query.trim()
        mutable.value = mutable.value.copy(suggestions = emptyList(), loading = query.length >= 2, error = null, searched = false)
        if (query.length < 2) return
        job = scope.launch {
            try {
                if (!immediate) delay(debounceMs)
                val found = search(query).distinctBy { it.id }
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

    fun clear() {
        ++revision; job?.cancel()
        mutable.value = PlaylistSearchState()
    }
}
