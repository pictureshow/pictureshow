package app.pictureshow.core.playback

import app.pictureshow.core.AuthorizationHeader
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.Server

/**
 * What the player opens: the chosen media source, how it plays, the URL and the headers every request must carry
 * (manifests, segments and the stream itself). The token travels only in the Authorization header; [toString]
 * redacts it and any `ApiKey` in a URL so a decision can be logged.
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
    SOURCE_NOT_FOUND
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
                PlayDecision(
                    itemId = itemId,
                    mediaSource = source,
                    method = PlayMethod.Transcode,
                    url = resolve(server, Redaction.stripApiKey(transcodingUrl)),
                    headers = headers,
                    playSessionId = playSessionId,
                    transcodeReasons = transcodeReasons(transcodingUrl)
                )
            }
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

    /** The server returns TranscodingUrl relative to its root without the base path, e.g. `/videos/{id}/master.m3u8?...`. */
    fun resolve(server: Server, relative: String): String = when {
        relative.startsWith("http://", ignoreCase = true) || relative.startsWith("https://", ignoreCase = true) -> relative
        relative.startsWith("/") -> base(server) + relative
        else -> base(server) + "/" + relative
    }

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

    private fun decode(value: String): String {
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
    private val apiKeyParameter = Regex("([?&](?:api_?key|ApiKey|X-Emby-Token|X-MediaBrowser-Token)=)[^&#]*", RegexOption.IGNORE_CASE)
    private val tokenField = Regex("(Token=\")[^\"]*(\")")

    /** Removes `ApiKey`/`api_key` from a URL; the Authorization header carries the token instead. */
    fun stripApiKey(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url
        val fragmentStart = url.indexOf('#', queryStart).let { if (it < 0) url.length else it }
        val kept = url.substring(queryStart + 1, fragmentStart).split('&').filter { part ->
            val name = part.substringBefore('=')
            part.isNotEmpty() && !TOKEN_PARAMETERS.any { it.equals(name, ignoreCase = true) }
        }
        val prefix = url.substring(0, queryStart)
        val fragment = url.substring(fragmentStart)
        return if (kept.isEmpty()) prefix + fragment else prefix + "?" + kept.joinToString("&") + fragment
    }

    fun url(url: String): String = apiKeyParameter.replace(url) { it.groupValues[1] + MASK }

    fun header(name: String, value: String): String = when {
        name.equals("Authorization", ignoreCase = true) || name.equals("X-Emby-Authorization", ignoreCase = true) ->
            tokenField.replace(value) { it.groupValues[1] + MASK + it.groupValues[2] }
        TOKEN_PARAMETERS.any { it.equals(name, ignoreCase = true) } -> MASK
        else -> value
    }

    fun headers(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) -> header(name, value) }

    private val TOKEN_PARAMETERS = listOf("ApiKey", "api_key", "X-Emby-Token", "X-MediaBrowser-Token")
}
