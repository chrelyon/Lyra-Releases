package android.content
import java.io.File
open class Context {
    companion object { const val MODE_PRIVATE=0 }
    val applicationContext get()=this
    val filesDir:File=java.nio.file.Files.createTempDirectory("lyra-backup-test").toFile()
    private val preferences=mutableMapOf<String,SharedPreferences>()
    fun getSharedPreferences(name:String,mode:Int)=preferences.getOrPut(name){SharedPreferences()}
}
class SharedPreferences {
    private val values=mutableMapOf<String,Any>()
    val all:Map<String,Any> get()=values.toMap()
    fun contains(key:String)=values.containsKey(key)
    fun getString(key:String,default:String?):String?=values[key] as? String ?: default
    fun getBoolean(key:String,default:Boolean)=values[key] as? Boolean ?: default
    fun getLong(key:String,default:Long)=values[key] as? Long ?: default
    fun getInt(key:String,default:Int)=values[key] as? Int ?: default
    fun getFloat(key:String,default:Float)=values[key] as? Float ?: default
    fun edit()=Editor(this)
    class Editor(private val prefs:SharedPreferences) {
        private val updates=mutableMapOf<String,Any?>();private var clearing=false
        fun clear()=apply{clearing=true}
        fun remove(key:String)=apply{updates[key]=null}
        fun putString(key:String,value:String?)=apply{updates[key]=value}
        fun putBoolean(key:String,value:Boolean)=apply{updates[key]=value}
        fun putInt(key:String,value:Int)=apply{updates[key]=value}
        fun putLong(key:String,value:Long)=apply{updates[key]=value}
        fun putFloat(key:String,value:Float)=apply{updates[key]=value}
        fun putStringSet(key:String,value:Set<String>?)=apply{updates[key]=value}
        fun commit():Boolean {if(clearing)prefs.values.clear();for((k,v) in updates)if(v==null)prefs.values.remove(k) else prefs.values[k]=v;return true}
        fun apply(){commit()}
    }
}
