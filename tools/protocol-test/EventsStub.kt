package app.lyra.music.personalization
import org.json.JSONObject
object ListeningEventStore {
    data class Event(val id: String,val songId: String)
    val pending=mutableListOf<Event>()
    fun pendingForCloud()=pending.toList()
    fun markSynced(ids: Set<String>) { pending.removeAll { it.id in ids } }
    fun toCloudJson(e: Event)=JSONObject().put("id",e.id).put("songId",e.songId).put("type","LIKED")
        .put("timestampMs",System.currentTimeMillis()).put("queueTitle","Test only")
}
