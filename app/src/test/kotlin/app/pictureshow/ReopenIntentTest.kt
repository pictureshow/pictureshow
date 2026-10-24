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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ReopenIntentTest {
    @Test fun pauseDuringTrackNegotiationReachesTheReplacementDecoder() = intent(initiallyPaused = false)

    @Test fun playDuringTrackNegotiationReachesTheReplacementDecoder() = intent(initiallyPaused = true)

    private fun intent(initiallyPaused: Boolean) = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val reports = mutableListOf<String>()
        val gateway = object : PlaybackGateway {
            override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
                if (++calls > 1) gate.await()
                return ClientResult.Success(
                    PlayOutcome.Play(
                        PlayDecision(
                            "episode",
                            MediaSourceInfo(id = "source"),
                            PlayMethod.Transcode,
                            "https://example.test/Videos/episode/master.m3u8",
                            emptyMap(),
                            "encode-$calls"
                        )
                    )
                )
            }
            override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit).also { reports += "start" }
            override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
            override suspend fun stopped(info: PlaybackStopInfo) = ClientResult.Success(Unit).also { reports += "stop" }
            override suspend fun played() = ClientResult.Success(Unit)
            override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
        }
        val output = object : PlaybackOutput {
            var snapshot = PlaybackPosition(42_000, initiallyPaused)
            override fun position() = snapshot
            override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) {
                snapshot = PlaybackPosition(positionMs, paused)
            }
            override fun select(type: MediaStreamType, index: Int) = false
            override fun pause() {
                snapshot = snapshot.copy(paused = true)
            }
            override fun release() = Unit
        }
        val session = PlaybackSession(gateway, output, backgroundScope, "en")
        session.start(42_000)
        runCurrent()
        output.snapshot = output.snapshot.copy(paused = initiallyPaused)
        session.ready()
        runCurrent()
        session.select(MediaStreamType.Subtitle, -1)
        runCurrent()
        assertEquals(2, calls)
        output.snapshot = output.snapshot.copy(paused = !initiallyPaused)
        gate.complete(Unit)
        runCurrent()
        assertEquals(!initiallyPaused, output.snapshot.paused, "The last media-button intent must survive the network wait")
        session.ready()
        session.close()
        runCurrent()
        assertEquals(listOf("start", "stop"), reports)
    }
}
