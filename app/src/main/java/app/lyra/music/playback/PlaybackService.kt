package app.lyra.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.lyra.music.MainActivity
import app.lyra.music.R
import app.lyra.music.audio.AudioNormalizationController
import app.lyra.music.audio.AudioSettings
import app.lyra.music.audio.LocalAudioScanWorker
import app.lyra.music.audio.LocalLoudnessStore
import app.lyra.music.diagnostics.LyraLog as Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaConstants
import app.lyra.music.core.model.DiscoveryGroupSection
import app.lyra.music.core.model.DiscoveryGroupSummary
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song
import app.lyra.music.local.LocalMediaAccess
import app.lyra.music.local.LocalMediaRepository
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.likes.YouTubeLikesManager
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import app.lyra.music.personalization.ServerPlaylistClient
import app.lyra.music.personalization.PersonalizedMixEngine
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.personalization.RecommendationEngine
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import app.lyra.music.youtube.repository.DiscoverMenu
import app.lyra.music.youtube.repository.DiscoveryDirectMixRepository
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Proprietario unico del player e punto di ingresso dei controller esterni.
 *
 * Dalla fase 5E il servizio non si limita a tenere vivo ExoPlayer: sa anche
 * riprendere una sessione persistita e avanzare nella queue quando i comandi
 * arrivano da lock screen, Bluetooth o altri controller Media3, anche senza
 * Activity/ViewModel attivi.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private var player: ExoPlayer? = null
    private var deferredPlaybackResolver: LyraPlaybackDataSpecResolver? = null
    private var streamPrefetchJob: kotlinx.coroutines.Job? = null
    private var playbackMonitorJob: kotlinx.coroutines.Job? = null
    private var audioNormalizationController: AudioNormalizationController? = null
    private var audioCrossfadeCoordinator: AudioCrossfadeCoordinator? = null
    private var preloadNetworkManager: ConnectivityManager? = null
    private var preloadNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var playbackSessionStore: PlaybackSessionStore
    private val playbackRepository: PlaybackRepository = YouTubePlaybackRepository()
    private val musicRepository = YouTubeMusicRepository()
    private val localMediaRepository by lazy { LocalMediaRepository(this) }

    private val discoveryGroupCache = mutableMapOf<String, DiscoveryGroupSection>()
    private val discoveryPlaylistCache = mutableMapOf<String, Playlist>()
    private var localSongsCache: List<Song> = emptyList()
    private val playlistContentCache = mutableMapOf<String, PlaylistContent>()
    private var playlistAccountGeneration: Long = Long.MIN_VALUE

    private fun ensureGooglePlaylistCacheIsolation() {
        val generation = YouTubeAuthorizationManager.state.value.authorizationGeneration
        if (generation == playlistAccountGeneration) return
        playlistAccountGeneration = generation
        playlistContentCache.clear()
    }
    private val autoSongContexts = mutableMapOf<String, AutoSongContext>()
    private val searchResultCache = mutableMapOf<String, List<Song>>()

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var navigationInFlight = false
    // I comandi del volante inviati mentre la sorgente viene preparata
    // non devono sovrascriversi a vicenda. Coda limitata per evitare
    // avanzamenti incontrollati in caso di pulsante bloccato.
    private val pendingExternalDirections = ArrayDeque<Int>()
    private var navigationGeneration = 0L
    private var lastEndedMediaId: String? = null
    private var lastAutoSkippedFailedMediaId: String? = null
    private val failedTrackSkipPolicy = FailedTrackSkipPolicy()
    private var bufferingWatchdog: Runnable? = null
    private var suppressNativeModePersistence = false
    private var automaticPrefetchInFlight = false
    private var pendingAutomaticNextMediaId: String? = null

    // Segnali del profilo raccolti nel servizio: telefono, lock screen, Bluetooth
    // e Android Auto passano tutti da qui, quindi non dipendiamo dalla UI aperta.
    private var profileMediaId: String? = null
    private var profileSong: Song? = null
    private var profileQueueTitle: String = "Lyra"
    // Tempo realmente riprodotto in questa sessione del brano. Non usiamo la
    // posizione del player: un seek in avanti non deve sembrare ascolto reale.
    private var profilePositionMs: Long = 0L
    private var profilePlaySegmentStartedAtElapsedMs: Long = 0L
    private var profileDurationMs: Long = 0L
    private var profileOutcomeRecordedForMediaId: String? = null
    private var profileOutcomeSuppressedForMediaId: String? = null
    private var historyRecordedMediaId: String? = null

    private val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
        .setDisplayName("Precedente")
        .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
        .build()

    private val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
        .setDisplayName("Successivo")
        .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
        .build()

    private val toggleLikeCommand = SessionCommand(
        AUTO_COMMAND_TOGGLE_LIKE,
        Bundle.EMPTY,
    )

    /**
     * Preferenze dei controlli Media3. Android Auto decide la resa finale, ma
     * le secondary slot ci permettono di chiedere shuffle/repeat accanto ai
     * controlli principali quando il display le supporta, con fallback nel
     * menu overflow sui layout piu' compatti.
     */
    private fun mediaButtonPreferences(): List<CommandButton> {
        val currentPlayer = player
        val shuffleEnabled = currentPlayer?.shuffleModeEnabled == true
        val repeatMode = currentPlayer?.repeatMode ?: Player.REPEAT_MODE_OFF

        val shuffleButton = CommandButton.Builder(
            if (shuffleEnabled) {
                CommandButton.ICON_SHUFFLE_ON
            } else {
                CommandButton.ICON_SHUFFLE_OFF
            }
        )
            .setDisplayName(
                if (shuffleEnabled) "Casuale attivo" else "Casuale disattivato"
            )
            .setPlayerCommand(Player.COMMAND_SET_SHUFFLE_MODE, !shuffleEnabled)
            .setSlots(
                CommandButton.SLOT_BACK_SECONDARY,
                CommandButton.SLOT_OVERFLOW,
            )
            .build()

        val (repeatIcon, repeatLabel, nextRepeatMode) = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> Triple(
                CommandButton.ICON_REPEAT_ONE,
                "Ripeti brano",
                Player.REPEAT_MODE_OFF,
            )
            Player.REPEAT_MODE_ALL -> Triple(
                CommandButton.ICON_REPEAT_ALL,
                "Ripeti tutto",
                Player.REPEAT_MODE_ONE,
            )
            else -> Triple(
                CommandButton.ICON_REPEAT_OFF,
                "Ripetizione disattivata",
                Player.REPEAT_MODE_ALL,
            )
        }

        val repeatButton = CommandButton.Builder(repeatIcon)
            .setDisplayName(repeatLabel)
            .setPlayerCommand(Player.COMMAND_SET_REPEAT_MODE, nextRepeatMode)
            .setSlots(
                CommandButton.SLOT_FORWARD_SECONDARY,
                CommandButton.SLOT_OVERFLOW,
            )
            .build()

        val buttons = mutableListOf(previousButton, nextButton, shuffleButton, repeatButton)
        currentSongForLike()?.takeIf { !it.isLocal }?.let { song ->
            val liked = YouTubeLikesManager.ratings.value[song.id] == true
            buttons += CommandButton.Builder(
                if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED
            )
                .setDisplayName(
                    if (liked) "Rimuovi dai brani piaciuti" else "Aggiungi ai brani piaciuti"
                )
                .setSessionCommand(toggleLikeCommand)
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build()
        }
        return buttons
    }

    private fun refreshMediaButtonPreferences() {
        val session = mediaSession ?: return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::refreshMediaButtonPreferences)
            return
        }
        session.setMediaButtonPreferences(mediaButtonPreferences())
    }

    private val positionCheckpoint = object : Runnable {
        override fun run() {
            player?.let { currentPlayer ->
                if (currentPlayer.mediaItemCount > 0 && currentPlayer.isPlaying) {
                    playbackSessionStore.savePosition(
                        currentPlayer.currentPosition.coerceAtLeast(0L)
                    )
                    recordHistoryForCurrentIfNeeded()
                    ensureProfileTrackingStarted()
                    updateProfileProgress(currentPlayer)
                }
            }
            mainHandler.postDelayed(this, POSITION_CHECKPOINT_MS)
        }
    }

    /** The service (not the UI) reports real Media3 playback for screen-off/Auto. */
    private fun publishMonitorState() {
        if (!::playbackSessionStore.isInitialized) return
        val currentPlayer = player ?: return
        val queue = playbackSessionStore.restore()?.queueState
        val snapshot = PlaybackMonitorReporter.snapshot(
            currentPlayer, queue, AutomaticPlaylistManager.source.value,
        ) ?: return
        playbackMonitorJob?.cancel()
        playbackMonitorJob = serviceScope.launch {
            PlaybackMonitorReporter.publish(snapshot)
        }
    }

    private fun skipFailedCurrentTrack(mediaId: String, reason: String) {
        if (mediaId.isBlank() || !PersonalizationSettings.autoSkipFailedTracks.value ||
            mediaId == lastAutoSkippedFailedMediaId || player?.currentMediaItem?.mediaId != mediaId
        ) return
        val restored = playbackSessionStore.restore() ?: return
        val queue = restored.queueState
        if (queue.currentSong?.id != mediaId) return
        lastAutoSkippedFailedMediaId = mediaId
        val target = failedTrackSkipPolicy.nextIndex(true, queue.currentIndex, queue.songs.size)
        if (target == null) {
            AutomaticPlaylistManager.notice.value =
                "Riproduzione interrotta: nessun brano successivo disponibile o troppi errori consecutivi."
            return
        }
        loadPersistedIndex(restored, target, reason, mediaId)
    }

    private fun updateBufferingWatchdog() {
        bufferingWatchdog?.let(mainHandler::removeCallbacks)
        bufferingWatchdog = null
        val current = player ?: return
        val mediaId = current.currentMediaItem?.mediaId.orEmpty()
        if (current.playbackState != Player.STATE_BUFFERING || !current.playWhenReady || mediaId.isBlank()) return
        val watchdog = Runnable {
            val live = player
            if (live?.currentMediaItem?.mediaId == mediaId &&
                live.playWhenReady && live.playbackState == Player.STATE_BUFFERING
            ) {
                Log.w("LYRA_PLAYBACK", "bufferingTimeout mediaId=$mediaId")
                skipFailedCurrentTrack(mediaId, "AUTO_SKIP_BUFFER_TIMEOUT")
            }
        }
        bufferingWatchdog = watchdog
        mainHandler.postDelayed(watchdog, 60_000L)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val mediaId = player?.currentMediaItem?.mediaId.orEmpty()
            Log.w(
                "LYRA_PLAYBACK",
                "autoSkipPlaybackError mediaId=$mediaId errorCode=${error.errorCode} " +
                    "errorName=${error.errorCodeName}",
                error,
            )
            // Media3 reports the error immediately before entering IDLE.
            mainHandler.post { skipFailedCurrentTrack(mediaId, "AUTO_SKIP_ERROR") }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updateBufferingWatchdog()
            publishMonitorState()
            val currentPlayer = player ?: return

            if (playbackState != Player.STATE_ENDED) {
                lastEndedMediaId = null
                return
            }

            ensureProfileTrackingStarted()
            updateProfileProgress(currentPlayer)
            finalizeProfileOutcome(completed = true, manualSkip = false)

            val mediaId = currentPlayer.currentMediaItem?.mediaId.orEmpty()
            if (mediaId.isBlank() || mediaId == lastEndedMediaId) return

            lastEndedMediaId = mediaId
            requestAutomaticAdvance(mediaId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            publishMonitorState()
            if (isPlaying) {
                failedTrackSkipPolicy.reset()
                lastAutoSkippedFailedMediaId = null
                recordHistoryForCurrentIfNeeded()
                ensureProfileTrackingStarted()
                player?.let(::updateProfileProgress)
            } else {
                player?.let(::updateProfileProgress)
                persistCurrentPosition(synchronous = true)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateBufferingWatchdog()
            audioCrossfadeCoordinator?.onMainPlayWhenReadyChanged(playWhenReady)
            val currentPlayer = player ?: return
            // Fallback: se una sessione ripristinata risultasse ancora IDLE, al primo
            // PLAY (telefono/lock screen/Auto) prepariamo la sorgente differita
            // e ripartiamo dal punto salvato.
            if (
                playWhenReady &&
                currentPlayer.playbackState == Player.STATE_IDLE &&
                currentPlayer.mediaItemCount > 0
            ) {
                Log.d(
                    "LYRA_RESTORE",
                    "prepareRestoredSession reason=$reason positionMs=${currentPlayer.currentPosition}"
                )
                currentPlayer.prepare()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateBufferingWatchdog()
            val previousId = profileMediaId
            var feedback: AutomaticPlaylistManager.FeedbackResult? = null
            if (!previousId.isNullOrBlank()) {
                if (profileOutcomeSuppressedForMediaId == previousId) {
                    profileOutcomeSuppressedForMediaId = null
                } else {
                    val manualSkip = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
                    feedback = finalizeProfileOutcome(
                        completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT,
                        manualSkip = manualSkip,
                    )
                }
            }
            resetProfileTrackingForTransition()
            historyRecordedMediaId = null

            val mediaId = mediaItem?.mediaId.orEmpty()
            if (mediaId != lastAutoSkippedFailedMediaId) {
                lastAutoSkippedFailedMediaId = null
            }
            if (mediaId.isBlank()) return

            if (player?.isPlaying == true) ensureProfileTrackingStarted()

            applyNormalizationForMediaItem(mediaItem)
            audioCrossfadeCoordinator?.onTransition(mediaItem, reason)

            AutomaticPlaylistManager.onPlaybackTransition(mediaId)
            syncPersistedQueueToCurrentMedia(mediaId)
            feedback?.let { handleAutomaticSessionFeedback(mediaId, it) }
            synchronizeServerPlaylistAtBoundary(previousId, mediaId)
            maybePrefetchAutomaticQueue(mediaId, reason)
            preResolveUpcomingAudio(mediaId)
            currentSongForLike()?.takeIf { !it.isLocal }?.let { song ->
                YouTubeLikesManager.refreshId(song.id)
            }
            refreshMediaButtonPreferences()
            publishMonitorState()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            audioNormalizationController?.onAudioSessionIdChanged(audioSessionId)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            // La UI telefono gestisce repeat sulla propria queue a singolo
            // MediaItem. Persistiamo invece il repeat nativo quando il player
            // contiene la timeline completa usata da Android Auto.
            if (!suppressNativeModePersistence && (player?.mediaItemCount ?: 0) > 1) {
                persistNativeRepeatMode(repeatMode)
            }
            refreshMediaButtonPreferences()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            if (!suppressNativeModePersistence && (player?.mediaItemCount ?: 0) > 1) {
                persistNativeShuffleMode(shuffleModeEnabled)
            }
            refreshMediaButtonPreferences()
        }
    }

    private val sessionCallback = object : MediaLibrarySession.Callback {
        override fun onConnectAsync(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.ConnectionResult> {
            val isAuto = session.isAutoCompanionController(controller) ||
                session.isAutomotiveController(controller)
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            if (isAuto || controller.isTrusted) {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                    .buildUpon()
                    .add(toggleLikeCommand)
                    .build()
                builder.setAvailableSessionCommands(sessionCommands)
            }
            if (isAuto) {
                builder.setMediaButtonPreferences(mediaButtonPreferences())
            }
            return Futures.immediateFuture(builder.build())
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction != AUTO_COMMAND_TOGGLE_LIKE) {
                return super.onCustomCommand(session, controller, customCommand, args)
            }

            val song = currentSongForLike()
            if (song == null || song.isLocal) {
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }

            YouTubeLikesManager.toggle(song)
            Log.d(
                "LYRA_AUTO",
                "command=TOGGLE_LIKE videoId=${song.id} controller=${controllerLabel(session, controller)}"
            )
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            Log.d("LYRA_AUTO", "libraryRoot controller=${controllerLabel(session, browser)}")
            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    buildLibraryRoot(),
                    libraryParamsForController(session, browser, params),
                )
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            ensureGooglePlaylistCacheIsolation()
            if (parentId == AUTO_LIBRARY_ROOT_ID) {
                // Android Auto raccomanda pochi nodi di primo livello
                // (tipicamente <= 4). Evitiamo quindi di mettere tutte le
                // playlist direttamente nella root: Preferiti resta una
                // scorciatoia e il resto vive sotto "Le mie playlist".
                val cachedPlaylists = YouTubeLibraryCache.load()?.playlists.orEmpty()
                val favorite = cachedPlaylists.firstOrNull(::isFavoritesPlaylist)
                val regularPlaylists = cachedPlaylists.filterNot(::isFavoritesPlaylist)
                val items = buildList {
                    favorite?.let { add(buildPlaylistMediaItem(it)) }
                    add(buildAllPlaylistsMediaItem(regularPlaylists.size + SavedAutomaticPlaylistStore.asPlaylists().size))
                    add(buildDiscoverMediaItem())
                    add(buildLocalLibraryMediaItem())
                }

                Log.d(
                    "LYRA_AUTO",
                    "browseRoot items=${items.size} cachedPlaylists=${cachedPlaylists.size} " +
                        "regularPlaylists=${regularPlaylists.size} favorite=${favorite != null} " +
                        "controller=${controllerLabel(session, browser)}"
                )
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(items, params)
                )
            }

            if (parentId == AUTO_ALL_PLAYLISTS_ID) {
                val cachedPlaylists = YouTubeLibraryCache.load()
                    ?.playlists
                    .orEmpty()
                    .filterNot(::isFavoritesPlaylist)
                if (cachedPlaylists.isNotEmpty()) {
                    val items = buildList {
                        add(buildHistoryRootMediaItem())
                        addAll(SavedAutomaticPlaylistStore.asPlaylists().map(::buildPlaylistMediaItem))
                        addAll(cachedPlaylists.map(::buildPlaylistMediaItem))
                    }
                        .forBrowser(session, browser, page, pageSize)
                    return Futures.immediateFuture(
                        LibraryResult.ofItemList(items, params)
                    )
                }

                // Cold start: ricostruiamo la libreria senza richiedere che
                // MainActivity sia stata aperta prima di Android Auto.
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    val playlists = runCatching {
                        if (!ensureYouTubeAuthorization()) {
                            emptyList()
                        } else {
                            withContext(Dispatchers.IO) {
                                musicRepository.getLibrarySnapshot()
                            }.also(YouTubeLibraryCache::save).playlists
                        }
                    }.onFailure { error ->
                        Log.e(
                            "LYRA_AUTO",
                            "browseAllPlaylists refreshFailed message=${error.message}",
                            error,
                        )
                    }.getOrDefault(emptyList())

                    Log.d(
                        "LYRA_AUTO",
                        "browseAllPlaylists source=network playlists=${playlists.size} " +
                            "controller=${controllerLabel(session, browser)}"
                    )
                    future.set(
                        LibraryResult.ofItemList(
                            buildList {
                                add(buildHistoryRootMediaItem())
                                addAll(SavedAutomaticPlaylistStore.asPlaylists().map(::buildPlaylistMediaItem))
                                addAll(playlists.filterNot(::isFavoritesPlaylist).map(::buildPlaylistMediaItem))
                            }.forBrowser(session, browser, page, pageSize),
                            params,
                        )
                    )
                }
                return future
            }

            if (parentId == AUTO_DISCOVER_ROOT_ID) {
                // Stesse cinque voci, nello stesso ordine, della schermata Scopri dell'app.
                val items = DiscoverMenu.entries
                    .map(::buildDiscoverEntryMediaItem)
                    .forBrowser(session, browser, page, pageSize)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(items, params)
                )
            }

            if (parentId == AUTO_AUTOMATIC_ROOT_ID) {
                val items = AutomaticPlaylistCatalog.menuPresets
                    .map(::buildAutomaticPresetMediaItem)
                    .forBrowser(session, browser, page, pageSize)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(items, params)
                )
            }

            if (parentId == AUTO_SAVED_AUTOMATIC_ROOT_ID) {
                val items = SavedAutomaticPlaylistStore.asPlaylists()
                    .map(::buildPlaylistMediaItem)
                    .forBrowser(session, browser, page, pageSize)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(items, params)
                )
            }

            if (parentId == AUTO_HISTORY_ROOT_ID) {
                val items = ListeningHistoryStore.entries.value
                    .map(::buildHistoryMediaItem)
                    .forBrowser(session, browser, page, pageSize)
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(items, params)
                )
            }

            if (parentId.startsWith(AUTO_DISCOVER_FOLDER_PREFIX)) {
                val folderId = Uri.decode(parentId.removePrefix(AUTO_DISCOVER_FOLDER_PREFIX))
                // Le sottocategorie sono metadata locali: Android Auto puo'
                // mostrarle immediatamente senza attendere tutte le query YT Music.
                val items = musicRepository.getDiscoveryGroups(folderId).map { group ->
                    buildDiscoverGroupMediaItem(
                        folderId = folderId,
                        group = group,
                    )
                }
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        items.forBrowser(session, browser, page, pageSize),
                        params,
                    )
                )
            }

            if (parentId.startsWith(AUTO_DISCOVER_GROUP_PREFIX)) {
                val key = Uri.decode(parentId.removePrefix(AUTO_DISCOVER_GROUP_PREFIX))
                val (folderId, groupId) = parseDiscoverGroupKey(key)
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItemList(emptyList(), params)
                    )
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    val group = runCatching {
                        withContext(Dispatchers.IO) {
                            musicRepository.getDiscoveryGroup(folderId, groupId)
                        }
                    }.onFailure { error ->
                        Log.e(
                            "LYRA_AUTO",
                            "discoverGroup failed folder=$folderId group=$groupId message=${error.message}",
                            error,
                        )
                    }.getOrNull()

                    group?.let { loadedGroup ->
                        discoveryGroupCache[buildDiscoverGroupKey(folderId, groupId)] = loadedGroup
                        loadedGroup.playlists.forEach { playlist ->
                            discoveryPlaylistCache[playlist.id] = playlist
                        }
                    }
                    future.set(
                        LibraryResult.ofItemList(
                            group?.playlists.orEmpty()
                                .map(::buildPlaylistMediaItem)
                                .forBrowser(session, browser, page, pageSize),
                            params,
                        )
                    )
                }
                return future
            }

            if (parentId == AUTO_LOCAL_ROOT_ID) {
                val items = if (LocalMediaAccess.hasPermission(this@PlaybackService)) {
                    listOf(
                        buildLocalCategoryMediaItem(AUTO_LOCAL_SONGS_ID, "Brani", "Musica locale nella cartella Music"),
                        buildLocalCategoryMediaItem(AUTO_LOCAL_ALBUMS_ID, "Album", "Sfoglia per album"),
                        buildLocalCategoryMediaItem(AUTO_LOCAL_ARTISTS_ID, "Artisti", "Sfoglia per artista"),
                    )
                } else {
                    listOf(buildLocalPermissionMediaItem())
                }
                return Futures.immediateFuture(LibraryResult.ofItemList(items, params))
            }

            if (
                parentId == AUTO_LOCAL_SONGS_ID ||
                parentId == AUTO_LOCAL_ALBUMS_ID ||
                parentId == AUTO_LOCAL_ARTISTS_ID ||
                parentId.startsWith(AUTO_LOCAL_ALBUM_PREFIX) ||
                parentId.startsWith(AUTO_LOCAL_ARTIST_PREFIX)
            ) {
                val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
                serviceScope.launch {
                    val songs = loadLocalSongs()
                    val items = when {
                        parentId == AUTO_LOCAL_SONGS_ID -> buildQueueMediaItems(
                            queueKey = "local:all",
                            queueTitle = "Dispositivo",
                            songs = songs,
                        )
                        parentId == AUTO_LOCAL_ALBUMS_ID -> songs
                            .groupBy { it.albumId ?: Long.MIN_VALUE }
                            .entries
                            .filter { it.key != Long.MIN_VALUE }
                            .sortedBy { it.value.firstOrNull()?.album.orEmpty().lowercase() }
                            .map { (albumId, albumSongs) ->
                                buildLocalCollectionMediaItem(
                                    mediaId = AUTO_LOCAL_ALBUM_PREFIX + albumId,
                                    title = albumSongs.firstOrNull()?.album.orEmpty().ifBlank { "Album sconosciuto" },
                                    subtitle = "${albumSongs.size} brani",
                                    artworkUrl = albumSongs.firstOrNull()?.thumbnailUrl,
                                )
                            }
                        parentId == AUTO_LOCAL_ARTISTS_ID -> songs
                            .groupBy { it.artist.ifBlank { "Artista sconosciuto" } }
                            .entries
                            .sortedBy { it.key.lowercase() }
                            .map { (artist, artistSongs) ->
                                buildLocalCollectionMediaItem(
                                    mediaId = AUTO_LOCAL_ARTIST_PREFIX + Uri.encode(artist),
                                    title = artist,
                                    subtitle = "${artistSongs.size} brani",
                                    artworkUrl = artistSongs.firstOrNull()?.thumbnailUrl,
                                )
                            }
                        parentId.startsWith(AUTO_LOCAL_ALBUM_PREFIX) -> {
                            val albumId = parentId.removePrefix(AUTO_LOCAL_ALBUM_PREFIX).toLongOrNull()
                            val albumSongs = songs.filter { it.albumId == albumId }
                            buildQueueMediaItems(
                                queueKey = "local:album:$albumId",
                                queueTitle = albumSongs.firstOrNull()?.album.orEmpty().ifBlank { "Album" },
                                songs = albumSongs,
                            )
                        }
                        else -> {
                            val artist = Uri.decode(parentId.removePrefix(AUTO_LOCAL_ARTIST_PREFIX))
                            val artistSongs = songs.filter { it.artist == artist }
                            buildQueueMediaItems(
                                queueKey = "local:artist:$artist",
                                queueTitle = artist.ifBlank { "Artista" },
                                songs = artistSongs,
                            )
                        }
                    }
                    future.set(
                        LibraryResult.ofItemList(
                            items.forBrowser(session, browser, page, pageSize),
                            params,
                        )
                    )
                }
                return future
            }

            val playlistId = parentId.removePrefix(AUTO_PLAYLIST_PREFIX)
            if (!parentId.startsWith(AUTO_PLAYLIST_PREFIX) || playlistId.isBlank()) {
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(emptyList(), params)
                )
            }

            playlistContentCache[playlistId]?.let { cached ->
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        buildSongMediaItems(cached).forBrowser(session, browser, page, pageSize),
                        params,
                    )
                )
            }

            val generationAtRequest = YouTubeAuthorizationManager.state.value.authorizationGeneration
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                runCatching {
                    loadPlaylistForAuto(playlistId)
                }.onSuccess { content ->
                    if (generationAtRequest != YouTubeAuthorizationManager.state.value.authorizationGeneration) {
                        future.set(LibraryResult.ofItemList(emptyList(), params))
                        return@onSuccess
                    }
                    playlistContentCache[playlistId] = content
                    val items = buildSongMediaItems(content).forBrowser(session, browser, page, pageSize)
                    Log.d(
                        "LYRA_AUTO",
                        "browsePlaylist id=$playlistId songs=${content.songs.size}"
                    )
                    future.set(LibraryResult.ofItemList(items, params))
                }.onFailure { error ->
                    Log.e(
                        "LYRA_AUTO",
                        "browsePlaylist failed id=$playlistId message=${error.message}",
                        error,
                    )
                    future.set(LibraryResult.ofItemList(emptyList(), params))
                }
            }
            return future
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            ensureGooglePlaylistCacheIsolation()
            if (mediaId == AUTO_LIBRARY_ROOT_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(buildLibraryRoot(), null)
                )
            }

            if (mediaId == AUTO_ALL_PLAYLISTS_ID) {
                return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        buildAllPlaylistsMediaItem(
                            YouTubeLibraryCache.load()
                                ?.playlists
                                .orEmpty()
                                .count { !isFavoritesPlaylist(it) } + SavedAutomaticPlaylistStore.asPlaylists().size
                        ),
                        null,
                    )
                )
            }

            when (mediaId) {
                AUTO_DISCOVER_ROOT_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildDiscoverMediaItem(), null)
                )
                AUTO_AUTOMATIC_ROOT_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildAutomaticPlaylistRootMediaItem(), null)
                )
                AUTO_SAVED_AUTOMATIC_ROOT_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildSavedAutomaticRootMediaItem(), null)
                )
                AUTO_MIX_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildMixMediaItem(), null)
                )
                AUTO_HISTORY_ROOT_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildHistoryRootMediaItem(), null)
                )
                AUTO_LOCAL_ROOT_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildLocalLibraryMediaItem(), null)
                )
                AUTO_LOCAL_SONGS_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        buildLocalCategoryMediaItem(AUTO_LOCAL_SONGS_ID, "Brani", "Musica locale nella cartella Music"),
                        null,
                    )
                )
                AUTO_LOCAL_ALBUMS_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        buildLocalCategoryMediaItem(AUTO_LOCAL_ALBUMS_ID, "Album", "Sfoglia per album"),
                        null,
                    )
                )
                AUTO_LOCAL_ARTISTS_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        buildLocalCategoryMediaItem(AUTO_LOCAL_ARTISTS_ID, "Artisti", "Sfoglia per artista"),
                        null,
                    )
                )
                AUTO_LOCAL_PERMISSION_ID -> return Futures.immediateFuture(
                    LibraryResult.ofItem(buildLocalPermissionMediaItem(), null)
                )
            }

            if (mediaId.startsWith(AUTO_AUTOMATIC_PRESET_PREFIX)) {
                val presetId = Uri.decode(mediaId.removePrefix(AUTO_AUTOMATIC_PRESET_PREFIX))
                val preset = AutomaticPlaylistCatalog.preset(presetId)
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItem(buildAutomaticPlaylistRootMediaItem(), null)
                    )
                return Futures.immediateFuture(
                    LibraryResult.ofItem(buildAutomaticPresetMediaItem(preset), null)
                )
            }

            if (mediaId.startsWith(AUTO_DISCOVER_FOLDER_PREFIX)) {
                val folderId = Uri.decode(mediaId.removePrefix(AUTO_DISCOVER_FOLDER_PREFIX))
                val folder = DiscoverMenu.entry(folderId)
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItem(buildDiscoverMediaItem(), null)
                    )
                return Futures.immediateFuture(
                    LibraryResult.ofItem(buildDiscoverEntryMediaItem(folder), null)
                )
            }

            if (mediaId.startsWith(AUTO_DISCOVER_GROUP_PREFIX)) {
                val key = Uri.decode(mediaId.removePrefix(AUTO_DISCOVER_GROUP_PREFIX))
                val (folderId, groupId) = parseDiscoverGroupKey(key)
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItem(buildDiscoverMediaItem(), null)
                    )
                val summary = musicRepository.getDiscoveryGroups(folderId)
                    .firstOrNull { it.id == groupId }
                    ?: return Futures.immediateFuture(
                        LibraryResult.ofItem(buildDiscoverMediaItem(), null)
                    )
                val cachedGroup = discoveryGroupCache[buildDiscoverGroupKey(folderId, groupId)]
                return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        cachedGroup?.let { buildDiscoverGroupMediaItem(folderId, it) }
                            ?: buildDiscoverGroupMediaItem(folderId, summary),
                        null,
                    )
                )
            }

            if (mediaId.startsWith(AUTO_HISTORY_ITEM_PREFIX)) {
                val entryId = Uri.decode(mediaId.removePrefix(AUTO_HISTORY_ITEM_PREFIX))
                val entry = ListeningHistoryStore.entry(entryId)
                if (entry != null) {
                    return Futures.immediateFuture(
                        LibraryResult.ofItem(buildHistoryMediaItem(entry), null)
                    )
                }
            }

            if (mediaId.startsWith(AUTO_PLAYLIST_PREFIX)) {
                val playlistId = mediaId.removePrefix(AUTO_PLAYLIST_PREFIX)
                val playlist = YouTubeLibraryCache.load()
                    ?.playlists
                    ?.firstOrNull { it.id == playlistId }
                    ?: discoveryPlaylistCache[playlistId]
                    ?: SavedAutomaticPlaylistStore.asPlaylists().firstOrNull { it.id == playlistId }
                    ?: discoveryGroupCache.values
                        .asSequence()
                        .flatMap { group -> group.playlists.asSequence() }
                        .firstOrNull { it.id == playlistId }
                if (playlist != null) {
                    return Futures.immediateFuture(
                        LibraryResult.ofItem(buildPlaylistMediaItem(playlist), null)
                    )
                }
            }

            autoSongContexts[mediaId]?.let { context ->
                return Futures.immediateFuture(
                    LibraryResult.ofItem(
                        buildSongMediaItem(
                            song = context.song,
                            queueKey = "item:$mediaId",
                            queueTitle = context.queueTitle,
                            queueSongs = context.queueSongs,
                            index = context.index,
                            mediaIdOverride = mediaId,
                            shuffleEnabled = context.shuffleEnabled,
                        ),
                        null,
                    )
                )
            }

            return Futures.immediateFuture(
                LibraryResult.ofItem(
                    MediaItem.Builder()
                        .setMediaId(mediaId)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle("Brano Lyra")
                                .setIsBrowsable(false)
                                .setIsPlayable(true)
                                .build()
                        )
                        .build(),
                    null,
                )
            )
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            if (mediaItems.all { it.localConfiguration != null }) {
                return Futures.immediateFuture(mediaItems)
            }

            val future = SettableFuture.create<List<MediaItem>>()
            serviceScope.launch {
                runCatching {
                    resolveControllerMediaItems(mediaItems)
                }.onSuccess { resolved ->
                    future.set(resolved)
                }.onFailure { error ->
                    Log.e(
                        "LYRA_AUTO",
                        "resolveSelection failed controller=${controllerLabel(mediaSession, controller)} " +
                            "message=${error.message}",
                        error,
                    )
                    future.setException(error)
                }
            }
            return future
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (mediaItems.isEmpty()) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        emptyList(),
                        C.INDEX_UNSET,
                        C.TIME_UNSET,
                    )
                )
            }

            // La nuova queue gapless del telefono e' stata gia' salvata dal
            // ViewModel. Non risolvere il solo brano corrente: l'indice puo'
            // riferirsi anche alla seconda occorrenza dello stesso videoId.
            // Qui preserviamo l'ordine e la timeline nativa come richiesti.
            val internalQueue = playbackSessionStore.restore()?.queueState
            if (
                isLyraUiController(mediaSession, controller) &&
                mediaItems.size > 1 &&
                mediaItems.all { it.localConfiguration != null } &&
                internalQueue?.songs?.map { it.id } == mediaItems.map { it.mediaId }
            ) {
                // La UI ha gia' stabilito l'ordine, incluso shuffle; non
                // mescoliamo due volte il player nativo.
                resetNativePlaybackModes()
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex,
                        startPositionMs,
                    )
                )
            }

            // Il controller interno di Lyra passa gia' l'URL risolto. Non
            // tocchiamo questo percorso: conserva la queue/UI 5D/5E esistente.
            if (
                isLyraUiController(mediaSession, controller) &&
                mediaItems.all { item ->
                    item.localConfiguration?.uri?.let { uri ->
                        LyraDeferredPlayback.videoIdOrNull(uri) == null
                    } == true
                }
            ) {
                resetNativePlaybackModes()
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        mediaItems,
                        startIndex,
                        startPositionMs,
                    )
                )
            }

            val requestedIndex = startIndex
                .takeIf { it in mediaItems.indices }
                ?: 0
            val requested = mediaItems[requestedIndex]
            val isKnownBrowserSelection = autoSongContexts.containsKey(requested.mediaId) ||
                requested.mediaId == AUTO_MIX_ID ||
                requested.mediaId.startsWith(AUTO_AUTOMATIC_PRESET_PREFIX)
            val requestedSearchQuery = requested.requestMetadata.searchQuery
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            val previousSnapshot = playbackSessionStore.restore()
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()

            serviceScope.launch {
                runCatching {
                    resolveRequestedContext(requested)
                }.onSuccess { context ->
                    val samePersistedQueue = previousSnapshot?.queueState?.let { previous ->
                        previous.currentSong?.id == context.song.id &&
                            previous.songs.map { it.id } == context.queueSongs.map { it.id }
                    } == true
                    val explicitStartPosition = startPositionMs
                        .takeUnless { it == C.TIME_UNSET }
                        ?.coerceAtLeast(0L)
                    val persistedPositionMs = previousSnapshot?.positionMs?.coerceAtLeast(0L) ?: 0L
                    val effectiveStartPosition = when {
                        explicitStartPosition != null && explicitStartPosition > 0L -> explicitStartPosition
                        !isKnownBrowserSelection && requestedSearchQuery == null && samePersistedQueue ->
                            persistedPositionMs
                        else -> 0L
                    }

                    persistAutoQueue(context, positionMs = effectiveStartPosition)

                    val queueItems = context.queueSongs.map { song ->
                        buildDeferredAutoMediaItem(song, context.queueTitle)
                    }
                    val queueIndex = context.index.coerceIn(0, queueItems.lastIndex)
                    applyNativePlaybackModesFromPersistedQueue()

                    Log.d(
                        "LYRA_AUTO",
                        "setQueue controller=${controllerLabel(mediaSession, controller)} " +
                            "size=${queueItems.size} startIndex=$queueIndex videoId=${context.song.id}",
                    )

                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            queueItems,
                            queueIndex,
                            effectiveStartPosition,
                        )
                    )
                }.onFailure { error ->
                    Log.e(
                        "LYRA_AUTO",
                        "setQueue failed controller=${controllerLabel(mediaSession, controller)} " +
                            "message=${error.message}",
                        error,
                    )
                    future.setException(error)
                }
            }

            return future
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            val future = SettableFuture.create<LibraryResult<Void>>()
            serviceScope.launch {
                val songs = runCatching {
                    withContext(Dispatchers.IO) {
                        searchAllSongs(query)
                    }
                }.onFailure { error ->
                    Log.e(
                        "LYRA_AUTO",
                        "search failed queryLength=${query.length} message=${error.message}",
                        error,
                    )
                }.getOrDefault(emptyList())

                searchResultCache[query] = songs
                session.notifySearchResultChanged(browser, query, songs.size, params)
                future.set(LibraryResult.ofVoid(params))
            }
            return future
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            searchResultCache[query]?.let { songs ->
                return Futures.immediateFuture(
                    LibraryResult.ofItemList(
                        buildSearchMediaItems(query, songs).forBrowser(session, browser, page, pageSize),
                        params,
                    )
                )
            }

            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            serviceScope.launch {
                val songs = runCatching {
                    withContext(Dispatchers.IO) {
                        searchAllSongs(query)
                    }
                }.getOrDefault(emptyList())
                searchResultCache[query] = songs
                future.set(
                    LibraryResult.ofItemList(
                        buildSearchMediaItems(query, songs).forBrowser(session, browser, page, pageSize),
                        params,
                    )
                )
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            if (!PlaybackResumeSettings.isRestoreLastSessionEnabled()) {
                Log.d(
                    "LYRA_EXTERNAL",
                    "playbackResumption disabled controller=${controller.packageName}"
                )
                return Futures.immediateFailedFuture(
                    IllegalStateException("Lyra playback resumption disabled")
                )
            }

            val restored = playbackSessionStore.restore()

            if (restored == null || !restored.queueState.hasQueue) {
                Log.d(
                    "LYRA_EXTERNAL",
                    "playbackResumption unavailable controller=${controller.packageName}"
                )
                return Futures.immediateFailedFuture(
                    IllegalStateException("No persisted Lyra playback session")
                )
            }

            val queue = restored.queueState
            val song = queue.currentSong
                ?: return Futures.immediateFailedFuture(
                    IllegalStateException("Persisted Lyra session has no current song")
                )

            Log.d(
                "LYRA_EXTERNAL",
                "playbackResumption requested controller=${controller.packageName} " +
                    "videoId=${song.id} positionMs=${restored.positionMs} " +
                    "isForPlayback=$isForPlayback size=${queue.songs.size}"
            )

            // Quando Android/System UI chiede soltanto i metadata di resume
            // (isForPlayback=false) restituiamo immediatamente il solo brano
            // corrente. Per una vera richiesta PLAY restituiamo invece l'intera
            // coda: Media3 applichera' setMediaItems/prepare/play automaticamente.
            if (!isForPlayback) {
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(
                        listOf(buildDeferredAutoMediaItem(song, queue.title)),
                        0,
                        restored.positionMs,
                    )
                )
            }

            val items = queue.songs.map { song -> buildDeferredAutoMediaItem(song, queue.title) }
            applyNativePlaybackModes(queue)

            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    items,
                    queue.currentIndex.coerceIn(0, items.lastIndex),
                    restored.positionMs,
                )
            )
        }

        override fun onPlayerInteractionFinished(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            playerCommands: Player.Commands,
        ) {
            if (isLyraUiController(session, controllerInfo)) return

            val origin = controllerLabel(session, controllerInfo)

            when {
                playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM) ||
                    playerCommands.contains(Player.COMMAND_SEEK_TO_NEXT) -> {
                    val active = player
                    val nativeQueue = (active?.mediaItemCount ?: 0) > 1
                    val nativeHasNext = active?.hasNextMediaItem() == true
                    val persisted = playbackSessionStore.restore()?.queueState
                    Log.d(
                        "LYRA_EXTERNAL",
                        "command=NEXT origin=$origin nativeQueue=$nativeQueue " +
                            "nativeIndex=${active?.currentMediaItemIndex} nextIndex=${active?.nextMediaItemIndex} " +
                            "persistedIndex=${persisted?.currentIndex} queueSize=${persisted?.songs?.size} " +
                            "inFlight=$navigationInFlight"
                    )
                    // Non inviare un secondo NEXT: il LyraSessionPlayer gestisce
                    // sia INDEX_UNSET sia il recupero ritardato dei seek nativi.
                    // Un fallback qui puo' causare doppi salti o corse con ExoPlayer.
                }

                playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM) ||
                    playerCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS) -> {
                    val active = player
                    val nativeQueue = (active?.mediaItemCount ?: 0) > 1
                    val nativeHasPrevious = active?.hasPreviousMediaItem() == true
                    val persisted = playbackSessionStore.restore()?.queueState
                    Log.d(
                        "LYRA_EXTERNAL",
                        "command=PREVIOUS origin=$origin nativeQueue=$nativeQueue " +
                            "nativeIndex=${active?.currentMediaItemIndex} previousIndex=${active?.previousMediaItemIndex} " +
                            "persistedIndex=${persisted?.currentIndex} queueSize=${persisted?.songs?.size} " +
                            "inFlight=$navigationInFlight"
                    )
                    // Solo diagnostica: l'eventuale recupero viene gestito dal
                    // wrapper, con verifica della transizione realmente avvenuta.
                }

                playerCommands.contains(Player.COMMAND_SET_SHUFFLE_MODE) -> {
                    Log.d(
                        "LYRA_AUTO",
                        "command=SHUFFLE origin=$origin enabled=${player?.shuffleModeEnabled == true}"
                    )
                }

                playerCommands.contains(Player.COMMAND_SET_REPEAT_MODE) -> {
                    Log.d(
                        "LYRA_AUTO",
                        "command=REPEAT origin=$origin mode=${player?.repeatMode ?: Player.REPEAT_MODE_OFF}"
                    )
                }

                playerCommands.contains(Player.COMMAND_STOP) -> {
                    // Android Auto e alcuni controller di sistema possono inviare STOP
                    // durante disconnessione/teardown. In 0.3.7 questo cancellava
                    // l'intera sessione persistita rendendo impossibile l'auto-resume.
                    // Lo STOP esterno ora ferma il player ma conserva queue e posizione.
                    // Lo Stop esplicito dalla UI Lyra continua invece a cancellare la
                    // sessione tramite PlaybackViewModel.stop().
                    persistCurrentPosition(synchronous = true)
                    navigationGeneration++
                    navigationInFlight = false
                    pendingExternalDirections.clear()
                    Log.d(
                        "LYRA_RESTORE",
                        "externalStopPreserved origin=$origin positionMs=${player?.currentPosition?.coerceAtLeast(0L) ?: 0L}"
                    )
                }

                playerCommands.contains(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) -> {
                    Log.d("LYRA_EXTERNAL", "command=SEEK origin=$origin")
                    persistCurrentPosition(synchronous = true)
                }

                playerCommands.contains(Player.COMMAND_PLAY_PAUSE) -> {
                    Log.d(
                        "LYRA_EXTERNAL",
                        "command=PLAY_PAUSE origin=$origin playing=${player?.isPlaying == true}"
                    )
                    if (player?.isPlaying != true) {
                        persistCurrentPosition(synchronous = true)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        playbackSessionStore = PlaybackSessionStore(this)
        YouTubeLibraryCache.initialize(this)
        YouTubeAuthorizationManager.initialize(this)
        YouTubeAuthorizationManager.refreshSilently()

        val createdPlayer = LyraPlayer.create(
            context = this,
            playbackRepository = playbackRepository,
            onResolverReady = { resolver -> deferredPlaybackResolver = resolver },
            onSourceResolved = { source ->
                mainHandler.post {
                    if (player?.currentMediaItem?.mediaId == source.videoId) {
                        if (source.loudnessDb != null) {
                            audioNormalizationController?.onTrackLoudnessChanged(source.loudnessDb)
                        } else {
                            audioNormalizationController?.resetForTrackWithoutLoudness()
                        }
                    }
                }
            },
        ).also {
            it.addListener(playerListener)
        }
        player = createdPlayer
        playbackSessionStore.restore()?.queueState?.let { queue ->
            AutomaticPlaylistManager.restoreServerBinding(queue.title,queue.songs)
        }
        serviceScope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                publishMonitorState()
                val queue=playbackSessionStore.restore()?.queueState ?: continue
                val current=player?.currentMediaItem?.mediaId ?: continue
                if (queue.currentSong?.id==current && AutomaticPlaylistManager.hasServerIntent()) {
                    val revision=ServerPlaylistClient.revision(queue.songs.map { it.id },queue.title,queue.repeatMode.name,queue.shuffleEnabled)
                    AutomaticPlaylistManager.prepareServerBoundary(current,queue.songs,revision,
                        queue.repeatMode==PlaybackRepeatMode.OFF && !queue.shuffleEnabled)
                }
            }
        }
        restorePausedSessionIntoPlayer(createdPlayer)
        installNetworkAwareAudioPreload()
        audioNormalizationController = AudioNormalizationController(createdPlayer).also { controller ->
            controller.onAudioSessionIdChanged(createdPlayer.audioSessionId)
            audioCrossfadeCoordinator = AudioCrossfadeCoordinator(
                context = this,
                primary = createdPlayer,
                repository = playbackRepository,
                store = playbackSessionStore,
                primaryNormalization = controller,
            ).also { it.start() }
        }

        val sessionPlayer = LyraSessionPlayer(
            delegatePlayer = createdPlayer,
            canNavigate = ::canNavigatePersistedQueue,
            onNavigate = { direction ->
                requestExternalNavigation(
                    direction = direction,
                    origin = "SESSION_PLAYER",
                )
            },
            onNativeSeek = ::verifyNativeSeekAfterDelay,
        )

        mediaSession = MediaLibrarySession.Builder(this, sessionPlayer, sessionCallback)
            .setSessionActivity(buildSessionActivityPendingIntent())
            .setMediaButtonPreferences(mediaButtonPreferences())
            .build()

        serviceScope.launch {
            AudioSettings.preloadAudioEnabled.collect { updateAudioPreloadConfiguration() }
        }
        serviceScope.launch {
            AudioSettings.gaplessEnabled.collect { updateAudioPreloadConfiguration() }
        }
        serviceScope.launch {
            AudioSettings.normalizationEnabled.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            AudioSettings.streamingNormalizationEnabled.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            AudioSettings.localNormalizationEnabled.collect {
                applyNormalizationForMediaItem(player?.currentMediaItem)
            }
        }
        serviceScope.launch {
            AudioSettings.preferReplayGain.collect {
                applyNormalizationForMediaItem(player?.currentMediaItem)
            }
        }
        serviceScope.launch {
            AudioSettings.truePeakProtection.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            AudioSettings.normalizationMode.collect {
                applyNormalizationForMediaItem(player?.currentMediaItem)
            }
        }
        serviceScope.launch {
            AudioSettings.targetLufs.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            AudioSettings.maxGainDb.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            AudioSettings.maxAttenuationDb.collect {
                audioNormalizationController?.onNormalizationSettingChanged()
            }
        }
        serviceScope.launch {
            LocalLoudnessStore.status.collect {
                val currentItem = player?.currentMediaItem ?: return@collect
                val currentSong = playbackSessionStore.restore()?.queueState?.songs
                    ?.firstOrNull { it.id == currentItem.mediaId }
                if (currentSong?.isLocal == true && LocalLoudnessStore.hasFreshMeasurement(currentSong)) {
                    applyNormalizationForMediaItem(currentItem)
                }
            }
        }
        serviceScope.launch {
            YouTubeLikesManager.ratings.collect {
                refreshMediaButtonPreferences()
            }
        }
        serviceScope.launch {
            YouTubeLikesManager.changes.collect { change ->
                applyFavoritesLikeChange(change)
            }
        }

        mainHandler.post(positionCheckpoint)
        Log.d("LYRA_EXTERNAL", "serviceReady externalControls=true")
    }


    private fun applyNormalizationForMediaItem(mediaItem: MediaItem?) {
        val item = mediaItem ?: run {
            audioNormalizationController?.resetForTrackWithoutLoudness()
            return
        }
        val restored = playbackSessionStore.restore()
        val queue = restored?.queueState
        val song = queue?.songs?.firstOrNull { it.id == item.mediaId }

        if (song?.isLocal == true) {
            val trackMeasurement = LocalLoudnessStore.get(song)
            val albumId = song.albumId
            val queueSongs = queue.songs
            val completeAlbumQueue = albumId != null &&
                queueSongs.size > 1 &&
                queueSongs.all { candidate ->
                    candidate.isLocal && candidate.albumId == albumId &&
                        LocalLoudnessStore.hasFreshMeasurement(candidate)
                }
            val measurement = when (AudioSettings.normalizationMode.value) {
                AudioSettings.NormalizationMode.TRACK -> trackMeasurement
                AudioSettings.NormalizationMode.ALBUM -> {
                    if (completeAlbumQueue) LocalLoudnessStore.albumMeasurement(song) ?: trackMeasurement
                    else trackMeasurement
                }
                AudioSettings.NormalizationMode.AUTOMATIC -> {
                    if (completeAlbumQueue) LocalLoudnessStore.albumMeasurement(song) ?: trackMeasurement
                    else trackMeasurement
                }
            }
            audioNormalizationController?.onLocalTrackMeasurement(measurement)
            if (measurement == null &&
                AudioSettings.normalizationEnabled.value &&
                AudioSettings.localNormalizationEnabled.value &&
                AudioSettings.r128AnalysisEnabled.value
            ) {
                LocalAudioScanWorker.scheduleCurrent(this, song.id)
            }
            return
        }

        val extras = item.mediaMetadata.extras
        val loudnessDb = if (extras?.containsKey(PlaybackMediaItemFactory.EXTRA_LOUDNESS_DB) == true) {
            extras.getDouble(PlaybackMediaItemFactory.EXTRA_LOUDNESS_DB)
        } else null
        if (loudnessDb != null) {
            audioNormalizationController?.onTrackLoudnessChanged(loudnessDb)
        } else {
            audioNormalizationController?.resetForTrackWithoutLoudness()
        }
    }

    private fun buildSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            SESSION_ACTIVITY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun restorePausedSessionIntoPlayer(targetPlayer: ExoPlayer) {
        if (!PlaybackResumeSettings.isRestoreLastSessionEnabled()) {
            Log.d("LYRA_RESTORE", "serviceRestoreSkipped settingDisabled=true")
            return
        }

        val restored = playbackSessionStore.restore()
        if (restored == null) {
            Log.d("LYRA_RESTORE", "serviceRestoreUnavailable snapshot=false")
            return
        }
        val queue = restored.queueState
        if (!queue.hasQueue) {
            Log.d("LYRA_RESTORE", "serviceRestoreUnavailable queue=false")
            return
        }

        val items = queue.songs.map { song -> buildDeferredAutoMediaItem(song, queue.title) }
        if (items.isEmpty()) return

        suppressNativeModePersistence = true
        try {
            targetPlayer.setMediaItems(
                items,
                queue.currentIndex.coerceIn(0, items.lastIndex),
                restored.positionMs.coerceAtLeast(0L),
            )
            targetPlayer.repeatMode = when (queue.repeatMode) {
                PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
            targetPlayer.shuffleModeEnabled = queue.shuffleEnabled
            // Android Auto/System UI devono vedere una sessione realmente pronta,
            // non soltanto una timeline in STATE_IDLE. Prepariamo quindi il
            // brano corrente mantenendo playWhenReady=false: metadata, posizione
            // e controlli risultano disponibili senza avviare l'audio.
            targetPlayer.playWhenReady = false
            targetPlayer.prepare()
        } finally {
            suppressNativeModePersistence = false
        }

        Log.d(
            "LYRA_RESTORE",
            "serviceSessionHydrated size=${items.size} index=${queue.currentIndex} " +
                "positionMs=${restored.positionMs} autoplay=false"
        )
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaLibrarySession? {
        Log.d(
            "LYRA_RESTORE",
            "controllerSession package=${controllerInfo.packageName} items=${player?.mediaItemCount ?: 0} " +
                "index=${player?.currentMediaItemIndex ?: C.INDEX_UNSET} " +
                "positionMs=${player?.currentPosition?.coerceAtLeast(0L) ?: 0L}"
        )
        return mediaSession
    }

    private fun buildLibraryRoot(): MediaItem {
        return MediaItem.Builder()
            .setMediaId(AUTO_LIBRARY_ROOT_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Lyra")
                    .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_generic))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun buildAllPlaylistsMediaItem(count: Int): MediaItem {
        return MediaItem.Builder()
            .setMediaId(AUTO_ALL_PLAYLISTS_ID)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Le mie playlist")
                    .setSubtitle(
                        count.takeIf { it > 0 }?.let {
                            if (it == 1) "1 playlist" else "$it playlist"
                        }
                    )
                    .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_folder))
                    .setExtras(compactFolderStyleExtras())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }


    private fun buildAutomaticPlaylistRootMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_AUTOMATIC_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Playlist automatiche")
                .setSubtitle(DiscoverMenu.entry(DiscoverMenu.AUTOMATIC_ID)?.subtitle)
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_automatic))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildSavedAutomaticRootMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_SAVED_AUTOMATIC_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Playlist salvate")
                .setSubtitle("${SavedAutomaticPlaylistStore.asPlaylists().size} playlist Lyra")
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_saved))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildMixMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_MIX_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Mix per me")
                .setSubtitle(DiscoverMenu.entry(DiscoverMenu.MIX_ID)?.subtitle)
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_for_you))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private fun buildHistoryRootMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_HISTORY_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Cronologia")
                .setSubtitle(
                    ListeningHistoryStore.entries.value.size.takeIf { it > 0 }?.let { "$it ascolti recenti" }
                        ?: "Nessun ascolto recente"
                )
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_history))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildHistoryMediaItem(entry: ListeningHistoryStore.Entry): MediaItem {
        val mediaId = AUTO_HISTORY_ITEM_PREFIX + Uri.encode(entry.id)
        autoSongContexts[mediaId] = AutoSongContext(
            song = entry.song,
            queueTitle = entry.queueTitle,
            queueSongs = entry.queueSongs,
            index = entry.currentIndex.coerceIn(0, entry.queueSongs.lastIndex),
            historyAutomatic = entry.automatic,
            allowSmartContinuation = entry.allowSmartContinuation,
        )
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.song.title)
                    .setArtist(entry.song.artist)
                    .setSubtitle("${entry.queueTitle} · ${entry.queueSongs.size} brani")
                    .setArtworkUri(autoArtworkUri(entry.song.artworkUrlForPlayback, R.drawable.auto_art_songs))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun buildAutomaticPresetMediaItem(
        preset: AutomaticPlaylistCatalog.Preset,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_AUTOMATIC_PRESET_PREFIX + Uri.encode(preset.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(preset.title)
                .setSubtitle(preset.subtitle)
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_discover))
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private fun buildDiscoverEntryMediaItem(entry: DiscoverMenu.Entry): MediaItem = when (entry.kind) {
        DiscoverMenu.Kind.AUTOMATIC_FOLDER -> buildAutomaticPlaylistRootMediaItem()
        DiscoverMenu.Kind.MIX -> buildMixMediaItem()
        DiscoverMenu.Kind.DIRECT -> buildDiscoverGroupMediaItem(
            folderId = entry.id,
            groupId = requireNotNull(entry.groupId),
            title = entry.title,
            playlistCount = null,
            artworkUrl = null,
        )
        DiscoverMenu.Kind.DECADES_FOLDER -> MediaItem.Builder()
            .setMediaId(AUTO_DISCOVER_FOLDER_PREFIX + Uri.encode(entry.id))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(entry.title)
                    .setSubtitle(entry.subtitle)
                    .setArtworkUri(autoArtworkUri(null, discoverFolderArtworkResId(entry.id)))
                    .setExtras(compactFolderStyleExtras())
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build()
            )
            .build()
    }

    private fun buildDiscoverGroupMediaItem(
        folderId: String,
        group: DiscoveryGroupSummary,
    ): MediaItem = buildDiscoverGroupMediaItem(
        folderId = folderId,
        groupId = group.id,
        title = group.title,
        playlistCount = group.playlistCount,
        artworkUrl = null,
    )

    private fun buildDiscoverGroupMediaItem(
        folderId: String,
        group: DiscoveryGroupSection,
    ): MediaItem = buildDiscoverGroupMediaItem(
        folderId = folderId,
        groupId = group.id,
        title = group.title,
        playlistCount = group.playlists.size,
        artworkUrl = group.playlists.firstNotNullOfOrNull { it.thumbnailUrl },
    )

    private fun buildDiscoverGroupMediaItem(
        folderId: String,
        groupId: String,
        title: String,
        playlistCount: Int?,
        artworkUrl: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(
            AUTO_DISCOVER_GROUP_PREFIX +
                Uri.encode(buildDiscoverGroupKey(folderId, groupId))
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(
                    if (DiscoveryDirectMixRepository.isDirectGroup(folderId, groupId)) {
                        "Riproduci direttamente il mix Lyra"
                    } else {
                        playlistCount?.let { count ->
                            if (count == 1) "1 playlist" else "$count playlist"
                        } ?: "Apri per caricare"
                    }
                )
                .setArtworkUri(
                    autoArtworkUri(
                        artworkUrl,
                        discoverFolderArtworkResId(folderId),
                    )
                )
                .apply {
                    if (artworkUrl.isNullOrBlank()) {
                        setExtras(compactFolderStyleExtras())
                    }
                }
                .setIsBrowsable(!DiscoveryDirectMixRepository.isDirectGroup(folderId, groupId))
                .setIsPlayable(DiscoveryDirectMixRepository.isDirectGroup(folderId, groupId))
                .build()
        )
        .build()

    private fun discoverFolderArtworkResId(folderId: String): Int = when (folderId) {
        "novita" -> R.drawable.auto_art_discover_new
        "decenni" -> R.drawable.auto_art_discover_decades
        "italiane" -> R.drawable.auto_art_discover_europe
        else -> R.drawable.auto_art_discover
    }

    private fun buildDiscoverGroupKey(folderId: String, groupId: String): String =
        "$folderId::$groupId"

    private fun parseDiscoverGroupKey(key: String): Pair<String, String>? {
        val parts = key.split("::", limit = 2)
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        return parts[0] to parts[1]
    }

    private fun buildDiscoverMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_DISCOVER_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Scopri")
                .setSubtitle("Playlist automatiche, mix personali e categorie musicali aggiornate")
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_discover))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildLocalLibraryMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_LOCAL_ROOT_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Dispositivo")
                .setSubtitle("Musica locale nella cartella Music")
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_device))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildLocalPermissionMediaItem(): MediaItem = MediaItem.Builder()
        .setMediaId(AUTO_LOCAL_PERMISSION_ID)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle("Autorizza dal telefono")
                .setSubtitle("Apri Lyra sul telefono e consenti l'accesso alla musica")
                .setArtworkUri(autoArtworkUri(null, R.drawable.auto_art_device))
                .setIsBrowsable(false)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildLocalCategoryMediaItem(
        mediaId: String,
        title: String,
        subtitle: String,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(autoArtworkUri(null, localCategoryArtwork(mediaId)))
                .setExtras(compactFolderStyleExtras())
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()
        )
        .build()

    private fun buildLocalCollectionMediaItem(
        mediaId: String,
        title: String,
        subtitle: String,
        artworkUrl: String?,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(mediaId)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setArtworkUri(
                    autoArtworkUri(
                        artworkUrl,
                        if (mediaId.startsWith(AUTO_LOCAL_ARTIST_PREFIX)) {
                            R.drawable.auto_art_artists
                        } else {
                            R.drawable.auto_art_albums
                        },
                    )
                )
                .build()
        )
        .build()

    /** Preferiti e' una scorciatoia di primo livello solo su Android Auto. */
    private fun isFavoritesPlaylist(
        playlist: app.lyra.music.core.model.Playlist,
    ): Boolean = playlist.title.trim().equals("Preferiti", ignoreCase = true)

    private fun compactFolderStyleExtras(): Bundle = Bundle().apply {
        putInt(
            MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM,
            MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_CATEGORY_LIST_ITEM,
        )
    }

    private fun libraryParamsForController(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): LibraryParams? {
        if (
            !session.isAutoCompanionController(browser) &&
            !session.isAutomotiveController(browser)
        ) {
            return params
        }

        val extras = Bundle(params?.extras ?: Bundle()).apply {
            // Playlist/cartelle come griglia con artwork; brani come lista,
            // piu' vicina alla gerarchia visiva della UI telefono. L'host
            // resta libero di adattare la resa al veicolo.
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM,
            )
            putInt(
                MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
                MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM,
            )
        }

        return LibraryParams.Builder()
            .setExtras(extras)
            .setOffline(params?.isOffline ?: false)
            .setRecent(params?.isRecent ?: false)
            .setSuggested(params?.isSuggested ?: false)
            .build()
    }

    private fun buildPlaylistMediaItem(
        playlist: app.lyra.music.core.model.Playlist,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(AUTO_PLAYLIST_PREFIX + playlist.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(playlist.title)
                    .setSubtitle(
                        playlist.songCount?.let { count ->
                            if (count == 1) "1 brano" else "$count brani"
                        }
                    )
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setArtworkUri(
                        autoArtworkUri(
                            playlist.thumbnailUrl,
                            if (isFavoritesPlaylist(playlist)) {
                                R.drawable.auto_art_favorites
                            } else {
                                R.drawable.auto_art_playlists
                            },
                        )
                    )
                    .build()
            )
            .build()
    }

    private fun autoArtworkUri(
        remoteUrl: String?,
        fallbackResId: Int = R.drawable.auto_art_generic,
    ): Uri {
        val fallback = Uri.Builder()
            .scheme("android.resource")
            .authority(packageName)
            .appendPath(fallbackResId.toString())
            .build()
            .toString()
        return requireNotNull(
            AutoArtworkProvider.register(this, remoteUrl, fallback)
        )
    }

    private fun localCategoryArtwork(mediaId: String): Int = when (mediaId) {
        AUTO_LOCAL_SONGS_ID -> R.drawable.auto_art_songs
        AUTO_LOCAL_ALBUMS_ID -> R.drawable.auto_art_albums
        AUTO_LOCAL_ARTISTS_ID -> R.drawable.auto_art_artists
        else -> R.drawable.auto_art_device
    }

    private fun buildDeferredAutoMediaItem(
        song: Song,
        contextLabel: String? = playbackSessionStore.restore()?.queueState?.title,
    ): MediaItem {
        return PlaybackMediaItemFactory.deferred(
            song = song,
            artworkUri = autoArtworkUri(song.artworkUrlForPlayback, R.drawable.auto_art_lyra),
            contextLabel = contextLabel,
        )
    }

    private fun buildSongMediaItems(content: PlaylistContent): List<MediaItem> =
        buildQueueMediaItems(
            queueKey = "playlist:${content.id}",
            queueTitle = content.title,
            songs = content.songs,
        )

    private fun buildQueueMediaItems(
        queueKey: String,
        queueTitle: String,
        songs: List<Song>,
    ): List<MediaItem> {
        if (songs.isEmpty()) return emptyList()
        return buildList {
            add(buildShuffleActionMediaItem(queueKey, queueTitle, songs))
            songs.forEachIndexed { index, song ->
                add(
                    buildSongMediaItem(
                        song = song,
                        queueKey = queueKey,
                        queueTitle = queueTitle,
                        queueSongs = songs,
                        index = index,
                    )
                )
            }
        }
    }

    private fun buildShuffleActionMediaItem(
        queueKey: String,
        queueTitle: String,
        songs: List<Song>,
    ): MediaItem {
        val startIndex = songs.indices.random()
        val mediaId = "$AUTO_SONG_PREFIX${queueKey.hashCode()}:shuffle"
        autoSongContexts[mediaId] = AutoSongContext(
            song = songs[startIndex],
            queueTitle = queueTitle,
            queueSongs = songs,
            index = startIndex,
            shuffleEnabled = true,
        )
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Riproduci brani in ordine casuale")
                    .setSubtitle(queueTitle)
                    .setArtworkUri(
                        autoArtworkUri(
                            null,
                            if (queueTitle.equals("Preferiti", ignoreCase = true)) {
                                R.drawable.auto_art_favorites
                            } else {
                                R.drawable.auto_art_songs
                            },
                        )
                    )
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()
    }

    private fun buildSongMediaItem(
        song: Song,
        queueKey: String,
        queueTitle: String,
        queueSongs: List<Song>,
        index: Int,
        mediaIdOverride: String? = null,
        shuffleEnabled: Boolean = false,
        automaticSeed: Boolean = false,
    ): MediaItem {
        // Lo stesso video puo' appartenere a piu' playlist. Android Auto tratta
        // mediaId come token opaco, quindi usiamo un ID contestuale per evitare
        // che aprire una seconda playlist sovrascriva la queue associata allo
        // stesso song.id nella cache del servizio.
        val browserMediaId = mediaIdOverride
            ?: "$AUTO_SONG_PREFIX${queueKey.hashCode()}:$index:${song.id}"
        autoSongContexts[browserMediaId] = AutoSongContext(
            song = song,
            queueTitle = queueTitle,
            queueSongs = queueSongs,
            index = index,
            shuffleEnabled = shuffleEnabled,
            automaticSeed = automaticSeed,
        )

        val extras = Bundle().apply {
            putString(AUTO_EXTRA_QUEUE_TITLE, queueTitle)
            putInt(AUTO_EXTRA_QUEUE_INDEX, index)
        }

        return MediaItem.Builder()
            .setMediaId(browserMediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setDisplayTitle(song.title)
                    .setArtist(song.artist.trim().ifBlank { "Artista non disponibile" })
                    .setSubtitle(song.artist.trim().ifBlank { "Artista non disponibile" })
                    .setDescription(song.artist.trim().ifBlank { "Artista non disponibile" })
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setExtras(extras)
                    .setArtworkUri(autoArtworkUri(song.artworkUrlForPlayback, R.drawable.auto_art_lyra))
                    .build()
            )
            .build()
    }

    private fun buildSearchMediaItems(
        query: String,
        songs: List<Song>,
    ): List<MediaItem> {
        val queueTitle = "Ricerca: $query"
        return songs.mapIndexed { index, song ->
            buildSongMediaItem(
                song = song,
                queueKey = "search:$query",
                queueTitle = queueTitle,
                queueSongs = songs,
                index = index,
                automaticSeed = true,
            )
        }
    }

    private suspend fun loadPlaylistForAuto(playlistId: String): PlaylistContent {
        val isYouTubeMusicDiscovery = playlistId.startsWith(
            YouTubeMusicRepository.DISCOVER_MUSIC_PLAYLIST_PREFIX
        )
        if (!isYouTubeMusicDiscovery && !ensureYouTubeAuthorization()) {
            throw IllegalStateException(
                "Apri Lyra sul telefono e collega Google per usare questa playlist in Android Auto."
            )
        }

        val content = withContext(Dispatchers.IO) {
            musicRepository.getPlaylist(playlistId)
        }
        val cachedTitle = YouTubeLibraryCache.load()
            ?.playlists
            ?.firstOrNull { it.id == playlistId }
            ?.title
        return if (cachedTitle.equals("Preferiti", ignoreCase = true)) {
            content.copy(
                title = "Preferiti",
                songs = YouTubeLikesManager.applyRecentChanges(content.songs),
            )
        } else {
            content
        }
    }

    private fun applyFavoritesLikeChange(change: YouTubeLikesManager.LikeChange) {
        val favoritesId = YouTubeLibraryCache.favoritesPlaylistId() ?: return
        val cached = playlistContentCache[favoritesId] ?: return
        val songs = if (change.liked) {
            if (cached.songs.any { it.id == change.song.id }) cached.songs
            else listOf(change.song) + cached.songs
        } else {
            cached.songs.filterNot { it.id == change.song.id }
        }
        playlistContentCache[favoritesId] = cached.copy(title = "Preferiti", songs = songs)
        Log.d(
            "LYRA_AUTO",
            "favoritesLiveUpdate videoId=${change.song.id} liked=${change.liked} songs=${songs.size}",
        )
    }

    private suspend fun loadLocalSongs(): List<Song> {
        if (!LocalMediaAccess.hasPermission(this)) return emptyList()
        if (localSongsCache.isNotEmpty()) return localSongsCache
        return withContext(Dispatchers.IO) {
            localMediaRepository.getSongs()
        }.also { localSongsCache = it }
    }

    private suspend fun searchAllSongs(query: String): List<Song> {
        val youtube = runCatching {
            musicRepository.searchSongs(query)
        }.getOrDefault(emptyList())
        val local = runCatching {
            localMediaRepository.searchSongs(query)
        }.getOrDefault(emptyList())
        return (local + youtube).distinctBy { it.id }.take(100)
    }

    private suspend fun ensureYouTubeAuthorization(): Boolean {
        val current = YouTubeAuthorizationManager.state.value
        if (current.isAuthorized) return true

        if (!current.isChecking && !current.isAuthorizing) {
            YouTubeAuthorizationManager.refreshSilently()
        }

        return withTimeoutOrNull(AUTO_AUTH_WAIT_MS) {
            YouTubeAuthorizationManager.state
                .filter { !it.isChecking && !it.isAuthorizing }
                .first()
                .isAuthorized
        } ?: false
    }

    private suspend fun resolveControllerMediaItems(
        requestedItems: List<MediaItem>,
    ): List<MediaItem> {
        return requestedItems.map { requested ->
            if (requested.localConfiguration != null) {
                return@map requested
            }

            val context = resolveRequestedContext(requested)
            persistAutoQueue(context)

            // Per addMediaItems e controller legacy lasciamo un elemento
            // riproducibile ma differito. Non serve risolvere la rete nel
            // callback della sessione: ci pensa LyraPlaybackDataSpecResolver.
            buildDeferredAutoMediaItem(context.song)
        }
    }

    private suspend fun resolveRequestedContext(
        requested: MediaItem,
    ): AutoSongContext {
        val mediaId = requested.mediaId
        val searchQuery = requested.requestMetadata.searchQuery
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (mediaId.startsWith(AUTO_DISCOVER_GROUP_PREFIX)) {
            val key = Uri.decode(mediaId.removePrefix(AUTO_DISCOVER_GROUP_PREFIX))
            val (folderId, groupId) = parseDiscoverGroupKey(key)
                ?: throw IllegalArgumentException("Gruppo Scopri non valido")
            if (DiscoveryDirectMixRepository.isDirectGroup(folderId, groupId)) {
                AutomaticPlaylistManager.clear()
                val result = DiscoveryDirectMixRepository().build(folderId, groupId)
                val first = result.songs.first()
                return AutoSongContext(
                    song = first,
                    queueTitle = result.title,
                    queueSongs = result.songs,
                    index = 0,
                )
            }
        }

        if (mediaId == AUTO_MIX_ID) {
            val result = PersonalizedMixEngine.build()
            AutomaticPlaylistManager.clear()
            return AutoSongContext(
                song = result.songs.first(),
                queueTitle = result.title,
                queueSongs = result.songs,
                index = 0,
                allowSmartContinuation = false,
            )
        }

        if (mediaId.startsWith(AUTO_AUTOMATIC_PRESET_PREFIX)) {
            val presetId = Uri.decode(mediaId.removePrefix(AUTO_AUTOMATIC_PRESET_PREFIX))
            val result = AutomaticPlaylistManager.startFromPreset(presetId)
            val first = result.songs.firstOrNull()
                ?: throw IllegalStateException("Playlist Automatica senza brani")
            return AutoSongContext(
                song = first,
                queueTitle = result.title,
                queueSongs = result.songs,
                index = 0,
                automaticSeed = true,
            )
        }

        // Google Assistant/Android Auto possono inviare playFromSearch con un
        // mediaId che coincide ancora con il brano corrente. La query vocale ha
        // precedenza assoluta: altrimenti Lyra riavvia il vecchio brano e la
        // schermata di ricerca si chiude senza usare quanto dettato.
        if (searchQuery != null) {
            val songs = searchResultCache[searchQuery]
                ?: searchAllSongs(searchQuery).also { searchResultCache[searchQuery] = it }
            val first = songs.firstOrNull()
                ?: throw IllegalStateException("Nessun risultato per: $searchQuery")
            val automatic = runCatching {
                AutomaticPlaylistManager.startFromSeed(
                    seed = first,
                    title = "Radio: ${first.title}",
                )
            }.getOrNull()
            return if (automatic != null) {
                AutoSongContext(
                    song = first,
                    queueTitle = automatic.title,
                    queueSongs = automatic.songs,
                    index = automatic.songs.indexOfFirst { it.id == first.id }.coerceAtLeast(0),
                    automaticSeed = true,
                )
            } else {
                AutoSongContext(
                    song = first,
                    queueTitle = "Ricerca: $searchQuery",
                    queueSongs = listOf(first),
                    index = 0,
                )
            }
        }

        if (mediaId.isBlank()) {
            throw IllegalArgumentException("Richiesta media senza id o query di ricerca")
        }

        autoSongContexts[mediaId]?.let { cached ->
            if (cached.historyAutomatic) {
                // La Cronologia deve partire subito con la queue salvata. La
                // continuazione Automix viene riattivata in background e sara'
                // disponibile prima che la coda storica arrivi alla fine.
                AutomaticPlaylistManager.clear()
                serviceScope.launch {
                    runCatching {
                        AutomaticPlaylistManager.resumeFromHistory(
                            title = cached.queueTitle,
                            songs = cached.queueSongs,
                        )
                    }.onFailure { error ->
                        Log.w("LYRA_HISTORY", "autoHistoryResumeFailed message=${error.message}")
                    }
                }
                return cached
            } else if (cached.automaticSeed) {
                val automatic = runCatching {
                    AutomaticPlaylistManager.startFromSeed(
                        seed = cached.song,
                        title = cached.queueTitle.ifBlank { "Radio: ${cached.song.title}" },
                    )
                }.getOrNull()
                if (automatic != null) {
                    return AutoSongContext(
                        song = cached.song,
                        queueTitle = automatic.title,
                        queueSongs = automatic.songs,
                        index = automatic.songs.indexOfFirst { it.id == cached.song.id }.coerceAtLeast(0),
                        automaticSeed = true,
                    )
                }
            } else {
                AutomaticPlaylistManager.clear()
            }
            return cached
        }

        return playbackSessionStore.restore()
                ?.queueState
                ?.let { queue ->
                    val directIndex = queue.songs.indexOfFirst { it.id == mediaId }
                    val contextualIndex = mediaId
                        .takeIf { it.startsWith(AUTO_SONG_PREFIX) }
                        ?.removePrefix(AUTO_SONG_PREFIX)
                        ?.split(":", limit = 3)
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.takeIf { it in queue.songs.indices }
                    val index = directIndex.takeIf { it >= 0 } ?: contextualIndex
                    index?.let { restoredIndex ->
                        AutoSongContext(
                            song = queue.songs[restoredIndex],
                            queueTitle = queue.title,
                            queueSongs = queue.songs,
                            index = restoredIndex,
                        )
                    }
                }
            ?: requested.toFallbackSongContext().also { AutomaticPlaylistManager.clear() }
    }

    private fun persistAutoQueue(
        context: AutoSongContext,
        positionMs: Long = 0L,
    ) {
        val previous = playbackSessionStore.restore()?.queueState
        val queue = PlaybackQueueUiState(
            title = context.queueTitle,
            songs = context.queueSongs,
            currentIndex = context.index.coerceIn(0, context.queueSongs.lastIndex),
            shuffleEnabled = context.shuffleEnabled,
            repeatMode = previous?.repeatMode ?: PlaybackRepeatMode.OFF,
            allowSmartContinuation = context.allowSmartContinuation,
            resumePositionMs = 0L,
        )
        playbackSessionStore.saveQueue(
            queueState = queue,
            unshuffledSongs = context.queueSongs,
            positionMs = positionMs.coerceAtLeast(0L),
        )
        Log.d(
            "LYRA_RESTORE",
            "autoQueueSaved videoId=${context.song.id} index=${queue.currentIndex} positionMs=${positionMs.coerceAtLeast(0L)}"
        )
    }

    private fun MediaItem.toFallbackSongContext(): AutoSongContext {
        val metadata = mediaMetadata
        val song = Song(
            id = mediaId,
            title = metadata.title?.toString().orEmpty().ifBlank { "Brano Lyra" },
            artist = metadata.artist?.toString().orEmpty(),
            durationSeconds = 0L,
            thumbnailUrl = metadata.artworkUri?.toString(),
        )
        return AutoSongContext(
            song = song,
            queueTitle = metadata.extras?.getString(AUTO_EXTRA_QUEUE_TITLE)
                .orEmpty()
                .ifBlank { "Lyra" },
            queueSongs = listOf(song),
            index = 0,
        )
    }

    private fun <T> List<T>.forBrowser(
        session: MediaSession,
        browser: MediaSession.ControllerInfo,
        page: Int,
        pageSize: Int,
    ): List<T> {
        // Android Auto/AAOS non supportano la paginazione della gerarchia
        // media: per questi controller restituiamo l'intero livello e lasciamo
        // all'host l'applicazione dei propri limiti di sicurezza/visualizzazione.
        if (
            session.isAutoCompanionController(browser) ||
            session.isAutomotiveController(browser)
        ) {
            return this
        }

        return paged(page, pageSize)
    }

    private fun <T> List<T>.paged(page: Int, pageSize: Int): List<T> {
        if (isEmpty() || page < 0 || pageSize <= 0) return emptyList()
        val from = (page.toLong() * pageSize.toLong())
            .coerceAtMost(size.toLong())
            .toInt()
        val to = (from + pageSize).coerceAtMost(size)
        return subList(from, to)
    }

    /** Prepara soltanto le URL firmate dei prossimi brani, senza scaricare audio.
     * Evita negoziazioni InnerTube sulla transizione; non promette gapless
     * nelle code smartphone ancora gestite con singolo MediaItem.
     */
    private fun preResolveUpcomingAudio(currentMediaId: String) {
        streamPrefetchJob?.cancel()
        streamPrefetchJob = null
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        if (!AudioSettings.prefetchSourcesEnabled.value) return
        val resolver = deferredPlaybackResolver ?: return
        val restored = playbackSessionStore.restore()?.queueState ?: return
        val currentIndex = restored.songs.indexOfFirst { it.id == currentMediaId }
        if (currentIndex < 0) return
        val upcoming = restored.songs.asSequence()
            .drop(currentIndex + 1)
            .filterNot { it.isLocal || it.id == currentMediaId }
            .distinctBy { it.id }
            .take(2)
            .map { it.id }
            .toList()
        if (upcoming.isEmpty()) return
        streamPrefetchJob = serviceScope.launch(Dispatchers.IO) {
            for (videoId in upcoming) {
                try {
                    resolver.prefetchSource(videoId)
                } catch (canceled: kotlinx.coroutines.CancellationException) {
                    throw canceled
                } catch (error: Exception) {
                    Log.w("LYRA_AUDIO", "sourcePrefetchFailed videoId=$videoId message=${error.message}")
                }
            }
        }
    }

    /** HTTP completion can only replace the FUTURE tail of this exact live queue. */
    private fun synchronizeServerPlaylistAtBoundary(endedId: String?, currentId: String) {
        val binding=AutomaticPlaylistManager.serverBinding() ?: return
        val snapshot=playbackSessionStore.restore()?.queueState ?: return
        val revision=ServerPlaylistClient.revision(snapshot.songs.map { it.id },snapshot.title,snapshot.repeatMode.name,snapshot.shuffleEnabled)
        serviceScope.launch {
            val future=AutomaticPlaylistManager.serverBoundary(endedId,revision,
                snapshot.repeatMode==PlaybackRepeatMode.OFF && !snapshot.shuffleEnabled)
            if (binding !== AutomaticPlaylistManager.serverBinding()) return@launch
            val live=playbackSessionStore.restore()?.queueState ?: return@launch
            val liveRevision=ServerPlaylistClient.revision(live.songs.map { it.id },live.title,live.repeatMode.name,live.shuffleEnabled)
            if (player?.currentMediaItem?.mediaId != currentId || liveRevision!=revision) return@launch
            if (future != null && future.isNotEmpty()) {
                AutomaticPlaylistManager.acceptServerTail(binding,currentId,live.songs,future)?.let {
                    replacePersistedAutomaticQueue(it,currentId,replaceCurrent=false)
                }
            }
            val updated=playbackSessionStore.restore()?.queueState ?: return@launch
            if (player?.currentMediaItem?.mediaId==currentId) {
                val updatedRevision=ServerPlaylistClient.revision(updated.songs.map { it.id },updated.title,updated.repeatMode.name,updated.shuffleEnabled)
                AutomaticPlaylistManager.prepareServerBoundary(currentId,updated.songs,updatedRevision,
                    updated.repeatMode==PlaybackRepeatMode.OFF && !updated.shuffleEnabled)
            }
        }
    }

    private fun maybePrefetchAutomaticQueue(currentMediaId: String, transitionReason: Int) {
        val queue = playbackSessionStore.restore()?.queueState
        val active = AutomaticPlaylistManager.isActive()
        if (!active && queue?.title?.startsWith("Playlist Automatica · ") != true) return

        val remaining = queue?.let { it.songs.lastIndex - it.currentIndex }
        val trigger = when (transitionReason) {
            Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "auto"
            Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "seek"
            Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "repeat"
            else -> "playlist_changed"
        }
        val reason = when {
            !active -> "session_inactive"
            automaticPrefetchInFlight -> "request_in_flight"
            queue == null || !queue.hasQueue -> "queue_missing"
            queue.currentSong?.id != currentMediaId -> "queue_mismatch"
            queue.repeatMode != PlaybackRepeatMode.OFF -> "repeat_enabled"
            queue.shuffleEnabled -> "shuffle_enabled"
            remaining != null && remaining > AUTOMATIC_PREFETCH_THRESHOLD -> "above_threshold"
            else -> "request"
        }
        Log.d("LYRA_AUTO_PLAYLIST",
            "refillDecision trigger=$trigger position=${queue?.currentIndex?.plus(1)} " +
                "size=${queue?.songs?.size} remaining=$remaining reason=$reason")
        if (reason != "request") return

        automaticPrefetchInFlight = true
        serviceScope.launch {
            runCatching { AutomaticPlaylistManager.loadMore() }
                .onSuccess { fresh ->
                    if (fresh.isNotEmpty()) appendAutomaticSongsToPersistedQueue(fresh)
                }
                .onFailure { error ->
                    Log.w(
                        "LYRA_AUTO_PLAYLIST",
                        "prefetchFailed message=${error.message}",
                    )
                }
            automaticPrefetchInFlight = false
            // A media controller may request NEXT while a prefetch is running.
            val pendingId = pendingAutomaticNextMediaId
            pendingAutomaticNextMediaId = null
            if (pendingId != null) {
                val pendingQueue = playbackSessionStore.restore()
                if (pendingQueue?.queueState?.currentSong?.id == pendingId &&
                    player?.currentMediaItem?.mediaId == pendingId
                ) {
                    val target = pendingQueue.queueState.currentIndex + 1
                    if (target in pendingQueue.queueState.songs.indices) {
                        loadPersistedIndex(
                            restored = pendingQueue,
                            targetIndex = target,
                            reason = "EXTERNAL_AUTO_NEXT_PREFETCH",
                            expectedCurrentMediaId = pendingId,
                        )
                    } else {
                        recoverVerifiedQueue(pendingQueue, pendingId)
                    }
                }
            }
        }
    }

    private fun appendAutomaticSongsToPersistedQueue(fresh: List<Song>): RestoredPlaybackSession? {
        if (fresh.isEmpty()) return playbackSessionStore.restore()
        val restored = playbackSessionStore.restore() ?: return null
        val queue = restored.queueState
        if (!queue.hasQueue) return restored
        val existingIds = queue.songs.mapTo(mutableSetOf()) { it.id }
        val unique = fresh.filter { existingIds.add(it.id) }
        if (unique.isEmpty()) return restored

        val extended = queue.songs + unique
        val updated = queue.copy(songs = extended)
        playbackSessionStore.saveQueue(
            queueState = updated,
            unshuffledSongs = extended,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: restored.positionMs,
        )
        // Se Media3 sta riproducendo una coda nativa, estendiamo anche la
        // timeline reale: il semplice aggiornamento dello store non basta.
        player?.let { current ->
            val nativeIds = (0 until current.mediaItemCount).map { i -> current.getMediaItemAt(i).mediaId }
            val expectedPrefix = queue.songs.map { it.id }
            if (nativeIds == expectedPrefix && current.mediaItemCount > 1) {
                current.addMediaItems(unique.map { song -> buildDeferredAutoMediaItem(song, queue.title) })
            }
        }
        Log.d(
            "LYRA_AUTO_PLAYLIST",
            "queueExtended added=${unique.size} total=${extended.size}",
        )
        return playbackSessionStore.restore()
    }

    private fun extendAutomaticQueueAndAdvance(
        endedMediaId: String,
        restored: RestoredPlaybackSession,
    ) {
        if (automaticPrefetchInFlight) {
            pendingAutomaticNextMediaId = endedMediaId
            Log.d("LYRA_AUTO_PLAYLIST", "refillQueuedWhilePrefetching mediaId=$endedMediaId")
            return
        }
        automaticPrefetchInFlight = true
        serviceScope.launch {
            val updated = runCatching {
                val fresh = AutomaticPlaylistManager.loadMore()
                appendAutomaticSongsToPersistedQueue(fresh)
            }.onFailure { error ->
                Log.w(
                    "LYRA_AUTO_PLAYLIST",
                    "extendAtEndFailed message=${error.message}",
                )
            }.getOrNull() ?: restored

            automaticPrefetchInFlight = false
            pendingAutomaticNextMediaId = null
            val queue = updated.queueState
            if (queue.currentSong?.id != endedMediaId) return@launch
            val target = queue.currentIndex + 1
            if (target !in queue.songs.indices) {
                recoverVerifiedQueue(updated, endedMediaId)
                return@launch
            }
            loadPersistedIndex(
                restored = updated,
                targetIndex = target,
                reason = "SERVICE_AUTO_PLAYLIST_NEXT",
                expectedCurrentMediaId = endedMediaId,
            )
        }
    }

    private fun recoverVerifiedQueue(restored: RestoredPlaybackSession, currentId: String) {
        val queue = restored.queueState
        val target = VerifiedQueueRecovery.targetIndex(
            AutomaticPlaylistManager.isVerifiedSession(queue.title, queue.songs),
            queue.currentIndex,
            queue.songs.size,
        )
        if (target == null) {
            AutomaticPlaylistManager.notice.value =
                "Nessun altro brano verificato disponibile. Riprova più tardi."
            return
        }
        AutomaticPlaylistManager.notice.value =
            "Server non disponibile o catalogo esaurito: riprendo i brani verificati già ascoltati."
        loadPersistedIndex(restored, target, "VERIFIED_LOCAL_RECOVERY", currentId)
    }


    private fun startSimilarContinuationAtEnd(
        endedMediaId: String,
        restored: RestoredPlaybackSession,
    ) {
        if (automaticPrefetchInFlight) return
        val seed = restored.queueState.currentSong ?: return
        if (seed.isLocal) return
        automaticPrefetchInFlight = true
        serviceScope.launch {
            val updated = runCatching {
                val result = AutomaticPlaylistManager.startFromSeed(
                    seed = seed,
                    title = "Continua con musica simile",
                )
                appendAutomaticSongsToPersistedQueue(
                    result.songs.filterNot { it.id == seed.id }
                )
            }.onFailure { error ->
                AutomaticPlaylistManager.clear()
                Log.w(
                    "LYRA_PERSONALIZE",
                    "continueSimilarFailed message=${error.message}",
                )
            }.getOrNull() ?: restored

            automaticPrefetchInFlight = false
            val queue = updated.queueState
            if (queue.currentSong?.id != endedMediaId) return@launch
            val target = queue.currentIndex + 1
            if (target !in queue.songs.indices) return@launch
            loadPersistedIndex(
                restored = updated,
                targetIndex = target,
                reason = "SERVICE_SMART_CONTINUATION",
                expectedCurrentMediaId = endedMediaId,
            )
        }
    }

    private fun requestAutomaticAdvance(endedMediaId: String) {
        val restored = playbackSessionStore.restore()
        val queue = restored?.queueState
        if (AutomaticPlaylistManager.isActive() || queue?.title?.startsWith("Playlist Automatica · ") == true) {
            Log.d("LYRA_AUTO_PLAYLIST",
                "queueEndCheck active=${AutomaticPlaylistManager.isActive()} " +
                    "queueMatches=${queue?.currentSong?.id == endedMediaId} " +
                    "position=${queue?.currentIndex?.plus(1)} size=${queue?.songs?.size} " +
                    "repeat=${queue?.repeatMode} inFlight=$automaticPrefetchInFlight")
        }
        if (restored == null || queue == null || !queue.hasQueue || queue.currentSong?.id != endedMediaId) return

        val targetIndex = when (queue.repeatMode) {
            PlaybackRepeatMode.ONE -> queue.currentIndex
            PlaybackRepeatMode.ALL -> {
                if (queue.currentIndex < queue.songs.lastIndex) {
                    queue.currentIndex + 1
                } else {
                    0
                }
            }
            PlaybackRepeatMode.OFF -> {
                if (queue.currentIndex < queue.songs.lastIndex) {
                    queue.currentIndex + 1
                } else {
                    if (AutomaticPlaylistManager.isActive()) {
                        Log.d(
                            "LYRA_AUTO_PLAYLIST",
                            "queueTailReached index=${queue.currentIndex}; requesting more",
                        )
                        extendAutomaticQueueAndAdvance(endedMediaId, restored)
                        return
                    }
                    if (
                        PersonalizationSettings.continueSimilar.value &&
                        queue.allowSmartContinuation &&
                        queue.currentSong?.isLocal == false
                    ) {
                        Log.d(
                            "LYRA_PERSONALIZE",
                            "queueTailReached smartContinuation=true index=${queue.currentIndex}",
                        )
                        startSimilarContinuationAtEnd(endedMediaId, restored)
                        return
                    }
                    Log.d(
                        "LYRA_EXTERNAL",
                        "queueEnded index=${queue.currentIndex} repeat=OFF service=true"
                    )
                    return
                }
            }
        }

        loadPersistedIndex(
            restored = restored,
            targetIndex = targetIndex,
            reason = when (queue.repeatMode) {
                PlaybackRepeatMode.ONE -> "SERVICE_REPEAT_ONE"
                PlaybackRepeatMode.ALL -> "SERVICE_AUTO_NEXT_REPEAT_ALL"
                PlaybackRepeatMode.OFF -> "SERVICE_AUTO_NEXT"
            },
            expectedCurrentMediaId = endedMediaId,
        )
    }

    /**
     * Media3 a volte accetta il comando NEXT ma la timeline non cambia
     * (coda ricostruita, transizione gapless o controller Auto). Non inviamo
     * un secondo seek subito: controlliamo dopo un breve intervallo e
     * recuperiamo solo se il Player e la queue sono rimasti fermi.
     */
    private fun verifyNativeSeekAfterDelay(
        direction: Int,
        previousIndex: Int,
        previousMediaId: String?,
        previousPositionMs: Long,
    ) {
        mainHandler.postDelayed({
            val current = player ?: return@postDelayed
            val queue = playbackSessionStore.restore()?.queueState ?: return@postDelayed
            val nowMediaId = current.currentMediaItem?.mediaId
            val nowIndex = current.currentMediaItemIndex
            val decision = AutoSeekRecoveryPolicy.shouldRecover(
                direction = direction,
                beforeIndex = previousIndex,
                afterIndex = nowIndex,
                beforeMediaId = previousMediaId,
                afterMediaId = nowMediaId,
                beforePositionMs = previousPositionMs,
                afterPositionMs = current.currentPosition,
                navigationInFlight = navigationInFlight,
                persistedCurrentMediaId = queue.currentSong?.id,
                hasNext = queue.hasNext,
                hasPrevious = queue.hasPrevious,
            )
            if (decision) {
                Log.w(
                    "LYRA_AUTO",
                    "nativeSeekNotApplied direction=$direction playerIndex=$nowIndex " +
                        "queueIndex=${queue.currentIndex} mediaId=$nowMediaId; applyingSingleFallback"
                )
                requestExternalNavigation(direction, "NATIVE_SEEK_RECOVERY")
            }
        }, AUTO_SEEK_VERIFY_DELAY_MS)
    }

    private fun canNavigatePersistedQueue(direction: Int): Boolean {
        val queue = playbackSessionStore.restore()?.queueState ?: return false
        // Android Auto deve poter inviare NEXT anche mentre si prepara una
        // traccia: requestExternalNavigation gestisce la coda dei comandi.
        if (!queue.hasQueue) return false

        return if (direction > 0) {
            queue.hasNext || (AutomaticPlaylistManager.isActive() &&
                AutomaticPlaylistManager.currentTitle() == queue.title)
        } else queue.hasPrevious
    }

    private fun requestExternalNavigation(
        direction: Int,
        origin: String,
    ) {
        if (navigationInFlight) {
            if (pendingExternalDirections.size < MAX_PENDING_NAVIGATION_COMMANDS) {
                pendingExternalDirections.addLast(if (direction > 0) 1 else -1)
                Log.d(
                    "LYRA_EXTERNAL",
                    "navigationQueued reason=inFlight origin=$origin direction=$direction " +
                        "pending=${pendingExternalDirections.size}"
                )
            } else {
                Log.w("LYRA_EXTERNAL", "navigationQueueFull origin=$origin direction=$direction")
            }
            return
        }

        val restored = playbackSessionStore.restore()
        if (restored == null || !restored.queueState.hasQueue) {
            Log.d(
                "LYRA_EXTERNAL",
                "navigationIgnored reason=noPersistedQueue origin=$origin"
            )
            return
        }

        val queue = restored.queueState
        val targetIndex = if (direction > 0) {
            when {
                queue.currentIndex < queue.songs.lastIndex -> queue.currentIndex + 1
                queue.repeatMode == PlaybackRepeatMode.ALL && queue.songs.size > 1 -> 0
                else -> {
                    if (AutomaticPlaylistManager.isActive() &&
                        AutomaticPlaylistManager.currentTitle() == queue.title
                    ) {
                        val currentId = queue.currentSong?.id ?: return
                        AutomaticPlaylistManager.notice.value =
                            "Cerco altri brani del genere richiesto..."
                        Log.d(
                            "LYRA_AUTO_PLAYLIST",
                            "externalNextRefill origin=$origin mediaId=$currentId",
                        )
                        extendAutomaticQueueAndAdvance(currentId, restored)
                    }
                    return
                }
            }
        } else {
            when {
                queue.currentIndex > 0 -> queue.currentIndex - 1
                queue.repeatMode == PlaybackRepeatMode.ALL && queue.songs.size > 1 ->
                    queue.songs.lastIndex
                else -> return
            }
        }

        loadPersistedIndex(
            restored = restored,
            targetIndex = targetIndex,
            reason = if (direction > 0) "EXTERNAL_NEXT_$origin" else "EXTERNAL_PREVIOUS_$origin",
            expectedCurrentMediaId = player?.currentMediaItem?.mediaId,
        )
    }

    private fun finishExternalNavigation(success: Boolean, reason: String) {
        if (!success) {
            navigationInFlight = false
            if (pendingExternalDirections.isNotEmpty()) {
                Log.w(
                    "LYRA_EXTERNAL",
                    "navigationFailed reason=$reason droppedPending=${pendingExternalDirections.size}"
                )
                pendingExternalDirections.clear()
            }
            return
        }
        val pending = pendingExternalDirections.removeFirstOrNull()
        if (pending == null) {
            navigationInFlight = false
            return
        }
        // Manteniamo inFlight fino al prossimo turno del Looper, cosi'
        // eventuali nuove pressioni non superano i comandi gia' in attesa.
        val generation = navigationGeneration
        mainHandler.post {
            if (generation != navigationGeneration || mediaSession == null) return@post
            navigationInFlight = false
            requestExternalNavigation(pending, "QUEUED_AFTER_$reason")
        }
    }

    private fun loadPersistedIndex(
        restored: RestoredPlaybackSession,
        targetIndex: Int,
        reason: String,
        expectedCurrentMediaId: String?,
    ) {
        val song = restored.queueState.songs.getOrNull(targetIndex) ?: return
        val generation = ++navigationGeneration
        navigationInFlight = true

        Log.d(
            "LYRA_EXTERNAL",
            "load requested mediaId=${song.id} index=$targetIndex reason=$reason local=${song.isLocal}"
        )

        serviceScope.launch {
            runCatching {
                if (song.isLocal) {
                    PlaybackMediaItemFactory.local(
                        song = song,
                        artworkUri = autoArtworkUri(song.artworkUrlForPlayback, R.drawable.auto_art_lyra),
                        contextLabel = restored.queueState.title,
                    )
                } else {
                    val source = withContext(Dispatchers.IO) {
                        deferredPlaybackResolver?.preparedPlaybackSource(song.id)
                            ?: playbackRepository.getPlaybackSource(song.id)
                    }
                    PlaybackMediaItemFactory.playable(
                        source = source,
                        song = song,
                        artworkUri = autoArtworkUri(song.artworkUrlForPlayback, R.drawable.auto_art_lyra),
                        contextLabel = restored.queueState.title,
                    )
                }
            }.onSuccess { mediaItem ->
                if (generation != navigationGeneration) return@onSuccess

                val currentMediaId = player?.currentMediaItem?.mediaId
                if (
                    !expectedCurrentMediaId.isNullOrBlank() &&
                    currentMediaId != expectedCurrentMediaId
                ) {
                    Log.d(
                        "LYRA_EXTERNAL",
                        "load discarded mediaId=${song.id} expected=$expectedCurrentMediaId actual=$currentMediaId"
                    )
                    finishExternalNavigation(success = false, reason = "STALE_MEDIA")
                    return@onSuccess
                }

                val newQueue = restored.queueState.copy(
                    currentIndex = targetIndex,
                    isChangingTrack = false,
                    resumePositionMs = 0L,
                )
                playbackSessionStore.saveQueue(
                    queueState = newQueue,
                    unshuffledSongs = restored.unshuffledSongs,
                    positionMs = 0L,
                )

                if (reason.startsWith("AUTO_SKIP_")) {
                    profileOutcomeSuppressedForMediaId = expectedCurrentMediaId
                }
                player?.apply {
                    if (AudioSettings.gaplessEnabled.value && mediaItemCount > 1 && newQueue.songs.size > 1) {
                        val updatedItems = newQueue.songs.mapIndexed { index, candidate ->
                            if (index == targetIndex) mediaItem
                            else buildDeferredAutoMediaItem(candidate, newQueue.title)
                        }
                        setMediaItems(updatedItems, targetIndex, 0L)
                    } else {
                        setMediaItem(mediaItem)
                    }
                    prepare()
                    play()
                }

                finishExternalNavigation(success = true, reason = reason)
                Log.d(
                    "LYRA_EXTERNAL",
                    "load ready mediaId=${song.id} index=$targetIndex reason=$reason"
                )
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                Log.e(
                    "LYRA_EXTERNAL",
                    "load failed mediaId=${song.id} index=$targetIndex reason=$reason message=${error.message}",
                    error,
                )
                if (generation != navigationGeneration) return@onFailure
                finishExternalNavigation(success = false, reason = reason)
                val live = playbackSessionStore.restore() ?: return@onFailure
                if (player?.currentMediaItem?.mediaId != expectedCurrentMediaId ||
                    live.queueState.songs.map { it.id } != restored.queueState.songs.map { it.id } ||
                    live.queueState.currentIndex != restored.queueState.currentIndex
                ) return@onFailure
                val next = failedTrackSkipPolicy.nextIndex(
                    PersonalizationSettings.autoSkipFailedTracks.value,
                    targetIndex, live.queueState.songs.size,
                )
                if (next == null) {
                    AutomaticPlaylistManager.notice.value =
                        "Impossibile caricare ${song.title}. Controlla la connessione o scegli un altro brano."
                    return@onFailure
                }
                loadPersistedIndex(live, next, "AUTO_SKIP_RESOLUTION", expectedCurrentMediaId)
            }
        }
    }

    private fun syncPersistedQueueToCurrentMedia(mediaId: String) {
        val restored = playbackSessionStore.restore() ?: return
        val queue = restored.queueState
        if (!queue.hasQueue) return

        val nativeIndex = player
            ?.currentMediaItemIndex
            ?.takeIf { index ->
                index in queue.songs.indices && queue.songs[index].id == mediaId
            }

        val targetIndex = nativeIndex
            ?: queue.songs.indexOfFirst { it.id == mediaId }
                .takeIf { it >= 0 }
            ?: return

        if (targetIndex == queue.currentIndex) return

        val updatedQueue = queue.copy(
            currentIndex = targetIndex,
            isChangingTrack = false,
            resumePositionMs = 0L,
        )
        playbackSessionStore.saveQueue(
            queueState = updatedQueue,
            unshuffledSongs = restored.unshuffledSongs,
            positionMs = 0L,
        )

        Log.d(
            "LYRA_EXTERNAL",
            "nativeQueueTransition mediaId=$mediaId index=$targetIndex " +
                "size=${updatedQueue.songs.size}"
        )
    }

    private fun applyNativePlaybackModesFromPersistedQueue() {
        playbackSessionStore.restore()?.queueState?.let(::applyNativePlaybackModes)
    }

    private fun applyNativePlaybackModes(queue: PlaybackQueueUiState) {
        val currentPlayer = player ?: return
        suppressNativeModePersistence = true
        try {
            currentPlayer.repeatMode = when (queue.repeatMode) {
                PlaybackRepeatMode.OFF -> Player.REPEAT_MODE_OFF
                PlaybackRepeatMode.ONE -> Player.REPEAT_MODE_ONE
                PlaybackRepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
            currentPlayer.shuffleModeEnabled = queue.shuffleEnabled
        } finally {
            suppressNativeModePersistence = false
        }
    }

    private fun resetNativePlaybackModes() {
        val currentPlayer = player ?: return
        suppressNativeModePersistence = true
        try {
            currentPlayer.repeatMode = Player.REPEAT_MODE_OFF
            currentPlayer.shuffleModeEnabled = false
        } finally {
            suppressNativeModePersistence = false
        }
    }

    private fun persistNativeRepeatMode(repeatMode: Int) {
        val restored = playbackSessionStore.restore() ?: return
        val queue = restored.queueState
        if (!queue.hasQueue) return

        val lyraMode = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> PlaybackRepeatMode.ONE
            Player.REPEAT_MODE_ALL -> PlaybackRepeatMode.ALL
            else -> PlaybackRepeatMode.OFF
        }
        if (queue.repeatMode == lyraMode) return

        playbackSessionStore.saveQueue(
            queueState = queue.copy(repeatMode = lyraMode),
            unshuffledSongs = restored.unshuffledSongs,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: restored.positionMs,
        )
        Log.d("LYRA_EXTERNAL", "nativeRepeat mode=$lyraMode")
    }

    private fun persistNativeShuffleMode(enabled: Boolean) {
        val restored = playbackSessionStore.restore() ?: return
        val queue = restored.queueState
        if (!queue.hasQueue || queue.shuffleEnabled == enabled) return

        playbackSessionStore.saveQueue(
            queueState = queue.copy(shuffleEnabled = enabled),
            unshuffledSongs = restored.unshuffledSongs,
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: restored.positionMs,
        )
        Log.d("LYRA_EXTERNAL", "nativeShuffle enabled=$enabled")
    }

    private fun recordHistoryForCurrentIfNeeded() {
        val currentPlayer = player ?: return
        val mediaId = currentPlayer.currentMediaItem?.mediaId.orEmpty()
        if (mediaId.isBlank() || historyRecordedMediaId == mediaId) return
        val restored = playbackSessionStore.restore()?.queueState ?: return
        if (!restored.hasQueue) return
        // Se lo stesso brano ricorre piu' volte, usa prima l'indice nativo
        // (timeline completa) oppure l'indice gia' persistito, non la prima
        // occorrenza del suo videoId.
        val nativeIndex = currentPlayer.currentMediaItemIndex.takeIf { index ->
            index in restored.songs.indices && restored.songs[index].id == mediaId
        }
        val persistedIndex = restored.currentIndex.takeIf { index ->
            restored.songs.getOrNull(index)?.id == mediaId
        }
        val index = nativeIndex ?: persistedIndex
            ?: restored.songs.indexOfFirst { it.id == mediaId }
                .takeIf { it >= 0 } ?: restored.currentIndex
        val snapshot = restored.copy(currentIndex = index.coerceIn(0, restored.songs.lastIndex))
        ListeningHistoryStore.record(
            queueState = snapshot,
            automatic = AutomaticPlaylistManager.isActive() ||
                snapshot.title.startsWith("Radio:") ||
                snapshot.title.startsWith("Playlist Automatica") ||
                snapshot.title.startsWith("Il tuo ") ||
                snapshot.title.startsWith("Continua con musica simile"),
        )
        historyRecordedMediaId = mediaId
        Log.d("LYRA_HISTORY", "serviceRecorded mediaId=$mediaId queue=${snapshot.title} size=${snapshot.songs.size}")
    }

    private fun ensureProfileTrackingStarted() {
        if (!PersonalizationSettings.enabled.value || profileMediaId != null) return
        val currentPlayer = player ?: return
        val mediaId = currentPlayer.currentMediaItem?.mediaId.orEmpty()
        if (mediaId.isBlank()) return
        val restored = playbackSessionStore.restore()?.queueState ?: return
        val song = restored.songs.firstOrNull { it.id == mediaId }
            ?: restored.currentSong?.takeIf { it.id == mediaId }
            ?: return
        profileMediaId = mediaId
        profileSong = song
        profileQueueTitle = restored.title.orEmpty().ifBlank { "Lyra" }
        profilePositionMs = 0L
        profilePlaySegmentStartedAtElapsedMs = if (currentPlayer.isPlaying) SystemClock.elapsedRealtime() else 0L
        profileDurationMs = currentPlayer.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            ?: (song.durationSeconds * 1_000L).coerceAtLeast(0L)
        profileOutcomeRecordedForMediaId = null
        ListeningProfileStore.recordStarted(song, profileQueueTitle)
        YouTubeLikesManager.ratings.value[song.id]?.let { liked ->
            ListeningProfileStore.setLiked(song, liked)
        }
        Log.d(
            "LYRA_PERSONALIZE",
            "started mediaId=$mediaId context=${ListeningProfileStore.inferContextTag(profileQueueTitle)}"
        )
    }

    private fun updateProfileProgress(currentPlayer: Player) {
        if (profileMediaId == null || currentPlayer.currentMediaItem?.mediaId != profileMediaId) return
        flushProfilePlayedTime()
        profilePlaySegmentStartedAtElapsedMs = if (currentPlayer.isPlaying) SystemClock.elapsedRealtime() else 0L
        val duration = currentPlayer.duration
        if (duration != C.TIME_UNSET && duration > 0L) profileDurationMs = duration
        if (profileDurationMs > 0L) profilePositionMs = profilePositionMs.coerceAtMost(profileDurationMs)
    }

    private fun flushProfilePlayedTime() {
        if (profilePlaySegmentStartedAtElapsedMs <= 0L) return
        val now = SystemClock.elapsedRealtime()
        profilePositionMs += (now - profilePlaySegmentStartedAtElapsedMs).coerceAtLeast(0L)
        profilePlaySegmentStartedAtElapsedMs = 0L
        if (profileDurationMs > 0L) profilePositionMs = profilePositionMs.coerceAtMost(profileDurationMs)
    }

    private fun finalizeProfileOutcome(
        completed: Boolean,
        manualSkip: Boolean,
    ): AutomaticPlaylistManager.FeedbackResult? {
        val mediaId = profileMediaId ?: return null
        if (profileOutcomeRecordedForMediaId == mediaId) return null
        val song = profileSong ?: return null
        flushProfilePlayedTime()
        ListeningProfileStore.recordOutcome(
            song = song,
            queueTitle = profileQueueTitle,
            listenedMs = profilePositionMs,
            durationMs = profileDurationMs,
            completed = completed,
            manualSkip = manualSkip,
        )
        val feedback = AutomaticPlaylistManager.recordPlaybackOutcome(
            song = song,
            listenedMs = profilePositionMs,
            durationMs = profileDurationMs,
            completed = completed,
            manualSkip = manualSkip,
        )
        profileOutcomeRecordedForMediaId = mediaId
        Log.d(
            "LYRA_PERSONALIZE",
            "outcome mediaId=$mediaId completed=$completed manualSkip=$manualSkip listenedMs=$profilePositionMs durationMs=$profileDurationMs " +
                "earlySkip=${feedback?.earlySkip} streak=${feedback?.consecutiveEarlySkips}"
        )
        return feedback
    }

    private fun resetProfileTrackingForTransition() {
        profileMediaId = null
        profileSong = null
        profileQueueTitle = "Lyra"
        profilePositionMs = 0L
        profilePlaySegmentStartedAtElapsedMs = 0L
        profileDurationMs = 0L
        profileOutcomeRecordedForMediaId = null
    }

    private fun handleAutomaticSessionFeedback(
        currentMediaId: String,
        feedback: AutomaticPlaylistManager.FeedbackResult,
    ) {
        if (!feedback.earlySkip || !AutomaticPlaylistManager.isActive() || AutomaticPlaylistManager.serverOwnsQueue() || AutomaticPlaylistManager.hasArtistConstraint()) return

        if (!feedback.strongIntentShift) {
            val reordered = AutomaticPlaylistManager.rerankFutureAfterFeedback(currentMediaId)
            // Never cut the track that has just started after the user's skip.
            replacePersistedAutomaticQueue(reordered,currentMediaId,replaceCurrent=false)
            return
        }

        serviceScope.launch {
            runCatching { AutomaticPlaylistManager.pivotAfterRepeatedSkips(currentMediaId) }
                .onSuccess { fresh ->
                    if (fresh.isEmpty()) return@onSuccess
                    // Keep the current track; adapt only the future automatic tail.
                    if (player?.currentMediaItem?.mediaId == currentMediaId) {
                        replacePersistedAutomaticQueue(
                            AutomaticPlaylistManager.currentSongs(),
                            currentMediaId,
                            replaceCurrent = false,
                        )
                    }
                }
                .onFailure { error ->
                    Log.w("LYRA_AUTO_PLAYLIST", "intentPivotFailed message=${error.message}")
                    val reordered = AutomaticPlaylistManager.rerankFutureAfterFeedback(currentMediaId)
                    replacePersistedAutomaticQueue(reordered, currentMediaId, replaceCurrent = false)
                }
        }
    }

    private fun replacePersistedAutomaticQueue(
        managerSongs: List<Song>,
        currentMediaId: String,
        replaceCurrent: Boolean,
        nextMediaId: String? = null,
    ) {
        if (managerSongs.isEmpty()) return
        val restored = playbackSessionStore.restore() ?: return
        val queue = restored.queueState
        val oldIndex = queue.songs.indexOfFirst { it.id == currentMediaId }.takeIf { it >= 0 } ?: return

        val native = player?.takeIf { current ->
            AudioSettings.gaplessEnabled.value &&
                current.mediaItemCount > 1 &&
                current.currentMediaItem?.mediaId == currentMediaId &&
                current.mediaItemCount == queue.songs.size
        }
        val managerCurrentIndex = managerSongs.indexOfFirst { it.id == currentMediaId }
        val newSongs: List<Song>
        val newIndex: Int
        if (!replaceCurrent && native != null) {
            // L'IA deve riordinare anche la timeline Media3, non solo lo store.
            // Conserviamo i brani gia' ascoltati + quello corrente, poi
            // sostituiamo i soli elementi futuri senza interrompere l'audio.
            val prefix = queue.songs.take(oldIndex + 1)
            val used = prefix.mapTo(mutableSetOf()) { it.id }
            val newTail = managerSongs.filter { used.add(it.id) }
            newSongs = prefix + newTail
            newIndex = oldIndex
        } else if (replaceCurrent && managerCurrentIndex < 0) {
            val prefix = queue.songs.take(oldIndex)
            newSongs = (prefix + managerSongs.drop(prefix.size)).distinctBy { it.id }
            newIndex = oldIndex.coerceAtMost(newSongs.lastIndex)
        } else {
            newSongs = managerSongs
            newIndex = managerSongs.indexOfFirst { it.id == (nextMediaId ?: currentMediaId) }
                .takeIf { it >= 0 } ?: oldIndex.coerceAtMost(managerSongs.lastIndex)
        }
        if (newSongs.isEmpty()) return

        val updatedQueue = queue.copy(
            title = AutomaticPlaylistManager.currentTitle() ?: queue.title,
            songs = newSongs,
            currentIndex = newIndex,
            isChangingTrack = replaceCurrent,
            resumePositionMs = 0L,
        )
        playbackSessionStore.saveQueue(
            queueState = updatedQueue,
            unshuffledSongs = newSongs,
            positionMs = if (replaceCurrent) 0L else player?.currentPosition?.coerceAtLeast(0L) ?: 0L,
        )

        if (!replaceCurrent && native != null) {
            val futureItems = newSongs.drop(newIndex + 1).map { candidate ->
                buildDeferredAutoMediaItem(candidate, updatedQueue.title)
            }
            // Il listener viene chiamato durante onMediaItemTransition:
            // posticipiamo di un turno la sostituzione della timeline.
            mainHandler.post {
                if (player === native && native.currentMediaItem?.mediaId == currentMediaId) {
                    native.replaceMediaItems(newIndex + 1, native.mediaItemCount, futureItems)
                }
            }
        }

        if (replaceCurrent) {
            profileOutcomeSuppressedForMediaId = currentMediaId
            mainHandler.postDelayed({
                if (profileOutcomeSuppressedForMediaId == currentMediaId) {
                    profileOutcomeSuppressedForMediaId = null
                }
            }, 8_000L)
            loadPersistedIndex(
                restored = playbackSessionStore.restore() ?: return,
                targetIndex = newIndex,
                reason = "SESSION_INTENT_SHIFT",
                expectedCurrentMediaId = currentMediaId,
            )
        }
    }

    private fun currentSongForLike(): Song? {
        val mediaId = player?.currentMediaItem?.mediaId.orEmpty()
        if (mediaId.isBlank() || mediaId.startsWith("local:")) return null

        val queue = playbackSessionStore.restore()?.queueState
        return queue?.songs?.firstOrNull { it.id == mediaId }
            ?: queue?.currentSong?.takeIf { it.id == mediaId }
    }

    private fun persistCurrentPosition(synchronous: Boolean) {
        val currentPlayer = player ?: return
        if (currentPlayer.mediaItemCount == 0) return

        playbackSessionStore.savePosition(
            positionMs = currentPlayer.currentPosition.coerceAtLeast(0L),
            synchronous = synchronous,
        )
    }

    private fun isLyraUiController(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
    ): Boolean {
        if (controllerInfo.packageName != packageName) return false

        return !session.isMediaNotificationController(controllerInfo) &&
            !session.isAutoCompanionController(controllerInfo) &&
            !session.isAutomotiveController(controllerInfo)
    }

    private fun controllerLabel(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
    ): String {
        return when {
            session.isAutoCompanionController(controllerInfo) -> "ANDROID_AUTO"
            session.isAutomotiveController(controllerInfo) -> "AUTOMOTIVE_OS"
            session.isMediaNotificationController(controllerInfo) -> "SYSTEM_MEDIA"
            else -> controllerInfo.packageName.ifBlank { "UNKNOWN" }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swipe-away dell'app o chiusura brusca del task: il servizio puo'
        // restare vivo, ma fissiamo comunque subito il punto esatto noto.
        persistCurrentPosition(synchronous = true)
        Log.d(
            "LYRA_RESTORE",
            "taskRemoved positionMs=${player?.currentPosition?.coerceAtLeast(0L) ?: 0L}"
        )
        super.onTaskRemoved(rootIntent)
    }

    /** Preload dei byte Media3 riconfigurabile senza distruggere il player. */
    private fun updateAudioPreloadConfiguration(meteredOverride: Boolean? = null) {
        val current = player ?: return
        val metered = meteredOverride ?: runCatching {
            getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        }.getOrDefault(true)
        val enabled = AudioSettings.preloadAudioEnabled.value &&
            AudioSettings.gaplessEnabled.value && !metered
        current.preloadConfiguration = if (enabled) {
            ExoPlayer.PreloadConfiguration(5_000_000L)
        } else {
            ExoPlayer.PreloadConfiguration.DEFAULT
        }
    }

    private fun installNetworkAwareAudioPreload() {
        val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                mainHandler.post { updateAudioPreloadConfiguration(meteredOverride = metered) }
            }

            override fun onLost(network: Network) {
                mainHandler.post { updateAudioPreloadConfiguration() }
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onSuccess {
                preloadNetworkManager = connectivity
                preloadNetworkCallback = callback
            }
            .onFailure { error ->
                Log.w("LYRA_AUDIO", "networkPreloadMonitorUnavailable ${error.message}")
            }
        updateAudioPreloadConfiguration()
    }

    override fun onDestroy() {
        bufferingWatchdog?.let(mainHandler::removeCallbacks)
        player?.let(::updateProfileProgress)
        finalizeProfileOutcome(completed = false, manualSkip = false)
        AutomaticPlaylistManager.clear()
        mainHandler.removeCallbacks(positionCheckpoint)
        preloadNetworkCallback?.let { callback ->
            runCatching { preloadNetworkManager?.unregisterNetworkCallback(callback) }
        }
        preloadNetworkCallback = null
        preloadNetworkManager = null
        persistCurrentPosition(synchronous = true)
        navigationGeneration++
        navigationInFlight = false
        pendingExternalDirections.clear()
        streamPrefetchJob?.cancel()
        streamPrefetchJob = null
        deferredPlaybackResolver = null

        player?.removeListener(playerListener)
        audioCrossfadeCoordinator?.release()
        audioCrossfadeCoordinator = null
        audioNormalizationController?.release()
        audioNormalizationController = null

        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }

        mediaSession = null
        player = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val POSITION_CHECKPOINT_MS = 2_000L
        const val AUTOMATIC_PREFETCH_THRESHOLD = 5
        const val AUTO_SEEK_VERIFY_DELAY_MS = 400L
        const val MAX_PENDING_NAVIGATION_COMMANDS = 3
        const val SESSION_ACTIVITY_REQUEST_CODE = 3008
        const val AUTO_COMMAND_TOGGLE_LIKE = "lyra.auto.toggle_like"
        const val AUTO_AUTH_WAIT_MS = 8_000L
        const val AUTO_LIBRARY_ROOT_ID = "lyra:auto:root"
        const val AUTO_ALL_PLAYLISTS_ID = "lyra:auto:all-playlists"
        const val AUTO_DISCOVER_ROOT_ID = "lyra:auto:discover"
        const val AUTO_AUTOMATIC_ROOT_ID = "lyra:auto:automatic"
        const val AUTO_SAVED_AUTOMATIC_ROOT_ID = "lyra:auto:automatic:saved"
        const val AUTO_MIX_ID = "lyra:auto:mix"
        const val AUTO_HISTORY_ROOT_ID = "lyra:auto:history"
        const val AUTO_HISTORY_ITEM_PREFIX = "lyra:auto:history:item:"
        const val AUTO_AUTOMATIC_PRESET_PREFIX = "lyra:auto:automatic:preset:"
        const val AUTO_DISCOVER_FOLDER_PREFIX = "lyra:auto:discover:folder:"
        const val AUTO_DISCOVER_GROUP_PREFIX = "lyra:auto:discover:group:"
        const val AUTO_LOCAL_ROOT_ID = "lyra:auto:local"
        const val AUTO_LOCAL_SONGS_ID = "lyra:auto:local:songs"
        const val AUTO_LOCAL_ALBUMS_ID = "lyra:auto:local:albums"
        const val AUTO_LOCAL_ARTISTS_ID = "lyra:auto:local:artists"
        const val AUTO_LOCAL_PERMISSION_ID = "lyra:auto:local:permission"
        const val AUTO_LOCAL_ALBUM_PREFIX = "lyra:auto:local:album:"
        const val AUTO_LOCAL_ARTIST_PREFIX = "lyra:auto:local:artist:"
        const val AUTO_PLAYLIST_PREFIX = "lyra:auto:playlist:"
        const val AUTO_SONG_PREFIX = "lyra:auto:song:"
        const val AUTO_EXTRA_QUEUE_TITLE = "lyra.auto.queueTitle"
        const val AUTO_EXTRA_QUEUE_INDEX = "lyra.auto.queueIndex"
    }
}

private data class AutoSongContext(
    val song: Song,
    val queueTitle: String,
    val queueSongs: List<Song>,
    val index: Int,
    val shuffleEnabled: Boolean = false,
    val automaticSeed: Boolean = false,
    val historyAutomatic: Boolean = false,
    val allowSmartContinuation: Boolean = true,
)