/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.seerr

import app.pictureshow.core.normalizeSearch
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable

/*
 * Seerr (Jellyseerr/Overseerr, one project since v3) request integration: models and the shared rules every platform
 * translates. Shapes are the ones Seerr v3.5.2 answers (checked against a running server, spec/features/seerr.md).
 */

/** Seerr permission bits (seerr-team/seerr server/lib/permissions.ts). */
object SeerrPermission {
    const val ADMIN = 2L
    const val REQUEST = 32L
    const val REQUEST_MOVIE = 262144L
    const val REQUEST_TV = 524288L
}

@Serializable
data class SeerrUser(
    val id: Int,
    val displayName: String? = null,
    val username: String? = null,
    val jellyfinUsername: String? = null,
    val jellyfinUserId: String? = null,
    val permissions: Long = 0
) {
    val name: String get() = displayName?.takeIf { it.isNotBlank() } ?: jellyfinUsername ?: username ?: id.toString()
    fun canRequest(type: String): Boolean = seerrCanRequest(permissions, type)
}

/** Movie requests need ADMIN, REQUEST or REQUEST_MOVIE; series need ADMIN, REQUEST or REQUEST_TV (4K is not offered). */
fun seerrCanRequest(permissions: Long, type: String): Boolean {
    val specific = when (type) {
        "movie" -> SeerrPermission.REQUEST_MOVIE
        "tv" -> SeerrPermission.REQUEST_TV
        else -> return false
    }
    return permissions and (SeerrPermission.ADMIN or SeerrPermission.REQUEST or specific) != 0L
}

@Serializable
data class SeerrSeasonStatus(val seasonNumber: Int, val status: Int = 1)

/** A request as embedded in a title's mediaInfo: only what decides which seasons can still be requested. */
@Serializable
data class SeerrMediaRequest(
    val id: Int = 0,
    val status: Int = 1,
    val is4k: Boolean = false,
    val seasons: List<SeerrSeasonStatus> = emptyList()
)

@Serializable
data class SeerrMedia(
    val tmdbId: Int = 0,
    val mediaType: String = "movie",
    val status: Int = 1,
    val seasons: List<SeerrSeasonStatus> = emptyList(),
    val requests: List<SeerrMediaRequest> = emptyList()
)

@Serializable
data class SeerrTitle(
    val id: Int,
    val mediaType: String = "",
    val title: String? = null,
    val name: String? = null,
    val posterPath: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val mediaInfo: SeerrMedia? = null,
    val seasons: List<SeerrSeason> = emptyList()
) {
    val label: String get() = title ?: name ?: id.toString()
    val year: String get() = (releaseDate ?: firstAirDate).orEmpty().take(4)
    val poster: String? get() = seerrPosterUrl(posterPath)
    val status: SeerrStatus get() = seerrMediaStatus(mediaInfo?.status ?: 1)
}

/**
 * Posters come straight from TMDB's public image CDN with no credentials: the Seerr session or key and the Jellyfin
 * token never go there. Only a TMDB file path ("/abc.jpg") is accepted, so a hostile response can't point elsewhere.
 */
fun seerrPosterUrl(path: String?, width: Int = 342): String? {
    val file = path?.takeIf {
        it.length > 1 &&
            it.startsWith("/") &&
            ".." !in it &&
            it.drop(1).all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "._-" }
    } ?: return null
    return "https://image.tmdb.org/t/p/w$width$file"
}

@Serializable
data class SeerrSeason(val seasonNumber: Int, val name: String = "", val episodeCount: Int = 0)

@Serializable
data class SeerrPage(val results: List<SeerrTitle> = emptyList(), val totalPages: Int = 1, val page: Int = 1)

@Serializable
data class SeerrUsers(val results: List<SeerrUser> = emptyList(), val pageInfo: SeerrPageInfo = SeerrPageInfo())

@Serializable
data class SeerrPageInfo(val results: Int = 0, val pages: Int = 1)

@Serializable
data class SeerrRequest(
    val id: Int,
    val status: Int,
    val type: String,
    val is4k: Boolean = false,
    val media: SeerrMedia,
    val seasons: List<SeerrSeasonStatus> = emptyList()
)

@Serializable
data class SeerrRequests(val results: List<SeerrRequest> = emptyList(), val pageInfo: SeerrPageInfo = SeerrPageInfo())

@Serializable
enum class SeerrMode { JELLYFIN, API_KEY }

/** The non-secret half of a connection. In API-key mode [user] is always the viewer's own linked Seerr user. */
@Serializable
data class SeerrConfig(val url: String, val mode: SeerrMode, val user: SeerrUser)

/** Never include credentials in data-class generated toString or diagnostics. */
@Serializable
class SeerrSecret(val apiKey: String = "", val cookie: String = "", val username: String = "", val password: String = "") {
    override fun toString(): String = "SeerrSecret([redacted])"
}

