package app.lyra.music.personalization

import app.lyra.music.core.model.Song

internal object PersonalizedMixPolicy {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    fun eligible(song: Song): Boolean = !song.isLocal && videoId.matches(song.id) &&
        song.title.trim().length in 1..500 && song.artist.trim().length in 1..300

    fun freshTarget(known: Int): Int = minOf(12, (known * 3 + 6) / 7)

    fun warning(known: Int, fresh: Int, status: String): String? {
        val target = freshTarget(known)
        if (fresh >= target && target > 0) return null
        val reason = when (status) {
            "provider_error" -> "servizi di scoperta non disponibili"
            "no_preference_genre" -> "generi preferiti non ancora riconosciuti"
            else -> "proposte verificate insufficienti"
        }
        return "Mix parziale: $fresh novità su $target previste ($reason)."
    }
}
