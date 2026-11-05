package app.lyra.music.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.playback.PlaybackViewModel
import app.lyra.music.playback.AutomaticPlaylistManager
import app.lyra.music.personalization.PersonalizedMixEngine
import app.lyra.music.youtube.repository.PlaylistSearchKind

@Composable
fun DiscoverScreen(
    playbackViewModel: PlaybackViewModel,
    onBack: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPlaylistClick: (String) -> Unit,
) {
    var automatic by rememberSaveable { mutableStateOf(false) }
    val loading by playbackViewModel.automaticPlaylistLoading.collectAsStateWithLifecycle()
    val error by playbackViewModel.automaticPlaylistError.collectAsStateWithLifecycle()
    val source by AutomaticPlaylistManager.source.collectAsStateWithLifecycle()
    BackHandler(enabled = automatic) { automatic = false }
    Column(Modifier.fillMaxSize().padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (automatic) automatic = false else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Text(if (automatic) "Playlist Automatica" else "Scopri",
                style = MaterialTheme.typography.headlineSmall)
        }
        Text("Se il server non è raggiungibile, Lyra usa la ricerca locale: la precisione delle proposte non è garantita.",
            style = MaterialTheme.typography.bodySmall)
        Text(source, style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        LazyColumn {
            if (automatic) {
                item {
                    AdvancedPlaylistSearch(busy = loading) { target ->
                        when (target.kind) {
                            PlaylistSearchKind.ARTIST -> playbackViewModel.startAutomaticArtist(target.title)
                            PlaylistSearchKind.ALBUM -> playbackViewModel.startAutomaticAlbum(target.id, target.title)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Scegli un genere", style = MaterialTheme.typography.titleMedium)
                }
                items(listOf("pop" to "Pop", "rock" to "Rock", "house" to "House", "techno" to "Techno")) { (id, title) ->
                    DiscoverCard(title, "Avvia la playlist $title", Icons.Rounded.AutoAwesome) {
                        if (!loading) playbackViewModel.startAutomaticPlaylist(id)
                    }
                }
            } else {
                item {
                    DiscoverCard("Playlist Automatica", "Pop, Rock, House, Techno", Icons.Rounded.AutoAwesome) {
                        automatic = true
                    }
                }
                item {
                    DiscoverCard("Mix per me", "Ascolti completi, skip, gradimento e brani preferiti", Icons.Rounded.AutoAwesome) {
                        if (!loading) playbackViewModel.startPersonalizedMix(PersonalizedMixEngine.DAILY_ID)
                    }
                }
                item {
                    DiscoverCard("Novità", "Brani con anno di uscita corrente nei metadati", Icons.Rounded.NewReleases) {
                        if (!loading) playbackViewModel.startDiscoveryMix("novita", "nuove-uscite")
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
