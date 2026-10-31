package app.lyra.music.ui.home

import app.lyra.music.diagnostics.LyraLog as Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.YouTubeLibrarySnapshot
import app.lyra.music.playback.ListeningHistoryStore
import app.lyra.music.playback.SavedAutomaticPlaylistStore
import app.lyra.music.youtube.auth.AuthorizationRequiredException
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import app.lyra.music.youtube.auth.YouTubeAuthorizationState
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel : ViewModel() {

    private val repository = YouTubeMusicRepository()

    val authorizationState: StateFlow<YouTubeAuthorizationState> =
        YouTubeAuthorizationManager.state

    private var youtubePlaylists: List<Playlist> = emptyList()
    private val _playlists = MutableStateFlow<List<Playlist>>(SavedAutomaticPlaylistStore.asPlaylists())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    val recentHistory = ListeningHistoryStore.entries

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _syncWarning = MutableStateFlow<String?>(null)
    val syncWarning: StateFlow<String?> = _syncWarning.asStateFlow()

    private var handledAuthorizationGeneration: Long? = null
    private var lastSuccessfulSyncEpochMs = 0L

    init {
        viewModelScope.launch {
            SavedAutomaticPlaylistStore.items.collect {
                refreshCombinedPlaylists()
            }
        }
        viewModelScope.launch {
            authorizationState.collect { state ->
                if (state.isChecking || state.isAuthorizing) return@collect

                if (state.isAuthorized) {
                    // Nome/avatar arrivano in un secondo momento tramite UserInfo e
                    // aggiornano lo stesso StateFlow. Non devono provocare una nuova
                    // sincronizzazione o far lampeggiare/sparire la libreria.
                    if (handledAuthorizationGeneration != state.authorizationGeneration) {
                        handledAuthorizationGeneration = state.authorizationGeneration
                        restoreCacheAndSync()
                    }
                } else if (!state.isChecking && !state.isAuthorizing) {
                    clearDisplayedLibrary()
                }
            }
        }
    }

    fun refreshLibrary() {
        if (!authorizationState.value.isAuthorized) return
        syncLibrary(showBlockingSpinner = false)
    }

    /**
     * Chiamato quando Lyra torna in primo piano.
     * Non facciamo polling continuo: al rientro aggiorniamo la libreria se
     * l'ultima sync non e' appena avvenuta. In questo modo una modifica fatta
     * in YouTube Music compare quasi subito tornando a Lyra, senza consumare
     * quota mentre l'app resta aperta.
     */
    fun onAppForegrounded() {
        val auth = authorizationState.value
        if (!auth.isAuthorized || auth.isChecking || auth.isAuthorizing) return
        if (_isLoading.value || _isRefreshing.value) return

        val elapsed = System.currentTimeMillis() - lastSuccessfulSyncEpochMs
        if (lastSuccessfulSyncEpochMs != 0L && elapsed < FOREGROUND_REFRESH_MIN_INTERVAL_MS) {
            Log.d(
                "LYRA_LIBRARY",
                "foregroundSync skipped elapsedMs=$elapsed"
            )
            return
        }

        Log.d("LYRA_LIBRARY", "foregroundSync requested elapsedMs=$elapsed")
        syncLibrary(showBlockingSpinner = _playlists.value.isEmpty())
    }

    fun disconnect() {
        if (!authorizationState.value.isAuthorized) return

        _isRefreshing.value = true
        _errorMessage.value = null
        _syncWarning.value = null

        YouTubeAuthorizationManager.disconnect {
            YouTubeLibraryCache.clear()
            clearDisplayedLibrary()
            _isRefreshing.value = false
        }
    }

    private fun restoreCacheAndSync() {
        val cached = YouTubeLibraryCache.load()
        if (cached != null) {
            applySnapshot(cached)
            _isLoading.value = false
            _syncWarning.value = null
            syncLibrary(showBlockingSpinner = false)
        } else {
            syncLibrary(showBlockingSpinner = true)
        }
    }

    private fun syncLibrary(showBlockingSpinner: Boolean) {
        if (!authorizationState.value.isAuthorized) return
        if (_isLoading.value || _isRefreshing.value) return

        viewModelScope.launch {
            val hasVisibleCache = _playlists.value.isNotEmpty()
            _isLoading.value = showBlockingSpinner && !hasVisibleCache
            _isRefreshing.value = !_isLoading.value
            _errorMessage.value = null
            _syncWarning.value = null

            runCatching {
                repository.getLibrarySnapshot()
            }.onSuccess { snapshot ->
                applySnapshot(snapshot)
                YouTubeLibraryCache.save(snapshot)
                Log.d(
                    "LYRA_LIBRARY",
                    "syncComplete musicPlaylists=${snapshot.playlists.size}"
                )
            }.onFailure { error ->
                if (error is AuthorizationRequiredException) {
                    _errorMessage.value = null
                } else if (hasVisibleCache) {
                    _syncWarning.value =
                        "Aggiornamento non riuscito: uso le playlist salvate."
                } else {
                    _errorMessage.value =
                        error.message ?: "Errore sincronizzazione playlist YouTube"
                }

                Log.e(
                    "LYRA_LIBRARY",
                    "librarySyncFailed cached=$hasVisibleCache message=${error.message}",
                    error
                )
            }

            _isLoading.value = false
            _isRefreshing.value = false
        }
    }

    private fun applySnapshot(snapshot: YouTubeLibrarySnapshot) {
        youtubePlaylists = snapshot.playlists
        refreshCombinedPlaylists()
        if (snapshot.syncedAtEpochMs > 0L) {
            lastSuccessfulSyncEpochMs = snapshot.syncedAtEpochMs
        }
    }

    private fun clearDisplayedLibrary() {
        youtubePlaylists = emptyList()
        refreshCombinedPlaylists()
        _isLoading.value = false
        _isRefreshing.value = false
        _errorMessage.value = null
        _syncWarning.value = null
    }

    private fun refreshCombinedPlaylists() {
        val local = SavedAutomaticPlaylistStore.asPlaylists()
        _playlists.value = (local + youtubePlaylists).distinctBy { it.id }
    }

    private companion object {
        // Evita refresh duplicati durante rapidi passaggi background/foreground.
        const val FOREGROUND_REFRESH_MIN_INTERVAL_MS = 15_000L
    }

}
