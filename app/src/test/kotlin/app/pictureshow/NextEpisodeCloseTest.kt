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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class NextEpisodeCloseTest {
    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val events = mutableListOf<String>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        val progresses = mutableListOf<PlaybackProgressInfo>()
        val stops = mutableListOf<PlaybackStopInfo>()
        val encodes = mutableListOf<String>()
        val outcomes = ArrayDeque<ClientResult<PlayOutcome>>()
        var gate: CompletableDeferred<Unit>? = null
        var stopFailures = 0
        var playedFailures = 0
        var playedGate: CompletableDeferred<Unit>? = null
        override suspend fun played(): ClientResult<Unit> {
            playedGate?.await()
            events += "played"
            if (playedFailures-- > 0) return ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline"))
            return ClientResult.Success(Unit)
        }
        var stopGate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            gate?.await()
            return outcomes.removeFirst()
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "start"
            starts += info
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "progress"
            progresses += info
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stopGate?.await()
            events += "stop"
            stops += info
            if (stopFailures-- > 0) return ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline"))
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            events += "delete"
            encodes += sessionId
            return ClientResult.Success(Unit)
        }
    }
    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        var opens = 0
        var locallySwitchable = true
        var released = false
        var openThrows = false
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            snapshot = PlaybackPosition(positionMs, paused)
            opens++
            if (openThrows) error("decoder initialization failed")
        }
        override fun select(type: MediaStreamType, index: Int) = locallySwitchable
        override fun pause() {
            snapshot = snapshot.copy(paused = true)
        }
        override fun release() {
            released = true
        }
    }
    private fun decision(
        method: PlayMethod = PlayMethod.DirectPlay,
        sessionId: String = "session-one",
        subtitleIndex: Int = -1,
        runtime: Long? = null
    ) = PlayDecision(
        "item-one",
        MediaSourceInfo(
            id = "source-one",
            runTimeTicks = runtime,
            defaultAudioStreamIndex = 2,
            defaultSubtitleStreamIndex = subtitleIndex,
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Audio, index = 1, language = "en"),
                MediaStream(type = MediaStreamType.Audio, index = 2, language = "fr"),
                MediaStream(type = MediaStreamType.Subtitle, index = 3, language = "en")
            )
        ),
        method,
        "https://example.test/video",
        mapOf("Authorization" to "test-header"),
        sessionId
    )

    @Test fun advanceWaitsForAcknowledgedStopThenPlayedThenRefreshAndNext() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            stopGate = CompletableDeferred()
            playedGate = CompletableDeferred()
        }
        val output = Output()
        val session = PlaybackSession(
            gateway,
            output,
            backgroundScope,
            "en",
            onFinished = { gateway.events += "refresh" },
            onCompletionPending = { gateway.events += "pending" }
        )
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(45_000, true)
        session.closeForNext({ true }) { gateway.events += "next" }
        session.closeForNext({ true }) { gateway.events += "duplicate-next" }
        runCurrent()
        assertEquals(listOf("start", "pending", "progress"), gateway.events)
        gateway.stopGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "pending", "progress", "stop"), gateway.events)
        gateway.playedGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "pending", "progress", "stop", "played", "refresh", "next"), gateway.events)
        assertEquals(1, gateway.starts.size)
        assertEquals(1, gateway.stops.size)
        assertEquals(450_000_000L, gateway.stops.single().positionTicks)
    }

    @Test fun playedFailureRetriesOnceAndStillStartsNextAfterBothFailures() = runTest {
        for (failures in listOf(1, 2)) {
            val gateway = Gateway().apply {
                outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
                playedFailures = failures
            }
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.closeForNext({ true }) { gateway.events += "next" }
            runCurrent()
            assertEquals(listOf("played", "played", "next"), gateway.events.takeLast(3))
            assertEquals(1, gateway.starts.size)
            assertEquals(1, gateway.stops.size)
            assertEquals(failures == 2, session.state.value.reportError != null)
        }
    }

    @Test fun failedStopNeverMarksPlayedButStillAllowsNext() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            stopFailures = 2
        }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.closeForNext({ true }) { gateway.events += "next" }
        runCurrent()
        assertEquals(listOf("start", "progress", "stop", "stop", "next"), gateway.events)
    }

    @Test fun backOrStopDuringSwitchBeforeAcknowledgedStopSuppressesPlayed() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            stopGate = CompletableDeferred()
        }
        var advance = true
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.closeForNext({ advance }) { if (advance) gateway.events += "next" }
        runCurrent()
        advance = false
        session.close()
        gateway.stopGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "progress", "stop"), gateway.events)
    }

    @Test fun backWhilePlayedIsPendingCancelsItAndStillFinishesCleanup() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            playedGate = CompletableDeferred()
        }
        var advance = true
        var cleaned = false
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en", onCleanupDone = { cleaned = true })
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.closeForNext({ advance }) { if (advance) gateway.events += "next" }
        runCurrent()
        assertEquals(listOf("start", "progress", "stop"), gateway.events)
        advance = false
        session.close()
        runCurrent()
        gateway.playedGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "progress", "stop"), gateway.events)
        assertTrue(cleaned)
    }

    @Test fun naturalCompletionThenCardAdvanceDoesNotMarkTwice() = runTest {
        val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(decision())) }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.ended()
        session.closeForNext({ true }) { gateway.events += "next" }
        runCurrent()
        assertEquals(1, gateway.events.count { it == "played" })
        assertEquals(1, gateway.starts.size)
        assertEquals(1, gateway.stops.size)
        assertEquals("next", gateway.events.last())
    }

    @Test fun ordinaryExitAndErrorAndUnstartedAdvanceNeverMarkPlayed() = runTest {
        for (reason in listOf("back", "stop", "error", "unstarted")) {
            val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(decision())) }
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
            session.start(0)
            runCurrent()
            if (reason != "unstarted") {
                session.ready()
                runCurrent()
            }
            if (reason == "error") {
                session.failed(false)
                runCurrent()
            }
            if (reason in listOf("error", "unstarted")) session.closeForNext({ true }) {} else session.close()
            runCurrent()
            assertFalse("played" in gateway.events, reason)
        }
    }
}
