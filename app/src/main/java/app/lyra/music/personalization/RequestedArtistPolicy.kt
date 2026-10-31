package app.lyra.music.personalization

import java.text.Normalizer

/** Match an actual credit, not a partial name or the title of a cover. */
object RequestedArtistPolicy {
    private fun normalize(value: String): String = Normalizer.normalize(value,Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"),"").lowercase().replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun matches(requested: String, credits: List<String>): Boolean {
        val target=normalize(requested)
        return target.isNotEmpty() && credits.any { normalize(it)==target }
    }
}
