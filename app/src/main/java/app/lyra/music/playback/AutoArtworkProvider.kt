package app.lyra.music.playback

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import app.lyra.music.diagnostics.LyraLog as Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.max

/**
 * Artwork provider per Android Auto / Automotive OS.
 *
 * Android for Cars richiede artwork esposto tramite URI locale content:// o
 * android.resource://. Le thumbnail YouTube sono invece URL https: le
 * registriamo con un id opaco, le scarichiamo solo quando l'host le richiede e
 * le conserviamo nella cache dell'app.
 *
 * Se Android Auto passa ContentResolver.EXTRA_SIZE, generiamo una variante
 * esattamente di quella dimensione (entro limiti di sicurezza). In assenza di
 * una misura richiesta usiamo un quadrato 512x512, sufficiente come sorgente
 * ad alta qualita' lasciando comunque all'host l'ultima parola sul layout.
 */
class AutoArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw FileNotFoundException("Lyra artwork e' disponibile in sola lettura")
        }
        val file = resolveArtworkFile(uri, requestedSize = null, signal = null)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openTypedAssetFile(
        uri: Uri,
        mimeTypeFilter: String,
        opts: Bundle?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        if (!mimeTypeFilterMatches(mimeTypeFilter)) {
            throw FileNotFoundException("MIME non supportato: $mimeTypeFilter")
        }

        signal?.throwIfCanceled()
        val file = resolveArtworkFile(
            uri = uri,
            requestedSize = requestedSize(opts),
            signal = signal,
        )
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        return AssetFileDescriptor(descriptor, 0L, file.length())
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolveArtworkFile(
        uri: Uri,
        requestedSize: Point?,
        signal: CancellationSignal?,
    ): File {
        val providerContext = context
            ?: throw FileNotFoundException("ContentProvider non inizializzato")
        val artworkId = uri.lastPathSegment
            ?.takeIf { it.matches(ARTWORK_ID_REGEX) }
            ?: throw FileNotFoundException("Artwork id non valido")

        val preferences = providerContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sourceUrl = preferences.getString(sourceKey(artworkId), null)
            ?: preferences.getString(artworkId, null)
            ?: throw FileNotFoundException("Artwork non registrato")
        val fallbackUrl = preferences.getString(fallbackKey(artworkId), null)

        val target = normalizeTargetSize(requestedSize)
        val cacheDirectory = File(providerContext.cacheDir, CACHE_DIRECTORY).apply {
            mkdirs()
        }
        val output = File(cacheDirectory, "${artworkId}_${target.x}x${target.y}.jpg")
        if (output.isFile && output.length() > 0L) return output

        synchronized(lockFor(artworkId, target)) {
            if (output.isFile && output.length() > 0L) return output

            signal?.throwIfCanceled()
            val primaryAttempt = runCatching {
                val source = ensureSourceFile(
                    context = providerContext,
                    artworkId = artworkId,
                    sourceUri = sourceUrl,
                    signal = signal,
                )
                signal?.throwIfCanceled()
                createSizedArtwork(
                    source = source,
                    destination = output,
                    width = target.x,
                    height = target.y,
                )
            }

            if (primaryAttempt.isFailure) {
                val primaryError = primaryAttempt.exceptionOrNull()
                    ?: FileNotFoundException("Artwork principale non disponibile")
                val fallback = fallbackUrl ?: throw primaryError
                Log.w(
                    "LYRA_AUTO",
                    "artworkPrimaryFailed id=$artworkId; usingFallback message=${primaryError.message}",
                )
                val fallbackSource = ensureSourceFile(
                    context = providerContext,
                    artworkId = "${artworkId}_fallback",
                    sourceUri = fallback,
                    signal = signal,
                )
                signal?.throwIfCanceled()
                createSizedArtwork(
                    source = fallbackSource,
                    destination = output,
                    width = target.x,
                    height = target.y,
                )
            }
        }

        return output
    }

    private fun ensureSourceFile(
        context: Context,
        artworkId: String,
        sourceUri: String,
        signal: CancellationSignal?,
    ): File {
        val directory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
        val source = File(directory, "${artworkId}_source")
        if (source.isFile && source.length() > 0L) return source

        val parsed = Uri.parse(sourceUri)
        val temporary = File(directory, "${artworkId}_source.tmp")
        var connection: HttpURLConnection? = null
        try {
            val input = when (parsed.scheme) {
                "https" -> {
                    connection = (URL(sourceUri).openConnection() as HttpURLConnection).apply {
                        connectTimeout = NETWORK_TIMEOUT_MS
                        readTimeout = NETWORK_TIMEOUT_MS
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "Lyra-AndroidAuto-Artwork/1")
                    }
                    connection.connect()
                    if (connection.responseCode !in 200..299) {
                        throw FileNotFoundException("HTTP artwork ${connection.responseCode}")
                    }
                    if (connection.contentLengthLong > MAX_DOWNLOAD_BYTES) {
                        throw FileNotFoundException("Artwork remoto troppo grande")
                    }
                    connection.inputStream
                }
                ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_ANDROID_RESOURCE -> {
                    context.contentResolver.openInputStream(parsed)
                        ?: throw FileNotFoundException("Artwork locale non disponibile")
                }
                else -> throw FileNotFoundException("Schema artwork non supportato")
            }

            var total = 0L
            input.use { sourceInput ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        signal?.throwIfCanceled()
                        val read = sourceInput.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw FileNotFoundException("Artwork supera il limite")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            if (temporary.length() == 0L) {
                throw FileNotFoundException("Artwork remoto vuoto")
            }

            if (!temporary.renameTo(source)) {
                temporary.copyTo(source, overwrite = true)
                temporary.delete()
            }
            Log.d("LYRA_AUTO", "artworkCached id=$artworkId bytes=${source.length()}")
            return source
        } catch (error: Exception) {
            temporary.delete()
            if (error is FileNotFoundException) throw error
            throw FileNotFoundException("Impossibile recuperare artwork: ${error.message}")
                .also { it.initCause(error) }
        } finally {
            connection?.disconnect()
        }
    }

    private fun createSizedArtwork(
        source: File,
        destination: File,
        width: Int,
        height: Int,
    ) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw FileNotFoundException("Artwork non decodificabile")
        }

        var sampleSize = 1
        while (
            bounds.outWidth / (sampleSize * 2) >= width &&
            bounds.outHeight / (sampleSize * 2) >= height
        ) {
            sampleSize *= 2
        }

        val bitmap = BitmapFactory.decodeFile(
            source.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw FileNotFoundException("Artwork non decodificabile")

        val cropped = centerCrop(bitmap, width, height)
        val temporary = File(destination.parentFile, destination.name + ".tmp")
        try {
            FileOutputStream(temporary).use { output ->
                if (!cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw FileNotFoundException("Impossibile codificare artwork")
                }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            if (cropped !== bitmap) cropped.recycle()
            bitmap.recycle()
            temporary.delete()
        }
    }

    private fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source

        val scale = max(
            targetWidth.toFloat() / source.width.toFloat(),
            targetHeight.toFloat() / source.height.toFloat(),
        )
        val scaledWidth = ceil(source.width * scale).toInt().coerceAtLeast(targetWidth)
        val scaledHeight = ceil(source.height * scale).toInt().coerceAtLeast(targetHeight)
        val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
        val left = ((scaledWidth - targetWidth) / 2).coerceAtLeast(0)
        val top = ((scaledHeight - targetHeight) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(scaled, left, top, targetWidth, targetHeight)
        if (scaled !== source && scaled !== cropped) scaled.recycle()
        return cropped
    }

    @Suppress("DEPRECATION")
    private fun requestedSize(opts: Bundle?): Point? {
        return opts?.getParcelable<Point>(ContentResolver.EXTRA_SIZE)
    }

    private fun normalizeTargetSize(requested: Point?): Point {
        val width = requested?.x
            ?.takeIf { it > 0 }
            ?.coerceIn(MIN_ARTWORK_PX, MAX_ARTWORK_PX)
            ?: DEFAULT_ARTWORK_PX
        val height = requested?.y
            ?.takeIf { it > 0 }
            ?.coerceIn(MIN_ARTWORK_PX, MAX_ARTWORK_PX)
            ?: DEFAULT_ARTWORK_PX
        return Point(width, height)
    }

    private fun mimeTypeFilterMatches(filter: String): Boolean {
        return filter == "*/*" || filter == "image/*" || filter == MIME_TYPE
    }

    private fun lockFor(id: String, size: Point): Any {
        val key = "$id:${size.x}x${size.y}"
        synchronized(ARTWORK_LOCKS) {
            return ARTWORK_LOCKS.getOrPut(key) { Any() }
        }
    }

    companion object {
        private const val PREFS_NAME = "lyra_auto_artwork_registry"
        private const val CACHE_DIRECTORY = "auto_artwork"
        private const val MIME_TYPE = "image/jpeg"
        private const val DEFAULT_ARTWORK_PX = 512
        private const val MIN_ARTWORK_PX = 64
        private const val MAX_ARTWORK_PX = 1024
        private const val JPEG_QUALITY = 90
        private const val NETWORK_TIMEOUT_MS = 8_000
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L
        private val ARTWORK_ID_REGEX = Regex("[a-f0-9]{64}")
        private val ARTWORK_LOCKS = mutableMapOf<String, Any>()

        /**
         * Registra una thumbnail remota e restituisce subito l'URI locale da
         * inserire nei metadata Media3. Nessun download avviene qui.
         */
        fun register(
            context: Context,
            remoteUrl: String?,
            fallbackUrl: String? = null,
        ): Uri? {
            val source = remoteUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: fallbackUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val fallback = fallbackUrl?.trim()?.takeIf { it.isNotEmpty() }

            if (!isSupportedArtworkUri(source)) return null
            if (fallback != null && !isSupportedArtworkUri(fallback)) return null

            val id = sha256(source + "|" + fallback.orEmpty())
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(sourceKey(id), source)
                .apply {
                    if (fallback != null) putString(fallbackKey(id), fallback)
                    else remove(fallbackKey(id))
                }
                .apply()

            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(context.packageName + AUTHORITY_SUFFIX)
                .appendPath(id)
                .build()
        }

        private fun isSupportedArtworkUri(value: String): Boolean {
            val parsed = Uri.parse(value)
            val supported = parsed.scheme == "https" ||
                parsed.scheme == ContentResolver.SCHEME_CONTENT ||
                parsed.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE
            if (!supported) return false
            return parsed.scheme != "https" || !parsed.host.isNullOrBlank()
        }

        private fun sourceKey(id: String): String = "$id:source"

        private fun fallbackKey(id: String): String = "$id:fallback"

        private fun sha256(value: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }

        private const val AUTHORITY_SUFFIX = ".autoartwork"
    }
}
