package app.lyra.music.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedGenrePlaylistClientTest {
    @Test fun supportsAllApplicationAutomaticPresetsWithDifferentMoodSemantics() {
        val genres = listOf(
            "pop", "rock", "rap", "dance", "house", "techno", "edm",
            "indie", "rnb", "metal", "jazz", "classica",
        )
        val moods = listOf("relax", "energia", "party", "focus", "romantica")
        for (name in genres) {
            assertTrue("Missing music preset $name", VerifiedGenrePlaylistClient.supported(name))
            assertFalse("Music genre misclassified as mood: $name",
                VerifiedGenrePlaylistClient.isMood(name))
        }
        for (name in moods) {
            assertTrue("Missing mood $name", VerifiedGenrePlaylistClient.supported(name))
            assertTrue("Mood misclassified as music genre: $name",
                VerifiedGenrePlaylistClient.isMood(name))
        }
        assertFalse(VerifiedGenrePlaylistClient.supported("electronic"))
        assertFalse(VerifiedGenrePlaylistClient.supported("unknown"))
        assertFalse(VerifiedGenrePlaylistClient.supported(null))
    }

    @Test fun coversMustBeTrustedHttpsImageHosts() {
        val image = "https://i.ytimg.com/vi/Hw2z_MdSf-k/hqdefault.jpg"
        assertEquals(image, VerifiedGenrePlaylistClient.approvedArtworkUrl(image))
        assertTrue(VerifiedGenrePlaylistClient.approvedArtworkUrl(
            "https://lh3.googleusercontent.com/a/abcdefg"
        ) != null)
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl("http://i.ytimg.com/path"))
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl("https://i.ytimg.com.evil.example/"))
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl("https://evil.example/cover.jpg"))
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl("https://alice@i.ytimg.com/cover"))
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl("https://i.ytimg.com:12345/cover"))
        assertNull(VerifiedGenrePlaylistClient.approvedArtworkUrl(null))
    }

    @Test fun explainsProgressiveDiscoveryStatusWithoutClaimingUnavailableProposals() {
        assertTrue(
            VerifiedGenrePlaylistClient.discoveryNotice("pending", false)
                ?.contains("si aggiornerà automaticamente") == true,
        )
        assertTrue(
            VerifiedGenrePlaylistClient.discoveryNotice("lastfm_not_configured", false)
                ?.contains("configurazione Last.fm") == true,
        )
        assertTrue(
            VerifiedGenrePlaylistClient.discoveryNotice("provider_unavailable", false)
                ?.contains("riproverà automaticamente") == true,
        )
        assertNull(VerifiedGenrePlaylistClient.discoveryNotice("ok", true))
        assertTrue(
            VerifiedGenrePlaylistClient.discoveryNotice("disabled", false)
                ?.contains("configurazione Last.fm") == true,
        )
        assertNull(VerifiedGenrePlaylistClient.discoveryNotice("unknown", false))
    }
}
