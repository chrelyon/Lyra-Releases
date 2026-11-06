package app.lyra.music.core.model

/**
 * Unica definizione dei menu della sezione Scopri.
 *
 * La schermata Android e il browser Android Auto leggono entrambi questi
 * elementi: ordine, voci e generi non possono divergere silenziosamente.
 */
object DiscoverMenuCatalog {
    data class RootItem(
        val id: String,
        val title: String,
        val subtitle: String,
    )

    data class AutomaticPreset(
        val id: String,
        val title: String,
    )

    const val AUTOMATIC_ID = "automatic"
    const val DAILY_MIX_ID = "daily"
    const val NEW_RELEASES_ID = "new-releases"
    const val NEW_RELEASES_FOLDER_ID = "novita"
    const val NEW_RELEASES_GROUP_ID = "nuove-uscite"

    val rootItems = listOf(
        RootItem(AUTOMATIC_ID, "Playlist Automatica", "Pop, Rock, House, Techno"),
        RootItem(DAILY_MIX_ID, "Mix per me", "Ascolti completi, skip, gradimento e brani preferiti"),
        RootItem(NEW_RELEASES_ID, "Novità", "Brani con anno di uscita corrente nei metadati"),
    )

    val automaticPresets = listOf(
        AutomaticPreset("pop", "Pop"),
        AutomaticPreset("rock", "Rock"),
        AutomaticPreset("house", "House"),
        AutomaticPreset("techno", "Techno"),
    )
}
