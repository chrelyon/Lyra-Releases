package app.lyra.music.youtube.repository

import app.lyra.music.core.model.DiscoveryFolderContent
import app.lyra.music.core.model.DiscoveryFolderSummary
import app.lyra.music.core.model.DiscoveryGenreSection
import app.lyra.music.core.model.DiscoveryGroupSection
import app.lyra.music.core.model.DiscoveryGroupSummary
import app.lyra.music.core.model.DiscoverySnapshot
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song
import app.lyra.music.core.model.YouTubeLibrarySnapshot
import app.lyra.music.playback.SavedAutomaticPlaylistStore
import app.lyra.music.youtube.api.YouTubeDataApiClient
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Libreria personale tramite YouTube Data API ufficiale.
 * Il playback/search resta su InnerTubeX anonimo e separato dall'account Google.
 */
class YouTubeMusicRepository : MusicRepository {

    suspend fun getLibrarySnapshot(): YouTubeLibrarySnapshot =
        YouTubeDataApiClient.getMyLibrarySnapshot()

    /**
     * Snapshot storico usato per compatibilita' con i nodi Scopri delle versioni
     * 0.3.5-0.3.7. Dalla 0.3.8 Lyra usa invece le cartelle lazy definite piu' sotto.
     */
    suspend fun getDiscoverySnapshot(forceRefresh: Boolean = false): DiscoverySnapshot {
        return DiscoverySnapshot(
            topSongs = emptyList(),
            playlists = getFeaturedMusicPlaylists(forceRefresh = forceRefresh),
            genres = getGenreSections(forceRefresh = forceRefresh),
            regionCode = "IT",
        )
    }

    /**
     * Elenco immediato delle cartelle Scopri. Non effettua rete.
     */
    fun getDiscoveryFolders(): List<DiscoveryFolderSummary> {
        val mode = DiscoverySettings.regionMode.value
        return DiscoveryCatalog.folders.map { definition ->
            DiscoveryFolderSummary(
                id = definition.id,
                title = DiscoveryCatalog.folderTitle(definition, mode),
                subtitle = DiscoveryCatalog.folderSubtitle(definition, mode),
                groupCount = DiscoveryCatalog.groupsFor(definition, mode).size,
            )
        }
    }

    /**
     * Elenco immediato dei gruppi presenti in una cartella. Utile ad Android Auto
     * per restituire metadata coerenti anche prima di aver caricato le playlist.
     */
    fun getDiscoveryGroups(folderId: String): List<DiscoveryGroupSummary> =
        DiscoveryCatalog.folders
            .firstOrNull { it.id == folderId }
            ?.let { folder -> DiscoveryCatalog.groupsFor(folder, DiscoverySettings.regionMode.value) }
            .orEmpty()
            .map { group ->
                val key = "$folderId/${group.id}"
                val now = System.currentTimeMillis()
                val count = cachedDiscoveryGroups[key]
                    ?.takeIf { now - it.loadedAtMs < DISCOVERY_CACHE_TTL_MS }
                    ?.content
                    ?.playlists
                    ?.size
                DiscoveryGroupSummary(group.id, group.title, count)
            }

    /**
     * Carica una sola cartella Scopri alla volta. In questo modo la nuova libreria
     * puo' essere molto piu' ricca senza trasformare l'apertura di Scopri in una
     * raffica di decine di query. Ogni cartella ha cache indipendente di 30 minuti.
     */
    suspend fun getDiscoveryFolder(
        folderId: String,
        forceRefresh: Boolean = false,
    ): DiscoveryFolderContent {
        val definition = DiscoveryCatalog.folders.firstOrNull { it.id == folderId }
            ?: throw IllegalArgumentException("Cartella Scopri sconosciuta: $folderId")

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedDiscoveryFolders[folderId]
                ?.takeIf { now - it.loadedAtMs < DISCOVERY_CACHE_TTL_MS }
                ?.let { return it.content }
        }

