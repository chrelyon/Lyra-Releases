package app.lyra.music.youtube.repository

import java.util.Calendar

/**
 * Controlli conservativi sulle etichette delle playlist pubbliche di Scopri.
 * I metadata di una PlaylistItem NON riportano l'anno di pubblicazione di
 * ciascun brano: il filtro "Novita'" richiede pertanto un riferimento
 * esplicito all'anno in corso e rifiuta le raccolte marcate con anni passati.
 * Questa e' una selezione editoriale, non una certificazione della data dei brani.
 */
internal object DiscoveryPlaylistQuality {
    private val yearPattern = Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")
    private val decadePattern = Regex("(?<!\\d)(?:19(?:60|70|80|90)|20(?:00|10)|60|70|80|90)s?(?!\\d)")
    private val freshWords = Regex("\\b(novit[aà]|nuov[eaio]|new|latest|recent|fresh|uscite|releases?|hits?|hit|viral[i]?|trending|oggi|today)\\b", RegexOption.IGNORE_CASE)

    private val genreTerms = mapOf(
        "pop" to listOf("pop"),
        "rock" to listOf("rock"),
        "rap" to listOf("rap", "hip hop", "hip-hop", "trap"),
        "dance" to listOf("dance"),
        "house" to listOf("house"),
        "techno" to listOf("techno"),
        "edm" to listOf("edm", "elettronica", "electronic"),
        "indie" to listOf("indie"),
        "rnb" to listOf("r&b", "rnb", "soul"),
        "metal" to listOf("metal"),
        "jazz" to listOf("jazz"),
        "classica" to listOf("classica", "classical", "classic music"),
        "reggae" to listOf("reggae"),
        "cantautorato" to listOf("cantautori", "cantautorato", "songwriter"),
    )

    private val moodTerms = mapOf(
        "relax" to listOf("relax", "relaxing"),
        "chill" to listOf("chill", "chillout", "chill-out"),
        "energia" to listOf("energia", "energy", "power", "energetic"),
        "party" to listOf("party", "festa", "club"),
        "romantica" to listOf("romantic", "romantica", "love songs"),
        "focus" to listOf("focus", "concentrazione", "deep work"),
        "malinconica" to listOf("sad", "malinconia", "melancholy", "heartbreak"),
    )

    private val activityTerms = mapOf(
        "workout" to listOf("workout", "palestra", "gym", "fitness"),
        "running" to listOf("running", "jogging", "corsa"),
        "guida" to listOf("driving", "road trip", "viaggio", "guida"),
        "studio" to listOf("study", "studio", "studying"),
        "lavoro" to listOf("work", "lavoro", "office"),
        "cena" to listOf("dinner", "cena"),
        "sonno" to listOf("sleep", "sonno", "sleeping"),
    )

    fun accepts(
        folderId: String,
        groupId: String,
        title: String,
        description: String?,
        currentYear: Int = Calendar.getInstance().get(Calendar.YEAR),
    ): Boolean {
        if (folderId == "novita" && !isFreshCollection(title, description, currentYear)) return false
        if (folderId == "generi" && !isCategoryCompatible(groupId, title, genreTerms)) return false
        if (folderId == "mood" && !isCategoryCompatible(groupId, title, moodTerms)) return false
        if (folderId == "attivita" && !isCategoryCompatible(groupId, title, activityTerms)) return false
        if (folderId == "decenni" && !isDecadeCompatible(groupId, title)) return false
        return true
    }

    /**
     * Punteggio solo su segnali disponibili: NON e' il numero di visualizzazioni.
     * Una playlist generica non deve battere una raccolta con categoria chiara.
     */
    fun qualityScore(folderId: String, groupId: String, title: String, songCount: Int?): Int {
        val normalized = title.lowercase()
        var score = 0
        if (songCount != null) {
            if (songCount < 5) return -100
            score += when {
                songCount in 12..120 -> 12
                songCount in 5..200 -> 7
                else -> 1
            }
        }
        val terms = when (folderId) {
            "generi" -> genreTerms[groupId]
            "mood" -> moodTerms[groupId]
            "attivita" -> activityTerms[groupId]
            else -> null
        }
        if (terms != null) {
            if (terms.any { normalized.containsTerm(it) }) score += 18
            else score -= 8
        }
        if (folderId == "decenni" && isDecadeCompatible(groupId, title)) score += 9
        if (normalized.contains(Regex("(?<![\\p{L}])(?:mix|playlist|hits|essentials|selezione|raccolta)(?![\\p{L}])"))) score += 2
        if (normalized.contains(Regex("(?<!\\d)(?:19|20)\\d{2}(?!\\d)")) && folderId != "decenni") score -= 2
        return score
    }

    private fun isFreshCollection(title: String, description: String?, year: Int): Boolean {
        val titleYears = yearPattern.findAll(title).map { it.value.toInt() }.toList()
        // L'anno passato (o un anno futuro ingannevole) prevale su "2026".
        if (titleYears.any { it != year }) return false
        val descriptionYears = yearPattern.findAll(description.orEmpty()).map { it.value.toInt() }.toList()
        if (descriptionYears.any { it != year }) return false
        val explicitlyCurrent = year in titleYears || year in descriptionYears
        return explicitlyCurrent && freshWords.containsMatchIn(title)
    }

    private fun isCategoryCompatible(
        groupId: String,
        title: String,
        categories: Map<String, List<String>>,
    ): Boolean {
        val expected = categories[groupId] ?: return true
        if (expected.any { title.containsTerm(it) }) return true
        // Senza un'etichetta affidabile evitiamo almeno gli scambi evidenti:
        // Techno nella cartella House, Rock nella cartella Pop, ecc.
        return categories.entries.none { (other, terms) ->
            other != groupId && terms.any { title.containsTerm(it) }
        }
    }

    private fun isDecadeCompatible(groupId: String, title: String): Boolean {
        val expectedDecade = when (groupId) {
            "60s" -> "60"
            "70s" -> "70"
            "80s" -> "80"
            "90s" -> "90"
            "2000s" -> "2000"
            "2010s" -> "2010"
            else -> return true
        }
        val mentioned = decadePattern.findAll(title)
            .map { match ->
                val digits = match.value.removeSuffix("s")
                if (digits.startsWith("19")) digits.takeLast(2) else digits
            }.toList()
        return mentioned.isEmpty() || mentioned.all { it == expectedDecade }
    }

    private fun String.containsTerm(term: String): Boolean =
        Regex("(?<![\\p{L}\\d])${Regex.escape(term)}(?![\\p{L}\\d])", RegexOption.IGNORE_CASE)
            .containsMatchIn(this)
}
