package app.pictureshow.core.playback

import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server
import io.ktor.http.Url

/**
 * What the player opens: the chosen media source, how it plays, the URL and the headers every request must carry
 * (manifests, segments and the stream itself). The token travels only in the Authorization header; [toString]
 * masks every header value and any `ApiKey` in a URL so a decision can be logged.
 */
class PlayDecision(
    val itemId: String,
    val mediaSource: MediaSourceInfo,
    val method: PlayMethod,
    val url: String,
    val headers: Map<String, String>,
    val playSessionId: String?,
    /** Why the server transcodes, from the transcoding URL's `TranscodeReasons` (empty unless [method] is Transcode). */
    val transcodeReasons: List<String> = emptyList()
) {
    val mediaSourceId: String? get() = mediaSource.id

    override fun toString(): String = "PlayDecision(itemId=$itemId, mediaSourceId=$mediaSourceId, method=$method, " +
        "url=${Redaction.url(url)}, headers=${Redaction.headers(headers)}, playSessionId=$playSessionId, " +
        "transcodeReasons=$transcodeReasons)"
}

sealed interface PlayOutcome {
    data class Play(val decision: PlayDecision) : PlayOutcome

    data class Unplayable(val reason: UnplayableReason, val message: String) : PlayOutcome
}

enum class UnplayableReason {
    /** The server refused (PlaybackInfoResponse.ErrorCode). */
    SERVER_REFUSED,

    /** No media source can be direct played, direct streamed or transcoded for this device. */
    NO_COMPATIBLE_SOURCE,

    /** The requested media source is not in the response. */
    SOURCE_NOT_FOUND,

    /** The stream URL cannot safely receive this server's authentication. */
    UNSAFE_STREAM_URL
}

object PlayDecisions {
    /**
     * Picks the media source and play method from a PlaybackInfo response.
     *
     * @param mediaSourceId The source the user chose (a version), or null for the server's first (preferred) source.
     */
    fun decide(
        server: Server,
        itemId: String,
        response: PlaybackInfoResponse,
        identity: ClientIdentity,
        token: String,
        mediaSourceId: String? = null
    ): PlayOutcome {
        response.errorCode?.let { code ->
            return PlayOutcome.Unplayable(UnplayableReason.SERVER_REFUSED, "The server refused playback ($code).")
        }
        val candidates = if (mediaSourceId != null) {
            response.mediaSources.filter { it.id == mediaSourceId }.ifEmpty {
                return PlayOutcome.Unplayable(UnplayableReason.SOURCE_NOT_FOUND, "The server did not return the chosen version.")
            }
        } else {
            response.mediaSources
        }
        val source = candidates.firstOrNull(::playable)
            ?: return PlayOutcome.Unplayable(UnplayableReason.NO_COMPATIBLE_SOURCE, "This video cannot be played on this device.")
        val headers = buildMap {
            source.requiredHttpHeaders.forEach { (name, value) ->
                if (value != null &&
                    !name.equals("Authorization", ignoreCase = true) &&
                    name.lowercase() !in setOf("apikey", "api_key", "x-emby-token", "x-mediabrowser-token")
                ) {
                    put(name, value)
                }
            }
            put("Authorization", AuthorizationHeader.build(identity, token))
        }
        val playSessionId = response.playSessionId
        val decision = when {
            source.supportsDirectPlay || source.supportsDirectStream -> PlayDecision(
                itemId = itemId,
                mediaSource = source,
                method = if (source.supportsDirectPlay) PlayMethod.DirectPlay else PlayMethod.DirectStream,
                url = staticStreamUrl(server, itemId, source, playSessionId),
                headers = headers,
                playSessionId = playSessionId
            )
            else -> {
                val transcodingUrl = source.transcodingUrl.orEmpty()
                val resolved = resolve(server, Redaction.stripApiKey(transcodingUrl))
                PlayDecision(
                    itemId = itemId,
                    mediaSource = source,
                    method = PlayMethod.Transcode,
                    url = resolved,
                    headers = headers,
                    playSessionId = playSessionId,
                    transcodeReasons = transcodeReasons(transcodingUrl)
                )
            }
        }
        if (!isSameOrigin(server, decision.url)) {
            return PlayOutcome.Unplayable(
                UnplayableReason.UNSAFE_STREAM_URL,
                "The server returned a stream URL outside its origin or an invalid URL. " +
                    "Playback was blocked to protect your sign-in."
            )
        }
        return PlayOutcome.Play(decision)
    }

    private fun playable(source: MediaSourceInfo): Boolean =
        source.supportsDirectPlay || source.supportsDirectStream || !source.transcodingUrl.isNullOrBlank()

