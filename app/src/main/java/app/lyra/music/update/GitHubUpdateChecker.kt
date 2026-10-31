package app.lyra.music.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.pm.PackageInfoCompat
import app.lyra.music.diagnostics.LyraLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Controllo aggiornamenti fuori dal Play Store tramite GitHub Releases.
 *
 * Il repository pubblico non richiede token. Se la release contiene
 * `update.json`, Lyra usa soprattutto `versionCode`; in sua assenza usa il tag
 * semantico (es. v0.2.0) come fallback. L'installazione resta sempre affidata
 * all'utente tramite il browser/installer Android.
 */
object GitHubUpdateChecker {

    private const val TAG = "LYRA_UPDATE"
    private const val OWNER = "chrelyon"
    private const val REPOSITORY = "Lyra-Releases"
    private const val API_URL =
        "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    private const val RELEASES_URL =
        "https://github.com/$OWNER/$REPOSITORY/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val versionCode: Long?,
        val notes: String?,
        val downloadUrl: String,
        val releaseUrl: String,
        val apkName: String?,
        val sha256: String?,
    )

    sealed interface Result {
        data class Available(val update: UpdateInfo) : Result
        data class UpToDate(val currentVersionName: String) : Result
        data object NoPublishedRelease : Result
        data class Error(val message: String) : Result
    }

    /**
     * Controllo automatico eseguito ad ogni apertura reale della UI Lyra.
     * Non viene applicato un intervallo di 24 ore: il risultato remoto viene
     * confrontato subito con il versionCode installato.
     */
    suspend fun checkAutomatically(context: Context): Result = checkNow(context)

    suspend fun checkNow(context: Context): Result = withContext(Dispatchers.IO) {
        runCatching {
            val releaseResponse = getJson(API_URL)
            if (releaseResponse.statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                return@withContext Result.NoPublishedRelease
            }
            if (releaseResponse.statusCode !in 200..299) {
                error("GitHub HTTP ${releaseResponse.statusCode}")
            }

            val release = JSONObject(releaseResponse.body)
            val releaseUrl = release.optString("html_url")
                .takeIf { it.isNotBlank() }
                ?: RELEASES_URL
            val tagName = release.optString("tag_name")
            val releaseNotes = release.optString("body").takeIf { it.isNotBlank() }
            val assets = release.optJSONArray("assets")

            var apkUrl: String? = null
            var apkName: String? = null
            var metadataUrl: String? = null

            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    val browserUrl = asset.optString("browser_download_url")
                    if (browserUrl.isBlank()) continue

                    when {
                        name.equals("update.json", ignoreCase = true) -> {
                            metadataUrl = browserUrl
                        }

                        name.endsWith(".apk", ignoreCase = true) && apkUrl == null -> {
                            apkUrl = browserUrl
                            apkName = name
                        }
                    }
                }
            }

            val metadata = metadataUrl?.let { url ->
                runCatching {
                    val response = getJson(url)
                    if (response.statusCode in 200..299) JSONObject(response.body) else null
                }.onFailure { error ->
                    LyraLog.w(TAG, "updateMetadata failed message=${error.message}")
                }.getOrNull()
            }

            val remoteVersionCode = metadata
                ?.optLong("versionCode", -1L)
                ?.takeIf { it >= 0L }
            val remoteVersionName = metadata
                ?.optString("versionName")
                ?.takeIf { it.isNotBlank() }
                ?: tagName.removePrefix("v").ifBlank { "sconosciuta" }
            val notes = metadata
                ?.optString("notes")
                ?.takeIf { it.isNotBlank() }
                ?: releaseNotes
            val sha256 = metadata
                ?.optString("sha256")
                ?.takeIf { it.isNotBlank() }

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            val currentVersionName = packageInfo.versionName.orEmpty()

            val isNewer = if (remoteVersionCode != null) {
                remoteVersionCode > currentVersionCode
            } else {
                compareSemanticVersions(remoteVersionName, currentVersionName) > 0
            }

            LyraLog.d(
                TAG,
                "check currentCode=$currentVersionCode remoteCode=${remoteVersionCode ?: -1L} " +
                    "available=$isNewer"
            )

            if (!isNewer) {
                Result.UpToDate(currentVersionName)
            } else {
                Result.Available(
                    UpdateInfo(
                        versionName = remoteVersionName,
                        versionCode = remoteVersionCode,
                        notes = notes,
                        downloadUrl = apkUrl ?: releaseUrl,
                        releaseUrl = releaseUrl,
                        apkName = apkName,
                        sha256 = sha256,
                    )
                )
            }
        }.getOrElse { error ->
            LyraLog.w(TAG, "check failed message=${error.message}")
            Result.Error("Controllo aggiornamenti non riuscito. Riprova più tardi.")
        }
    }

    fun openUpdate(context: Context, update: UpdateInfo) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openReleasesPage(context: Context) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun getJson(url: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        connection.setRequestProperty("User-Agent", "Lyra-Android")

        return try {
            val status = connection.responseCode
            val input = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val body = input?.use { stream ->
                BufferedReader(
                    InputStreamReader(stream, StandardCharsets.UTF_8)
                ).readText()
            }.orEmpty()
            HttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun compareSemanticVersions(remote: String, current: String): Int {
        val left = numericVersionParts(remote)
        val right = numericVersionParts(current)
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val l = left.getOrElse(index) { 0 }
            val r = right.getOrElse(index) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    private fun numericVersionParts(value: String): List<Int> {
        val clean = value.trim().removePrefix("v")
        return Regex("\\d+")
            .findAll(clean.substringBefore('-'))
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
            .ifEmpty { listOf(0) }
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String,
    )
}
