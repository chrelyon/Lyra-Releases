package app.lyra.music.youtube.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.lyra.music.diagnostics.LyraLog as Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Autorizzazione Google ufficiale per la libreria YouTube di Lyra.
 *
 * Dalla 0.3.5 usiamo youtube.force-ssl per poter leggere la libreria e
 * aggiungere/rimuovere i "Mi piace" tramite l'icona cuore.
 * Oltre allo scope YouTube richiediamo soltanto il profilo base Google
 * (openid/profile) per mostrare nome e avatar nella UI. Non chiediamo lo scope
 * email e Lyra non salva password, cookie o token su disco.
 */
object YouTubeAuthorizationManager {

    private const val TAG = "LYRA_AUTH"
    const val YOUTUBE_FORCE_SSL_SCOPE =
        "https://www.googleapis.com/auth/youtube.force-ssl"

    private const val OPENID_SCOPE = "openid"
    private const val USERINFO_ENDPOINT =
        "https://openidconnect.googleapis.com/v1/userinfo"

    private val requestedScopes = listOf(
        Scope(YOUTUBE_FORCE_SSL_SCOPE),
        Scope(OPENID_SCOPE),
        Scope(Scopes.PROFILE),
    )

    private var appContext: Context? = null
    private var accessToken: String? = null
    private var authorizedAccount: Account? = null
    private var silentRefreshInFlight = false
    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(YouTubeAuthorizationState())
    val state: StateFlow<YouTubeAuthorizationState> = _state.asStateFlow()

