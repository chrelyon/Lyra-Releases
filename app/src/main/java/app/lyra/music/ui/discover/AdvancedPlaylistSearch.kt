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
import app.lyra.music.youtube.repository.PlaylistSearchKind
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
    Text("Cerca un artista o un album. Per il genere scegli una categoria qui sotto.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = state.kind == PlaylistSearchKind.ARTIST, onClick = { controller.kindChanged(PlaylistSearchKind.ARTIST) }, label = { Text("Artisti") }, enabled = !busy)
        FilterChip(selected = state.kind == PlaylistSearchKind.ALBUM, onClick = { controller.kindChanged(PlaylistSearchKind.ALBUM) }, label = { Text("Album") }, enabled = !busy)
    }
    OutlinedTextField(value = state.query, onValueChange = controller::queryChanged,
        label = { Text(if (state.kind == PlaylistSearchKind.ARTIST) "Nome artista" else "Titolo album o artista") },
        singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { controller.refresh(immediate = true) }))
    if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    state.error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { controller.refresh(immediate = true) }, enabled = !busy) { Text("Riprova") }
    }
    if (state.searched && !state.loading && state.error == null && state.selected == null && state.suggestions.isEmpty()) {
        Text("Nessun risultato. Prova un nome più completo.", style = MaterialTheme.typography.bodySmall)
    }
    for (target in state.suggestions) {
        Row(modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) { controller.select(target); focus.clearFocus() }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AsyncImage(model = target.thumbnail, contentDescription = null, modifier = Modifier.size(44.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(target.title, style = MaterialTheme.typography.bodyLarge)
                Text(target.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    state.selected?.let { target -> Text("Selezionato: ${target.title} · ${target.subtitle}", style = MaterialTheme.typography.bodySmall) }
    Button(onClick = { state.selected?.let { focus.clearFocus(); onStart(it) } }, enabled = state.selected != null && !busy,
        modifier = Modifier.padding(vertical = 8.dp)) {
        Text(if (state.kind == PlaylistSearchKind.ARTIST) "Crea playlist artista" else "Riproduci album")
    }
}
