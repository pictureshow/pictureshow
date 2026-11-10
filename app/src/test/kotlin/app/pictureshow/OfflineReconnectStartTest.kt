/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReconnectStartTest {
    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "item",
                        MediaSourceInfo(
                            id = "source",
                            defaultAudioStreamIndex = 1,
                            defaultSubtitleStreamIndex = 3,
                            mediaStreams = listOf(
                                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac"),
                                MediaStream(type = MediaStreamType.Audio, index = 6, codec = "aac"),
                                MediaStream(type = MediaStreamType.Subtitle, index = 3, codec = "srt")
                            )
                        ),
                        PlayMethod.DirectPlay,
                        "https://example.test/video",
                        emptyMap(),
                        "viewing"
                    )
                )
            )
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            starts += info
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit)
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
    }

    private class Output : PlaybackOutput {
        val opened = mutableListOf<Triple<Long, Int, Int>>()
        override fun position() = PlaybackPosition(123_000, false)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opened += Triple(positionMs, audioIndex, subtitleIndex)
        }
        override fun select(type: MediaStreamType, index: Int) = true
        override fun pause() = Unit
        override fun release() = Unit
    }

    @Test
    fun `a handoff opens the bound source at its position with the viewer's tracks, and reports them as chosen`() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(123_000, PlaybackStartTracks("source", 6, -1))
        runCurrent()
        val request = gateway.requests.single()
        assertEquals(123_000.milliseconds, request.startPosition)
        assertEquals("source", request.mediaSourceId)
        assertEquals(6, request.audioStreamIndex)
        assertEquals(-1, request.subtitleStreamIndex, "explicit Off isn't a request for the default")
        assertEquals(Triple(123_000L, 6, -1), output.opened.single())
        session.ready()
        runCurrent()
        assertEquals(6, gateway.starts.single().audioStreamIndex)
        assertEquals(-1, gateway.starts.single().subtitleStreamIndex)
        session.close()
    }

    @Test
    fun `a null index leaves that choice to the server and out of the report`() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0, PlaybackStartTracks(null, null, 3))
        runCurrent()
        assertNull(gateway.requests.single().audioStreamIndex)
        assertNull(gateway.requests.single().mediaSourceId)
        session.ready()
        runCurrent()
        assertNull(gateway.starts.single().audioStreamIndex)
        assertEquals(3, gateway.starts.single().subtitleStreamIndex)
        session.close()
    }

    @Test
    fun `an ordinary start is exactly what it was`() = runTest {
        val gateway = Gateway()
        val session = PlaybackSession(gateway, Output(), backgroundScope, "en")
        session.start(5_000)
        runCurrent()
        assertEquals(PlaybackRequest(startPosition = 5_000.milliseconds), gateway.requests.single())
        session.close()
    }
}
