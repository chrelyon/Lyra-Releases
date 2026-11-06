package app.lyra.music.genre

import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Server-verified per-track playlists, rather than YTMusic artist-level Automix.
 *
 * A configured opt-in classifier is required. Never degrade a strict genre
 * request into a broad electronic playlist on network/provider errors.
 */
object VerifiedGenrePlaylistClient {
    private val genrePresets = setOf(
        "pop", "rock", "rap", "dance", "house", "techno", "edm",
        "indie", "rnb", "metal", "jazz", "blues", "reggae", "classica",
    )
    private val moodPresets = setOf("relax", "energia", "party", "focus", "romantica")
    private val videoId = Regex("^[A-Za-z0-9_-]{11}$")
    private val imageHosts = setOf("i.ytimg.com", "lh3.googleusercontent.com")

    fun supported(preset: String?): Boolean =
        preset != null && (preset in genrePresets || preset in moodPresets)

    fun isMood(preset: String?): Boolean = preset != null && preset in moodPresets
    fun enabled(preset: String?): Boolean =
        supported(preset) && GenreClassifierSettings.isConfigured()

    suspend fun fetch(
        preset: String,
        excludeIds: Collection<String> = emptyList(),
        limit: Int = 40,
    ): List<Song> = withContext(Dispatchers.IO) {
        require(supported(preset)) { "Preset non supportato per la playlist verificata" }
        require(limit in 1..50) { "Limite brani non valido" }
        val token = GenreClassifierSettings.token()
            ?: throw IllegalStateException("Token Lyra non configurato")
        val base = GenreClassifierSettings.SERVER_URL
        if (!GenreDecisionPolicy.validEndpoint(base)) {
            throw IllegalStateException("Endpoint Lyra non attendibile")
        }

        val payload = JSONObject()
            .put("genre", preset)
            .put("limit", limit)
            .put("include_external", true)
            .put("exclude_ids", JSONArray(excludeIds.filter(videoId::matches)
                .distinct().takeLast(100)))

        val connection = URL("$base/v1/playlists/verified-genre")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 35_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use {
                it.write(payload.toString().toByteArray(Charsets.UTF_8))
            }
            when (val code = connection.responseCode) {
                200 -> {
                    val body = connection.inputStream.bufferedReader().use {
                        it.readText().take(90_001)
                    }
                    if (body.length > 90_000) throw IllegalStateException("Risposta server eccessiva")
                    val parsed = parseVerifiedTracks(body, preset)
                    val response = JSONObject(body)
                    val rows = response.optJSONArray("tracks") ?: JSONArray()
                    val evidence = (0 until minOf(rows.length(), 8)).mapNotNull { index ->
                        val item = rows.optJSONObject(index) ?: return@mapNotNull null
                        val id = item.optString("id").takeIf(videoId::matches)
                            ?: return@mapNotNull null
                        val source = item.optString("genreSource").take(48)
                        val reason = item.optString("genreReason").take(64)
                        "$id:$source:$reason"
                    }.joinToString(";")
                    Log.d(
                        "LYRA_AUTO_PLAYLIST",
                        "verifiedResponse genre=$preset requested=$limit " +
                            "serverSelected=${response.optInt("selected_count", rows.length())} " +
                            "serverVerified=${response.optInt("verified_count", -1)} " +
                            "parsed=${parsed.size} externalStatus=${response.optString("external_status", "none")} " +
                            "externalDiscovery=${response.optBoolean("external_discovery", false)} " +
                            "evidence=$evidence",
                    )
                    parsed.ifEmpty {
                        throw IllegalStateException(
                            "Nessun brano $preset verificato: Lyra non inserisce generi casuali"
                        )
                    }
                }
                401, 403 -> throw IllegalStateException("Token Lyra non autorizzato")
                404 -> throw IllegalStateException("Aggiorna il server Lyra: API playlist verificata assente")
                else -> throw IllegalStateException("Playlist Lyra non disponibile (HTTP $code)")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Only explicitly trusted public image URLs enter player metadata. */
    internal fun approvedArtworkUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.takeIf { url ->
            runCatching {
                val parsed = URI(url)
                parsed.scheme.equals("https", ignoreCase = true) &&
                    parsed.host?.lowercase()?.let { it in imageHosts } == true &&
                    parsed.userInfo == null && parsed.fragment == null &&
                    (parsed.port == -1 || parsed.port == 443)
            }.getOrDefault(false)
        }
    }

    /** Pure validator, suitable for deterministic tests without live server. */
    fun parseVerifiedTracks(json: String, preset: String): List<Song> {
        val body = JSONObject(json)
        if (body.optString("genre") != preset) {
            throw IllegalArgumentException("Risposta playlist di un genere diverso")
        }
        val tracks = body.optJSONArray("tracks") ?: JSONArray()
        val out = ArrayList<Song>(minOf(tracks.length(),50))
        val seen = HashSet<String>()
        for (index in 0 until minOf(tracks.length(),50)) {
            val item = tracks.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val title = item.optString("title").trim()
            val artist = item.optString("artist").trim()
            if (!videoId.matches(id) ||
                title.isBlank() || artist.isBlank() ||
                item.optString("genre") != preset
            ) continue
            if (!seen.add(id)) continue
            val artwork = approvedArtworkUrl(item.optString("thumbnailUrl"))
            out.add(Song(
                id = id,
                title = title,
                artist = artist,
                durationSeconds = 0L,
                thumbnailUrl = artwork,
            ))
        }
        return out
    }
}
