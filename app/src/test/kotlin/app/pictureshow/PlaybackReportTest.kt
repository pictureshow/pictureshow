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
import kotlin.test.assertNull
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class PlaybackReportTest {
    private class Gateway : PlaybackGateway {
        val progresses = mutableListOf<PlaybackProgressInfo>()
        val stops = mutableListOf<PlaybackStopInfo>()
        val progressResults = ArrayDeque<ClientResult<Unit>>()
        val stopResults = ArrayDeque<ClientResult<Unit>>()

        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> = ClientResult.Success(
            PlayOutcome.Play(
                PlayDecision(
                    "item",
                    MediaSourceInfo(id = "source"),
                    PlayMethod.DirectPlay,
                    "https://example.test/video",
                    emptyMap(),
                    "session"
                )
            )
        )

        override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)

        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            progresses += info
            return progressResults.removeFirstOrNull() ?: ClientResult.Success(Unit)
        }

        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stops += info
            return stopResults.removeFirstOrNull() ?: ClientResult.Success(Unit)
        }

        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
    }

    private class Output : PlaybackOutput {
        override fun position() = PlaybackPosition(60_000, false)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() = Unit
        override fun release() = Unit
    }

    private fun unreachable() = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Could not reach the server"))

    @Test
    fun aFailedPeriodicProgressReportIsRetriedQuietlyAndNeverShown() = runTest {
        val gateway = Gateway().apply { progressResults += unreachable() }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.progress()
        runCurrent()
        assertNull(session.state.value.reportError)
        session.progress()
        runCurrent()
        assertEquals(2, gateway.progresses.size)
        assertNull(session.state.value.reportError)
        session.close()
        runCurrent()
        assertNull(session.state.value.reportError)
    }

    @Test
    fun theFinalStopReportRetriesOnceAndStaysQuietWhenTheRetryLands() = runTest {
        val gateway = Gateway().apply { stopResults += unreachable() }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(2, gateway.stops.size)
        assertNull(session.state.value.reportError)
    }

    @Test
    fun theFinalStopReportIsShownOnlyAfterItsRetryAlsoFails() = runTest {
        val gateway = Gateway().apply {
            stopResults += unreachable()
            stopResults += unreachable()
        }
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        assertEquals(2, gateway.stops.size)
        assertEquals("Could not reach the server", session.state.value.reportError)
    }
}
