/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.seerr

import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.serialization.Serializable

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
    fun canRequest(type: String): Boolean = permissions and (2L or 32L or if (type == "movie") 262144L else 524288L) != 0L
}

@Serializable
data class SeerrSeasonStatus(val seasonNumber: Int, val status: Int = 1)

@Serializable
data class SeerrMedia(
    val tmdbId: Int = 0,
    val mediaType: String = "movie",
    val status: Int = 1,
    val seasons: List<SeerrSeasonStatus> = emptyList()
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
    val overview: String = "",
    val mediaInfo: SeerrMedia? = null,
    val seasons: List<SeerrSeason> = emptyList()
) {
    val label: String get() = title ?: name ?: id.toString()
    val year: String get() = (releaseDate ?: firstAirDate).orEmpty().take(4)
    val poster: String? get() = posterPath?.takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/w342$it" }
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
data class SeerrRequest(val id: Int, val status: Int, val type: String, val media: SeerrMedia)

@Serializable
data class SeerrRequests(val results: List<SeerrRequest> = emptyList(), val pageInfo: SeerrPageInfo = SeerrPageInfo())

@Serializable
enum class SeerrMode { JELLYFIN, API_KEY }

@Serializable
data class SeerrConfig(val url: String, val mode: SeerrMode, val user: SeerrUser, val adminFallback: Boolean = false)

/** Never include credentials in data-class generated toString or diagnostics. */
@Serializable
class SeerrSecret(val apiKey: String = "", val cookie: String = "", val username: String = "", val password: String = "") {
    override fun toString(): String = "SeerrSecret([redacted])"
}

fun normalizeSeerrUrl(value: String): String {
    val raw = value.trim().let { if ("://" in it) it else "https://$it" }
    val url = Url(raw)
    require(url.protocol == URLProtocol.HTTP || url.protocol == URLProtocol.HTTPS)
    require(url.host.isNotBlank() && url.user.isEmpty() && url.password.isEmpty() && url.encodedQuery.isEmpty() && url.fragment.isEmpty())
    return url.toString().trimEnd('/')
}

/** RFC 3986 unreserved bytes only, including the historically validator-sensitive !'()* characters. */
fun encodeSeerrQuery(value: String): String = buildString {
    for (byte in value.encodeToByteArray()) {
        val n = byte.toInt() and 255
        val c = n.toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~") append(c) else {
            append('%')
            append("0123456789ABCDEF"[n ushr 4])
            append("0123456789ABCDEF"[n and 15])
        }
    }
}

enum class SeerrStatus { UNKNOWN, PENDING, PROCESSING, PARTIAL, AVAILABLE, BLOCKED, DELETED, APPROVED, DECLINED, FAILED, COMPLETED }
fun seerrMediaStatus(value: Int): SeerrStatus = when (value) {
    2 -> SeerrStatus.PENDING
    3 -> SeerrStatus.PROCESSING
    4 -> SeerrStatus.PARTIAL
    5 -> SeerrStatus.AVAILABLE
    6 -> SeerrStatus.BLOCKED
    7 -> SeerrStatus.DELETED
    else -> SeerrStatus.UNKNOWN
}
fun seerrRequestStatus(value: Int): SeerrStatus = when (value) {
    1 -> SeerrStatus.PENDING
    2 -> SeerrStatus.APPROVED
    3 -> SeerrStatus.DECLINED
    4 -> SeerrStatus.FAILED
    5 -> SeerrStatus.COMPLETED
    else -> SeerrStatus.UNKNOWN
}
fun seerrSearchOffers(titles: List<SeerrTitle>): List<SeerrTitle> = titles.filter {
    it.mediaType in setOf("movie", "tv") && it.mediaInfo?.status != 5
}.distinctBy { it.mediaType to it.id }

/** Specials excluded; only real, unrequested, unavailable seasons can be selected. Deleted seasons can be re-requested. */
fun seerrSelectableSeasons(title: SeerrTitle): List<Int> = title.seasons.filter { season ->
    season.seasonNumber > 0 && season.episodeCount > 0 &&
        title.mediaInfo?.seasons.orEmpty().none { it.seasonNumber == season.seasonNumber && it.status in 2..6 }
}.map { it.seasonNumber }.distinct().sorted()

enum class SeerrError { UNREACHABLE, PASSWORD, EXPIRED, PERMISSION, QUOTA, ALREADY_REQUESTED, SERVER, ADDRESS, STORAGE }
class SeerrFailure(val reason: SeerrError) : Exception(reason.name)
fun seerrError(status: Int, body: String, login: Boolean = false): SeerrError {
    val text = body.lowercase()
    return when {
        "quota" in text || "limit reached" in text -> SeerrError.QUOTA
        "already" in text && ("request" in text || "available" in text) -> SeerrError.ALREADY_REQUESTED
        login && (status in listOf(401, 403) || "password" in text || "credentials" in text) -> SeerrError.PASSWORD
        status == 401 -> SeerrError.EXPIRED
        status == 403 -> SeerrError.PERMISSION
        else -> SeerrError.SERVER
    }
}
