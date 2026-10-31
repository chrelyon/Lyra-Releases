package app.lyra.music.ui.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.likes.YouTubeLikesManager
import app.lyra.music.youtube.repository.MusicRepository
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaylistViewModel : ViewModel() {

    private val repository: MusicRepository = YouTubeMusicRepository()

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var currentPlaylistId: String? = null
    private var currentIsFavorites = false

    init {
        viewModelScope.launch {
            YouTubeLikesManager.changes.collect { change ->
                if (!currentIsFavorites) return@collect
                _songs.value = applyLikeChange(_songs.value, change)
                Log.d(
                    "LYRA_LIBRARY",
                    "favoritesLiveUpdate videoId=${change.song.id} liked=${change.liked} songs=${_songs.value.size}",
                )
            }
        }
    }

    fun loadPlaylist(playlistId: String) {
        currentPlaylistId = playlistId
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            runCatching {
                repository.getPlaylist(playlistId)
            }.onSuccess { playlist ->
                _title.value = playlist.title
                currentIsFavorites =
                    playlist.title.trim().equals("Preferiti", ignoreCase = true) ||
                        YouTubeLibraryCache.favoritesPlaylistId() == playlistId
                _songs.value = if (currentIsFavorites) {
                    YouTubeLikesManager.applyRecentChanges(playlist.songs)
                } else {
                    playlist.songs
                }
                Log.d(
                    "LYRA_LIBRARY",
                    "playlist=$playlistId title=${playlist.title} songs=${_songs.value.size} favorites=$currentIsFavorites"
                )
            }.onFailure { error ->
                _songs.value = emptyList()
                currentIsFavorites = false
                _errorMessage.value = error.message ?: "Errore caricamento playlist"
                Log.e(
                    "LYRA_LIBRARY",
                    "playlist=$playlistId loadFailed message=${error.message}",
                    error
                )
            }

            _isLoading.value = false
        }
    }

    private fun applyLikeChange(
        songs: List<Song>,
        change: YouTubeLikesManager.LikeChange,
    ): List<Song> {
        return if (change.liked) {
            if (songs.any { it.id == change.song.id }) songs
            else listOf(change.song) + songs
        } else {
            songs.filterNot { it.id == change.song.id }
        }
    }
}
