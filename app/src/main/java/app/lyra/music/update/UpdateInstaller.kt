package app.lyra.music.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import app.lyra.music.diagnostics.LyraLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Scarica gli APK di aggiornamento nella cache privata di Lyra e apre
 * l'installer Android tramite FileProvider.
 *
 * Nessun APK viene scritto nella cartella Download. Dopo un aggiornamento
 * riuscito, il primo avvio della nuova versione elimina automaticamente il
 * file temporaneo rimasto in cache.
 */
object UpdateInstaller {

    private const val TAG = "LYRA_UPDATE"
    private const val CACHE_DIRECTORY = "updates"
    private const val PREFS = "lyra_update_installer"
    private const val PREF_PENDING_VERSION_CODE = "pending_version_code"
    private const val PREF_PENDING_FILE = "pending_file"

    sealed interface Result {
        data object InstallerOpened : Result
        data object PermissionRequired : Result
        data class Error(val message: String) : Result
    }

    suspend fun downloadAndOpenInstaller(
        context: Context,
        update: GitHubUpdateChecker.UpdateInfo,
        onProgress: (Int) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            val apkName = update.apkName
                ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
                ?: return@withContext Result.Error(
                    "La release non contiene un APK installabile."
                )

            val updateDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply {
                mkdirs()
            }
            val safeName = apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val apkFile = File(updateDirectory, safeName)

            val canReuse = apkFile.isFile && apkFile.length() > 0L &&
                verifySha256IfAvailable(apkFile, update.sha256)

            if (!canReuse) {
                apkFile.delete()
                downloadApk(
                    url = update.downloadUrl,
                    destination = apkFile,
                    onProgress = onProgress,
                )

                if (!verifySha256IfAvailable(apkFile, update.sha256)) {
                    apkFile.delete()
                    return@withContext Result.Error(
                        "Il controllo SHA-256 dell'aggiornamento non coincide. Download annullato."
                    )
                }
            } else {
                onProgress(100)
                LyraLog.d(TAG, "reusing cached update file=${apkFile.name}")
            }

            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREF_PENDING_VERSION_CODE, update.versionCode ?: -1L)
                .putString(PREF_PENDING_FILE, apkFile.absolutePath)
                .apply()

            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                return@withContext Result.PermissionRequired
            }

            withContext(Dispatchers.Main) {
                openInstaller(context, apkFile)
            }
            Result.InstallerOpened
        }.getOrElse { error ->
            LyraLog.w(TAG, "download/install failed message=${error.message}")
            Result.Error(
                error.message?.takeIf { it.isNotBlank() }
                    ?: "Download dell'aggiornamento non riuscito."
            )
        }
    }

    fun openUnknownSourcesSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Da chiamare all'avvio. Se il versionCode installato ha raggiunto quello
     * dell'APK scaricato, l'update e' riuscito e la cache puo' essere rimossa.
     */
    fun cleanupAfterSuccessfulUpdate(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pendingVersionCode = prefs.getLong(PREF_PENDING_VERSION_CODE, -1L)
            if (pendingVersionCode <= 0L) return

            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val currentVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
            if (currentVersionCode < pendingVersionCode) return

            val pendingFile = prefs.getString(PREF_PENDING_FILE, null)
                ?.let(::File)
            pendingFile?.takeIf { it.exists() }?.delete()

            File(context.cacheDir, CACHE_DIRECTORY)
                .takeIf { it.exists() }
                ?.listFiles()
                ?.forEach { file ->
                    if (file.isFile && file.extension.equals("apk", ignoreCase = true)) {
                        file.delete()
                    }
                }

            prefs.edit().clear().apply()
            LyraLog.d(
                TAG,
                "temporary update cleaned currentCode=$currentVersionCode " +
                    "pendingCode=$pendingVersionCode"
            )
        }.onFailure { error ->
            LyraLog.w(TAG, "cleanup failed message=${error.message}")
        }
    }

    private fun openInstaller(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        LyraLog.d(TAG, "installer opened file=${apkFile.name}")
    }

    private fun downloadApk(
        url: String,
        destination: File,
        onProgress: (Int) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "Lyra-Android")

        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                error("Download GitHub HTTP $status")
            }

            val totalBytes = connection.contentLengthLong.takeIf { it > 0L }
            var copied = 0L
            var lastProgress = -1

            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read

                        if (totalBytes != null) {
                            val progress = ((copied * 100L) / totalBytes)
                                .toInt()
                                .coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                    output.flush()
                }
            }
            onProgress(100)
            LyraLog.d(TAG, "download completed bytes=$copied file=${destination.name}")
        } finally {
            connection.disconnect()
        }
    }

    private fun verifySha256IfAvailable(file: File, expected: String?): Boolean {
        val normalizedExpected = expected
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: return true

        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val matches = actual == normalizedExpected
        LyraLog.d(TAG, "sha256 verified=$matches file=${file.name}")
        return matches
    }
}
