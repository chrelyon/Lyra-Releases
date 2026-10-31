package app.lyra.music.youtube.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseMetadataPolicyTest {
    private fun result(
        song: String = "First Light",
        albumTitle: String = "First Light",
        year: Int? = 2026,
        older: List<ReleaseMetadataPolicy.Edition> = emptyList(),
    ) = ReleaseMetadataPolicy.evaluate(
        songTitle = song,
        evidence = ReleaseMetadataPolicy.Evidence(
            album = ReleaseMetadataPolicy.Edition(albumTitle, year, listOf("Band")),
            otherVersions = older,
        ),
        startYear = 2025,
        endYear = 2026,
        knownCurrentYear = 2026,
    )

    @Test fun `allows contemporary original edition`() = assertTrue(result().accepted)
    @Test fun `does not guess missing year`() = assertEquals(
        ReleaseMetadataPolicy.Reason.UNKNOWN_OR_IMPLAUSIBLE_YEAR, result(year = null).reason,
    )
    @Test fun `rejects old edition`() = assertEquals(
        ReleaseMetadataPolicy.Reason.OUTSIDE_PERIOD, result(year = 1985).reason,
    )
    @Test fun `rejects remastered track`() = assertFalse(result(song = "First Light (Remastered)").accepted)
    @Test fun `rejects compilation`() = assertFalse(result(albumTitle = "Greatest Hits").accepted)
    @Test fun `live in genuine song title is not automatically a variant`() = assertTrue(result(song = "Live and Let Die").accepted)
    @Test fun `rejects proven earlier edition`() = assertEquals(
        ReleaseMetadataPolicy.Reason.EARLIER_ALBUM_EDITION_KNOWN,
        result(older = listOf(ReleaseMetadataPolicy.Edition("First Light", 2020, listOf("Band")))).reason,
    )
    @Test fun `does not reject another artist with same album title`() = assertTrue(
        result(older = listOf(ReleaseMetadataPolicy.Edition("First Light", 2020, listOf("Different band")))).accepted,
    )
}
