package app.lyra.music.personalization

import app.lyra.music.core.model.Song

/** Soft preference; unclassified music remains eligible and no nationality is guessed. */
object RegionPreferencePolicy {
    private val europe = "IT FR DE ES PT GB IE NL BE LU CH AT DK SE NO FI IS PL CZ SK HU SI HR BA RS ME AL MK GR RO BG EE LV LT UA MD BY MT CY TR".split(" ").toSet()
    fun preferred(song: Song, mode: String): Boolean? {
        val country = if (song.origin == "italiano") "IT" else song.countryCode.orEmpty().uppercase()
        return when (mode) {
            "ITALY" -> if (country == "IT") true else if (country.isNotEmpty() || song.origin == "straniero") false else null
            "ITALY_EUROPE" -> country.takeIf { it.isNotEmpty() }?.let { it in europe }
            else -> if (country == "IT") false else if (country.isNotEmpty() || song.origin == "straniero") true else null
        }
    }
    fun mix(preferred: List<Song>, outside: List<Song>, limit: Int): List<Song> {
        val yes=preferred.iterator(); val no=outside.iterator()
        return buildList {
            while ((yes.hasNext() || no.hasNext()) && size < limit) {
                repeat(4) { if (yes.hasNext() && size < limit) add(yes.next()) }
                if (no.hasNext() && size < limit) add(no.next())
            }
        }.distinctBy { it.id }
    }
    fun order(songs: List<Song>, mode: String): List<Song> {
        val yes=songs.filter { preferred(it,mode)==true }
        if (yes.isEmpty()) return songs
        val no=songs.filter { preferred(it,mode)==false }
        return mix(yes,no,songs.size)+songs.filter { preferred(it,mode)==null }
    }
    fun queries(mode: String): Pair<String,String> = when(mode) {
        "ITALY" -> "musica italiana canzoni" to "global international songs"
        "ITALY_EUROPE" -> "European Italian French German Spanish music" to "American Latin Asian global songs"
        else -> "global international music hits" to "musica italiana canzoni"
    }
}