    fun initialize(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    /**
     * Controllo silenzioso all'avvio. Se il grant esiste gia', Google Play
     * Services restituisce un token senza aprire alcuna UI. Se serve il
     * consenso, ci limitiamo a segnare lo stato come non autorizzato.
     */
    fun refreshSilently() {
        val context = appContext ?: return
        if (silentRefreshInFlight) {
            Log.d(TAG, "silentCheck skipped reason=inFlight")
            return
        }
        silentRefreshInFlight = true

        _state.value = _state.value.copy(
            isChecking = true,
            isAuthorizing = false,
            errorMessage = null,
        )

        Identity.getAuthorizationClient(context)
            .authorize(buildAuthorizationRequest(forceAccountSelection = false))
            .addOnSuccessListener { result ->
                silentRefreshInFlight = false
                if (result.hasResolution()) {
                    accessToken = null
                    authorizedAccount = null
                    _state.value = YouTubeAuthorizationState(
                        isChecking = false,
                        isAuthorizing = false,
                        isAuthorized = false,
                        authorizationGeneration = _state.value.authorizationGeneration,
                    )
                    Log.d(TAG, "silentCheck resolutionRequired=true")
                } else {
                    acceptAuthorizationResult(result, source = "silent")
                }
            }
            .addOnFailureListener { error ->
                silentRefreshInFlight = false
                accessToken = null
                authorizedAccount = null
                _state.value = YouTubeAuthorizationState(
                    isChecking = false,
                    isAuthorizing = false,
                    isAuthorized = false,
                    authorizationGeneration = _state.value.authorizationGeneration,
                    errorMessage = friendlyError(error),
                )
                Log.e(TAG, "silentCheck failed message=${error.message}", error)
            }
    }

    /** Richiesta esplicita avviata dalla UI. */
    fun authorize(
        activity: Activity,
        forceAccountSelection: Boolean = false,
        onResolutionRequired: (PendingIntent) -> Unit,
    ) {
        initialize(activity.applicationContext)

        _state.value = _state.value.copy(
            isChecking = false,
            isAuthorizing = true,
            errorMessage = null,
        )

        Identity.getAuthorizationClient(activity)
            .authorize(buildAuthorizationRequest(forceAccountSelection))
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        accessToken = null
                        authorizedAccount = null
                        _state.value = YouTubeAuthorizationState(
                            isAuthorized = false,
                            authorizationGeneration = _state.value.authorizationGeneration,
                            errorMessage = "Google non ha restituito la finestra di autorizzazione.",
                        )
                        Log.e(TAG, "interactive resolution missingPendingIntent")
                    } else {
                        Log.d(
                            TAG,
                            "interactive resolutionRequired=true " +
                                "forceAccountSelection=$forceAccountSelection"
                        )
                        onResolutionRequired(pendingIntent)
                    }
                } else {
                    acceptAuthorizationResult(
                        result,
                        source = if (forceAccountSelection) {
                            "interactive_select_cached"
                        } else {
                            "interactive_cached"
                        },
                    )
                }
            }
            .addOnFailureListener { error ->
                accessToken = null
                authorizedAccount = null
                _state.value = YouTubeAuthorizationState(
                    isAuthorized = false,
                    authorizationGeneration = _state.value.authorizationGeneration,
                    errorMessage = friendlyError(error),
                )
                Log.e(TAG, "interactive failed message=${error.message}", error)
            }
    }

    /** Riceve il risultato dello sheet Google aperto tramite PendingIntent. */
    fun handleAuthorizationResult(
        activity: Activity,
        resultCode: Int,
        data: Intent?,
    ) {
        if (resultCode != Activity.RESULT_OK || data == null) {
            _state.value = _state.value.copy(
                isChecking = false,
                isAuthorizing = false,
                errorMessage = "Accesso Google annullato.",
            )
            Log.d(TAG, "interactiveResult cancelled resultCode=$resultCode")
            return
        }

        runCatching {
            Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
        }.onSuccess { result ->
            acceptAuthorizationResult(result, source = "interactive_result")
        }.onFailure { error ->
            accessToken = null
            authorizedAccount = null
            _state.value = YouTubeAuthorizationState(
                isAuthorized = false,
                authorizationGeneration = _state.value.authorizationGeneration,
                errorMessage = friendlyError(error),
            )
            Log.e(TAG, "interactiveResult failed message=${error.message}", error)
        }
    }

    fun accessTokenOrNull(): String? = accessToken

    /** Revoca l'accesso Google concesso a Lyra e cancella lo stato locale. */
    fun disconnect(onComplete: (Boolean) -> Unit = {}) {
        val context = appContext
        if (context == null) {
            clearLocalAuthorizationState(
                errorMessage = "Disconnessione Google non disponibile."
            )
            onComplete(false)
            return
        }

        _state.value = _state.value.copy(
            isChecking = true,
            isAuthorizing = false,
            errorMessage = null,
        )

        val builder = RevokeAccessRequest.builder()
            .setScopes(requestedScopes)
        authorizedAccount?.let(builder::setAccount)

        Identity.getAuthorizationClient(context)
            .revokeAccess(builder.build())
            .addOnCompleteListener { task ->
                clearLocalAuthorizationState(
                    errorMessage = if (task.isSuccessful) {
                        null
                    } else {
                        "Google non ha confermato la revoca dell'accesso."
                    }
                )
                Log.d(TAG, "disconnect complete success=${task.isSuccessful}")
                onComplete(task.isSuccessful)
            }
    }

    /** Invalida un access token che ha restituito 401. */
    fun invalidateCurrentAccessToken() {
        val context = appContext ?: return
        val invalidToken = accessToken ?: return

        accessToken = null
        _state.value = _state.value.copy(
            isAuthorized = false,
            isChecking = true,
            profileName = null,
            profilePictureUrl = null,
            errorMessage = null,
        )

        val request = ClearTokenRequest.builder()
            .setToken(invalidToken)
            .build()

        Identity.getAuthorizationClient(context)
            .clearToken(request)
            .addOnCompleteListener {
                Log.d(TAG, "tokenCacheCleared success=${it.isSuccessful}")
                refreshSilently()
            }
    }

    private fun buildAuthorizationRequest(
        forceAccountSelection: Boolean,
    ): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes)

        if (forceAccountSelection) {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }

        return builder.build()
    }

    @Suppress("DEPRECATION")
    private fun acceptAuthorizationResult(
        result: AuthorizationResult,
        source: String,
    ) {
        val token = result.accessToken
        val granted = result.grantedScopes
        val youtubeGranted = granted.contains(YOUTUBE_FORCE_SSL_SCOPE)

        if (token.isNullOrBlank() || !youtubeGranted) {
            accessToken = null
            authorizedAccount = null
            _state.value = YouTubeAuthorizationState(
                isAuthorized = false,
                authorizationGeneration = _state.value.authorizationGeneration,
                errorMessage = "Google non ha concesso l'accesso richiesto a YouTube.",
            )
            Log.w(
                TAG,
                "authorizationRejected source=$source token=${!token.isNullOrBlank()} " +
                    "youtubeGranted=$youtubeGranted grantedScopes=${granted.size}"
            )
            return
        }

        accessToken = token
        authorizedAccount = runCatching {
            result.toGoogleSignInAccount()?.account
        }.getOrNull()

        val previousState = _state.value
        val nextGeneration = if (previousState.isAuthorized) {
            previousState.authorizationGeneration
        } else {
            previousState.authorizationGeneration + 1L
        }
        _state.value = previousState.copy(
            isChecking = false,
            isAuthorizing = false,
            isAuthorized = true,
            authorizationGeneration = nextGeneration,
            errorMessage = null,
        )

        // Non loggare mai token, e-mail, nome o identificatori account Google.
        Log.d(
            TAG,
            "authorizationGranted source=$source scope=youtube.force-ssl+profile " +
                "grantedScopes=${granted.size} generation=$nextGeneration"
        )

        // L'avatar e' cosmetico: un errore UserInfo non deve bloccare YouTube.
        profileScope.launch {
            val profile = runCatching { fetchGoogleProfile(token) }
                .onFailure { error ->
                    Log.w(TAG, "profileLoad failed message=${error.message}")
                }
                .getOrNull()
                ?: return@launch

            if (
                _state.value.isAuthorized &&
                _state.value.authorizationGeneration == nextGeneration &&
                accessToken == token
            ) {
                _state.value = _state.value.copy(
                    profileName = profile.name,
                    profilePictureUrl = profile.pictureUrl,
                )
                Log.d(TAG, "profileLoad success picture=${profile.pictureUrl != null}")
            }
        }
    }

    private fun fetchGoogleProfile(token: String): GoogleProfile {
        val connection = URL(USERINFO_ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream?.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }.orEmpty()

            if (status !in 200..299) {
                throw IllegalStateException("Google UserInfo HTTP $status")
            }

            val json = JSONObject(body)
            GoogleProfile(
                name = json.optString("name").takeIf { it.isNotBlank() },
                pictureUrl = json.optString("picture").takeIf { it.isNotBlank() },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun clearLocalAuthorizationState(
        errorMessage: String? = null,
    ) {
        silentRefreshInFlight = false
        accessToken = null
        authorizedAccount = null
        _state.value = YouTubeAuthorizationState(
            isChecking = false,
            isAuthorizing = false,
            isAuthorized = false,
            authorizationGeneration = _state.value.authorizationGeneration + 1L,
            profileName = null,
            profilePictureUrl = null,
            errorMessage = errorMessage,
        )
    }

    private fun friendlyError(error: Throwable): String {
        return error.message
            ?.takeIf { it.isNotBlank() }
            ?: "Autorizzazione Google non disponibile."
    }

    private data class GoogleProfile(
        val name: String?,
        val pictureUrl: String?,
    )
}

data class YouTubeAuthorizationState(
    val isChecking: Boolean = true,
    val isAuthorizing: Boolean = false,
    val isAuthorized: Boolean = false,
    val authorizationGeneration: Long = 0L,
    val profileName: String? = null,
    val profilePictureUrl: String? = null,
    val errorMessage: String? = null,
)

class AuthorizationRequiredException(
    message: String = "Accesso Google richiesto per leggere le playlist YouTube."
) : IllegalStateException(message)
