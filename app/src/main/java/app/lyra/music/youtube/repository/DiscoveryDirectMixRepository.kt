package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Song
import app.lyra.music.core.model.DiscoverMenuCatalog
import app.lyra.music.personalization.RecommendationEngine
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Scopri senza scelta di playlist: prepara code reali di canzoni da YT Music.
 * Per i Decenni confronta l'anno dell'edizione album con il periodo, scartando
 * ristampe e varianti riconoscibili. L'anno originale del SINGOLO brano non e'
 * certificabile dall'API e non viene mai dedotto dalla query testuale.
 */
class DiscoveryDirectMixRepository {
    data class Result(val title: String, val songs: List<Song>)
    private val music = YouTubeMusicRepository()

    suspend fun build(folderId: String, groupId: String): Result {
        val title = if (
            folderId == DiscoverMenuCatalog.NEW_RELEASES_FOLDER_ID &&
            groupId == DiscoverMenuCatalog.NEW_RELEASES_GROUP_ID
        ) {
            "Novità"
        } else {
            music.getDiscoveryGroups(folderId).firstOrNull { it.id == groupId }?.title ?: "Scopri"
        }
        val selected = when (folderId) {
            "decenni" -> decadeSongs(groupId)
            "novita" -> NewReleaseDiscovery.songsForGroup(groupId)
            "nazioni" -> if (groupId == "italia") italianSongs() else emptyList()
            "mood", "attivita" -> playlistSourceSongs(folderId, groupId)
            else -> emptyList()
        }
        if (selected.isEmpty()) throw IllegalStateException(
            if (folderId == "decenni" || folderId == "novita")
                "Nessun brano con anno verificato disponibile al momento. Riprova più tardi."
            else "Nessun brano musicale coerente disponibile al momento."
        )
        // Ricalcolo alla pressione: le preferenze locali/cloud già sincronizzate
        // concorrono al ranking, ma non bypassano i controlli sui periodi.
        val ranked = RecommendationEngine.rank(
            selected.distinctBy { it.id },
            RecommendationEngine.SessionContext(title = "Scopri · $title", contextTag = tag(folderId, groupId)),
        ).take(QUEUE_LIMIT)
        return Result("Scopri · $title", ranked)
    }

    private fun tag(folder: String, group: String): String? = when (folder) {
        "mood" -> group
        "attivita" -> "energia"
        "novita" -> "novita"
        "nazioni" -> "italia"
        "decenni" -> group
        else -> null
    }

    private suspend fun playlistSourceSongs(folder: String, group: String): List<Song> {
        val section = music.getDiscoveryGroup(folder, group)
        // Usa diverse playlist di YTMusic come sorgenti, senza mostrarle.
        val chosen = section.playlists.shuffled().take(4)
        val gate = Semaphore(2)
        val batches = coroutineScope {
            chosen.map { playlist ->
                async {
                    gate.withPermit {
                        runCatching {
                            withTimeoutOrNull(10_000L) { music.getPlaylist(playlist.id) }
                        }.getOrNull()?.songs.orEmpty().shuffled().take(25)
                    }
                }
            }.awaitAll()
        }
        return batches.flatten()
            .filter { it.id.isNotBlank() && it.artist.isNotBlank() }
            .distinctBy { it.id }
    }

    private suspend fun italianSongs(): List<Song> {
        val queries = listOf(
            "canzoni italiane più ascoltate", "pop italiano classici", "rock italiano successi",
            "cantautori italiani canzoni", "rap italiano hit", "indie italiano canzoni",
        ).shuffled().take(4)
        val items = queries.flatMap { searchSongs(it) }.distinctBy { it.id }
        return items.filter { !it.isEpisode && it.artists.isNotEmpty() }
            .shuffled().map(::toSong).take(QUEUE_LIMIT)
    }

    private suspend fun decadeSongs(groupId: String): List<Song> {
        val decadeStart = when (groupId) {
            "80s" -> 1980
            "90s" -> 1990
            "2000s" -> 2000
            "2010s" -> 2010
            else -> throw IllegalArgumentException("Decennio non riconosciuto: $groupId")
        }
        val sampledYears = (decadeStart..decadeStart + 9).shuffled().take(5)
        val queries = sampledYears.flatMap { year ->
            listOf("canzoni italiane $year", "top songs international $year")
        }.shuffled().take(6)
        val searches = Semaphore(3)
        val found = coroutineScope {
            queries.map { query ->
                async { searches.withPermit { searchSongs(query) } }
            }.awaitAll().flatten()
        }
        val candidates = found
            .asSequence().filter {
                !it.isEpisode && it.id.isNotBlank() && it.artists.any { artist -> artist.name.isNotBlank() }
            }
            .distinctBy { it.id }.take(MAX_ALBUM_CHECKS).toList()
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        return coroutineScope {
            candidates.map { candidate ->
                async {
                    val album = candidate.album?.takeIf { it.id.isNotBlank() } ?: return@async null
                    val evidence = YouTubeAlbumEvidence.get(album.id)
                    val decision = ReleaseMetadataPolicy.evaluate(
                        songTitle = candidate.title,
                        evidence = evidence,
                        startYear = decadeStart,
                        endYear = decadeStart + 9,
                        knownCurrentYear = thisYear,
                    )
                    if (!decision.accepted) return@async null
                    toSong(candidate, evidence)
                }
            }.awaitAll().filterNotNull()
        }.shuffled()
    }

    private suspend fun searchSongs(query: String): List<SongItem> = runCatching {
        withTimeoutOrNull(7_000L) {
            YouTube.search(query, filter = YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        }?.items.orEmpty().filterIsInstance<SongItem>()
    }.getOrDefault(emptyList())

    private fun toSong(
        item: SongItem,
        edition: ReleaseMetadataPolicy.Evidence? = null,
    ) = Song(
        id = item.id,
        title = item.title,
        artist = item.artists.map { it.name.trim() }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }.joinToString(", "),
        durationSeconds = item.duration?.toLong() ?: 0L,
        thumbnailUrl = item.thumbnail.takeIf { it.isNotBlank() }
            ?: edition?.album?.artworkUrl,
        album = item.album?.name?.takeIf { it.isNotBlank() }
            ?: edition?.album?.title,
    )

    companion object {
        private const val QUEUE_LIMIT = 60
        private const val MAX_ALBUM_CHECKS = 36

        fun isDirectGroup(folder: String, group: String): Boolean = when (folder) {
            "decenni", "novita", "mood" -> true
            "attivita" -> group == "workout"
            "nazioni" -> group == "italia"
            else -> false
        }
    }
}
