package app.lyra.music.genre

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedGenrePlaylistClientTest {
    @Test fun onlyExplicitlySupportedGenresUseStrictServer() {
        assertTrue(VerifiedGenrePlaylistClient.supported("techno"))
        assertTrue(VerifiedGenrePlaylistClient.supported("house"))
        assertTrue(VerifiedGenrePlaylistClient.supported("dance"))
        assertTrue(VerifiedGenrePlaylistClient.supported("rock"))
        assertFalse(VerifiedGenrePlaylistClient.supported("electronic"))
        assertFalse(VerifiedGenrePlaylistClient.supported("pop"))
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
}
