package app.lyra.music.playback
import app.lyra.music.core.model.Song
object AutomaticPlaylistVarietyStore {
    fun recentProposals(id: String)=emptySet<String>()
    fun recentOpeners(id: String)=emptySet<String>()
    fun recentSeeds(id: String)=emptySet<String>()
    fun remember(id: String,seeds: List<String>,songs: List<Song>,recordOpening: Boolean=true) {}
}
