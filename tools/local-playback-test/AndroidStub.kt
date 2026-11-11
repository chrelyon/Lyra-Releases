package android.content
open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext get() = this
    val preferences = mutableMapOf<String, Preferences>()
    fun getSharedPreferences(name: String, mode: Int) = preferences.getOrPut(name) { Preferences() }
}
class Preferences {
    val values = mutableMapOf<String, Any>()
    fun contains(key: String) = key in values
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun getLong(key: String, default: Long) = values[key] as? Long ?: default
    fun getString(key: String, default: String?) = values[key] as? String ?: default
    fun edit() = Editor(this)
}
class Editor(val prefs: Preferences) {
    fun putBoolean(key: String, value: Boolean) = apply { prefs.values[key] = value }
    fun putLong(key: String, value: Long) = apply { prefs.values[key] = value }
    fun putString(key: String, value: String) = apply { prefs.values[key] = value }
    fun remove(key: String) = apply { prefs.values.remove(key) }
    fun commit() = true
    fun apply() {}
}
class Intent
