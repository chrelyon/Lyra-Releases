package app.lyra.music.youtube.api

import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.core.model.DiscoverySnapshot
import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song
import app.lyra.music.core.model.YouTubeLibrarySnapshot
import app.lyra.music.youtube.auth.AuthorizationRequiredException
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.Duration
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Client REST minimale per YouTube Data API v3. */
object YouTubeDataApiClient {

    private const val TAG = "LYRA_LIBRARY"
    private const val API_ROOT = "https://www.googleapis.com/youtube/v3"
    private const val MAX_PAGES = 100
    private const val MUSIC_CATEGORY_ID = "10"
    private const val MUSIC_PLAYLIST_SAMPLE_SIZE = 20
    private const val DEFAULT_REGION_CODE = "IT"
    private const val DISCOVERY_CACHE_TTL_MS = 30L * 60L * 1_000L

    @Volatile
    private var cachedDiscovery: DiscoverySnapshot? = null

    @Volatile
    private var cachedDiscoveryAtMs: Long = 0L

    /**
     * Libreria Lyra: soltanto musica.
     *
     * - playlist create dall'account solo se prevalentemente musicali;
     * - playlist speciale "Preferiti" ricavata dai liked videos del canale;
     * - quando "Preferiti" viene aperta, getPlaylist() filtra comunque ogni
     *   elemento e restituisce soltanto video classificati Music.
     *
     * Non sincronizziamo uploads, watch history, watch later o altri contenuti
     * non musicali del canale.
     */
    suspend fun getMyLibrarySnapshot(): YouTubeLibrarySnapshot =
        withContext(Dispatchers.IO) {
            val ownedPlaylists = getOwnedPlaylists()
            val musicPlaylists = filterMusicPlaylists(ownedPlaylists)
            val likedPlaylistId = getLikedPlaylistId()

            val lyraPlaylists = buildList {
                likedPlaylistId?.let { playlistId ->
                    add(
                        Playlist(
                            id = playlistId,
                            title = "Preferiti",
                            thumbnailUrl = null,
                            // Il conteggio YouTube comprende anche like non musicali.
                            // Non lo mostriamo come conteggio Lyra.
                            songCount = null,
                        )
                    )
                }

                addAll(
                    musicPlaylists.filterNot { playlist ->
                        playlist.id == likedPlaylistId
                    }
                )
            }

            val snapshot = YouTubeLibrarySnapshot(
                channel = null,
                playlists = lyraPlaylists,
                syncedAtEpochMs = System.currentTimeMillis(),
                accessibleChannelCount = 0,
            )

            Log.d(
                TAG,
                "dataApi library playlists=${ownedPlaylists.size} " +
                    "musicPlaylists=${musicPlaylists.size} " +
                    "liked=${likedPlaylistId != null}"
            )
            snapshot
        }

    suspend fun getMyPlaylists(): List<Playlist> =
        getMyLibrarySnapshot().playlists

    suspend fun getPlaylist(playlistId: String): PlaylistContent =
        withContext(Dispatchers.IO) {
            val title = getPlaylistTitle(playlistId)
            val candidates = mutableListOf<Song>()
            var pageToken: String? = null
            var pageCount = 0

            do {
                val params = linkedMapOf(
                    "part" to "snippet,contentDetails",
                    "playlistId" to playlistId,
                    "maxResults" to "50",
                )
                pageToken?.let { params["pageToken"] = it }

                val json = getJson("playlistItems", params)
                val items = json.optJSONArray("items")

                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val snippet = item.optJSONObject("snippet")
                        val contentDetails = item.optJSONObject("contentDetails")

                        val videoId = contentDetails?.optString("videoId")
                            ?.takeIf { it.isNotBlank() }
                            ?: snippet
                                ?.optJSONObject("resourceId")
                                ?.optString("videoId")
                                ?.takeIf { it.isNotBlank() }
                            ?: continue

                        val titleText = snippet
                            ?.optString("title")
                            .orEmpty()
                            .ifBlank { "Brano YouTube" }

                        if (
                            titleText.equals("Deleted video", ignoreCase = true) ||
                            titleText.equals("Private video", ignoreCase = true)
                        ) {
                            continue
                        }

                        candidates += Song(
                            id = videoId,
                            title = titleText,
                            artist = snippet
                                ?.optString("videoOwnerChannelTitle")
                                .orEmpty()
                                .ifBlank { snippet?.optString("channelTitle").orEmpty() },
                            // playlistItems non espone la durata. Media3 la aggiorna
                            // appena il brano viene caricato.
                            durationSeconds = 0L,
                            thumbnailUrl = bestThumbnail(snippet),
                        )
                    }
                }

                pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
                pageCount++
            } while (pageToken != null && pageCount < MAX_PAGES)

            // Anche dentro una playlist musicale possono esserci video non musicali.
            // La stessa chiamata videos.list usata per il filtro arricchisce anche
            // durata, artista e thumbnail, evitando richieste API aggiuntive.
            val musicMetadata = getMusicVideoMetadata(candidates.map { it.id })
            val songs = candidates.mapNotNull { candidate ->
                val metadata = musicMetadata[candidate.id] ?: return@mapNotNull null
                candidate.copy(
                    title = candidate.title.ifBlank { metadata.title },
                    artist = normalizeArtist(candidate.artist)
                        .ifBlank { metadata.artist },
                    durationSeconds = metadata.durationSeconds
                        .takeIf { it > 0L }
                        ?: candidate.durationSeconds,
                    thumbnailUrl = metadata.thumbnailUrl ?: candidate.thumbnailUrl,
                )
            }

            Log.d(
                TAG,
                "dataApi playlist id=$playlistId candidates=${candidates.size} " +
                    "musicSongs=${songs.size} filteredOut=${candidates.size - songs.size} " +
                    "pages=$pageCount"
            )

            PlaylistContent(
                id = playlistId,
                title = title,
                songs = songs,
            )
        }



    /** Classifica musicale YouTube per regione + playlist pubbliche da scoprire. */
    suspend fun getDiscoverySnapshot(
        regionCode: String = DEFAULT_REGION_CODE,
        forceRefresh: Boolean = false,
    ): DiscoverySnapshot = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = cachedDiscovery
        if (
            !forceRefresh &&
            cached != null &&
            cached.regionCode == regionCode &&
            now - cachedDiscoveryAtMs < DISCOVERY_CACHE_TTL_MS
        ) {
            Log.d(TAG, "discover cacheHit region=$regionCode")
            return@withContext cached
        }

        DiscoverySnapshot(
            topSongs = getTopMusicVideos(regionCode),
            playlists = searchMusicPlaylists(regionCode),
            regionCode = regionCode,
        ).also { snapshot ->
            cachedDiscovery = snapshot
            cachedDiscoveryAtMs = now
        }
    }

    suspend fun getTopMusicVideos(
        regionCode: String = DEFAULT_REGION_CODE,
        maxResults: Int = 50,
    ): List<Song> = withContext(Dispatchers.IO) {
        val json = getJson(
            path = "videos",
            params = linkedMapOf(
                "part" to "snippet,contentDetails",
                "chart" to "mostPopular",
                "regionCode" to regionCode,
                "videoCategoryId" to MUSIC_CATEGORY_ID,
                "maxResults" to maxResults.coerceIn(1, 50).toString(),
            ),
        )

        val items = json.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val snippet = item.optJSONObject("snippet")
                val contentDetails = item.optJSONObject("contentDetails")
                add(
                    Song(
                        id = id,
                        title = snippet?.optString("title").orEmpty().ifBlank { "Brano YouTube" },
                        artist = normalizeArtist(snippet?.optString("channelTitle").orEmpty()),
                        durationSeconds = parseIsoDurationSeconds(
                            contentDetails?.optString("duration")
                        ),
                        thumbnailUrl = bestThumbnail(snippet),
                    )
                )
            }
        }.also { songs ->
            Log.d(TAG, "discover top region=$regionCode songs=${songs.size}")
        }
    }

    suspend fun searchMusicPlaylists(
        regionCode: String = DEFAULT_REGION_CODE,
        maxResults: Int = 24,
    ): List<Playlist> = withContext(Dispatchers.IO) {
        val json = getJson(
            path = "search",
            params = linkedMapOf(
                "part" to "snippet",
                "q" to "musica playlist",
                "type" to "playlist",
                "regionCode" to regionCode,
                "relevanceLanguage" to "it",
                "safeSearch" to "moderate",
                "maxResults" to maxResults.coerceIn(1, 50).toString(),
            ),
        )

        val items = json.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                val playlistId = item
                    .optJSONObject("id")
                    ?.optString("playlistId")
                    ?.takeIf { it.isNotBlank() }
                    ?: continue
                val snippet = item.optJSONObject("snippet")
                add(
                    Playlist(
                        id = playlistId,
                        title = snippet?.optString("title").orEmpty().ifBlank { "Playlist YouTube" },
                        thumbnailUrl = bestThumbnail(snippet),
                        songCount = null,
                    )
                )
            }
        }.distinctBy { it.id }.also { playlists ->
            Log.d(TAG, "discover playlists region=$regionCode count=${playlists.size}")
        }
    }


    /** Restituisce lo stato Mi piace dell'account per i video indicati. */
    suspend fun getLikedRatings(videoIds: Collection<String>): Map<String, Boolean> =
        withContext(Dispatchers.IO) {
            val cleanIds = videoIds
                .asSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("local:") }
                .distinct()
                .toList()

            buildMap {
                cleanIds.chunked(50).forEach { chunk ->
                    val json = getJson(
                        path = "videos/getRating",
                        params = linkedMapOf("id" to chunk.joinToString(",")),
                    )
                    val items = json.optJSONArray("items") ?: return@forEach
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        val id = item.optString("videoId").takeIf { it.isNotBlank() } ?: continue
                        put(id, item.optString("rating") == "like")
                    }
                }
            }
        }

    /** Aggiunge o rimuove il Mi piace YouTube per un brano. */
    suspend fun setVideoLiked(videoId: String, liked: Boolean) = withContext(Dispatchers.IO) {
        require(videoId.isNotBlank() && !videoId.startsWith("local:")) {
            "ID video non valido"
        }
        postNoContent(
            path = "videos/rate",
            params = linkedMapOf(
                "id" to videoId,
                "rating" to if (liked) "like" else "none",
            ),
        )
        Log.d(TAG, "rating updated videoId=$videoId liked=$liked")
    }

    /**
     * Recupera soltanto l'ID della playlist di sistema dei video piaciuti.
     * Nessun altro dato del canale viene sincronizzato o mostrato in Lyra.
     */
    private fun getLikedPlaylistId(): String? {
        val json = getJson(
            path = "channels",
            params = linkedMapOf(
                "part" to "contentDetails",
                "mine" to "true",
                "maxResults" to "1",
            ),
        )

        val items = json.optJSONArray("items") ?: return null
        if (items.length() == 0) return null

        return items
            .getJSONObject(0)
            .optJSONObject("contentDetails")
            ?.optJSONObject("relatedPlaylists")
            ?.optString("likes")
            ?.takeIf { it.isNotBlank() }
            ?.also { Log.d(TAG, "likedPlaylist available=true") }
    }

    private fun filterMusicPlaylists(playlists: List<Playlist>): List<Playlist> {
        val accepted = playlists.filter { playlist ->
            if (playlist.songCount == 0) {
                false
            } else {
                val sampleIds = getPlaylistSampleVideoIds(
                    playlistId = playlist.id,
                    maxResults = MUSIC_PLAYLIST_SAMPLE_SIZE,
                )

                if (sampleIds.isEmpty()) {
                    false
                } else {
                    val musicCount = getMusicVideoMetadata(sampleIds).size
                    // Richiediamo almeno il 60% di elementi musicali nel campione.
                    // Per playlist da 1-2 elementi equivale a richiederli tutti musicali.
                    val requiredMusicCount = (sampleIds.size * 3 + 4) / 5
                    musicCount >= requiredMusicCount
                }
            }
        }

        Log.d(
            TAG,
            "musicFilter playlistsInput=${playlists.size} accepted=${accepted.size} " +
                "sampleSize=$MUSIC_PLAYLIST_SAMPLE_SIZE"
        )
        return accepted
    }

    private fun getPlaylistSampleVideoIds(
        playlistId: String,
        maxResults: Int,
    ): List<String> {
        val json = getJson(
            path = "playlistItems",
            params = linkedMapOf(
                "part" to "contentDetails",
                "playlistId" to playlistId,
                "maxResults" to maxResults.coerceIn(1, 50).toString(),
            ),
        )

        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                items.getJSONObject(index)
                    .optJSONObject("contentDetails")
                    ?.optString("videoId")
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }

    /**
     * YouTube categoryId=10 e' la categoria Music. I canali "- Topic" sono
     * accettati anche in caso di metadata categoria incompleti.
     *
     * Oltre al filtro, restituiamo i metadata utili a Lyra usando la stessa
     * chiamata videos.list: durata, artista e thumbnail non costano quindi
     * richieste aggiuntive rispetto alla verifica "solo musica".
     */
    private fun getMusicVideoMetadata(
        videoIds: List<String>
    ): Map<String, MusicVideoMetadata> {
        if (videoIds.isEmpty()) return emptyMap()

        return buildMap {
            videoIds.distinct().chunked(50).forEach { batch ->
                val json = getJson(
                    path = "videos",
                    params = linkedMapOf(
                        "part" to "snippet,contentDetails",
                        "id" to batch.joinToString(","),
                    ),
                )

                val items = json.optJSONArray("items") ?: return@forEach
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    val id = item.optString("id")
                    val snippet = item.optJSONObject("snippet")
                    val contentDetails = item.optJSONObject("contentDetails")
                    val categoryId = snippet?.optString("categoryId")
                    val channelTitle = snippet?.optString("channelTitle").orEmpty()

                    val isMusic = categoryId == MUSIC_CATEGORY_ID ||
                        channelTitle.endsWith(" - Topic", ignoreCase = true)

                    if (id.isBlank() || !isMusic) continue

                    put(
                        id,
                        MusicVideoMetadata(
                            title = snippet?.optString("title").orEmpty(),
                            artist = normalizeArtist(channelTitle),
                            durationSeconds = parseIsoDurationSeconds(
                                contentDetails?.optString("duration")
                            ),
                            thumbnailUrl = bestThumbnail(snippet),
                        )
                    )
                }
            }
        }
    }

    private fun normalizeArtist(value: String): String {
        return value
            .removeSuffix(" - Topic")
            .removeSuffix(" - topic")
            .trim()
    }

    private fun parseIsoDurationSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { Duration.parse(value).seconds }
            .getOrDefault(0L)
            .coerceAtLeast(0L)
    }

    private data class MusicVideoMetadata(
        val title: String,
        val artist: String,
        val durationSeconds: Long,
        val thumbnailUrl: String?,
    )

    private fun getOwnedPlaylists(): List<Playlist> {
        val playlists = mutableListOf<Playlist>()
        var pageToken: String? = null
        var pageCount = 0

        do {
            val params = linkedMapOf(
                "part" to "snippet,contentDetails",
                "mine" to "true",
                "maxResults" to "50",
            )
            pageToken?.let { params["pageToken"] = it }

            val json = getJson("playlists", params)
            val items = json.optJSONArray("items")

            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val snippet = item.optJSONObject("snippet")
                    val contentDetails = item.optJSONObject("contentDetails")

                    val id = item.optString("id")
                    if (id.isBlank()) continue

                    playlists += Playlist(
                        id = id,
                        title = snippet?.optString("title").orEmpty().ifBlank {
                            "Playlist YouTube"
                        },
                        thumbnailUrl = bestThumbnail(snippet),
                        songCount = contentDetails
                            ?.takeIf { it.has("itemCount") }
                            ?.optInt("itemCount"),
                    )
                }
            }

            pageToken = json.optString("nextPageToken").takeIf { it.isNotBlank() }
            pageCount++
        } while (pageToken != null && pageCount < MAX_PAGES)

        Log.d(TAG, "dataApi playlists=${playlists.size} pages=$pageCount")
        return playlists
    }

    private fun getPlaylistTitle(playlistId: String): String {
        val json = getJson(
            path = "playlists",
            params = linkedMapOf(
                "part" to "snippet",
                "id" to playlistId,
                "maxResults" to "1",
            ),
        )

        val title = json.optJSONArray("items")
		     ?.takeIf { it.length() > 0 }
             ?.getJSONObject(0)
             ?.optJSONObject("snippet")
             ?.optString("title")
             .orEmpty()
             .ifBlank { "Playlist YouTube" }
			 
        return if (
            title.equals("Liked songs", ignoreCase = true) ||
            title.equals("Liked videos", ignoreCase = true)
        ) {
            "Preferiti"
        } else {
            title
        }
    }


    private fun postNoContent(
        path: String,
        params: Map<String, String>,
    ) {
        val token = YouTubeAuthorizationManager.accessTokenOrNull()
            ?: throw AuthorizationRequiredException()

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        val connection = (URL("$API_ROOT/$path?$query").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.doOutput = false
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")

        try {
            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.w(TAG, "dataApi unauthorized path=$path; clearing cached token")
                YouTubeAuthorizationManager.invalidateCurrentAccessToken()
                throw AuthorizationRequiredException("La sessione Google deve essere rinnovata.")
            }
            if (status !in 200..299) {
                val body = connection.errorStream?.use { input ->
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
                }.orEmpty()
                val reason = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull()
                throw IllegalStateException(
                    "YouTube Data API HTTP $status" +
                        (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(
        path: String,
        params: Map<String, String>,
    ): JSONObject {
        val token = YouTubeAuthorizationManager.accessTokenOrNull()
            ?: throw AuthorizationRequiredException()

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

        val connection = (URL("$API_ROOT/$path?$query").openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
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
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                    .readText()
            }.orEmpty()

            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.w(TAG, "dataApi unauthorized path=$path; clearing cached token")
                YouTubeAuthorizationManager.invalidateCurrentAccessToken()
                throw AuthorizationRequiredException(
                    "La sessione Google deve essere rinnovata."
                )
            }

            if (status !in 200..299) {
                val reason = runCatching {
                    JSONObject(body)
                        .optJSONObject("error")
                        ?.optString("message")
                }.getOrNull()

                throw IllegalStateException(
                    "YouTube Data API HTTP $status" +
                        (reason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                )
            }

            JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun bestThumbnail(snippet: JSONObject?): String? {
        val thumbnails = snippet?.optJSONObject("thumbnails") ?: return null
        return listOf("maxres", "standard", "high", "medium", "default")
            .firstNotNullOfOrNull { key ->
                thumbnails.optJSONObject(key)
                    ?.optString("url")
                    ?.takeIf { it.isNotBlank() }
            }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString())
}
