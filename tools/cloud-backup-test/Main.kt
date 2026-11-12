package app.lyra.music.cloud

import android.content.Context
import app.lyra.music.genre.GenreClassifierSettings as Access
import app.lyra.music.playback.ListeningProfileStore as Music
import app.lyra.music.personalization.ListeningEventStore as Events
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.core.model.Song
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import java.net.*
import java.io.ByteArrayOutputStream
import java.io.File

private val requests=mutableListOf<Pair<String,JSONObject?>>()
private var failReply=false
private var canonical=JSONObject().put("schemaVersion",2).put("tracks",JSONArray())
private var backup:JSONObject?=null
private val receipts=mutableMapOf<String,JSONObject>()
fun main()=runBlocking {
 val context=Context()
 val prefs=context.getSharedPreferences("lyra_audio_settings",0)
 prefs.edit().putBoolean("boolean",true).putString("string","Test setting").putInt("int",3)
  .putLong("long",5000000000).putFloat("float",1.25f).putStringSet("set",setOf("a","b")).commit()
 context.getSharedPreferences("lyra_google_session",0).edit().putString("cookie","DO_NOT_EXPORT").commit()
 val token="lyra_"+"A".repeat(64)
 val exported=DeviceBackup.export(context,token)
 check(!exported.toString().contains("DO_NOT_EXPORT"))
 prefs.edit().clear().commit()
 DeviceBackup.restore(context,exported)
 check(prefs.getString("string",null)=="Test setting"&&prefs.getLong("long",0)==5000000000)
 check(prefs.all["set"]==setOf("a","b"))
 check(!File(context.filesDir,"lyra-device-before-restore.json").readText().contains(token))
 val malicious=JSONObject().put("schemaVersion",1).put("preferences",JSONObject().put("lyra_google_session",JSONObject()))
 check(runCatching {DeviceBackup.restore(context,malicious)}.isFailure)
 check(prefs.getInt("int",0)==3)
 // Replay a pending preference journal, simulating death between preference commits.
 val journal=JSONObject(exported.toString()).apply{remove("accessToken")}
 prefs.edit().clear().commit();File(context.filesDir,"lyra-device-restore.pending.json").writeText(journal.toString())
 DeviceBackup.completePendingRestore(context);check(prefs.getInt("int",0)==3)
 println("PASS typed configuration roundtrip, Google exclusion and interrupted restore journal")
 URL.setURLStreamHandlerFactory { scheme -> if(scheme!="https")null else object:URLStreamHandler(){
  override fun openConnection(url:URL):URLConnection=object:HttpURLConnection(url){
   val output=ByteArrayOutputStream()
   var response:JSONObject?=null
   override fun connect(){}
   override fun disconnect(){}
   override fun usingProxy()=false
   override fun getOutputStream()=output
   override fun getResponseCode():Int {
    check(getRequestProperty("X-Lyra-Installation").length>=20)
    check(getRequestProperty("X-Lyra-App-Version")=="1.0.26")
    if(response==null){
     val payload=output.toString(Charsets.UTF_8).takeIf{it.isNotBlank()}?.let(::JSONObject)
     synchronized(requests){requests.add(url.path to payload)}
     response=when(url.path){
      "/v2/health"->JSONObject().put("ok",true).put("schemaVersion",2).put("storage","ubuntu-sqlite")
      "/v2/restore"->JSONObject().put("profile",canonical).put("events",JSONArray())
      "/v2/sync"->{
       val value=requireNotNull(payload);val id=value.getString("syncId")
       receipts.getOrPut(id){canonical=value.getJSONObject("profile");JSONObject().put("acceptedEvents",value.getJSONArray("events").length()).put("profile",canonical)}
      }
      "/v2/backups"->{backup=requireNotNull(payload);JSONObject().put("saved",true)}
      "/v2/backup/recover"->JSONObject().put("listenerId","restored_"+"R".repeat(24)).put("client",backup).put("recovered",true)
      else->error("Unexpected endpoint: "+url.path)
     }
    }
    return 200
   }
   override fun getInputStream():java.io.InputStream {
    getResponseCode()
    if(url.path=="/v2/sync"&&failReply){failReply=false;throw java.io.IOException("Synthetic reply loss")}
    return response.toString().byteInputStream()
   }
  }
 } }
 Access.savedToken=token;Access.connectionEnabled.value=true;Access.authorized.value=false
 PersonalizationSettings.initialize(context);Events.initialize(context);Music.initialize(context)
 CloudProfileSyncSettings.initialize(context);CloudProfileSync.initialize(context)
 Access.authorized.value=true
 // Enable manually without background scheduling racing this deterministic test.
 CloudProfileSyncSettings.onServerConnectionChanged()
 // Let its scheduled bootstrap finish before recording playback.
 repeat(100){if(!CloudProfileSyncSettings.ready.value)Thread.sleep(10)}
 check(CloudProfileSyncSettings.ready.value){ CloudProfileSync.status.value.toString() }
 val song=Song("AAAAAAAAAAA","Synthetic song","Synthetic artist",100)
 Music.setLiked(song,true)
 failReply=true
 val failed=CloudProfileSync.performBackgroundBackup()
 check(!failed)
 val pending=context.getSharedPreferences("lyra_cloud_music_identity_v2",0).getString("pending_shared_sync",null)
 check(pending!=null)
 check(CloudProfileSync.performBackgroundBackup())
 val uploads=synchronized(requests){requests.filter{it.first=="/v2/sync"}.map{it.second!!}}
 check(uploads[uploads.lastIndex-1].getString("syncId")==uploads.last().getString("syncId"))
 check(Events.pendingForCloud().isEmpty())
 check(backup?.getString("accessToken")==token)
 check(Music.stats.value[song.id]?.liked==true)
 println("PASS actual profile/event/cloud clients, stable sync replay and encrypted-backup upload contract")
 // Isolated code exchange remains usable after the access token was lost.
 Access.savedToken=null;Access.authorized.value=false
 val previousRestore=CloudProfileSync.status.value.lastRestoreAtMs
 check(CloudProfileSync.importRecoveryCode("LYRA-R-"+"A".repeat(24)))
 repeat(200){if(CloudProfileSync.status.value.lastRestoreAtMs<=previousRestore||Access.savedToken==null)Thread.sleep(10)}
 check(CloudProfileSync.status.value.error==null){CloudProfileSync.status.value.error.orEmpty()}
 check(CloudProfileSync.status.value.lastRestoreAtMs>0)
 check(Access.savedToken==token)
 println("PASS lost-token recovery restores configuration and access via Telegram proof")
}
