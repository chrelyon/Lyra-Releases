package app.lyra.music.personalization

import app.lyra.music.core.model.Song
import kotlin.random.Random

/**
 * Varieta' controllata delle radio automatiche: pesca solamente dal gruppo
 * piu' rilevante, ma evita di riproporre sempre gli stessi inizi e artisti.
 * La politica non interroga YouTube e non cambia il genere del preset.
 */
object AutomaticVarietyPolicy {
    private const val SEED_POOL = 20
    private const val FIRST_WINDOW = 12
    private const val NEXT_WINDOW = 6

    fun artistKey(artist: String?): String = artist.orEmpty().trim().lowercase()
        .split(Regex("\\s+(?:feat\\.?|featuring|ft\\.?|with|x)\\s+|\\s*[,&]\\s*"), limit = 2)
        .firstOrNull().orEmpty().trim()

    fun chooseSeed(
        rankedCandidates: List<Song>,
        recentSeedIds: Set<String> = emptySet(),
        recentProposalIds: Set<String> = emptySet(),
        avoidArtists: Set<String> = emptySet(),
        random: Random = Random.Default,
    ): Song? {
        val pool = rankedCandidates.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }
            .take(SEED_POOL)
            .toList()
        if (pool.isEmpty()) return null
        val allowed = pool.filterNot { artistKey(it.artist) in avoidArtists }
            .ifEmpty { pool }
        val notRecentSeed = allowed.filterNot { it.id in recentSeedIds }
            .ifEmpty { allowed }
        val fresh = notRecentSeed.filterNot { it.id in recentProposalIds }
            .ifEmpty { notRecentSeed }
        // Peschiamo solo tra brani rilevanti: l'indice originale conta ancora,
        // ma non obbliga sempre a selezionare il primo risultato.
        return weightedIndex(fresh.size, random) { index ->
            1.0 / (1.0 + index * 0.20)
        }.let(fresh::get)
    }

    fun diversifyRanked(
        ranked: List<Song>,
        recentProposalIds: Set<String> = emptySet(),
        recentOpeningIds: Set<String> = emptySet(),
        previousArtistKeys: List<String> = emptyList(),
        salt: Long = System.nanoTime(),
    ): List<Song> {
        val remaining = ranked.asSequence()
            .filter { it.id.isNotBlank() }
            .distinctBy { it.id }
            .toMutableList()
        if (remaining.size < 2) return remaining
        val random = Random(salt)
        val result = ArrayList<Song>(remaining.size)
        while (remaining.isNotEmpty()) {
            val first = result.isEmpty()
            val window = remaining.take(if (first) FIRST_WINDOW else NEXT_WINDOW)
            // Non allontanarsi dal ranking: per l'inizio campioniamo i primi
            // 12, per il resto soltanto i primi 6 ancora disponibili.
            var eligible = window
            if (first) {
                val notOpeners = eligible.filterNot { it.id in recentOpeningIds }
                if (notOpeners.isNotEmpty()) eligible = notOpeners
            }
            val fresh = eligible.filterNot { it.id in recentProposalIds }
            if (fresh.isNotEmpty()) eligible = fresh
            val previous = (previousArtistKeys + result.takeLast(2).map { artistKey(it.artist) })
                .takeLast(2).filter { it.isNotBlank() }.toSet()
            val differentArtists = eligible.filterNot { artistKey(it.artist) in previous }
            if (differentArtists.isNotEmpty()) eligible = differentArtists
            // Se un artista occupa piu' della meta' dei posti residui, va
            // distribuito subito: rimandarlo creerebbe duplicati consecutivi
            // inevitabili alla fine della lista, anche con altri artisti liberi.
            val counts = remaining.groupingBy { artistKey(it.artist) }.eachCount()
            val urgent = eligible.filter { song ->
                val key = artistKey(song.artist)
                key.isNotBlank() && (counts[key] ?: 0) * 2 > remaining.size
            }
            if (urgent.isNotEmpty()) eligible = urgent
            val selected = weightedIndex(eligible.size, random) { index ->
                val originalPosition = window.indexOfFirst { it.id == eligible[index].id }
                1.0 / (1.0 + originalPosition * 0.23)
            }
            val song = eligible[selected]
            remaining.removeAt(remaining.indexOfFirst { it.id == song.id })
            result.add(song)
        }
        return result
    }

    private inline fun weightedIndex(size: Int, random: Random, weight: (Int) -> Double): Int {
        require(size > 0)
        val total = (0 until size).sumOf(weight)
        var cursor = random.nextDouble() * total
        for (index in 0 until size) {
            cursor -= weight(index)
            if (cursor < 0.0) return index
        }
        return size - 1
    }
}
