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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlaybackSessionTest {
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

    @Test
    fun naturalEndMarksPlayedAfterStopBeforeRefreshAndNextEvenWithBogusRuntime() = runTest {
        val chosen = decision(runtime = 42_949_822_950_000)
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(chosen))
            stopGate = CompletableDeferred()
            playedGate = CompletableDeferred()
        }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en", onFinished = { gateway.events += "refresh" })
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(60_000, false)
        session.ended(chosen)
        session.close { gateway.events += "next" }
        runCurrent()
        assertEquals(listOf("start", "progress"), gateway.events)
        gateway.stopGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "progress", "stop"), gateway.events)
        gateway.playedGate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("start", "progress", "stop", "played", "refresh", "next"), gateway.events)
        assertEquals(600_000_000L, gateway.stops.single().positionTicks)
    }

    @Test
    fun nativeTranscodeBufferingReleasesOutputAtFortyFiveSeconds() = runTest {
        val chosen = decision(method = PlayMethod.Transcode)
        val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(chosen)) }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.buffering(true, chosen)
        runCurrent()
        advanceTimeBy(44_999)
        runCurrent()
        assertFalse(output.released)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(output.released)
        assertFalse("played" in gateway.events)
        session.close()
        runCurrent()
    }

    @Test
    fun stopBackErrorsAndGiveUpNeverMarkPlayedAndStaleEndCannotFinishReplacement() = runTest {
        for (exit in listOf("stop", "back", "error", "give-up", "stale")) {
            val chosen = decision()
            val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(chosen)) }
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            when (exit) {
                "error" -> session.failed(false, chosen)
                "give-up" -> {
                    session.buffering(true, chosen)
                    advanceTimeBy(45_000)
                }
                "stale" -> session.ended(decision(sessionId = "old"))
            }
            runCurrent()
            if (exit == "error" || exit == "give-up") session.ended(chosen)
            session.close()
            runCurrent()
            assertFalse("played" in gateway.events, exit)
        }
    }

    @Test
    fun naturalEndReportsImmediatelyWithoutNavigationAndDuplicateEndDoesNotMarkTwice() = runTest {
        val chosen = decision()
        val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(chosen)) }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.ended(chosen)
        session.ended(chosen)
        runCurrent()
        assertEquals(listOf("start", "progress", "stop", "played"), gateway.events)
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
        assertEquals(1, gateway.events.count { it == "played" })
    }

    @Test
    fun naturalEndRetriesTransientReportsButDoesNotMarkPlayedBeforeAnAcknowledgedStop() = runTest {
        for (failures in listOf(1, 2)) {
            val chosen = decision()
            val gateway = Gateway().apply {
                outcomes += ClientResult.Success(PlayOutcome.Play(chosen))
                stopFailures = failures
                playedFailures = 1
            }
            val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.ended(chosen)
            session.close()
            runCurrent()
            assertEquals(2, gateway.events.count { it == "stop" })
            assertEquals(if (failures == 1) 2 else 0, gateway.events.count { it == "played" })
            if (failures == 2) assertEquals("Offline", session.state.value.reportError)
        }
    }

    @Test
    fun nextEpisodeCannotStartUntilFinalStopAndEncodeCleanupComplete() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode)))
            stopGate = CompletableDeferred()
        }
        val output = Output()
        var starts = 0
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(95_000, false)
        session.close {
            gateway.events += "next-start"
            starts++
        }
        session.close { starts++ }
        runCurrent()
        assertTrue(output.released)
        assertEquals(0, starts)
        assertTrue(gateway.stops.isEmpty())
        gateway.stopGate!!.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
        assertEquals(listOf("progress", "stop", "delete", "next-start"), gateway.events.takeLast(4))
        assertEquals(950_000_000, gateway.stops.single().positionTicks)
    }

    @Test
    fun serverSubtitleDefaultIsUsedAndOffIsRememberedInFinalProgressBeforeStop() = runTest {
        val chosen = decision(subtitleIndex = 3)
        val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(chosen)) }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "fr")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        assertEquals(2, gateway.starts.single().audioStreamIndex)
        assertEquals(3, gateway.starts.single().subtitleStreamIndex)
        session.select(MediaStreamType.Audio, 1)
        session.select(MediaStreamType.Subtitle, -1)
        runCurrent()
        assertEquals(1, gateway.progresses.last().audioStreamIndex)
        assertEquals(-1, gateway.progresses.last().subtitleStreamIndex)
        session.close()
        runCurrent()
        assertEquals(1, gateway.requests.size)
        assertEquals(1, gateway.progresses.last().audioStreamIndex)
        assertEquals(-1, gateway.progresses.last().subtitleStreamIndex)
        assertEquals(listOf("progress", "stop"), gateway.events.takeLast(2))
    }

    @Test
    fun startPeriodicPauseSeekTrackAndStopReportsUseHundredNanosecondTicks() = runTest {
        val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(decision())) }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(90_000)
        runCurrent()
        assertEquals(90.seconds, gateway.requests.single().startPosition)
        assertEquals(2, session.state.value.audioIndex) // server preference beats device locale
        assertEquals(-1, session.state.value.subtitleIndex)
        session.ready()
        session.ready()
        runCurrent()
        assertEquals(1, gateway.starts.size)
        assertEquals(900_000_000L, gateway.starts.single().positionTicks)
        advanceTimeBy(9_999)
        runCurrent()
        assertTrue(gateway.progresses.isEmpty())
        output.snapshot = PlaybackPosition(100_000, false)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1_000_000_000L, gateway.progresses.single().positionTicks)
        output.snapshot = PlaybackPosition(100_250, true)
        session.progress()
        runCurrent()
        assertTrue(gateway.progresses.last().isPaused)
        assertEquals(1_002_500_000L, gateway.progresses.last().positionTicks)
        output.snapshot = PlaybackPosition(145_000, true)
        session.progress()
        runCurrent()
        assertEquals(1_450_000_000L, gateway.progresses.last().positionTicks)
        session.select(MediaStreamType.Subtitle, 3)
        runCurrent()
        assertEquals(3, gateway.progresses.last().subtitleStreamIndex)
        assertEquals(1, gateway.requests.size) // direct track switch remains local
        output.snapshot = PlaybackPosition(146_000, true)
        session.close()
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
        assertEquals(1_460_000_000L, gateway.stops.single().positionTicks)
        assertEquals(2, gateway.progresses.last().audioStreamIndex)
        assertEquals(3, gateway.progresses.last().subtitleStreamIndex)
        assertEquals(listOf("progress", "stop"), gateway.events.takeLast(2))
        assertTrue(gateway.encodes.isEmpty())
        assertTrue(output.released)
        val count = gateway.progresses.size
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(count, gateway.progresses.size)
    }

    @Test
    fun transcodeTrackChangeStopsOnlyOldEncodeAndPreservesPositionAndPause() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode)))
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode, "session-two")))
        }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(42_125, true)
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        assertEquals(42_125L, gateway.requests.last().startPosition.inWholeMilliseconds)
        assertEquals(1, gateway.requests.last().audioStreamIndex)
        assertEquals(-1, gateway.requests.last().subtitleStreamIndex)
        assertEquals(listOf("session-one"), gateway.encodes)
        assertEquals(listOf("start", "delete"), gateway.events)
        assertEquals(PlaybackPosition(42_125, true), output.snapshot)
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(listOf("session-one", "session-two"), gateway.encodes)
        assertEquals(1, gateway.starts.size)
        assertEquals(1, gateway.stops.size)
    }

    @Test
    fun directFailureRetriesOnceWithCopyDisabledAtRequestedResumePosition() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode, "fallback")))
        }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(120_000)
        runCurrent()
        output.snapshot = PlaybackPosition(0, false) // decoder never started
        session.failed(true)
        runCurrent()
        val retry = gateway.requests.last()
        assertFalse(retry.enableDirectPlay)
        assertFalse(retry.enableDirectStream)
        assertFalse(retry.allowVideoStreamCopy)
        assertEquals(120.seconds, retry.startPosition)
        assertEquals(2, output.opens)
        session.failed(true)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertNotNull(session.state.value.error)
        assertEquals(listOf("fallback"), gateway.encodes)
        session.close()
        runCurrent()
        assertEquals(1, gateway.stops.size)
    }

    @Test
    fun queuedOldPlayerCallbacksCannotStartOrFailTheReplacementDecision() = runTest {
        val old = decision()
        val replacement = decision(PlayMethod.Transcode, "replacement")
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(old))
            outcomes += ClientResult.Success(PlayOutcome.Play(replacement))
        }
        val output = Output().apply { locallySwitchable = false }
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        session.select(MediaStreamType.Subtitle, 3)
        runCurrent() // replacement PlaybackInfo is suspended
        session.ready(old)
        session.failed(true, old)
        session.progress(old)
        gate.complete(Unit)
        runCurrent()
        assertTrue(gateway.starts.isEmpty())
        assertTrue(gateway.encodes.isEmpty())
        assertEquals(2, gateway.requests.size)
        assertEquals(null, session.state.value.error)
        assertTrue(session.state.value.preparing)
        session.ready(replacement)
        runCurrent()
        assertEquals("replacement", gateway.starts.single().playSessionId)
        session.close()
        runCurrent()
    }

    @Test
    fun synchronousPlayerInitializationFailureGetsOneFallbackThenClearError() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Success(PlayOutcome.Play(decision()))
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode, "fallback")))
        }
        val output = Output().apply { openThrows = true }
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(15_000)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertNotNull(session.state.value.error)
        assertFalse(session.state.value.preparing)
        assertEquals(listOf("fallback"), gateway.encodes)
        session.close()
        runCurrent()
    }

    @Test
    fun negotiationFailureIsClearAndDoesNotReportAnInventedSession() = runTest {
        val gateway = Gateway().apply {
            outcomes += ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Server unreachable"))
        }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        assertEquals("Server unreachable", session.state.value.error)
        session.close()
        runCurrent()
        assertTrue(gateway.stops.isEmpty())
    }

    @Test
    fun exitWhilePlaybackInfoIsPendingCleansUpEncodeWithoutOpeningPlayer() = runTest {
        val gate = CompletableDeferred<Unit>()
        val gateway = Gateway().apply {
            this.gate = gate
            outcomes += ClientResult.Success(PlayOutcome.Play(decision(PlayMethod.Transcode)))
        }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.close()
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, output.opens)
        assertEquals(listOf("session-one"), gateway.encodes)
        assertEquals(1, gateway.stops.size)
    }

    @Test
    fun routeChangeRepreparesWithCurrentStreamChoices() = runTest {
        val gateway = Gateway().apply {
            repeat(2) { outcomes += ClientResult.Success(PlayOutcome.Play(decision())) }
        }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        output.snapshot = PlaybackPosition(73_000, false)
        session.routeChanged()
        runCurrent()
        assertEquals(73.seconds, gateway.requests.last().startPosition)
        assertEquals(2, gateway.requests.last().audioStreamIndex)
        session.close()
        runCurrent()
    }

    @Test fun everyCloseMarksProgressPendingBeforeNavigationAndReportCompletion() = runTest {
        for (failure in listOf(false, true)) {
            val gateway = Gateway().apply { outcomes += ClientResult.Success(PlayOutcome.Play(decision())) }
            val events = mutableListOf<String>()
            val session = PlaybackSession(
                gateway,
                Output(),
                backgroundScope,
                "en",
                onCompletionPending = { events += "pending" },
                onFinished = { events += "fresh" }
            )
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            if (failure) {
                session.failed(false)
                runCurrent()
            }
            gateway.stopGate = CompletableDeferred()
            finishPlayback(session) { events += "navigation" }
            assertEquals(listOf("pending", "navigation"), events, "Back/system/failure close must synchronously guard stale progress")
            gateway.stopGate!!.complete(Unit)
            runCurrent()
            assertEquals(listOf("pending", "navigation", "fresh"), events)
        }
    }

    @Test fun closeBeforeSetupFinishesAlwaysReleasesItsPendingGuard() = runTest {
        val events = mutableListOf<String>()
        val session = PlaybackSession(
            Gateway(),
            Output(),
            backgroundScope,
            "en",
            onCompletionPending = { events += "pending" },
            onFinished = { events += "fresh" }
        )
        session.close()
        assertEquals(listOf("pending"), events)
        runCurrent()
        assertEquals(listOf("pending", "fresh"), events)
    }

    @Test fun reentrantDisposalCannotArmOrFinishTheSameExitTwice() = runTest {
        var pending = 0
        var finished = 0
        lateinit var session: PlaybackSession
        session = PlaybackSession(
            Gateway(),
            Output(),
            backgroundScope,
            "en",
            onCompletionPending = {
                pending++
                session.close()
            },
            onFinished = { finished++ }
        )
        session.close()
        runCurrent()
        assertEquals(1, pending)
        assertEquals(1, finished)
    }
}
