package app.lyra.music.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.core.model.Song
import app.lyra.music.youtube.likes.YouTubeLikesManager

/** Cuore YouTube: non viene mostrato per i file locali. */
@Composable
fun YouTubeLikeButton(
    song: Song,
    modifier: Modifier = Modifier,
) {
    if (song.isLocal) return

    val ratings by YouTubeLikesManager.ratings.collectAsStateWithLifecycle()
    val busyIds by YouTubeLikesManager.busyIds.collectAsStateWithLifecycle()
    val liked = ratings[song.id] == true
    val busy = song.id in busyIds

    LaunchedEffect(song.id) {
        YouTubeLikesManager.refreshId(song.id)
    }

    IconButton(
        onClick = { YouTubeLikesManager.toggle(song) },
        enabled = !busy,
        modifier = modifier,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                imageVector = if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (liked) "Rimuovi dai brani piaciuti" else "Aggiungi ai brani piaciuti",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
