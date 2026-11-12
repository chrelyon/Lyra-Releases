package app.lyra.music.genre
import kotlinx.coroutines.flow.MutableStateFlow
object GenreClassifierSettings {
 const val SERVER_URL="https://synthetic.ts.net:10000"
 val endpoint=MutableStateFlow(SERVER_URL)
 val authorized=MutableStateFlow(false)
 val connectionEnabled=MutableStateFlow(false)
 var savedToken:String?=null
 fun isConfigured()=connectionEnabled.value&&authorized.value
 fun token()=savedToken
 fun saveToken(token:String):Boolean {savedToken=token;return true}
 fun recordAuthorization(token:String,allowed:Boolean):Boolean {authorized.value=allowed;return allowed}
 fun setConnectionEnabled(value:Boolean) {connectionEnabled.value=value}
}
