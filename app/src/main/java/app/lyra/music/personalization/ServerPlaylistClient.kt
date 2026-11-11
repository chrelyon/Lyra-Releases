package app.lyra.music.personalization

import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.core.model.Song
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.cloud.LyraServerHttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NoVerifiedCandidatesException : IllegalStateException("Nessun brano verificato per questa richiesta. Aggiorna il DB musicale del server.")

/** Compact metadata protocol. This class never resolves or downloads audio. */
class ServerPlaylistClient(val kind: String, val value: String, val title: String, restore: Boolean = false, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var owner = CloudProfileSync.playlistOwner()
    private var state = if (restore) restored(kind, value, owner) else null
    @Volatile private var active = true
    private var retryAt = 0L
    @Volatile var authoritative = false
        private set
    @Volatile var needsResync = true
        private set

    init { if (state == null) state = JSONObject().put("kind",kind).put("value",value)
        .put("title",title).put("owner",owner).put("create_key",key()) }

    private fun save() { if (active) CloudProfileSync.savePlaylistState(state!!.toString()) }
    private fun checkOwner() {
        if (owner==null) { owner=CloudProfileSync.playlistOwner();state!!.put("owner",owner);save() }
        check(active && owner != null && owner == CloudProfileSync.playlistOwner()) { "Identità server non pronta" } }
    private suspend fun request(path: String, data: JSONObject? = null, method: String = "POST"): JSONObject {
        checkOwner()
        return CloudProfileSync.playlistRequest(method,"/v3/playlists/$path",data)
    }
    private fun base(): JSONObject = JSONObject().put("request_id",key()).put("epoch",state!!.getInt("epoch"))
    private fun path(action: String): String = "sessions/${state!!.getString("session_id")}/$action"
    private suspend fun ensureSession() {
        checkOwner();save()
        if (state!!.has("session_id")) return
        val capabilities=request("capabilities",method="GET")
        check(capabilities.optBoolean("delivery_enabled") && capabilities.optBoolean("metadata_batches")) { "Playlist server disattivate" }
        val created=request("sessions",JSONObject().put("request_id",state!!.getString("create_key"))
            .put("intent",JSONObject().put("kind",kind).put("value",if(kind=="genres") JSONArray(value.split("|")) else value)))
        state!!.put("session_id",created.getString("session_id")).put("epoch",created.getInt("epoch"))
            .put("cursor",created.getInt("cursor"))
        save()
    }

    private suspend fun flushFeedback(): Boolean {
        repeat(4) {
            val batch=ListeningEventStore.pendingForCloud().filter { ID.matches(it.songId) }.take(15)
            if (batch.isEmpty()) return true
            val events=JSONArray()
            batch.forEach { event ->
                val data=ListeningEventStore.toCloudJson(event)
                data.remove("queueTitle")
                // Nullable duration/fraction are omitted, rather than sent as JSON null.
                data.keys().asSequence().toList().filter { data.isNull(it) }.forEach(data::remove)
                events.put(data)
            }
            val reply=request("events",JSONObject().put("events",events)).getJSONArray("acknowledged")
            val accepted=(0 until reply.length()).map { reply.getString(it) }.toSet()
            check(accepted.containsAll(batch.map { it.id })) { "Feedback non confermato" }
            ListeningEventStore.markSynced(accepted)
        }
        return ListeningEventStore.pendingForCloud().none { ID.matches(it.songId) }
    }

    private suspend fun songs(ids: JSONArray): List<Song> {
        val ordered=(0 until ids.length()).map { ids.getString(it) }
        check(ordered.all(ID::matches) && ordered.distinct().size == ordered.size && ordered.size <= 20)
        val known=ListeningProfileStore.stats.value.mapValues { it.value.song }.toMutableMap()
        val cache=state!!.optJSONObject("metadata") ?: JSONObject()
        cache.keys().forEach { id ->
            val m=cache.getJSONObject(id)
            known[id]=Song(id,m.getString("title"),m.getString("artist"),0,"https://i.ytimg.com/vi/$id/hqdefault.jpg", origin=m.optString("origin").takeIf { it.isNotBlank() },countryCode=m.optString("countryCode").takeIf { it.isNotBlank() })
        }
        val missing=ordered.filterNot { it in known }
        if (missing.isNotEmpty()) {
            val metadata=request(path("metadata"),JSONObject().put("ids",JSONArray(missing))).getJSONArray("tracks")
            for (i in 0 until metadata.length()) {
                val m=metadata.getJSONObject(i);val id=m.getString("id")
                if (id in missing) {
                    known[id]=Song(id,m.getString("title"),m.getString("artist"),0,"https://i.ytimg.com/vi/$id/hqdefault.jpg", origin=m.optString("origin").takeIf { it.isNotBlank() },countryCode=m.optString("countryCode").takeIf { it.isNotBlank() })
                    cache.put(id,m)
                }
            }
        }
        check(ordered.all { it in known }) { "Metadati incompleti" }
        // Only the outstanding batch needs persistent metadata; history already lives in the profile.
        val kept=JSONObject();ordered.forEach { id -> cache.optJSONObject(id)?.let { kept.put(id,it) } }
        state!!.put("metadata",kept);save()
        return ordered.map { known.getValue(it) }.filterNot { ListeningProfileStore.isBlocked(it.id) }
    }

    suspend fun next(exclude: Set<String>): List<Song> {
        if (!mutex.tryLock()) { offline();throw IllegalStateException("Riallineamento in preparazione") }
        try { return nextLocked(exclude) } finally { mutex.unlock() }
    }
    private suspend fun nextLocked(exclude: Set<String>): List<Song> {
        check(now() >= retryAt)
        return try {
            ensureSession();flushFeedback()
            val pending=state!!.optJSONObject("next_request") ?: base().put("cursor",state!!.getInt("cursor"))
                .put("limit",12).put("exclude_ids",JSONArray(exclude.filter(ID::matches).takeLast(100))).also {
                    state!!.put("next_request",it);save()
                }
            val reply=state!!.optJSONObject("next_reply") ?: request(path("next"),pending).also {
                state!!.put("next_reply",it);save()
            }
            check(reply.getString("session_id") == state!!.getString("session_id") && reply.getInt("epoch") == state!!.getInt("epoch"))
            val result=songs(reply.getJSONArray("ids"))
            state!!.put("cursor",reply.getInt("cursor")).remove("next_request");state!!.remove("next_reply");save()
            if (result.isEmpty()) throw NoVerifiedCandidatesException()
            authoritative=true;needsResync=false
            result
        } catch (e: CancellationException) { throw e }
        catch (e: NoVerifiedCandidatesException) { authoritative=true;needsResync=true;throw e }
        catch (e: Exception) { recover(e);offline();throw e }
    }

    fun feedbackChanged() { needsResync=true }
    fun localFallback() { offline() }
    private fun offline() { authoritative=false;needsResync=true;retryAt=now()+30_000 }

    suspend fun prepare(currentId: String, queueIds: List<String>, revision: String, repeatOff: Boolean) = mutex.withLock {
        if (!needsResync || !repeatOff || !ID.matches(currentId) || now()<retryAt) return@withLock
        try {
            ensureSession()
            if (!flushFeedback()) return@withLock
            val old=state!!.optJSONObject("prepared")
            if (old != null) {
                if (old.optString("current_id")==currentId && old.optString("revision")==revision &&
                    now()-old.optLong("at") < 240_000) return@withLock
                request(path("cancel-prepare"),base().put("prepared_token",old.getString("token")))
                state!!.remove("prepared");save()
            }
            // Retry exactly the same request if the response was lost.
            val pending=state!!.optJSONObject("prepare_request") ?: base().put("current_id",currentId)
                .put("remaining_ids",JSONArray(queueIds.filter(ID::matches).takeLast(100)))
                .put("protected_ids",JSONArray()).put("repeat_mode","off").put("queue_revision",revision).put("limit",12).also {
                    state!!.put("prepare_request",it);save()
                }
            val reply=request(path("prepare"),pending)
            state!!.put("prepared",JSONObject().put("token",reply.getString("prepared_token"))
                .put("current_id",pending.getString("current_id")).put("revision",pending.getString("queue_revision"))
                .put("at",now()))
            state!!.remove("prepare_request");save()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is LyraServerHttpException && e.reason=="insufficient_verified_candidates") {
                state!!.remove("prepare_request");save()
                authoritative=true;needsResync=true;retryAt=now()+60_000
            } else { recover(e);offline() }
        }
    }

    suspend fun boundary(endedId: String?, revision: String, repeatOff: Boolean): List<Song>? = mutex.withLock {
        if (!repeatOff) return@withLock null
        try {
            checkOwner()
            // A previous commit response can arrive after another transition. Keep it for the next boundary.
            val ready=state!!.optJSONObject("commit_reply")
            if (ready != null) {
                if (ready.getInt("epoch")!=state!!.getInt("epoch")) {
                    state!!.remove("commit_reply");state!!.remove("commit_request");save()
                    needsResync=true;return@withLock null
                }
                return@withLock finishCommit(ready)
            }
            val prepared=state!!.optJSONObject("prepared") ?: return@withLock null
            if (!ServerQueuePolicy.canCommit(prepared.getString("current_id"),endedId,
                    prepared.getString("revision"),revision,repeatOff)) return@withLock null
            val data=state!!.optJSONObject("commit_request") ?: base().put("prepared_token",prepared.getString("token"))
                .put("ended_track_id",endedId).put("queue_revision",revision).also {
                    state!!.put("commit_request",it);save()
                }
            val reply=request(path("commit"),data)
            state!!.put("epoch",reply.getInt("epoch")).put("cursor",reply.getInt("cursor"))
                .put("commit_reply",reply);save()
            finishCommit(reply)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { recover(e);offline();null }
    }
    private suspend fun finishCommit(reply: JSONObject): List<Song> {
        check(reply.getString("session_id")==state!!.getString("session_id"))
        return songs(reply.getJSONArray("ids"))
    }
    fun applied() {
        state!!.remove("prepared");state!!.remove("commit_request");state!!.remove("commit_reply")
        // Discard any earlier refill response: it belongs to the preceding epoch.
        state!!.remove("next_request");state!!.remove("next_reply")
        authoritative=true;needsResync=false;save()
    }

    private suspend fun recover(error: Exception) {
        if (error !is LyraServerHttpException || error.status !in listOf(404,409)) return
        if (error.reason in setOf("session_exhausted","session_request_limit")) {
            runCatching { request("sessions/${state!!.getString("session_id")}",method="DELETE") }
            state=JSONObject().put("kind",kind).put("value",value).put("title",title)
                .put("owner",owner).put("create_key",key())
            save();return
        }
        try {
            if (!state!!.has("session_id")) return
            val snapshot=request("sessions/${state!!.getString("session_id")}",method="GET")
            state!!.put("epoch",snapshot.getInt("epoch")).put("cursor",snapshot.getInt("cursor"))
            val token=snapshot.optString("prepared_token").takeIf { it.isNotBlank() && it != "null" }
            if (token != null) request(path("cancel-prepare"),base().put("prepared_token",token))
            for (field in listOf("prepared","prepare_request","next_request","next_reply","commit_request")) state!!.remove(field)
            save()
        } catch (e: LyraServerHttpException) {
            if (e.status==404) {
                val fresh=JSONObject().put("kind",kind).put("value",value).put("title",title)
                    .put("owner",owner).put("create_key",key())
                state=fresh;save()
            }
        } catch (_: Exception) { /* Keep exact requests for retry after a network error. */ }
    }

    fun invalidate() {
        active=false
        val session=state!!.optString("session_id")
        CloudProfileSync.savePlaylistState(null)
        if (session.isNotBlank()) cleanupScope.launch {
            if (owner==CloudProfileSync.playlistOwner()) runCatching {
                CloudProfileSync.playlistRequest("DELETE","/v3/playlists/sessions/$session")
            }
        }
    }

    companion object {
        private val cleanupScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        private val ID=Regex("^[A-Za-z0-9_-]{11}$")
        private fun key()=UUID.randomUUID().toString()
        fun revision(ids: List<String>, title: String, repeat: String, shuffle: Boolean): String =
            UUID.nameUUIDFromBytes((title+"|"+repeat+"|"+shuffle+"|"+ids.joinToString(",")).toByteArray()).toString()
        private fun restored(kind: String,value: String,owner: String?): JSONObject? = runCatching {
            JSONObject(CloudProfileSync.playlistState() ?: return null).takeIf {
                it.optString("owner")==owner && it.optString("kind")==kind && it.optString("value")==value
            }
        }.getOrNull()
        fun savedIntent(): Triple<String,String,String>? = runCatching {
            val saved=JSONObject(CloudProfileSync.playlistState() ?: return null)
            if (saved.optString("owner")!=CloudProfileSync.playlistOwner()) return null
            Triple(saved.getString("kind"),saved.getString("value"),saved.getString("title"))
        }.getOrNull()
    }
}
