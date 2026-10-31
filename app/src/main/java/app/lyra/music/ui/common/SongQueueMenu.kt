package app.lyra.music.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
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

@Composable
fun SongQueueMenu(
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.Rounded.MoreVert, contentDescription = "Azioni coda")
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        DropdownMenuItem(
            text = { Text("Riproduci dopo") },
            onClick = {
                expanded = false
                onPlayNext()
            },
        )
        DropdownMenuItem(
            text = { Text("Aggiungi alla coda") },
            onClick = {
                expanded = false
                onAddToQueue()
            },
        )
    }
}
