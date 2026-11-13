/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import app.pictureshow.core.Server
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackSpeeds
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PlaybackSpeedTest {
    @Test fun speedAndPitchSurviveDirectToTranscodeReplacementWithoutChangingMediaPositionOrPause() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Fixture", "https://example.test", "12"))
        fun decision(method: PlayMethod) = PlayDecision(
            "film",
            MediaSourceInfo(runTimeTicks = 9_000_000_000),
            method,
            "https://example.test/Videos/film/stream.ts",
            emptyMap(),
            "session"
        )
        try {
            output.open(decision(PlayMethod.DirectPlay), 120_000, -1, -1, true)
            output.setSpeed(1.5f)
            assertEquals(1.5f, output.player.value!!.playbackParameters.speed)
            assertEquals(1f, output.player.value!!.playbackParameters.pitch)
            assertEquals(PlaybackPosition(120_000, true), output.position())
            output.open(decision(PlayMethod.Transcode), 240_000, -1, -1, true)
            assertEquals(1.5f, output.player.value!!.playbackParameters.speed)
            assertEquals(1f, output.player.value!!.playbackParameters.pitch)
            assertEquals(PlaybackPosition(240_000, true), output.position())
            val nextViewing = ViewingPlaybackSpeed()
            assertEquals(1f, nextViewing.state.value.speed)
        } finally {
            output.release()
        }
    }

    @Test fun encodedOutputReturnsToNormalAndExplainsLimitationThenPcmAllowsSpeedAgain() {
        val speed = ViewingPlaybackSpeed()
        speed.select(2f, null)
        speed.audioOutput(true, null)
        assertEquals(1f, speed.state.value.speed)
        assertEquals(PlaybackSpeeds.ENCODED_AUDIO, speed.state.value.message)
        speed.select(2f, null)
        assertEquals(1f, speed.state.value.speed)
        speed.audioOutput(false, null)
        speed.select(0.5f, null)
        assertEquals(0.5f, speed.state.value.speed)
        speed.select(Float.NaN, null)
        assertEquals(0.5f, speed.state.value.speed)
        speed.observed(androidx.media3.common.PlaybackParameters(1f, 1f))
        assertEquals(1f, speed.state.value.speed)
        assertNotNull(speed.state.value.message)
    }

    @Test fun syncRateRequiresObservedPcmAndIsFencedToTheLiveDecoder() {
        val output = Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Fixture", "https://example.test", "12"))
        val decision = PlayDecision(
            "film", MediaSourceInfo(runTimeTicks = 9_000_000_000), PlayMethod.DirectPlay,
            "https://example.test/Videos/film/stream.ts", emptyMap(), "session"
        )
        try {
            output.open(decision, 0, -1, -1, true)
            val host = PlaybackHostState(app.pictureshow.core.MediaItem("film", "Film"), 0, output, {}, {})
            host.playerSource = androidx.compose.runtime.mutableStateOf(output.player.value)
            val first = requireNotNull(output.syncNativeSnapshot()).generation
            assertEquals(false, host.syncRendererSnapshot().rateSupported, "Unknown sink cannot advertise exact rates")
            output.observeSyncAudioOutput(first, false)
            assertEquals(true, host.syncRendererSnapshot().rateSupported, "Confirmed PCM on our software sink supports temporary rates")
            host.applySyncTimeline(null, null, 0.97)
            assertEquals(0.97f, output.player.value!!.playbackParameters.speed)
            assertEquals(1f, output.player.value!!.playbackParameters.pitch)
            host.applySyncTimeline(null, null, 1.03)
            assertEquals(1.03f, output.player.value!!.playbackParameters.speed)
            output.observeSyncAudioOutput(first, true)
            assertEquals(false, host.syncRendererSnapshot().rateSupported, "Encoded output must return to seek fallback")
            output.open(decision, 0, -1, -1, true)
            host.playerSource = androidx.compose.runtime.mutableStateOf(output.player.value)
            val second = requireNotNull(output.syncNativeSnapshot()).generation
            assertEquals(false, host.syncRendererSnapshot().rateSupported)
            output.observeSyncAudioOutput(first, false)
            assertEquals(false, host.syncRendererSnapshot().rateSupported, "A replaced decoder cannot qualify the new sink")
            output.observeSyncAudioOutput(second, false)
            assertEquals(true, host.syncRendererSnapshot().rateSupported)
            output.release()
            output.observeSyncAudioOutput(second, false)
            assertEquals(false, output.supportsSyncRate(host.player), "Released output evidence stays unavailable")
        } finally {
            output.release()
        }
    }

}
