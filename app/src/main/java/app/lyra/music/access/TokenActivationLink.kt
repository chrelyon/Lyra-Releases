package app.lyra.music.access

import java.net.URI
import java.net.URLDecoder

/** Parses a draft only. Opening a link never saves a token or authorizes a server request. */
object TokenActivationLink {
    private const val HOST = "vivisochat-lab.tail056f21.ts.net"
    private val tokenPattern = Regex("lyra_[A-Za-z0-9_-]{40,115}")

    fun parse(value: String?): String? = runCatching {
        if (value == null || value.length > 1024) return null
        val uri = URI(value)
        if (uri.rawUserInfo != null) return null
        val parameters = when (uri.scheme?.lowercase()) {
            "https" -> {
                if (uri.host?.lowercase() != HOST || uri.port !in setOf(-1,443,10000) ||
                    uri.rawPath != "/app/activate" || uri.rawQuery != null) return null
                uri.rawFragment
            }
            "lyra" -> {
                if (uri.host != "activate" || uri.port != -1 || uri.rawPath !in listOf("", "/") ||
                    uri.rawFragment != null) return null
                uri.rawQuery
            }
            else -> return null
        } ?: return null
        if ('&' in parameters || !parameters.startsWith("token=")) return null
        val token = URLDecoder.decode(parameters.removePrefix("token="), "UTF-8")
        token.takeIf(tokenPattern::matches)
    }.getOrNull()
}
