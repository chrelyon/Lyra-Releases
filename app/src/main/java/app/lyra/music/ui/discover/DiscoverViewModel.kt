package app.lyra.music.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.DiscoveryFolderSummary
import app.lyra.music.core.model.DiscoveryGroupSection
import app.lyra.music.core.model.DiscoveryGroupSummary
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import app.lyra.music.personalization.PersonalizedMixEngine
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DiscoverViewModel : ViewModel() {
    private val repository = YouTubeMusicRepository()

    private val _folders = MutableStateFlow(
        listOf(
            DiscoveryFolderSummary(
                id = AUTOMATIC_FOLDER_ID,
                title = "Playlist Automatica",
                subtitle = "Scegli un genere musicale e lascia continuare Lyra",
                groupCount = AutomaticPlaylistCatalog.presets.size,
            ),
        ) + repository.getDiscoveryFolders().take(1) + listOf(
            DiscoveryFolderSummary(
                id = FOR_YOU_FOLDER_ID,
                title = "Mix per me",
                subtitle = "Mix personalizzato dai tuoi ascolti e preferiti",
                groupCount = PersonalizedMixEngine.summaries().size,
            ),
        ) + repository.getDiscoveryFolders().drop(1)
    )
    val folders: StateFlow<List<DiscoveryFolderSummary>> = _folders.asStateFlow()

    private val _selectedFolderId = MutableStateFlow<String?>(null)
    val selectedFolderId: StateFlow<String?> = _selectedFolderId.asStateFlow()

    private val _groups = MutableStateFlow<List<DiscoveryGroupSummary>>(emptyList())
    val groups: StateFlow<List<DiscoveryGroupSummary>> = _groups.asStateFlow()

    private val _selectedGroupId = MutableStateFlow<String?>(null)
    val selectedGroupId: StateFlow<String?> = _selectedGroupId.asStateFlow()

    private val _selectedGroup = MutableStateFlow<DiscoveryGroupSection?>(null)
    val selectedGroup: StateFlow<DiscoveryGroupSection?> = _selectedGroup.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun openFolder(folderId: String) {
        _selectedFolderId.value = folderId
        _groups.value = when (folderId) {
            AUTOMATIC_FOLDER_ID -> AutomaticPlaylistCatalog.presets.map { preset ->
                DiscoveryGroupSummary(
                    id = preset.id,
                    title = preset.title,
                    playlistCount = null,
                )
            }
            FOR_YOU_FOLDER_ID -> PersonalizedMixEngine.summaries().map { mix ->
                DiscoveryGroupSummary(
                    id = mix.id,
                    title = mix.title,
                    playlistCount = null,
                )
            }
            else -> repository.getDiscoveryGroups(folderId)
        }
        _selectedGroupId.value = null
        _selectedGroup.value = null
        _errorMessage.value = null
        _isLoading.value = false
    }

    fun closeFolder() {
        _selectedFolderId.value = null
        _groups.value = emptyList()
        _selectedGroupId.value = null
        _selectedGroup.value = null
        _errorMessage.value = null
        _isLoading.value = false
    }

    fun openGroup(groupId: String) {
        val folderId = _selectedFolderId.value ?: return
        if (_selectedGroupId.value == groupId && _selectedGroup.value != null) return
        _selectedGroupId.value = groupId
        _selectedGroup.value = null
        requestGroup(folderId, groupId, forceRefresh = false)
    }

    fun closeGroup() {
        _selectedGroupId.value = null
        _selectedGroup.value = null
        _errorMessage.value = null
        _isLoading.value = false
        _selectedFolderId.value?.let { folderId ->
            when (folderId) {
                AUTOMATIC_FOLDER_ID -> Unit
                FOR_YOU_FOLDER_ID -> _groups.value = PersonalizedMixEngine.summaries().map { mix ->
                    DiscoveryGroupSummary(mix.id, mix.title, null)
                }
                else -> _groups.value = repository.getDiscoveryGroups(folderId)
            }
        }
    }

    fun refresh() {
        val folderId = _selectedFolderId.value ?: return
        val groupId = _selectedGroupId.value ?: return
        if (folderId == FOR_YOU_FOLDER_ID) {
            openGroup(groupId)
        } else {
            requestGroup(folderId, groupId, forceRefresh = true)
        }
    }

    fun isAutomaticFolderSelected(): Boolean = _selectedFolderId.value == AUTOMATIC_FOLDER_ID
    fun isForYouFolderSelected(): Boolean = _selectedFolderId.value == FOR_YOU_FOLDER_ID

    private fun requestGroup(
        folderId: String,
        groupId: String,
        forceRefresh: Boolean,
    ) {
        if (_isLoading.value) return

        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null

            runCatching {
                repository.getDiscoveryGroup(
                    folderId = folderId,
                    groupId = groupId,
                    forceRefresh = forceRefresh,
                )
            }
                .onSuccess { result ->
                    if (
                        _selectedFolderId.value == folderId &&
                        _selectedGroupId.value == groupId
                    ) {
                        _selectedGroup.value = result
                        _groups.value = repository.getDiscoveryGroups(folderId)
                        Log.d(
                            "LYRA_DISCOVER",
                            "groupLoaded folder=$folderId group=$groupId playlists=${result.playlists.size}",
                        )
                    }
                }
                .onFailure { error ->
                    if (
                        _selectedFolderId.value == folderId &&
                        _selectedGroupId.value == groupId
                    ) {
                        _errorMessage.value =
                            error.message ?: "Impossibile caricare le playlist"
                    }
                    Log.e(
                        "LYRA_DISCOVER",
                        "groupLoadFailed folder=$folderId group=$groupId message=${error.message}",
                        error,
                    )
                }

            if (
                _selectedFolderId.value == folderId &&
                _selectedGroupId.value == groupId
            ) {
                _isLoading.value = false
            }
        }
    }
    companion object {
        const val AUTOMATIC_FOLDER_ID = "automatic"
        const val FOR_YOU_FOLDER_ID = "for-you"
    }
}
