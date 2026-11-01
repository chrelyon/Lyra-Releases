package app.lyra.music.advancedsearchtest
import app.lyra.music.ui.discover.PlaylistSearchController
import app.lyra.music.youtube.repository.*
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.*
import kotlinx.coroutines.*

fun main() = runBlocking {
    val artist=PlaylistSearchTarget("artist1","Rammstein",PlaylistSearchKind.ARTIST,"Artista")
    val album=PlaylistSearchTarget("album1","Mutter",PlaylistSearchKind.ALBUM,"Rammstein · 2001")
    var calls=0
    val controller=PlaylistSearchController(this,{ _, kind -> calls++;listOf(artist,album).filter { it.kind==kind } },debounceMs=0)
    controller.queryChanged("r");yield();check(calls==0)
    controller.queryChanged("ram");yield();check(controller.state.value.suggestions==listOf(artist))
    controller.select(artist);check(controller.state.value.selected==artist)
    controller.queryChanged("mutt");check(controller.state.value.selected==null)
    controller.kindChanged(PlaylistSearchKind.ALBUM);yield();check(controller.state.value.suggestions==listOf(album))
    controller.select(artist);check(controller.state.value.selected==null)
    controller.select(album);check(controller.state.value.selected==album)
    controller.clear();check(controller.state.value.query.isEmpty())
    val slow=CompletableDeferred<List<PlaylistSearchTarget>>()
    val latest=PlaylistSearchController(this,{ query,_ ->
        if(query=="old") withContext(NonCancellable) { slow.await() } else listOf(artist)
    },0)
    latest.queryChanged("old");yield();latest.queryChanged("new");yield()
    slow.complete(listOf(album));yield();yield()
    check(latest.state.value.query=="new" && latest.state.value.suggestions==listOf(artist))
    val failed=PlaylistSearchController(this,{ _,_ -> error("provider failure") },0)
    failed.queryChanged("bad");yield();check(failed.state.value.error!=null && !failed.state.value.loading)
    val timed=PlaylistSearchController(this,{ _,_ -> withTimeout(1) { delay(100);emptyList() } },0)
    timed.queryChanged("wait");delay(20);check(timed.state.value.error!=null && !timed.state.value.loading)
    var debouncedCalls=0
    val debounced=PlaylistSearchController(this,{ _,_ -> debouncedCalls++;emptyList() },20)
    debounced.queryChanged("ra");debounced.queryChanged("ram");debounced.queryChanged("ramm")
    delay(40);check(debouncedCalls==1)
    val repo=AdvancedPlaylistSearchRepository()
    YouTube.searchItems=listOf(ArtistItem("1","Rammstein"),ArtistItem("profile","Fan profile",true),AlbumItem("a","Mutter"))
    check(repo.suggestions("ra",PlaylistSearchKind.ARTIST).map { it.title }==listOf("Rammstein"))
    check(YouTube.lastFilter=="artist")
    check(repo.suggestions("mu",PlaylistSearchKind.ALBUM).map { it.id }==listOf("a"))
    YouTube.albumPage=YouTube.AlbumPage(AlbumItem("a","Mutter"),listOf(SongItem("s1","Mein Herz brennt"),SongItem("s2","Links 2 3 4")))
    check(repo.albumSongs("a").map { it.id }==listOf("s1","s2"))
    check(runCatching { repo.albumSongs("different") }.isFailure)
    println("OK: typed suggestions, selection reset, stale responses, debounce, failures, timeout, album identity and track order")
}
