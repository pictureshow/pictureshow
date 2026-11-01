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
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ExternalSubtitleSessionTest {
    @Test
    fun transcodeTextAndOffKeepTheEncodeAndViewingWhileReportingTheChosenTrackAndPauseIntent() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.ready(gateway.decision)
        runCurrent()
        for (index in listOf(2, -1, 1)) {
            session.select(MediaStreamType.Subtitle, index)
            runCurrent()
            assertEquals(index, session.state.value.subtitleIndex)
            assertEquals(index, gateway.progress.last().subtitleStreamIndex)
            assertEquals(1_260_000_000L, gateway.progress.last().positionTicks)
            assertEquals(true, gateway.progress.last().isPaused)
        }
        assertEquals(listOf(2, -1, 1), output.choices)
        assertEquals(1, gateway.requests.size)
        assertEquals(1, output.opens)
        assertEquals(1, gateway.starts)
        assertEquals(0, gateway.deletes)
        assertEquals(0, gateway.stops)
        session.close()
        runCurrent()
    }

    @Test
    fun enteringAndLeavingBurnInStillRenegotiatesAndKeepsFullMediaPosition() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.select(MediaStreamType.Subtitle, 3)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertEquals(3, gateway.requests.last().subtitleStreamIndex)
        assertEquals(126_000.milliseconds, gateway.requests.last().startPosition)
        session.select(MediaStreamType.Subtitle, -1)
        runCurrent()
        assertEquals(3, gateway.requests.size)
        assertEquals(-1, gateway.requests.last().subtitleStreamIndex)
        assertEquals(3, output.opens)
        assertEquals(emptyList(), output.choices)
        session.close()
        runCurrent()
    }

    @Test
    fun unavailablePreparedTextGroupFallsBackToServerNegotiation() = runTest {
        val gateway = Gateway()
        val output = Output(available = false)
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(126_000)
        runCurrent()
        session.select(MediaStreamType.Subtitle, 2)
        runCurrent()
        assertEquals(listOf(2), output.choices)
        assertEquals(2, gateway.requests.size)
        assertEquals(2, output.opens)
        assertEquals(2, gateway.requests.last().subtitleStreamIndex)
        session.close()
        runCurrent()
    }

    private class Output(private val available: Boolean = true) : PlaybackOutput {
        var opens = 0
        val choices = mutableListOf<Int>()
        override fun position() = PlaybackPosition(126_000, true)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            assertEquals(126_000L, positionMs)
            opens++
        }
        override fun select(type: MediaStreamType, index: Int): Boolean {
            assertEquals(MediaStreamType.Subtitle, type)
            choices += index
            return available
        }
        override fun pause() = Unit
        override fun release() = Unit
    }

    private class Gateway : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val progress = mutableListOf<PlaybackProgressInfo>()
        var starts = 0
        var deletes = 0
        var stops = 0
        var decision = make(1)
        private fun make(index: Int) = PlayDecision(
            "item",
            MediaSourceInfo(
                defaultSubtitleStreamIndex = index,
                mediaStreams = listOf(1, 2, 3).map {
                    MediaStream(
                        type = MediaStreamType.Subtitle,
                        index = it,
                        codec = if (it == 3) "pgssub" else "subrip",
                        deliveryMethod = if (it == 3) SubtitleDeliveryMethod.Encode else SubtitleDeliveryMethod.External,
                        deliveryUrl = if (it == 3) null else "/sub$it.srt"
                    )
                }
            ),
            PlayMethod.Transcode,
            "https://example.test/master.m3u8",
            emptyMap(),
            "session"
        )
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            decision = make(request.subtitleStreamIndex ?: 1)
            return ClientResult.Success(PlayOutcome.Play(decision))
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            starts++
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            progress += info
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stops++
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            deletes++
            return ClientResult.Success(Unit)
        }
    }
}
