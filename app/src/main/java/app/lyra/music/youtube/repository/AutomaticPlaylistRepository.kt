package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Song
import app.lyra.music.personalization.AutomaticVarietyPolicy
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Genera code automatiche usando il Watch Next / Automix nativo di YouTube Music.
 *
 * Non prova a stimare localmente il genere di un brano: per un brano seed delega
 * la similarita' al catalogo YouTube Music. Per i preset di Scopri cerca prima
 * un seed coerente con il mood/genere scelto e poi apre il relativo Automix.
 */
class AutomaticPlaylistRepository {

    data class Batch(
        val songs: List<Song>,
        val endpoint: WatchEndpoint,
        val continuation: String?,
    )

    data class MixedBatch(
        val italianSeed: Song,
        val internationalSeed: Song,
        val italian: Batch,
        val international: Batch,
    )

    suspend fun regionalSongs(): List<Song> {
        val mode = DiscoverySettings.regionMode.value.name
        val (preferred, outside) = app.lyra.music.personalization.RegionPreferencePolicy.queries(mode)
        val first = findSeedFromQueries(listOf(preferred), "Area musicale preferita", emptySet(), emptySet())
        val main = fromSeed(first).songs
        val secondary = try {
            val second = findSeedFromQueries(listOf(outside), "Esplorazione", emptySet(), emptySet())
            fromSeed(second).songs.filterNot { other -> main.any { it.id == other.id } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
          catch (_: Exception) { emptyList() }
        return app.lyra.music.personalization.RegionPreferencePolicy.mix(main, secondary, 20)
    }

    suspend fun artistSongs(artist: String): List<Song> {
        val result=withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            YouTube.search(query=artist,filter=YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        } ?: throw IllegalStateException("Ricerca artista non disponibile")
        return result.items.filterIsInstance<SongItem>()
            .filter { item -> app.lyra.music.personalization.RequestedArtistPolicy.matches(artist,item.artists.map { it.name }) }
            .map(SongItem::toAutomaticSong).distinctBy { it.id }.take(20)
            .also { check(it.isNotEmpty()) { "Nessun brano per questo artista" } }
    }

    suspend fun findSeed(
        presetId: String,
        excludingIds: Set<String> = emptySet(),
        avoidingArtists: Set<String> = emptySet(),
    ): Song {
        val preset = AutomaticPlaylistCatalog.preset(presetId)
            ?: throw IllegalArgumentException("Preset automatico sconosciuto: $presetId")
        return findSeedFromQueries(preset.queries, preset.title, excludingIds, avoidingArtists)
    }

    /**
     * Pop non usa un unico seed regionale: apre due Automix distinti, uno
     * italiano e uno internazionale. Il manager li alterna per tutta la sessione.
     */
    suspend fun balancedPopBatch(): MixedBatch {
        val italianSeed = findSeedFromQueries(
            listOf("pop Italia hits", "Italian pop hits", "pop italiano playlist"),
            "Pop Italia",
        )
        val internationalSeed = findSeedFromQueries(
            listOf("global pop hits", "international pop hits", "worldwide pop hits"),
            "Pop internazionale",
        )
        return MixedBatch(
            italianSeed = italianSeed,
            internationalSeed = internationalSeed,
            italian = fromSeed(italianSeed),
            international = fromSeed(internationalSeed),
        )
    }

    private suspend fun findSeedFromQueries(
        queries: List<String>,
        title: String,
        excludingIds: Set<String> = emptySet(),
        avoidingArtists: Set<String> = emptySet(),
    ): Song {
        val candidates = buildList {
            for (query in queries.shuffled().take(MAX_SEED_QUERIES)) {
                val result = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    YouTube.search(
                        query = query,
                        filter = YouTube.SearchFilter.FILTER_SONG,
                    ).getOrThrow()
                } ?: continue
                addAll(result.items.filterIsInstance<SongItem>())
                if (size >= MIN_SEED_CANDIDATES) break
            }
        }
            .distinctBy { it.id }
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }

        val seed = AutomaticVarietyPolicy.chooseSeed(
            rankedCandidates = candidates.take(SEED_RANDOM_POOL).map(SongItem::toAutomaticSong),
            recentSeedIds = excludingIds,
            avoidArtists = avoidingArtists,
        ) ?: throw IllegalStateException("Nessun brano disponibile per $title")
        return seed
    }

    suspend fun fromSeed(seed: Song): Batch {
        val endpoint = WatchEndpoint(videoId = seed.id)
        val result = withTimeoutOrNull(NEXT_TIMEOUT_MS) {
            YouTube.next(endpoint).getOrThrow()
        } ?: throw IllegalStateException("Timeout creazione radio YouTube Music")

        val songs = result.items
            .map(SongItem::toAutomaticSong)
            .filterNot { it.id == seed.id }
            .distinctBy { it.id }
            .take(BATCH_LIMIT)

        return Batch(
            songs = songs,
            endpoint = result.endpoint,
            continuation = result.continuation,
        )
    }

