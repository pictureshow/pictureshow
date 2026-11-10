/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.sync.SyncClient
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.syncCreate
import app.pictureshow.core.sync.syncEnqueue
import app.pictureshow.core.sync.syncIgnoreWait
import app.pictureshow.core.sync.syncJoin
import app.pictureshow.core.sync.syncMove
import app.pictureshow.core.sync.syncNewQueue
import app.pictureshow.core.sync.syncPing
import app.pictureshow.core.sync.syncReadiness
import app.pictureshow.core.sync.syncRemove
import app.pictureshow.core.sync.syncRepeat
import app.pictureshow.core.sync.syncSelect
import app.pictureshow.core.sync.syncShuffle
import app.pictureshow.core.sync.syncTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/** The wire boundary is exercised through HTTP, including refused and uncertain mutations. */
class SyncClientTest {
    private val account = Account(
        Server("server", "Test server", "https://media.example.test/proxy/jellyfin/", "12.4"),
        User("viewer", "Viewer"),
        "viewer-token",
        deviceId = "account-device"
    )
    private val requests = mutableListOf<HttpRequestData>()
    private var body = "[]"
    private var status = HttpStatusCode.OK
    private var responseDelay = 0L
    private var failure: Exception? = null

    private fun TestScope.client() = SyncClient(
        HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        requests += request
                        failure?.let { throw it }
                        delay(responseDelay)
                        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            )
        ),
        ClientIdentity("TV", "install-device")
    )

    @Test fun everyMutationUsesViewerAuthorizationAndTheServerBasePrefix() = runTest {
        val sync = client()
        status = HttpStatusCode.NoContent
        assertEquals(204, sync.send(account, syncJoin("group")))
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/proxy/jellyfin/SyncPlay/Join", request.url.encodedPath)
        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().contains("Token=\"viewer-token\""))
        assertTrue(request.headers[HttpHeaders.Authorization].orEmpty().contains("DeviceId=\"account-device\""))
        assertFalse(request.url.toString().contains("viewer-token"))
        assertTrue(request.url.parameters.isEmpty())
        assertEquals(Json.parseToJsonElement("""{"GroupId":"group"}"""), wireBody(request))
    }

    @Test fun frozenMutationBodiesMatchThePublishedServerDTOs() = runTest {
        val expected = listOf(
            syncCreate("Evening") to ("New" to """{"GroupName":"Evening"}"""),
            syncJoin("g") to ("Join" to """{"GroupId":"g"}"""),
            syncNewQueue(listOf("same", "same"), 1, 9007199254740993L) to (
                "SetNewQueue" to
                    """{"PlayingQueue":["same","same"],"PlayingItemPosition":1,"StartPositionTicks":9007199254740993}"""
                ),
            syncEnqueue(listOf("a", "b"), true) to ("Queue" to """{"ItemIds":["a","b"],"Mode":"QueueNext"}"""),
            syncEnqueue(listOf("a"), false) to ("Queue" to """{"ItemIds":["a"],"Mode":"Queue"}"""),
            syncMove("entry-b", 0) to ("MovePlaylistItem" to """{"PlaylistItemId":"entry-b","NewIndex":0}"""),
            syncRemove(listOf("entry-a")) to (
                "RemoveFromPlaylist" to """{"PlaylistItemIds":["entry-a"],"ClearPlaylist":false,"ClearPlayingItem":false}"""
                ),
            syncSelect("entry-b") to ("SetPlaylistItem" to """{"PlaylistItemId":"entry-b"}"""),
            syncTransport("NextItem", playlistItemId = "entry-b") to ("NextItem" to """{"PlaylistItemId":"entry-b"}"""),
            syncTransport("PreviousItem", playlistItemId = "entry-b") to ("PreviousItem" to """{"PlaylistItemId":"entry-b"}"""),
            syncTransport("Seek", Long.MAX_VALUE) to ("Seek" to """{"PositionTicks":9223372036854775807}"""),
            syncRepeat("RepeatAll") to ("SetRepeatMode" to """{"Mode":"RepeatAll"}"""),
            syncShuffle(true) to ("SetShuffleMode" to """{"Mode":"Shuffle"}"""),
            syncShuffle(false) to ("SetShuffleMode" to """{"Mode":"Sorted"}"""),
            syncIgnoreWait(true) to ("SetIgnoreWait" to """{"IgnoreWait":true}"""),
            syncPing(28) to ("Ping" to """{"Ping":28}""")
        )
        val sync = client()
        expected.forEach { (command, wire) ->
            sync.send(account, command)
            val request = requests.last()
            assertEquals("/proxy/jellyfin/SyncPlay/${wire.first}", request.url.encodedPath)
            assertEquals(Json.parseToJsonElement(wire.second), wireBody(request), wire.first)
            assertTrue(request.body.contentType.toString().startsWith("application/json"))
        }
        assertEquals(expected.size, requests.size)
    }

    @Test fun readinessCarriesTheActualEntryPositionAndIntentAndHasNoPlaceholder() = runTest {
        val sync = client()
        listOf(true to "Ready", false to "Buffering").forEach { (ready, path) ->
            sync.send(account, syncReadiness(ready, 1762776000123L, 5678901234L, true, "entry-current"))
            assertEquals("/proxy/jellyfin/SyncPlay/$path", requests.last().url.encodedPath)
            assertEquals(
                Json.parseToJsonElement(
                    """{"When":"2025-11-10T12:00:00.123Z","PositionTicks":5678901234,"IsPlaying":true,"PlaylistItemId":"entry-current"}"""
                ),
                wireBody(requests.last())
            )
        }
    }

    @Test fun pauseUnpauseStopAndLeaveHaveNoJsonBody() = runTest {
        val sync = client()
        listOf(syncTransport("Pause"), syncTransport("Unpause"), syncTransport("Stop"), SyncRequest("Leave")).forEach {
            sync.send(account, it)
            assertEquals(null, wireBody(requests.last()))
        }
    }

    @Test fun allThreeServerAccessPoliciesAreReturnedWithoutInventingHostRights() = runTest {
        val sync = client()
        listOf("CreateAndJoinGroups", "JoinGroups", "None").forEach { policy ->
            body = """{"Id":"viewer","Policy":{"SyncPlayAccess":"$policy"}}"""
            assertEquals(policy, assertIs<ClientResult.Success<String>>(sync.access(account)).value)
            assertEquals("/proxy/jellyfin/Users/Me", requests.last().url.encodedPath)
        }
    }

    @Test fun emptyListAndDeniedListAreDistinctAndEachRequestRunsOnce() = runTest {
        val sync = client()
        body = "[]"
        assertEquals(emptyList<Any>(), assertIs<ClientResult.Success<List<*>>>(sync.groups(account)).value)
        status = HttpStatusCode.Forbidden
        val denied = assertIs<ClientResult.Failure>(sync.groups(account))
        assertEquals(403, denied.error.status)
        assertTrue(denied.error.message.contains("not allowed"))
        assertEquals(2, requests.size)
        assertEquals("/proxy/jellyfin/SyncPlay/List", requests.last().url.encodedPath)
    }

    @Test fun malformedClockAndGroupResponsesAreRecoverableFailures() = runTest {
        val sync = client()
        body = """{"RequestReceptionTime":"bad","ResponseTransmissionTime":"2026-11-10T12:00:00Z"}"""
        assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<ClientResult.Failure>(sync.utcTime(account)).error.kind)
        body = """[{"GroupId":"g","GroupName":"Incomplete"}]"""
        assertEquals(ErrorKind.INVALID_RESPONSE, assertIs<ClientResult.Failure>(sync.groups(account)).error.kind)
        assertEquals(2, requests.size)
    }

    @Test fun utcTimeRetainsTheServerReceptionAndTransmissionInstants() = runTest {
        body = """{"RequestReceptionTime":"1970-01-01T00:00:01.123Z","ResponseTransmissionTime":"1970-01-01T00:00:01.128Z"}"""
        val time = assertIs<ClientResult.Success<app.pictureshow.core.sync.SyncUtcTime>>(client().utcTime(account)).value
        assertEquals(1123L, time.receptionMs)
        assertEquals(1128L, time.transmissionMs)
        assertEquals("/proxy/jellyfin/GetUtcTime", requests.single().url.encodedPath)
    }

    @Test fun uncertainAndDeniedMutationsAreNeverAutomaticallyRetried() = runTest {
        val sync = client()
        status = HttpStatusCode.Forbidden
        assertEquals(403, sync.send(account, syncTransport("Pause")))
        failure = IllegalStateException("Connection closed")
        assertEquals(0, sync.send(account, syncTransport("Seek", 10000000)))
        failure = null
        responseDelay = ApiTimeouts.REQUEST_MILLIS + 1
        assertEquals(0, sync.send(account, syncTransport("NextItem", playlistItemId = "entry")))
        assertEquals(3, requests.size)
    }

    @Test fun cancellationPropagatesInsteadOfBecomingARecoverableNetworkFailure() = runTest {
        failure = CancellationException("Viewing ended")
        val sync = client()
        assertFailsWith<CancellationException> { sync.send(account, syncTransport("Pause")) }
    }

    @Test fun invalidRequestsFailBeforeHttpDispatch() = runTest {
        assertFailsWith<IllegalArgumentException> { syncTransport("Seek", -1) }
        assertFailsWith<IllegalArgumentException> { syncTransport("NextItem") }
        assertFailsWith<IllegalArgumentException> { syncReadiness(true, 0, -1, false, "entry") }
        assertFailsWith<IllegalArgumentException> { syncReadiness(true, 0, 0, false, "") }
        assertFailsWith<IllegalArgumentException> { syncNewQueue(listOf("a"), 1) }
        assertFailsWith<IllegalArgumentException> { syncNewQueue(emptyList()) }
        assertFailsWith<IllegalArgumentException> { syncRepeat("host-only") }
        assertFailsWith<IllegalArgumentException> { SyncRequest("../Sessions/Logout") }
        assertTrue(requests.isEmpty())
    }

    private fun wireBody(request: HttpRequestData) = (request.body as? TextContent)?.text?.let { Json.parseToJsonElement(it) }
}
