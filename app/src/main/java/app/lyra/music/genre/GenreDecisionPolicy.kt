package app.lyra.music.genre

/** Pure conservative policy: unknown and unavailable labels must never starve playback. */
object GenreDecisionPolicy {
    private val supportedPresets = setOf("house", "techno", "dance", "rock")

    fun supported(preset: String?): Boolean = preset != null && preset.lowercase() in supportedPresets

    fun shouldExclude(found: Boolean, decision: String?): Boolean =
        found && decision.equals("rejected", ignoreCase = true)

    fun keepQueue(candidateIds: List<String>, rejectedIds: Set<String>): List<String> {
        if (candidateIds.isEmpty()) return emptyList()
        val accepted = candidateIds.filterNot(rejectedIds::contains)
        // Fail-open if the whole radio would otherwise be empty.
        return accepted.ifEmpty { candidateIds }
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
