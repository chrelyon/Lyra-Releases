package app.lyra.music.cloud

import java.net.HttpURLConnection
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject

class LyraServerHttpException(val status: Int,val reason: String?=null): IllegalStateException("HTTP $status")
/** Test-only adapter. Never compiled into the app; no real credentials. */
object CloudProfileSync {
    var owner: String?="synthetic-profile"
    var saved: String?=null
    var failAfterSuffix: String?=null
    var offline=false
    var requests=0
    var hold: CompletableDeferred<Unit>?=null
    fun playlistOwner()=owner
    fun playlistState()=saved
    fun savePlaylistState(value: String?) { saved=value }
    suspend fun playlistRequest(method: String,path: String,payload: JSONObject? = null): JSONObject {
        if (offline) throw IOException("synthetic offline")
        if (path.endsWith("/prepare")) hold?.await()
        return withContext(Dispatchers.IO) {
            requests++
            val connection=URI("http://127.0.0.1:8766$path").toURL().openConnection() as HttpURLConnection
            connection.requestMethod=method
            connection.connectTimeout=2000;connection.readTimeout=2000
            connection.setRequestProperty("X-Lyra-Installation","installation_synthetic_001")
            connection.setRequestProperty("X-Lyra-Capabilities","server_queue_v1,boundary_commit_v1,offline_fallback_v1")
            try {
                if (payload!=null) {
                    connection.doOutput=true;connection.setRequestProperty("Content-Type","application/json")
                    connection.outputStream.use { it.write(payload.toString().toByteArray()) }
                }
                val status=connection.responseCode
                val raw=(if(status in 200..299) connection.inputStream else connection.errorStream).bufferedReader().use { it.readText() }
                if (status !in 200..299) throw LyraServerHttpException(status,runCatching { JSONObject(raw).optString("detail") }.getOrNull())
                if (failAfterSuffix?.let(path::endsWith)==true) { failAfterSuffix=null;throw IOException("synthetic lost reply") }
                JSONObject(raw)
            } finally { connection.disconnect() }
        }
    }
}
