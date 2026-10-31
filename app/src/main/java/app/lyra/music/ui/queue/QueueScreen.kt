package app.lyra.music.ui.queue

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyra.music.playback.PlaybackQueueUiState
import app.lyra.music.ui.player.SongArtwork

/** Coda di riproduzione gestita da Lyra. */
@Composable
fun QueueScreen(
    queueState: PlaybackQueueUiState,
    onBack: () -> Unit,
    onPlayIndex: (Int) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onSaveAutomaticPlaylist: () -> Unit,
    notice: String? = null,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(queueState.songs.isEmpty()) {
        if (queueState.songs.isEmpty()) onBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Column(modifier = Modifier.padding(start = 4.dp)) {
                Text(
                    text = "Coda",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (queueState.title.isNotBlank()) {
                    Text(
                        text = queueState.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        val canSaveAutomatic = queueState.title.startsWith("Playlist Automatica") ||
            queueState.title.startsWith("Radio:")
        if (canSaveAutomatic) {
            OutlinedButton(
                onClick = onSaveAutomaticPlaylist,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text("Salva playlist automatica")
            }
        }
        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (queueState.isChangingTrack) {
            Text(
                text = "Caricamento brano...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else if (queueState.shuffleEnabled) {
            Text(
                text = "Shuffle attivo: il riordino manuale è temporaneamente disabilitato.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 6.dp),
        ) {
            itemsIndexed(
                items = queueState.songs,
                key = { index, song -> "${song.id}-$index" },
            ) { index, song ->
                val isCurrent = index == queueState.currentIndex

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCurrent) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.60f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
                        },
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable(
                            enabled = !queueState.isChangingTrack && !isCurrent,
                            onClick = { onPlayIndex(index) },
                        ),
                ) {
                    Column(modifier = Modifier.padding(9.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )

                            SongArtwork(
                                artworkUrl = song.thumbnailUrl,
                                contentDescription = song.title,
                                modifier = Modifier.size(50.dp),
                            )

                            Spacer(Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val subtitle = buildQueueSubtitle(
                                    artist = song.artist,
                                    durationSeconds = song.durationSeconds,
                                )
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }

                            if (isCurrent) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = "Brano corrente",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { onMoveUp(index) },
                                enabled = index > 0 &&
                                    !queueState.isChangingTrack &&
                                    !queueState.shuffleEnabled,
                            ) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Sposta su")
                            }
                            IconButton(
                                onClick = { onMoveDown(index) },
                                enabled = index < queueState.songs.lastIndex &&
                                    !queueState.isChangingTrack &&
                                    !queueState.shuffleEnabled,
                            ) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Sposta giù")
                            }
                            IconButton(
                                onClick = { onRemove(index) },
                                enabled = !queueState.isChangingTrack,
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Rimuovi dalla coda",
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(12.dp)) }
        }
    }
}

private fun buildQueueSubtitle(
    artist: String,
    durationSeconds: Long,
): String {
    val duration = durationSeconds
        .takeIf { it > 0L }
        ?.let { seconds -> "%d:%02d".format(seconds / 60L, seconds % 60L) }

    return listOfNotNull(
        artist.takeIf { it.isNotBlank() },
        duration,
    ).joinToString("  •  ")
}
