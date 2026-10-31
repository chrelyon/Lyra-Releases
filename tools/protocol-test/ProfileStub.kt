package app.lyra.music.playback
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
object ListeningProfileStore {
    data class TrackStat(val song: Song)
    val stats=MutableStateFlow<Map<String,TrackStat>>(emptyMap())
    fun isBlocked(id: String)=false
    fun inferContextTag(title: String): String?=title.substringAfter("·", "").trim().lowercase().takeIf { it.isNotBlank() }
    fun seedCandidatesForTag(tag: String)=emptyList<Song>()
}
