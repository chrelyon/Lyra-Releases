package app.lyra.music.ui.analytics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.analytics.AnalyticsDay
import app.lyra.music.analytics.AnalyticsOutcome
import app.lyra.music.analytics.AnalyticsRow
import app.lyra.music.analytics.ListeningAnalytics
import app.lyra.music.analytics.ListeningAnalyticsRepository
import app.lyra.music.genre.GenreClassifierSettings
import app.lyra.music.cloud.CloudProfileSyncSettings
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.playback.ListeningProfileStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ChartChoice(val title: String, val description: String) {
    ARTISTS("Artisti più apprezzati", "Affinità ricavata dal profilo complessivo"),
    TRACKS("Canzoni preferite", "Brani con segnali di ascolto più positivi"),
    HISTORY("Andamento degli ascolti", "Avvii dei brani e minuti realmente ascoltati"),
    OUTCOME("Completamenti e skip", "Come si concludono gli ascolti"),
    CONTEXTS("Contesti musicali", "Playlist e modalità da cui partono più ascolti"),
}

@Composable
fun ListeningAnalyticsScreen(onBack: () -> Unit) {
    val authorized by GenreClassifierSettings.authorized.collectAsStateWithLifecycle()
    if (!authorized) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro") }
            Text("Verifica il token Telegram nelle Impostazioni per aprire le statistiche.")
        }
        return
    }
    // La lettura locale è reattiva; i dati server Lyra sono invece uno snapshot remoto esplicito.
    val localTracks by ListeningProfileStore.stats.collectAsStateWithLifecycle()
    val localEvents by ListeningEventStore.events.collectAsStateWithLifecycle()
    val cloudEnabled by CloudProfileSyncSettings.enabled.collectAsStateWithLifecycle()
    val cloudStatus by CloudProfileSync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var remote by remember { mutableStateOf<app.lyra.music.analytics.AnalyticsSnapshot?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(ChartChoice.ARTISTS) }
    var menuOpen by remember { mutableStateOf(false) }
    var periodDays by remember { mutableStateOf(30) }

    suspend fun refreshCloud() {
        loading = true
        error = null
        try {
            remote = ListeningAnalyticsRepository.cloudSnapshot()
        } catch (cause: Exception) {
            error = "server Lyra non disponibile: ${cause.message ?: "errore di connessione"}. Mostro i dati locali."
            remote = null
        } finally {
            loading = false
        }
    }

    LaunchedEffect(cloudEnabled) {
        if (cloudEnabled) refreshCloud() else remote = null
    }

    // Le StateFlow raccolte aggiornano la UI anche quando non si accede al cloud.
    val local = remember(localTracks, localEvents) { ListeningAnalyticsRepository.localSnapshot() }
    val snapshot = if (cloudEnabled) remote ?: local else local
    val dated = selected == ChartChoice.HISTORY || selected == ChartChoice.OUTCOME || selected == ChartChoice.CONTEXTS
    val events = if (dated) ListeningAnalytics.filteredEvents(snapshot.events, periodDays, System.currentTimeMillis()) else snapshot.events
    val artistRows = remember(snapshot.tracks) { ListeningAnalytics.artistRows(snapshot.tracks) }
    val trackRows = remember(snapshot.tracks) { ListeningAnalytics.trackRows(snapshot.tracks) }
    val contextRows = ListeningAnalytics.contexts(events)
    val outcomes = ListeningAnalytics.outcome(events)
    val days = ListeningAnalytics.daily(snapshot.events, periodDays, System.currentTimeMillis())

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Indietro")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("I tuoi gusti musicali", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Lyra Music Intelligence", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { if (cloudEnabled && !loading) scope.launch { refreshCloud() } }, enabled = cloudEnabled && !loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Aggiorna i dati server Lyra")
            }
        }

        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fonte: ${snapshot.source}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                if (remote != null && snapshot.updatedAtMs > 0L) {
                    val time = remember(snapshot.updatedAtMs) {
                        SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(snapshot.updatedAtMs))
                    }
                    Text("Backup aggiornato: $time", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (remote != null && cloudStatus.pendingEvents > 0) {
                    Text("${cloudStatus.pendingEvents} eventi locali non ancora sincronizzati: la copia server Lyra può essere meno recente.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (!cloudEnabled) Text("Per leggere direttamente server Lyra, attiva Backup musicale nelle Impostazioni.", style = MaterialTheme.typography.bodySmall)
                Text("${snapshot.tracks.size} brani nel profilo · ${snapshot.events.size} eventi disponibili", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Scegli il grafico", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selected.title, modifier = Modifier.weight(1f))
                Text("▾")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ChartChoice.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.title) }, onClick = { selected = item; menuOpen = false })
                }
            }
        }
        Text(selected.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (dated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                listOf(7, 30, 90).forEach { daysCount ->
                    FilterChip(
                        selected = periodDays == daysCount,
                        onClick = { periodDays = daysCount },
                        label = { Text("$daysCount giorni") },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                when (selected) {
                    ChartChoice.ARTISTS -> BarGraph(artistRows)
                    ChartChoice.TRACKS -> BarGraph(trackRows)
                    ChartChoice.CONTEXTS -> BarGraph(contextRows)
                    ChartChoice.HISTORY -> ListeningTimeline(days)
                    ChartChoice.OUTCOME -> OutcomeDonut(outcomes)
                }
            }
        }

        Text("Dati in dettaglio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                when (selected) {
                    ChartChoice.ARTISTS -> DetailRows(artistRows)
                    ChartChoice.TRACKS -> DetailRows(trackRows)
                    ChartChoice.CONTEXTS -> DetailRows(contextRows)
                    ChartChoice.HISTORY -> {
                        DetailRows(days.asReversed().filter { it.count > 0 || it.listenedMinutes > 0 }
                            .map { AnalyticsRow(it.label, it.count, "${it.listenedMinutes} minuti ascoltati") })
                    }
                    ChartChoice.OUTCOME -> DetailRows(listOf(
                        AnalyticsRow("Ascolti completati", outcomes.completed, "Brani completati o ascoltati per almeno l’85%"),
                        AnalyticsRow("Ascolti parziali", outcomes.partial, "Interruzioni non classificate come skip precoce"),
                        AnalyticsRow("Skip precoci", outcomes.earlySkip, "Skip manuali registrati come precoci"),
                    ))
                }
            }
        }
        if (selected == ChartChoice.ARTISTS || selected == ChartChoice.TRACKS) {
            Text("Affinità relativa ricavata dal profilo complessivo; non è una percentuale né un dato limitato agli ultimi giorni.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        } else {
            Text("Le statistiche temporali usano gli eventi disponibili; non rappresentano necessariamente tutta la cronologia se è stata ripulita o non sincronizzata.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
        }
        if (selected == ChartChoice.CONTEXTS) {
            Text("I contesti indicano le playlist o modalità d'ascolto, non il genere certificato delle singole canzoni.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun DetailRows(rows: List<AnalyticsRow>) {
    if (rows.isEmpty()) {
        Text("Nessun dato disponibile per questa vista.", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
        return
    }
    rows.forEachIndexed { index, item ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
            Text("${index + 1}.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.label, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Text(item.value.toString(), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun BarGraph(rows: List<AnalyticsRow>) {
    if (rows.isEmpty()) { EmptyGraph(); return }
    val color = MaterialTheme.colorScheme.primary
    val max = rows.maxOf { it.value }.coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        rows.take(8).forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall)
                Text(item.value.toString(), style = MaterialTheme.typography.labelSmall)
            }
            Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(modifier = Modifier.fillMaxWidth((item.value.toFloat() / max).coerceIn(0f, 1f))
                    .height(10.dp).clip(RoundedCornerShape(6.dp)).background(color))
            }
        }
    }
}

@Composable
private fun ListeningTimeline(days: List<AnalyticsDay>) {
    if (days.isEmpty() || days.all { it.count == 0 }) { EmptyGraph(); return }
    val purple = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outlineVariant
    val counts = days.map { it.count.toFloat() }
    val max = counts.maxOrNull()?.coerceAtLeast(1f) ?: 1f
    Canvas(modifier = Modifier.fillMaxWidth().height(185.dp)) {
        val inset = 8.dp.toPx()
        val width = size.width - inset * 2
        val height = size.height - inset * 2
        drawLine(axis, Offset(inset, inset + height), Offset(inset + width, inset + height), strokeWidth = 1.dp.toPx())
        val path = Path()
        counts.forEachIndexed { index, value ->
            val x = inset + index.toFloat() / (counts.size - 1).coerceAtLeast(1) * width
            val y = inset + height * (1f - value / max)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, purple, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
        if (counts.size <= 14) counts.forEachIndexed { index, value ->
            val x = inset + index.toFloat() / (counts.size - 1).coerceAtLeast(1) * width
            val y = inset + height * (1f - value / max)
            drawCircle(purple, radius = 3.dp.toPx(), center = Offset(x, y))
        }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(days.first().label, style = MaterialTheme.typography.labelSmall)
        Text("${days.sumOf { it.count }} avvii · ${days.sumOf { it.listenedMinutes }} min", style = MaterialTheme.typography.labelSmall)
        Text(days.last().label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun OutcomeDonut(outcome: AnalyticsOutcome) {
    if (outcome.total == 0) { EmptyGraph(); return }
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.error)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val values = listOf(outcome.completed, outcome.partial, outcome.earlySkip)
                var angle = -90f
                values.forEachIndexed { index, count ->
                    if (count > 0) {
                        val sweep = 360f * count / outcome.total.toFloat()
                        drawArc(colors[index], angle, sweep, false, style = Stroke(width = 26.dp.toPx(), cap = StrokeCap.Butt))
                        angle += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(outcome.total.toString(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("esiti registrati", style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.height(12.dp))
        listOf("Completati" to outcome.completed, "Parziali" to outcome.partial, "Skip precoci" to outcome.earlySkip)
            .forEachIndexed { index, pair ->
                Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[index]))
                    Text("  ${pair.first}: ${pair.second} (${pair.second * 100 / outcome.total}%)", style = MaterialTheme.typography.bodyMedium)
                }
            }
    }
}

@Composable
private fun EmptyGraph() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().height(140.dp)) {
        Text("Ancora nessun ascolto da rappresentare", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
