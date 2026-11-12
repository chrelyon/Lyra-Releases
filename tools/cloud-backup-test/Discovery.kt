package app.lyra.music.youtube.repository
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
object DiscoverySettings {
 enum class RegionMode {INTERNATIONAL,ITALY}
 val regionMode=MutableStateFlow(RegionMode.INTERNATIONAL)
 fun initialize(context:Context){}
}
object AutomaticPlaylistCatalog {data class Preset(val title:String,val id:String);val presets=emptyList<Preset>()}
