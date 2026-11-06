package app.lyra.music.personalization

import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.core.model.Song
import app.lyra.music.playback.ListeningProfileStore
import app.lyra.music.youtube.auth.YouTubeAuthorizationManager
import app.lyra.music.youtube.auth.YouTubeAuthorizationState
import app.lyra.music.youtube.cache.GoogleLibraryAccountPolicy
import app.lyra.music.youtube.cache.YouTubeLibraryCache
import app.lyra.music.youtube.likes.YouTubeLikesManager
import app.lyra.music.youtube.repository.AutomaticPlaylistRepository
import app.lyra.music.youtube.repository.YouTubeMusicRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

object PersonalizedMixEngine {
    const val TITLE = "Mix per me"
    private const val FAVORITES_TIMEOUT_MS = 10_000L
    private const val MAX_REQUEST_BYTES = 48_000

    data class Result(
        val title: String,
        val songs: List<Song>,
        val warning: String? = null,
    )

    private suspend fun googleFavorites(auth: YouTubeAuthorizationState, useLikes: Boolean): List<Song> {
        if (!auth.isAuthorized || !useLikes) return emptyList()
        val songs = try {
            withTimeoutOrNull(FAVORITES_TIMEOUT_MS) {
                val repository = YouTubeMusicRepository()
                val cached = YouTubeLibraryCache.load()
                val snapshot = cached ?: repository.getLibrarySnapshot()
                check(GoogleLibraryAccountPolicy.sameSession(
                    auth.authorizationGeneration,
                    YouTubeAuthorizationManager.state.value.isAuthorized,
                    YouTubeAuthorizationManager.state.value.authorizationGeneration,
                )) { "Account Google cambiato durante la preparazione del Mix" }
                val id = snapshot.playlists.firstOrNull {
                    it.title.trim().equals("Preferiti", ignoreCase = true)
                }?.id
                if (id == null) emptyList() else repository.getPlaylist(id).songs
            }
                ?: throw IllegalStateException("Caricamento dei Preferiti Google scaduto")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("Impossibile caricare i Preferiti Google. Verifica la connessione e riprova.", error)
        }
        check(GoogleLibraryAccountPolicy.sameSession(
            auth.authorizationGeneration,
            YouTubeAuthorizationManager.state.value.isAuthorized,
            YouTubeAuthorizationManager.state.value.authorizationGeneration,
        )) { "Account Google cambiato durante la preparazione del Mix" }
        return YouTubeLikesManager.applyRecentChanges(songs)
    }

    private fun candidates(songs: List<Song>): List<Song> = songs
        .filter { PersonalizedMixPolicy.eligible(it) && !ListeningProfileStore.isBlocked(it.id) }
        .distinctBy { it.id }
        .take(100)

    internal fun payload(local: List<Song>, favorites: List<Song>): JSONObject {
        val localJson = JSONArray()
        val favoritesJson = JSONArray()
        val root = JSONObject().put("local_tracks", localJson).put("google_favorites", favoritesJson)
        for (i in 0 until maxOf(local.size, favorites.size)) {
            for ((song, target) in listOf(local.getOrNull(i) to localJson, favorites.getOrNull(i) to favoritesJson)) {
                if (song == null) continue
                target.put(JSONObject().put("id", song.id).put("title", song.title.trim()).put("artist", song.artist.trim()))
                if (root.toString().toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
                    target.remove(target.length() - 1)
                }
            }
        }
        return root
    }

