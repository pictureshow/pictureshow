/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamProtocol
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class CopiedAudioSeekSessionTest {
    @Test
    fun copiedAudioTrackReplacementPreservesPauseTargetAndRateProfileEvaluation() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(120_000)
        runCurrent()
        output.snapshot = PlaybackPosition(120_000, true)
        session.ready()
        runCurrent()
        session.select(MediaStreamType.Audio, 3)
        runCurrent()
        val request = gateway.requests.last()
        assertTrue(request.enableDirectPlay)
        assertTrue(request.enableDirectStream)
        assertEquals(3, request.audioStreamIndex)
        assertEquals(2, request.subtitleStreamIndex)
        assertEquals(PlaybackPosition(120_000, true), output.snapshot)
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(1, gateway.events.count { it == "start" })
        assertEquals(1, gateway.events.count { it == "stop" })
    }

    @Test
    fun sweepSpacedBurstsFromZeroAndMidFilePlayingAndPausedKeepOneViewingAndFinalAbsoluteTarget() = runTest {
        for (start in listOf(0L, 120_000L)) {
            for (paused in listOf(false, true)) {
                val gateway = Gateway()
                val output = Output()
                val session = PlaybackSession(gateway, output, backgroundScope, "en")
                session.start(start)
                runCurrent()
                output.snapshot = PlaybackPosition(start, paused)
                session.ready()
                runCurrent()
                repeat(10) { step ->
                    val target = start + (step + 1) * 10_000
                    assertTrue(session.seek(target, session.state.value.decision))
                    advanceTimeBy(700)
                    runCurrent()
                    assertEquals(PlaybackPosition(target, paused), output.snapshot)
                    session.ready()
                    runCurrent()
                }
                assertEquals(start + 100_000, gateway.requests.last().startPosition.inWholeMilliseconds)
                assertEquals(1, gateway.events.count { it == "start" })
                assertEquals(0, gateway.events.count { it == "stop" })
                assertEquals(11, output.opens)
                assertTrue(session.ended())
                runCurrent()
                assertEquals(listOf("stop", "played"), gateway.events.filter { it == "stop" || it == "played" })
                session.close()
                runCurrent()
                assertEquals(1, gateway.events.count { it == "stop" })
            }
        }
    }

    @Test
    fun mixedDirectionIntentDuringBlockedReplacementOpensOnlyLatestAndCleansSupersededEncode() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(120_000)
        runCurrent()
        session.ready()
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        gateway.gate = CompletableDeferred()
        assertTrue(session.seek(130_000, old))
        advanceTimeBy(700)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertTrue(session.seek(160_000, old))
        assertTrue(session.seek(100_000, old))
        gateway.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(120_000L, 130_000L, 100_000L), gateway.requests.map { it.startPosition.inWholeMilliseconds })
        assertEquals(2, output.opens, "The superseded middle stream must never open")
        assertEquals(100_000L, output.snapshot.milliseconds)
        assertTrue("delete:encode-2" in gateway.events)
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(1, gateway.events.count { it == "start" })
        assertEquals(1, gateway.events.count { it == "stop" })
        assertEquals(0, gateway.events.count { it == "played" })
    }

    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        var opens = 0
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opens++
            assertTrue(audioIndex in listOf(1, 3))
            assertEquals(2, subtitleIndex)
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() {
            snapshot = snapshot.copy(paused = true)
        }
        override fun release() = Unit
    }

    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val events = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            val id = "encode-${requests.size}"
            gate?.await()
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "item-copy",
                        MediaSourceInfo(
                            id = "source-copy",
                            runTimeTicks = 6_000_000_000,
                            transcodingContainer = "ts",
                            transcodingSubProtocol = MediaStreamProtocol.Hls,
                            defaultAudioStreamIndex = 1,
                            defaultSubtitleStreamIndex = 2,
                            mediaStreams = listOf(
                                MediaStream(type = MediaStreamType.Video, index = 0, codec = "av1", realFrameRate = 60.0),
                                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", channels = 6),
                                MediaStream(type = MediaStreamType.Subtitle, index = 2, codec = "srt")
                            )
                        ),
                        PlayMethod.Transcode,
                        "https://example.test/Videos/item-copy/master.m3u8?VideoCodec=h264&MaxFramerate=30&AudioCodec=aac&MaxAudioChannels=6",
                        emptyMap(),
                        id
                    )
                )
            )
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "start"
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "progress"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            events += "stop"
            return ClientResult.Success(Unit)
        }
        override suspend fun played(): ClientResult<Unit> {
            events += "played"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            events += "delete:$sessionId"
            return ClientResult.Success(Unit)
        }
    }
}
