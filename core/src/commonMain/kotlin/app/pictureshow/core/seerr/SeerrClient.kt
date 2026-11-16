/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.seerr

import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * One viewer's connection to Seerr. [http] must be a dedicated client with redirects off and no default headers: the
 * Jellyfin token never goes to Seerr, and Seerr's cookie or key never goes anywhere else.
 */
class SeerrClient(
    private val http: HttpClient,
    val config: SeerrConfig,
    private var secret: SeerrSecret,
    private val saveSecret: suspend (SeerrSecret) -> Unit = {}
) {
    private val renewal = Mutex()

    private suspend fun raw(path: String, method: HttpMethod, body: String?, lookup: Boolean = false): HttpResponse {
        val headers = seerrAuthHeaders(config.mode, secret, config.user.id, lookup)
        return try {
            http.request("${config.url}/api/v1/$path") {
                this.method = method
                headers.forEach { (name, value) -> this.headers.append(name, value) }
                if (body != null) {
                    this.headers.append("Content-Type", "application/json")
                    setBody(body)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            throw SeerrFailure(SeerrError.UNREACHABLE)
        }
    }

    private suspend fun readBody(response: HttpResponse): String = try {
        response.bodyAsText()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw SeerrFailure(SeerrError.UNREACHABLE)
    }

    /**
     * Jellyfin mode renews a lapsed 30-day session once with the saved Jellyfin password. Seerr answers a missing
     * session 401 and a stale one 403, the same 403 as a missing permission, so a 401/403 is checked against
     * /auth/me (which needs no permission) before renewing.
     */
    private suspend fun call(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null, lookup: Boolean = false): String {
        val used = secret
        var response = raw(path, method, body, lookup)
        if (config.mode == SeerrMode.JELLYFIN && response.status.value in listOf(401, 403)) {
            val expired = path == "auth/me" || raw("auth/me", HttpMethod.Get, null).status.value in listOf(401, 403)
            if (expired) {
                renewal.withLock {
                    if (secret === used) {
                        if (used.password.isEmpty()) throw SeerrFailure(SeerrError.EXPIRED)
                        val renewed = try {
                            login(http, config.url, used.username, used.password).second
                        } catch (failure: SeerrFailure) {
                            // A changed Jellyfin password means signing in to Seerr again, not a wrong password now.
                            throw SeerrFailure(if (failure.reason == SeerrError.PASSWORD) SeerrError.EXPIRED else failure.reason)
                        }
                        secret = renewed
                        saveSecret(renewed)
                    }
                }
                response = raw(path, method, body, lookup)
            }
        }
        val text = readBody(response)
        if (response.status.value !in 200..299 || response.status.value == 202) {
            throw SeerrFailure(seerrError(response.status.value, text))
        }
        return text
    }

    private inline fun <reified T> decode(text: String): T = try {
        seerrJson.decodeFromString<T>(text)
    } catch (_: SerializationException) {
        throw SeerrFailure(SeerrError.SERVER)
    } catch (_: IllegalArgumentException) {
        throw SeerrFailure(SeerrError.SERVER)
    }

    suspend fun me(): SeerrUser = decode(call("auth/me"))
    suspend fun trending(page: Int = 1): SeerrPage = decode(call("discover/trending?page=$page"))
    suspend fun search(query: String, page: Int = 1): SeerrPage = decode(call("search?query=${encodeSeerrQuery(query.trim())}&page=$page"))
    suspend fun detail(type: String, id: Int): SeerrTitle {
        require(type in setOf("movie", "tv") && id > 0)
        return decode<SeerrTitle>(call("$type/$id")).copy(mediaType = type)
    }

    /** The viewer's own requests, newest first. */
    suspend fun requests(skip: Int = 0, take: Int = 20): SeerrRequests =
        decode(call("request?take=$take&skip=$skip&filter=all&sort=added&requestedBy=${config.user.id}"))

    /** A movie, or the chosen series seasons; each season must still be requestable by [seerrSelectableSeasons]. */
    suspend fun request(title: SeerrTitle, seasons: List<Int> = emptyList()): SeerrRequest {
        require(title.mediaType in setOf("movie", "tv"))
        require(title.mediaType != "tv" || seasons.isNotEmpty() && seasons.all { it in seerrSelectableSeasons(title) })
        val payload = buildJsonObject {
            put("mediaType", title.mediaType)
            put("mediaId", title.id)
            if (title.mediaType == "tv") putJsonArray("seasons") { seasons.distinct().sorted().forEach { add(JsonPrimitive(it)) } }
        }
        return decode(call("request", HttpMethod.Post, payload.toString()))
    }

    companion object {
        private val seerrJson = Json { ignoreUnknownKeys = true }

        /** Signs in with the viewer's Jellyfin name and password; Seerr must already have Jellyfin set up. */
        suspend fun login(http: HttpClient, url: String, username: String, password: String): Pair<SeerrUser, SeerrSecret> {
            val config = SeerrConfig(normalizeSeerrUrl(url), SeerrMode.JELLYFIN, SeerrUser(0))
            val client = SeerrClient(http, config, SeerrSecret())
            val body = buildJsonObject {
                put("username", username)
                put("password", password)
            }.toString()
            val response = client.raw("auth/jellyfin", HttpMethod.Post, body)
            val text = client.readBody(response)
            if (response.status.value !in 200..299) throw SeerrFailure(seerrError(response.status.value, text, login = true))
            val cookie = seerrSessionCookie(response.headers.getAll("Set-Cookie").orEmpty()) ?: throw SeerrFailure(SeerrError.SERVER)
            return client.decode<SeerrUser>(text) to SeerrSecret(cookie = cookie, username = username, password = password)
        }

        /**
         * An API key acts as Seerr's admin unless X-API-User names someone, so the key is only usable once the
         * viewer's own Seerr user is found by Jellyfin user id across every page of users. No match fails closed
         * with [SeerrError.NOT_LINKED]: requests are never made as the admin or anyone else.
         */
        suspend fun apiKey(http: HttpClient, url: String, key: String, jellyfinId: String): SeerrConfig {
            val lookup = SeerrConfig(normalizeSeerrUrl(url), SeerrMode.API_KEY, SeerrUser(0))
            val client = SeerrClient(http, lookup, SeerrSecret(apiKey = key))
            var skip = 0
            while (true) {
                val response = client.raw("user?take=100&skip=$skip", HttpMethod.Get, null, lookup = true)
                val text = client.readBody(response)
                if (response.status.value !in 200..299) throw SeerrFailure(seerrError(response.status.value, text, apiKey = true))
                val users = client.decode<SeerrUsers>(text)
                seerrLinkedUser(users.results, jellyfinId)?.let { return lookup.copy(user = it) }
                skip += users.results.size
                if (users.results.isEmpty() || skip >= users.pageInfo.results) break
            }
            throw SeerrFailure(SeerrError.NOT_LINKED)
        }
    }
}
