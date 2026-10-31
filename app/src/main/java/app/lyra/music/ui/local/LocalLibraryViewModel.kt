package app.lyra.music.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.Song
import app.lyra.music.local.LocalMediaRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocalLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalMediaRepository(application)

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun load() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            runCatching { repository.getSongs() }
                .onSuccess { _songs.value = it }
                .onFailure { error ->
                    _songs.value = emptyList()
                    _errorMessage.value = error.message ?: "Impossibile leggere la libreria locale"
                }
            _isLoading.value = false
        }
    }
}
