package app.lyra.music.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverMenuCatalogTest {
    @Test
    fun rootMenuMatchesTheThreeDiscoverEntriesInDisplayOrder() {
        assertEquals(
            listOf(
                DiscoverMenuCatalog.AUTOMATIC_ID,
                DiscoverMenuCatalog.DAILY_MIX_ID,
                DiscoverMenuCatalog.NEW_RELEASES_ID,
            ),
            DiscoverMenuCatalog.rootItems.map { it.id },
        )
        assertEquals(3, DiscoverMenuCatalog.rootItems.size)
        assertEquals(3, DiscoverMenuCatalog.rootItems.map { it.id }.distinct().size)
        assertEquals("novita", DiscoverMenuCatalog.NEW_RELEASES_FOLDER_ID)
        assertEquals("nuove-uscite", DiscoverMenuCatalog.NEW_RELEASES_GROUP_ID)
    }

    @Test
    fun automaticMenuContainsOnlyTheFourVisibleGenres() {
        assertEquals(
            listOf("pop", "rock", "house", "techno"),
            DiscoverMenuCatalog.automaticPresets.map { it.id },
        )
        assertEquals(4, DiscoverMenuCatalog.automaticPresets.size)
        assertTrue(DiscoverMenuCatalog.automaticPresets.all { it.title.isNotBlank() })
    }
}
