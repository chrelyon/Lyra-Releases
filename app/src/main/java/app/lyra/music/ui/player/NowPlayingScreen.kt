package app.lyra.music.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.lyra.music.playback.AutomaticPlaylistManager
import app.lyra.music.genre.LyraServerMonitor
import app.lyra.music.playback.PlaybackQueueUiState
import app.lyra.music.playback.PlaybackRepeatMode
import app.lyra.music.playback.PlaybackUiState
import app.lyra.music.ui.common.YouTubeLikeButton
import app.lyra.music.ui.common.PersonalizationFeedbackMenu
import kotlin.math.roundToLong

/** Schermata completa del brano corrente. */
@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    queueState: PlaybackQueueUiState,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onNext: () -> Unit,
    onOpenQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(state.hasMediaItem) {
        if (!state.hasMediaItem) onBack()
    }

    var isSeeking by remember { mutableStateOf(false) }
    var seekFraction by remember { mutableFloatStateOf(0f) }
    val automaticSource by AutomaticPlaylistManager.source.collectAsStateWithLifecycle()
    val automaticNotice by AutomaticPlaylistManager.notice.collectAsStateWithLifecycle()
    val serverStatus by LyraServerMonitor.status.collectAsStateWithLifecycle()
    val isAutomaticQueue = AutomaticPlaylistManager.isActive() &&
        AutomaticPlaylistManager.currentTitle() == queueState.title
    val playerWarning = if (!isAutomaticQueue) null else when {
        automaticNotice != null -> automaticNotice
        serverStatus == LyraServerMonitor.Status.OFFLINE &&
            automaticSource.startsWith("Server") ->
            "Server Lyra non raggiungibile: la playlist automatica potrebbe esaurirsi."
        queueState.songs.size == 1 ->
            "Playlist incompleta: un solo brano disponibile. Premi Next per cercarne altri."
        else -> null
    }

    val durationMs = state.durationMs.coerceAtLeast(0L)
    val actualFraction = if (durationMs > 0L) {
        (state.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderFraction = if (isSeeking) seekFraction else actualFraction
    val displayedPositionMs = if (isSeeking && durationMs > 0L) {
        (seekFraction * durationMs).roundToLong()
    } else {
        state.positionMs
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Text(
                text = "In riproduzione",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))

        SongArtwork(
            artworkUrl = state.artworkUrl,
            contentDescription = state.title.takeIf { it.isNotBlank() },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                .widthIn(max = 340.dp)
                .aspectRatio(1f),
        )

        Spacer(Modifier.height(22.dp))

        Text(
            text = state.title.ifBlank { "Brano in riproduzione" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.artist.isNotBlank()) {
            Text(
                text = state.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        state.contextLabel.ifBlank { queueState.title }.takeIf { it.isNotBlank() }?.let { contextLabel ->
            Text(
                text = contextLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            )
        }

        if (playerWarning != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(
                    text = playerWarning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        queueState.currentSong?.takeIf { !it.isLocal }?.let { song ->
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                YouTubeLikeButton(song = song)
                PersonalizationFeedbackMenu(song = song)
            }
        }

        Spacer(Modifier.height(12.dp))

        Slider(
            value = sliderFraction,
            onValueChange = { value ->
                isSeeking = true
                seekFraction = value.coerceIn(0f, 1f)
            },
            onValueChangeFinished = {
                if (durationMs > 0L) {
                    onSeekTo((seekFraction * durationMs).roundToLong())
                }
                isSeeking = false
            },
            enabled = durationMs > 0L,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDuration(displayedPositionMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDuration(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.isBuffering) {
            Text(
                text = "Caricamento...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        if (queueState.hasQueue) {
            Text(
                text = "${queueState.currentIndex + 1} / ${queueState.songs.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onToggleShuffle,
                    enabled = queueState.songs.size > 1 && !queueState.isChangingTrack,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = if (queueState.shuffleEnabled) "Disattiva shuffle" else "Attiva shuffle",
                        tint = if (queueState.shuffleEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }

                IconButton(
                    onClick = onOpenQueue,
                    enabled = !queueState.isChangingTrack,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Apri coda")
                }

                IconButton(
                    onClick = onCycleRepeat,
                    enabled = !queueState.isChangingTrack,
                ) {
                    val repeatIcon = if (queueState.repeatMode == PlaybackRepeatMode.ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    }
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = when (queueState.repeatMode) {
                            PlaybackRepeatMode.OFF -> "Ripetizione disattivata"
                            PlaybackRepeatMode.ALL -> "Ripeti tutti"
                            PlaybackRepeatMode.ONE -> "Ripeti uno"
                        },
                        tint = if (queueState.repeatMode == PlaybackRepeatMode.OFF) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrevious,
                enabled = queueState.hasPrevious && !queueState.isChangingTrack,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipPrevious,
                    contentDescription = "Brano precedente",
                    modifier = Modifier.size(34.dp),
                )
            }

            FilledIconButton(
                onClick = if (state.isPlaying) onPause else onResume,
                enabled = !queueState.isChangingTrack,
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pausa" else "Riproduci",
                    modifier = Modifier.size(38.dp),
                )
            }

            IconButton(
                onClick = onNext,
                enabled = (queueState.hasNext || isAutomaticQueue) && !queueState.isChangingTrack,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.SkipNext,
                    contentDescription = "Brano successivo",
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        IconButton(
            onClick = onStop,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(
                imageVector = Icons.Rounded.Stop,
                contentDescription = "Ferma riproduzione",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
