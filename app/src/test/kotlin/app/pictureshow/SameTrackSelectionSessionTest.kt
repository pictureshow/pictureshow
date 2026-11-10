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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class SameTrackSelectionSessionTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    @Test
    fun readyBurnInAudioOffAndDirectPicksPreservePlayerEncodePauseAndReportViewerIntent() = runTest {
        for ((type, index, transcode) in listOf(
            Triple(MediaStreamType.Subtitle, 3, true),
            Triple(MediaStreamType.Audio, 1, true),
            Triple(MediaStreamType.Subtitle, -1, true),
            Triple(MediaStreamType.Audio, 1, false)
        )) {
            for (paused in listOf(false, true)) {
                val gateway = Gateway(transcode, index.takeIf { type == MediaStreamType.Subtitle } ?: 3)
                val output = Output()
                val lines = mutableListOf<String>()
                val session = PlaybackSession(gateway, output, backgroundScope, "en", diagnostic = lines::add)
                session.start(126_000)
                runCurrent()
                session.ready()
                runCurrent()
                val initial = session.state.value.decision
                output.snapshot = PlaybackPosition(128_379, paused)
                session.select(type, index)
                runCurrent()
                assertEquals(1, gateway.requests.size)
                assertSame(initial, session.state.value.decision)
                assertEquals(1, output.opens)
                assertEquals(0, output.selects)
                assertEquals(0, output.seeks)
                assertEquals(listOf(type to index), output.reselections)
                assertEquals(0, gateway.deletes)
                assertEquals(0, gateway.stops)
                assertEquals(1, gateway.starts.size)
                val report = gateway.progress.last()
                assertEquals(1_283_790_000L, report.positionTicks)
                assertEquals(paused, report.isPaused)
                assertEquals(index, if (type == MediaStreamType.Audio) report.audioStreamIndex else report.subtitleStreamIndex)
                assertNull(if (type == MediaStreamType.Audio) report.subtitleStreamIndex else report.audioStreamIndex)
                assertEquals(
                    PlaybackDiagnostics.line(
                        initial,
                        "track-reselect",
                        "type=${if (type == MediaStreamType.Audio) "audio" else "subtitle"} " +
                            "stream=${if (index < 0) "off" else index} ready=true positionMs=128379"
                    ),
                    lines.last { it.startsWith("track-reselect ") }
                )
                session.close()
                runCurrent()
            }
        }
    }

    @Test
    fun picksDuringPreparationReportOnExistingReadyWithoutReopening() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.select(MediaStreamType.Audio, 1)
        session.select(MediaStreamType.Subtitle, 3)
        runCurrent()
        assertEquals(1, gateway.requests.size)
        assertEquals(1, output.opens)
        assertTrue(gateway.starts.isEmpty())
        assertTrue(gateway.progress.isEmpty())
        session.ready()
        runCurrent()
        assertEquals(1, gateway.starts.single().audioStreamIndex)
        assertEquals(3, gateway.starts.single().subtitleStreamIndex)
        session.close()
        runCurrent()
    }

    @Test
    fun queuedPicksCompareWithCompletedPreparationRatherThanOldPanelIndex() = runTest {
        val gateway = Gateway()
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        gateway.gate = CompletableDeferred()
        session.select(MediaStreamType.Subtitle, 4)
        runCurrent()
        session.select(MediaStreamType.Subtitle, 4)
        // This was the checked row before the pending change to 4; it must really switch back.
        session.select(MediaStreamType.Subtitle, 3)
        session.select(MediaStreamType.Subtitle, 3)
        gateway.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(null, 4, 3), gateway.requests.map { it.subtitleStreamIndex })
        assertEquals(3, output.opens)
        assertEquals(listOf(MediaStreamType.Subtitle to 4, MediaStreamType.Subtitle to 3), output.reselections)
        session.ready()
        runCurrent()
        assertEquals(3, gateway.progress.last().subtitleStreamIndex)
        session.close()
        runCurrent()
    }

    @Test
    fun reselectingRecoveryAudioKeepsRecoveryAndItsExitPolicyBeforeAndAfterReady() = runTest {
        val gateway = Gateway(transcode = false)
        val output = Output()
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        session.audioFailed()
        runCurrent()
        assertFalse(gateway.requests.last().allowAudioStreamCopy)
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        assertTrue(gateway.progress.isEmpty())
        session.ready()
        runCurrent()
        assertEquals(1, gateway.progress.last().audioStreamIndex)
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        assertEquals(2, gateway.requests.size)
        session.select(MediaStreamType.Audio, 2)
        runCurrent()
        assertEquals(3, gateway.requests.size)
        assertTrue(gateway.requests.last().enableDirectPlay)
        assertTrue(gateway.requests.last().allowAudioStreamCopy)
        session.close()
        runCurrent()
    }

    private class Gateway(private val transcode: Boolean = true, private val subtitle: Int = 3) : PlaybackGateway {
        val requests = mutableListOf<PlaybackRequest>()
        val starts = mutableListOf<PlaybackProgressInfo>()
        val progress = mutableListOf<PlaybackProgressInfo>()
        var deletes = 0
        var stops = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            requests += request
            gate?.await()
            return ClientResult.Success(
                PlayOutcome.Play(
                    PlayDecision(
                        "item",
                        MediaSourceInfo(
                            id = "source",
                            defaultAudioStreamIndex = 1,
                            defaultSubtitleStreamIndex = subtitle,
                            mediaStreams = listOf(
                                MediaStream(type = MediaStreamType.Video, index = 0, codec = "h264"),
                                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "dts"),
                                MediaStream(type = MediaStreamType.Audio, index = 2, codec = "ac3"),
                                MediaStream(
                                    type = MediaStreamType.Subtitle,
                                    index = 3,
                                    codec = "pgs",
                                    deliveryMethod = SubtitleDeliveryMethod.Encode
                                ),
                                MediaStream(
                                    type = MediaStreamType.Subtitle,
                                    index = 4,
                                    codec = "pgs",
                                    deliveryMethod = SubtitleDeliveryMethod.Encode
                                )
                            )
                        ),
                        if (transcode || !request.enableDirectPlay) PlayMethod.Transcode else PlayMethod.DirectPlay,
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
    private class Output : PlaybackOutput {
        var snapshot = PlaybackPosition(0, false)
        var opens = 0
        var selects = 0
        var seeks = 0
        val reselections = mutableListOf<Pair<MediaStreamType, Int>>()
        override fun position() = snapshot
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
            opens++
            snapshot = PlaybackPosition(positionMs, paused)
        }
        override fun select(type: MediaStreamType, index: Int): Boolean {
            selects++
            return false
        }
        override fun reselect(type: MediaStreamType, index: Int) {
            reselections += type to index
        }
        override fun beginSeek() {
            seeks++
        }
        override fun pause() = Unit
        override fun release() = Unit
    }
}
