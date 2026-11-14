/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LivePlaybackClientTest {
    private fun TestScope.mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler(handler)
            }
        }

    private val account = Account(Server("server", "Demo", "https://example.test/base", "12"), User("viewer", "Viewer"), "token")
    private val identity = ClientIdentity("TV", "device")
    private val initial = """{"PlaySessionId":"viewing","MediaSources":[{"Id":"tuner","RequiresOpening":true,"OpenToken":"open-once"}]}"""
    private val opened = """{"MediaSource":{"Id":"opened","LiveStreamId":"live-id","RequiresClosing":true,"IsInfiniteStream":true,""" +
        """"SupportsDirectStream":true,"Container":"ts"}}"""

    @Test fun sourceIsOpenedOnceWithConsistentJsonAndReturnedSourceIsUsed() = runTest {
        val paths = mutableListOf<String>()
        var body = ""
        val http = mockClient(
            { request ->
                paths += request.url.encodedPath
                if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                    respond(initial, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    assertEquals("/base/LiveStreams/Open", request.url.encodedPath)
                    assertTrue(request.url.parameters.isEmpty())
                    body = request.body.toByteArray().decodeToString()
                    respond(opened, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        )
        try {
            val result = PlaybackClient(http, identity).prepare(account, "channel", CapabilityFixtures.projector)
            val decision = assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(result).value).decision
            assertEquals(listOf("/base/Items/channel/PlaybackInfo", "/base/LiveStreams/Open"), paths)
            assertEquals("opened", decision.mediaSourceId)
            assertEquals("live-id", decision.ownedLiveStreamId)
            assertTrue(decision.isLive)
            assertTrue(decision.url.contains("mediaSourceId=opened"))
            assertTrue(decision.url.contains("liveStreamId=live-id"))
            val fields = PlaybackJson.parseToJsonElement(body).jsonObject
            assertEquals("open-once", fields.getValue("OpenToken").jsonPrimitive.content)
            assertEquals("channel", fields.getValue("ItemId").jsonPrimitive.content)
            assertEquals("viewer", fields.getValue("UserId").jsonPrimitive.content)
            assertEquals("viewing", fields.getValue("PlaySessionId").jsonPrimitive.content)
            assertTrue(fields.getValue("DeviceProfile").jsonObject.isNotEmpty())
        } finally {
            http.close()
        }
    }

    @Test fun returnedExistingLiveIdIsNotOpenedOrClaimed() = runTest {
        var requests = 0
        val http = mockClient(
            {
                requests++
                respond(
                    """{"PlaySessionId":"viewing","MediaSources":[{"Id":"shared","RequiresOpening":true,
                       "OpenToken":"unused","LiveStreamId":"someone-else","RequiresClosing":true,"SupportsDirectStream":true}]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )
        try {
            val result = PlaybackClient(http, identity).prepare(account, "channel", CapabilityFixtures.projector)
            val decision = assertIs<PlayOutcome.Play>(assertIs<ClientResult.Success<PlayOutcome>>(result).value).decision
            assertEquals(1, requests)
            assertNull(decision.ownedLiveStreamId)
        } finally {
            http.close()
        }
    }

    @Test fun deniedOpenIsVisibleAndNeverInventsAStreamUrl() = runTest {
        var requests = 0
        val http = mockClient(
            {
                requests++
                if (requests == 1) {
                    respond(initial, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond("", HttpStatusCode.Forbidden)
                }
            }
        )
        try {
            assertIs<ClientResult.Failure>(PlaybackClient(http, identity).prepare(account, "channel", CapabilityFixtures.projector))
            assertEquals(2, requests)
        } finally {
            http.close()
        }
    }

    @Test fun close404IsAlreadyClosedBut500RemainsFailure() = runTest {
        for ((status, success) in listOf(HttpStatusCode.NotFound to true, HttpStatusCode.InternalServerError to false)) {
            val http = mockClient(
                { request ->
                    assertEquals("owned/id", request.url.parameters["liveStreamId"])
                    respond("", status)
                }
            )
            try {
                val result = PlaybackClient(http, identity).closeLiveStream(account, "owned/id")
                assertEquals(success, result is ClientResult.Success)
            } finally {
                http.close()
            }
        }
    }

    @Test fun unsafeOpenedSourceIsClosedInsteadOfLeaked() = runTest {
        val paths = mutableListOf<String>()
        val http = mockClient(
            { request ->
                paths += request.url.encodedPath
                val body = when {
                    request.url.encodedPath.endsWith("PlaybackInfo") -> initial
                    request.url.encodedPath.endsWith(
                        "Open"
                    ) ->
                        """{"MediaSource":{"Id":"opened","LiveStreamId":"owned","RequiresClosing":true,
                       "TranscodingUrl":"https://outside.test/live.m3u8"}}"""
                    else -> ""
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val outcome = assertIs<ClientResult.Success<PlayOutcome>>(
                PlaybackClient(http, identity).prepare(account, "channel", CapabilityFixtures.projector)
            ).value
            assertIs<PlayOutcome.Unplayable>(outcome)
            assertEquals("/base/LiveStreams/Close", paths.last())
            assertEquals(3, paths.size)
        } finally {
            http.close()
        }
    }

    @Test fun openedSourceWithoutIdentityIsRejectedBeforeAnyMediaUrlIsBuilt() = runTest {
        var count = 0
        val http = mockClient(
            {
                count++
                respond(
                    if (count == 1) initial else """{"MediaSource":{"Id":"opened","SupportsDirectStream":true}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )
        try {
            assertIs<ClientResult.Failure>(PlaybackClient(http, identity).prepare(account, "channel", CapabilityFixtures.projector))
            assertEquals(2, count)
        } finally {
            http.close()
        }
    }

    @Test fun liveReportsCarryIdentityAndLiveIsNeverTranscodeRewound() {
        val decision = PlayDecision(
            "channel",
            MediaSourceInfo(isInfiniteStream = true, liveStreamId = "live", runTimeTicks = 300_000_000),
            PlayMethod.Transcode,
            "https://example.test/master.m3u8",
            emptyMap(),
            "session"
        )
        assertFalse(TranscodeSeek.required(decision))
        assertEquals("live", PlaybackReports(decision).stopped(kotlin.time.Duration.ZERO).liveStreamId)
    }
}
