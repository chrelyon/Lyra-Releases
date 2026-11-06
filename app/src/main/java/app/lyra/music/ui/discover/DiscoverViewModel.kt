package app.lyra.music.ui.discover

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Stato di navigazione di Scopri: radice con cinque voci e un solo livello interno. */
class DiscoverViewModel : ViewModel() {
    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    fun openFolder(folderId: String) {
        _selectedFolderId.value = folderId
    }

    fun closeFolder() {
        _selectedFolderId.value = null
    }
}
