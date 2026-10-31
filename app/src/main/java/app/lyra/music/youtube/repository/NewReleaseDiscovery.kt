package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song
import app.lyra.music.personalization.RecommendationEngine
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/**
 * Playlist VIRTUALI costruite con canzoni. Per evitare false "nuove uscite"
 * viene controllato l'anno dell'EDIZIONE album e vengono scartate ristampe,
 * compilation e altre versioni storiche note. L'anno della prima uscita del
 * singolo brano NON e' disponibile in modo affidabile e non viene inventato.
 * Il paese viene soltanto suggerito dalle query: YT Music non espone la
 * nazionalita' verificata dell'artista su ogni SongItem.
 */
internal object NewReleaseDiscovery {
    const val PREFIX = "lyra-new-release:"
    private const val CACHE_TTL_MS = 30L * 60L * 1000L
    private const val SEARCH_TIMEOUT_MS = 7_000L
    private const val MIN_TRACKS = 3
    private const val MAX_TRACKS = 18
    private const val MAX_ALBUM_CHECKS = 14
    private val contentCache = java.util.concurrent.ConcurrentHashMap<String, CachedContent>()

    private data class CachedContent(val content: PlaylistContent, val atMs: Long)
    private data class Definition(val id: String, val title: String, val query: String)

    private fun definitions(groupId: String, year: Int): List<Definition> = when (groupId) {
        "hit-italiane" -> listOf(
            Definition("hit-pop-it", "Hit italiane · Pop", "nuove hit italiane pop $year"),
            Definition("hit-urban-it", "Hit italiane · Rap e Indie", "nuove canzoni italiane rap indie $year"),
        )
        "hit-straniere" -> listOf(
            Definition("hit-global-pop", "Hit straniere · Pop", "new international pop hits $year"),
            Definition("hit-global-dance", "Hit straniere · Dance", "new global dance hits $year"),
        )
        "nuove-uscite" -> listOf(
            Definition("italia", "Nuove uscite · Italia", "nuove uscite musica italiana $year"),
            Definition("internazionali", "Nuove uscite · Internazionali", "new music releases $year international"),
        )
        "hit-momento" -> listOf(
            Definition("pop", "Hit recenti · Pop", "new pop songs $year releases"),
            Definition("dance", "Hit recenti · Dance", "new dance electronic songs $year releases"),
        )
        "novita-italiane" -> listOf(
            Definition("pop-italia", "Novità italiane · Pop", "nuove canzoni pop italiane $year"),
            Definition("rap-italia", "Novità italiane · Rap e Indie", "nuove canzoni rap indie italiane $year"),
        )
        "virali" -> listOf(
            Definition("virali-italia", "Virali recenti · Italia", "canzoni virali italiane nuove $year"),
            Definition("virali-global", "Virali recenti · Internazionali", "new trending viral songs $year"),
        )
        else -> emptyList()
    }

    /** Compatibilità: verifica solamente la data dell'edizione (non l'originale). */
    fun isCurrentRelease(albumYear: Int?, currentYear: Int): Boolean = albumYear == currentYear

    suspend fun groupPlaylists(
        groupId: String,
        mode: DiscoverySettings.RegionMode,
        forceRefresh: Boolean = false,
    ): List<Playlist> {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val seenSongs = mutableSetOf<String>()
        val results = mutableListOf<Playlist>()
        // L'ordine rende stabile la scelta ed evita di riciclare lo stesso video
        // nelle due raccolte dello stesso gruppo.
        for (definition in definitions(groupId, year)) {
            if (mode == DiscoverySettings.RegionMode.ITALY &&
                definition.id in setOf("internazionali", "pop", "dance", "virali-global")
            ) continue
            val id = "$PREFIX$groupId:${definition.id}:$year"
            if (forceRefresh) {
                contentCache.remove(id)
                YouTubeAlbumEvidence.clearCache()
            }
            val cached = contentCache[id]?.takeIf {
                System.currentTimeMillis() - it.atMs < CACHE_TTL_MS
            }?.content
            val content = cached ?: buildContent(id, definition, year).also {
                contentCache[id] = CachedContent(it, System.currentTimeMillis())
            }
            val unique = content.songs.filter { seenSongs.add(it.id) }
            if (unique.size < MIN_TRACKS) continue
            val selected = content.copy(songs = unique)
            contentCache[id] = CachedContent(selected, System.currentTimeMillis())
            results += Playlist(
                id = id,
                title = selected.title,
                thumbnailUrl = selected.songs.firstOrNull()?.thumbnailUrl,
                songCount = selected.songs.size,
            )
        }
        return results
    }

