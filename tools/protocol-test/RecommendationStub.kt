package app.lyra.music.personalization
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
object PersonalizationSettings {
    val enabled=MutableStateFlow(true)
    val learnFromSkips=MutableStateFlow(true)
    val avoidRepeats=MutableStateFlow(true)
}
object RecommendationEngine {
    data class SessionContext(val title: String,val contextTag: String?,val recentSongs: List<Song> = emptyList(),
        val sessionSalt: Long=0,val avoidArtists: Set<String> = emptySet(),val positiveArtists: Set<String> = emptySet(),val diversificationLevel: Int=0)
    fun rank(candidates: List<Song>,context: SessionContext)=candidates
    fun artistKey(value: String?)=value.orEmpty().lowercase()
}
