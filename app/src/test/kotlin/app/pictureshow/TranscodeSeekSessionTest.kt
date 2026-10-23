/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A remote seek is a viewer intent that can supersede work while the server is still negotiating. */
class TranscodeSeekSessionTest {
    @Test
    fun tenRapidStepsNegotiateOnlyLatestTargetAfterOldEncodeCleanup() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        repeat(10) {
            val target = 126_000L + (it + 1) * 10_000
            output.snapshot = PlaybackPosition(target, false)
            session.seek(target, old)
        }
        advanceTimeBy(249)
        runCurrent()
        assertEquals(1, gateway.requests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(126_000L, 226_000L), gateway.requests.map { it.startPosition.inWholeMilliseconds })
        assertEquals(listOf(126_000L, 226_000L), output.opens.map { it.position })
        assertTrue(events.indexOf("delete:session-1") < events.indexOf("prepare:226000"))
        assertTrue(events.indexOf("delete:session-1") < events.indexOf("open:226000"))
        val request = gateway.requests.last()
        assertEquals("source-1", request.mediaSourceId)
        assertTrue(request.enableDirectPlay, "Keep video incompatibility evaluation so the measured software fps floor survives seeking")
        assertTrue(request.enableDirectStream)
        session.close()
        runCurrent()
    }

    @Test
    fun seekStopsTheOldEncodeAgainLaterAndNeverTheNewOne() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        output.snapshot = PlaybackPosition(226_000, false)
        session.seek(226_000, old)
        advanceTimeBy(250)
        runCurrent()
        val replacement = assertNotNull(session.state.value.decision)
        session.ready(replacement)
        runCurrent()
        assertEquals(listOf("delete:session-1"), events.filter { it.startsWith("delete:") })
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf("delete:session-1", "delete:session-1"), events.filter { it.startsWith("delete:") })
        session.close()
        runCurrent()
        assertEquals("delete:session-2", events.filter { it.startsWith("delete:") }.last())
    }

    @Test
    fun seekThatArrivesDuringPrepareDiscardsIntermediateStreamAndOpensLatestOnly() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        gateway.gate = CompletableDeferred()
        output.snapshot = PlaybackPosition(200_000, false)
        session.seek(200_000, old)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(200_000L, gateway.requests.last().startPosition.inWholeMilliseconds)
        output.snapshot = PlaybackPosition(300_000, false)
        session.seek(300_000, old)
        gateway.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(126_000L, 200_000L, 300_000L), gateway.requests.map { it.startPosition.inWholeMilliseconds })
        assertEquals(listOf(126_000L, 300_000L), output.opens.map { it.position })
        assertTrue(events.indexOf("delete:session-2") < events.indexOf("open:300000"))
        assertFalse(events.contains("open:200000"))
        session.close()
        runCurrent()
    }

    @Test
    fun selectedTracksPausedStateAndReportsStayOnOriginalMediaCoordinates() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        output.snapshot = PlaybackPosition(226_000, true)
        session.seek(226_000, old)
        advanceTimeBy(250)
        runCurrent()
        val opened = output.opens.last()
        assertEquals(2, opened.audio)
        assertEquals(3, opened.subtitle)
        assertTrue(opened.paused)
        assertEquals(2, gateway.requests.last().audioStreamIndex)
        assertEquals(3, gateway.requests.last().subtitleStreamIndex)
        assertTrue(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().enableDirectStream)
        session.ready(assertNotNull(session.state.value.decision))
        runCurrent()
        assertEquals(2_260_000_000L, gateway.starts.last().positionTicks)
        assertTrue(gateway.starts.last().isPaused)
        assertEquals(2, gateway.starts.last().audioStreamIndex)
        assertEquals(3, gateway.starts.last().subtitleStreamIndex)
        session.close()
        runCurrent()
    }

    @Test
    fun pauseWhilePlaybackInfoIsBlockedSurvivesTheReplacement() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        gateway.gate = CompletableDeferred()
        output.snapshot = PlaybackPosition(200_000, false)
        session.seek(200_000, old)
        advanceTimeBy(250)
        runCurrent()
        output.pause()
        gateway.gate!!.complete(Unit)
        runCurrent()
        assertTrue(output.opens.last().paused, "The latest pause intent must win over the earlier seek snapshot")
        session.close()
        runCurrent()
    }

    @Test
    fun oldSourceAndClosedSessionCannotRestartOrReportTheReplacedStream() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        session.ready(old)
        runCurrent()
        output.snapshot = PlaybackPosition(200_000, false)
        session.seek(200_000, old)
        advanceTimeBy(250)
        runCurrent()
        val count = gateway.requests.size
        val starts = gateway.starts.size
        session.seek(400_000, old)
        session.ready(old)
        session.progress(old)
        advanceTimeBy(250)
        runCurrent()
        assertEquals(count, gateway.requests.size)
        assertEquals(starts, gateway.starts.size)
        session.close()
        runCurrent()
        val finalEvents = events.toList()
        session.seek(500_000)
        session.ready()
        session.progress()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(finalEvents, events)
    }

    @Test
    fun closingDuringPrepareCleansReturnedEncodeWithoutOpeningIt() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        gateway.gate = CompletableDeferred()
        output.snapshot = PlaybackPosition(200_000, false)
        session.seek(200_000, old)
        advanceTimeBy(250)
        runCurrent()
        session.close()
        gateway.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(126_000L), output.opens.map { it.position })
        assertTrue(events.contains("delete:session-2"))
    }

    @Test
    fun failedPrepareLeavesAnErrorAndFurtherSeekIntentIsInert() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        gateway.fail = true
        session.seek(200_000, old)
        advanceTimeBy(250)
        runCurrent()
        assertNotNull(session.state.value.error)
        val requests = gateway.requests.size
        session.seek(300_000, old)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(requests, gateway.requests.size)
        assertEquals(1, output.opens.size)
        session.close()
        runCurrent()
    }

    @Test
    fun seekAcceptanceDistinguishesCurrentRetiredClosedAndErroredSessions() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        assertFalse(session.seek(200_000), "An unopened session cannot accept a seek")
        session.start(126_000)
        runCurrent()
        val old = assertNotNull(session.state.value.decision)
        val retired = PlayDecision(old.itemId, old.mediaSource, old.method, old.url, old.headers, "retired", old.transcodeReasons)
        assertFalse(session.seek(200_000, retired))
        assertTrue(session.seek(200_000, old))
        advanceTimeBy(250)
        runCurrent()
        val current = assertNotNull(session.state.value.decision)
        assertFalse(session.seek(300_000, old), "The retired wrapper must not create a pending target")
        gateway.fail = true
        assertTrue(session.seek(300_000, current))
        advanceTimeBy(250)
        runCurrent()
        assertNotNull(session.state.value.error)
        assertFalse(session.seek(400_000, current), "A failed session cannot accept a pending target")
        session.close()
        runCurrent()
        assertFalse(session.seek(500_000, current))
    }

    @Test
    fun trackSwitchOnReducedRateSourcePreservesDirectEvaluationForTheMeasuredRateFloor() = runTest {
        val events = mutableListOf<String>()
        val gateway = Gateway(events)
        val output = Output(events)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.ready(assertNotNull(session.state.value.decision))
        runCurrent()
        output.snapshot = PlaybackPosition(126_000, true)
        session.select(MediaStreamType.Audio, 2)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().enableDirectStream)
        assertEquals(126_000L, gateway.requests.last().startPosition.inWholeMilliseconds)
        assertTrue(output.opens.last().paused)
        session.close()
        runCurrent()
    }

    private data class Open(val position: Long, val audio: Int, val subtitle: Int, val paused: Boolean)

    private class Output(private val events: MutableList<String>) : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        val opens = mutableListOf<Open>()
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            events += "open:$positionMs"
            opens += Open(positionMs, audioIndex, subtitleIndex, paused)
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() {
            snapshot = snapshot.copy(paused = true)
        }
        override fun release() {
            events += "release"
        }
    }

    private class Gateway(private val events: MutableList<String>) : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            val session = "session-${requests.size}"
            events += "prepare:${request.startPosition.inWholeMilliseconds}"
            gate?.await()
            if (fail) return ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Server unavailable"))
            return ClientResult.Success(PlayOutcome.Play(eligibleDecision(session)))
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            starts += info
            events += "start:${info.playSessionId}"
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "progress:${info.playSessionId}"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            events += "stop:${info.playSessionId}"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            events += "delete:$sessionId"
            return ClientResult.Success(Unit)
        }
    }

    companion object {
        private fun eligibleDecision(session: String) = PlayDecision(
            itemId = "item-1",
            mediaSource = MediaSourceInfo(
                id = "source-1",
                runTimeTicks = 6_000_000_000,
                transcodingSubProtocol = MediaStreamProtocol.Hls,
                transcodingContainer = "ts",
                defaultAudioStreamIndex = 2,
                defaultSubtitleStreamIndex = 3,
                mediaStreams = listOf(
                    MediaStream(type = MediaStreamType.Video, realFrameRate = 60.0),
                    MediaStream(type = MediaStreamType.Audio, index = 2, language = "en", codec = "pcm_s16le", channels = 2),
                    MediaStream(type = MediaStreamType.Subtitle, index = 3, language = "en")
                )
            ),
            method = PlayMethod.Transcode,
            url = "https://example.test/Videos/item-1/master.m3u8?VideoCodec=h264&MaxFramerate=30&AudioCodec=aac",
            headers = emptyMap(),
            playSessionId = session
        )
    }
}
