package app.lyra.music.playback

import android.net.Uri
import app.lyra.music.audio.AudioSettings
import app.lyra.music.diagnostics.LyraLog as Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

/**
 * URI interna usata da Media3 per rappresentare un brano Lyra senza
 * materializzare in anticipo l'URL firmato di YouTube.
 *
 * Questo consente al player di possedere una playlist completa (quindi
 * NEXT/PREV nativi per Android Auto, Bluetooth e System UI) mentre ogni
 * sorgente viene risolta solo quando ExoPlayer prova realmente ad aprirla.
 */
object LyraDeferredPlayback {

    private const val SCHEME = "lyra"
    private const val AUTHORITY = "stream"

    fun uri(videoId: String): Uri = Uri.Builder()
        .scheme(SCHEME)
        .authority(AUTHORITY)
        .appendPath(videoId)
        .build()

    fun videoIdOrNull(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.authority != AUTHORITY) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}

/**
 * Risolve le URI lyra://stream/... nel momento in cui il DataSource viene
 * aperto. ResolvingDataSource consente esplicitamente una risoluzione
 * bloccante; usiamo quindi il repository esistente su Dispatchers.IO.
 *
 * Le sorgenti vengono tenute in una cache breve per evitare di ripetere la
 * negoziazione InnerTube per seek/range ravvicinati, ma vengono comunque
 * rinnovate abbastanza spesso da non dipendere a lungo da URL firmati.
 */
@OptIn(UnstableApi::class)
class LyraPlaybackDataSpecResolver(
    private val playbackRepository: PlaybackRepository,
    private val onSourceResolved: (PlaybackSource) -> Unit = {},
) : ResolvingDataSource.Resolver {

    private data class CachedSource(
        val source: PlaybackSource,
        val resolvedAtMs: Long,
        val quality: AudioSettings.StreamingQuality,
    )

    private val cache = ConcurrentHashMap<String, CachedSource>()
    private val resolveLocks = ConcurrentHashMap<String, Any>()

    /** Prerisolve sorgenti future riutilizzando cache, lock e TTL del player.
     * Non effettua download, non registra l'URL firmato nei log.
     */
    suspend fun prefetchSource(videoId: String) {
        withContext(Dispatchers.IO) { sourceFor(videoId) }
    }

    /** Condivide il risultato del prefetch anche con i comandi di navigazione del servizio. */
    suspend fun preparedPlaybackSource(videoId: String): PlaybackSource =
        withContext(Dispatchers.IO) { sourceFor(videoId) }

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val videoId = LyraDeferredPlayback.videoIdOrNull(dataSpec.uri)
            ?: return dataSpec

        val source = runCatching {
            sourceFor(videoId)
        }.getOrElse { error ->
            throw IOException(
                "Impossibile risolvere la sorgente audio Lyra per $videoId",
                error,
            )
        }

        onSourceResolved(source)

        Log.d(
            "LYRA_STREAM",
            "deferredResolved videoId=$videoId client=${source.clientName} " +
                "mime=${source.mimeType} bitrate=${source.bitrate}",
        )

        return dataSpec.buildUpon()
            .setUri(source.url)
            .setKey(dataSpec.key ?: "lyra:$videoId")
            .build()
    }

    override fun resolveReportedUri(uri: Uri): Uri = uri

    private fun sourceFor(videoId: String): PlaybackSource {
        val now = System.currentTimeMillis()
        val quality = AudioSettings.streamingQuality.value
        cache[videoId]
            ?.takeIf { now - it.resolvedAtMs < SOURCE_CACHE_MS && it.quality == quality }
            ?.let { return it.source }

        val lock = resolveLocks.getOrPut(videoId) { Any() }
        return synchronized(lock) {
            val secondNow = System.currentTimeMillis()
            cache[videoId]
                ?.takeIf { secondNow - it.resolvedAtMs < SOURCE_CACHE_MS && it.quality == quality }
                ?.source
                ?: runBlocking(Dispatchers.IO) {
                    playbackRepository.getPlaybackSource(videoId)
                }.also { source ->
                    cache[videoId] = CachedSource(
                        source = source,
                        resolvedAtMs = System.currentTimeMillis(),
                        quality = quality,
                    )
                    trimCache()
                }
        }
    }

    private fun trimCache() {
        if (cache.size <= MAX_CACHE_ITEMS) return
        val oldest = cache.entries
            .sortedBy { it.value.resolvedAtMs }
            .take(cache.size - MAX_CACHE_ITEMS)
        oldest.forEach { entry ->
            cache.remove(entry.key, entry.value)
            resolveLocks.remove(entry.key)
        }
    }

    private companion object {
        // Sufficiente per seek/range ravvicinati, breve rispetto alla normale
        // validita' degli URL firmati restituiti dal resolver YouTube.
        const val SOURCE_CACHE_MS = 10 * 60 * 1_000L
        const val MAX_CACHE_ITEMS = 24
    }
}
