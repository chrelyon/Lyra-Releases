package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalizedMixEngineTest {
    private val known = Song("abcdefghijk", "Titolo", "Artista", 123, thumbnailUrl = "cover")
    private val fresh = Song("abcdefghij_", "Novità", "Altro", 0)

    private fun track(song: Song, source: String) = JSONObject()
        .put("id", song.id).put("title", song.title).put("artist", song.artist).put("source", source)

    private fun response(tracks: JSONArray, knownCount: Int, newCount: Int) = JSONObject()
        .put("apiVersion", 2).put("tracks", tracks)
        .put("known_count", knownCount).put("new_count", newCount)
        .put("discovery_status", "ok")

    @Test fun serverResponsePreservesMetadataAndReportsPartialMix() {
        val result = PersonalizedMixEngine.parseResponse(
            response(JSONArray().put(track(known, "local_listening")).put(track(fresh, "lastfm_ytmusic")), 1, 1),
            listOf(known), emptyList(),
        )
        assertEquals(listOf(known, fresh), result.songs)
        assertEquals("Mix per me", result.title)
        val partial = PersonalizedMixEngine.parseResponse(
            response(JSONArray().put(track(known, "profile_rating")), 1, 0), emptyList(), emptyList(),
        )
        assertTrue(partial.title.contains("0 novità"))
        assertTrue(partial.warning!!.contains("0 novità su 1"))
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateTracksAreRejected() {
        PersonalizedMixEngine.parseResponse(
            response(JSONArray().put(track(known, "local_listening")).put(track(known, "lastfm_ytmusic")), 1, 1),
            emptyList(), emptyList(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun incorrectCountsAreRejected() {
        PersonalizedMixEngine.parseResponse(
            response(JSONArray().put(track(known, "local_listening")), 0, 1), emptyList(), emptyList(),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun emptyPlaylistIsRejected() {
        PersonalizedMixEngine.parseResponse(response(JSONArray(), 0, 0), emptyList(), emptyList())
    }

    @Test fun requestRespectsUtf8LimitAndKeepsBothSources() {
        val local = (0 until 100).map {
            known.copy(id = "%011d".format(it), title = "è".repeat(500), artist = "è".repeat(300))
        }
        val favorites = (100 until 200).map {
            known.copy(id = "%011d".format(it), title = "è".repeat(500), artist = "è".repeat(300))
        }
        val payload = PersonalizedMixEngine.payload(local, favorites)
        assertTrue(payload.toString().toByteArray(Charsets.UTF_8).size <= 48_000)
        assertTrue(payload.getJSONArray("local_tracks").length() in 1..100)
        assertTrue(payload.getJSONArray("google_favorites").length() in 1..100)
        val item = payload.getJSONArray("local_tracks").getJSONObject(0)
        assertEquals(setOf("id", "title", "artist"), item.keys().asSequence().toSet())
    }
}
