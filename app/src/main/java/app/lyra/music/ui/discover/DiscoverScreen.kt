package app.lyra.music.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mood
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import app.lyra.music.youtube.repository.DiscoveryDirectMixRepository
import app.lyra.music.personalization.PersonalizedMixEngine

@Composable
fun DiscoverScreen(
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
    onPlaylistClick: (String) -> Unit,
    viewModel: DiscoverViewModel = viewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val selectedGroupId by viewModel.selectedGroupId.collectAsStateWithLifecycle()
    val selectedGroup by viewModel.selectedGroup.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val automaticSource by app.lyra.music.playback.AutomaticPlaylistManager.source.collectAsStateWithLifecycle()
    val automaticPlaylistLoading by playbackViewModel.automaticPlaylistLoading.collectAsStateWithLifecycle()
    val automaticPlaylistError by playbackViewModel.automaticPlaylistError.collectAsStateWithLifecycle()

    val selectedFolder = folders.firstOrNull { it.id == selectedFolderId }
    val selectedGroupSummary = groups.firstOrNull { it.id == selectedGroupId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    when {
                        selectedGroupId != null -> viewModel.closeGroup()
                        selectedFolderId != null -> viewModel.closeFolder()
                        else -> onBack()
                    }
                },
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Text(
                text = selectedGroup?.title
                    ?: selectedGroupSummary?.title
                    ?: selectedFolder?.title
                    ?: "Scopri",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (selectedGroupId != null) {
                IconButton(onClick = viewModel::refresh, enabled = !isLoading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna")
                }
            }
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
        automaticPlaylistError?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }
        if (automaticPlaylistLoading) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Lyra sta preparando la musica…")
            }
        }

        when {
            selectedFolderId == null -> {
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    item {
                        Text(
                            text = "Esplora YouTube Music",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }

                    items(folders, key = { it.id }) { folder ->
                        DiscoverCard(
                            title = folder.title,
                            subtitle = if (folder.id == "attivita") folder.subtitle else "${folder.subtitle} · ${folder.groupCount} categorie",
                            icon = folderIcon(folder.id),
                            onClick = {
                                if (folder.id == "attivita") playbackViewModel.startDiscoveryMix("attivita", "workout")
                                else viewModel.openFolder(folder.id)
                            },
                        )
                    }
                }
            }

            selectedGroupId == null -> {
                LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                    if (selectedFolderId==DiscoverViewModel.AUTOMATIC_FOLDER_ID) item {
                        Text(automaticSource,style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(bottom=8.dp))
                        AdvancedPlaylistSearch(busy = automaticPlaylistLoading, onStart = { target ->
                            when (target.kind) {
                                app.lyra.music.youtube.repository.PlaylistSearchKind.ARTIST -> playbackViewModel.startAutomaticArtist(target.title)
                                app.lyra.music.youtube.repository.PlaylistSearchKind.ALBUM -> playbackViewModel.startAutomaticAlbum(target.id, target.title)
                            }
                        })
                    }

                    item {
                        Text(
                            text = selectedFolder?.subtitle.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        val automaticFolder = selectedFolderId == DiscoverViewModel.AUTOMATIC_FOLDER_ID
                        val forYouFolder = selectedFolderId == DiscoverViewModel.FOR_YOU_FOLDER_ID
                        val subtitle = if (automaticFolder) {
                            AutomaticPlaylistCatalog.preset(group.id)?.subtitle
                                ?: "Avvia subito una playlist continua"
                        } else if (forYouFolder) {
                            PersonalizedMixEngine.summaries().firstOrNull { it.id == group.id }?.subtitle
                                ?: "Mix personale Lyra"
                        } else if (DiscoveryDirectMixRepository.isDirectGroup(selectedFolderId.orEmpty(), group.id)) {
                            "Tocca per ascoltare il mix scelto da Lyra"
                        } else {
                            group.playlistCount?.let { count ->
                                if (count == 1) "1 playlist" else "$count playlist"
                            } ?: "Apri per caricare"
                        }
                        DiscoverCard(
                            title = group.title,
                            subtitle = subtitle,
                            icon = if (automaticFolder || forYouFolder) Icons.Rounded.AutoAwesome else Icons.AutoMirrored.Rounded.QueueMusic,
                            onClick = {
                                when {
                                    automaticFolder -> playbackViewModel.startAutomaticPlaylist(group.id)
                                    forYouFolder -> playbackViewModel.startPersonalizedMix(group.id)
                                    DiscoveryDirectMixRepository.isDirectGroup(selectedFolderId.orEmpty(), group.id) ->
                                        playbackViewModel.startDiscoveryMix(selectedFolderId.orEmpty(), group.id)
                                    else -> viewModel.openGroup(group.id)
                                }
                            },
                        )
                    }
                }
            }

            else -> {
                if (isLoading && selectedGroup == null) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = 28.dp),
                    )
                }

                selectedGroup?.let { group ->
                    LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                        if (group.playlists.isEmpty()) {
                            item {
                                Text(
                                    text = "Nessuna playlist disponibile al momento",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(12.dp),
                                )
                            }
                        } else {
                            item {
                                Text(
                                    text = if (group.playlists.size == 1) {
                                        "1 playlist disponibile"
                                    } else {
                                        "${group.playlists.size} playlist disponibili"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                            }
                            items(
                                items = group.playlists,
                                key = { playlist -> "${selectedFolderId}-${group.id}-${playlist.id}" },
                            ) { playlist ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    ),
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { onPlaylistClick(playlist.id) },
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
                                                modifier = Modifier.size(58.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
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
                                                )
                                            }
                                        }
                                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

private fun folderIcon(folderId: String): ImageVector = when (folderId) {
    DiscoverViewModel.AUTOMATIC_FOLDER_ID -> Icons.Rounded.AutoAwesome
    "novita" -> Icons.Rounded.NewReleases
    "mood" -> Icons.Rounded.Mood
    "attivita" -> Icons.Rounded.FitnessCenter
    "decenni" -> Icons.Rounded.History
    "nazioni" -> Icons.Rounded.Public
    else -> Icons.AutoMirrored.Rounded.QueueMusic
}
