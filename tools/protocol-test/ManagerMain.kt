package app.lyra.music.protocoltest
import app.lyra.music.playback.AutomaticPlaylistManager as Manager
import app.lyra.music.cloud.CloudProfileSync as Wire
import app.lyra.music.personalization.NoVerifiedCandidatesException
import app.lyra.music.personalization.RequestedArtistPolicy
import app.lyra.music.genre.GenreDecisionPolicy
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.*
fun main()=runBlocking {
    Wire.owner="synthetic-profile";Wire.offline=false;Manager.clear()
    val count=YouTube.searchCount
    val missing=runCatching { Manager.startFromPreset("rock") }.exceptionOrNull()
    check(missing is NoVerifiedCandidatesException)
    check(YouTube.searchCount==count && !Manager.isActive())
    println("PASS reachable server with no verified genre never falls back to an unrelated radio")
    val noGenre=app.lyra.music.personalization.ServerPlaylistClient("genre","rock","Synthetic no genre")
    check(runCatching { noGenre.next(emptySet()) }.exceptionOrNull() is NoVerifiedCandidatesException)
    noGenre.prepare("bridge00000",listOf("bridge00000"),"queue_revision_12345",true)
    check(noGenre.authoritative)
    Wire.offline=true;Manager.clear()
    val artist=Manager.startFromArtist("Rammstein")
    check(artist.songs.size==4 && artist.songs.all { it.artist=="Rammstein" })
    check(Manager.loadMore().isEmpty())
    Manager.clear()
    val hold=CompletableDeferred<Unit>();YouTube.hold=hold
    val start=async { runCatching { Manager.startFromArtist("Rammstein") } }
    yield();Manager.clear();hold.complete(Unit)
    check(start.await().isFailure && !Manager.isActive() && Manager.currentSongs().isEmpty())
    YouTube.hold=null
    println("PASS exact artist filtering, no unrelated refill, manual clear discards late local responses")
    check(RequestedArtistPolicy.matches("Beyoncé",listOf("Beyonce")))
    check(!RequestedArtistPolicy.matches("Simon & Garfunkel",listOf("Simon")))
    check(!RequestedArtistPolicy.matches("Rammstein",listOf("Rammstein Tribute")))
    check(GenreDecisionPolicy.keepQueue(listOf("a","b"),setOf("a","b")).isEmpty())
    println("PASS complete artist credit matching and rejected genre candidates cannot return")
    Manager.clear();Wire.offline=true
    val local=Manager.startFromPreset("rock")
    val refillHold=CompletableDeferred<Unit>();YouTube.holdNext=refillHold
    val refill=async { Manager.loadMore() };yield()
    val current=local.songs.first()
    val server=app.lyra.music.core.model.Song("server00001","Server","Rammstein",200)
    val accepted=Manager.acceptServerTail(Manager.serverBinding(),current.id,local.songs,listOf(server))!!
    refillHold.complete(Unit)
    check(refill.await().isEmpty())
    check(Manager.currentSongs()==accepted)
    YouTube.holdNext=null
    println("PASS late local refill cannot contaminate a queue already taken over by server")
    Manager.clear()
}
