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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Dedicated client, with redirects disabled by its owner. No Jellyfin credentials/headers reach poster hosts. */
class SeerrClient(
    private val http: HttpClient,
    val config: SeerrConfig,
    private var secret: SeerrSecret,
    private val saveSecret: suspend (SeerrSecret) -> Unit = {}
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val renewal = Mutex()

    private suspend fun raw(path: String, method: HttpMethod, body: String?, auth: SeerrSecret = secret): HttpResponse = try {
        http.request("${config.url}/api/v1/$path") {
            this.method = method
            // The caller owns this client: no global cookies or default Jellyfin authorization.
            if (config.mode == SeerrMode.API_KEY) {
                headers.append("X-Api-Key", auth.apiKey)
                headers.append("X-API-User", config.user.id.toString())
            } else if (auth.cookie.isNotEmpty()) {
                headers.append("Cookie", auth.cookie)
            }
            if (body != null) {
                headers.append("Content-Type", "application/json")
                setBody(body)
            }
        }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (_: Exception) {
        throw SeerrFailure(SeerrError.UNREACHABLE)
    }

    private suspend fun call(path: String, method: HttpMethod = HttpMethod.Get, body: String? = null): String {
        val used = secret
        var response = raw(path, method, body)
        if (config.mode == SeerrMode.JELLYFIN && response.status.value in listOf(401, 403)) {
            // Seerr uses the same 403 for missing session and missing permission. /me has no permission requirement.
            val expired = path == "auth/me" || raw("auth/me", HttpMethod.Get, null).status.value in listOf(401, 403)
            if (expired) {
                renewal.withLock {
                    if (secret === used) {
                        val renewed = login(http, config.url, used.username, used.password)
                        saveSecret(renewed.second)
                        secret = renewed.second
                    }
                }
                response = raw(path, method, body)
            }
        }
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) throw SeerrFailure(seerrError(response.status.value, text))
        return text
    }

    suspend fun me(): SeerrUser = json.decodeFromString(call("auth/me"))
    suspend fun trending(page: Int = 1): SeerrPage = json.decodeFromString(call("discover/trending?page=$page"))
    suspend fun search(query: String, page: Int = 1): SeerrPage =
        json.decodeFromString(call("search?query=${encodeSeerrQuery(query.trim())}&page=$page"))
    suspend fun detail(type: String, id: Int): SeerrTitle {
        require(type in setOf("movie", "tv") && id > 0)
        return json.decodeFromString<SeerrTitle>(call("$type/$id")).copy(mediaType = type)
    }
    suspend fun requests(skip: Int = 0): SeerrRequests = json.decodeFromString(
        call("request?take=20&skip=$skip&filter=all&sort=added&requestedBy=${config.user.id}")
    )
    suspend fun request(title: SeerrTitle, seasons: List<Int> = emptyList()): SeerrRequest {
        require(title.mediaType in setOf("movie", "tv"))
        require(title.mediaType != "tv" || seasons.isNotEmpty() && seasons.all { it in seerrSelectableSeasons(title) })
        val payload = buildJsonObject {
            put("mediaType", title.mediaType)
            put("mediaId", title.id)
            if (title.mediaType == "tv") putJsonArray("seasons") { seasons.distinct().sorted().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        return json.decodeFromString(call("request", HttpMethod.Post, payload.toString()))
    }

    companion object {
        suspend fun login(http: HttpClient, url: String, username: String, password: String): Pair<SeerrUser, SeerrSecret> {
            val config = SeerrConfig(normalizeSeerrUrl(url), SeerrMode.JELLYFIN, SeerrUser(0))
            val client = SeerrClient(http, config, SeerrSecret())
            val body = buildJsonObject { put("username", username); put("password", password) }.toString()
            val response = client.raw("auth/jellyfin", HttpMethod.Post, body)
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) throw SeerrFailure(seerrError(response.status.value, text, login = true))
            val cookie = response.headers.getAll("Set-Cookie").orEmpty().map { it.substringBefore(';') }
                .firstOrNull { it.startsWith("connect.sid=") } ?: throw SeerrFailure(SeerrError.EXPIRED)
            return client.json.decodeFromString<SeerrUser>(text) to SeerrSecret(cookie = cookie, username = username, password = password)
        }

        suspend fun apiKey(http: HttpClient, url: String, key: String, jellyfinId: String): SeerrConfig {
            val config = SeerrConfig(normalizeSeerrUrl(url), SeerrMode.API_KEY, SeerrUser(1), adminFallback = true)
            val client = SeerrClient(http, config, SeerrSecret(apiKey = key))
            // Exhaust pagination: a viewer past the first page must not accidentally request as admin.
            var skip = 0
            while (true) {
                val users = client.json.decodeFromString<SeerrUsers>(client.call("user?take=100&skip=$skip"))
                users.results.firstOrNull { it.jellyfinUserId.equals(jellyfinId, ignoreCase = true) }?.let {
                    return config.copy(user = it, adminFallback = false)
                }
                skip += users.results.size
                if (users.results.isEmpty() || skip >= users.pageInfo.results) break
            }
            return config.copy(user = client.me())
        }
    }
}
