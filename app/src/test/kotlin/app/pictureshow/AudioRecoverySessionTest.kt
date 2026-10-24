/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.ClientResult
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
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AudioRecoverySessionTest {
    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        val stops = mutableListOf<PlaybackStopInfo>()
        val encodes = mutableListOf<String>()
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "film",
                        MediaSourceInfo(
                            id = "source",
                            defaultAudioStreamIndex = 1,
                            mediaStreams = listOf(
                                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "dts"),
                                MediaStream(type = MediaStreamType.Audio, index = 2, codec = "ac3")
                            )
                        ),
                        if (request.enableDirectPlay) PlayMethod.DirectPlay else PlayMethod.Transcode,
                        "https://example.test/stream",
                        emptyMap(),
                        "session-${requests.size}"
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
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            encodes += sessionId
            return ClientResult.Success(Unit)
        }
    }
    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        var locallySwitchable = false
        val opened = mutableListOf<PlaybackPosition>()
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            snapshot = PlaybackPosition(positionMs, paused)
            opened += snapshot
        }
        override fun select(type: MediaStreamType, index: Int) = locallySwitchable
        override fun pause() {
            snapshot = snapshot.copy(paused = true)
        }
        override fun release() = Unit
    }

    @Test fun audioFailureKeepsVideoCopyPositionAndPauseAndLaterAc3ReturnsToDirect() = runTest {
        for (paused in listOf(false, true)) {
            val gateway = Gateway()
            val output = Output()
            val session = PlaybackSession(gateway, output, backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            output.snapshot = PlaybackPosition(128_379, paused)
            session.audioFailed()
            runCurrent()
            val retry = gateway.requests.last()
            assertTrue(retry.allowVideoStreamCopy, "Audio failure must preserve the video")
            assertEquals(128_379.milliseconds, retry.startPosition)
            assertEquals(PlaybackPosition(128_379, paused), output.opened.last())
            session.ready()
            runCurrent()
            session.select(MediaStreamType.Audio, 2)
            runCurrent()
            assertTrue(gateway.requests.last().enableDirectPlay, "Working AC3 must escape audio recovery")
            assertTrue(gateway.requests.last().allowVideoStreamCopy)
            assertEquals(1, gateway.starts.size, "Recovery is one viewing")
            assertTrue(gateway.stops.isEmpty())
            session.close()
            runCurrent()
        }
    }

    @Test fun localDecodeThenAudioOnlyServerRecoveryIsBoundedAndStaleErrorsCannotReopen() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(30_000)
        runCurrent()
        val first = session.state.value.decision!!
        session.audioFailed(first, retryLocally = true)
        runCurrent()
        assertTrue(gateway.requests.last().enableDirectPlay, "First retry uses local decoding")
        assertTrue(gateway.requests.last().allowAudioStreamCopy)
        assertTrue(gateway.requests.last().allowVideoStreamCopy)
        session.audioFailed(first, retryLocally = true)
        runCurrent()
        assertEquals(2, gateway.requests.size, "Retired player's error is stale")
        session.audioFailed()
        runCurrent()
        assertFalse(gateway.requests.last().allowAudioStreamCopy, "Second retry converts audio only")
        assertTrue(gateway.requests.last().requireVideoStreamCopy)
        assertFalse(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().allowVideoStreamCopy)
        session.audioFailed()
        runCurrent()
        assertNotNull(session.state.value.error)
        assertEquals(3, gateway.requests.size, "No full-video fallback or retry loop after audio recovery fails")
        session.close()
        runCurrent()
    }

    @Test fun changedRouteRestoresOriginalNegotiationAfterAudioOnlyRecovery() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(30_000)
        runCurrent()
        session.audioFailed()
        runCurrent()
        assertFalse(gateway.requests.last().allowAudioStreamCopy)
        session.routeChanged()
        runCurrent()
        assertTrue(gateway.requests.last().allowAudioStreamCopy)
        assertTrue(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().allowVideoStreamCopy)
        assertEquals(30_000.milliseconds, gateway.requests.last().startPosition)
        assertEquals(listOf("session-2"), gateway.encodes)
        session.close()
        runCurrent()
    }

    @Test fun directTrackSwitchToRefusedDtsPreservesChosenTrackPositionAndPause() = runTest {
        for (paused in listOf(false, true)) {
            val gateway = Gateway()
            val output = Output().apply { locallySwitchable = true }
            val session = PlaybackSession(gateway, output, backgroundScope, "en")
            session.start(0)
            runCurrent()
            session.ready()
            runCurrent()
            session.select(MediaStreamType.Audio, 2)
            runCurrent()
            output.snapshot = PlaybackPosition(128_379, paused)
            session.select(MediaStreamType.Audio, 1)
            runCurrent()
            assertEquals(1, gateway.requests.size, "The incident begins with an in-player switch")
            session.audioFailed(retryLocally = true)
            runCurrent()
            assertEquals(1, gateway.requests.last().audioStreamIndex)
            assertTrue(gateway.requests.last().enableDirectPlay)
            assertTrue(gateway.requests.last().allowVideoStreamCopy)
            assertEquals(PlaybackPosition(128_379, paused), output.opened.last())
            session.ready()
            runCurrent()
            session.select(MediaStreamType.Audio, 2)
            runCurrent()
            assertTrue(gateway.requests.last().enableDirectPlay)
            assertEquals(2, gateway.requests.last().audioStreamIndex)
            assertEquals(1, gateway.starts.size)
            session.close()
            runCurrent()
        }
    }

    @Test fun unsupportedLocalTrackUsesServerButNextWorkingTrackRestoresDirectPolicy() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        assertFalse(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().allowVideoStreamCopy)
        assertTrue(gateway.requests.last().requireVideoStreamCopy)
        session.select(MediaStreamType.Audio, 2)
        runCurrent()
        assertTrue(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().allowVideoStreamCopy)
        session.close()
        runCurrent()
    }
}
