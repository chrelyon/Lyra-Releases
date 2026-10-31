package app.lyra.music.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.core.model.Song
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.playback.ListeningProfileStore

/** Feedback esplicito facoltativo per accelerare l'apprendimento della radio. */
@Composable
fun PersonalizationFeedbackMenu(song: Song) {
    val enabled by PersonalizationSettings.enabled.collectAsStateWithLifecycle()
    if (!enabled || song.isLocal) return

    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Rounded.Tune, contentDescription = "Personalizza consigli")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Più musica come questa") },
            onClick = {
                expanded = false
                ListeningProfileStore.boostSimilar(song)
            },
        )
        DropdownMenuItem(
            text = { Text("Meno di questo artista") },
            onClick = {
                expanded = false
                ListeningProfileStore.reduceArtist(song)
            },
        )
        DropdownMenuItem(
            text = { Text("Non riprodurre più questo brano") },
            onClick = {
                expanded = false
                ListeningProfileStore.blockSong(song)
            },
        )
    }
}