    suspend fun playlist(id: String): PlaylistContent? {
        if (!id.startsWith(PREFIX)) return null
        val cached = contentCache[id]?.takeIf {
            System.currentTimeMillis() - it.atMs < CACHE_TTL_MS
        }?.content
        if (cached != null) return cached
        // Recupero dopo riavvio del servizio: gli ID sono deterministici e
        // non rimandano a playlist di terzi.
        val encoded = id.removePrefix(PREFIX).split(':')
        if (encoded.size != 3) return null
        val year = encoded[2].toIntOrNull() ?: return null
        if (year != Calendar.getInstance().get(Calendar.YEAR)) return null
        val definition = definitions(encoded[0], year).firstOrNull { it.id == encoded[1] }
            ?: return null
        return buildContent(id, definition, year).takeIf { it.songs.size >= MIN_TRACKS }
            ?.also { contentCache[id] = CachedContent(it, System.currentTimeMillis()) }
    }

    /** Le hit sono una coda di brani, non una schermata di playlist YTM. */
    suspend fun songsForGroup(groupId: String): List<Song> {
        val collections = groupPlaylists(
            groupId = groupId,
            mode = DiscoverySettings.RegionMode.INTERNATIONAL,
        )
        val available = collections.flatMap { playlist ->
            playlist(playlist.id)?.songs.orEmpty()
        }.distinctBy { it.id }
        return RecommendationEngine.rank(
            available,
            RecommendationEngine.SessionContext(
                title = if (groupId == "hit-italiane") "Hit del momento italiane" else "Hit del momento straniere",
                contextTag = "novita",
            ),
        ).take(40)
    }

    fun clearCache() {
        contentCache.clear()
        YouTubeAlbumEvidence.clearCache()
    }

    private suspend fun buildContent(id: String, definition: Definition, year: Int): PlaylistContent {
        val page = runCatching {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                YouTube.search(definition.query, filter = YouTube.SearchFilter.FILTER_SONG).getOrThrow()
            }
        }.getOrNull()
        val candidates = page?.items.orEmpty()
            .filterIsInstance<SongItem>()
            .filter {
                !it.isEpisode && it.id.isNotBlank() && it.artists.any { artist -> artist.name.isNotBlank() }
            }
            .distinctBy { it.id }
            .take(MAX_ALBUM_CHECKS)

        val validated = coroutineScope {
            candidates.map { song ->
                async {
                    // Valore locale: SongItem.album appartiene al modulo InnerTubeX,
                    // quindi Kotlin non puo' smart-castare la proprieta' esterna.
                    val album = song.album?.takeIf { it.id.isNotBlank() } ?: return@async null
                    val evidence = YouTubeAlbumEvidence.get(album.id)
                    val decision = ReleaseMetadataPolicy.evaluate(
                        songTitle = song.title,
                        evidence = evidence,
                        startYear = year,
                        endYear = year,
                        knownCurrentYear = year,
                    )
                    if (!decision.accepted) return@async null
                    Song(
                        id = song.id,
                        title = song.title,
                        artist = song.artists.map { it.name.trim() }
                            .filter { it.isNotBlank() }
                            .distinctBy { it.lowercase() }
                            .joinToString(", "),
                        durationSeconds = song.duration?.toLong() ?: 0L,
                        thumbnailUrl = song.thumbnail.takeIf { it.isNotBlank() }
                            ?: evidence?.album?.artworkUrl,
                        album = album.name.takeIf { it.isNotBlank() }
                            ?: evidence?.album?.title,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        val ranked = RecommendationEngine.rank(
            validated,
            RecommendationEngine.SessionContext(
                title = definition.title,
                contextTag = "novita",
            ),
        ).take(MAX_TRACKS)
        return PlaylistContent(id = id, title = definition.title, songs = ranked)
    }

}
