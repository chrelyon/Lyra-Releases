package app.lyra.music.googlelogouttest
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager as Auth
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import android.app.Activity
import android.content.Intent
import com.google.android.gms.auth.api.identity.*
import java.net.*
fun main() {
 // UserInfo is cosmetic; intercept it so this test never contacts Google.
 URL.setURLStreamHandlerFactory { protocol -> if (protocol != "https") null else object: URLStreamHandler() {
  override fun openConnection(url: URL)=object: HttpURLConnection(url) {
   override fun connect() {}
   override fun disconnect() {}
   override fun usingProxy()=false
   override fun getResponseCode()=200
   override fun getInputStream()="{}".byteInputStream()
  }
 } }
 val activity=Activity();Auth.initialize(activity)
 val google=Identity.client
 Auth.refreshSilently();check(Auth.state.value.isAuthorized)
 Auth.disconnect()
 check(!Auth.state.value.isAuthorized && Auth.accessTokenOrNull()==null)
 check(activity.getSharedPreferences("lyra_google_session",0).getBoolean("explicitly_signed_out",false))
 val calls=google.authorizeCalls
 repeat(3) { Auth.initialize(activity);Auth.refreshSilently() }
 check(google.authorizeCalls==calls && !Auth.state.value.isAuthorized)
 check(YouTubeLibraryCache.clears>0)
 println("PASS logout survives repeated startup/foreground checks even when Google revocation fails")

 // A successful request started before logout may finish afterward.
 google.next=Task(AuthorizationResult(),delayed=true)
 Auth.authorize(activity,onResolutionRequired={})
 val pending=google.next
 Auth.disconnect();pending.finish()
 check(!Auth.state.value.isAuthorized && Auth.accessTokenOrNull()==null)
 Auth.handleAuthorizationResult(activity,Activity.RESULT_OK,Intent())
 check(!Auth.state.value.isAuthorized)
 println("PASS late authorization callbacks and dismissed Google sheets cannot log the user back in")

 google.next=Task(AuthorizationResult(null))
 Auth.authorize(activity,onResolutionRequired={})
 check(!Auth.state.value.isAuthorized)
 Auth.refreshSilently();check(google.authorizeCalls==calls+2)
 println("PASS a failed explicit login keeps the persistent logout choice")

 google.next=Task(AuthorizationResult())
 Auth.authorize(activity,onResolutionRequired={})
 check(Auth.state.value.isAuthorized && Auth.accessTokenOrNull()!=null)
 check(!activity.getSharedPreferences("lyra_google_session",0).getBoolean("explicitly_signed_out",true))
 println("PASS successful explicit Google login re-enables normal silent token refresh")
}
