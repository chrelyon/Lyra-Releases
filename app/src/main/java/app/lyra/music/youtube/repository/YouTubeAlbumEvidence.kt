package app.lyra.music.youtube.repository

import com.metrolist.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Fonte UNICA per Decenni e Novità. Condivide query, limiti di concorrenza e
 * cache TTL, senza conservare dati personali o alterare l'archivio remoto Ubuntu.
 */
internal object YouTubeAlbumEvidence {
    private const val TIMEOUT_MS = 4_000L
    private const val POSITIVE_TTL_MS = 6L * 60L * 60L * 1_000L
    private const val NEGATIVE_TTL_MS = 10L * 60L * 1_000L
    private const val MAX_ENTRIES = 512
    private val permits = Semaphore(4)
    private data class CacheEntry(
        val value: ReleaseMetadataPolicy.Evidence?,
        val expiresAtMs: Long,
    )
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun get(albumId: String): ReleaseMetadataPolicy.Evidence? {
        if (albumId.isBlank()) return null
        val now = System.currentTimeMillis()
        cache[albumId]?.takeIf { it.expiresAtMs > now }?.let { return it.value }
        val found = permits.withPermit {
            // Ricontrollo dopo l'attesa: un'altra ricerca potrebbe aver popolato la cache.
            cache[albumId]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let {
                return@withPermit it.value
            }
            try {
                withTimeoutOrNull(TIMEOUT_MS) {
                    val page = YouTube.album(albumId, withSongs = false).getOrThrow()
                    ReleaseMetadataPolicy.Evidence(
                        album = ReleaseMetadataPolicy.Edition(
                            title = page.album.title,
                            year = page.album.year,
                            artists = page.album.artists.orEmpty().map { it.name },
                            artworkUrl = page.album.thumbnail.takeIf { it.isNotBlank() },
                        ),
                        otherVersions = page.otherVersions.map { version ->
                            ReleaseMetadataPolicy.Edition(
                                title = version.title,
                                year = version.year,
                                artists = version.artists.orEmpty().map { it.name },
                            )
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        if (cache.size >= MAX_ENTRIES) cache.clear()
        cache[albumId] = CacheEntry(
            value = found,
            expiresAtMs = System.currentTimeMillis() +
                (if (found == null) NEGATIVE_TTL_MS else POSITIVE_TTL_MS),
        )
        return found
    }

    fun clearCache() = cache.clear()
}
