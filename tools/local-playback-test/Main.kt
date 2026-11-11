package app.lyra.music.localtest
import android.content.Context
import app.lyra.music.core.model.Song
import app.lyra.music.playback.AutomaticPlaylistManager as Manager
import app.lyra.music.genre.GenreClassifierSettings as Access
import app.lyra.music.genre.VerifiedGenrePlaylistClient as Verified
import app.lyra.music.personalization.PersonalizationSettings as Settings
import app.lyra.music.personalization.RecommendationEngine
import app.lyra.music.cloud.CloudProfileSync as Wire
import app.lyra.music.diagnostics.DiagnosticsSettings
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.*
fun main() = runBlocking {
    val context=Context()
    app.lyra.music.youtube.repository.DiscoverySettings.initialize(context)
    check(app.lyra.music.youtube.repository.DiscoverySettings.regionMode.value.name=="INTERNATIONAL")
    val italian=(0 until 30).map { Song("it-$it","Italian","Artist",200,origin="italiano") }
    val foreign=(0 until 30).map { Song("world-$it","World","Artist",200,origin="straniero") }
    val regional=app.lyra.music.personalization.RegionPreferencePolicy.order(italian+foreign,"ITALY").take(20)
    check(regional.count { it.origin=="italiano" }==16)
    check(app.lyra.music.personalization.RegionPreferencePolicy.order(italian+foreign,"INTERNATIONAL").take(20).count { it.origin=="straniero" }==16)
    println("PASS international default and 80/20 regional ordering without a hard exclusion")
    Settings.initialize(context)
    DiagnosticsSettings.initialize(context)
    check(DiagnosticsSettings.isLoggingEnabled(context))
    check(DiagnosticsSettings.loggingEnabledSince(context)>0)
    DiagnosticsSettings.setLoggingEnabled(context,false)
    DiagnosticsSettings.initialize(context)
    check(!DiagnosticsSettings.isLoggingEnabled(context))
    check(!Settings.useServerAlgorithm.value)
    Access.authorized.value=true
    check(Settings.useServerAlgorithm.value)
    Access.authorized.value=false
    check(!Settings.useServerAlgorithm.value)
    println("PASS diagnostics defaults and explicit opt-out; server engine follows verified token")

    val songs=(0 until 45).map { Song("device-$it","Music $it","Artist",200,playbackUri="content://music/$it") }
    Manager.clear();Wire.owner=null;Wire.offline=true
    val serverCalls=Wire.requests;val searches=YouTube.searchCount
    val first=Manager.startRandom(songs)
    check(first.title=="Play Casuale" && first.songs.size==20)
    val second=Manager.loadMore()
    check(second.size==20 && second.none { it in first.songs })
    check(Manager.loadMore().size==5)
    check(Manager.loadMore().isEmpty())
    check(Wire.requests==serverCalls && YouTube.searchCount==searches)
    check(RecommendationEngine.rankings>=3)
    println("PASS tokenless local start, local ranking, multiple refills without duplicates or network")

    Manager.clear();Access.authorized.value=false
    val bootstrap=Manager.startRandom(emptyList())
    check(bootstrap.songs.isNotEmpty() && YouTube.searchCount>searches && Wire.requests==serverCalls)
    check(Manager.loadMore().isNotEmpty())
    println("PASS first-use public music discovery requires neither Google login nor Lyra token")

    Manager.clear();Access.authorized.value=true;Verified.fail=false
    val strict=Manager.startFromPreset("rock")
    check(strict.songs.size==5)
    Manager.configureLocalLibrary { songs }
    Access.authorized.value=false
    val fallback=Manager.loadMore()
    check(fallback.size==20 && fallback.all { it.isLocal })
    check(Manager.source.value.startsWith("Locale"))
    println("PASS server playlist continues with device songs when authorization/connectivity is lost")

    Manager.clear();Access.authorized.value=true;Verified.fail=false
    Manager.startFromPreset("rock");Verified.fail=true
    check(Manager.loadMore().all { it.isLocal })
    check(Manager.currentSongs().size>5)
    println("PASS server transport failure falls back during refill instead of exhausting the queue")

    Manager.clear();Access.authorized.value=false
    Manager.configureLocalLibrary { emptyList() }
    val started=Manager.startRandom(emptyList())
    val hold=CompletableDeferred<Unit>();YouTube.holdNext=hold
    val refill=async { Manager.loadMore() };yield();Manager.clear();hold.complete(Unit)
    check(refill.await().isEmpty() && !Manager.isActive())
    YouTube.holdNext=null
    println("PASS late fallback cannot revive a stopped or replaced playlist")
    Manager.clear();Manager.configureLocalLibrary { songs }
    Manager.restoreServerBinding("Play Casuale", started.songs)
    check(Manager.isActive() && Manager.loadMore().all { it.isLocal })
    Manager.clear()
    val resumed=Manager.resumeFromHistory("Play Casuale", songs.take(3))
    check(resumed.songs.size==3 && Manager.loadMore().size==20)
    println("PASS local random queues resume from persistence/history without a server token")
}