    suspend fun build(): Result {
        val owner = if (PersonalizationSettings.useServerAlgorithm.value) {
            CloudProfileSync.playlistOwner()
                ?: throw IllegalStateException("Attiva il collegamento e il backup musicale Lyra Server nelle Impostazioni")
        } else null
        val auth = YouTubeAuthorizationManager.state.value
        val useLikes = PersonalizationSettings.useLikes.value
        val local = candidates(if (PersonalizationSettings.useHistory.value) {
            ListeningProfileStore.topSongs(Int.MAX_VALUE).filter { ListeningProfileStore.playCount(it.id) > 0 }
        } else emptyList())
        val favorites = candidates(googleFavorites(auth, useLikes))
        if (owner == null) return buildLocal(local, favorites)

        val response = CloudProfileSync.personalizedMixRequest(payload(local, favorites))
        check(owner == CloudProfileSync.playlistOwner()) { "Profilo Lyra cambiato durante la preparazione del Mix" }
        if (auth.isAuthorized && useLikes) {
            val current = YouTubeAuthorizationManager.state.value
            check(GoogleLibraryAccountPolicy.sameSession(
                auth.authorizationGeneration, current.isAuthorized, current.authorizationGeneration,
            )) { "Account Google cambiato durante la preparazione del Mix" }
        }
        return parseResponse(response, local, favorites)
    }

    internal fun parseResponse(response: JSONObject, local: List<Song>, favorites: List<Song>): Result {
        check(response.optInt("apiVersion", -1) == 2) { "Risposta Mix del server non valida" }
        val tracks = response.optJSONArray("tracks")
            ?: throw IllegalStateException("Risposta Mix del server senza brani")
        check(tracks.length() <= 40) { "Risposta Mix del server non valida" }
        val knownById = (local + favorites).associateBy { it.id }
        val seen = mutableSetOf<String>()
        var known = 0
        var fresh = 0
        val songs = (0 until tracks.length()).map { index ->
            val item = tracks.optJSONObject(index)
                ?: throw IllegalStateException("Brano Mix non valido")
            val id = item.opt("id") as? String ?: throw IllegalStateException("ID Mix non valido")
            val title = item.opt("title") as? String ?: throw IllegalStateException("Titolo Mix non valido")
            val artist = item.opt("artist") as? String ?: throw IllegalStateException("Artista Mix non valido")
            val song = Song(id = id, title = title, artist = artist, durationSeconds = 0)
            check(PersonalizedMixPolicy.eligible(song) && seen.add(id)) { "Brano Mix non valido o duplicato" }
            when (item.optString("source")) {
                "local_listening", "google_favorite", "profile_rating" -> known++
                "lastfm_ytmusic" -> fresh++
                else -> throw IllegalStateException("Sorgente Mix non valida")
            }
            knownById[id] ?: song
        }
        check(known == response.optInt("known_count", -1) && fresh == response.optInt("new_count", -1)) {
            "Conteggi Mix del server non validi"
        }
        check(songs.isNotEmpty()) { "Nessun brano disponibile: ascolta musica o aggiungi Preferiti" }
        val warning = PersonalizedMixPolicy.warning(known, fresh, response.optString("discovery_status"))
        val title = if (warning == null) TITLE else "$TITLE · $fresh novità"
        return Result(title, songs, warning)
    }

    private suspend fun buildLocal(local: List<Song>, favorites: List<Song>): Result {
        val pool = buildList {
            for (i in 0 until maxOf(local.size, favorites.size)) {
                local.getOrNull(i)?.let(::add)
                favorites.getOrNull(i)?.let(::add)
            }
        }.distinctBy { it.id }
        check(pool.isNotEmpty()) { "Ascolta o salva qualche brano tra i Preferiti per creare il Mix per me" }
        val known = pool.take(28)
        val knownIds = pool.mapTo(HashSet()) { it.id }
        val fresh = AutomaticPlaylistRepository().fromSeed(known.random()).songs
            .filter { it.id !in knownIds }.distinctBy { it.id }
            .take(PersonalizedMixPolicy.freshTarget(known.size))
        val iterator = fresh.iterator()
        val queue = buildList {
            known.forEachIndexed { index, song ->
                add(song)
                if ((index + 1) % 2 == 0 && iterator.hasNext()) add(iterator.next())
            }
            iterator.forEachRemaining { add(it) }
        }.distinctBy { it.id }
        val warning = PersonalizedMixPolicy.warning(known.size, fresh.size, "local")
        val title = if (warning == null) "$TITLE · locale" else "$TITLE · locale · ${fresh.size} novità"
        return Result(title, queue, warning)
    }
}
