package app.lyra.music.youtube.repository

/**
 * Menu Scopri unico: la schermata dell'app e Android Auto lo costruiscono
 * entrambi da questa definizione, cosi' voci e ordine restano identici.
 */
internal object DiscoverMenu {
    const val AUTOMATIC_ID = "automatic"
    const val MIX_ID = "mix"
    const val DECADES_ID = "decenni"

    enum class Kind { AUTOMATIC_FOLDER, MIX, DIRECT, DECADES_FOLDER }

    data class Entry(
        val id: String,
        val title: String,
        val subtitle: String,
        val kind: Kind,
        /** Gruppo riprodotto direttamente quando kind == DIRECT. */
        val groupId: String? = null,
    )

    val entries: List<Entry> by lazy {
        fun folder(id: String) = requireNotNull(DiscoveryCatalog.folders.firstOrNull { it.id == id })
        val novita = folder("novita")
        val italiane = folder("italiane")
        val decenni = folder(DECADES_ID)
        listOf(
            Entry(
                AUTOMATIC_ID, "Playlist automatiche",
                "Scegli un genere o cerca artisti e album", Kind.AUTOMATIC_FOLDER,
            ),
            Entry(
                MIX_ID, "Mix per me",
                "Circa 70% ascolti e Preferiti, 30% novità quando disponibili", Kind.MIX,
            ),
            Entry(novita.id, novita.title, novita.subtitle, Kind.DIRECT, novita.groups.first().id),
            Entry(italiane.id, italiane.title, italiane.subtitle, Kind.DIRECT, italiane.groups.first().id),
            Entry(decenni.id, decenni.title, decenni.subtitle, Kind.DECADES_FOLDER),
        )
    }

    fun entry(id: String): Entry? = entries.firstOrNull { it.id == id }

    val decades: List<DiscoveryCatalog.GroupDefinition>
        get() = requireNotNull(DiscoveryCatalog.folders.firstOrNull { it.id == DECADES_ID }).groups
}
