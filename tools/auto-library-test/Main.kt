package app.lyra.music.playback

fun main() {
    for (server in listOf(false, true)) for (google in listOf(false, true)) {
        val state = AutoLibraryPolicy.State(server, server, google)
        val tabs = AutoLibraryPolicy.root(state)
        check(tabs.take(3) == listOf(AutoLibraryPolicy.Entry.HOME, AutoLibraryPolicy.Entry.DEVICE, AutoLibraryPolicy.Entry.RECENT))
        check(tabs.size <= 4)
        val home = AutoLibraryPolicy.home(state)
        check(home.first() == if (server) AutoLibraryPolicy.Entry.DISCOVER else AutoLibraryPolicy.Entry.RANDOM)
        check((AutoLibraryPolicy.Entry.FAVORITES in home) == google)
        check((AutoLibraryPolicy.Entry.PLAYLISTS in home) == google)
        check((AutoLibraryPolicy.Entry.YTM in tabs) == google)
    }
    // A saved token without verified authorization must never unlock Discovery.
    val disconnected = AutoLibraryPolicy.State(false, false, true)
    check(AutoLibraryPolicy.home(disconnected).first() == AutoLibraryPolicy.Entry.RANDOM)
    check(AutoLibraryPolicy.serverLabel(disconnected) == "Server non attivo")
    check(AutoLibraryPolicy.serverLabel(AutoLibraryPolicy.State(true, true, false)) == "Server attivo")
    check(AutoLibraryPolicy.serverLabel(AutoLibraryPolicy.State(true, false, false)) == "Server non attivo")
    println("PASS all token/Google combinations, stable four-tab limit, random/Discovery switch and honest server indicator")
}
