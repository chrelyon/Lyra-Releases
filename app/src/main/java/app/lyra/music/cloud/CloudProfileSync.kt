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
 * Backup anonimo del solo profilo musicale Lyra.
 *
 * Il server di sincronizzazione riceve titoli, artisti e segnali di ascolto utili all'algoritmo,
 * ma nessun dato dell'account Google: niente nome, e-mail, UID, cookie, OAuth
 * token o password. Gli eventi sono associati esclusivamente a un listenerId
 * casuale generato da Lyra e protetti da un segreto casuale conservato sul
 * dispositivo e nel codice di recupero.
 */
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
            if (bootstrapIfNeeded()) syncInternal()
        }
    }

    fun retryBootstrapOrSync() {
        if (!::appContext.isInitialized || !CloudProfileSyncSettings.enabled.value) return
        scope.launch {
            if (bootstrapIfNeeded()) scheduleSync()
        }
    }

    internal suspend fun performBackgroundBackup(): Boolean {
        if (!::appContext.isInitialized) return true
        return bootstrapIfNeeded() && syncInternal()
    }

    fun recoveryCode(): String? {
        if (!::appContext.isInitialized) return null
        val identity = ensureIdentity()
        return "$RECOVERY_PREFIX.${identity.listenerId}.${identity.secret}"
    }

    fun importRecoveryCode(code: String): Boolean {
        if (!::appContext.isInitialized || CloudProfileSyncSettings.backendEndpoint.isBlank()) return false
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
                "Il server configurato non e un backend Lyra Ubuntu valido"
            }
            // Capture pre-migration events before network I/O, so new listens are not lost.
            val previousPendingIds = ListeningEventStore.pendingForCloud().mapTo(mutableSetOf()) { it.id }
            val freshIdentity = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FRESH_IDENTITY, false)
            val restored = try {
                requestJson("GET", "/v2/restore", identity, null, endpoint)
            } catch (missing: IllegalStateException) {
                if (!freshIdentity || missing.message != "Nessun backup musicale associato a questo codice") {
                    throw missing
                }
                // Only a newly generated device identity may create a new Ubuntu profile.
                // Never create an empty replacement for a previously imported/recovered ID.
                requestJson("POST", "/v2/enroll", identity,
                    JSONObject().put("profile", ListeningProfileStore.cloudSnapshotJson()), endpoint)
                requestJson("GET", "/v2/restore", identity, null, endpoint)
            }
            val profile = restored.optJSONObject("profile")
                ?: throw IllegalStateException("Profilo non presente nel database Ubuntu")
            ListeningProfileStore.restoreCloudSnapshotJson(profile)
            ListeningEventStore.restoreCloudEventsJson(restored.optJSONArray("events"))
            // No re-upload of the pre-import backlog; preserve new listening events.
            if (!freshIdentity) ListeningEventStore.markSynced(previousPendingIds)
            check(CloudProfileSyncSettings.markUbuntuReady(endpoint)) {
                "Impossibile memorizzare il passaggio a Ubuntu"
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

    private suspend fun syncInternal(): Boolean = mutex.withLock {
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
                val batch = pending.take(MAX_EVENTS_PER_SYNC)
                val payload = JSONObject()
                    .put("schemaVersion", 2)
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("sentAtMs", System.currentTimeMillis())
                    .put("events", JSONArray().apply {
                        batch.forEach { put(ListeningEventStore.toCloudJson(it)) }
                    })
                    .put("profile", ListeningProfileStore.cloudSnapshotJson())

                val response = requestJson("POST", "/v2/sync", identity, payload)
                val accepted = response.optInt("acceptedEvents", batch.size)
                if (accepted < batch.size) {
                    throw IllegalStateException("Lyra Server ha accettato solo $accepted/${batch.size} eventi")
                }
                if (batch.isNotEmpty()) {
                    ListeningEventStore.markSynced(batch.mapTo(mutableSetOf()) { it.id })
                }
                pending = ListeningEventStore.pendingForCloud()
            } while (pending.isNotEmpty())

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
            val root = requestJson("GET", "/v2/restore", identity, null)
            val profile = root.optJSONObject("profile")
                ?: throw IllegalStateException("Backup musicale Lyra Server non valido")
            ListeningProfileStore.restoreCloudSnapshotJson(profile)
            ListeningEventStore.restoreCloudEventsJson(root.optJSONArray("events"))
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
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LISTENER_ID, identity.listenerId)
            .putString(KEY_SECRET, identity.secret)
            .commit()
    }

    private fun clearIdentity() {
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

    private fun requestJson(
        method: String,
        path: String,
        identity: Identity,
        payload: JSONObject?,
        rootOverride: String? = null,
    ): JSONObject {
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
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw IllegalStateException("Nessun backup musicale associato a questo codice")
            }
            if (code !in 200..299) {
                throw IllegalStateException("Lyra Server HTTP $code ${response.take(120)}")
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
        val root = rootOverride ?: CloudProfileSyncSettings.backendEndpoint.trimEnd('/')
        return (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = REQUEST_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${identity.secret}")
            setRequestProperty("X-Lyra-Listener", identity.listenerId)
            setRequestProperty("X-Lyra-App-Version", BuildConfig.VERSION_NAME)
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
