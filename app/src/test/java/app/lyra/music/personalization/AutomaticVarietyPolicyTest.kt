package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutomaticVarietyPolicyTest {
    private fun songs(count: Int): List<Song> = (0 until count).map { index ->
        Song("$index", "Brano $index", "Artista ${index % 8}", 185)
    }

    @Test fun `seed without candidates is null`() =
        assertNull(AutomaticVarietyPolicy.chooseSeed(emptyList()))

    @Test fun `only viable seed is preserved`() =
        assertEquals("0", AutomaticVarietyPolicy.chooseSeed(songs(1))?.id)

    @Test fun `seed rotation excludes previous sources if possible`() {
        val selected = AutomaticVarietyPolicy.chooseSeed(
            songs(20), recentSeedIds = (0..17).map(Int::toString).toSet(), random = Random(3),
        )
        assertTrue(selected?.id in setOf("18", "19"))
    }

    @Test fun `seed keeps relevance by limiting search pool`() {
        val selected = AutomaticVarietyPolicy.chooseSeed(songs(45), random = Random(20))
        assertTrue(requireNotNull(selected).id.toInt() < 20)
    }

    @Test fun `recently proposed seeds are cooled when alternatives exist`() {
        val selected = AutomaticVarietyPolicy.chooseSeed(
            songs(12), recentProposalIds = (0..9).map(Int::toString).toSet(), random = Random(3),
        )
        assertTrue(selected?.id in setOf("10", "11"))
    }

    @Test fun `first track avoids previous openers when there are alternatives`() {
        val ordered = AutomaticVarietyPolicy.diversifyRanked(
            songs(25), recentOpeningIds = (0..8).map(Int::toString).toSet(), salt = 5L,
        )
        assertTrue(ordered.first().id in setOf("9", "10", "11"))
    }

    @Test fun `recent queue candidates rotate to new tracks`() {
        val ordered = AutomaticVarietyPolicy.diversifyRanked(
            songs(25), recentProposalIds = (0..8).map(Int::toString).toSet(), salt = 5L,
        )
        assertTrue(ordered.first().id in setOf("9", "10", "11"))
    }

    @Test fun `different sessions change their ordered beginning`() {
        val beginnings = (1L..16L).map { salt ->
            AutomaticVarietyPolicy.diversifyRanked(songs(35), salt = salt)
                .take(5).map { it.id }
        }.toSet()
        assertTrue(beginnings.size >= 12)
    }

    @Test fun `same session salt produces stable queue`() {
        val input = songs(40)
        val a = AutomaticVarietyPolicy.diversifyRanked(input, salt = 723L)
        val b = AutomaticVarietyPolicy.diversifyRanked(input, salt = 723L)
        assertEquals(a, b)
    }

    @Test fun `all candidates are preserved exactly once`() {
        val input = songs(50)
        val ordered = AutomaticVarietyPolicy.diversifyRanked(input, salt = 12L)
        assertEquals(input.size, ordered.size)
        assertEquals(input.map { it.id }.toSet(), ordered.map { it.id }.toSet())
    }

    @Test fun `opening stays inside top relevance window`() {
        val ordered = AutomaticVarietyPolicy.diversifyRanked(songs(35), salt = 3L)
        assertTrue(ordered.first().id.toInt() in 0..11)
    }

    @Test fun `duplicate IDs do not repeat within the generated queue`() {
        val ordered = AutomaticVarietyPolicy.diversifyRanked(songs(3) + songs(3), salt = 99L)
        assertEquals(3, ordered.size)
    }

    @Test fun `different artists are preferred near each other`() {
        val input = listOf(
            Song("a", "Uno", "Alpha", 160), Song("b", "Due", "Alpha", 160),
            Song("c", "Tre", "Beta", 160), Song("d", "Quattro", "Gamma", 160),
        )
        val ordered = AutomaticVarietyPolicy.diversifyRanked(input, salt = 5L)
        for (i in 1 until ordered.size) {
            assertNotEquals(
                AutomaticVarietyPolicy.artistKey(ordered[i - 1].artist),
                AutomaticVarietyPolicy.artistKey(ordered[i].artist),
            )
        }
    }

    @Test fun `different Youtube video versions are not silently merged`() {
        val sameTitle = listOf(
            Song("one", "Canzone", "Artist", 180),
            Song("two", "Canzone", "Artist", 180),
        )
        assertEquals(2, AutomaticVarietyPolicy.diversifyRanked(sameTitle, salt = 8L).size)
    }

    @Test fun `artist normalization respects featuring and real titles`() {
        assertEquals("artist", AutomaticVarietyPolicy.artistKey("Artist feat. Guest"))
        assertEquals("live band", AutomaticVarietyPolicy.artistKey("Live Band"))
        assertFalse(AutomaticVarietyPolicy.artistKey("HouseArtist").isBlank())
    }
}
