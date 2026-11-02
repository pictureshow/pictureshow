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
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class PlaybackReportDiagnosticsTest {
    private class Output : PlaybackOutput {
        override fun position() = PlaybackPosition(0, false)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() = Unit
        override fun release() = Unit
    }

    @Test
    fun eachStartProgressAndStopLogsItsActualBodyIndependentOriginsAndHttpStatus() = runTest {
        val lines = mutableListOf<String>()
        val bodies = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        respond(INFO, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        try {
            val gateway = gateway(http)
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en", diagnostic = lines::add)
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.select(MediaStreamType.Subtitle, 3)
            runCurrent()
            session.select(MediaStreamType.Audio, 2)
            runCurrent()
            session.select(MediaStreamType.Subtitle, -1)
            runCurrent()
            session.close()
            runCurrent()
            val reports = lines.filter { it.startsWith("report ") }.map { it.replace(Regex("elapsedMs=\\d+"), "elapsedMs=N") }
            val prefix = "report item=item session=play "
            assertEquals(
                listOf(
                    "${prefix}seq=1 kind=start positionMs=0 audio=omitted subtitle=omitted audioOrigin=automatic subtitleOrigin=automatic status=204 failure=none elapsedMs=N",
                    "${prefix}seq=2 kind=progress positionMs=0 audio=omitted subtitle=3 audioOrigin=automatic subtitleOrigin=viewer status=204 failure=none elapsedMs=N",
                    "${prefix}seq=3 kind=progress positionMs=0 audio=2 subtitle=3 audioOrigin=viewer subtitleOrigin=viewer status=204 failure=none elapsedMs=N",
                    "${prefix}seq=4 kind=progress positionMs=0 audio=2 subtitle=-1 audioOrigin=viewer subtitleOrigin=viewer status=204 failure=none elapsedMs=N",
                    "${prefix}seq=5 kind=progress positionMs=0 audio=2 subtitle=-1 audioOrigin=viewer subtitleOrigin=viewer status=204 failure=none elapsedMs=N",
                    "${prefix}seq=6 kind=stop positionMs=0 audio=omitted subtitle=omitted audioOrigin=viewer subtitleOrigin=viewer status=204 failure=none elapsedMs=N"
                ),
                reports
            )
            assertEquals(bodies.size, reports.size)
            bodies.zip(reports).forEach { (body, line) ->
                for ((wire, field) in listOf("AudioStreamIndex" to "audio", "SubtitleStreamIndex" to "subtitle")) {
                    assertTrue("$field=${body[wire]?.jsonPrimitive?.content ?: "omitted"} " in line)
                }
            }
            assertTrue(lines.any { "track-reselect" in it })
        } finally {
            http.close()
        }
    }

    @Test
    fun aBlockedPeriodicReportDelaysAPickButCannotSendAnOlderQueuedSnapshotAfterIt() = runTest {
        val bodies = mutableListOf<JsonObject>()
        val lines = mutableListOf<String>()
        var progress = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        respond(INFO, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        bodies += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                        if (request.url.encodedPath.endsWith("Progress") && ++progress == 1) delay(60_000)
                        respond("", HttpStatusCode.NoContent)
                    }
                }
            }
        }
        try {
            val session = PlaybackSession(gateway(http), Output(), backgroundScope, "en", diagnostic = lines::add)
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.progress()
            runCurrent()
            session.select(MediaStreamType.Subtitle, 3)
            advanceTimeBy(25_000)
            runCurrent()
            assertEquals(2, bodies.size, "In-flight Progress holds the serial queue, including the pick")
            advanceTimeBy(5_001)
            runCurrent()
            val progresses = bodies.drop(2)
            assertTrue(progresses.isNotEmpty())
            assertTrue(progresses.all { it["SubtitleStreamIndex"]?.jsonPrimitive?.int == 3 })
            assertTrue(lines.any { "seq=2 kind=progress" in it && "subtitle=omitted" in it && "status=none failure=timeout" in it })
            assertTrue(lines.any { "seq=3 kind=progress" in it && "subtitle=3" in it && "subtitleOrigin=viewer status=204" in it })
            session.close()
            runCurrent()
        } finally {
            http.close()
        }
    }

    @Test
    fun failedProgressIsDroppedUntilNextTickAndStopRetryHasItsOwnOrderedOutcome() = runTest {
        val lines = mutableListOf<String>()
        var progresses = 0
        var stops = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        respond(INFO, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    } else {
                        val failure = when {
                            request.url.encodedPath.endsWith("Progress") -> ++progresses == 1
                            request.url.encodedPath.endsWith("Stopped") -> ++stops == 1
                            else -> false
                        }
                        respond("", if (failure) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NoContent)
                    }
                }
            }
        }
        try {
            val session = PlaybackSession(gateway(http), Output(), backgroundScope, "en", diagnostic = lines::add)
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.select(MediaStreamType.Subtitle, 3)
            runCurrent()
            assertEquals(1, progresses)
            advanceTimeBy(9_999)
            runCurrent()
            assertEquals(1, progresses)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(2, progresses)
            session.close()
            runCurrent()
            assertEquals(2, stops)
            val reports = lines.filter { it.startsWith("report ") }
            assertTrue(reports[1].contains("subtitle=3 audioOrigin=automatic subtitleOrigin=viewer status=503 failure=http"))
            assertTrue(reports[2].contains("subtitle=3 audioOrigin=automatic subtitleOrigin=viewer status=204 failure=none"))
            assertTrue(reports.takeLast(2)[0].contains("kind=stop") && reports.takeLast(2)[0].contains("status=503 failure=http"))
            assertTrue(reports.last().contains("kind=stop") && reports.last().contains("status=204 failure=none"))
        } finally {
            http.close()
        }
    }

    private fun gateway(http: HttpClient) = ServerPlaybackGateway(
        PlaybackClient(http, ClientIdentity("TV", "fixture")),
        Account(Server("server", "Fixture", "https://example.test", "12"), User("viewer", "Viewer"), "fictional"),
        "item",
        { DeviceCapabilities("TV", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2)) },
        markPlayed = { ClientResult.Success(Unit) }
    )

    companion object {
        private const val INFO = """{"PlaySessionId":"play","MediaSources":[{"Id":"source","SupportsDirectPlay":true,"Container":"mkv",
        "DefaultAudioStreamIndex":2,"DefaultSubtitleStreamIndex":3,"MediaStreams":[{"Type":"Audio","Index":2},
        {"Type":"Subtitle","Index":3,"Codec":"subrip","IsExternal":true,"DeliveryUrl":"/sub.srt"}]}]}"""
    }
}
