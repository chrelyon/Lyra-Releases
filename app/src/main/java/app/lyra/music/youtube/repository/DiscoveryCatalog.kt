package app.lyra.music.youtube.repository

/**
 * Catalogo locale di Scopri.
 *
 * Le query sono volutamente orientate a Italia ed Europa: InnerTubeX non espone
 * un filtro geografico affidabile sulle playlist, quindi Lyra combina query
 * regionali, ranking locale e filtro dei risultati palesemente fuori target.
 */
internal object DiscoveryCatalog {
    data class FolderDefinition(
        val id: String,
        val title: String,
        val subtitle: String,
        val groups: List<GroupDefinition>,
    )

    data class GroupDefinition(
        val id: String,
        val title: String,
        val queries: List<String>,
    )

    val folders = listOf(
        FolderDefinition(
            id = "novita",
            title = "Novità",
            subtitle = "Brani italiani e stranieri dell'anno corrente",
            groups = listOf(group("tutte", "Novità", "nuove hit italiane", "new international hits")),
        ),
        FolderDefinition(
            id = "mood",
            title = "Mood",
            subtitle = "Scegli il mood, Lyra prepara direttamente la musica",
            groups = listOf(
                group("relax", "Relax",
                    "relax Italia playlist",
                    "European relax playlist",
                    "relax music Europe playlist"),
                group("chill", "Chill",
                    "chill Italia playlist",
                    "European chill playlist",
                    "chill music Europe playlist"),
                group("energia", "Energia",
                    "energia musica Italia playlist",
                    "energy boost Europe playlist",
                    "European energy hits playlist"),
                group("party", "Party",
                    "party hits Italia playlist",
                    "European party hits playlist",
                    "party music Europe playlist"),
                group("romantica", "Romantica",
                    "canzoni romantiche italiane playlist",
                    "romantic Europe playlist",
                    "European love songs playlist"),
                group("focus", "Concentrazione",
                    "focus Italia playlist",
                    "focus music Europe playlist",
                    "European study focus playlist"),
                group("malinconica", "Malinconica",
                    "malinconica italiana playlist",
                    "sad songs Europe playlist",
                    "European emotional playlist"),
            ),
        ),
        FolderDefinition(
            id = "decenni",
            title = "Decenni",
            subtitle = "Dagli anni 80 a oggi",
            groups = listOf(
                decade("80s", "Anni 80", "80s"),
                decade("90s", "Anni 90", "90s"),
                decade("2000s", "Anni 2000", "2000s"),
                decade("2010s", "Anni 2010", "2010s"),
                decade("2020s", "Anni 2020", "2020s"),
            ),
        ),
        FolderDefinition(
            id = "italiane",
            title = "Italiane",
            subtitle = "Canzoni italiane dal catalogo Lyra e nuove proposte",
            groups = listOf(group("italia", "Italiane", "Italian hits playlist")),
        ),
    )

    fun queriesFor(
        definition: GroupDefinition,
        mode: DiscoverySettings.RegionMode,
    ): List<String> = when (mode) {
        DiscoverySettings.RegionMode.ITALY -> {
            val italian = definition.queries.filter { query ->
                val text = query.lowercase()
                listOf("italia", "italian", "italiane", "italiano", "italiana", "italy")
                    .any(text::contains)
            }
            (italian + listOf(
                "${definition.title} Italia playlist",
                "${definition.title} Italian music playlist",
            )).distinct()
        }

        DiscoverySettings.RegionMode.ITALY_EUROPE -> definition.queries

        DiscoverySettings.RegionMode.INTERNATIONAL -> listOf(
            "${definition.title} playlist",
            "${definition.title} hits playlist",
            "${definition.title} music playlist",
            "best ${definition.title} playlist",
        )
    }

    fun groupsFor(folder: FolderDefinition): List<GroupDefinition> = folder.groups

    private fun group(id: String, title: String, vararg queries: String) =
        GroupDefinition(id = id, title = title, queries = queries.toList())

    private fun decade(id: String, title: String, decade: String) =
        group(
            id,
            title,
            "$decade Italia hits playlist",
            "$decade European hits playlist",
            "$decade Italy music playlist",
            "$decade Europe playlist",
        )
}
