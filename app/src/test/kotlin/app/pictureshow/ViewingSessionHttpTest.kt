/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlaybackClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Exercise the real gateway and wire reports; every identifier and response is fictional. */
class ViewingSessionHttpTest {
    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        val opened = mutableListOf<Long>()
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opened += positionMs
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() {
            snapshot = snapshot.copy(paused = true)
        }
        override fun release() = Unit
    }
    private class Wire(scope: TestScope) {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val deletes = mutableListOf<String?>()
        var sequence = 0
        var block: CompletableDeferred<Unit>? = null
        var failPrepare = false
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(scope.testScheduler)
                addHandler { request ->
                    val path = request.url.encodedPath
                    val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                    calls += path to (body?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject {})
                    when {
                        path.endsWith("PlaybackInfo") -> {
                            sequence++
                            block?.await()
                            if (failPrepare) {
                                respond("{}", HttpStatusCode.ServiceUnavailable)
                            } else {
                                respond(
                                    """{"PlaySessionId":"encode-$sequence","MediaSources":[{"Id":"source","RunTimeTicks":12000000000,
                                "SupportsTranscoding":true,"TranscodingContainer":"ts","TranscodingSubProtocol":"hls",
                                "TranscodingUrl":"/Videos/film/master.m3u8?VideoCodec=h264&MaxFramerate=30&AudioCodec=aac&PlaySessionId=encode-$sequence",
                                "DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":-1,
                                "MediaStreams":[{"Type":"Video","RealFrameRate":60},{"Type":"Audio","Index":1,"Codec":"pcm_s16le","Channels":2}]}]}""",
                                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                                )
                            }
                        }
                        path.endsWith("ActiveEncodings") -> {
                            assertEquals("test-device", request.url.parameters["deviceId"])
                            deletes += request.url.parameters["playSessionId"]
                            respond("", HttpStatusCode.NoContent)
                        }
                        else -> respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        val gateway = ServerPlaybackGateway(
            PlaybackClient(http, ClientIdentity("TV", "test-device")),
            Account(Server("server", "Test", "https://example.test", "12.0"), User("user", "Viewer"), "test-token"),
            "film",
            {
                DeviceCapabilities("TV", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))
            },
            markPlayed = { ClientResult.Success(Unit) }
        )
        fun reports(path: String) = calls.filter { it.first == "/Sessions/Playing$path" }.map { it.second }
    }

    @Test
    fun seeksTrackAndRouteChangesReportOneViewingWithCurrentEncodeAndFinalPosition() = runTest {
        val wire = Wire(this)
        val output = Output()
        val session = PlaybackSession(wire.gateway, output, backgroundScope, "en")
        try {
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            repeat(3) { i ->
                output.snapshot = PlaybackPosition((i + 1) * 100_000L, true)
                session.seek(output.snapshot.milliseconds)
                advanceTimeBy(250)
                runCurrent()
                session.ready()
                runCurrent()
            }
            session.select(MediaStreamType.Audio, 1)
            runCurrent()
            session.ready()
            runCurrent()
            session.routeChanged()
            runCurrent()
            session.ready()
            runCurrent()
            assertEquals(1, wire.reports("").size, "Stream replacement must not start another play")
            assertTrue(wire.reports("/Stopped").isEmpty(), "Only leaving playback reports Stop")
            val progress = wire.reports("/Progress").last()
            assertEquals("encode-6", progress["PlaySessionId"]?.jsonPrimitive?.content)
            assertEquals(3_000_000_000L, progress["PositionTicks"]?.jsonPrimitive?.long)
            assertEquals(true, progress["IsPaused"]?.jsonPrimitive?.boolean)
            output.snapshot = PlaybackPosition(321_000, true)
            session.close()
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            val stop = wire.reports("/Stopped").single()
            assertEquals("encode-6", stop["PlaySessionId"]?.jsonPrimitive?.content)
            assertEquals(3_210_000_000L, stop["PositionTicks"]?.jsonPrimitive?.long)
            assertEquals((1..6).flatMap { listOf("encode-$it", "encode-$it") }.sorted(), wire.deletes.filterNotNull().sorted())
        } finally {
            session.close()
            wire.http.close()
        }
    }

    @Test
    fun replacementHasNoSerialProgressOrStopRequestsBeforeNegotiating() = runTest {
        val wire = Wire(this)
        val output = Output()
        val session = PlaybackSession(wire.gateway, output, backgroundScope, "en")
        try {
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            wire.calls.clear()
            repeat(10) {
                session.seek(100_000L + it * 10_000)
                session.progress() // Position discontinuities from the wrapper must not queue old-source reports.
            }
            advanceTimeBy(250)
            runCurrent()
            assertEquals(listOf("/Videos/ActiveEncodings", "/Items/film/PlaybackInfo"), wire.calls.map { it.first })
            assertEquals(listOf(0L, 190_000L), output.opened)
        } finally {
            session.close()
            runCurrent()
            wire.http.close()
        }
    }

    @Test
    fun stopDuringReplacementCleansBothEncodesAndReportsOnlyFinalTarget() = runTest {
        for (fail in listOf(false, true)) {
            val wire = Wire(this)
            val output = Output()
            val session = PlaybackSession(wire.gateway, output, backgroundScope, "en")
            try {
                session.start(0)
                runCurrent()
                val old = assertNotNull(session.state.value.decision)
                session.ready()
                runCurrent()
                wire.block = CompletableDeferred()
                wire.failPrepare = fail
                output.snapshot = PlaybackPosition(220_000, true)
                session.seek(220_000)
                advanceTimeBy(250)
                runCurrent()
                // A newer backward intent arrives while the previous PlaybackInfo is still blocked.
                output.snapshot = PlaybackPosition(110_000, true)
                assertTrue(session.seek(110_000, old))
                session.close()
                wire.block!!.complete(Unit)
                runCurrent()
                advanceTimeBy(3_000)
                runCurrent()
                assertEquals(listOf(0L), output.opened)
                assertEquals(1, wire.reports("").size)
                val stop = wire.reports("/Stopped").single()
                assertEquals(1_100_000_000L, stop["PositionTicks"]?.jsonPrimitive?.long)
                assertEquals(false, stop["Failed"]?.jsonPrimitive?.boolean)
                val expectedDeletes = if (fail) {
                    listOf("encode-1", "encode-1")
                } else {
                    listOf("encode-1", "encode-1", "encode-2", "encode-2")
                }
                assertEquals(expectedDeletes, wire.deletes.filterNotNull().sorted())
            } finally {
                session.close()
                wire.http.close()
            }
        }
    }

    @Test
    fun failedReplacementStopsAfterTheFailedPrepareAndCloseDoesNotDuplicateStop() = runTest {
        val wire = Wire(this)
        val output = Output()
        val session = PlaybackSession(wire.gateway, output, backgroundScope, "en")
        try {
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            wire.failPrepare = true
            output.snapshot = PlaybackPosition(240_000, true)
            session.seek(240_000)
            advanceTimeBy(250)
            runCurrent()
            assertNotNull(session.state.value.error)
            assertEquals(1, wire.reports("/Stopped").size)
            assertTrue(
                wire.calls.indexOfLast { it.first.endsWith("/Stopped") } > wire.calls.indexOfLast { it.first.endsWith("/PlaybackInfo") }
            )
            assertEquals(true, wire.reports("/Stopped").single()["Failed"]?.jsonPrimitive?.boolean)
            session.close()
            runCurrent()
            assertEquals(2_400_000_000L, wire.reports("/Stopped").single()["PositionTicks"]?.jsonPrimitive?.long)
            assertEquals(1, wire.reports("").size)
        } finally {
            session.close()
            wire.http.close()
        }
    }
}