    suspend fun continueFrom(
        endpoint: WatchEndpoint,
        continuation: String,
    ): Batch {
        val result = withTimeoutOrNull(CONTINUATION_TIMEOUT_MS) {
            YouTube.next(endpoint, continuation).getOrThrow()
        } ?: throw IllegalStateException("Timeout continuazione Playlist Automatica")

        return Batch(
            songs = result.items
                .map(SongItem::toAutomaticSong)
                .distinctBy { it.id }
                .take(BATCH_LIMIT),
            endpoint = result.endpoint,
            continuation = result.continuation,
        )
    }

    private companion object {
        const val SEARCH_TIMEOUT_MS = 7_000L
        const val NEXT_TIMEOUT_MS = 10_000L
        const val CONTINUATION_TIMEOUT_MS = 7_000L
        const val MAX_SEED_QUERIES = 3
        const val MIN_SEED_CANDIDATES = 18
        const val SEED_RANDOM_POOL = 24
        const val BATCH_LIMIT = 40
    }
}

object AutomaticPlaylistCatalog {
    data class Preset(
        val id: String,
        val title: String,
        val subtitle: String,
        val queries: List<String>,
    )

    val presets: List<Preset> = listOf(
        preset("relax", "Relax", "Atmosfera rilassata",
            "relax Italia music", "European chill relax", "soft pop Europe"),
        preset("energia", "Energia", "Brani energici e positivi",
            "energia hit Italia", "European energy hits", "upbeat pop Europe"),
        preset("party", "Party", "Musica da festa",
            "party hits Italia", "European party hits", "dance party Europe"),
        preset("focus", "Concentrazione", "Musica per lavorare e studiare",
            "focus music Europe", "study music Italy", "concentration playlist Europe"),
        preset("romantica", "Romantica", "Atmosfera romantica",
            "canzoni romantiche italiane", "European love songs", "romantic pop Europe"),
        preset("pop", "Pop", "Pop italiano e internazionale",
            "pop Italia hits", "Italian pop hits", "global pop hits", "international pop hits"),
        preset("rock", "Rock", "Rock e preferenze personali",
            "rock Italia", "European rock", "Italian rock hits"),
        preset("rap", "Hip Hop", "Hip Hop e Rap",
            "rap italiano", "European hip hop", "hip hop Italia"),
        preset("dance", "Dance", "Dance",
            "dance Italia hits", "European dance hits", "dance Europe"),
        preset("house", "House", "House, deep e tech house",
            "house Italia", "European house", "deep house Europe", "tech house Europe"),
        preset("techno", "Techno", "Techno e melodic techno",
            "techno Italia", "European techno", "Berlin techno", "melodic techno Europe"),
        preset("edm", "Elettronica", "Elettronica ed EDM",
            "EDM Italia", "European electronic music", "EDM Europe"),
        preset("indie", "Indie", "Indie rock e indie pop",
            "indie Italia", "European indie", "Italian indie"),
        preset("rnb", "R&B", "R&B e soul",
            "R&B Italia", "European R&B", "soul Europe"),
        preset("metal", "Metal", "Metal",
            "metal Italia", "European metal", "Italian metal"),
        preset("jazz", "Jazz", "Jazz",
            "jazz Italia", "European jazz", "Italian jazz"),
        preset("blues", "Blues", "Blues e blues rock",
            "blues Italia", "blues rock", "classic blues"),
        preset("reggae", "Reggae", "Reggae e roots reggae",
            "reggae Italia", "roots reggae", "classic reggae"),
        preset("classica", "Classica", "Musica classica",
            "classical Europe", "musica classica Italia", "European classical music"),
    )

    /** Generi mostrati nei menu (app e Android Auto), nell'ordine di visualizzazione. */
    private val menuIds = listOf(
        "pop", "rock", "metal", "house", "techno", "edm",
        "dance", "rap", "rnb", "jazz", "blues", "reggae", "classica",
    )

    val menuPresets: List<Preset> = menuIds.mapNotNull { preset(it) }

    fun preset(id: String): Preset? = presets.firstOrNull { it.id == id }

    private fun preset(
        id: String,
        title: String,
        subtitle: String,
        vararg queries: String,
    ) = Preset(id, title, subtitle, queries.toList())
}

private fun SongItem.toAutomaticSong(): Song = Song(
    id = id,
    title = title,
    artist = artists.joinToString(", ") { it.name },
    durationSeconds = duration?.toLong() ?: 0L,
    thumbnailUrl = thumbnail,
)