/**
 * The address a viewer typed, as the base every API path is appended to. No scheme means https. Credentials,
 * queries and fragments are refused (IllegalArgumentException); a trailing slash and a trailing "/api/v1" (pasted from
 * Seerr's docs) are removed.
 */
fun normalizeSeerrUrl(value: String): String {
    val trimmed = value.trim()
    require(trimmed.isNotEmpty() && trimmed.none { it.isWhitespace() })
    val raw = if ("://" in trimmed) trimmed else "https://$trimmed"
    // Ktor reports an unparseable address as IllegalStateException; callers handle one exception type.
    val url = try {
        Url(raw)
    } catch (_: IllegalStateException) {
        throw IllegalArgumentException("Not an address")
    }
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS)
    require(raw.substringBefore("://").lowercase() == url.protocol.name)
    require(url.host.isNotBlank() && url.user == null && url.password == null)
    require(url.encodedQuery.isEmpty() && url.fragment.isEmpty() && '?' !in raw && '#' !in raw)
    val path = url.encodedPath.trimEnd('/').removeSuffix("/api/v1").trimEnd('/')
    val port = if (url.specifiedPort > 0 && url.specifiedPort != url.protocol.defaultPort) ":${url.specifiedPort}" else ""
    val host = url.host.lowercase().let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
    return "${url.protocol.name}://$host$port$path"
}

/** RFC 3986 unreserved bytes only. Seerr's OpenAPI validator rejects raw reserved characters such as !'()* (400). */
fun encodeSeerrQuery(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val n = byte.toInt() and 255
        val c = n.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") {
            append(c)
        } else {
            append('%')
            append("0123456789ABCDEF"[n ushr 4])
            append("0123456789ABCDEF"[n and 15])
        }
    }
}

enum class SeerrStatus { UNKNOWN, PENDING, PROCESSING, PARTIAL, AVAILABLE, BLOCKED, DELETED, APPROVED, DECLINED, FAILED, COMPLETED }

/** MediaStatus: UNKNOWN=1, PENDING, PROCESSING, PARTIALLY_AVAILABLE, AVAILABLE, BLOCKLISTED, DELETED=7. */
fun seerrMediaStatus(value: Int): SeerrStatus = when (value) {
    2 -> SeerrStatus.PENDING
    3 -> SeerrStatus.PROCESSING
    4 -> SeerrStatus.PARTIAL
    5 -> SeerrStatus.AVAILABLE
    6 -> SeerrStatus.BLOCKED
    7 -> SeerrStatus.DELETED
    else -> SeerrStatus.UNKNOWN
}

/** MediaRequestStatus: PENDING=1, APPROVED, DECLINED, FAILED, COMPLETED=5. */
fun seerrRequestStatus(value: Int): SeerrStatus = when (value) {
    1 -> SeerrStatus.PENDING
    2 -> SeerrStatus.APPROVED
    3 -> SeerrStatus.DECLINED
    4 -> SeerrStatus.FAILED
    5 -> SeerrStatus.COMPLETED
    else -> SeerrStatus.UNKNOWN
}

/**
 * What a request's row says. A request the server finished, or whose title is now in the library, reads Available;
 * one still waiting for an admin reads Pending approval; approved but not yet in the library reads Approved.
 */
fun seerrRequestDisplay(request: SeerrRequest): SeerrStatus {
    val media = seerrMediaStatus(request.media.status)
    return when {
        media == SeerrStatus.AVAILABLE -> SeerrStatus.AVAILABLE
        request.status == 5 -> SeerrStatus.AVAILABLE
        media == SeerrStatus.PARTIAL && request.status == 2 -> SeerrStatus.PARTIAL
        media == SeerrStatus.PROCESSING && request.status == 2 -> SeerrStatus.PROCESSING
        else -> seerrRequestStatus(request.status)
    }
}

/**
 * The "Not in your library" offers for a search: movies and series only (TMDB multi-search also returns people),
 * never one Seerr already reports available or one the library search itself found (same title and year, compared
 * the way library search normalizes text), each once, in Seerr's order.
 */
fun seerrSearchOffers(titles: List<SeerrTitle>, library: List<Pair<String, Int?>> = emptyList()): List<SeerrTitle> {
    val owned = library.map { seerrTitleKey(it.first, it.second?.toString().orEmpty()) }.toSet()
    return titles.filter {
        it.mediaType in setOf("movie", "tv") && it.mediaInfo?.status != 5 && seerrTitleKey(it.label, it.year) !in owned
    }.distinctBy { it.mediaType to it.id }
}

private fun seerrTitleKey(name: String, year: String) = "${normalizeSearch(name).lowercase()}|$year"

/**
 * Seasons the viewer can still request, the same rule the server applies (MediaRequest.request): specials and empty
 * seasons are never offered; a season is taken when a non-4K request that is neither declined nor completed names it,
 * or when the media's own season status is anything but unknown or deleted.
 */
