package app.lyra.music.playback

/** Stable car tabs; the host owns their layout. No credentials enter media metadata. */
internal object AutoLibraryPolicy {
    enum class Entry { HOME, DEVICE, RECENT, YTM, RANDOM, DISCOVER, FAVORITES, PLAYLISTS }
    data class State(val serverAuthorized: Boolean, val serverOnline: Boolean, val googleAuthorized: Boolean, val googleGeneration: Long = 0L)
    fun root(state: State): List<Entry> = listOf(Entry.HOME, Entry.DEVICE, Entry.RECENT) +
        if (state.googleAuthorized) listOf(Entry.YTM) else emptyList()
    fun home(state: State): List<Entry> = listOf(
        if (state.serverAuthorized) Entry.DISCOVER else Entry.RANDOM, Entry.DEVICE, Entry.RECENT,
    ) + if (state.googleAuthorized) listOf(Entry.FAVORITES, Entry.PLAYLISTS) else emptyList()
    fun serverLabel(state: State): String = if (state.serverAuthorized && state.serverOnline)
        "Server attivo" else "Server non attivo"
}
