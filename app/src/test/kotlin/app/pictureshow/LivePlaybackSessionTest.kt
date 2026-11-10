/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
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
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class LivePlaybackSessionTest {
    @get:Rule val timeLimit = PerTestTimeLimit(180)
    private fun source(owned: Boolean = true) = PlayDecision(
        "channel",
        MediaSourceInfo(id = "source", liveStreamId = "live", isInfiniteStream = true, requiresClosing = true),
        PlayMethod.DirectStream,
        "https://example.test/live",
        emptyMap(),
        "session",
        ownedLiveStreamId = "live".takeIf { owned }
    )
    private class Output : PlaybackOutput {
        var opens = 0
        var paused = false
        override fun position() = PlaybackPosition(2_000, paused)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opens++
        }
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() {
            paused = true
        }
        override fun release() = Unit
        override fun canSeek() = false
    }
    private class Gateway(private val decision: PlayDecision) : PlaybackGateway {
        val events = mutableListOf<String>()
        var prepared: PlayDecision = decision
        var gate: CompletableDeferred<Unit>? = null
        var failedClose = false
        var lastProgress: PlaybackProgressInfo? = null
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            gate?.await()
            return ClientResult.Success(PlayOutcome.Play(prepared))
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            lastProgress = info
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            lastProgress = info
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            assertEquals("live", info.liveStreamId)
            events += "stopped"
            return ClientResult.Success(Unit)
        }
        override suspend fun closeLiveStream(liveStreamId: String): ClientResult<Unit> {
            assertEquals("live", liveStreamId)
            events += "close"
            return if (failedClose) ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline")) else ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
        override suspend fun played(): ClientResult<Unit> {
            events += "played"
            return ClientResult.Success(Unit)
        }
    }

    @Test fun stoppedPrecedesOwnedCloseAndUnknownWindowNeverReportsCanSeek() = runTest {
        val gateway = Gateway(source())
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        assertFalse(requireNotNull(gateway.lastProgress).canSeek)
        session.close()
        runCurrent()
        assertEquals(listOf("stopped", "close"), gateway.events)
    }

    @Test fun sharedLiveIdentityIsReportedButNeverExplicitlyClosed() = runTest {
        val gateway = Gateway(source(owned = false))
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(listOf("stopped"), gateway.events)
    }

    @Test fun backDuringPrepareClosesLateSourceWithoutOpeningDecoder() = runTest {
        val gateway = Gateway(source()).apply { gate = CompletableDeferred() }
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.close()
        gateway.gate?.complete(Unit)
        runCurrent()
        assertEquals(0, output.opens)
        assertEquals(listOf("stopped", "close"), gateway.events)
    }

    @Test fun liveEosIsARecoverableFailureAndCannotMarkAChannelWatched() = runTest {
        val gateway = Gateway(source())
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        assertFalse(session.ended())
        runCurrent()
        assertTrue(requireNotNull(session.state.value.error).contains("live stream ended"))
        assertFalse("played" in gateway.events)
        assertTrue(output.paused)
        session.close()
        runCurrent()
    }

    @Test fun eachOwnedAcquisitionClosesEvenWhenProviderReusesTheLiveId() = runTest {
        val gateway = Gateway(source())
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        gateway.prepared = source()
        session.routeChanged()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(listOf("close", "stopped", "close"), gateway.events)
    }

    @Test fun growingRecordingCompletesOnlyAfterActualDecoderEos() = runTest {
        val gateway = Gateway(source())
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en", allowLiveCompletion = true)
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        assertFalse("played" in gateway.events)
        assertTrue(session.ended())
        runCurrent()
        assertEquals(listOf("stopped", "close", "played"), gateway.events)
        session.close()
        runCurrent()
    }

    @Test fun cleanupFailureRemainsVisibleAfterStoppedWasAccepted() = runTest {
        val gateway = Gateway(source()).apply { failedClose = true }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.close()
        runCurrent()
        assertTrue(requireNotNull(session.state.value.reportError).contains("Could not close the live source"))
    }
}
