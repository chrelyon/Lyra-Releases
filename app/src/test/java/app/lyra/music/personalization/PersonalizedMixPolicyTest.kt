package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PersonalizedMixPolicyTest {
    private val song = Song(id = "abcdefghijk", title = "Titolo", artist = "Artista", durationSeconds = 0)

    @Test fun onlyPlayableYoutubeCandidatesWithValidMetadataAreSent() {
        assertTrue(PersonalizedMixPolicy.eligible(song))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(playbackUri = "content://media/1")))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(id = "local:1")))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(title = " ")))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(artist = " ")))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(title = "a".repeat(501))))
        assertFalse(PersonalizedMixPolicy.eligible(song.copy(artist = "a".repeat(301))))
    }

    @Test fun discoveryTargetAndPartialWarningMatchServer() {
        assertEquals(12, PersonalizedMixPolicy.freshTarget(28))
        assertEquals(1, PersonalizedMixPolicy.freshTarget(1))
        assertEquals(0, PersonalizedMixPolicy.freshTarget(0))
        assertNull(PersonalizedMixPolicy.warning(28, 12, "ok"))
        assertTrue(PersonalizedMixPolicy.warning(28, 0, "provider_error")!!.contains("servizi di scoperta"))
        assertTrue(PersonalizedMixPolicy.warning(7, 2, "no_preference_genre")!!.contains("2 novità su 3"))
    }
}
