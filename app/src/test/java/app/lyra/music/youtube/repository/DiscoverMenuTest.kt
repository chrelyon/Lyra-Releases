package app.lyra.music.youtube.repository

import app.lyra.music.genre.VerifiedGenrePlaylistClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiscoverMenuTest {
    @Test fun rootHasFiveEntriesInOrder() {
        assertEquals(
            listOf("automatic", "mix", "novita", "italiane", "decenni"),
            DiscoverMenu.entries.map { it.id },
        )
    }

    @Test fun decadesGoFromEightiesToToday() {
        assertEquals(
            listOf("80s", "90s", "2000s", "2010s", "2020s"),
            DiscoverMenu.decades.map { it.id },
        )
    }

    @Test fun automaticMenuHasOnlySupportedGenres() {
        val ids = AutomaticPlaylistCatalog.menuPresets.map { it.id }
        assertEquals(
            listOf("pop", "rock", "metal", "house", "techno", "edm", "dance", "rap", "rnb", "jazz", "blues", "reggae", "classica"),
            ids,
        )
        assertFalse("relax" in ids)
        ids.forEach { assertEquals(true, VerifiedGenrePlaylistClient.supported(it)) }
    }
}