fun seerrSelectableSeasons(title: SeerrTitle): List<Int> {
    val taken = seerrTakenSeasons(title)
    return title.seasons.filter { it.seasonNumber > 0 && it.episodeCount > 0 && it.seasonNumber !in taken.keys }
        .map { it.seasonNumber }.distinct().sorted()
}

/** Each unavailable season and why: AVAILABLE, PARTIAL, PROCESSING, PENDING (requested) or BLOCKED. */
fun seerrTakenSeasons(title: SeerrTitle): Map<Int, SeerrStatus> {
    val media = title.mediaInfo ?: return emptyMap()
    val taken = mutableMapOf<Int, SeerrStatus>()
    media.seasons.filter { it.status != 1 && it.status != 7 }.forEach { taken[it.seasonNumber] = seerrMediaStatus(it.status) }
    media.requests.filter { !it.is4k && it.status != 3 && it.status != 5 }.forEach { request ->
        request.seasons.forEach { season ->
            if (season.seasonNumber !in taken) {
                taken[season.seasonNumber] = if (request.status == 2) SeerrStatus.APPROVED else SeerrStatus.PENDING
            }
        }
    }
    return taken
}

/** A movie can be requested when Seerr knows nothing of it, or it was deleted; anything else is already on its way. */
fun seerrMovieRequestable(title: SeerrTitle): Boolean = (title.mediaInfo?.status ?: 1).let { it == 1 || it == 7 }

enum class SeerrError {
    UNREACHABLE,
    PASSWORD,
    API_KEY,
    EXPIRED,
    PERMISSION,
    QUOTA,
    ALREADY_REQUESTED,
    BLOCKED,
    NOT_LINKED,
    SERVER,
    ADDRESS,
    STORAGE
}

class SeerrFailure(val reason: SeerrError) : Exception(reason.name)

/**
 * A failed answer in the viewer's terms. Seerr v3.5.2: wrong Jellyfin password 401 INVALID_CREDENTIALS; no session
 * 401 (validator) or a stale one 403; no permission 403 "You do not have permission..."; quota 403 "... Quota
 * exceeded."; blocklisted 403 "This media is blocklisted."; duplicate 409; nothing left to request 202 (a success
 * status the client treats as a failure). [apiKey] marks the API-key sign-in, where 401/403 mean the key is wrong.
 */
fun seerrError(status: Int, body: String, login: Boolean = false, apiKey: Boolean = false): SeerrError {
    val text = body.lowercase()
    return when {
        status in 300..399 -> SeerrError.ADDRESS
        status == 404 && (login || apiKey) -> SeerrError.ADDRESS
        "quota" in text -> SeerrError.QUOTA
        "blocklist" in text -> SeerrError.BLOCKED
        status == 409 || status == 202 || "already exists" in text || "no seasons available" in text -> SeerrError.ALREADY_REQUESTED
        login && status in listOf(401, 403) -> SeerrError.PASSWORD
        apiKey && status in listOf(401, 403) -> SeerrError.API_KEY
        status == 401 -> SeerrError.EXPIRED
        status == 403 -> SeerrError.PERMISSION
        else -> SeerrError.SERVER
    }
}

/**
 * The authentication headers for one call. Jellyfin mode sends the session cookie. API-key mode always names the
 * viewer's own linked Seerr user with X-API-User, and refuses to call without one (no id means the key alone would act
 * as Seerr's admin, user 1); only the user lookup during connect ([lookup]) may send the key alone.
 */
fun seerrAuthHeaders(mode: SeerrMode, secret: SeerrSecret, userId: Int, lookup: Boolean = false): List<Pair<String, String>> = when (mode) {
    SeerrMode.JELLYFIN -> if (secret.cookie.isEmpty()) emptyList() else listOf("Cookie" to secret.cookie)
    SeerrMode.API_KEY -> when {
        lookup -> listOf("X-Api-Key" to secret.apiKey)
        userId > 0 -> listOf("X-Api-Key" to secret.apiKey, "X-API-User" to userId.toString())
        else -> throw SeerrFailure(SeerrError.NOT_LINKED)
    }
}

/** The viewer's own Seerr user: the one imported from this Jellyfin user. Never a fallback to anyone else. */
fun seerrLinkedUser(users: List<SeerrUser>, jellyfinUserId: String): SeerrUser? {
    val id = jellyfinUserId.replace("-", "").lowercase()
    if (id.isEmpty()) return null
    return users.firstOrNull { it.jellyfinUserId?.replace("-", "")?.lowercase() == id }
}

/** The session cookie from a sign-in's Set-Cookie headers, as "connect.sid=value", or null. */
fun seerrSessionCookie(setCookie: List<String>): String? =
    setCookie.map { it.substringBefore(';').trim() }.firstOrNull { it.startsWith("connect.sid=") && it.length > "connect.sid=".length }
