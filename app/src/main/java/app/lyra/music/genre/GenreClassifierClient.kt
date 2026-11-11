package app.lyra.music.genre

import android.os.SystemClock
import app.lyra.music.BuildConfig
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Read-only access to the classifier's existing /v1/lookup cache.
 *  No YouTube audio is downloaded or uploaded by this client.
 */
object GenreClassifierClient {
    private const val CONNECT_TIMEOUT_MS = 900
    private const val READ_TIMEOUT_MS = 1100
    private const val MAX_LOOKUPS = 48
    private const val BATCH_TIMEOUT_MS = 3_500L
    private const val RETRY_COOLDOWN_MS = 45_000L
    @Volatile private var retryAfterElapsedMs: Long = 0L

    private data class LookupResult(val id: String, val found: Boolean, val rejected: Boolean)

    sealed class ConnectionCheck {
        data object Ready : ConnectionCheck()
        data class Failed(val reason: String) : ConnectionCheck()
    }

    /** Independent public health probe; usable before entering the private classifier token. */
    suspend fun probeHealth(): ConnectionCheck = withContext(Dispatchers.IO) {
        val endpoint = GenreClassifierSettings.SERVER_URL
        try {
            val health = request(endpoint, "/health", null, null)
            if (health.optString("status") != "ok") {
                ConnectionCheck.Failed("Classificatore non pronto")
            } else {
                val profile = request(endpoint, "/v2/health", null, null)
                if (profile.optBoolean("ok", false)) ConnectionCheck.Ready
                else ConnectionCheck.Failed("Servizio profili non pronto")
            }
        } catch (error: Exception) {
            ConnectionCheck.Failed("Server non raggiungibile (${error.javaClass.simpleName})")
        }
    }

    suspend fun checkConnection(): ConnectionCheck = withContext(Dispatchers.IO) {
        val endpoint = GenreClassifierSettings.endpoint.value
        val token = GenreClassifierSettings.token()
        if (!GenreClassifierSettings.connectionEnabled.value || !GenreDecisionPolicy.validEndpoint(endpoint) || token == null) {
            return@withContext ConnectionCheck.Failed("Indirizzo o token non configurati")
        }
        try {
            val status = request(endpoint, "/health", null, null)
            if (status.optString("status") != "ok") {
                GenreClassifierSettings.recordAuthorization(token, false)
                return@withContext ConnectionCheck.Failed("Servizio non ancora pronto")
            }
            val test = request(endpoint, "/v1/lookup", token,
                JSONObject().put("media_id", "lyra-connectivity-probe").put("preset", "house"))
            if (!test.has("found")) {
                GenreClassifierSettings.recordAuthorization(token, false)
                ConnectionCheck.Failed("Verifica dell’accesso non disponibile")
            } else if (GenreClassifierSettings.recordAuthorization(token, true)) {
                ConnectionCheck.Ready
            } else ConnectionCheck.Failed("Token modificato durante la verifica")
        } catch (error: Exception) {
            GenreClassifierSettings.recordAuthorization(token, false)
            ConnectionCheck.Failed(
                when (error) {
                    is UnauthorizedException -> "Token non autorizzato o revocato."
                    is UpdateRequiredException -> "Aggiorna Lyra per collegarti al server."
                    else -> "Connessione non riuscita: ${error.javaClass.simpleName}"
                }
            )
        }
    }

    suspend fun filterKnownIncompatible(preset: String?, songs: List<Song>): List<Song> {
        if (!GenreClassifierSettings.connectionEnabled.value ||
            LyraServerMonitor.status.value == LyraServerMonitor.Status.OFFLINE ||
            !GenreClassifierSettings.enabled.value ||
            !GenreDecisionPolicy.supported(preset) || songs.isEmpty()) return songs
        val endpoint = GenreClassifierSettings.endpoint.value
        val token = GenreClassifierSettings.token() ?: return songs
        if (!GenreDecisionPolicy.validEndpoint(endpoint)) return songs
        if (SystemClock.elapsedRealtime() < retryAfterElapsedMs) return songs
        val tag = requireNotNull(preset).lowercase()
        val candidates = songs.asSequence().filterNot { it.isLocal }.distinctBy { it.id }
            .take(MAX_LOOKUPS).toList()
        val lookedUp = withTimeoutOrNull(BATCH_TIMEOUT_MS) {
            coroutineScope {
                val slots = Semaphore(6)
                candidates.map { song ->
                    async(Dispatchers.IO) {
                        slots.withPermit {
                            runCatching {
                                val response = request(endpoint, "/v1/lookup", token,
                                    JSONObject().put("media_id", song.id).put("preset", tag))
                                val found = response.optBoolean("found", false)
                                LookupResult(
                                    id = song.id,
                                    found = found,
                                    rejected = GenreDecisionPolicy.shouldExclude(
                                        found, response.optString("decision", "unknown")),
                                )
                            }.getOrNull()
                        }
                    }
                }.awaitAll()
            }
        }
        if (lookedUp == null) {
            retryAfterElapsedMs = SystemClock.elapsedRealtime() + RETRY_COOLDOWN_MS
            Log.w("LYRA_GENRE", "lookupTimeout preset=$tag; original queue preserved")
            return songs
        }
        val successful = lookedUp.filterNotNull()
        if (successful.isEmpty() && candidates.isNotEmpty()) {
            retryAfterElapsedMs = SystemClock.elapsedRealtime() + RETRY_COOLDOWN_MS
            Log.w("LYRA_GENRE", "lookupUnavailable preset=$tag; retry delayed")
            return songs
        }
        retryAfterElapsedMs = 0L
        val excluded = successful.filter { it.rejected }.mapTo(mutableSetOf()) { it.id }
        val known = successful.count { it.found }
        Log.d("LYRA_GENRE", "preset=$tag lookedUp=${successful.size} classified=$known rejected=${excluded.size}")
        if (excluded.isEmpty()) {
            return songs
        }
        val keep = GenreDecisionPolicy.keepQueue(songs.map { it.id }, excluded).toSet()
        val filtered = songs.filter { it.id in keep }
        Log.d("LYRA_GENRE", "preset=$tag filteredQueue=${filtered.size} (original=${songs.size})")
        return filtered
    }

    private class UnauthorizedException : Exception()
    private class UpdateRequiredException : Exception()

    private fun request(endpoint: String, path: String, bearer: String?, body: JSONObject?): JSONObject {
        val url = URL(endpoint + path)
        val connection = (url.openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.setRequestProperty("X-Lyra-App-Version", BuildConfig.VERSION_NAME)
            connection.setRequestProperty("X-Lyra-App-Version-Code", BuildConfig.VERSION_CODE.toString())
            connection.instanceFollowRedirects = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            } else {
                connection.requestMethod = "GET"
            }
            if (bearer != null) connection.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 401 || status == 403 || status == 426) {
                bearer?.let { GenreClassifierSettings.recordAuthorization(it, false) }
                if (status == 426) throw UpdateRequiredException()
                throw UnauthorizedException()
            }
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val result = connection.inputStream.bufferedReader().use { it.readText().take(16_384) }
            return JSONObject(result)
        } finally {
            connection.disconnect()
        }
    }
}
