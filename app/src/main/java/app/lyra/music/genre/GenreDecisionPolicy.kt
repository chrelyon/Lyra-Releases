package app.lyra.music.genre

/** Offline compatibility policy: a known rejection must never be reintroduced. */
object GenreDecisionPolicy {
    private val supportedPresets = setOf("house", "techno", "dance", "rock")

    fun supported(preset: String?): Boolean = preset != null && preset.lowercase() in supportedPresets

    fun shouldExclude(found: Boolean, decision: String?): Boolean =
        found && decision.equals("rejected", ignoreCase = true)

    fun keepQueue(candidateIds: List<String>, rejectedIds: Set<String>): List<String> {
        if (candidateIds.isEmpty()) return emptyList()
        val accepted = candidateIds.filterNot(rejectedIds::contains)
        return accepted
    }

    /** The pilot must be HTTPS on the fixed Tailscale Funnel port. */
    fun validEndpoint(raw: String): Boolean = runCatching {
        val url = java.net.URI(raw.trim().removeSuffix("/"))
        url.scheme.equals("https", ignoreCase = true) &&
            url.host?.lowercase()?.endsWith(".ts.net") == true &&
            url.host.orEmpty().length > ".ts.net".length &&
            url.port == 10000 &&
            url.userInfo == null &&
            (url.rawPath.isNullOrEmpty() || url.rawPath == "/") &&
            url.rawQuery == null && url.rawFragment == null
    }.getOrDefault(false)
}
