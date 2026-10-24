/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.ClientResult
import app.pictureshow.core.playback.MediaSourceInfo
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class StartPositionRecoveryTest {
    @Test
    fun unseekableSourceReturningZeroRetriesAtRequestedPositionBeforeReportingAStart() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        val direct = session.state.value.decision
        output.snapshot = PlaybackPosition(0, false)
        session.ready(direct)
        runCurrent()
        assertEquals(listOf(160_000L, 160_000L), gateway.requests.map { it.startPosition.inWholeMilliseconds })
        assertFalse(gateway.requests.last().enableDirectPlay)
        assertFalse(gateway.requests.last().enableDirectStream)
        assertFalse(gateway.requests.last().allowVideoStreamCopy)
        assertTrue(gateway.starts.isEmpty(), "Never tell the server that playback began at zero")
        assertTrue(gateway.stops.isEmpty(), "A replacement stays one viewing")
        session.ready(direct) // The old callback cannot start or replace the new viewing.
        session.ready()
        runCurrent()
        assertEquals(1, gateway.starts.size)
        assertEquals(1_600_000_000, gateway.starts.single().positionTicks)
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
    }

    @Test
    fun longInitialSeekFallsBackOnceButOrdinaryPlaybackBufferingDoesNotChangeRoute() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        val old = session.state.value.decision
        session.buffering(true, old)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, gateway.requests.size, "A file with only an initial sync sample must not spend 45 seconds decoding locally")
        assertEquals(160_000L, gateway.requests.last().startPosition.inWholeMilliseconds)
        val replacement = assertNotNull(session.state.value.decision)
        session.buffering(true, old)
        advanceTimeBy(45_000)
        runCurrent()
        assertNull(session.state.value.error, "Retired-source deadlines must not fail the replacement")
        session.ready(replacement)
        runCurrent()
        session.buffering(true, replacement)
        advanceTimeBy(45_000)
        runCurrent()
        assertNotNull(session.state.value.error)
        assertEquals(2, gateway.requests.size, "A later stall must not create a retry loop")
        session.close()
        runCurrent()
    }

    @Test
    fun replacementThatAlsoIgnoresStartShowsErrorAndCannotCompleteTheItem() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        output.snapshot = PlaybackPosition(0, false)
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(0, false)
        session.ready()
        runCurrent()
        assertNotNull(session.state.value.error)
        assertTrue(gateway.starts.isEmpty())
        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.stops.single().failed)
        session.ended()
        runCurrent()
        assertEquals(0, gateway.played)
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
    }

    @Test
    fun accurateStartAndStartAtZeroKeepTheirNegotiatedRoute() = runTest {
        for (start in listOf(0L, 160_000L)) {
            val gateway = Gateway()
            val output = Output()
            val session = PlaybackSession(gateway, output, backgroundScope, "en")
            session.start(start)
            runCurrent()
            output.snapshot = PlaybackPosition(start + 20, false)
            session.ready()
            runCurrent()
            assertEquals(1, gateway.requests.size)
            assertEquals((start + 20) * 10_000, gateway.starts.single().positionTicks)
            session.close()
            runCurrent()
        }
    }

    @Test
    fun nativeSeekDuringPreparationSupersedesOpeningOffsetWithoutServerReplacement() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        output.snapshot = PlaybackPosition(80_000, true)
        assertFalse(session.seek(80_000), "The decoder still handles native seeks locally")
        session.ready()
        runCurrent()
        assertEquals(1, gateway.requests.size)
        assertEquals(800_000_000, gateway.starts.single().positionTicks)
        assertTrue(gateway.starts.single().isPaused)
        session.close()
        runCurrent()
    }

    @Test
    fun directPlaybackThatWasAlreadyReadyKeepsItsRouteDuringAnOrdinaryStall() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        session.ready()
        runCurrent()
        session.buffering(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, gateway.requests.size)
        assertTrue(session.state.value.bufferingSlow)
        session.buffering(false)
        advanceTimeBy(40_000)
        runCurrent()
        assertNull(session.state.value.error)
        session.close()
        runCurrent()
    }

    @Test
    fun playbackAdvancingBeforeQueuedReadyRunsDoesNotTriggerStartRecovery() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        session.ready() // READY at the correct target, before the command consumer runs.
        output.snapshot = PlaybackPosition(164_000, false)
        runCurrent()
        assertEquals(1, gateway.requests.size, "Queue latency is not an incorrect start")
        assertEquals(1, gateway.starts.size)
        session.close()
        runCurrent()
    }

    @Test
    fun nativeSeekCannotHideAReadyMismatchBeforeItsQueuedRecoveryRuns() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(160_000)
        runCurrent()
        output.snapshot = PlaybackPosition(0, false)
        session.ready() // The output has stopped this zero-reset source.
        output.snapshot = PlaybackPosition(80_000, true)
        assertFalse(session.seek(80_000)) // A seek mask can move position without restarting a stopped loader.
        runCurrent()
        assertEquals(listOf(160_000L, 80_000L), gateway.requests.map { it.startPosition.inWholeMilliseconds })
        assertTrue(gateway.starts.isEmpty(), "A masked seek cannot make a stopped source ready")
        session.ready()
        runCurrent()
        assertEquals(800_000_000, gateway.starts.single().positionTicks)
        assertTrue(gateway.starts.single().isPaused)
        session.close()
        runCurrent()
    }

    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() = Unit
        override fun release() = Unit
    }

    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        val stops = mutableListOf<PlaybackStopInfo>()
        var played = 0
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "film",
                        MediaSourceInfo(id = "source", runTimeTicks = 2_000_000_000),
                        if (requests.size == 1) PlayMethod.DirectPlay else PlayMethod.Transcode,
                        "https://example.test/Videos/film/stream",
                        emptyMap(),
                        "encode-${requests.size}"
                    )
                )
            )
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            starts += info
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stops += info
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
        override suspend fun played(): ClientResult<Unit> {
            played++
            return ClientResult.Success(Unit)
        }
    }
}