    /** `GET /Videos/{itemId}/stream?static=true`: the original file, byte-range seekable. */
    internal fun staticStreamUrl(server: Server, itemId: String, source: MediaSourceInfo, playSessionId: String?): String {
        val query = buildList {
            add("static" to "true")
            source.id?.let { add("mediaSourceId" to it) }
            playSessionId?.let { add("playSessionId" to it) }
            source.eTag?.let { add("tag" to it) }
        }
        return base(server) + "/Videos/" + encode(itemId) + "/stream?" + query.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    /**
     * Jellyfin root-relative paths omit the configured base path, so `/videos/...` stays below that base path.
     * Absolute and protocol-relative references retain their own authority; callers must check [isSameOrigin]
     * before attaching authentication, including for subtitle URLs and every manifest/segment/redirect request.
     */
    fun resolve(server: Server, relative: String): String = when {
        relative.startsWith("//") -> server.url.substringBefore(':') + ":" + relative
        SCHEME.containsMatchIn(relative) -> relative
        relative.startsWith("/") -> base(server) + relative
        else -> base(server) + "/" + relative
    }

    /**
     * Whether an absolute request URL may receive this server's authentication. Path does not affect origin;
     * scheme, case-insensitive host and effective port must all match. Relative references must be resolved first.
     * Invalid or ambiguous URLs, embedded credentials and non-HTTP(S) schemes are rejected conservatively.
     */
    fun isSameOrigin(server: Server, url: String): Boolean {
        val origin = absoluteHttpUrl(server.url) ?: return false
        val target = absoluteHttpUrl(url) ?: return false
        return origin.protocol.name.equals(target.protocol.name, ignoreCase = true) &&
            origin.host.equals(target.host, ignoreCase = true) &&
            origin.port == target.port
    }

    private fun absoluteHttpUrl(value: String): Url? {
        if (!HTTP_AUTHORITY.containsMatchIn(value) ||
            value.any { it.isWhitespace() || it.code < 32 || it.code == 127 || it == '\\' }
        ) {
            return null
        }
        val authority = value.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority || '%' in authority) return null
        val portSuffix = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return null
            val suffix = authority.substring(close + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith(':')) return null
            suffix.removePrefix(":").takeIf { suffix.isNotEmpty() }
        } else {
            if (authority.count { it == ':' } > 1) return null
            authority.substringAfter(':').takeIf { ':' in authority }
        }
        // Ktor uses zero as its unspecified-port sentinel; an explicit :0 must not equal the default port.
        if (portSuffix != null) {
            val explicitPort = portSuffix.toIntOrNull()
            if (portSuffix.isEmpty() || portSuffix.any { !it.isDigit() } || explicitPort == null || explicitPort !in 1..65535) return null
        }
        return runCatching { Url(value) }.getOrNull()?.takeIf {
            it.host.isNotEmpty() &&
                it.user == null &&
                it.password == null &&
                (it.protocol.name.equals("http", true) || it.protocol.name.equals("https", true))
        }
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
    private val HTTP_AUTHORITY = Regex("^https?://", RegexOption.IGNORE_CASE)

    internal fun transcodeReasons(url: String): List<String> {
        val value = query(url).firstOrNull { it.first.equals("TranscodeReasons", ignoreCase = true) }?.second ?: return emptyList()
        return decode(value).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun base(server: Server): String = server.url.trimEnd('/')

    private fun query(url: String): List<Pair<String, String>> {
        val query = url.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return emptyList()
        return query.split('&').filter { it.isNotEmpty() }.map { it.substringBefore('=') to it.substringAfter('=', "") }
    }

    internal fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val code = byte.toInt() and 255
            if (code in 65..90 || code in 97..122 || code in 48..57 || code.toChar() in "-._~") {
                append(code.toChar())
            } else {
                append('%')
                append(HEX[code ushr 4])
                append(HEX[code and 15])
            }
        }
    }

    internal fun decode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                    continue
                }
            }
            if (c == '+') bytes += ' '.code.toByte() else c.toString().encodeToByteArray().forEach { bytes += it }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/** Keeps access tokens out of anything that may be logged. */
object Redaction {
    private const val MASK = "REDACTED"
    private val tokenField = Regex("(Token=\")[^\"]*(\")")

    /** Removes `ApiKey`/`api_key` from a URL; the Authorization header carries the token instead. */
    fun stripApiKey(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val kept = url.substring(queryStart + 1, fragmentStart).split('&').filterNot(::tokenParameter)
        val prefix = url.substring(0, queryStart)
        val fragment = url.substring(fragmentStart)
        return if (kept.isEmpty()) prefix + fragment else prefix + "?" + kept.joinToString("&") + fragment
    }

    fun url(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val masked = url.substring(queryStart + 1, fragmentStart).split('&').map { part ->
            if (tokenParameter(part)) part.substringBefore('=') + "=" + MASK else part
        }
        return url.substring(0, queryStart + 1) + masked.joinToString("&") + url.substring(fragmentStart)
    }

    private fun tokenParameter(part: String): Boolean {
        val name = PlayDecisions.decode(part.substringBefore('='))
        return TOKEN_PARAMETERS.any { it.equals(name, ignoreCase = true) }
    }

    fun header(name: String, value: String): String = when {
        name.equals("Authorization", ignoreCase = true) || name.equals("X-Emby-Authorization", ignoreCase = true) ->
            tokenField.replace(value) { it.groupValues[1] + MASK + it.groupValues[2] }
        TOKEN_PARAMETERS.any { it.equals(name, ignoreCase = true) } -> MASK
        else -> value
    }

    /** Required headers are arbitrary credentials: diagnostics retain names but never print any value. */
    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { MASK }

    private val TOKEN_PARAMETERS = listOf("ApiKey", "api_key", "X-Emby-Token", "X-MediaBrowser-Token")
}
