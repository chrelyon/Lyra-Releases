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
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.playback.AutomaticPlaylistManager
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import app.lyra.music.youtube.repository.DiscoverMenu
import app.lyra.music.youtube.repository.PlaylistSearchKind

@Composable
fun DiscoverScreen(
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
    viewModel: DiscoverViewModel = viewModel(),
) {
    val selectedFolderId by viewModel.selectedFolderId.collectAsStateWithLifecycle()
    val automaticSource by AutomaticPlaylistManager.source.collectAsStateWithLifecycle()
    val loading by playbackViewModel.automaticPlaylistLoading.collectAsStateWithLifecycle()
    val error by playbackViewModel.automaticPlaylistError.collectAsStateWithLifecycle()
    val selected = selectedFolderId?.let(DiscoverMenu::entry)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (selected != null) viewModel.closeFolder() else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Text(
                text = selected?.title ?: "Scopri",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
        }

        error?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        }
        if (loading) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Lyra sta preparando la musica…")
            }
        }

        LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
            when (selected?.kind) {
                null -> items(DiscoverMenu.entries, key = { it.id }) { entry ->
                    DiscoverCard(
                        title = entry.title,
                        subtitle = entry.subtitle,
                        icon = entryIcon(entry.id),
                        onClick = {
                            when (entry.kind) {
                                DiscoverMenu.Kind.MIX -> playbackViewModel.startPersonalizedMix()
                                DiscoverMenu.Kind.DIRECT ->
                                    playbackViewModel.startDiscoveryMix(entry.id, requireNotNull(entry.groupId))
                                else -> viewModel.openFolder(entry.id)
                            }
                        },
                    )
                }

                DiscoverMenu.Kind.AUTOMATIC_FOLDER -> {
                    item {
                        Text(
                            text = automaticSource,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        AdvancedPlaylistSearch(busy = loading, onStart = { target ->
                            when (target.kind) {
                                PlaylistSearchKind.ARTIST -> playbackViewModel.startAutomaticArtist(target.title)
                                PlaylistSearchKind.ALBUM -> playbackViewModel.startAutomaticAlbum(target.id, target.title)
                            }
                        })
                    }
                    items(AutomaticPlaylistCatalog.menuPresets, key = { it.id }) { preset ->
                        DiscoverCard(
                            title = preset.title,
                            subtitle = preset.subtitle,
                            icon = Icons.Rounded.AutoAwesome,
                            onClick = { playbackViewModel.startAutomaticPlaylist(preset.id) },
                        )
                    }
                }

                DiscoverMenu.Kind.DECADES_FOLDER -> items(DiscoverMenu.decades, key = { it.id }) { decade ->
                    DiscoverCard(
                        title = decade.title,
                        subtitle = "Riproduci direttamente il mix Lyra",
                        icon = Icons.Rounded.History,
                        onClick = { playbackViewModel.startDiscoveryMix(DiscoverMenu.DECADES_ID, decade.id) },
                    )
                }

                else -> Unit
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

private fun entryIcon(entryId: String): ImageVector = when (entryId) {
    DiscoverMenu.AUTOMATIC_ID -> Icons.Rounded.AutoAwesome
    DiscoverMenu.MIX_ID -> Icons.Rounded.Favorite
    "novita" -> Icons.Rounded.NewReleases
    "italiane" -> Icons.Rounded.Public
    DiscoverMenu.DECADES_ID -> Icons.Rounded.History
    else -> Icons.Rounded.AutoAwesome
}
