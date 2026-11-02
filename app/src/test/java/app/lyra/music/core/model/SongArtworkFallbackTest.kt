package app.lyra.music.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongArtworkFallbackTest {
    @Test fun youtubeIdProvidesCoverWhenThumbnailMissing() {
        val song = Song("Hw2z_MdSf-k", "World, Hold On", "Bob Sinclar", 0L)
        assertEquals("https://i.ytimg.com/vi/Hw2z_MdSf-k/hqdefault.jpg", song.artworkUrlForPlayback)
    }

    @Test fun originalThumbnailHasPriority() {
        val song = Song("Hw2z_MdSf-k", "Song", "Artist", 0L, thumbnailUrl="https://img.example/cover.jpg")
        assertEquals("https://img.example/cover.jpg", song.artworkUrlForPlayback)
    }

    @Test fun localTracksAndInvalidIdsDoNotReceiveYoutubeArtwork() {
        assertNull(Song("local:42", "Local", "Artist", 0L, playbackUri="content://media/42").artworkUrlForPlayback)
        assertNull(Song("invalid", "Unknown", "Artist", 0L).artworkUrlForPlayback)
    }
}
