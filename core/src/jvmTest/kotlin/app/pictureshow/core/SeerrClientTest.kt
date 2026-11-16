/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.seerr.SeerrClient
import app.pictureshow.core.seerr.SeerrConfig
import app.pictureshow.core.seerr.SeerrError
import app.pictureshow.core.seerr.SeerrFailure
import app.pictureshow.core.seerr.SeerrMode
import app.pictureshow.core.seerr.SeerrSeason
import app.pictureshow.core.seerr.SeerrSecret
import app.pictureshow.core.seerr.SeerrTitle
import app.pictureshow.core.seerr.SeerrUser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Seerr v3.5.2's answers as observed on a running server, replayed against the client (spec/features/seerr.md). */
class SeerrClientTest {
    private val base = "https://seerr.example.test"
    private val pilot = """{"id":2,"displayName":"pilot","jellyfinUserId":"db7d5bcbc3cf4c0d88a2ff7e124a2c4b","permissions":32}"""
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private class Seen(
        val method: String,
        val path: String,
        val cookie: String?,
        val key: String?,
        val user: String?,
        val other: Set<String>
    )

    private fun client(seen: MutableList<Seen>, answer: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    seen += Seen(
                        request.method.value,
                        request.url.encodedPathAndQuery.removePrefix("/api/v1/"),
                        request.headers["Cookie"],
                        request.headers["X-Api-Key"],
                        request.headers["X-API-User"],
                        request.headers.names().map { it.lowercase() }.toSet()
                    )
                    answer(request)
                }
            }
        }

    private fun path(request: HttpRequestData) = request.url.encodedPathAndQuery.removePrefix("/api/v1/")

    @Test fun anEndedSessionIsRenewedOnceWithTheSavedPasswordAndSaved() = runBlocking {
        val seen = mutableListOf<Seen>()
        val http = client(seen) { request ->
            val cookie = request.headers["Cookie"]
            when {
                path(request) == "auth/jellyfin" ->
                    respond(pilot, HttpStatusCode.OK, headersOf(HttpHeaders.SetCookie, "connect.sid=s%3Anew; Path=/; HttpOnly"))
                cookie == "connect.sid=s%3Aold" -> respond("""{"message":"Unauthorized"}""", HttpStatusCode.Forbidden)
                path(
                    request
                ).startsWith("discover/trending") -> respond("""{"page":1,"totalPages":1,"results":[]}""", HttpStatusCode.OK, json)
                else -> respond(pilot, HttpStatusCode.OK, json)
            }
        }
        var saved: SeerrSecret? = null
        val secret = SeerrSecret(cookie = "connect.sid=s%3Aold", username = "pilot", password = "pw")
        val seerr = SeerrClient(http, SeerrConfig(base, SeerrMode.JELLYFIN, SeerrUser(2)), secret) { saved = it }
        assertTrue(seerr.trending().results.isEmpty())
        assertEquals("connect.sid=s%3Anew", saved?.cookie)
        assertEquals(listOf("discover/trending?page=1", "auth/me", "auth/jellyfin", "discover/trending?page=1"), seen.map { it.path })
        assertEquals("connect.sid=s%3Anew", seen.last().cookie)
        assertNull(seen[2].cookie)
        // Seerr never sees the Jellyfin token: no Jellyfin authorization header on any call.
        assertTrue(seen.none { "authorization" in it.other || "x-emby-token" in it.other || "x-mediabrowser-token" in it.other })
    }

    @Test fun aMissingPermissionIsNotMistakenForAnEndedSession() = runBlocking {
        val seen = mutableListOf<Seen>()
        val http = client(seen) { request ->
            if (path(request) == "request") {
                respond("""{"message":"You do not have permission to make movie requests."}""", HttpStatusCode.Forbidden)
            } else {
                respond(pilot, HttpStatusCode.OK, json)
            }
        }
        val seerr =
            SeerrClient(
                http,
                SeerrConfig(base, SeerrMode.JELLYFIN, SeerrUser(2)),
                SeerrSecret(cookie = "connect.sid=x", username = "p", password = "pw")
            )
        val failure = assertFailsWith<SeerrFailure> { seerr.request(SeerrTitle(603, "movie")) }
        assertEquals(SeerrError.PERMISSION, failure.reason)
        assertTrue(seen.none { it.path == "auth/jellyfin" })
    }

    @Test fun aChangedJellyfinPasswordEndsTheConnectionRatherThanBlamingTheViewer() = runBlocking {
        val http = client(mutableListOf()) { request ->
            if (path(request) == "auth/jellyfin") {
                respond("""{"message":"INVALID_CREDENTIALS"}""", HttpStatusCode.Unauthorized)
            } else {
                respond("""{"message":"cookie 'connect.sid' required"}""", HttpStatusCode.Unauthorized)
            }
        }
        val seerr =
            SeerrClient(
                http,
                SeerrConfig(base, SeerrMode.JELLYFIN, SeerrUser(2)),
                SeerrSecret(cookie = "connect.sid=x", username = "p", password = "old")
            )
        assertEquals(SeerrError.EXPIRED, assertFailsWith<SeerrFailure> { seerr.requests() }.reason)
    }

    @Test fun nothingLeftToRequestIsAFailureNotARequest() = runBlocking {
        val http = client(mutableListOf()) { respond("""{"message":"No seasons available to request"}""", HttpStatusCode.Accepted, json) }
        val seerr = SeerrClient(http, SeerrConfig(base, SeerrMode.JELLYFIN, SeerrUser(2)), SeerrSecret(cookie = "connect.sid=x"))
        val series = SeerrTitle(1399, "tv", seasons = listOf(SeerrSeason(1, episodeCount = 10)))
        assertEquals(SeerrError.ALREADY_REQUESTED, assertFailsWith<SeerrFailure> { seerr.request(series, listOf(1)) }.reason)
    }

    @Test fun anApiKeyIsOnlyUsedAsTheViewersOwnSeerrUser() = runBlocking {
        val seen = mutableListOf<Seen>()
        val admin = """{"id":1,"displayName":"admin","jellyfinUserId":"ab3c4f85f313450683238943da5e73b9","permissions":2}"""
        val users = """{"pageInfo":{"pages":1,"results":2},"results":[$admin,$pilot]}"""
        val http = client(seen) { request ->
            when {
                path(request).startsWith("user?") -> respond(users, HttpStatusCode.OK, json)
                else -> respond(pilot, HttpStatusCode.OK, json)
            }
        }
        val config = SeerrClient.apiKey(http, base, "key", "DB7D5BCB-C3CF-4C0D-88A2-FF7E124A2C4B")
        assertEquals(2, config.user.id)
        // The lookup is the only call without X-API-User.
        assertEquals(listOf<String?>(null), seen.map { it.user })
        SeerrClient(http, config, SeerrSecret(apiKey = "key")).me()
        assertEquals("2", seen.last().user)
        assertEquals("key", seen.last().key)
    }

    @Test fun anUnlinkedViewerFailsClosedInsteadOfActingAsTheAdmin() = runBlocking {
        val seen = mutableListOf<Seen>()
        fun user(id: Int) = "{\"id\":$id,\"jellyfinUserId\":\"" + "%032x".format(id) + "\"}"
        val page = { skip: Int ->
            val ids = if (skip == 0) (1..100) else (101..120)
            "{\"pageInfo\":{\"pages\":2,\"results\":120},\"results\":[" + ids.joinToString(",") { user(it) } + "]}"
        }
        val http = client(seen) { request ->
            val skip = request.url.parameters["skip"]?.toInt() ?: 0
            respond(page(skip), HttpStatusCode.OK, json)
        }
        val failure = assertFailsWith<SeerrFailure> { SeerrClient.apiKey(http, base, "key", "ffffffffffffffffffffffffffffffff") }
        assertEquals(SeerrError.NOT_LINKED, failure.reason)
        assertEquals(listOf("user?take=100&skip=0", "user?take=100&skip=100"), seen.map { it.path })
        // A config without a linked user can't call Seerr at all, so it can never request as user 1.
        val unlinked = SeerrClient(http, SeerrConfig(base, SeerrMode.API_KEY, SeerrUser(0)), SeerrSecret(apiKey = "key"))
        assertEquals(SeerrError.NOT_LINKED, assertFailsWith<SeerrFailure> { unlinked.request(SeerrTitle(603, "movie")) }.reason)
        assertEquals(2, seen.size)
    }

    @Test fun aWrongApiKeySaysSo() = runBlocking {
        val http =
            client(mutableListOf()) {
                respond("""{"message":"You do not have permission to access this endpoint."}""", HttpStatusCode.Forbidden)
            }
        assertEquals(SeerrError.API_KEY, assertFailsWith<SeerrFailure> { SeerrClient.apiKey(http, base, "wrong", "abc") }.reason)
    }

    @Test fun searchWordsAreFullyEncoded() = runBlocking {
        val seen = mutableListOf<Seen>()
        val http = client(seen) { respond("""{"page":1,"totalPages":1,"results":[]}""", HttpStatusCode.OK, json) }
        SeerrClient(http, SeerrConfig(base, SeerrMode.JELLYFIN, SeerrUser(2)), SeerrSecret(cookie = "c=1")).search(" Ocean's (2001) ")
        assertEquals("search?query=Ocean%27s%20%282001%29&page=1", seen.single().path)
    }
}
