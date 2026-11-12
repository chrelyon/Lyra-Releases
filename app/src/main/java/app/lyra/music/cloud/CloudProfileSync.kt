package app.lyra.music.cloud

import android.content.Context
import android.util.Base64
import app.lyra.music.BuildConfig
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.playback.ListeningProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * Backup cifrato delle configurazioni e sincronizzazione del profilo musicale condiviso.
 *
 * Il server di sincronizzazione riceve titoli, artisti e segnali di ascolto utili all'algoritmo,
 * ma nessun dato dell'account Google: niente nome, e-mail, UID, cookie, OAuth
 * token o password. Gli eventi sono associati esclusivamente a un listenerId
 * casuale generato da Lyra e protetti da un segreto casuale conservato sul
 * dispositivo e nel codice di recupero.
 */
class LyraServerHttpException(val status: Int, val reason: String? = null) : IllegalStateException("Lyra Server HTTP $status")

object CloudProfileSync {
    private const val PREFS_NAME = "lyra_cloud_music_identity_v2"
    private const val KEY_LISTENER_ID = "listener_id"
    private const val KEY_SECRET = "secret"
    private const val KEY_FRESH_IDENTITY = "identity_created_on_this_device"
    private const val REQUEST_TIMEOUT_MS = 15_000
    private const val RECOVERY_PREFIX = "LYRA2"
    private const val MAX_EVENTS_PER_SYNC = 40

    data class Status(
        val syncing: Boolean = false,
        val restoring: Boolean = false,
        val pendingEvents: Int = 0,
        val lastSuccessAtMs: Long = 0L,
        val lastRestoreAtMs: Long = 0L,
        val error: String? = null,
    )

    private class FullBackupUnavailable : IllegalStateException("Backup completo non ancora disponibile. Inserisci il token salvato oppure richiedine uno nuovo all’amministratore.")

    private data class Identity(
        val listenerId: String,
        val secret: String,
    )

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    fun initialize(context: Context) {
        appContext = context.applicationContext
        ensureIdentity()
        finishPendingMusicApply()
        _status.value = _status.value.copy(
            pendingEvents = ListeningEventStore.pendingForCloud().size,
        )
        if (CloudProfileSyncSettings.enabled.value) {
            CloudBackupWork.schedulePeriodic(appContext)
            retryBootstrapOrSync()
        }
    }

    fun scheduleSync() {
        if (!::appContext.isInitialized) return
        if (!UbuntuOnlyCutoverPolicy.canUpload(CloudProfileSyncSettings.enabled.value,
                CloudProfileSyncSettings.ready.value, CloudProfileSyncSettings.backendEndpoint)) return
        _status.value = _status.value.copy(
            pendingEvents = ListeningEventStore.pendingForCloud().size,
        )
        CloudBackupWork.scheduleOneShot(appContext)
    }

