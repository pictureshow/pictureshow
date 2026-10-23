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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlaybackBufferingTest {
    private class Output : PlaybackOutput {
        var released = false
        override fun position() = PlaybackPosition(127_000, false)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            released = false
        }
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() = Unit
        override fun release() {
            released = true
        }
    }

    private class Gateway(val method: PlayMethod = PlayMethod.Transcode) : PlaybackGateway {
        var prepares = 0
        val stops = mutableListOf<PlaybackStopInfo>()
        val deletes = mutableListOf<String>()
        var progressGate: CompletableDeferred<Unit>? = null
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            prepares++
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "item",
                        MediaSourceInfo(id = "source"),
                        method,
                        "https://example.test/stream",
                        emptyMap(),
                        "play-$prepares"
                    )
                )
            )
        }
        override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            progressGate?.await()
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stops += info
            stopGate?.await()
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            deletes += sessionId
            return ClientResult.Success(Unit)
        }
    }

    @Test
    fun stalledSeekShowsFeedbackThenReleasesPlayerBeforeSlowServerCleanup() = runTest {
        val gateway = Gateway().apply { stopGate = CompletableDeferred() }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        session.buffering(true)
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(session.state.value.bufferingSlow)
        assertFalse(output.released)
        // Frequent callbacks (including repeated seek targets) do not renew an endless deadline.
        repeat(7) {
            session.buffering(true)
            runCurrent()
            advanceTimeBy(5_000)
        }
        runCurrent()
        assertNotNull(session.state.value.error)
        assertFalse(session.state.value.preparing)
        assertFalse(session.state.value.bufferingSlow)
        assertTrue(output.released)
        assertTrue(gateway.stops.single().failed)
        assertEquals(1_270_000_000, gateway.stops.single().positionTicks)
        gateway.stopGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("play-1"), gateway.deletes)
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
        assertEquals(1, gateway.prepares) // No implicit quality change or retry loop.
    }

    @Test
    fun feedbackAndReleaseDoNotWaitForAnInFlightProgressReport() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        gateway.progressGate = CompletableDeferred()
        session.progress()
        runCurrent() // The session command consumer is now waiting on the server.
        session.routeChanged()
        session.select(MediaStreamType.Audio, 1)
        session.failed(true)
        session.buffering(true)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(session.state.value.bufferingSlow)
        advanceTimeBy(35_000)
        runCurrent()
        assertNotNull(session.state.value.error)
        assertTrue(output.released)
        assertTrue(gateway.stops.isEmpty())
        gateway.progressGate!!.complete(Unit)
        runCurrent()
        assertTrue(gateway.stops.single().failed)
        assertEquals(listOf("play-1"), gateway.deletes)
        assertEquals(1, gateway.prepares)
        assertNotNull(session.state.value.error)
        session.close()
        runCurrent()
    }

    @Test
    fun ordinaryDirectAndTranscodedSeeksCanResumeAndCancelTheirDeadline() = runTest {
        for (method in listOf(PlayMethod.DirectPlay, PlayMethod.Transcode)) {
            val gateway = Gateway(method)
            val output = Output()
            val session = PlaybackSession(gateway, output, backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            session.buffering(true)
            runCurrent()
            advanceTimeBy(18_000)
            runCurrent()
            session.buffering(false)
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertNull(session.state.value.error)
            assertFalse(session.state.value.bufferingSlow)
            assertFalse(output.released)
            assertTrue(gateway.stops.isEmpty())
            session.close()
            runCurrent()
        }
    }

    @Test
    fun pausedBufferingDoesNotExpireAndNewWaitGetsItsOwnDeadline() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.buffering(true)
        runCurrent()
        advanceTimeBy(40_000)
        session.buffering(false) // Media3 sends waiting=false when playWhenReady=false.
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(session.state.value.error)
        session.buffering(true)
        runCurrent()
        advanceTimeBy(44_999)
        runCurrent()
        assertNull(session.state.value.error)
        advanceTimeBy(1)
        runCurrent()
        assertNotNull(session.state.value.error)
        assertEquals(listOf("play-1"), gateway.deletes)
        session.close()
        runCurrent()
    }

    @Test
    fun oldSourceCallbacksAndCancelledDeadlineCannotFailReplacement() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        val old = session.state.value.decision
        session.buffering(true, old)
        runCurrent()
        advanceTimeBy(40_000)
        session.routeChanged()
        runCurrent()
        session.buffering(true, old)
        session.buffering(false, old)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(session.state.value.error)
        assertFalse(session.state.value.bufferingSlow)
        assertEquals(listOf("play-1"), gateway.deletes)
        session.close()
        runCurrent()
    }

    @Test
    fun exitCancelsBufferingWithoutDuplicateStop() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.buffering(true)
        runCurrent()
        session.close()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertNull(session.state.value.error)
        assertEquals(1, gateway.stops.size)
        assertEquals(listOf("play-1"), gateway.deletes)
    }
}
