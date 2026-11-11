package app.lyra.music.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lyra.music.R
import app.lyra.music.genre.GenreClassifierSettings
import app.lyra.music.genre.LyraServerMonitor
import app.lyra.music.core.model.Playlist
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.update.GitHubUpdateChecker
import coil3.compose.AsyncImage

@Composable
fun HomeScreen(
    onPlaylistClick: (String) -> Unit,
    onAuthorizeYouTube: () -> Unit,
    onOpenSettings: () -> Unit,
    availableUpdate: GitHubUpdateChecker.UpdateInfo?,
    showUpdateBanner: Boolean,
    onDismissUpdateBanner: () -> Unit,
    onOpenUpdateSettings: () -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLocalLibrary: () -> Unit,
    onOpenRecentHistory: () -> Unit,
    onStartDailyMix: () -> Unit,
    dailyMixLoading: Boolean,
    dailyMixError: String?,
    viewModel: HomeViewModel = viewModel()
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val syncWarning by viewModel.syncWarning.collectAsStateWithLifecycle()
    val serverConnectionEnabled by GenreClassifierSettings.connectionEnabled.collectAsStateWithLifecycle()
    val serverStatus by LyraServerMonitor.status.collectAsStateWithLifecycle()
    val serverAuthorized by GenreClassifierSettings.authorized.collectAsStateWithLifecycle()
    val authorizationState by
        viewModel.authorizationState.collectAsStateWithLifecycle()
    var favoritesUnavailable by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .offset(x = (-10).dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.home_header_lyra_logo),
                    contentDescription = "Lyra",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp),
                )
                Text(
                    text = "La tua musica sempre con te",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    letterSpacing = (-0.2).sp,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 1.dp, top = 2.dp),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "Impostazioni",
                        )
                    }
                    if (availableUpdate != null) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = (-7).dp, y = 7.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape)
                        )
                    }
                }

                when {
                    authorizationState.isChecking || authorizationState.isAuthorizing -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(30.dp),
                            strokeWidth = 2.5.dp,
                        )
                    }

                    authorizationState.isAuthorized -> {
                        ConnectedGoogleMenu(
                            isRefreshing = isRefreshing,
                            profileName = authorizationState.profileName,
                            profilePictureUrl = authorizationState.profilePictureUrl,
                            onRefresh = viewModel::refreshLibrary,
                            onDisconnect = viewModel::disconnect,
                        )
                    }
                }
            }
        }

        if (!serverConnectionEnabled || serverStatus == LyraServerMonitor.Status.OFFLINE) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Text(
                    "Server Lyra ${if (serverConnectionEnabled) "non raggiungibile" else "disattivato"}: le playlist potrebbero essere meno precise. La musica continua normalmente.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        if (showUpdateBanner && availableUpdate != null) {
            UpdateAvailableBanner(
                update = availableUpdate,
                onOpen = onOpenUpdateSettings,
                onDismiss = onDismissUpdateBanner,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
        ) {
            HomeFeatureCard(
                title = "Scopri",
                icon = { Icon(Icons.Rounded.Explore, contentDescription = null) },
                onClick = onOpenDiscover,
                enabled = serverAuthorized,
                modifier = Modifier.weight(1f),
            )
            HomeFeatureCard(
                title = "Cerca",
                icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                onClick = onOpenSearch,
                modifier = Modifier.weight(1f),
            )
            HomeFeatureCard(
                title = "Dispositivo",
                icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                onClick = onOpenLocalLibrary,
                modifier = Modifier.weight(1f),
            )
        }

        // Seconda riga: recente / Mix per me / preferiti.
        val favoritesPlaylistId = YouTubeLibraryCache.favoritesPlaylistId()
            ?: playlists.firstOrNull { it.title.trim().equals("Preferiti", ignoreCase = true) }?.id
        // Nascondi il duplicato dalla Home, mantenendo la playlist nei dati
        // e nel pulsante Preferiti sotto Dispositivo.
        val visiblePlaylists = playlists.filterNot { playlist ->
            playlist.id == favoritesPlaylistId ||
                playlist.title.trim().equals("Preferiti", ignoreCase = true)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            HomeFeatureCard(
                title = "Ascoltati di recente",
                icon = { Icon(Icons.Rounded.History, contentDescription = null) },
                onClick = onOpenRecentHistory,
                modifier = Modifier.weight(1f),
            )
            HomeFeatureCard(
                title = if (dailyMixLoading) "Creo il mix..." else "Mix per me",
                icon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null) },
                onClick = onStartDailyMix,
                enabled = serverAuthorized && !dailyMixLoading,
                modifier = Modifier.weight(1f),
            )
            HomeFeatureCard(
                title = "Preferiti",
                enabled = authorizationState.isAuthorized && !authorizationState.isChecking && !authorizationState.isAuthorizing,
                icon = { Icon(Icons.Rounded.Favorite, contentDescription = null) },
                onClick = {
                    if (favoritesPlaylistId != null) onPlaylistClick(favoritesPlaylistId)
                    else favoritesUnavailable = true
                },
                modifier = Modifier.weight(1f),
            )
        }

        dailyMixError?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (favoritesUnavailable) {
            Text(
                "Per aprire Preferiti, collega e sincronizza il tuo account YouTube Music.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        Text(
            text = "Le mie playlist",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp, bottom = 12.dp)
        )

        when {
            authorizationState.isChecking -> {
                LoadingMessage("Verifica account Google...")
            }

            authorizationState.isAuthorizing -> {
                LoadingMessage("Autorizzazione Google...")
            }

            !authorizationState.isAuthorized -> {
                authorizationState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Collega Google per sincronizzare le tue playlist musicali.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onAuthorizeYouTube) {
                            Text("Accedi con Google")
                        }
                    }
                }

                OutlinedButton(
                    onClick = { onPlaylistClick(DIAGNOSTIC_PLAYLIST_ID) },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Apri playlist YouTube di test")
                }

                if (visiblePlaylists.isNotEmpty()) {
                    Text(
                        text = "Playlist Lyra salvate",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                    visiblePlaylists.take(8).forEach { playlist ->
                        PlaylistCard(
                            playlist = playlist,
                            onClick = { onPlaylistClick(playlist.id) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            else -> {
                if (isLoading && playlists.isEmpty()) {
                    LoadingMessage("Sincronizzazione...")
                } else {
                    syncWarning?.let { message ->
                        StatusMessage(message)
                    }

                    errorMessage?.let { message ->
                        StatusMessage(message, isError = true)
                    }

                    if (!isRefreshing && errorMessage == null && visiblePlaylists.isEmpty()) {
                        Text(
                            text = "Nessun'altra playlist da mostrare.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(
                            items = visiblePlaylists,
                            key = { it.id },
                        ) { playlist ->
                            PlaylistCard(
                                playlist = playlist,
                                onClick = { onPlaylistClick(playlist.id) }
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeFeatureCard(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        ),
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 12.dp),
        ) {
            icon()
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

@Composable
private fun ConnectedGoogleMenu(
    isRefreshing: Boolean,
    profileName: String?,
    profilePictureUrl: String?,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                tonalElevation = 4.dp,
                shadowElevation = 2.dp,
            ) {
                if (!profilePictureUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = profilePictureUrl,
                        contentDescription = profileName?.let { "Profilo Google di $it" }
                            ?: "Profilo Google",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        val initial = profileName
                            ?.trim()
                            ?.firstOrNull()
                            ?.uppercase()
                        if (initial != null) {
                            Text(
                                text = initial,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }

            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            if (!profileName.isNullOrBlank()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = profileName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
            }
            DropdownMenuItem(
                text = { Text("Aggiorna playlist") },
                leadingIcon = {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onRefresh()
                },
                enabled = !isRefreshing,
            )
            DropdownMenuItem(
                text = { Text("Disconnetti Google") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onDisconnect()
                },
                enabled = !isRefreshing,
            )
        }
    }
}

@Composable
private fun UpdateAvailableBanner(
    update: GitHubUpdateChecker.UpdateInfo,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Download,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 2.dp),
            ) {
                Text(
                    text = "Lyra ${update.versionName} disponibile",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Nuovo aggiornamento pronto. Tocca qui per installarlo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Nascondi aggiornamento per questa sessione",
                )
            }
        }
    }
}

@Composable
private fun LoadingMessage(text: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
    ) {
        CircularProgressIndicator()
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun StatusMessage(
    text: String,
    isError: Boolean = false,
) {
    Surface(
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!playlist.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = playlist.thumbnailUrl,
                    contentDescription = playlist.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(66.dp)
                        .clip(RoundedCornerShape(14.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                playlist.songCount?.let { count ->
                    Text(
                        text = if (count == 1) "1 brano" else "$count brani",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }

            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Apri playlist",
                )
            }
        }
    }
}

private const val DIAGNOSTIC_PLAYLIST_ID =
    "PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI"
