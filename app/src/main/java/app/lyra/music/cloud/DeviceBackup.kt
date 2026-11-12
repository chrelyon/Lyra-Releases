package app.lyra.music.cloud

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit allowlist: never Google cookies, OAuth, device credentials or Keystore ciphertext. */
internal object DeviceBackup {
    val preferenceNames = setOf(
        "lyra_audio_settings", "lyra_personalization_settings", "lyra_discovery_settings",
        "lyra_playback_resume_settings", "lyra_playback_session", "lyra_listening_history",
        "lyra_saved_automatic_playlists", "lyra_automatic_variety", "lyra_listening_profile",
        "lyra_listening_events", "lyra_diagnostics",
    )
    private const val JOURNAL = "lyra-device-restore.pending.json"

    fun export(context: Context, token: String?): JSONObject {
        val prefs = JSONObject()
        preferenceNames.forEach { name ->
            val entries = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                val type = when (value) {
                    is String -> "string"
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    is Set<*> -> "set"
                    else -> null
                }
                if (type != null) entries.put(key, JSONObject().put("type", type)
                    .put("value", if (value is Set<*>) JSONArray(value.toList()) else value))
            }
            prefs.put(name, entries)
        }
        return JSONObject().put("schemaVersion", 1).put("preferences", prefs).apply {
            if (token != null) put("accessToken", token)
        }
    }

    private fun validate(payload: JSONObject) {
        require(payload.getInt("schemaVersion") == 1) { "Versione backup non supportata" }
        val prefs = payload.getJSONObject("preferences")
        prefs.keys().forEach { name ->
            require(name in preferenceNames) { "Configurazione backup non supportata" }
            val entries = prefs.getJSONObject(name)
            entries.keys().forEach { key ->
                val entry = entries.getJSONObject(key)
                when (entry.getString("type")) {
                    "string" -> require(entry.get("value") is String)
                    "boolean" -> require(entry.get("value") is Boolean)
                    "int" -> { val value = entry.getLong("value"); require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }
                    "long" -> entry.getLong("value")
                    "float" -> require(entry.getDouble("value").isFinite())
                    "set" -> { val values = entry.getJSONArray("value"); for (i in 0 until values.length()) require(values.get(i) is String) }
                    else -> error("Tipo di configurazione non supportato")
                }
            }
        }
    }

    @Synchronized fun restore(context: Context, payload: JSONObject) {
        validate(payload)
        // The journal contains only allowlisted preferences, never the access token.
        val safe = JSONObject().put("schemaVersion", 1).put("preferences", payload.getJSONObject("preferences"))
        File(context.filesDir, "lyra-device-before-restore.json").writeText(export(context, null).toString())
        val temporary = File(context.filesDir, "$JOURNAL.tmp")
        temporary.outputStream().use { output -> output.write(safe.toString().toByteArray(Charsets.UTF_8)); output.fdSyncIfAvailable() }
        check(temporary.renameTo(File(context.filesDir, JOURNAL))) { "Impossibile preparare il ripristino" }
        completePendingRestore(context)
    }

    private fun java.io.OutputStream.fdSyncIfAvailable() {
        if (this is java.io.FileOutputStream) fd.sync()
    }

    @Synchronized fun completePendingRestore(context: Context) {
        val journal = File(context.filesDir, JOURNAL)
        if (!journal.isFile) return
        val payload = JSONObject(journal.readText())
        validate(payload)
        val prefs = payload.getJSONObject("preferences")
        prefs.keys().forEach { name ->
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            val entries = prefs.getJSONObject(name)
            entries.keys().forEach { key -> put(editor, key, entries.getJSONObject(key)) }
            check(editor.commit()) { "Salvataggio configurazione non riuscito: riprova al riavvio" }
        }
        check(journal.delete()) { "Impossibile completare il ripristino" }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, entry: JSONObject) {
        when (entry.getString("type")) {
            "string" -> editor.putString(key, entry.getString("value"))
            "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
            "int" -> editor.putInt(key, entry.getInt("value"))
            "long" -> editor.putLong(key, entry.getLong("value"))
            "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
            "set" -> { val values = entry.getJSONArray("value"); editor.putStringSet(key, (0 until values.length()).map { values.getString(it) }.toSet()) }
        }
    }
}
