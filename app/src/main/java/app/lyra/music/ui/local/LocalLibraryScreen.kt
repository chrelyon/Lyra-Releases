package app.lyra.music.ui.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.lyra.music.core.model.Song
import app.lyra.music.local.LocalMediaAccess
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.ui.player.SongArtwork

private enum class LocalMode { SONGS, ALBUMS, ARTISTS }

private data class LocalCollection(
    val key: String,
    val title: String,
    val songs: List<Song>,
    val artworkUrl: String? = null,
)

@Composable
fun LocalLibraryScreen(
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
    viewModel: LocalLibraryViewModel = viewModel(),
) {
    val context = LocalContext.current
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var hasPermission by remember { mutableStateOf(LocalMediaAccess.hasPermission(context)) }
    var mode by remember { mutableStateOf(LocalMode.SONGS) }
    var collectionTitle by remember { mutableStateOf<String?>(null) }
    var collectionSongs by remember { mutableStateOf<List<Song>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) viewModel.load()
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.load()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = {
                    if (collectionTitle != null) {
                        collectionTitle = null
                        collectionSongs = emptyList()
                    } else {
                        onBack()
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Column {
                Text(
                    text = collectionTitle ?: "Dispositivo",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                if (collectionTitle != null) {
                    Text(
                        text = "${collectionSongs.size} brani",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!hasPermission) {
            Text(
                text = "Consenti a Lyra di leggere i file audio indicizzati da Android. L'accesso è in sola lettura.",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 22.dp),
            )
            Button(
                onClick = { permissionLauncher.launch(LocalMediaAccess.requiredPermission()) },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Consenti accesso alla musica")
            }
            return@Column
        }

        if (collectionTitle == null) {
            Row(modifier = Modifier.padding(top = 12.dp)) {
                FilterChip(
                    selected = mode == LocalMode.SONGS,
                    onClick = { mode = LocalMode.SONGS },
                    label = { Text("Brani") },
                    leadingIcon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == LocalMode.ALBUMS,
                    onClick = { mode = LocalMode.ALBUMS },
                    label = { Text("Album") },
                    leadingIcon = { Icon(Icons.Rounded.Album, contentDescription = null) },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = mode == LocalMode.ARTISTS,
                    onClick = { mode = LocalMode.ARTISTS },
                    label = { Text("Artisti") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp),
            )
        }

        errorMessage?.let { message ->
            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp))
        }

        val visibleSongs = collectionTitle?.let { collectionSongs } ?: songs
        if (collectionTitle != null || mode == LocalMode.SONGS) {
            if (visibleSongs.isNotEmpty()) {
                Button(
                    onClick = {
                        playbackViewModel.playQueueShuffled(
                            songs = visibleSongs,
                            queueTitle = collectionTitle ?: "Dispositivo",
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Riproduci casualmente")
                }
            }
            SongList(
                songs = visibleSongs,
                queueTitle = collectionTitle ?: "Dispositivo",
                playbackViewModel = playbackViewModel,
            )
        } else if (collectionTitle == null) {
            val collections = if (mode == LocalMode.ALBUMS) {
                songs
                    .groupBy { song ->
                        song.albumId?.let { "id:$it" }
                            ?: "name:${song.album.orEmpty()}|artist:${song.artist}"
                    }
                    .map { (key, albumSongs) ->
                        LocalCollection(
                            key = key,
                            title = albumSongs.firstOrNull()?.album.orEmpty().ifBlank { "Album sconosciuto" },
                            songs = albumSongs,
                            artworkUrl = albumSongs.firstNotNullOfOrNull { it.thumbnailUrl },
                        )
                    }
            } else {
                songs
                    .groupBy { it.artist.ifBlank { "Artista sconosciuto" } }
                    .map { (artist, artistSongs) ->
                        LocalCollection(
                            key = "artist:$artist",
                            title = artist,
                            songs = artistSongs,
                        )
                    }
            }.sortedBy { it.title.lowercase() }

            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(collections, key = { it.key }) { collection ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                collectionTitle = collection.title
                                collectionSongs = collection.songs
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (mode == LocalMode.ALBUMS) {
                                SongArtwork(
                                    artworkUrl = collection.artworkUrl,
                                    contentDescription = collection.title,
                                    modifier = Modifier.size(54.dp),
                                )
                            } else {
                                Icon(
                                    Icons.Rounded.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    collection.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "${collection.songs.size} brani",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    queueTitle: String,
    playbackViewModel: PlaybackViewModel,
) {
    LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
        itemsIndexed(songs, key = { index, song -> "${song.id}-$index" }) { index, song ->
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
                            queueTitle = queueTitle,
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
                            song.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOf(song.artist, song.album.orEmpty())
                                .filter { it.isNotBlank() }
                                .joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                }
            }
        }
    }
}
