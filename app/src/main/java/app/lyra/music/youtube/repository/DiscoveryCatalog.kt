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
            subtitle = "Brani da album dell'anno, escluse ristampe riconoscibili",
            groups = listOf(
                group("hit-italiane", "Hit del momento italiane", "nuove hit italiane"),
                group("hit-straniere", "Hit del momento straniere", "new international hits"),
            )
        ),
        FolderDefinition(
            id = "nazioni",
            title = "Italiane",
            subtitle = "Brani italiani classificati da Lyra",
            groups = listOf(
                group("italia", "Canzoni italiane",
                    "musica italiana playlist",
                    "canzoni italiane",
                    "Italian hits playlist",
                    "Italian pop and rock songs"),
            ),
        ),
        FolderDefinition(
            id = "decenni",
            title = "Decenni",
            subtitle = "Edizioni album del periodo; prima uscita del brano non certificata",
            groups = listOf(
                decade("80s", "Anni 80", "80s"),
                decade("90s", "Anni 90", "90s"),
                decade("2000s", "Anni 2000", "2000s"),
                decade("2010s", "Anni 2010", "2010s"),
                decade("2020s", "Anni 2020", "2020s"),
            ),
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

    fun groupsFor(
        folder: FolderDefinition,
        mode: DiscoverySettings.RegionMode,
    ): List<GroupDefinition> = folder.groups

    fun folderTitle(folder: FolderDefinition, mode: DiscoverySettings.RegionMode): String =
        if (folder.id == "nazioni") "Italiane" else folder.title

    fun folderSubtitle(folder: FolderDefinition, mode: DiscoverySettings.RegionMode): String =
        if (folder.id == "nazioni") "Canzoni italiane" else folder.subtitle

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
