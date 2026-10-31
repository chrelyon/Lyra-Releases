package app.lyra.music.cloud

import app.lyra.music.genre.GenreDecisionPolicy

/** Pure routing and safety gates; never falls back to Cloudflare. */
object UbuntuOnlyCutoverPolicy {
    fun endpoint(classifier: String, previousUbuntu: String): String =
        sequenceOf(classifier, previousUbuntu)
            .map { it.trim().trimEnd('/') }
            .firstOrNull { GenreDecisionPolicy.validEndpoint(it) } ?: ""

    fun canUpload(enabled: Boolean, restored: Boolean, endpoint: String): Boolean =
        enabled && restored && GenreDecisionPolicy.validEndpoint(endpoint)
}
