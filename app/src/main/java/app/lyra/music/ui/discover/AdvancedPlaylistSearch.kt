package app.lyra.music.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import app.lyra.music.youtube.repository.AdvancedPlaylistSearchRepository
import app.lyra.music.youtube.repository.PlaylistSearchTarget

class AdvancedPlaylistSearchViewModel : ViewModel() {
    val controller = PlaylistSearchController(viewModelScope, AdvancedPlaylistSearchRepository()::suggestions)
}

@Composable
fun AdvancedPlaylistSearch(
    busy: Boolean,
    onStart: (PlaylistSearchTarget) -> Unit,
    model: AdvancedPlaylistSearchViewModel = viewModel(),
) {
    val controller = model.controller
    val state by controller.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    DisposableEffect(controller) { onDispose { controller.clear() } }
    Text("Cerca avanzato", style = MaterialTheme.typography.titleMedium)
    Text("Scrivi un artista o un album e scegli il suggerimento: la musica parte subito.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(value = state.query, onValueChange = controller::queryChanged,
        label = { Text("Artista o album") },
        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { controller.refresh(immediate = true) }))
    if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    state.error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { controller.refresh(immediate = true) }, enabled = !busy) { Text("Riprova") }
    }
    if (state.searched && !state.loading && state.error == null && state.suggestions.isEmpty()) {
        Text("Nessun risultato. Prova un nome più completo.", style = MaterialTheme.typography.bodySmall)
    }
    for (target in state.suggestions) {
        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
            focus.clearFocus()
            controller.clear()
            onStart(target)
        }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(model = target.thumbnail, contentDescription = null, modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(target.title, style = MaterialTheme.typography.bodyLarge)
                Text(target.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
