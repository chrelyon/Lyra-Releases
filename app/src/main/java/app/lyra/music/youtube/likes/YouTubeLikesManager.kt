package app.lyra.music.youtube.likes

import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.youtube.api.YouTubeDataApiClient
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stato condiviso dei "Mi piace" YouTube mostrati dall'icona cuore.
 *
 * Dalla 0.3.10 un cambio rating pubblica anche un evento applicativo e conserva
 * un piccolo overlay in memoria. In questo modo la playlist Preferiti puo'
 * aggiornarsi immediatamente senza attendere il successivo refresh della Data API.
 */
object YouTubeLikesManager {
    private const val TAG = "LYRA_LIKES"
    private const val MAX_RECENT_CHANGES = 100

    data class LikeChange(
        val song: Song,
        val liked: Boolean,
        val changedAtMs: Long = System.currentTimeMillis(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ratings = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val ratings: StateFlow<Map<String, Boolean>> = _ratings.asStateFlow()

    private val _busyIds = MutableStateFlow<Set<String>>(emptySet())
    val busyIds: StateFlow<Set<String>> = _busyIds.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _changes = MutableSharedFlow<LikeChange>(extraBufferCapacity = 32)
    val changes: SharedFlow<LikeChange> = _changes.asSharedFlow()

    private val _recentChanges = MutableStateFlow<Map<String, LikeChange>>(emptyMap())
    val recentChanges: StateFlow<Map<String, LikeChange>> = _recentChanges.asStateFlow()

    @Volatile
    private var authorizationGeneration: Long = Long.MIN_VALUE
    private val ratingLoadsInFlight = mutableSetOf<String>()

    fun refresh(songs: Collection<Song>) {
        refreshIds(songs.asSequence().filterNot(Song::isLocal).map(Song::id).toList())
    }

    fun refreshId(videoId: String) {
        refreshIds(listOf(videoId))
    }

    fun toggle(song: Song) {
        if (song.isLocal) return
        val videoId = song.id.takeIf { it.isNotBlank() } ?: return
        syncAuthorizationGeneration()
        if (!YouTubeAuthorizationManager.state.value.isAuthorized) {
            _errorMessage.value = "Collega Google per modificare i brani piaciuti."
            return
        }
        if (videoId in _busyIds.value) return

        val previousValue = _ratings.value[videoId] ?: false
        val newValue = !previousValue
        _busyIds.value = _busyIds.value + videoId
        _errorMessage.value = null

        // Feedback immediato del cuore; in caso di errore ripristiniamo lo stato.
        _ratings.value = _ratings.value + (videoId to newValue)

        scope.launch {
            runCatching {
                YouTubeDataApiClient.setVideoLiked(videoId, newValue)
            }.onSuccess {
                val change = LikeChange(song = song, liked = newValue)
                rememberChange(change)
                _changes.emit(change)
                Log.d(TAG, "ratingChanged videoId=$videoId liked=$newValue")
            }.onFailure { error ->
                _ratings.value = _ratings.value + (videoId to previousValue)
                _errorMessage.value = error.message ?: "Impossibile aggiornare il Mi piace."
                Log.e(TAG, "ratingChangeFailed videoId=$videoId message=${error.message}", error)
            }
            _busyIds.value = _busyIds.value - videoId
        }
    }

    /** Applica le modifiche locali recenti alla playlist Preferiti. */
    fun applyRecentChanges(songs: List<Song>): List<Song> {
        val changes = _recentChanges.value
        if (changes.isEmpty()) return songs

        val result = songs
            .filterNot { song -> changes[song.id]?.liked == false }
            .toMutableList()
        val existingIds = result.asSequence().map(Song::id).toMutableSet()

        changes.values
            .asSequence()
            .filter { it.liked }
            .sortedByDescending { it.changedAtMs }
            .map { it.song }
            .filter { existingIds.add(it.id) }
            .toList()
            .asReversed()
            .forEach { song -> result.add(0, song) }

        return result
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun rememberChange(change: LikeChange) {
        val updated = LinkedHashMap(_recentChanges.value)
        updated.remove(change.song.id)
        updated[change.song.id] = change
        while (updated.size > MAX_RECENT_CHANGES) {
            val firstKey = updated.keys.firstOrNull() ?: break
            updated.remove(firstKey)
        }
        _recentChanges.value = updated
    }

    private fun refreshIds(ids: Collection<String>) {
        syncAuthorizationGeneration()
        if (!YouTubeAuthorizationManager.state.value.isAuthorized) return
        val clean = ids
            .asSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && !it.startsWith("local:") }
            .distinct()
            .filterNot { _ratings.value.containsKey(it) }
            .filterNot { id -> synchronized(ratingLoadsInFlight) { id in ratingLoadsInFlight } }
            .toList()
        if (clean.isEmpty()) return
        synchronized(ratingLoadsInFlight) { ratingLoadsInFlight += clean }

        scope.launch {
            runCatching { YouTubeDataApiClient.getLikedRatings(clean) }
                .onSuccess { result ->
                    // Un ID non restituito viene trattato come non piaciuto.
                    val completed = clean.associateWith { result[it] == true }
                    _ratings.value = _ratings.value + completed
                    Log.d(TAG, "ratingsLoaded count=${completed.size}")
                }
                .onFailure { error ->
                    Log.w(TAG, "ratingsLoadFailed message=${error.message}")
                }
            synchronized(ratingLoadsInFlight) { ratingLoadsInFlight.removeAll(clean.toSet()) }
        }
    }

    private fun syncAuthorizationGeneration() {
        val current = YouTubeAuthorizationManager.state.value.authorizationGeneration
        if (current == authorizationGeneration) return
        authorizationGeneration = current
        _ratings.value = emptyMap()
        _busyIds.value = emptySet()
        _recentChanges.value = emptyMap()
        synchronized(ratingLoadsInFlight) { ratingLoadsInFlight.clear() }
        _errorMessage.value = null
    }
}
