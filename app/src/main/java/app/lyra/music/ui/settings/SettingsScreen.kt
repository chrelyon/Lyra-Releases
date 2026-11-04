package app.lyra.music.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lyra.music.BuildConfig
import app.lyra.music.genre.GenreClassifierClient
import app.lyra.music.genre.GenreClassifierSettings
import app.lyra.music.genre.LyraServerMonitor
import app.lyra.music.audio.AudioSettings
import app.lyra.music.audio.LocalLoudnessStore
import app.lyra.music.diagnostics.DiagnosticsReporter
import app.lyra.music.diagnostics.DiagnosticsSettings
import app.lyra.music.playback.PlaybackResumeSettings
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.cloud.CloudProfileSyncSettings
import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.update.GitHubUpdateChecker
import app.lyra.music.update.UpdateInstaller
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.repository.DiscoverySettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenListeningAnalytics: () -> Unit,
    availableUpdate: GitHubUpdateChecker.UpdateInfo? = null,
    focusUpdates: Boolean = false,
    onFocusUpdatesHandled: () -> Unit = {},
    onUpdateResult: (GitHubUpdateChecker.Result) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val updateButtonFocusRequester = remember { FocusRequester() }
    val authorizationState by YouTubeAuthorizationManager.state.collectAsStateWithLifecycle()
    val normalizationEnabled by AudioSettings.normalizationEnabled.collectAsStateWithLifecycle()
    val streamingNormalizationEnabled by AudioSettings.streamingNormalizationEnabled.collectAsStateWithLifecycle()
    val localNormalizationEnabled by AudioSettings.localNormalizationEnabled.collectAsStateWithLifecycle()
    val preferReplayGain by AudioSettings.preferReplayGain.collectAsStateWithLifecycle()
    val r128AnalysisEnabled by AudioSettings.r128AnalysisEnabled.collectAsStateWithLifecycle()
    val truePeakProtection by AudioSettings.truePeakProtection.collectAsStateWithLifecycle()
    val normalizationMode by AudioSettings.normalizationMode.collectAsStateWithLifecycle()
    val targetLufs by AudioSettings.targetLufs.collectAsStateWithLifecycle()
    val maxGainDb by AudioSettings.maxGainDb.collectAsStateWithLifecycle()
    val maxAttenuationDb by AudioSettings.maxAttenuationDb.collectAsStateWithLifecycle()
    val backgroundScanEnabled by AudioSettings.backgroundScanEnabled.collectAsStateWithLifecycle()
    val scanOnlyWhileCharging by AudioSettings.scanOnlyWhileCharging.collectAsStateWithLifecycle()
    val localScanStatus by LocalLoudnessStore.status.collectAsStateWithLifecycle()
    val streamingQuality by AudioSettings.streamingQuality.collectAsStateWithLifecycle()
    val gaplessEnabled by AudioSettings.gaplessEnabled.collectAsStateWithLifecycle()
    val crossfadeEnabled by AudioSettings.crossfadeEnabled.collectAsStateWithLifecycle()
    val crossfadeDurationMs by AudioSettings.crossfadeDurationMs.collectAsStateWithLifecycle()
    val preloadAudioEnabled by AudioSettings.preloadAudioEnabled.collectAsStateWithLifecycle()
    val prefetchSourcesEnabled by AudioSettings.prefetchSourcesEnabled.collectAsStateWithLifecycle()
    val adaptiveBufferingEnabled by AudioSettings.adaptiveBufferingEnabled.collectAsStateWithLifecycle()
    val restoreLastSession by PlaybackResumeSettings.restoreLastSession.collectAsStateWithLifecycle()
    val discoveryRegion by DiscoverySettings.regionMode.collectAsStateWithLifecycle()
    val personalizationEnabled by PersonalizationSettings.enabled.collectAsStateWithLifecycle()
    val serverAlgorithmEnabled by PersonalizationSettings.useServerAlgorithm.collectAsStateWithLifecycle()
    val discoveryLevel by PersonalizationSettings.discoveryLevel.collectAsStateWithLifecycle()
    val useHistory by PersonalizationSettings.useHistory.collectAsStateWithLifecycle()
    val useLikes by PersonalizationSettings.useLikes.collectAsStateWithLifecycle()
    val learnFromSkips by PersonalizationSettings.learnFromSkips.collectAsStateWithLifecycle()
    val avoidRepeats by PersonalizationSettings.avoidRepeats.collectAsStateWithLifecycle()
    val continueSimilar by PersonalizationSettings.continueSimilar.collectAsStateWithLifecycle()
    val genreEnabled by GenreClassifierSettings.enabled.collectAsStateWithLifecycle()
    val serverConnectionEnabled by GenreClassifierSettings.connectionEnabled.collectAsStateWithLifecycle()
    val serverStatus by LyraServerMonitor.status.collectAsStateWithLifecycle()
    val genreTokenSaved by GenreClassifierSettings.hasToken.collectAsStateWithLifecycle()
    var showDisableServerConfirmation by remember { mutableStateOf(false) }
    var genreTokenInput by remember { mutableStateOf("") }
    var genreConnectionMessage by remember { mutableStateOf<String?>(null) }
    var genreChecking by remember { mutableStateOf(false) }
    val cloudSyncEnabled by CloudProfileSyncSettings.enabled.collectAsStateWithLifecycle()
    val ubuntuSyncUrl by CloudProfileSyncSettings.ubuntuUrl.collectAsStateWithLifecycle()
    val ubuntuReady by CloudProfileSyncSettings.ready.collectAsStateWithLifecycle()
    val cloudStatus by CloudProfileSync.status.collectAsStateWithLifecycle()

    var loggingEnabled by remember {
        mutableStateOf(DiagnosticsSettings.isLoggingEnabled(context))
    }
    var showLogoutConfirmation by remember { mutableStateOf(false) }
    var showResetProfileConfirmation by remember { mutableStateOf(false) }
    var showDeleteCloudConfirmation by remember { mutableStateOf(false) }
    var showRecoveryCodeDialog by remember { mutableStateOf(false) }
    var showRestoreCloudDialog by remember { mutableStateOf(false) }
    var recoveryCodeInput by remember { mutableStateOf("") }
    var cloudUiMessage by remember { mutableStateOf<String?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateInstallMessage by remember { mutableStateOf<String?>(null) }
    var updateResult by remember(availableUpdate) {
        mutableStateOf<GitHubUpdateChecker.Result?>(
            availableUpdate?.let { GitHubUpdateChecker.Result.Available(it) }
        )
    }

    LaunchedEffect(focusUpdates, updateResult) {
        if (focusUpdates) {
            // Attende il primo layout completo: Aggiornamenti e' l'ultima sezione,
            // quindi maxValue porta direttamente alla card e al pulsante update.
            delay(180)
            scrollState.animateScrollTo(scrollState.maxValue)
            delay(120)
            runCatching { updateButtonFocusRequester.requestFocus() }
            onFocusUpdatesHandled()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 24.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Indietro",
                )
            }
            Text(
                text = "Impostazioni",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        SettingsSectionTitle("Diagnostica")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Abilita log diagnostici",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (loggingEnabled) {
                            "Attivi. In caso di crash Lyra proporrà l'invio del report a ${DiagnosticsReporter.SUPPORT_EMAIL}."
                        } else {
                            "Disattivati. Nessun log diagnostico viene salvato sul dispositivo."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Switch(
                    checked = loggingEnabled,
                    onCheckedChange = { enabled ->
                        DiagnosticsSettings.setLoggingEnabled(context, enabled)
                        loggingEnabled = enabled
                    },
                )
            }
        }

        Text(
            text = "I log restano nella memoria privata dell'app. Token Google e URL audio firmati vengono esclusi o oscurati. Disattivando l'opzione i log locali vengono cancellati.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp),
        )

        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Versione Lyra",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = "Motore streaming InnerTubeX ${BuildConfig.INNERTUBEX_VERSION}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = "Gli aggiornamenti del motore vengono verificati lato sviluppo e richiedono una nuova build Lyra.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }


        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Riproduzione")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ripristina ultima sessione",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Conserva playlist, brano, posizione, Shuffle e Repeat. Alla riapertura Lyra riparte dallo stesso punto in pausa, anche con Android Auto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = restoreLastSession,
                        onCheckedChange = PlaybackResumeSettings::setRestoreLastSession,
                    )
                }

                Text(
                    text = "La posizione viene salvata anche durante la riproduzione e quando Android Auto si scollega. Disattivando l'opzione la sessione corrente non viene ripristinata al successivo avvio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Personalizzazione")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Personalizzazione musicale",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Adatta radio e continuazioni a ciò che ascolti, completi, salti e aggiungi ai Preferiti.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = personalizationEnabled,
                        onCheckedChange = PersonalizationSettings::setEnabled,
                    )
                }

                SettingToggleRow(
                    title = "Algoritmo playlist del server",
                    subtitle = if (serverAlgorithmEnabled) {
                        "Attivo: il server propone e verifica i brani delle nuove playlist automatiche. " +
                            "Se non è raggiungibile, Lyra segnala l'uso del motore locale."
                    } else {
                        "Disattivo: le playlist automatiche vengono costruite dallo smartphone. " +
                            "Cronologia, preferiti e apprendimento locale rimangono disponibili."
                    },
                    checked = serverAlgorithmEnabled,
                    onCheckedChange = PersonalizationSettings::setUseServerAlgorithm,
                )

                if (personalizationEnabled) {
                    Text(
                        text = "Livello di scoperta",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                    )
                    PersonalizationSettings.DiscoveryLevel.entries.forEach { level ->
                        val (title, subtitle) = when (level) {
                            PersonalizationSettings.DiscoveryLevel.CONSERVATIVE ->
                                "Conservativo" to "Più musica già affine ai tuoi ascolti."
                            PersonalizationSettings.DiscoveryLevel.BALANCED ->
                                "Bilanciato" to "Equilibrio fra familiarità e scoperta, consigliato."
                            PersonalizationSettings.DiscoveryLevel.EXPLORATIVE ->
                                "Esplorativo" to "Introduce più artisti e brani nuovi."
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            RadioButton(
                                selected = discoveryLevel == level,
                                onClick = { PersonalizationSettings.setDiscoveryLevel(level) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    SettingToggleRow(
                        title = "Usa la cronologia",
                        subtitle = "Impara da frequenza e durata degli ascolti.",
                        checked = useHistory,
                        onCheckedChange = PersonalizationSettings::setUseHistory,
                    )
                    SettingToggleRow(
                        title = "Usa i Preferiti",
                        subtitle = "Il cuore è un segnale positivo forte.",
                        checked = useLikes,
                        onCheckedChange = PersonalizationSettings::setUseLikes,
                    )
                    SettingToggleRow(
                        title = "Impara dagli skip",
                        subtitle = "Gli skip molto precoci riducono la probabilità di riproporre brani simili.",
                        checked = learnFromSkips,
                        onCheckedChange = PersonalizationSettings::setLearnFromSkips,
                    )
                    SettingToggleRow(
                        title = "Evita ripetizioni frequenti",
                        subtitle = "Penalizza brani e artisti ascoltati troppo di recente.",
                        checked = avoidRepeats,
                        onCheckedChange = PersonalizationSettings::setAvoidRepeats,
                    )
                    OutlinedButton(
                        onClick = { showResetProfileConfirmation = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) {
                        Text("Reimposta profilo musicale")
                    }
                }

                SettingToggleRow(
                    title = "Continua con musica simile",
                    subtitle = "Alla fine di una coda YouTube crea automaticamente una continuazione. Se la personalizzazione è attiva, Lyra la riordina secondo i tuoi gusti.",
                    checked = continueSimilar,
                    onCheckedChange = PersonalizationSettings::setContinueSimilar,
                )
            }
        }

        // Fixed Funnel server; private token is never stored in the APK.
        Spacer(Modifier.height(14.dp))
        SettingsSectionTitle("Server Lyra e verifica dei generi")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Connessione al server Lyra", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Collegamento automatico e verifica ogni 5 minuti. I backup Ubuntu e il controllo dei generi vengono sospesi se disattivi la connessione.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = serverConnectionEnabled,
                        onCheckedChange = { requested ->
                            if (!requested) showDisableServerConfirmation = true
                            else GenreClassifierSettings.setConnectionEnabled(true)
                        },
                    )
                }
                Text(
                    "Server: ${GenreClassifierSettings.SERVER_URL}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    when {
                        !serverConnectionEnabled -> "Server disattivato: playlist senza verifica dei generi e backup remoto sospeso."
                        serverStatus == LyraServerMonitor.Status.ONLINE -> "Server raggiungibile."
                        serverStatus == LyraServerMonitor.Status.CHECKING -> "Verifica connessione in corso…"
                        else -> "Server non raggiungibile: le playlist potrebbero essere meno precise. La riproduzione continua."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (serverStatus == LyraServerMonitor.Status.ONLINE && serverConnectionEnabled)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    enabled = serverConnectionEnabled,
                    onClick = { LyraServerMonitor.refresh() },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Controlla connessione") }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Filtro intelligente dei generi", style = MaterialTheme.typography.titleMedium)
                        Text("Esclude soltanto brani già classificati incompatibili. Mai bloccare l’ascolto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = genreEnabled && serverConnectionEnabled,
                        enabled = serverConnectionEnabled && !genreChecking &&
                            (genreEnabled || GenreClassifierSettings.isConfigured()),
                        onCheckedChange = { active ->
                            if (!active) {
                                GenreClassifierSettings.setEnabled(false)
                                genreConnectionMessage = "Filtro musicale disattivato"
                            } else {
                                scope.launch {
                                    genreChecking = true
                                    val check = GenreClassifierClient.checkConnection()
                                    genreChecking = false
                                    if (check is GenreClassifierClient.ConnectionCheck.Ready) {
                                        GenreClassifierSettings.setEnabled(true)
                                        genreConnectionMessage = "Filtro attivo: House, Techno, Dance e Rock"
                                    } else {
                                        genreConnectionMessage = (check as GenreClassifierClient.ConnectionCheck.Failed).reason
                                    }
                                }
                            }
                        },
                    )
                }
                OutlinedTextField(
                    value = genreTokenInput,
                    onValueChange = { genreTokenInput = it; genreConnectionMessage = null },
                    label = { Text(if (genreTokenSaved) "Nuovo token API (opzionale)" else "Token API del classificatore") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = serverConnectionEnabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Text("Il token non è incorporato nell'APK: viene cifrato con Android Keystore.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    OutlinedButton(
                        enabled = serverConnectionEnabled,
                        onClick = {
                            if (GenreClassifierSettings.saveToken(genreTokenInput)) {
                                genreTokenInput = ""
                                genreConnectionMessage = "Token salvato. Puoi attivare il filtro musicale."
                                LyraServerMonitor.refresh()
                            } else {
                                genreConnectionMessage = "Token mancante o non valido (almeno 40 caratteri)."
                            }
                        },
                    ) { Text("Salva token") }
                    OutlinedButton(
                        enabled = serverConnectionEnabled && genreTokenSaved && !genreChecking,
                        onClick = {
                            scope.launch {
                                genreChecking = true
                                val check = GenreClassifierClient.checkConnection()
                                genreChecking = false
                                genreConnectionMessage = when (check) {
                                    GenreClassifierClient.ConnectionCheck.Ready -> "Connessione e autorizzazione verificate"
                                    is GenreClassifierClient.ConnectionCheck.Failed -> check.reason
                                }
                            }
                        },
                    ) { Text(if (genreChecking) "Controllo..." else "Verifica token") }
                }
                genreConnectionMessage?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 10.dp))
                }
                Text("Il server controlla i brani già presenti nella cache. Un brano senza classificazione viene comunque riprodotto.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp))
            }
        }

        if (showDisableServerConfirmation) {
            AlertDialog(
                onDismissRequest = { showDisableServerConfirmation = false },
                title = { Text("Disattivare il server Lyra?") },
                text = { Text("Le playlist potrebbero essere meno precise: non verranno verificati i generi musicali. Anche i backup su Ubuntu saranno sospesi finché non riattiverai il collegamento. La musica continuerà normalmente.") },
                confirmButton = {
                    TextButton(onClick = {
                        showDisableServerConfirmation = false
                        GenreClassifierSettings.setConnectionEnabled(false)
                    }) { Text("Disattiva") }
                },
                dismissButton = {
                    TextButton(onClick = { showDisableServerConfirmation = false }) { Text("Annulla") }
                },
            )
        }

        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Backup musicale Ubuntu",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (CloudProfileSyncSettings.endpointConfigured) {
                                "Salva solo il profilo musicale Lyra: brani, artisti, ascolti, skip, Like/Dislike e segnali usati dall’algoritmo. Nessun nome, e-mail, UID Google, password, cookie o token OAuth viene salvato."
                            } else {
                                "Il server Ubuntu è preconfigurato nell’applicazione."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = cloudSyncEnabled,
                        enabled = CloudProfileSyncSettings.endpointConfigured,
                        onCheckedChange = CloudProfileSyncSettings::setEnabled,
                    )
                }

                Text(
                    when {
                        ubuntuSyncUrl.isBlank() -> "Server Ubuntu preconfigurato. La vecchia sincronizzazione non viene usata."
                        ubuntuReady -> "Profilo e backup su Ubuntu tramite Funnel. La vecchia sincronizzazione non viene usata."
                        else -> "Primo ripristino da Ubuntu in attesa. Il server deve contenere il profilo importato. Nessun invio ad altri server."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp),
                )

                if (cloudSyncEnabled) {
                    Text(
                        text = when {
                            cloudStatus.restoring -> "Ripristino del profilo musicale in corso…"
                            cloudStatus.syncing -> "Backup musicale in corso…"
                            cloudStatus.error != null -> "Ultimo tentativo: ${cloudStatus.error}"
                            cloudStatus.lastRestoreAtMs > 0L -> "Profilo musicale ripristinato."
                            cloudStatus.lastSuccessAtMs > 0L -> "Backup musicale aggiornato."
                            else -> "In attesa del primo backup musicale."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    cloudUiMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    OutlinedButton(
                        onClick = CloudProfileSync::syncNow,
                        enabled = !cloudStatus.syncing && !cloudStatus.restoring,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) { Text("Backup ora") }
                    OutlinedButton(
                        onClick = { showRecoveryCodeDialog = true },
                        enabled = !cloudStatus.restoring,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Codice di recupero") }
                    OutlinedButton(
                        onClick = {
                            recoveryCodeInput = ""
                            cloudUiMessage = null
                            showRestoreCloudDialog = true
                        },
                        enabled = !cloudStatus.syncing && !cloudStatus.restoring,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text("Ripristina da codice") }
                    TextButton(
                        onClick = { showDeleteCloudConfirmation = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Elimina backup su Ubuntu") }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Le mie statistiche")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BarChart, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("I tuoi gusti musicali", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold)
                        Text("Artisti, canzoni, andamento degli ascolti e skip. Grafici e dati dettagliati.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OutlinedButton(
                    onClick = onOpenListeningAnalytics,
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                ) { Text("Apri statistiche musicali") }
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Scopri")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Area musicale preferita",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Influenza le playlist proposte in Scopri senza modificare la tua libreria personale.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                DiscoverySettings.RegionMode.entries.forEach { mode ->
                    val (title, subtitle) = when (mode) {
                        DiscoverySettings.RegionMode.ITALY ->
                            "Italia" to "Dà priorità a playlist e scene italiane."
                        DiscoverySettings.RegionMode.ITALY_EUROPE ->
                            "Italia + Europa" to "Selezione italiana ed europea, consigliata."
                        DiscoverySettings.RegionMode.INTERNATIONAL ->
                            "Internazionale" to "Allarga Scopri al catalogo musicale globale."
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                    ) {
                        RadioButton(
                            selected = discoveryRegion == mode,
                            onClick = { DiscoverySettings.setRegionMode(mode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Audio")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Normalizzazione audio avanzata",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Uniforma il loudness senza modificare i file originali. Streaming e musica locale sono configurabili separatamente.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Switch(
                        checked = normalizationEnabled,
                        onCheckedChange = AudioSettings::setNormalizationEnabled,
                    )
                }

                AudioToggleRow(
                    title = "Streaming YouTube",
                    subtitle = "Usa i dati loudness forniti dallo stream quando disponibili.",
                    checked = streamingNormalizationEnabled,
                    enabled = normalizationEnabled,
                    onCheckedChange = AudioSettings::setStreamingNormalizationEnabled,
                )
                AudioToggleRow(
                    title = "Musica locale",
                    subtitle = "Applica ReplayGain o l'analisi R128 ai file nella cartella Music.",
                    checked = localNormalizationEnabled,
                    enabled = normalizationEnabled,
                    onCheckedChange = AudioSettings::setLocalNormalizationEnabled,
                )
                AudioToggleRow(
                    title = "Preferisci ReplayGain",
                    subtitle = "Se il decoder espone tag ReplayGain affidabili, usa il gain del file prima del calcolo LUFS.",
                    checked = preferReplayGain,
                    enabled = normalizationEnabled && localNormalizationEnabled,
                    onCheckedChange = AudioSettings::setPreferReplayGain,
                )
                AudioToggleRow(
                    title = "Analisi EBU R128",
                    subtitle = "Decodifica i file in background, calcola loudness integrato con K-weighting e gating e salva il risultato in cache.",
                    checked = r128AnalysisEnabled,
                    enabled = normalizationEnabled && localNormalizationEnabled,
                    onCheckedChange = AudioSettings::setR128AnalysisEnabled,
                )
                AudioToggleRow(
                    title = "Protezione True Peak",
                    subtitle = "Limita l'amplificazione mantenendo 1 dB di margine dai picchi stimati.",
                    checked = truePeakProtection,
                    enabled = normalizationEnabled && localNormalizationEnabled,
                    onCheckedChange = AudioSettings::setTruePeakProtection,
                )

                Text(
                    text = "Modalità normalizzazione",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                )
                AudioSettings.NormalizationMode.entries.forEach { mode ->
                    val (title, subtitle) = when (mode) {
                        AudioSettings.NormalizationMode.TRACK -> "Traccia" to "Ogni brano viene portato verso il target selezionato."
                        AudioSettings.NormalizationMode.ALBUM -> "Album" to "Mantiene le differenze dinamiche tra brani dello stesso album."
                        AudioSettings.NormalizationMode.AUTOMATIC -> "Automatica" to "Album Gain per una coda dello stesso album, Track Gain negli altri casi."
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = normalizationMode == mode,
                            enabled = normalizationEnabled && localNormalizationEnabled,
                            onClick = { AudioSettings.setNormalizationMode(mode) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text(
                    text = "Target loudness locale",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                listOf(-16.0 to "Conservativo", -14.0 to "Standard", -12.0 to "Forte").forEach { (value, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = kotlin.math.abs(targetLufs - value) < 0.1,
                            enabled = normalizationEnabled,
                            onClick = { AudioSettings.setTargetLufs(value) },
                        )
                        Text("$label (${value.toInt()} LUFS)", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text(
                    text = "Limite amplificazione",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
                listOf(3.0, 6.0, 9.0).forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = kotlin.math.abs(maxGainDb - value) < 0.1,
                            enabled = normalizationEnabled,
                            onClick = { AudioSettings.setMaxGainDb(value) },
                        )
                        Text("+${value.toInt()} dB", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Text(
                    text = "Limite attenuazione",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
                )
                listOf(6.0, 8.0, 12.0).forEach { value ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = kotlin.math.abs(maxAttenuationDb - value) < 0.1,
                            enabled = normalizationEnabled,
                            onClick = { AudioSettings.setMaxAttenuationDb(value) },
                        )
                        Text("-${value.toInt()} dB", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                AudioToggleRow(
                    title = "Scansiona libreria in background",
                    subtitle = "Analizza gradualmente i file locali e riutilizza la cache alle riproduzioni successive.",
                    checked = backgroundScanEnabled,
                    enabled = localNormalizationEnabled && r128AnalysisEnabled,
                    onCheckedChange = AudioSettings::setBackgroundScanEnabled,
                )
                AudioToggleRow(
                    title = "Analizza solo durante la ricarica",
                    subtitle = "Riduce l'impatto sulla batteria quando la libreria è molto grande.",
                    checked = scanOnlyWhileCharging,
                    enabled = backgroundScanEnabled && localNormalizationEnabled && r128AnalysisEnabled,
                    onCheckedChange = AudioSettings::setScanOnlyWhileCharging,
                )

                Text(
                    text = when {
                        localScanStatus.running -> "Analisi: ${localScanStatus.analyzed}/${localScanStatus.total} · ${localScanStatus.currentTitle.orEmpty()}"
                        localScanStatus.total > 0 -> "Analisi locale: ${localScanStatus.analyzed} analizzati · ${localScanStatus.pending} da analizzare"
                        else -> "Analisi locale: cache ${localScanStatus.analyzed} brani"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
                localScanStatus.lastError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
                Row(modifier = Modifier.padding(top = 10.dp)) {
                    OutlinedButton(
                        onClick = AudioSettings::requestLocalScan,
                        enabled = normalizationEnabled && localNormalizationEnabled && r128AnalysisEnabled && !localScanStatus.running,
                    ) { Text("Analizza ora") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = AudioSettings::clearLocalAnalysisCache) {
                        Text("Cancella cache")
                    }
                }

                Text(
                    text = "Continuità e buffering",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
                )
                AudioToggleRow(
                    title = "Gapless: coda continua",
                    subtitle = "Usa la playlist nativa Media3 per le transizioni. Attivo dalla prossima coda.",
                    checked = gaplessEnabled,
                    enabled = true,
                    onCheckedChange = AudioSettings::setGaplessEnabled,
                )
                AudioToggleRow(
                    title = "Crossfade tra brani",
                    subtitle = "Sovrappone i brani usando un secondo decoder quando possibile. Esclude tracce dello stesso album; per i test e' disattivato inizialmente.",
                    checked = crossfadeEnabled,
                    enabled = gaplessEnabled,
                    onCheckedChange = AudioSettings::setCrossfadeEnabled,
                )
                Text(
                    text = "Durata crossfade",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
                listOf(2_000, 4_000, 6_000, 8_000).forEach { duration ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = crossfadeDurationMs == duration,
                            enabled = crossfadeEnabled,
                            onClick = { AudioSettings.setCrossfadeDurationMs(duration) },
                        )
                        Text("${duration / 1_000} secondi", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                AudioToggleRow(
                    title = "Precarica audio del prossimo brano",
                    subtitle = "Su rete non a consumo Media3 precarica fino a 5 secondi della prossima traccia. La configurazione segue automaticamente il tipo di rete.",
                    checked = preloadAudioEnabled,
                    enabled = gaplessEnabled,
                    onCheckedChange = AudioSettings::setPreloadAudioEnabled,
                )
                AudioToggleRow(
                    title = "Prerisolvi i prossimi brani",
                    subtitle = "Prepara in anticipo gli indirizzi streaming dei prossimi due brani. Non scarica l'audio e non garantisce gapless.",
                    checked = prefetchSourcesEnabled,
                    enabled = true,
                    onCheckedChange = AudioSettings::setPrefetchSourcesEnabled,
                )
                AudioToggleRow(
                    title = "Buffer in base alla connessione",
                    subtitle = "Ottimizza il buffer iniziale per rete a consumo o non a consumo. La modifica ha effetto al successivo avvio del player.",
                    checked = adaptiveBufferingEnabled,
                    enabled = true,
                    onCheckedChange = AudioSettings::setAdaptiveBufferingEnabled,
                )

                Text(
                    text = "Qualità streaming",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
                )
                Text(
                    text = "La nuova qualità viene usata dal brano successivo che Lyra deve risolvere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                AudioSettings.StreamingQuality.entries.forEach { quality ->
                    val (title, subtitle) = when (quality) {
                        AudioSettings.StreamingQuality.AUTOMATIC -> "Automatica" to "Lascia a InnerTubeX la scelta più adatta."
                        AudioSettings.StreamingQuality.HIGH -> "Alta" to "Preferisce lo stream audio di qualità più elevata."
                        AudioSettings.StreamingQuality.DATA_SAVER -> "Risparmio dati" to "Riduce il bitrate e il consumo di rete."
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    ) {
                        RadioButton(
                            selected = streamingQuality == quality,
                            onClick = { AudioSettings.setStreamingQuality(quality) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Account Google")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (authorizationState.isAuthorized) {
                                authorizationState.profileName ?: "Account Google collegato"
                            } else {
                                "Nessun account collegato"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (authorizationState.isAuthorized) {
                                "Accesso YouTube attivo per libreria e brani piaciuti."
                            } else {
                                "Puoi accedere nuovamente dalla Home."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                if (authorizationState.isAuthorized) {
                    OutlinedButton(
                        onClick = { showLogoutConfirmation = true },
                        enabled = !authorizationState.isChecking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnetti da Google")
                    }
                }
            }
        }

        Spacer(Modifier.height(26.dp))
        SettingsSectionTitle("Aggiornamenti")
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Aggiornamenti tramite GitHub Releases",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Lyra controlla automaticamente gli aggiornamenti a ogni apertura dell'app. Puoi anche verificare manualmente qui.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Button(
                    onClick = {
                        isCheckingUpdate = true
                        updateResult = null
                        scope.launch {
                            val result = GitHubUpdateChecker.checkNow(context)
                            updateResult = result
                            onUpdateResult(result)
                            isCheckingUpdate = false
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .then(
                            if (updateResult is GitHubUpdateChecker.Result.Available) {
                                Modifier
                            } else {
                                Modifier.focusRequester(updateButtonFocusRequester)
                            }
                        ),
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .width(18.dp)
                                .height(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Controllo...")
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Controlla aggiornamenti")
                    }
                }

                when (val result = updateResult) {
                    is GitHubUpdateChecker.Result.Available -> {
                        Text(
                            text = "Disponibile Lyra ${result.update.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        result.update.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                            Text(
                                text = notes.take(500),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                isDownloadingUpdate = true
                                updateInstallMessage = null
                                scope.launch {
                                    when (
                                        val installResult = UpdateInstaller.downloadAndOpenInstaller(
                                            context = context,
                                            update = result.update,
                                        )
                                    ) {
                                        UpdateInstaller.Result.InstallerOpened -> {
                                            updateInstallMessage =
                                                "Installer Android aperto. Conferma l'aggiornamento."
                                        }

                                        UpdateInstaller.Result.PermissionRequired -> {
                                            updateInstallMessage =
                                                "Autorizza Lyra a installare aggiornamenti, poi premi di nuovo Aggiorna ora."
                                            UpdateInstaller.openUnknownSourcesSettings(context)
                                        }

                                        is UpdateInstaller.Result.Error -> {
                                            updateInstallMessage = installResult.message
                                        }
                                    }
                                    isDownloadingUpdate = false
                                }
                            },
                            enabled = !isDownloadingUpdate,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .focusRequester(updateButtonFocusRequester),
                        ) {
                            if (isDownloadingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .width(18.dp)
                                        .height(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("Download aggiornamento...")
                            } else {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Aggiorna ora")
                            }
                        }

                        updateInstallMessage?.let { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }

                    is GitHubUpdateChecker.Result.UpToDate -> {
                        Text(
                            text = "Lyra è aggiornata (${result.currentVersionName}).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }

                    GitHubUpdateChecker.Result.NoPublishedRelease -> {
                        Text(
                            text = "Nessuna release pubblica trovata su GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }

                    is GitHubUpdateChecker.Result.Error -> {
                        Text(
                            text = result.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                    }

                    null -> Unit
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    if (showResetProfileConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetProfileConfirmation = false },
            title = { Text("Reimpostare il profilo musicale?") },
            text = { Text("Verranno cancellati i segnali di apprendimento locali. Cronologia e playlist restano disponibili.") },
            confirmButton = {
                TextButton(onClick = {
                    ListeningProfileStore.resetProfile()
                    showResetProfileConfirmation = false
                }) { Text("Reimposta") }
            },
            dismissButton = { TextButton(onClick = { showResetProfileConfirmation = false }) { Text("Annulla") } },
        )
    }

    if (showRecoveryCodeDialog) {
        val recoveryCode = CloudProfileSync.recoveryCode().orEmpty()
        AlertDialog(
            onDismissRequest = { showRecoveryCodeDialog = false },
            title = { Text("Codice di recupero Lyra") },
            text = {
                Column {
                    Text("Conservalo in un luogo sicuro. Identifica in modo anonimo il tuo profilo musicale e permette di ripristinarlo dopo cancellazione dati o reinstallazione. Non contiene dati del tuo account Google.")
                    Text(
                        text = recoveryCode,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Codice recupero Lyra", recoveryCode))
                    cloudUiMessage = "Codice di recupero copiato negli appunti."
                    showRecoveryCodeDialog = false
                }) { Text("Copia") }
            },
            dismissButton = { TextButton(onClick = { showRecoveryCodeDialog = false }) { Text("Chiudi") } },
        )
    }

    if (showRestoreCloudDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreCloudDialog = false },
            title = { Text("Ripristina backup Lyra") },
            text = {
                Column {
                    Text("Inserisci il codice LYRA2 del backup. Verrà ripristinato soltanto il profilo musicale usato dall’algoritmo; account Google e libreria vengono recuperati separatamente dopo il login.")
                    OutlinedTextField(
                        value = recoveryCodeInput,
                        onValueChange = { recoveryCodeInput = it },
                        label = { Text("Codice di recupero") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (CloudProfileSync.importRecoveryCode(recoveryCodeInput)) {
                        cloudUiMessage = "Codice accettato. Ripristino avviato."
                        showRestoreCloudDialog = false
                    } else {
                        cloudUiMessage = "Codice di recupero non valido."
                    }
                }) { Text("Ripristina") }
            },
            dismissButton = { TextButton(onClick = { showRestoreCloudDialog = false }) { Text("Annulla") } },
        )
    }

    if (showDeleteCloudConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteCloudConfirmation = false },
            title = { Text("Eliminare il backup remoto attivo?") },
            text = { Text("Verranno eliminati da Ubuntu il profilo musicale e gli eventi associati all’identificatore Lyra corrente. I dati locali restano sul telefono.") },
            confirmButton = {
                TextButton(onClick = {
                    CloudProfileSyncSettings.setEnabled(false)
                    CloudProfileSync.deleteRemoteProfile()
                    cloudUiMessage = "Richiesta di cancellazione inviata."
                    showDeleteCloudConfirmation = false
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { showDeleteCloudConfirmation = false }) { Text("Annulla") } },
        )
    }

    if (showLogoutConfirmation) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirmation = false },
            title = { Text("Disconnettere Google?") },
            text = {
                Text(
                    "Lyra revocherà l'accesso Google e cancellerà la cache locale della libreria. Potrai accedere nuovamente dalla Home."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutConfirmation = false
                        YouTubeAuthorizationManager.disconnect {
                            YouTubeLibraryCache.clear()
                        }
                    }
                ) {
                    Text("Disconnetti")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirmation = false }) {
                    Text("Annulla")
                }
            },
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AudioToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}
