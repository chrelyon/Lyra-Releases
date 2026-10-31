package app.lyra.music.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    enum class SuggestionKind { QUERY, SONG, ARTIST }

    data class SmartSuggestion(
        val label: String,
        val subtitle: String,
        val kind: SuggestionKind,
        val song: Song? = null,
    )

    private val repository = YouTubeMusicRepository()
    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    private val _results = MutableStateFlow<List<Song>>(emptyList())
    val results: StateFlow<List<Song>> = _results.asStateFlow()

    private val _suggestions = MutableStateFlow<List<SmartSuggestion>>(emptyList())
    val suggestions: StateFlow<List<SmartSuggestion>> = _suggestions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _lastQuery = MutableStateFlow("")
    val lastQuery: StateFlow<String> = _lastQuery.asStateFlow()

    fun onQueryChanged(query: String) {
        val normalized = query.trim()
        suggestionJob?.cancel()
        if (normalized.length < MIN_SUGGESTION_CHARS) {
            _suggestions.value = emptyList()
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(SUGGESTION_DEBOUNCE_MS)
            runCatching { YouTube.searchSuggestions(normalized).getOrThrow() }
                .onSuccess { response ->
                    val smart = buildList {
                        response.recommendedItems.forEach { item ->
                            when (item) {
                                is SongItem -> add(
                                    SmartSuggestion(
                                        label = item.title,
                                        subtitle = item.artists.joinToString(", ") { it.name },
                                        kind = SuggestionKind.SONG,
                                        song = item.toSong(),
                                    )
                                )
                                is ArtistItem -> add(
                                    SmartSuggestion(
                                        label = item.title,
                                        subtitle = "Artista",
                                        kind = SuggestionKind.ARTIST,
                                    )
                                )
                                else -> Unit
                            }
                        }
                        response.queries.forEach { text ->
                            if (text.isNotBlank()) {
                                add(SmartSuggestion(text, "Ricerca", SuggestionKind.QUERY))
                            }
                        }
                    }
                        .distinctBy { "${it.kind}:${it.label.lowercase()}" }
                        .take(MAX_SUGGESTIONS)
                    _suggestions.value = smart
                }
                .onFailure { error ->
                    Log.d("LYRA_SEARCH", "suggestions failed message=${error.message}")
                    _suggestions.value = emptyList()
                }
        }
    }

    fun clearSuggestions() {
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
    }

    fun search(query: String) {
        val normalized = query.trim()
        if (normalized.length < 2) return
        suggestionJob?.cancel()
        _suggestions.value = emptyList()
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            _lastQuery.value = normalized

            runCatching { repository.searchSongs(normalized) }
                .onSuccess { songs ->
                    _results.value = songs.distinctBy { it.id }.take(MAX_RESULTS)
                    Log.d("LYRA_SEARCH", "youtube queryLength=${normalized.length} results=${songs.size}")
                }
                .onFailure { error ->
                    _results.value = emptyList()
                    _errorMessage.value = error.message ?: "Ricerca YouTube non riuscita"
                    Log.e("LYRA_SEARCH", "youtube failed message=${error.message}", error)
                }
            _isLoading.value = false
        }
    }

    private fun SongItem.toSong(): Song = Song(
        id = id,
        title = title,
        artist = artists.joinToString(", ") { it.name },
        durationSeconds = duration?.toLong() ?: 0L,
        thumbnailUrl = thumbnail,
    )

    private companion object {
        const val MAX_RESULTS = 100
        const val MAX_SUGGESTIONS = 8
        const val MIN_SUGGESTION_CHARS = 2
        const val SUGGESTION_DEBOUNCE_MS = 280L
    }
}
