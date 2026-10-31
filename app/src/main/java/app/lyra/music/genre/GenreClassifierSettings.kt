package app.lyra.music.genre

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Fixed Lyra Funnel hostname; per-device token remains encrypted and user configurable. */
object GenreClassifierSettings {
    private const val PREFS = "lyra_genre_classifier_v1"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ENDPOINT = "endpoint" // legacy setting, ignored after migration
    private const val KEY_CONNECTION_ENABLED = "connection_enabled_v1"
    const val SERVER_URL = "https://vivisochat-lab.tail056f21.ts.net:10000"
    private const val KEY_ENCRYPTED_TOKEN = "encrypted_token"
    private const val KEY_ALIAS = "lyra_genre_api_key_v1"

    private lateinit var context: Context
    private val _enabled = MutableStateFlow(false)
    private val _endpoint = MutableStateFlow(SERVER_URL)
    private val _connectionEnabled = MutableStateFlow(true)
    private val _hasToken = MutableStateFlow(false)

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val connectionEnabled: StateFlow<Boolean> = _connectionEnabled.asStateFlow()
    val endpoint: StateFlow<String> = _endpoint.asStateFlow()
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Never trust a previously user-entered or legacy Funnel address.
        _endpoint.value = SERVER_URL
        _connectionEnabled.value = prefs.getBoolean(KEY_CONNECTION_ENABLED, true)
        _hasToken.value = readToken() != null
        // Keep the user's genre-filter preference even when server access is disabled.
        _enabled.value = prefs.getBoolean(KEY_ENABLED, false) && _hasToken.value
    }

    fun isConfigured(): Boolean =
        _connectionEnabled.value && _hasToken.value

    /** Only the bearer token may be changed, not the server URL. */
    fun saveToken(newToken: String): Boolean {
        if (!::context.isInitialized) return false
        if (newToken.isNotBlank()) {
            val token = newToken.trim()
            if (token.length < 40) return false
            val sealed = runCatching { encrypt(token) }.getOrNull() ?: return false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_ENCRYPTED_TOKEN, sealed).apply()
        }
        _hasToken.value = readToken() != null
        return _hasToken.value
    }

    fun setConnectionEnabled(value: Boolean) {
        if (!::context.isInitialized) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONNECTION_ENABLED, value).apply()
        _connectionEnabled.value = value
        app.lyra.music.cloud.CloudProfileSyncSettings.onServerConnectionChanged()
        app.lyra.music.genre.LyraServerMonitor.refresh()
    }

    fun setEnabled(value: Boolean) {
        if (!::context.isInitialized) return
        val effective = value && _hasToken.value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, effective).apply()
        _enabled.value = effective
    }

    fun token(): String? = if (::context.isInitialized) readToken() else null

    private fun readToken(): String? = runCatching {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENCRYPTED_TOKEN, null) ?: throw IllegalStateException("No saved token")
        val bytes = Base64.decode(saved, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            .takeIf { it.length >= 40 }
    }.getOrNull()

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
    }
}
