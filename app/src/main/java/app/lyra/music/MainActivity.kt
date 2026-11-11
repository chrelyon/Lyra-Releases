package app.lyra.music

import android.app.PendingIntent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.lyra.music.genre.LyraServerMonitor
import app.lyra.music.diagnostics.DiagnosticsReporter
import app.lyra.music.diagnostics.LyraLog
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.ui.home.HomeScreen
import app.lyra.music.ui.home.HomeViewModel
import app.lyra.music.ui.home.ListeningHistoryScreen
import app.lyra.music.ui.discover.DiscoverScreen
import app.lyra.music.ui.local.LocalLibraryScreen
import app.lyra.music.ui.search.SearchScreen
import app.lyra.music.ui.player.MiniPlayer
import app.lyra.music.ui.player.NowPlayingScreen
import app.lyra.music.ui.playlist.PlaylistScreen
import app.lyra.music.ui.queue.QueueScreen
import app.lyra.music.ui.settings.SettingsScreen
import app.lyra.music.ui.analytics.ListeningAnalyticsScreen
import app.lyra.music.ui.theme.LyraTheme
import app.lyra.music.update.GitHubUpdateChecker
import app.lyra.music.update.UpdateInstaller
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import app.lyra.music.youtube.cache.YouTubeLibraryCache

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by lazy {
        ViewModelProvider(this)[HomeViewModel::class.java]
    }

    // Incrementato ogni volta che l'Activity torna in foreground. La UI usa
    // questo trigger per verificare subito se GitHub pubblica una nuova release.
    private var updateCheckTrigger by mutableIntStateOf(0)

    private val youtubeAuthorizationLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult()
        ) { result ->
            YouTubeAuthorizationManager.handleAuthorizationResult(
                activity = this,
                resultCode = result.resultCode,
                data = result.data,
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LyraLog.d("LYRA_APP", "MainActivity onCreate")

        UpdateInstaller.cleanupAfterSuccessfulUpdate(applicationContext)

        YouTubeAuthorizationManager.initialize(applicationContext)
        YouTubeLibraryCache.initialize(applicationContext)
        YouTubeAuthorizationManager.refreshSilently()

        setContent {
            LyraTheme {
                Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.background) {
                    val navController = rememberNavController()
                    val currentBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = currentBackStackEntry?.destination?.route

                    val playbackViewModel: PlaybackViewModel = viewModel()
                    val playbackState by
                        playbackViewModel.uiState.collectAsStateWithLifecycle()
                    val queueState by
                        playbackViewModel.queueState.collectAsStateWithLifecycle()
                    val queueNotice by
                        playbackViewModel.queueNotice.collectAsStateWithLifecycle()
                    val automaticPlaylistLoading by
                        playbackViewModel.automaticPlaylistLoading.collectAsStateWithLifecycle()
                    val automaticPlaylistError by
                        playbackViewModel.automaticPlaylistError.collectAsStateWithLifecycle()
                    var availableUpdate by remember {
                        mutableStateOf<GitHubUpdateChecker.UpdateInfo?>(null)
                    }
                    var updateBannerDismissed by rememberSaveable { mutableStateOf(false) }
                    var focusUpdateSettings by rememberSaveable { mutableStateOf(false) }

                    LaunchedEffect(updateCheckTrigger) {
                        when (val result = GitHubUpdateChecker.checkAutomatically(this@MainActivity)) {
                            is GitHubUpdateChecker.Result.Available -> {
                                availableUpdate = result.update
                                updateBannerDismissed = false
                            }

                            is GitHubUpdateChecker.Result.UpToDate,
                            GitHubUpdateChecker.Result.NoPublishedRelease -> {
                                availableUpdate = null
                            }

                            is GitHubUpdateChecker.Result.Error -> Unit
                        }
                    }

                    // Dopo un cold start il servizio non contiene ancora un URL audio.
                    // La queue persistita fornisce comunque i metadata al mini-player;
                    // lo stream verra' risolto solo quando l'utente preme PLAY.
                    val displayedPlaybackState = if (playbackState.hasMediaItem) {
                        playbackState
                    } else {
                        queueState.currentSong?.let { song ->
                            playbackState.copy(
                                hasMediaItem = true,
                                isPlaying = false,
                                isBuffering = false,
                                isEnded = false,
                                mediaId = song.id,
                                title = song.title,
                                artist = song.artist,
                                artworkUrl = song.thumbnailUrl,
                                positionMs = queueState.resumePositionMs,
                                durationMs = song.durationSeconds
                                    .coerceAtLeast(0L) * 1_000L,
                            )
                        } ?: playbackState
                    }

                    Scaffold(
                        bottomBar = {
                            if (
                                displayedPlaybackState.hasMediaItem &&
                                currentRoute != NOW_PLAYING_ROUTE
                            ) {
                                MiniPlayer(
                                    state = displayedPlaybackState,
                                    onOpen = {
                                        navController.navigate(NOW_PLAYING_ROUTE) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onPause = playbackViewModel::pause,
                                    onResume = playbackViewModel::resume,
                                    onStop = playbackViewModel::stop,
                                )
                            }
                        }
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = HOME_ROUTE,
                            modifier = Modifier.padding(innerPadding),
                        ) {
                            composable(HOME_ROUTE) {
                                HomeScreen(
                                    onPlaylistClick = { playlistId ->
                                        navController.navigate("playlist/$playlistId")
                                    },
                                    onAuthorizeYouTube = {
                                        requestYouTubeAuthorization()
                                    },
                                    onOpenSettings = {
                                        focusUpdateSettings = false
                                        navController.navigate(SETTINGS_ROUTE) {
                                            launchSingleTop = true
                                        }
                                    },
                                    availableUpdate = availableUpdate,
                                    showUpdateBanner = availableUpdate != null && !updateBannerDismissed,
                                    onDismissUpdateBanner = { updateBannerDismissed = true },
                                    onOpenUpdateSettings = {
                                        focusUpdateSettings = true
                                        navController.navigate(SETTINGS_ROUTE) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onOpenDiscover = {
                                        navController.navigate(DISCOVER_ROUTE) { launchSingleTop = true }
                                    },
                                    onOpenSearch = {
                                        navController.navigate(SEARCH_ROUTE) { launchSingleTop = true }
                                    },
                                    onOpenLocalLibrary = {
                                        navController.navigate(LOCAL_LIBRARY_ROUTE) { launchSingleTop = true }
                                    },
                                    onOpenRecentHistory = {
                                        navController.navigate(HISTORY_ROUTE) { launchSingleTop = true }
                                    },
                                    onStartDailyMix = playbackViewModel::startPersonalizedMix,
                                    dailyMixLoading = automaticPlaylistLoading,
                                    dailyMixError = automaticPlaylistError,
                                    viewModel = homeViewModel,
                                )
                            }

                            composable(
                                route = "playlist/{playlistId}"
                            ) { backStackEntry ->
                                val playlistId =
                                    backStackEntry.arguments
                                        ?.getString("playlistId")
                                        ?: return@composable

                                PlaylistScreen(
                                    playlistId = playlistId,
                                    playbackViewModel = playbackViewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(HISTORY_ROUTE) {
                                ListeningHistoryScreen(
                                    playbackViewModel = playbackViewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(DISCOVER_ROUTE) {
                                DiscoverScreen(
                                    playbackViewModel = playbackViewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(SEARCH_ROUTE) {
                                SearchScreen(
                                    playbackViewModel = playbackViewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(LOCAL_LIBRARY_ROUTE) {
                                LocalLibraryScreen(
                                    playbackViewModel = playbackViewModel,
                                    onBack = { navController.popBackStack() },
                                )
                            }

                            composable(NOW_PLAYING_ROUTE) {
                                NowPlayingScreen(
                                    state = displayedPlaybackState,
                                    queueState = queueState,
                                    onBack = { navController.popBackStack() },
                                    onPrevious = playbackViewModel::previous,
                                    onPause = playbackViewModel::pause,
                                    onResume = playbackViewModel::resume,
                                    onNext = playbackViewModel::next,
                                    onToggleShuffle = playbackViewModel::toggleShuffle,
                                    onCycleRepeat = playbackViewModel::cycleRepeatMode,
                                    onOpenQueue = {
                                        navController.navigate(QUEUE_ROUTE) {
                                            launchSingleTop = true
                                        }
                                    },
                                    onSeekTo = playbackViewModel::seekTo,
                                )
                            }

                            composable(QUEUE_ROUTE) {
                                QueueScreen(
                                    queueState = queueState,
                                    onBack = { navController.popBackStack() },
                                    onPlayIndex = playbackViewModel::playQueueIndex,
                                    onMoveUp = { index ->
                                        playbackViewModel.moveQueueItem(index, index - 1)
                                    },
                                    onMoveDown = { index ->
                                        playbackViewModel.moveQueueItem(index, index + 1)
                                    },
                                    onRemove = playbackViewModel::removeQueueItem,
                                    onSaveAutomaticPlaylist = playbackViewModel::saveCurrentAutomaticPlaylist,
                                    notice = queueNotice,
                                )
                            }

                            composable(SETTINGS_ROUTE) {
                                SettingsScreen(
                                    onBack = { navController.popBackStack() },
                                    onSwitchGoogleAccount = { requestYouTubeAuthorization(forceAccountSelection = true) },
                                    onOpenListeningAnalytics = {
                                        navController.navigate(ANALYTICS_ROUTE) { launchSingleTop = true }
                                    },
                                    availableUpdate = availableUpdate,
                                    focusUpdates = focusUpdateSettings,
                                    onFocusUpdatesHandled = { focusUpdateSettings = false },
                                    onUpdateResult = { result ->
                                        when (result) {
                                            is GitHubUpdateChecker.Result.Available -> {
                                                availableUpdate = result.update
                                                updateBannerDismissed = false
                                            }
                                            is GitHubUpdateChecker.Result.UpToDate,
                                            GitHubUpdateChecker.Result.NoPublishedRelease -> {
                                                availableUpdate = null
                                            }
                                            is GitHubUpdateChecker.Result.Error -> Unit
                                        }
                                    },
                                )
                            }
                            composable(ANALYTICS_ROUTE) {
                                ListeningAnalyticsScreen(onBack = { navController.popBackStack() })
                            }
                        }
                    }



                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateCheckTrigger += 1
        LyraLog.d("LYRA_UPDATE", "automatic check trigger=$updateCheckTrigger")
        // Recheck Ubuntu connectivity on every foreground entry.
        LyraServerMonitor.refresh()
        DiagnosticsReporter.scheduleUpload(this)
    }

    override fun onResume() {
        super.onResume()
        LyraLog.d("LYRA_APP", "MainActivity onResume")
        homeViewModel.onAppForegrounded()
    }

    private fun requestYouTubeAuthorization(forceAccountSelection: Boolean = false) {
        YouTubeAuthorizationManager.authorize(
            activity = this,
            forceAccountSelection = forceAccountSelection,
            onResolutionRequired = ::launchYouTubeAuthorization,
        )
    }

    private fun launchYouTubeAuthorization(pendingIntent: PendingIntent) {
        youtubeAuthorizationLauncher.launch(
            IntentSenderRequest.Builder(
                pendingIntent.intentSender
            ).build()
        )
    }

    private companion object {
        const val HOME_ROUTE = "home"
        const val NOW_PLAYING_ROUTE = "now-playing"
        const val QUEUE_ROUTE = "queue"
        const val SETTINGS_ROUTE = "settings"
        const val ANALYTICS_ROUTE = "listening-analytics"
        const val DISCOVER_ROUTE = "discover"
        const val HISTORY_ROUTE = "history"
        const val SEARCH_ROUTE = "search"
        const val LOCAL_LIBRARY_ROUTE = "local-library"
    }
}
