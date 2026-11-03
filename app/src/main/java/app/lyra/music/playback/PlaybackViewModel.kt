package app.lyra.music.playback

import android.app.Application
import app.lyra.music.diagnostics.LyraLog as Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.lyra.music.core.model.Song
import app.lyra.music.audio.AudioSettings
import app.lyra.music.personalization.PersonalizedMixEngine
import app.lyra.music.youtube.repository.DiscoveryDirectMixRepository
import app.lyra.music.youtube.likes.YouTubeLikesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val playbackRepository: PlaybackRepository =
        YouTubePlaybackRepository()

    private val playbackController =
        PlaybackController(application)

    private val playbackSessionStore =
        PlaybackSessionStore(application)

    val uiState: StateFlow<PlaybackUiState> = playbackController.uiState

    private val _queueState = MutableStateFlow(PlaybackQueueUiState())
    val queueState: StateFlow<PlaybackQueueUiState> = _queueState.asStateFlow()

    private val _automaticPlaylistLoading = MutableStateFlow(false)
    val automaticPlaylistLoading: StateFlow<Boolean> = _automaticPlaylistLoading.asStateFlow()

    private val _automaticPlaylistError = MutableStateFlow<String?>(null)
    val automaticPlaylistError: StateFlow<String?> = _automaticPlaylistError.asStateFlow()

    private val _queueNotice = MutableStateFlow<String?>(null)
    val queueNotice: StateFlow<String?> = _queueNotice.asStateFlow()

    /**
     * Ordine lineare della queue prima dell'attivazione dello shuffle.
     * Gli stessi oggetti Song vengono riutilizzati nella queue mescolata: questo
     * permette di ritrovare con precisione il brano corrente anche con duplicati.
     */
    private var unshuffledSongs: List<Song> = emptyList()

    private var loadGeneration = 0L
    private var automaticGeneration = 0L
    private var lastKnownPositionMs = 0L
    private var lastPersistedPositionMs = 0L
    private var lastExternalSyncLogKey: String? = null

    init {
        restorePersistedSession()

        viewModelScope.launch {
            uiState.collect { state ->
                checkpointPlaybackPosition(state)
                syncQueueWithSessionPlayer(state)
                syncAutomaticQueueFromManager()
            }
        }
    }

    fun play(
        videoId: String,
        title: String,
        artist: String,
    ) {
        automaticGeneration++
        AutomaticPlaylistManager.clear()
        val song = Song(
            id = videoId,
            title = title,
            artist = artist,
            durationSeconds = 0L,
        )
        val currentRepeatMode = _queueState.value.repeatMode
        unshuffledSongs = listOf(song)

        _queueState.value = PlaybackQueueUiState(
            title = title,
            songs = listOf(song),
            currentIndex = 0,
            repeatMode = currentRepeatMode,
        )
        lastKnownPositionMs = 0L
        persistQueueSnapshot(reason = "SINGLE_SET", positionMs = 0L)
        resolveAndPlay(
            song = song,
            targetIndex = 0,
            reason = "SINGLE",
        )
    }

    fun playQueue(
        songs: List<Song>,
        startIndex: Int,
        queueTitle: String,
        preserveAutomaticSession: Boolean = false,
        forceSequential: Boolean = false,
        allowSmartContinuation: Boolean = true,
    ) {
        if (songs.isEmpty() || startIndex !in songs.indices) return
        if (!preserveAutomaticSession) {
            automaticGeneration++
            AutomaticPlaylistManager.clear()
        }
        _automaticPlaylistError.value = null

        val previousState = _queueState.value
        val shuffleEnabled = previousState.shuffleEnabled && !forceSequential
        val repeatMode = if (forceSequential) PlaybackRepeatMode.OFF else previousState.repeatMode
        val startSong = songs[startIndex]

        unshuffledSongs = songs

        val arrangedSongs = if (shuffleEnabled && songs.size > 1) {
            shuffleKeepingCurrentPosition(
                songs = songs,
                currentIndex = startIndex,
            )
        } else {
            songs
        }
        val arrangedIndex = findSameSongIndex(arrangedSongs, startSong)
            .takeIf { it >= 0 }
            ?: startIndex

        _queueState.value = PlaybackQueueUiState(
            title = queueTitle,
            allowSmartContinuation = allowSmartContinuation,
            songs = arrangedSongs,
            currentIndex = arrangedIndex,
            isChangingTrack = true,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
        )

        Log.d(
            "LYRA_QUEUE",
            "set title=$queueTitle size=${songs.size} startIndex=$arrangedIndex " +
                "shuffle=$shuffleEnabled repeat=$repeatMode"
        )

        lastKnownPositionMs = 0L
        persistQueueSnapshot(reason = "QUEUE_SET", positionMs = 0L)

        if (AudioSettings.gaplessEnabled.value && arrangedSongs.size > 1) {
            startNativeQueue(arrangedSongs, arrangedIndex, queueTitle, repeatMode)
        } else {
            resolveAndPlay(
                song = startSong,
                targetIndex = arrangedIndex,
                reason = "QUEUE_START",
            )
        }
    }

    fun playQueueShuffled(
        songs: List<Song>,
        queueTitle: String,
    ) {
        if (songs.isEmpty()) return
        automaticGeneration++
        AutomaticPlaylistManager.clear()
        _automaticPlaylistError.value = null

        val repeatMode = _queueState.value.repeatMode
        unshuffledSongs = songs
        val shuffledSongs = songs.shuffled()
        val startSong = shuffledSongs.first()

        _queueState.value = PlaybackQueueUiState(
            title = queueTitle,
            songs = shuffledSongs,
            currentIndex = 0,
            isChangingTrack = true,
            shuffleEnabled = true,
            repeatMode = repeatMode,
        )
        lastKnownPositionMs = 0L
        persistQueueSnapshot(reason = "QUEUE_SHUFFLE_START", positionMs = 0L)

        if (AudioSettings.gaplessEnabled.value && shuffledSongs.size > 1) {
            startNativeQueue(shuffledSongs, 0, queueTitle, repeatMode)
        } else {
            resolveAndPlay(
                song = startSong,
                targetIndex = 0,
                reason = "SHUFFLE_START",
            )
        }
    }

    /**
     * Un risultato scelto da Cerca parte immediatamente; mentre il brano suona
     * Lyra prepara in background il Watch Next/Automix di YouTube Music e lo
     * accoda. In questo modo, finito il brano cercato, la riproduzione continua
     * con musica simile senza un secondo comando dell'utente.
     */
    /**
     * Avvia un brano scelto dalla Cronologia e usa lo stesso brano come seed
     * per una nuova radio di musica simile.
     */
    fun playRecentAsRadio(seed: Song) {
        playSearchResultAsRadio(seed)
    }

    fun playSearchResultAsRadio(seed: Song) {
        val generation = ++automaticGeneration
        AutomaticPlaylistManager.clear()
        _automaticPlaylistError.value = null

        playQueue(
            songs = listOf(seed),
            startIndex = 0,
            queueTitle = "Radio: ${seed.title}",
            preserveAutomaticSession = true,
        )

        viewModelScope.launch {
            runCatching {
                AutomaticPlaylistManager.startFromSeed(seed)
            }.onSuccess { result ->
                if (generation != automaticGeneration || _queueState.value.currentSong?.id != seed.id) {
                    AutomaticPlaylistManager.clear()
                    return@onSuccess
                }
                applyAutomaticStartResult(result, expectedSeedId = seed.id)
            }.onFailure { error ->
                _automaticPlaylistError.value =
                    error.message ?: "Impossibile creare la radio automatica"
                Log.w(
                    "LYRA_AUTO_PLAYLIST",
                    "searchRadioFailed videoId=${seed.id} message=${error.message}",
                )
            }
        }
    }

    /** Avvia direttamente una selezione Lyra, senza schermate di playlist. */
    fun startDiscoveryMix(folderId: String, groupId: String) {
        if (_automaticPlaylistLoading.value) return
        val generation = ++automaticGeneration
        _automaticPlaylistLoading.value = true
        _automaticPlaylistError.value = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    DiscoveryDirectMixRepository().build(folderId, groupId)
                }
            }.onSuccess { result ->
                if (generation == automaticGeneration) {
                    playQueue(result.songs, 0, result.title, forceSequential = true)
                }
            }.onFailure { error ->
                if (generation == automaticGeneration) {
                    _automaticPlaylistError.value = error.message ?: "Impossibile preparare il mix"
                }
            }
            _automaticPlaylistLoading.value = false
        }
    }

    /** Avvia con un solo tap un mood/genere di Scopri -> Playlist Automatica. */
    fun startAutomaticPlaylist(presetId: String) {
        if (_automaticPlaylistLoading.value) return
        val generation = ++automaticGeneration
        // The strict provider keeps the previous automatic session untouched
        // while checking the new one. If the server rejects the request,
        // the old queue remains navigable instead of becoming orphaned.
        if (!app.lyra.music.genre.VerifiedGenrePlaylistClient.supported(presetId)) {
            AutomaticPlaylistManager.clear()
        }
        _automaticPlaylistLoading.value = true
        _automaticPlaylistError.value = null

        viewModelScope.launch {
            runCatching {
                AutomaticPlaylistManager.startFromPreset(presetId)
            }.onSuccess { result ->
                if (generation != automaticGeneration) {
                    AutomaticPlaylistManager.clear()
                    return@onSuccess
                }
                playQueue(
                    songs = result.songs,
                    startIndex = 0,
                    queueTitle = result.title,
                    preserveAutomaticSession = true,
                )
                Log.d(
                    "LYRA_AUTO_PLAYLIST",
                    "started preset=$presetId size=${result.songs.size}",
                )
            }.onFailure { error ->
                if (generation == automaticGeneration) {
                    val detail = error.message ?: "Risposta del server non disponibile"
                    _automaticPlaylistError.value =
                        "Playlist non avviata: $detail" +
                            if (_queueState.value.hasQueue)
                                " La coda precedente è rimasta selezionata."
                            else ""
                }
                Log.e(
                    "LYRA_AUTO_PLAYLIST",
                    "startFailed preset=$presetId message=${error.message} previousQueueRetained=${_queueState.value.hasQueue}",
                    error,
                )
            }
            if (generation == automaticGeneration) _automaticPlaylistLoading.value = false
        }
    }

    fun startAutomaticArtist(artist: String) {
        if (_automaticPlaylistLoading.value || artist.isBlank()) return
        val generation=++automaticGeneration
        AutomaticPlaylistManager.clear()
        _automaticPlaylistLoading.value=true;_automaticPlaylistError.value=null
        viewModelScope.launch {
            try {
                val result=AutomaticPlaylistManager.startFromArtist(artist)
                if (generation==automaticGeneration) playQueue(result.songs,0,result.title,preserveAutomaticSession=true)
            } catch (canceled: kotlinx.coroutines.CancellationException) { throw canceled }
            catch (_: Exception) { if (generation==automaticGeneration) _automaticPlaylistError.value="Impossibile creare la playlist per questo artista" }
            finally { _automaticPlaylistLoading.value=false }
        }
    }

    /** The selected album is a finite, ordered queue; never reinterpret it as a genre or artist radio. */
    fun startAutomaticAlbum(browseId: String, title: String) {
        if (_automaticPlaylistLoading.value || browseId.isBlank()) return
        val generation = ++automaticGeneration
        AutomaticPlaylistManager.clear()
        _automaticPlaylistLoading.value = true
        _automaticPlaylistError.value = null
        viewModelScope.launch {
            try {
                val songs = app.lyra.music.youtube.repository.AdvancedPlaylistSearchRepository().albumSongs(browseId)
                if (generation == automaticGeneration) {
                    playQueue(songs, 0, "Album · $title", forceSequential = true, allowSmartContinuation = false)
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                if (generation == automaticGeneration) _automaticPlaylistError.value = "Caricamento album scaduto. Riprova."
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == automaticGeneration) _automaticPlaylistError.value = "Impossibile caricare l'album selezionato"
            } finally { _automaticPlaylistLoading.value = false }
        }
    }

    private fun applyAutomaticStartResult(
        result: AutomaticPlaylistManager.StartResult,
        expectedSeedId: String,
    ) {
        val queue = _queueState.value
        if (queue.currentSong?.id != expectedSeedId || result.songs.isEmpty()) return

        val currentIndex = result.songs.indexOfFirst { it.id == expectedSeedId }
            .takeIf { it >= 0 }
            ?: 0
        unshuffledSongs = result.songs
        _queueState.value = queue.copy(
            title = result.title,
            songs = result.songs,
            currentIndex = currentIndex,
        )
        persistQueueSnapshot(reason = "AUTO_RADIO_READY")
        if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount == queue.songs.size) {
            val oldIds = queue.songs.map { it.id }
            if (result.songs.take(oldIds.size).map { it.id } == oldIds) {
                playbackController.appendNativeItemsIfMatching(oldIds, result.songs.drop(oldIds.size), result.title)
            }
        }
        Log.d(
            "LYRA_AUTO_PLAYLIST",
            "radioReady seed=$expectedSeedId size=${result.songs.size}",
        )
    }

    private fun syncAutomaticQueueFromManager() {
        if (!AutomaticPlaylistManager.isActive()) return
        val knownSongs = AutomaticPlaylistManager.currentSongs()
        val queue = _queueState.value
        if (!queue.hasQueue || knownSongs.isEmpty() || queue.isChangingTrack) return
        val title = AutomaticPlaylistManager.currentTitle() ?: queue.title
        if (knownSongs.map { it.id } == queue.songs.map { it.id } && title == queue.title) return

        val currentId = uiState.value.mediaId.takeIf { id ->
            knownSongs.any { it.id == id }
        } ?: queue.currentSong?.id ?: return
        val currentIndex = knownSongs.indexOfFirst { it.id == currentId }
            .takeIf { it >= 0 }
            ?: return

        unshuffledSongs = knownSongs
        _queueState.value = queue.copy(
            title = title,
            songs = knownSongs,
            currentIndex = currentIndex,
        )
        persistQueueSnapshot(reason = "AUTO_QUEUE_SYNC")
        if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount == queue.songs.size) {
            val oldIds = queue.songs.map { it.id }
            if (knownSongs.take(oldIds.size).map { it.id } == oldIds) {
                playbackController.appendNativeItemsIfMatching(oldIds, knownSongs.drop(oldIds.size), title)
            }
        }
    }

    /** Ripristina dalla Cronologia l'intera coda associata al brano scelto. */
    fun playHistoryEntry(entry: ListeningHistoryStore.Entry) {
        if (entry.queueSongs.isEmpty()) return
        automaticGeneration++
        AutomaticPlaylistManager.clear()
        playQueue(
            songs = entry.queueSongs,
            startIndex = entry.currentIndex.coerceIn(0, entry.queueSongs.lastIndex),
            queueTitle = entry.queueTitle,
            preserveAutomaticSession = entry.automatic,
        )
        if (entry.automatic) {
            val generation = automaticGeneration
            viewModelScope.launch {
                runCatching {
                    AutomaticPlaylistManager.resumeFromHistory(entry.queueTitle, entry.queueSongs)
                }.onSuccess { result ->
                    if (generation == automaticGeneration) {
                        syncAutomaticQueueFromManager()
                        Log.d("LYRA_HISTORY", "automaticHistoryResumed size=${result.songs.size}")
                    }
                }.onFailure { error ->
                    Log.w("LYRA_HISTORY", "automaticHistoryResumeFailed message=${error.message}")
                }
            }
        }
    }

    /** Avvia uno dei mix locali della sezione Scopri -> Per te. */
    fun startPersonalizedMix(mixId: String) {
        val automatic = PersonalizedMixEngine.automaticSeed(mixId)
        if (automatic != null) {
            val (seed, title) = automatic
            val generation = ++automaticGeneration
            AutomaticPlaylistManager.clear()
            _automaticPlaylistLoading.value = true
            _automaticPlaylistError.value = null
            viewModelScope.launch {
                runCatching { AutomaticPlaylistManager.startFromSeed(seed, title) }
                    .onSuccess { result ->
                        if (generation == automaticGeneration) {
                            playQueue(
                                songs = result.songs,
                                startIndex = result.songs.indexOfFirst { it.id == seed.id }.coerceAtLeast(0),
                                queueTitle = result.title,
                                preserveAutomaticSession = true,
                            )
                        }
                    }
                    .onFailure { error ->
                        _automaticPlaylistError.value = error.message ?: "Impossibile creare il mix personalizzato"
                    }
                _automaticPlaylistLoading.value = false
            }
            return
        }

        val songs = PersonalizedMixEngine.localSongs(mixId)
        if (songs.isNotEmpty()) {
            val title = PersonalizedMixEngine.summaries().firstOrNull { it.id == mixId }?.title ?: "Per te"
            playQueue(songs = songs, startIndex = 0, queueTitle = title)
        } else {
            _automaticPlaylistError.value = "Non ci sono ancora abbastanza ascolti per questo mix."
        }
    }

    fun previous() {
        val queue = _queueState.value
        if (!queue.hasQueue || queue.isChangingTrack) return

        val targetIndex = when {
            queue.currentIndex > 0 -> queue.currentIndex - 1
            queue.repeatMode == PlaybackRepeatMode.ALL && queue.songs.size > 1 ->
                queue.songs.lastIndex
            else -> return
        }
        playQueueIndex(targetIndex, reason = "PREVIOUS")
    }

    fun next() {
        val queue = _queueState.value
        if (!queue.hasQueue || queue.isChangingTrack) return

        val targetIndex = when {
            queue.currentIndex < queue.songs.lastIndex -> queue.currentIndex + 1
            queue.repeatMode == PlaybackRepeatMode.ALL && queue.songs.size > 1 -> 0
            else -> null
        }
        if (targetIndex != null) {
            playQueueIndex(targetIndex, reason = "NEXT")
            return
        }

        // Manual NEXT on the final track must try the automatic queue provider.
        if (!AutomaticPlaylistManager.isActive() || _automaticPlaylistLoading.value) return
        val generation = automaticGeneration
        val endedId = queue.currentSong?.id ?: return
        _automaticPlaylistLoading.value = true
        _automaticPlaylistError.value = null
        viewModelScope.launch {
            try {
                val fetched = AutomaticPlaylistManager.loadMore()
                if (generation != automaticGeneration) return@launch
                val liveQueue = _queueState.value
                if (!liveQueue.hasQueue || liveQueue.isChangingTrack ||
                    liveQueue.currentSong?.id != endedId ||
                    !AutomaticPlaylistManager.isActive()) return@launch

                syncAutomaticQueueFromManager()
                val updated = _queueState.value
                if (updated.currentSong?.id != endedId) return@launch
                if (updated.currentIndex < updated.songs.lastIndex) {
                    playQueueIndex(updated.currentIndex + 1, reason = "NEXT_AUTO_REFILL")
                } else {
                    _automaticPlaylistError.value =
                        "Nessun altro brano verificato disponibile per questa playlist."
                    Log.w(
                        "LYRA_AUTO_PLAYLIST",
                        "manualNextNoTail current=$endedId fetched=${fetched.size}"
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (generation == automaticGeneration) {
                    _automaticPlaylistError.value =
                        "Non riesco a caricare il prossimo brano: ${error.message ?: "server non disponibile"}"
                    Log.w("LYRA_AUTO_PLAYLIST", "manualNextFailed message=${error.message}")
                }
            } finally {
                if (generation == automaticGeneration) _automaticPlaylistLoading.value = false
            }
        }
    }

    fun playQueueIndex(index: Int) {
        val queue = _queueState.value
        if (
            index !in queue.songs.indices ||
            index == queue.currentIndex ||
            queue.isChangingTrack
        ) {
            return
        }
        playQueueIndex(index, reason = "QUEUE_JUMP")
    }

    fun toggleShuffle() {
        val queue = _queueState.value
        if (!queue.hasQueue || queue.songs.size < 2 || queue.isChangingTrack) return

        val currentSong = queue.currentSong ?: return

        if (!queue.shuffleEnabled) {
            unshuffledSongs = queue.songs
            val shuffledSongs = shuffleKeepingCurrentPosition(
                songs = queue.songs,
                currentIndex = queue.currentIndex,
            )
            val newCurrentIndex = findSameSongIndex(shuffledSongs, currentSong)
                .takeIf { it >= 0 }
                ?: queue.currentIndex

            _queueState.value = queue.copy(
                songs = shuffledSongs,
                currentIndex = newCurrentIndex,
                shuffleEnabled = true,
            )

            Log.d(
                "LYRA_QUEUE",
                "shuffle enabled current=$newCurrentIndex size=${shuffledSongs.size}"
            )
            persistQueueSnapshot(reason = "SHUFFLE_ON")
            if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount > 1) {
                startNativeQueue(shuffledSongs, newCurrentIndex, queue.title, queue.repeatMode, uiState.value.positionMs)
            }
            return
        }

        val restoredSongs = unshuffledSongs.ifEmpty { queue.songs }
        val restoredCurrentIndex = findSameSongIndex(restoredSongs, currentSong)
            .takeIf { it >= 0 }
            ?: queue.currentIndex.coerceIn(0, restoredSongs.lastIndex)

        _queueState.value = queue.copy(
            songs = restoredSongs,
            currentIndex = restoredCurrentIndex,
            shuffleEnabled = false,
        )

        Log.d(
            "LYRA_QUEUE",
            "shuffle disabled current=$restoredCurrentIndex size=${restoredSongs.size}"
        )
        persistQueueSnapshot(reason = "SHUFFLE_OFF")
        if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount > 1) {
            startNativeQueue(restoredSongs, restoredCurrentIndex, queue.title, queue.repeatMode, uiState.value.positionMs)
        }
    }

    fun cycleRepeatMode() {
        val queue = _queueState.value
        val newMode = queue.repeatMode.next()
        _queueState.value = queue.copy(repeatMode = newMode)
        Log.d("LYRA_QUEUE", "repeat mode=$newMode")
        persistQueueSnapshot(reason = "REPEAT_$newMode")
        playbackController.setNativeRepeatMode(newMode)
    }

    fun playNext(song: Song) {
        addSongToQueue(song, playNext = true)
    }

    fun addToQueue(song: Song) {
        addSongToQueue(song, playNext = false)
    }

    private fun addSongToQueue(song: Song, playNext: Boolean) {
        val queue = _queueState.value
        if (!queue.hasQueue) {
            playQueue(listOf(song), 0, song.title)
            return
        }

        // Un intervento manuale sulla coda conclude il rifornimento automatico,
        // altrimenti il manager potrebbe riaggiungere subito i propri brani.
        automaticGeneration++
        AutomaticPlaylistManager.clear()

        val insertionIndex = if (playNext) {
            (queue.currentIndex + 1).coerceAtMost(queue.songs.size)
        } else {
            queue.songs.size
        }
        val updated = queue.songs.toMutableList().apply { add(insertionIndex, song) }
        val currentSong = queue.currentSong
        val currentIndex = currentSong?.let { target ->
            updated.indices.firstOrNull { index -> updated[index] === target }
                ?: updated.indexOfFirst { it == target }.takeIf { it >= 0 }
        } ?: queue.currentIndex

        _queueState.value = queue.copy(
            songs = updated,
            currentIndex = currentIndex.coerceIn(0, updated.lastIndex),
        )

        unshuffledSongs = if (queue.shuffleEnabled) {
            val base = unshuffledSongs.ifEmpty { queue.songs }.toMutableList()
            if (playNext) {
                val sourceCurrent = queue.currentSong
                val sourceIndex = sourceCurrent?.let { base.indexOf(it) }?.takeIf { it >= 0 }
                base.add(((sourceIndex ?: base.lastIndex) + 1).coerceAtMost(base.size), song)
            } else {
                base.add(song)
            }
            base
        } else {
            updated
        }

        _queueNotice.value = if (playNext) "Brano impostato come prossimo" else "Brano aggiunto alla coda"
        persistQueueSnapshot(reason = if (playNext) "QUEUE_PLAY_NEXT" else "QUEUE_APPEND")
        if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount == queue.songs.size && queue.songs.size > 1) {
            playbackController.insertNativeItem(insertionIndex, song, queue.title)
        }
        Log.d("LYRA_QUEUE", "manualAdd playNext=$playNext videoId=${song.id} size=${updated.size}")
    }

    fun saveCurrentAutomaticPlaylist() {
        val queue = _queueState.value
        val automaticLike = queue.title.startsWith("Playlist Automatica") ||
            queue.title.startsWith("Radio:") || AutomaticPlaylistManager.isActive()
        if (!queue.hasQueue || !automaticLike) {
            _queueNotice.value = "Nessuna Playlist Automatica da salvare"
            return
        }
        val saved = SavedAutomaticPlaylistStore.save(queue.title, queue.songs)
        _queueNotice.value = if (saved != null) {
            "Playlist salvata in Le mie playlist"
        } else {
            "Impossibile salvare la playlist"
        }
    }

    fun clearQueueNotice() {
        _queueNotice.value = null
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        val queue = _queueState.value
        if (
            fromIndex !in queue.songs.indices ||
            toIndex !in queue.songs.indices ||
            fromIndex == toIndex ||
            queue.isChangingTrack
        ) {
            return
        }

        if (queue.shuffleEnabled) {
            Log.d(
                "LYRA_QUEUE",
                "move ignored shuffleEnabled=true from=$fromIndex to=$toIndex"
            )
            return
        }

        automaticGeneration++
        AutomaticPlaylistManager.clear()
        val reordered = queue.songs.toMutableList()
        val movedSong = reordered.removeAt(fromIndex)
        reordered.add(toIndex, movedSong)

        val newCurrentIndex = when {
            queue.currentIndex == fromIndex -> toIndex
            fromIndex < queue.currentIndex && toIndex >= queue.currentIndex ->
                queue.currentIndex - 1
            fromIndex > queue.currentIndex && toIndex <= queue.currentIndex ->
                queue.currentIndex + 1
            else -> queue.currentIndex
        }

        unshuffledSongs = reordered
        _queueState.value = queue.copy(
            songs = reordered,
            currentIndex = newCurrentIndex,
        )

        Log.d(
            "LYRA_QUEUE",
            "move from=$fromIndex to=$toIndex current=$newCurrentIndex size=${reordered.size}"
        )
        persistQueueSnapshot(reason = "QUEUE_MOVE")
        if (AudioSettings.gaplessEnabled.value && uiState.value.mediaItemCount == queue.songs.size && queue.songs.size > 1) {
            playbackController.moveNativeItem(fromIndex, toIndex)
        }
    }

    fun removeQueueItem(index: Int) {
        val queue = _queueState.value
        if (index !in queue.songs.indices || queue.isChangingTrack) return

        automaticGeneration++
        AutomaticPlaylistManager.clear()
        val removedSong = queue.songs[index]
        val removedCurrent = index == queue.currentIndex
        val remaining = queue.songs.toMutableList().apply { removeAt(index) }
        unshuffledSongs = removeSameSong(unshuffledSongs, removedSong)

        Log.d(
            "LYRA_QUEUE",
            "remove index=$index current=${queue.currentIndex} sizeBefore=${queue.songs.size}"
        )

        if (remaining.isEmpty()) {
            stop()
            return
        }

        val nativeQueue = AudioSettings.gaplessEnabled.value &&
            uiState.value.mediaItemCount == queue.songs.size && queue.songs.size > 1
        if (removedCurrent && nativeQueue) {
            _queueState.value = queue.copy(
                songs = remaining,
                currentIndex = index.coerceAtMost(remaining.lastIndex),
                resumePositionMs = 0L,
            )
            persistQueueSnapshot(reason = "REMOVE_CURRENT_NATIVE", positionMs = 0L)
            playbackController.removeNativeItem(index)
            return
        }
        if (removedCurrent) {
            loadGeneration++
            val targetIndex = index.coerceAtMost(remaining.lastIndex)
            _queueState.value = queue.copy(
                songs = remaining,
                currentIndex = targetIndex,
                isChangingTrack = true,
                resumePositionMs = 0L,
            )
            lastKnownPositionMs = 0L
            persistQueueSnapshot(reason = "REMOVE_CURRENT", positionMs = 0L)
            playbackController.stop()
            resolveAndPlay(
                song = remaining[targetIndex],
                targetIndex = targetIndex,
                reason = "REMOVE_CURRENT",
            )
            return
        }

        val newCurrentIndex = if (index < queue.currentIndex) {
            queue.currentIndex - 1
        } else {
            queue.currentIndex
        }

        _queueState.value = queue.copy(
            songs = remaining,
            currentIndex = newCurrentIndex,
        )
        persistQueueSnapshot(reason = "QUEUE_REMOVE")
        if (nativeQueue) playbackController.removeNativeItem(index)
    }

    private fun playQueueIndex(
        index: Int,
        reason: String,
    ) {
        val queue = _queueState.value
        val song = queue.songs.getOrNull(index) ?: return

        if (
            AudioSettings.gaplessEnabled.value &&
            uiState.value.mediaItemCount == queue.songs.size &&
            uiState.value.mediaId == queue.currentSong?.id &&
            queue.songs.size > 1
        ) {
            playbackController.seekToQueueIndex(index)
            return
        }

        _queueState.value = queue.copy(isChangingTrack = true)
        resolveAndPlay(song = song, targetIndex = index, reason = reason)
    }

    private fun startNativeQueue(
        songs: List<Song>,
        index: Int,
        title: String,
        repeatMode: PlaybackRepeatMode,
        positionMs: Long = 0L,
    ) {
        // Annulla eventuali risoluzioni asincrone della vecchia coda.
        loadGeneration++
        markTrackReady(index, positionMs, "NATIVE_QUEUE")
        playbackController.playNativeQueue(songs, index, positionMs, title, repeatMode)
    }

    private fun resolveAndPlay(
        song: Song,
        targetIndex: Int,
        reason: String,
        startPositionMs: Long = 0L,
    ) {
        val generation = ++loadGeneration

        Log.d(
            if (song.isLocal) "LYRA_LOCAL" else "LYRA_STREAM",
            "requested=${song.id} queueIndex=$targetIndex reason=$reason local=${song.isLocal}"
        )

        viewModelScope.launch {
            val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)

            if (song.isLocal) {
                if (generation != loadGeneration) return@launch
                markTrackReady(targetIndex, safeStartPositionMs, reason)
                runCatching {
                    playbackController.playLocal(
                        song = song,
                        startPositionMs = safeStartPositionMs,
                        contextLabel = _queueState.value.title,
                    )
                }.onFailure { error ->
                    markTrackLoadFailed(generation, song, targetIndex, reason, error)
                }
                return@launch
            }

            runCatching {
                playbackRepository.getPlaybackSource(song.id)
            }.onSuccess { source ->
                if (generation != loadGeneration) {
                    Log.d(
                        "LYRA_QUEUE",
                        "discardStaleLoad videoId=${song.id} generation=$generation"
                    )
                    return@onSuccess
                }

                Log.d(
                    "LYRA_STREAM",
                    "ready videoId=${song.id} client=${source.clientName} " +
                        "mime=${source.mimeType} bitrate=${source.bitrate}"
                )

                markTrackReady(targetIndex, safeStartPositionMs, reason)
                playbackController.play(
                    source = source,
                    song = song,
                    startPositionMs = safeStartPositionMs,
                    contextLabel = _queueState.value.title,
                )
            }.onFailure { error ->
                markTrackLoadFailed(generation, song, targetIndex, reason, error)
            }
        }
    }

    private fun markTrackReady(
        targetIndex: Int,
        startPositionMs: Long,
        reason: String,
    ) {
        _queueState.value = _queueState.value.copy(
            currentIndex = targetIndex,
            isChangingTrack = false,
            resumePositionMs = startPositionMs,
        )
        lastKnownPositionMs = startPositionMs
        persistQueueSnapshot(
            reason = "TRACK_READY_$reason",
            positionMs = startPositionMs,
        )
    }

    private fun markTrackLoadFailed(
        generation: Long,
        song: Song,
        targetIndex: Int,
        reason: String,
        error: Throwable,
    ) {
        if (generation == loadGeneration) {
            _queueState.value = _queueState.value.copy(isChangingTrack = false)
        }
        Log.e(
            if (song.isLocal) "LYRA_LOCAL" else "LYRA_STREAM",
            "loadFailed id=${song.id} queueIndex=$targetIndex reason=$reason message=${error.message}",
            error,
        )
    }

    fun pause() {
        val positionMs = currentResumePositionMs()
        playbackSessionStore.savePosition(positionMs, synchronous = true)
        lastKnownPositionMs = positionMs
        lastPersistedPositionMs = positionMs
        playbackController.pause()
    }

    fun resume() {
        val playerState = uiState.value
        val queue = _queueState.value

        if (!playerState.hasMediaItem && queue.hasQueue && !queue.isChangingTrack) {
            val song = queue.currentSong ?: return
            val startPositionMs = queue.resumePositionMs.coerceAtLeast(0L)

            Log.d(
                "LYRA_RESTORE",
                "resumeRestored videoId=${song.id} index=${queue.currentIndex} " +
                    "positionMs=$startPositionMs"
            )

            _queueState.value = queue.copy(isChangingTrack = true)
            resolveAndPlay(
                song = song,
                targetIndex = queue.currentIndex,
                reason = "RESTORE_RESUME",
                startPositionMs = startPositionMs,
            )
            return
        }

        playbackController.resume()
    }

    fun seekTo(positionMs: Long) {
        val queue = _queueState.value
        val safePositionMs = positionMs.coerceAtLeast(0L)

        if (!uiState.value.hasMediaItem && queue.hasQueue) {
            val durationMs = queue.currentSong
                ?.durationSeconds
                ?.takeIf { it > 0L }
                ?.times(1_000L)
            val restoredPositionMs = durationMs
                ?.let { safePositionMs.coerceAtMost(it) }
                ?: safePositionMs

            _queueState.value = queue.copy(resumePositionMs = restoredPositionMs)
            lastKnownPositionMs = restoredPositionMs
            lastPersistedPositionMs = restoredPositionMs
            playbackSessionStore.savePosition(restoredPositionMs, synchronous = true)
            Log.d(
                "LYRA_RESTORE",
                "seekRestored positionMs=$restoredPositionMs"
            )
            return
        }

        lastKnownPositionMs = safePositionMs
        lastPersistedPositionMs = safePositionMs
        playbackSessionStore.savePosition(safePositionMs, synchronous = true)
        playbackController.seekTo(safePositionMs)
    }

    fun stop() {
        automaticGeneration++
        AutomaticPlaylistManager.clear()
        loadGeneration++
        unshuffledSongs = emptyList()
        lastKnownPositionMs = 0L
        lastPersistedPositionMs = 0L
        _queueState.value = PlaybackQueueUiState()
        playbackSessionStore.clear()
        lastExternalSyncLogKey = null
        Log.d("LYRA_RESTORE", "sessionCleared reason=STOP")
        playbackController.stop()
    }

    private fun restorePersistedSession() {
        if (!PlaybackResumeSettings.isRestoreLastSessionEnabled()) {
            Log.d("LYRA_RESTORE", "sessionRestoreSkipped settingDisabled=true")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val restored = playbackSessionStore.restore() ?: return@launch

            withContext(Dispatchers.Main.immediate) {
                // Se l'utente ha gia' avviato una nuova queue mentre il restore
                // era in corso, la sessione vecchia non deve sovrascriverla.
                if (_queueState.value.hasQueue) {
                    Log.d("LYRA_RESTORE", "sessionRestoreSkipped queueAlreadyActive=true")
                    return@withContext
                }

                unshuffledSongs = restored.unshuffledSongs
                lastKnownPositionMs = restored.positionMs
                lastPersistedPositionMs = restored.positionMs
                _queueState.value = restored.queueState

                Log.d(
                    "LYRA_RESTORE",
                    "sessionRestored size=${restored.queueState.songs.size} " +
                        "index=${restored.queueState.currentIndex} " +
                        "positionMs=${restored.positionMs} " +
                        "shuffle=${restored.queueState.shuffleEnabled} " +
                        "repeat=${restored.queueState.repeatMode} autoplay=false"
                )
            }
        }
    }

    private fun syncQueueWithSessionPlayer(state: PlaybackUiState) {
        if (!state.hasMediaItem || state.mediaId.isBlank()) return

        val queue = _queueState.value
        if (!queue.hasQueue) return
        // Due occorrenze dello stesso brano possono avere lo stesso mediaId.
        // Con timeline nativa distinguiamole anche tramite l'indice Media3.
        if (queue.currentSong?.id == state.mediaId &&
            (state.mediaItemCount <= 1 || queue.currentIndex == state.mediaItemIndex)
        ) return

        // I comandi esterni (notifica, Bluetooth e in futuro Android Auto)
        // vengono eseguiti dal PlaybackService. Il servizio aggiorna prima lo
        // snapshot persistito e poi cambia MediaItem: quando la UI e' viva
        // riallineiamo quindi la sua queue allo stesso snapshot.
        val restored = playbackSessionStore.restore()
        val restoredQueue = restored?.queueState
        if (
            restored != null &&
            restoredQueue != null &&
            restoredQueue.hasQueue &&
            restoredQueue.currentSong?.id == state.mediaId
        ) {
            unshuffledSongs = restored.unshuffledSongs
            _queueState.value = restoredQueue.copy(
                isChangingTrack = false,
                resumePositionMs = state.positionMs.coerceAtLeast(0L),
            )
            lastKnownPositionMs = state.positionMs.coerceAtLeast(0L)
            lastPersistedPositionMs = lastKnownPositionMs

            val syncKey = "${state.mediaId}:${restoredQueue.currentIndex}"
            if (lastExternalSyncLogKey != syncKey) {
                Log.d("LYRA_EXTERNAL", "uiQueueSynced mediaId=${state.mediaId} index=${restoredQueue.currentIndex}")
                lastExternalSyncLogKey = syncKey
            }
            return
        }

        // Fallback difensivo per sessioni create da controller legacy che
        // dovessero aggiornare il MediaItem prima dello snapshot.
        val matchingIndex = queue.songs.indices
            .minByOrNull { index ->
                if (queue.songs[index].id == state.mediaId) {
                    kotlin.math.abs(index - queue.currentIndex)
                } else {
                    Int.MAX_VALUE
                }
            }
            ?.takeIf { queue.songs[it].id == state.mediaId }
            ?: return

        _queueState.value = queue.copy(
            currentIndex = matchingIndex,
            isChangingTrack = false,
            resumePositionMs = state.positionMs.coerceAtLeast(0L),
        )
        val syncKey = "${state.mediaId}:$matchingIndex"
        if (lastExternalSyncLogKey != syncKey) {
            Log.d("LYRA_EXTERNAL", "uiQueueSyncedFallback mediaId=${state.mediaId} index=$matchingIndex")
            lastExternalSyncLogKey = syncKey
        }
    }

    private fun checkpointPlaybackPosition(state: PlaybackUiState) {
        if (!state.hasMediaItem || !state.isPlaying) return

        val currentSong = _queueState.value.currentSong ?: return
        if (state.mediaId != currentSong.id) return

        lastKnownPositionMs = state.positionMs.coerceAtLeast(0L)

        if (lastKnownPositionMs >= lastPersistedPositionMs + POSITION_CHECKPOINT_MS) {
            playbackSessionStore.savePosition(lastKnownPositionMs)
            lastPersistedPositionMs = lastKnownPositionMs
        }
    }

    private fun persistQueueSnapshot(
        reason: String,
        positionMs: Long = currentResumePositionMs(),
    ) {
        val queue = _queueState.value
        if (!queue.hasQueue) {
            playbackSessionStore.clear()
            return
        }

        val safePositionMs = positionMs.coerceAtLeast(0L)
        playbackSessionStore.saveQueue(
            queueState = queue,
            unshuffledSongs = unshuffledSongs,
            positionMs = safePositionMs,
        )
        lastKnownPositionMs = safePositionMs
        lastPersistedPositionMs = safePositionMs

        Log.d(
            "LYRA_RESTORE",
            "sessionSaved reason=$reason size=${queue.songs.size} " +
                "index=${queue.currentIndex} positionMs=$safePositionMs " +
                "shuffle=${queue.shuffleEnabled} repeat=${queue.repeatMode}"
        )
    }

    private fun currentResumePositionMs(): Long {
        val playerState = uiState.value
        return if (playerState.hasMediaItem) {
            playerState.positionMs.coerceAtLeast(0L)
        } else {
            _queueState.value.resumePositionMs.coerceAtLeast(0L)
        }
    }

    private fun shuffleKeepingCurrentPosition(
        songs: List<Song>,
        currentIndex: Int,
    ): List<Song> {
        if (songs.size < 2 || currentIndex !in songs.indices) return songs

        val currentSong = songs[currentIndex]
        val others = songs.toMutableList().apply { removeAt(currentIndex) }
        val shuffled = others.shuffled().toMutableList()

        // Evita uno shuffle apparentemente "senza effetto" quando il sorteggio
        // casuale restituisce per caso lo stesso ordine.
        if (
            shuffled.size > 1 &&
            shuffled.indices.all { index -> shuffled[index] === others[index] }
        ) {
            shuffled.add(shuffled.removeAt(0))
        }

        shuffled.add(currentIndex.coerceAtMost(shuffled.size), currentSong)
        return shuffled
    }

    private fun findSameSongIndex(
        songs: List<Song>,
        target: Song,
    ): Int {
        val identityIndex = songs.indexOfFirst { it === target }
        if (identityIndex >= 0) return identityIndex

        return songs.indexOfFirst {
            it.id == target.id &&
                it.title == target.title &&
                it.artist == target.artist
        }
    }

    private fun removeSameSong(
        songs: List<Song>,
        target: Song,
    ): List<Song> {
        if (songs.isEmpty()) return songs

        val identityIndex = songs.indexOfFirst { it === target }
            .takeIf { it >= 0 }
            ?: songs.indexOfFirst {
                it.id == target.id &&
                    it.title == target.title &&
                    it.artist == target.artist
            }

        if (identityIndex < 0) return songs

        return songs.toMutableList().apply { removeAt(identityIndex) }
    }

    override fun onCleared() {
        if (_queueState.value.hasQueue) {
            persistQueueSnapshot(reason = "VIEWMODEL_CLEARED")
        }
        playbackController.release()
        super.onCleared()
    }

    private companion object {
        const val POSITION_CHECKPOINT_MS = 5_000L
    }
}
