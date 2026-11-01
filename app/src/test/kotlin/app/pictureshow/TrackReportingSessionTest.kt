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
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackClient
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TrackReportingSessionTest {
    @Test
    fun viewerAudioChoiceDoesNotPromoteCarriedAutomaticSubtitles() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        assertEquals(1, gateway.reports.last().audioStreamIndex)
        assertNull(gateway.reports.last().subtitleStreamIndex)
        session.routeChanged()
        runCurrent()
        session.ready()
        session.failed(true)
        runCurrent()
        session.ready()
        runCurrent()
        assertEquals(3, gateway.requests.last().subtitleStreamIndex)
        assertEquals(1, gateway.reports.last().audioStreamIndex)
        assertNull(gateway.reports.last().subtitleStreamIndex)
        session.close()
        runCurrent()
        assertNull(gateway.reports.last().subtitleStreamIndex)
    }

    @Test
    fun automaticTracksStayOmittedThroughFallbackRouteAudioRecoveryServerSeekAndFinalReport() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.ready()
        session.progress()
        runCurrent()
        session.failed(true)
        runCurrent()
        session.ready()
        session.routeChanged()
        runCurrent()
        session.ready()
        session.audioFailed(retryLocally = true)
        runCurrent()
        session.ready()
        runCurrent()
        assertTrue(session.seek(150_000))
        advanceTimeBy(251)
        runCurrent()
        session.ready()
        runCurrent()
        assertEquals(150_000L, output.position().milliseconds)
        assertEquals(3, gateway.requests.last().subtitleStreamIndex, "Carried indices are playback intent, not viewer choices")
        assertEquals(1, gateway.requests.last().audioStreamIndex)
        assertEquals(3, session.state.value.subtitleIndex)
        session.close()
        runCurrent()
        assertTrue(gateway.reports.size >= 5)
        assertTrue(gateway.reports.all { it.subtitleStreamIndex == null && it.audioStreamIndex == null })
    }

    @Test
    fun sameTrackAndOffAreViewerChoicesIndependentlyAndSurviveReopensButNotANewViewing() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        session.select(MediaStreamType.Subtitle, 3)
        runCurrent()
        assertEquals(3, gateway.reports.last().subtitleStreamIndex)
        assertNull(gateway.reports.last().audioStreamIndex)
        session.select(MediaStreamType.Audio, 1)
        session.select(MediaStreamType.Subtitle, -1)
        session.routeChanged()
        runCurrent()
        session.ready()
        session.failed(true)
        runCurrent()
        session.ready()
        runCurrent()
        assertEquals(-1, gateway.reports.last().subtitleStreamIndex)
        assertEquals(1, gateway.reports.last().audioStreamIndex)
        session.close()
        runCurrent()
        assertEquals(-1, gateway.reports.last().subtitleStreamIndex)
        val next = PlaybackSession(gateway, output, backgroundScope, "en")
        next.start(0)
        runCurrent()
        next.ready()
        runCurrent()
        assertNull(gateway.reports.last().subtitleStreamIndex)
        assertNull(gateway.reports.last().audioStreamIndex)
        next.close()
        runCurrent()
    }

    @Test
    fun localTranscodeSequenceReportsEveryRealSelectionWithoutStaleIndicesOrNewPreparation() = runTest {
        val gateway = Gateway(transcoding = true)
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.ready()
        runCurrent()
        assertNull(gateway.reports.single().subtitleStreamIndex)
        for (index in listOf(4, 5, -1, 3, 3)) {
            session.select(MediaStreamType.Subtitle, index)
            runCurrent()
            assertEquals(index, gateway.reports.last().subtitleStreamIndex)
            assertNull(gateway.reports.last().audioStreamIndex)
            assertEquals(1_260_000_000L, gateway.reports.last().positionTicks)
        }
        assertEquals(listOf(4, 5, -1, 3, 3), gateway.reports.drop(1).map { it.subtitleStreamIndex })
        assertEquals(1, gateway.requests.size)
        session.close()
        runCurrent()
    }

    @Test
    fun captionOverrideAndAutomaticAudioFallbackAreAbsentOnTheWireUntilTheirOwnViewerChoice() = runTest {
        val reports = mutableListOf<JsonObject>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
                    val response = if (request.url.encodedPath.endsWith("PlaybackInfo")) {
                        val subtitle = body["SubtitleStreamIndex"]?.jsonPrimitive?.intOrNull ?: -1
                        """{"MediaSources":[{"Id":"source","SupportsDirectPlay":true,"DefaultSubtitleStreamIndex":$subtitle,
                        "MediaStreams":[{"Type":"Audio","Index":2,"IsDefault":true},
                        {"Type":"Subtitle","Index":3,"Language":"eng","Codec":"subrip","IsDefault":true}]}]}"""
                    } else {
                        if (!request.url.encodedPath.endsWith("Stopped")) reports += body
                        "{}"
                    }
                    respond(response, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            val account = Account(Server("server", "Fixture", "https://example.test", "12"), User("viewer", "Viewer"), "fictional")
            val gateway = ServerPlaybackGateway(PlaybackClient(http, ClientIdentity("TV", "fixture")), account, "item", {
                DeviceCapabilities("TV", emptyList(), DisplayCapabilities(1920, 1080, 60.0), emptyList(), AudioOutput(2))
            }, "eng", markPlayed = { ClientResult.Success(Unit) })
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
            session.start(0)
            runCurrent()
            assertEquals(3, session.state.value.subtitleIndex)
            assertEquals(2, session.state.value.audioIndex)
            session.ready()
            session.progress()
            session.routeChanged()
            runCurrent()
            session.ready()
            runCurrent()
            assertTrue(reports.size >= 3)
            assertTrue(reports.all { "SubtitleStreamIndex" !in it && "AudioStreamIndex" !in it })
            session.select(MediaStreamType.Subtitle, 3)
            runCurrent()
            assertEquals(3, reports.last().getValue("SubtitleStreamIndex").jsonPrimitive.intOrNull)
            assertFalse("AudioStreamIndex" in reports.last())
            session.select(MediaStreamType.Audio, 2)
            session.select(MediaStreamType.Subtitle, -1)
            runCurrent()
            assertEquals(-1, session.state.value.subtitleIndex)
            session.close()
            runCurrent()
            assertEquals(-1, reports.last().getValue("SubtitleStreamIndex").jsonPrimitive.intOrNull)
            assertEquals(2, reports.last().getValue("AudioStreamIndex").jsonPrimitive.intOrNull)
        } finally {
            http.close()
        }
    }

    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() = Unit
        override fun release() = Unit
    }

    private class Gateway(private val transcoding: Boolean = false) : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val reports = mutableListOf<PlaybackProgressInfo>()
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "item",
                        MediaSourceInfo(
                            id = "source",
                            defaultAudioStreamIndex = request.audioStreamIndex ?: 1,
                            defaultSubtitleStreamIndex = request.subtitleStreamIndex ?: 3,
                            transcodingSubProtocol = MediaStreamProtocol.Hls,
                            transcodingContainer = "ts",
                            runTimeTicks = 10_000_000_000,
                            mediaStreams = listOf(MediaStream(type = MediaStreamType.Video, realFrameRate = 60.0)) +
                                (3..5).map {
                                    MediaStream(
                                        type = MediaStreamType.Subtitle,
                                        index = it,
                                        codec = "subrip",
                                        isExternal = true,
                                        deliveryUrl = "/sub$it.srt"
                                    )
                                }
                        ),
                        if (transcoding || !request.enableDirectPlay) PlayMethod.Transcode else PlayMethod.DirectPlay,
                        "https://example.test/master.m3u8?VideoCodec=h264&MaxFramerate=30",
                        emptyMap(),
                        "session${requests.size}"
                    )
                )
            )
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            reports += info
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            reports += info
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit)
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
    }
}
