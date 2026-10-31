package app.lyra.music.core.model

data class DiscoverySnapshot(
    val topSongs: List<Song>,
    val playlists: List<Playlist>,
    val genres: List<DiscoveryGenreSection> = emptyList(),
    val regionCode: String,
)

data class DiscoveryGenreSection(
    val id: String,
    val title: String,
    val playlists: List<Playlist>,
)

/**
 * Cartella di primo livello della nuova sezione Scopri.
 *
 * Le definizioni sono locali e leggere: le playlist vengono caricate soltanto
 * quando l'utente apre la cartella, evitando decine di richieste all'avvio.
 */
data class DiscoveryFolderSummary(
    val id: String,
    val title: String,
    val subtitle: String,
    val groupCount: Int,
)

data class DiscoveryGroupSummary(
    val id: String,
    val title: String,
    val playlistCount: Int? = null,
)

data class DiscoveryFolderContent(
    val id: String,
    val title: String,
    val subtitle: String,
    val groups: List<DiscoveryGroupSection>,
)

data class DiscoveryGroupSection(
    val id: String,
    val title: String,
    val playlists: List<Playlist>,
)
