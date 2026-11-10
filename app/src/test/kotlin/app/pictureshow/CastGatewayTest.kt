/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.CastViewedItem
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

    private val openInfo = """{"PlaySessionId":"cast-play","MediaSources":[{"Id":"tuner","RequiresOpening":true,"OpenToken":"open"}]}"""
    private val liveSource = """{"MediaSource":{"Id":"source","LiveStreamId":"live","RequiresClosing":true,"IsInfiniteStream":true,""" +
        """"SupportsDirectStream":true,"Container":"ts"}}"""

    private suspend fun prepareLive(gateway: ClientCastGateway) {
        val request = CastPlayRequest(listOf(CastPlayItem("channel", "Channel")))
        val outcome = assertIs<ClientResult.Success<PlayOutcome>>(gateway.prepare(account, "channel", request, 0)).value
        assertIs<PlayOutcome.Play>(outcome)
    }

    private fun liveGateway(closeStatus: () -> HttpStatusCode = { HttpStatusCode.NoContent }) = gateway { request ->
        when {
            request.url.encodedPath.endsWith("PlaybackInfo") -> HttpStatusCode.OK to openInfo
            request.url.encodedPath.endsWith("LiveStreams/Open") -> HttpStatusCode.OK to liveSource
            request.url.encodedPath.endsWith("LiveStreams/Close") -> closeStatus() to ""
            else -> HttpStatusCode.NoContent to ""
        }
    }

    @Test fun ownedLiveSourceStopsWithLiveIdThenClosesBeforeLogout() = runBlocking {
        val gateway = liveGateway()
        prepareLive(gateway)
        val report = CastReport("channel", "source", "cast-play", PlayMethod.DirectStream, 30, false)
        gateway.reportStart(account, report)
        gateway.reportStopped(account, report)
        assertTrue(gateway.revokeSession(account))
        val paths = requests.map { it.url.encodedPath.substringAfter("/jf/") }
        assertTrue(paths.indexOf("Sessions/Playing/Stopped") < paths.indexOf("LiveStreams/Close"), paths.toString())
        assertTrue(paths.indexOf("LiveStreams/Close") < paths.indexOf("Sessions/Logout"), paths.toString())
        val body = requests.first { it.url.encodedPath.endsWith("Stopped") }.body.toByteArray().decodeToString()
        assertTrue("\"LiveStreamId\":\"live\"" in body, body)
        assertEquals(1, paths.count { it == "LiveStreams/Close" })
    }

    @Test fun changingChannelStopsAndClosesPreviousAcquisitionEvenWhenProviderReusesLiveId() = runBlocking {
        val gateway = liveGateway()
        prepareLive(gateway)
        gateway.reportStart(account, CastReport("channel", "source", "cast-play", PlayMethod.DirectStream, 10, false))
        val next = gateway.prepare(account, "other-channel", CastPlayRequest(emptyList()), 0)
        assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(next).value)
        gateway.reportProgress(account, CastReport("other-channel", "source", "cast-play", PlayMethod.DirectStream, 0, false))
        val paths = requests.map { it.url.encodedPath }
        val stopped = paths.indexOfFirst { it.endsWith("Stopped") }
        val close = paths.indexOfFirst { it.endsWith("LiveStreams/Close") }
        val progress = paths.indexOfFirst { it.endsWith("Progress") }
        assertTrue(stopped >= 0 && close > stopped && progress > close, paths.toString())
        assertTrue(gateway.revokeSession(account))
        assertEquals(2, requests.count { it.url.encodedPath.endsWith("LiveStreams/Close") })
    }

    @Test fun failedLoadClosesPreparedSourceWithoutAPlaybackReport() = runBlocking {
        val gateway = liveGateway()
        prepareLive(gateway)
        assertTrue(gateway.revokeSession(account))
        val paths = requests.map { it.url.encodedPath }
        assertTrue(paths.any { it.endsWith("LiveStreams/Close") })
        assertTrue(paths.none { it.endsWith("Sessions/Playing/Stopped") })
        assertIs<ClientResult.Failure>(gateway.prepare(account, "later", CastPlayRequest(emptyList()), 0))
        assertEquals(1, paths.count { it.endsWith("LiveStreams/Open") })
    }

    @Test fun closeFailureKeepsCredentialPendingAndRetriesBeforeLogout() = runBlocking {
        var status = HttpStatusCode.InternalServerError
        val gateway = liveGateway { status }
        prepareLive(gateway)
        assertFalse(gateway.revokeSession(account))
        assertTrue(requests.none { it.url.encodedPath.endsWith("Logout") })
        status = HttpStatusCode.NotFound
        assertTrue(gateway.revokeSession(account))
        assertEquals(2, requests.count { it.url.encodedPath.endsWith("LiveStreams/Close") })
        assertEquals(1, requests.count { it.url.encodedPath.endsWith("Logout") })
    }

    @Test fun restoredPrivateOwnershipIsClosedWithoutNegotiatingAgain() = runBlocking {
        val gateway = liveGateway()
        gateway.restoreLiveSources(account, listOf(CastViewedItem("entry", "channel", ownedLiveStreamId = "saved-live")))
        assertTrue(gateway.revokeSession(account))
        assertEquals("saved-live", requests.first().url.parameters["liveStreamId"])
        assertTrue(requests.last().url.encodedPath.endsWith("Logout"))
        assertTrue(requests.none { it.url.encodedPath.endsWith("PlaybackInfo") })
    }

    @Test fun sharedLiveSourceIsReportedButNeverExplicitlyClosed() = runBlocking {
        val gateway = gateway { request ->
            if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                HttpStatusCode.OK to """{"MediaSources":[{"Id":"source","LiveStreamId":"shared","RequiresClosing":true,
                    "SupportsDirectStream":true,"Container":"ts"}]}"""
            } else {
                HttpStatusCode.NoContent to ""
            }
        }
        prepareLive(gateway)
        gateway.reportStopped(account, CastReport("channel", "source", null, PlayMethod.DirectStream, 0, false))
        assertTrue(gateway.revokeSession(account))
        assertTrue(requests.none { it.url.encodedPath.endsWith("LiveStreams/Close") })
        val body = requests.first { it.url.encodedPath.endsWith("Stopped") }.body.toByteArray().decodeToString()
        assertTrue("shared" in body, body)
    }

    @Test fun revokeWaitsForInFlightOpenBeforeClosingAndInvalidatingCredential() = runBlocking {
        val opened = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val http = HttpClient(
            MockEngine { request ->
                requests += request
                val body = when {
                    request.url.encodedPath.endsWith("PlaybackInfo") -> openInfo
                    request.url.encodedPath.endsWith("LiveStreams/Open") -> {
                        opened.complete(Unit)
                        release.await()
                        liveSource
                    }
                    else -> ""
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val gateway = ClientCastGateway(http, identity(), "Phone")
            val prepare = async { prepareLive(gateway) }
            opened.await()
            val revoke = async { gateway.revokeSession(account) }
            release.complete(Unit)
            prepare.await()
            assertTrue(revoke.await())
            val paths = requests.map { it.url.encodedPath }
            val close = paths.indexOfFirst { it.endsWith("LiveStreams/Close") }
            val logout = paths.indexOfFirst { it.endsWith("Logout") }
            assertTrue(close >= 0 && logout > close, paths.toString())
        } finally {
            http.close()
        }
    }
}
