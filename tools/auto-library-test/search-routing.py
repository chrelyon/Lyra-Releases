"""Compile and execute the real search-routing prefix with deterministic adapters."""
from pathlib import Path
import sys
root=Path(sys.argv[1]);out=Path(sys.argv[2]);s=(root/'app/src/main/java/app/lyra/music/playback/PlaybackService.kt').read_text();a=s.index('        val mediaId = requested.mediaId',s.index('    private suspend fun resolveRequestedContext('));b=s.index('        if (mediaId.startsWith(AUTO_DISCOVER_GROUP_PREFIX))',a)
(out/'SearchRouting.kt').write_text('''package app.lyra.music.autosearchtest
import kotlinx.coroutines.runBlocking
data class Song(val id:String="song",val title:String="Test")
data class Meta(val searchQuery:String?)
data class Request(val mediaId:String,val requestMetadata:Meta)
data class AutoSongContext(val song:Song,val queueTitle:String,val queueSongs:List<Song>,val index:Int,val automaticSeed:Boolean=false)
data class Result(val title:String,val songs:List<Song>)
object AutomaticPlaylistManager {suspend fun startFromSeed(seed:Song,title:String):Result=error("Synthetic local radio unavailable")}
class SearchHarness {
private val searchResultCache=mutableMapOf<String,List<Song>>()
private suspend fun searchAllSongs(query:String)=listOf(Song())
private fun requiresDiscoveryAccess(id:String)=id.contains("discover")
suspend fun resolve(requested:Request):AutoSongContext {
'''+s[a:b]+'''
return AutoSongContext(Song(),"ACTION",emptyList(),0)
}
}
fun main()=runBlocking {
 val h=SearchHarness()
 for(id in listOf("lyra:auto:discover","lyra:auto:random","lyra:auto:mix","")) {
  check(h.resolve(Request(id,Meta(" Test "))).queueTitle=="Ricerca: Test")
 }
 check(runCatching {h.resolve(Request("lyra:auto:discover",Meta(null)))}.isFailure)
 check(h.resolve(Request("lyra:auto:random",Meta(null))).queueTitle=="ACTION")
 println("PASS real voice-search routing bypasses stale browse actions while non-search Discovery stays blocked")
}
''')
(out/'NoToken.kt').write_text('package app.lyra.music.genre\nobject GenreClassifierSettings {fun isConfigured()=false}\n')
