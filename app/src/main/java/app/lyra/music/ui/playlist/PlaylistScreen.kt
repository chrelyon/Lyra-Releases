package app.lyra.music.ui.playlist

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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.ui.common.YouTubeLikeButton
import app.lyra.music.ui.common.SongQueueMenu
import app.lyra.music.youtube.likes.YouTubeLikesManager
import app.lyra.music.ui.player.SongArtwork

@Composable
fun PlaylistScreen(
    playlistId: String,
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
    viewModel: PlaylistViewModel = viewModel(),
) {
    val title by viewModel.title.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    LaunchedEffect(songs) {
        YouTubeLikesManager.refresh(songs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp)
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
                    text = title.ifBlank { "Playlist YouTube" },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (songs.isNotEmpty()) {
                    Text(
                        text = if (songs.size == 1) "1 brano" else "${songs.size} brani",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (songs.isNotEmpty()) {
            Button(
                onClick = {
                    playbackViewModel.playQueueShuffled(
                        songs = songs,
                        queueTitle = title.ifBlank { "Playlist YouTube" },
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Riproduci casualmente")
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 28.dp)
            )
        }

        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 20.dp, start = 8.dp, end = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.padding(top = 14.dp),
        ) {
            itemsIndexed(
                items = songs,
                key = { index, song -> "${song.id}-$index" },
            ) { index, song ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable {
                            playbackViewModel.playQueue(
                                songs = songs,
                                startIndex = index,
                                queueTitle = title.ifBlank { "Playlist YouTube" },
                            )
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SongArtwork(
                            artworkUrl = song.artworkUrlForPlayback,
                            contentDescription = song.title,
                            modifier = Modifier.size(58.dp),
                        )

                        Spacer(Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = song.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )

                            val subtitle = buildSongSubtitle(song.artist, song.durationSeconds)
                            if (subtitle.isNotBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }

                        SongQueueMenu(
                            onPlayNext = { playbackViewModel.playNext(song) },
                            onAddToQueue = { playbackViewModel.addToQueue(song) },
                        )
                        YouTubeLikeButton(song = song)

                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            item { Spacer(Modifier.size(16.dp)) }
        }
    }
}

private fun buildSongSubtitle(
    artist: String,
    durationSeconds: Long,
): String {
    val duration = durationSeconds
        .takeIf { it > 0L }
        ?.let(::formatSongDuration)

    return listOfNotNull(
        artist.takeIf { it.isNotBlank() },
        duration,
    ).joinToString("  •  ")
}

private fun formatSongDuration(durationSeconds: Long): String {
    val minutes = durationSeconds / 60L
    val seconds = durationSeconds % 60L
    return "%d:%02d".format(minutes, seconds)
}