    /** Snapshot remoto in sola lettura per i grafici: non sovrascrive il profilo locale. */
    suspend fun readAnalyticsSnapshot(): JSONObject = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "Lyra Server non inizializzato" }
        check(CloudProfileSyncSettings.enabled.value && CloudProfileSyncSettings.ready.value &&
              CloudProfileSyncSettings.backendEndpoint.isNotBlank()) {
            "Attiva il backup musicale nelle Impostazioni"
        }
        requestJson("GET", "/v2/restore", ensureIdentity(), null)
    }

    fun syncNow() {
        if (!::appContext.isInitialized) return
        CloudBackupWork.cancelOneShot(appContext)
        scope.launch {
            if (bootstrapIfNeeded()) syncInternal(forceBackup = true)
        }
    }

    fun retryBootstrapOrSync() {
        if (!::appContext.isInitialized || !CloudProfileSyncSettings.enabled.value) return
        scope.launch {
            if (bootstrapIfNeeded()) scheduleSync()
        }
    }

    internal suspend fun performBackgroundBackup(): Boolean {
        if (!::appContext.isInitialized || !CloudProfileSyncSettings.enabled.value) return true
        return bootstrapIfNeeded() && syncInternal()
    }

    fun recoveryCode(): String? {
        if (!::appContext.isInitialized) return null
        val identity = ensureIdentity()
        return "$RECOVERY_PREFIX.${identity.listenerId}.${identity.secret}"
    }

    fun importRecoveryCode(code: String): Boolean {
        if (!::appContext.isInitialized || CloudProfileSyncSettings.backendEndpoint.isBlank()) return false
        val telegramCode = code.trim().uppercase(java.util.Locale.ROOT)
        if (Regex("LYRA-R-[A-F0-9]{24}").matches(telegramCode)) {
            val tokenBeforeRecovery = app.lyra.music.genre.GenreClassifierSettings.token()
            scope.launch {
                _status.value = _status.value.copy(restoring = true, error = null)
                try {
                    val identity = mutex.withLock {
                        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                            .digest(telegramCode.toByteArray()).joinToString("") { "%02x".format(it) }
                        val secret = if (prefs.getString("recovery_challenge_hash", null) == fingerprint)
                            prefs.getString("recovery_new_secret", null) else null
                        val newSecret = secret ?: encode(randomBytes(32))
                        check(prefs.edit().putString("recovery_challenge_hash", fingerprint)
                            .putString("recovery_new_secret", newSecret).commit()) { "Impossibile preparare il ripristino" }
                        app.lyra.music.genre.GenreClassifierSettings.setConnectionEnabled(true)
                        val reply = try {
                            requestJson("POST", "/v2/backup/recover", ensureIdentity(),
                                JSONObject().put("code", telegramCode).put("newSecret", newSecret))
                        } catch (error: FullBackupUnavailable) {
                            // Earlier profiles have a musical backup only: saved token remains required.
                            if (app.lyra.music.genre.GenreClassifierSettings.token() == null) throw error
                            requestJson("POST", "/v2/recovery/redeem", ensureIdentity(),
                                JSONObject().put("code", telegramCode).put("newSecret", newSecret))
                        }
                        check(tokenBeforeRecovery == app.lyra.music.genre.GenreClassifierSettings.token()) { "Token modificato durante il ripristino. Riprova." }
                        val client = reply.optJSONObject("client")
                        CloudProfileSyncSettings.clearUbuntuReady()
                        prefs.edit().remove("pending_shared_sync").remove("pending_shared_apply").commit()
                        client?.let { DeviceBackup.restore(appContext, it) }
                        client?.optString("accessToken")?.takeIf { it.startsWith("lyra_") }?.let {
                            check(app.lyra.music.genre.GenreClassifierSettings.saveToken(it)) { "Impossibile salvare il token ripristinato" }
                        }
                        val token = app.lyra.music.genre.GenreClassifierSettings.token()
                            ?: error("Richiedi un nuovo token Telegram")
                        val listener = reply.getString("listenerId")
                        require(Regex("[A-Za-z0-9_-]{20,80}").matches(listener)) { "Risposta di recupero non valida" }
                        Identity(listener, newSecret).also {
                            // No local snapshot may overwrite the recovered server profile before import.
                            CloudProfileSyncSettings.clearUbuntuReady()
                            persistIdentity(it)
                            prefs.edit().putBoolean(KEY_FRESH_IDENTITY, false).commit()
                            app.lyra.music.genre.GenreClassifierSettings.recordAuthorization(token, true)
                        }
                    }
                    reloadRestoredSettings()
                    restoreInternal(identity, adoptIdentity = true)
                } catch (error: Exception) {
                    _status.value = _status.value.copy(restoring = false, error = friendlyError(error))
                }
            }
            return true
        }
        val identity = parseRecoveryCode(code) ?: return false
        _status.value = Status(pendingEvents = ListeningEventStore.pendingForCloud().size)
        scope.launch { restoreInternal(identity, adoptIdentity = true) }
        return true
    }

    /**
     * First launch after the upgrade: Ubuntu is authoritative. The old listener ID and
     * recovery secret are reused, but no request is ever sent to Cloudflare.
     * Existing pending events are intentionally not uploaded (old D1 may have been
     * updated after the export). Events recorded while the restore is running are kept.
     */
    private suspend fun bootstrapIfNeeded(): Boolean = mutex.withLock {
        if (!CloudProfileSyncSettings.enabled.value || !CloudProfileSyncSettings.endpointConfigured) {
            return@withLock false
        }
        if (CloudProfileSyncSettings.ready.value) return@withLock true
        _status.value = _status.value.copy(restoring = true, error = null)
        return@withLock try {
            val identity = ensureIdentity()
            val endpoint = CloudProfileSyncSettings.backendEndpoint
            val health = requestJson("GET", "/v2/health", identity, null, endpoint)
            check(health.optBoolean("ok") && health.optInt("schemaVersion") == 2 &&
                  health.optString("storage") == "ubuntu-sqlite") {
                "Il server configurato non e un servizio Lyra valido"
            }
            // Capture pre-migration events before network I/O, so new listens are not lost.
            val previousPendingIds = ListeningEventStore.pendingForCloud().mapTo(mutableSetOf()) { it.id }
            val freshIdentity = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FRESH_IDENTITY, false)
            val restored = try {
                requestJson("GET", "/v2/restore?apply=true", identity, null, endpoint)
            } catch (missing: IllegalStateException) {
                if (!freshIdentity || missing.message != "Nessun backup musicale associato a questo codice") {
                    throw missing
                }
                // Only a newly generated device identity may create a new Ubuntu profile.
                // Never create an empty replacement for a previously imported/recovered ID.
                requestJson("POST", "/v2/enroll", identity,
                    JSONObject().put("profile", ListeningProfileStore.cloudSnapshotJson()), endpoint)
                requestJson("GET", "/v2/restore?apply=true", identity, null, endpoint)
            }
            val profile = restored.optJSONObject("profile")
                ?: throw IllegalStateException("Profilo non presente nel profilo musicale")
            ListeningProfileStore.restoreCloudSnapshotJson(profile)
            ListeningEventStore.restoreCloudEventsJson(restored.optJSONArray("events"), replaceSynced = true)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong("music_generation", restored.optLong("musicGeneration", 0L)).commit()
            // No re-upload of the pre-import backlog; preserve new listening events.
            if (!freshIdentity) ListeningEventStore.markSynced(previousPendingIds)
            check(CloudProfileSyncSettings.markUbuntuReady(endpoint)) {
                "Impossibile memorizzare il collegamento a Lyra"
            }
            _status.value = _status.value.copy(
                restoring = false,
                pendingEvents = ListeningEventStore.pendingForCloud().size,
                lastRestoreAtMs = System.currentTimeMillis(),
                error = null,
            )
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FRESH_IDENTITY, false).apply()
            Log.d("LYRA_SERVER", "Ubuntu profile ready; new user enrollment supported")
            true
        } catch (error: Exception) {
            _status.value = _status.value.copy(restoring = false, error = friendlyError(error))
            Log.w("LYRA_SERVER", "Ubuntu initial restore failed message=${error.message}")
            false
        }
    }

    fun restoreLatest() {
        if (!::appContext.isInitialized || CloudProfileSyncSettings.backendEndpoint.isBlank()) return
        scope.launch {
            if (bootstrapIfNeeded()) restoreInternal(ensureIdentity(), adoptIdentity = false)
        }
    }

    fun deleteRemoteProfile() {
        if (!::appContext.isInitialized || CloudProfileSyncSettings.backendEndpoint.isBlank()) return
        scope.launch {
            mutex.withLock {
                val identity = ensureIdentity()
                val connection = openConnection("DELETE", "/v2/profile", identity)
                try {
                    connection.connect()
                    val code = connection.responseCode
                    if (code !in 200..299 && code != HttpURLConnection.HTTP_NOT_FOUND) {
                        throw IllegalStateException("Lyra Server HTTP $code")
                    }
                    clearIdentity()
                    CloudProfileSyncSettings.clearUbuntuReady()
                    _status.value = Status(
                        pendingEvents = ListeningEventStore.pendingForCloud().size,
                        lastSuccessAtMs = System.currentTimeMillis(),
                    )
                } catch (error: Exception) {
                    _status.value = _status.value.copy(error = friendlyError(error))
                } finally {
                    connection.disconnect()
                }
            }
        }
    }

    private suspend fun syncInternal(forceBackup: Boolean = false): Boolean = mutex.withLock {
        if (!CloudProfileSyncSettings.enabled.value) return@withLock true
        if (!UbuntuOnlyCutoverPolicy.canUpload(true, CloudProfileSyncSettings.ready.value,
                CloudProfileSyncSettings.backendEndpoint)) return@withLock false
        val identity = ensureIdentity()
        _status.value = _status.value.copy(
            syncing = true,
            pendingEvents = ListeningEventStore.pendingForCloud().size,
            error = null,
        )

        return@withLock try {
            var pending = ListeningEventStore.pendingForCloud()
            do {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val stored = prefs.getString("pending_shared_sync", null)?.let(::JSONObject)
                val batch = pending.take(MAX_EVENTS_PER_SYNC)
                val sentProfile = stored?.getJSONObject("profile") ?: ListeningProfileStore.cloudSnapshotJson()
                val payload = stored ?: JSONObject()
                    .put("syncId", encode(randomBytes(24)))
                    .put("musicGeneration", prefs.getLong("music_generation", 0L))
                    .put("schemaVersion", 2)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("sentAtMs", System.currentTimeMillis())
                    .put("events", JSONArray().apply {
                        batch.forEach { put(ListeningEventStore.toCloudJson(it)) }
                    })
                    .put("profile", sentProfile)

                check(prefs.edit().putString("pending_shared_sync", payload.toString()).commit()) { "Impossibile preparare la sincronizzazione" }
                val sentEvents = payload.getJSONArray("events")
                val sentIds = (0 until sentEvents.length()).map { sentEvents.getJSONObject(it).getString("id") }
                val response = requestJson("POST", "/v2/sync", identity, payload)
                val accepted = response.optInt("acceptedEvents", sentIds.size)
                if (accepted < sentIds.size) {
                    throw IllegalStateException("Lyra Server ha accettato solo $accepted/${sentIds.size} eventi")
                }
                response.optJSONObject("profile")?.let { shared ->
                    // Store the already computed result. A process restart replays this exact
                    // result, rather than adding the same shared changes a second time.
                    ListeningProfileStore.applySharedSnapshot(shared, sentProfile, reset = response.optBoolean("resetMusic"), beforeApply = { merged ->
                        val journal = JSONObject().put("profile", merged).put("eventIds", JSONArray(sentIds))
                            .put("events", response.optJSONArray("events"))
                            .put("musicGeneration", response.optLong("musicGeneration", 0L))
                            .put("resetMusic", response.optBoolean("resetMusic"))
                        check(prefs.edit().putString("pending_shared_apply", journal.toString()).commit())
                    }, afterApply = {
                        applySharedHistory(response)
                        ListeningEventStore.markSynced(sentIds.toSet())
                        check(prefs.edit().putLong("music_generation", response.optLong("musicGeneration", 0L))
                            .remove("pending_shared_apply").remove("pending_shared_sync").commit())
                    })
                } ?: run {
                    ListeningEventStore.markSynced(sentIds.toSet())
                    check(prefs.edit().remove("pending_shared_sync").commit())
                }
                pending = ListeningEventStore.pendingForCloud()
            } while (pending.isNotEmpty())

            requestJson("POST", if (forceBackup) "/v2/backups?manual=true" else "/v2/backups", identity,
                DeviceBackup.export(appContext, app.lyra.music.genre.GenreClassifierSettings.token()))
            _status.value = Status(
                syncing = false,
                pendingEvents = 0,
                lastSuccessAtMs = System.currentTimeMillis(),
            )
            Log.d("LYRA_SERVER", "music profile backup success")
            true
        } catch (error: Exception) {
            _status.value = _status.value.copy(
                syncing = false,
                pendingEvents = ListeningEventStore.pendingForCloud().size,
                error = friendlyError(error),
            )
            Log.w("LYRA_SERVER", "music profile backup failed message=${error.message}")
            false
        }
    }

    private suspend fun restoreInternal(
        identity: Identity,
        adoptIdentity: Boolean,
    ): Unit = mutex.withLock {
        _status.value = _status.value.copy(restoring = true, error = null)
        try {
            val root = requestJson("GET", "/v2/restore?apply=true", identity, null)
            val profile = root.optJSONObject("profile")
                ?: throw IllegalStateException("Backup musicale Lyra Server non valido")
            ListeningProfileStore.restoreCloudSnapshotJson(profile)
            ListeningEventStore.restoreCloudEventsJson(root.optJSONArray("events"), replaceSynced = true)
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong("music_generation", root.optLong("musicGeneration", 0L)).commit()
            if (adoptIdentity) {
                persistIdentity(identity)
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(KEY_FRESH_IDENTITY, false).apply()
                CloudProfileSyncSettings.markUbuntuReady(CloudProfileSyncSettings.backendEndpoint)
            }
            _status.value = Status(
                restoring = false,
                pendingEvents = ListeningEventStore.pendingForCloud().size,
                lastSuccessAtMs = _status.value.lastSuccessAtMs,
                lastRestoreAtMs = System.currentTimeMillis(),
            )
            Log.d("LYRA_SERVER", "music profile restore success")
            scheduleSync()
        } catch (error: Exception) {
            _status.value = _status.value.copy(
                restoring = false,
                error = friendlyError(error),
            )
            Log.w("LYRA_SERVER", "music profile restore failed message=${error.message}")
        }
    }

    private fun finishPendingMusicApply() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val journal = prefs.getString("pending_shared_apply", null)?.let(::JSONObject) ?: return
        ListeningProfileStore.restoreCloudSnapshotJson(journal.getJSONObject("profile"))
        applySharedHistory(journal)
        val ids = journal.getJSONArray("eventIds")
        ListeningEventStore.markSynced((0 until ids.length()).map { ids.getString(it) }.toSet())
        check(prefs.edit().putLong("music_generation", journal.optLong("musicGeneration", 0L))
            .remove("pending_shared_apply").remove("pending_shared_sync").commit())
    }

    private fun applySharedHistory(reply: JSONObject) {
        ListeningEventStore.restoreCloudEventsJson(reply.optJSONArray("events"),
            replaceSynced = true, discardPending = reply.optBoolean("resetMusic"))
    }

    private fun reloadRestoredSettings() {
        app.lyra.music.audio.AudioSettings.initialize(appContext)
        app.lyra.music.personalization.PersonalizationSettings.initialize(appContext)
        app.lyra.music.youtube.repository.DiscoverySettings.initialize(appContext)
        app.lyra.music.playback.PlaybackResumeSettings.initialize(appContext)
        app.lyra.music.playback.ListeningHistoryStore.initialize(appContext)
        app.lyra.music.playback.SavedAutomaticPlaylistStore.initialize(appContext)
        app.lyra.music.playback.AutomaticPlaylistVarietyStore.initialize(appContext)
        app.lyra.music.personalization.ListeningEventStore.initialize(appContext)
        app.lyra.music.diagnostics.DiagnosticsSettings.initialize(appContext)
    }

    private fun ensureIdentity(): Identity {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listenerId = prefs.getString(KEY_LISTENER_ID, null)
        val secret = prefs.getString(KEY_SECRET, null)
        if (!listenerId.isNullOrBlank() && !secret.isNullOrBlank()) {
            return Identity(listenerId, secret)
        }
        val identity = Identity(
            listenerId = encode(randomBytes(16)),
            secret = encode(randomBytes(32)),
        )
        persistIdentity(identity)
        prefs.edit().putBoolean(KEY_FRESH_IDENTITY, true).apply()
        return identity
    }

    private fun persistIdentity(identity: Identity) {
        val previous=appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LISTENER_ID,null)
        if (previous!=null && previous!=identity.listenerId) app.lyra.music.playback.AutomaticPlaylistManager.clear()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LISTENER_ID, identity.listenerId)
            .putString(KEY_SECRET, identity.secret)
            .commit()
    }

    private fun clearIdentity() {
        app.lyra.music.playback.AutomaticPlaylistManager.clear()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun parseRecoveryCode(raw: String): Identity? {
        val parts = raw.trim().replace("\n", "").replace(" ", "").split('.')
        if (parts.size != 3 || parts[0] != RECOVERY_PREFIX) return null
        val listenerBytes = decodeOrNull(parts[1]) ?: return null
        val secretBytes = decodeOrNull(parts[2]) ?: return null
        if (listenerBytes.size != 16 || secretBytes.size != 32) return null
        return Identity(parts[1], parts[2])
    }

    /** Same anonymous profile identity; never exposes the bearer to UI or playlist code. */
    internal fun playlistOwner(): String? = if (::appContext.isInitialized &&
        CloudProfileSyncSettings.enabled.value && CloudProfileSyncSettings.ready.value &&
        app.lyra.music.genre.GenreClassifierSettings.isConfigured()
    ) ensureIdentity().listenerId else null

    internal fun playlistState(): String? = if (::appContext.isInitialized)
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("playlist_v3_state", null) else null

    internal fun savePlaylistState(value: String?) {
        if (::appContext.isInitialized) appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("playlist_v3_state", value).commit()
    }

    internal suspend fun playlistRequest(method: String, path: String, payload: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            check(playlistOwner() != null && path.startsWith("/v3/playlists/")) { "Server playlist non disponibile" }
            requestJson(method, path, ensureIdentity(), payload)
        }

    internal suspend fun personalizedMixRequest(payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        check(playlistOwner() != null) {
            "Attiva il collegamento e il backup musicale Lyra Server nelle Impostazioni"
        }
        requestJson("POST", "/v2/mix", ensureIdentity(), payload)
    }

    @Synchronized internal fun installationId(): String {
        val prefs=appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("installation_id", null) ?: encode(randomBytes(16)).also {
            prefs.edit().putString("installation_id", it).commit()
        }
    }

    private fun requestJson(
        method: String,
        path: String,
        identity: Identity,
        payload: JSONObject?,
        rootOverride: String? = null,
    ): JSONObject {
        val checkedToken = app.lyra.music.genre.GenreClassifierSettings.token()
        val connection = openConnection(method, path, identity, rootOverride)
        try {
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { output ->
                    output.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
                }
            } else {
                connection.connect()
            }
            val code = connection.responseCode
            val response = readBody(connection)
            if (code == 401 || code == 403 || code == 426) {
                checkedToken?.let {
                    app.lyra.music.genre.GenreClassifierSettings.recordAuthorization(it, false)
                }
            }
            if (path == "/v2/backup/recover" && (code == 404 || (code == 409 &&
                runCatching { JSONObject(response).optString("detail") }.getOrNull() == "full_device_backup_not_available_use_saved_token"))) {
                throw FullBackupUnavailable()
            }
            if (path in setOf("/v2/recovery/redeem", "/v2/backup/recover") && code !in 200..299 && code != 426) {
                throw IllegalStateException(when (code) {
                    400, 422 -> "Codice non valido, scaduto o già utilizzato. Richiedi /ripristina al bot."
                    429 -> "Troppi tentativi di ripristino. Attendi 10 minuti."
                    401, 403 -> "Token Telegram non autorizzato al ripristino."
                    else -> "Ripristino temporaneamente non disponibile. Riprova."
                })
            }
            if (code == 426) throw IllegalStateException("Aggiorna Lyra per collegarti al server.")
            if (path.startsWith("/v3/") && code !in 200..299) {
                val reason=runCatching { JSONObject(response).optString("detail") }.getOrNull()
                    ?.takeIf { it.length<=80 && it.matches(Regex("[a-z_]+")) }
                throw LyraServerHttpException(code,reason)
            }
            if (path == "/v2/mix" && code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IllegalStateException("Mix non disponibile: aggiorna il server Lyra e verifica il profilo")
            }
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IllegalStateException("Nessun backup musicale associato a questo codice")
            }
            if (code !in 200..299) {
                throw IllegalStateException("Lyra Server HTTP $code")
            }
            return runCatching { JSONObject(response) }
                .getOrElse { throw IllegalStateException("Risposta Lyra Server non valida") }
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        method: String,
        path: String,
        identity: Identity,
        rootOverride: String? = null,
    ): HttpURLConnection {
        check(path in setOf("/v2/backup/recover", "/v2/recovery/redeem") || app.lyra.music.genre.GenreClassifierSettings.isConfigured()) {
            "Inserisci e verifica il token Telegram nelle Impostazioni"
        }
        val root = rootOverride ?: CloudProfileSyncSettings.backendEndpoint.trimEnd('/')
        return (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = if (path.startsWith("/v3/")) 4000 else REQUEST_TIMEOUT_MS
            readTimeout = when {
                path.startsWith("/v3/") -> 4000
                path == "/v2/mix" -> 60_000
                else -> REQUEST_TIMEOUT_MS
            }
            setRequestProperty("Accept", "application/json")
            val bearer = if (path == "/v2/backup/recover") "telegram-recovery" else if (path == "/v2/recovery/redeem")
                app.lyra.music.genre.GenreClassifierSettings.token() ?: error("Inserisci il tuo token Telegram") else identity.secret
            setRequestProperty("Authorization", "Bearer $bearer")
            setRequestProperty("X-Lyra-Listener", identity.listenerId)
            if (root.trimEnd('/') == app.lyra.music.genre.GenreClassifierSettings.SERVER_URL.trimEnd('/')) {
                app.lyra.music.genre.GenreClassifierSettings.token()
                    ?.takeIf { it.startsWith("lyra_") }
                    ?.let { setRequestProperty("X-Lyra-Access-Token", it) }
            }
            setRequestProperty("X-Lyra-App-Version", BuildConfig.VERSION_NAME)
            setRequestProperty("X-Lyra-App-Version-Code", BuildConfig.VERSION_CODE.toString())
            setRequestProperty("X-Lyra-Region", app.lyra.music.youtube.repository.DiscoverySettings.regionMode.value.name)
            setRequestProperty("X-Lyra-Installation", installationId())
            setRequestProperty("X-Lyra-Device-Model", (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).filter { it.code in 32..126 }.take(80))
            if (path.startsWith("/v3/")) setRequestProperty("X-Lyra-Capabilities", "server_queue_v1,boundary_commit_v1,offline_fallback_v1")
            setRequestProperty("User-Agent", "Lyra/${BuildConfig.VERSION_NAME}")
        }
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream?.use {
            BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(SecureRandom()::nextBytes)

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)

    private fun decodeOrNull(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }.getOrNull()

    private fun friendlyError(error: Throwable): String =
        error.message?.takeIf { it.isNotBlank() } ?: "Backup musicale Lyra Server non disponibile"
}
