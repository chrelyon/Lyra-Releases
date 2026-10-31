package app.lyra.music.youtube.repository

import java.text.Normalizer
import java.util.Locale

/**
 * Regole pure per classificare le EDIZIONI di album restituite da YouTube Music.
 *
 * Il campo AlbumItem.year è l'anno dell'edizione dell'album, NON una prova
 * dell'anno della prima pubblicazione della singola registrazione. Lyra evita
 * quindi di dichiarare una data originale quando non è fornita dalla sorgente.
 * Rifiuta le ristampe riconoscibili, ma non pretende di identificarle tutte.
 */
internal object ReleaseMetadataPolicy {
    data class Edition(
        val title: String,
        val year: Int?,
        val artists: List<String> = emptyList(),
        val artworkUrl: String? = null,
    )

    data class Evidence(
        val album: Edition,
        val otherVersions: List<Edition> = emptyList(),
    )

    enum class Reason {
        ACCEPTED,
        UNKNOWN_OR_IMPLAUSIBLE_YEAR,
        OUTSIDE_PERIOD,
        TRACK_IS_VARIANT,
        ALBUM_IS_REISSUE_OR_COMPILATION,
        EARLIER_ALBUM_EDITION_KNOWN,
    }

    data class Decision(val accepted: Boolean, val reason: Reason, val editionYear: Int?)

    private val trackVariant = Regex(
        """(?i)\b(?:remaster(?:ed|ing)?|re[ -]?record(?:ed|ing)?|karaoke|""" +
            """sped[ -]?up|slowed(?:[ -]?down)?|nightcore|tribute|""" +
            """cover[ -]?version|instrumental[ -]?version|alternate[ -]?take|""" +
            """radio[ -]?edit|club[ -]?mix|remix(?:ed)?|acoustic[ -]?version|""" +
            """\d{4}[ -]?version)\b""",
    )
    private val markedLiveVersion = Regex(
        """(?i)(?:\(\s*live[^)]*\)|\[\s*live[^\]]*\]|\s+-\s+live\b)""",
    )
    private val albumReissue = Regex(
        """(?i)\b(?:remaster(?:ed|ing)?|re[ -]?issue|re[ -]?release|""" +
            """deluxe|expanded|anniversary|collector'?s[ -]?edition|""" +
            """special[ -]?edition|legacy[ -]?edition|bonus[ -]?tracks?|""" +
            """\d{4}[ -]?(?:edition|version))\b""",
    )
    private val albumCompilation = Regex(
        """(?i)\b(?:greatest[ -]?hits|best[ -]?of|anthology|compilation|""" +
            """the[ -]?collection|essential[ -]?collection)\b""",
    )
    private val removableEdition = Regex(
        """(?i)(?:\s*(?:\(|\[)\s*(?:\d{4}\s+)?(?:remaster(?:ed)?|deluxe(?:\s+edition)?|""" +
            """expanded(?:\s+edition)?|\d+(?:st|nd|rd|th)\s+anniversary(?:\s+edition)?|""" +
            """special\s+edition|reissue|re-release)\s*(?:\)|\]))""",
    )
    private val marks = Regex("\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")

    fun evaluate(
        songTitle: String,
        evidence: Evidence?,
        startYear: Int,
        endYear: Int,
        knownCurrentYear: Int,
    ): Decision {
        val year = evidence?.album?.year
        if (year == null || year !in 1900..knownCurrentYear) {
            return Decision(false, Reason.UNKNOWN_OR_IMPLAUSIBLE_YEAR, year)
        }
        if (year !in startYear..endYear) {
            return Decision(false, Reason.OUTSIDE_PERIOD, year)
        }
        if (trackVariant.containsMatchIn(songTitle) || markedLiveVersion.containsMatchIn(songTitle)) {
            return Decision(false, Reason.TRACK_IS_VARIANT, year)
        }
        if (albumReissue.containsMatchIn(evidence.album.title) ||
            albumCompilation.containsMatchIn(evidence.album.title)
        ) {
            return Decision(false, Reason.ALBUM_IS_REISSUE_OR_COMPILATION, year)
        }
        if (hasEarlierMatchingAlbumEdition(evidence, knownCurrentYear)) {
            return Decision(false, Reason.EARLIER_ALBUM_EDITION_KNOWN, year)
        }
        return Decision(true, Reason.ACCEPTED, year)
    }

    private fun hasEarlierMatchingAlbumEdition(evidence: Evidence, currentYear: Int): Boolean {
        val album = evidence.album
        val selectedYear = album.year ?: return false
        val title = normalizedAlbumName(album.title)
        if (title.isEmpty()) return false
        val artistNames = album.artists.map(::normalizedText).filter(String::isNotEmpty).toSet()
        return evidence.otherVersions.any { version ->
            val olderYear = version.year
            if (olderYear == null || olderYear !in 1900..currentYear || olderYear >= selectedYear) {
                return@any false
            }
            if (normalizedAlbumName(version.title) != title) return@any false
            val otherArtists = version.artists.map(::normalizedText).filter(String::isNotEmpty).toSet()
            // Senza almeno un artista verificabile su ENTRAMBE le edizioni
            // evitiamo un falso match basato solo su un titolo comune.
            artistNames.isNotEmpty() && otherArtists.isNotEmpty() &&
                artistNames.intersect(otherArtists).isNotEmpty()
        }
    }

    private fun normalizedAlbumName(value: String): String =
        normalizedText(value.replace(removableEdition, " "))

    private fun normalizedText(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(marks, "")
            .lowercase(Locale.ROOT)
            .replace(punctuation, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
