package app.pictureshow.core

import io.ktor.http.Url

object AddressCandidates {
    /** Scheme-less input tries HTTPS first. Explicit schemes and ports are respected. */
    fun build(input: String): List<String> {
        val text = input.trim().trimEnd('/')
        require(text.isNotBlank() && text.none { it.isWhitespace() }) { "Enter a server address." }
        val explicitScheme = text.contains("://")
        val parsed = try {
            Url(if (explicitScheme) text else "https://$text")
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid server address.")
        }
        require(parsed.protocol.name in listOf("http", "https")) { "Use an HTTP or HTTPS address." }
        require(parsed.host.isNotBlank() && parsed.user == null && parsed.password == null) {
            "Enter a server address without credentials."
        }
        require(parsed.parameters.isEmpty() && parsed.fragment.isEmpty()) { "Remove the query or fragment from the server address." }
        val authority = text.substringAfter("://", text).substringBefore('/')
        val explicitPort = if (authority.startsWith('[')) {
            authority.substringAfter(']', "").startsWith(':')
        } else {
            authority.contains(':')
        }
        require(parsed.port in 1..65535) { "Use a port between 1 and 65535." }
        val host = if (parsed.host.contains(':')) "[${parsed.host.removeSurrounding("[", "]")}]" else parsed.host
        val path = parsed.encodedPath.trimEnd('/')
        val schemes = if (explicitScheme) listOf(parsed.protocol.name) else listOf("https", "http")
        return schemes.flatMap { scheme ->
            val ports = if (explicitPort) listOf(parsed.port) else if (scheme == "https") listOf(443, 8920) else listOf(80, 8096)
            ports.map { port ->
                val suffix = if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port"
                "$scheme://$host$suffix$path"
            }
        }.distinct()
    }
}
