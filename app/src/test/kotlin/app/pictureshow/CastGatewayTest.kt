/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** The real gateway against a fake server: the routes it calls and how each answer reads. Real time, since the client has timeouts. */
class CastGatewayTest {
    private val account = Account(Server("server", "Server", "http://server.example/jf", "12.4"), User("me", "Ana"), "viewer-token")
    private val requests = mutableListOf<HttpRequestData>()

    private fun gateway(respond: (HttpRequestData) -> Pair<HttpStatusCode, String>): ClientCastGateway {
        val engine = MockEngine { request ->
            requests += request
            val (status, body) = respond(request)
            respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return ClientCastGateway(HttpClient(engine), identity(), "Phone")
    }

    private fun identity() = DeviceIdentityStore(
        object : DataStore<Preferences> {
            private val values = MutableStateFlow(emptyPreferences())
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                transform(values.value).also { values.value = it }
        }
    )

    private fun answering(status: HttpStatusCode, body: String = "") = gateway { status to body }

    @Test fun aSessionDeviceIsItsOwnStableIdPerReceiverAndViewer() = runBlocking {
        val gateway = answering(HttpStatusCode.OK)
        val first = gateway.sessionDevice(account, "route-a")
        assertTrue(Regex("cast-[0-9a-f]{32}").matches(first), first)
        assertEquals(first, gateway.sessionDevice(account, "route-a"), "a new login under it replaces the last one")
        assertNotEquals(first, gateway.sessionDevice(account, "route-b"))
        assertNotEquals(first, gateway.sessionDevice(account.copy(user = User("other", "Bo")), "route-a"))
        val elsewhere = account.copy(server = account.server.copy(id = "elsewhere"))
        assertNotEquals(first, gateway.sessionDevice(elsewhere, "route-a"))
    }

    @Test fun quickConnectOffIsItsOwnAnswerAndAsksNoSession() = runBlocking {
        val gateway = gateway { HttpStatusCode.OK to "false" }
        assertIs<CastSessionIssue.QuickConnectOff>(gateway.provisionSession(account, "cast-d"))
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("/QuickConnect/Enabled"), requests.single().url.toString())
    }

    @Test fun anUnreadableQuickConnectAnswerIsAFailureAndAsksNoSession() = runBlocking {
        val gateway = answering(HttpStatusCode.InternalServerError)
        assertIs<CastSessionIssue.Failed>(gateway.provisionSession(account, "cast-d"))
        assertEquals(1, requests.size)
    }

    @Test fun aRefusedRenewalIsAFailure() = runBlocking {
        val gateway = gateway { request ->
            if (request.url.encodedPath.endsWith("/QuickConnect/Enabled")) HttpStatusCode.OK to "true" else HttpStatusCode.Forbidden to ""
        }
        assertIs<CastSessionIssue.Failed>(gateway.provisionSession(account, "cast-d"))
        assertTrue(requests.size > 1)
    }

    @Test fun revokingCallsLogoutWithTheTemporaryToken() = runBlocking {
        val session = account.copy(accessToken = "cast-token", deviceId = "cast-d")
        assertTrue(answering(HttpStatusCode.NoContent).revokeSession(session))
        val request = requests.single()
        assertTrue(request.url.encodedPath.endsWith("/Sessions/Logout"), request.url.toString())
        val auth = request.headers[HttpHeaders.Authorization].orEmpty()
        assertTrue("cast-token" in auth)
        assertFalse("viewer-token" in auth)
    }

    @Test fun aSessionAlreadyGoneOrThatTheServerWontLogOutIsSettled() = runBlocking {
        val session = account.copy(accessToken = "cast-token")
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound).forEach {
            assertTrue(answering(it).revokeSession(session), it.toString())
        }
    }

    @Test fun anythingElseStaysPending() = runBlocking {
        val session = account.copy(accessToken = "cast-token")
        listOf(HttpStatusCode.InternalServerError, HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable).forEach {
            assertFalse(answering(it).revokeSession(session), it.toString())
        }
        val offline = ClientCastGateway(HttpClient(MockEngine { throw IOException("offline") }), identity(), "Phone")
        assertFalse(offline.revokeSession(session))
    }
}