        val mode = DiscoverySettings.regionMode.value
        val groups = coroutineScope {
            DiscoveryCatalog.groupsFor(definition, mode).map { groupDefinition ->
                async {
                    getDiscoveryGroup(
                        folderId = folderId,
                        groupId = groupDefinition.id,
                        forceRefresh = forceRefresh,
                    )
                }
            }.awaitAll()
        }

        val content = DiscoveryFolderContent(
            id = definition.id,
            title = DiscoveryCatalog.folderTitle(definition, mode),
            subtitle = DiscoveryCatalog.folderSubtitle(definition, mode),
            groups = groups,
        )
        cachedDiscoveryFolders[folderId] = CachedDiscoveryFolder(content, now)
        return content
    }


    /**
     * Carica un singolo gruppo Scopri. Android Auto usa questo percorso per non
     * scaricare tutti i generi/mood quando l'utente entra soltanto in uno di essi.
     */
    suspend fun getDiscoveryGroup(
        folderId: String,
        groupId: String,
        forceRefresh: Boolean = false,
    ): DiscoveryGroupSection {
        val folder = DiscoveryCatalog.folders.firstOrNull { it.id == folderId }
            ?: throw IllegalArgumentException("Cartella Scopri sconosciuta: $folderId")
        val definition = DiscoveryCatalog.groupsFor(folder, DiscoverySettings.regionMode.value)
            .firstOrNull { it.id == groupId }
            ?: throw IllegalArgumentException("Gruppo Scopri sconosciuto: $folderId/$groupId")
        val key = "$folderId/$groupId"
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            cachedDiscoveryGroups[key]
                ?.takeIf { now - it.loadedAtMs < DISCOVERY_CACHE_TTL_MS }
                ?.let { return it.content }
        }

        val content = if (folderId == "novita") {
            DiscoveryGroupSection(
                id = definition.id,
                title = definition.title,
                playlists = NewReleaseDiscovery.groupPlaylists(
                    groupId = definition.id,
                    mode = DiscoverySettings.regionMode.value,
                    forceRefresh = forceRefresh,
                ),
            )
        } else {
            loadDiscoveryGroupSection(folderId, definition)
        }
        cachedDiscoveryGroups[key] = CachedDiscoveryGroup(content, now)
        return content
    }

    private suspend fun loadDiscoveryGroupSection(
        folderId: String,
        definition: DiscoveryCatalog.GroupDefinition,
    ): DiscoveryGroupSection {
        val queries = DiscoveryCatalog.queriesFor(
            definition = definition,
            mode = DiscoverySettings.regionMode.value,
        )
        val candidates = mutableListOf<PlaylistItem>()
        // Esaminiamo tutte le query prima di scegliere: la prima risposta non
        // deve monopolizzare le otto posizioni. Nessun riempimento obbligatorio.
        for (query in queries.distinct().take(DISCOVERY_GROUP_MAX_QUERIES)) {
            candidates += searchDiscoveryPlaylists(query, folderId, definition.id)
        }
        val best = candidates
            .distinctBy { it.id.removePrefix("VL") }
            .map { item ->
                val count = item.songCountText?.filter(Char::isDigit)?.toIntOrNull()
                val quality = DiscoveryPlaylistQuality.qualityScore(
                    folderId, definition.id, item.title, count,
                )
                item to (quality + discoveryRegionalScore(item))
            }
            .filter { it.second >= 0 }
            .sortedByDescending { it.second }
            .take(DISCOVERY_QUALITY_VALIDATION_LIMIT)
            .map { it.first }
        val gate = Semaphore(3)
        val checked = coroutineScope {
            best.map { candidate ->
                async {
                    gate.withPermit {
                        if (isHealthyDiscoveryPlaylist(candidate)) candidate else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return DiscoveryGroupSection(
            id = definition.id,
            title = definition.title,
            playlists = checked
                .take(DISCOVERY_GROUP_PLAYLIST_LIMIT)
                .map(PlaylistItem::toLyraDiscoveryPlaylist),
        )
    }

    /**
     * Controllo reale dei primi brani: playlist con meno di 5 tracce valide,
     * bloccate o non caricabili non vengono mostrate tra le selezionate.
     * Non abbiamo un conteggio affidabile delle visualizzazioni delle playlist.
     */
    private suspend fun isHealthyDiscoveryPlaylist(candidate: PlaylistItem): Boolean {
        val page = runCatching {
            withTimeoutOrNull(DISCOVERY_QUALITY_TIMEOUT_MS) {
                YouTube.playlist(candidate.id.removePrefix("VL")).getOrThrow()
            }
        }.getOrNull() ?: return false
        return page.songs
            .asSequence()
            .filter { !it.isEpisode && it.id.isNotBlank() && it.artists.isNotEmpty() }
            .map { it.id }
            .distinct()
            .take(5)
            .count() >= 5
    }

    private suspend fun searchDiscoveryPlaylists(
        query: String,
        folderId: String,
        groupId: String,
    ): List<PlaylistItem> {
        val collected = mutableListOf<PlaylistItem>()
        val seenContinuations = mutableSetOf<String>()

        val firstPage = runCatching {
            withTimeoutOrNull(DISCOVERY_SEARCH_TIMEOUT_MS) {
                YouTube.search(
                    query = query,
                    filter = YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
                ).getOrThrow()
            }
        }.getOrNull() ?: return emptyList()

        fun append(items: List<PlaylistItem>) {
            collected += items.filter(::isDiscoveryPlaylistCandidate)
                .filter { item ->
                    DiscoveryPlaylistQuality.accepts(
                        folderId = folderId,
                        groupId = groupId,
                        title = item.title,
                        description = item.description,
                    )
                }
                .filterNot { DiscoveryQualityStore.isTemporarilyBlocked(it.id) }
        }

        append(firstPage.items.filterIsInstance<PlaylistItem>())
        var continuation = firstPage.continuation
        var pageCount = 1
        while (
            continuation != null &&
            pageCount < DISCOVERY_SEARCH_MAX_PAGES &&
            collected.distinctBy { it.id }.size < DISCOVERY_SEARCH_RESULT_TARGET
        ) {
            val token = continuation
            if (!seenContinuations.add(token)) break
            val next = runCatching {
                withTimeoutOrNull(DISCOVERY_SEARCH_CONTINUATION_TIMEOUT_MS) {
                    YouTube.searchContinuation(token).getOrThrow()
                }
            }.getOrNull() ?: break
            append(next.items.filterIsInstance<PlaylistItem>())
            continuation = next.continuation
            pageCount++
        }
        return collected.distinctBy { it.id.removePrefix("VL") }
            .take(DISCOVERY_SEARCH_RESULT_TARGET)
    }

    private fun isDiscoveryPlaylistCandidate(item: PlaylistItem): Boolean {
        if (item.isPodcast || item.id.isBlank() || item.title.isBlank()) return false
        val normalized = buildString {
            append(item.title)
            append(' ')
            append(item.author?.name.orEmpty())
            append(' ')
            append(item.description.orEmpty())
        }.lowercase()

        return when (DiscoverySettings.regionMode.value) {
            DiscoverySettings.RegionMode.INTERNATIONAL -> true
            DiscoverySettings.RegionMode.ITALY,
            DiscoverySettings.RegionMode.ITALY_EUROPE -> {
                !NON_EUROPEAN_SCRIPT_REGEX.containsMatchIn(normalized) &&
                    DISCOVERY_EXCLUDED_KEYWORDS.none(normalized::contains)
            }
        }
    }

    private fun discoveryRegionalScore(item: PlaylistItem): Int {
        val text = (item.title + " " + item.author?.name.orEmpty()).lowercase()
        return when (DiscoverySettings.regionMode.value) {
            DiscoverySettings.RegionMode.INTERNATIONAL -> 0
            DiscoverySettings.RegionMode.ITALY -> ITALY_PRIORITY_KEYWORDS.sumOf { keyword ->
                if (text.contains(keyword)) 5 else 0
            }
            DiscoverySettings.RegionMode.ITALY_EUROPE -> DISCOVERY_PRIORITY_KEYWORDS.sumOf { keyword ->
                if (text.contains(keyword)) 3 else 0
            }
        }
    }

    /**
     * Scopri legacy usa esclusivamente il catalogo music.youtube.com tramite il
     * client WEB_REMIX di InnerTubeX e restituisce soltanto PlaylistItem.
     */
    suspend fun getFeaturedMusicPlaylists(forceRefresh: Boolean = false): List<Playlist> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedFeaturedPlaylists
                ?.takeIf { now - cachedFeaturedPlaylistsAtMs < DISCOVERY_CACHE_TTL_MS }
                ?.let { return it }
        }

        val loaded = coroutineScope {
            DISCOVERY_FEATURED_QUERIES.map { query ->
                async {
                    runCatching {
                        YouTube.search(
                            query = query,
                            filter = YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
                        )
                            .getOrThrow()
                            .items
                            .filterIsInstance<PlaylistItem>()
                            .map(PlaylistItem::toLyraDiscoveryPlaylist)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
                .flatten()
                .distinctBy { it.id }
                .take(FEATURED_PLAYLIST_LIMIT)
        }

        cachedFeaturedPlaylists = loaded
        cachedFeaturedPlaylistsAtMs = now
        return loaded
    }

    suspend fun getGenreSections(forceRefresh: Boolean = false): List<DiscoveryGenreSection> {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedGenreSections
                ?.takeIf { now - cachedGenreSectionsAtMs < DISCOVERY_CACHE_TTL_MS }
                ?.let { return it }
        }

        val loaded = coroutineScope {
            LEGACY_DISCOVERY_GENRES.map { definition ->
                async {
                    val playlists = runCatching {
                        YouTube.search(
                            query = definition.query,
                            filter = YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST,
                        )
                            .getOrThrow()
                            .items
                            .filterIsInstance<PlaylistItem>()
                            .map(PlaylistItem::toLyraDiscoveryPlaylist)
                            .distinctBy { it.id }
                            .take(LEGACY_GENRE_PLAYLIST_LIMIT)
                    }.getOrDefault(emptyList())

                    playlists.takeIf { it.isNotEmpty() }?.let {
                        DiscoveryGenreSection(
                            id = definition.id,
                            title = definition.title,
                            playlists = it,
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        cachedGenreSections = loaded
        cachedGenreSectionsAtMs = now
        return loaded
    }

    suspend fun getTopMusicVideos(): List<Song> =
        YouTubeDataApiClient.getTopMusicVideos()

    override suspend fun getPlaylists(): List<Playlist> =
        YouTubeDataApiClient.getMyPlaylists()

    override suspend fun getPlaylist(
        playlistId: String
    ): PlaylistContent {
        if (playlistId.startsWith(NewReleaseDiscovery.PREFIX)) {
            return NewReleaseDiscovery.playlist(playlistId)
                ?: throw IllegalStateException("Nessuna nuova uscita con anno verificato disponibile")
        }

        if (playlistId.startsWith(SavedAutomaticPlaylistStore.PLAYLIST_PREFIX)) {
            return SavedAutomaticPlaylistStore.getContent(playlistId)
                ?: throw IllegalArgumentException("Playlist Lyra salvata non trovata")
        }

        if (playlistId.startsWith(DISCOVER_MUSIC_PLAYLIST_PREFIX)) {
            return getPublicPlaylistWithInnerTube(
                playlistId = playlistId.removePrefix(DISCOVER_MUSIC_PLAYLIST_PREFIX),
                discoveryId = playlistId,
            )
        }

        if (playlistId == DISCOVER_TOP_IT_PLAYLIST_ID) {
            return PlaylistContent(
                id = playlistId,
                title = "Top musica Italia",
                songs = getTopMusicVideos(),
            )
        }

        // Le playlist personali usano la Data API quando l'account e'
        // autorizzato. Manteniamo il fallback InnerTube per playlist
        // pubbliche/diagnostiche anche senza login.
        return if (YouTubeAuthorizationManager.accessTokenOrNull() != null) {
            YouTubeDataApiClient.getPlaylist(playlistId)
        } else {
            getPublicPlaylistWithInnerTube(playlistId = playlistId)
        }
    }

    override suspend fun searchSongs(
        query: String
    ): List<Song> {
        return YouTube
            .search(
                query = query,
                filter = YouTube.SearchFilter.FILTER_SONG
            )
            .getOrThrow()
            .items
            .filterIsInstance<SongItem>()
            .map { song -> song.toLyraSong() }
    }

    private suspend fun getPublicPlaylistWithInnerTube(
        playlistId: String,
        discoveryId: String? = null,
    ): PlaylistContent {
        val normalizedId = playlistId.removePrefix("VL")
        val page = withTimeoutOrNull(PLAYLIST_INITIAL_TIMEOUT_MS) {
            YouTube.playlist(normalizedId).getOrThrow()
        } ?: run {
            discoveryId?.let(DiscoveryQualityStore::markFailed)
            throw IllegalStateException("Timeout caricamento playlist YouTube Music")
        }

        if (page.songs.isEmpty()) {
            discoveryId?.let(DiscoveryQualityStore::markFailed)
            throw IllegalStateException("Playlist YouTube Music senza brani disponibili")
        }
        discoveryId?.let(DiscoveryQualityStore::markHealthy)

        val songs = page.songs
            .map { it.toLyraSong() }
            .toMutableList()

        var continuation = page.songsContinuation
        var pageCount = 1

        // Le playlist molto grandi non devono bloccare indefinitamente la UI.
        // Se una continuation fallisce o scade, restituiamo comunque i brani gia'
        // caricati invece di lasciare la schermata in attesa per minuti.
        while (continuation != null && pageCount < MAX_PLAYLIST_PAGES) {
            val continuationToken = continuation
            val continuationPage = runCatching {
                withTimeoutOrNull(PLAYLIST_CONTINUATION_TIMEOUT_MS) {
                    YouTube.playlistContinuation(continuationToken).getOrThrow()
                }
            }.getOrNull() ?: break

            songs += continuationPage.songs.map { it.toLyraSong() }
            continuation = continuationPage.continuation
            pageCount++
        }

        return PlaylistContent(
            id = playlistId,
            title = page.playlist.title,
            songs = songs.distinctBy { it.id },
        )
    }

    companion object {
        const val DISCOVER_TOP_IT_PLAYLIST_ID = "lyra-discover-top-it" // compatibilita' sessioni 0.3.x
        const val DISCOVER_MUSIC_PLAYLIST_PREFIX = "lyra-ytm:"

        fun clearDiscoveryCaches() {
            cachedDiscoveryFolders.clear()
            cachedDiscoveryGroups.clear()
            cachedGenreSections = null
            cachedGenreSectionsAtMs = 0L
            cachedFeaturedPlaylists = null
            cachedFeaturedPlaylistsAtMs = 0L
            NewReleaseDiscovery.clearCache()
        }

        private const val MAX_PLAYLIST_PAGES = 3
        private const val LEGACY_GENRE_PLAYLIST_LIMIT = 5
        private const val FEATURED_PLAYLIST_LIMIT = 16
        private const val DISCOVERY_GROUP_PLAYLIST_LIMIT = 8
        private const val DISCOVERY_SEARCH_RESULT_TARGET = 20
        private const val DISCOVERY_QUALITY_VALIDATION_LIMIT = 11
        private const val DISCOVERY_QUALITY_TIMEOUT_MS = 4_000L
        private const val DISCOVERY_SEARCH_MAX_PAGES = 2
        private const val DISCOVERY_GROUP_MAX_QUERIES = 3
        private const val DISCOVERY_SEARCH_TIMEOUT_MS = 8_000L
        private const val DISCOVERY_SEARCH_CONTINUATION_TIMEOUT_MS = 3_000L
        private const val PLAYLIST_INITIAL_TIMEOUT_MS = 8_000L
        private const val PLAYLIST_CONTINUATION_TIMEOUT_MS = 3_000L
        private const val DISCOVERY_CACHE_TTL_MS = 30L * 60L * 1_000L

        @Volatile private var cachedGenreSections: List<DiscoveryGenreSection>? = null
        @Volatile private var cachedGenreSectionsAtMs: Long = 0L
        @Volatile private var cachedFeaturedPlaylists: List<Playlist>? = null
        @Volatile private var cachedFeaturedPlaylistsAtMs: Long = 0L
        private val cachedDiscoveryFolders = ConcurrentHashMap<String, CachedDiscoveryFolder>()
        private val cachedDiscoveryGroups = ConcurrentHashMap<String, CachedDiscoveryGroup>()

        private val DISCOVERY_FEATURED_QUERIES = listOf(
            "Italia hit del momento",
            "new music releases playlist",
            "playlist del momento",
            "mix musica italiana",
        )

        private val DISCOVERY_EXCLUDED_KEYWORDS = listOf(
            "bollywood", "hindi", "punjabi", "india", "indian",
            "ukraine", "ukrainian", "russia", "russian", "k-pop", "kpop",
            "arabic hits", "arab hits",
        )

        private val ITALY_PRIORITY_KEYWORDS = listOf(
            "italia", "italian", "italiano", "italiana", "italiane", "italy",
        )

        private val DISCOVERY_PRIORITY_KEYWORDS = listOf(
            "italia", "italian", "italiano", "italiana", "europe", "european",
            "uk", "british", "france", "french", "german", "germany",
            "spain", "spanish", "dutch", "netherlands", "scandinav", "nordic",
        )

        private val NON_EUROPEAN_SCRIPT_REGEX = Regex(
            "[\u0400-\u052F\u0600-\u06FF\u0900-\u0D7F\u0E00-\u0E7F" +
                "\u3040-\u30FF\u3400-\u9FFF\uAC00-\uD7AF]"
        )

        private val LEGACY_DISCOVERY_GENRES = listOf(
            GenreDefinition("pop", "Pop", "pop hits playlist"),
            GenreDefinition("rock", "Rock", "rock essentials playlist"),
            GenreDefinition("rap", "Hip Hop e Rap", "hip hop rap playlist"),
            GenreDefinition("dance", "Dance ed Elettronica", "dance electronic playlist"),
            GenreDefinition("indie", "Indie", "indie music playlist"),
            GenreDefinition("jazz", "Jazz", "jazz essentials playlist"),
            GenreDefinition("classica", "Classica", "classical music playlist"),
            GenreDefinition("relax", "Relax e Chill", "chill relax playlist"),
            GenreDefinition("workout", "Workout", "workout music playlist"),
            GenreDefinition("80s", "Anni 80", "80s hits playlist"),
            GenreDefinition("90s", "Anni 90", "90s hits playlist"),
            GenreDefinition("2000s", "Anni 2000", "2000s hits playlist"),
        )
    }

    private data class CachedDiscoveryFolder(
        val content: DiscoveryFolderContent,
        val loadedAtMs: Long,
    )

    private data class CachedDiscoveryGroup(
        val content: DiscoveryGroupSection,
        val loadedAtMs: Long,
    )


    private data class GenreDefinition(
        val id: String,
        val title: String,
        val query: String,
    )
}

private fun PlaylistItem.toLyraDiscoveryPlaylist(): Playlist {
    return Playlist(
        id = YouTubeMusicRepository.DISCOVER_MUSIC_PLAYLIST_PREFIX +
            id.removePrefix("VL"),
        title = title,
        thumbnailUrl = thumbnail,
        songCount = songCountText
            ?.filter { char -> char.isDigit() }
            ?.toIntOrNull(),
    )
}

private fun SongItem.toLyraSong(): Song {
    return Song(
        id = id,
        title = title,
        artist = artists.joinToString(", ") { it.name },
        durationSeconds = duration?.toLong() ?: 0L,
        thumbnailUrl = thumbnail
    )
}
