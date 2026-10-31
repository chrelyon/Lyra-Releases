package app.lyra.music.ui.home

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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.playback.ListeningHistoryStore
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.ui.player.SongArtwork

/**
 * Elenco dei brani ascoltati di recente.
 *
 * Una stessa canzone compare una sola volta, mantenendo l'occorrenza piu' recente.
 * Ogni tap riproduce una coda ordinata di massimo 100 brani unici della
 * cronologia, a partire da quello selezionato fino all'ultimo.
 */
@Composable
fun ListeningHistoryScreen(
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
) {
    val history by ListeningHistoryStore.entries.collectAsStateWithLifecycle()
    val uniqueEntries = remember(history) {
        history.distinctBy { it.song.id }.take(100)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Ascoltati di recente",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${uniqueEntries.size} brani unici",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (uniqueEntries.isEmpty()) {
            Text(
                text = "Non ci sono ancora brani nella cronologia.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(
                    items = uniqueEntries,
                    key = { it.song.id },
                ) { entry ->
                    val song = entry.song
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
                                    songs = uniqueEntries.map { it.song },
                                    startIndex = uniqueEntries.indexOf(entry),
                                    queueTitle = "Ascoltati di recente",
                                    forceSequential = true,
                                )
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SongArtwork(
                                artworkUrl = song.thumbnailUrl,
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
                                Text(
                                    text = song.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "Riproduci in sequenza fino all’ultimo brano",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Riproduci")
                        }
                    }
                }
            }
        }
    }
}
