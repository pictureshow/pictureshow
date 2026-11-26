/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentType
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackNetwork
import app.pictureshow.core.playback.PlaybackNetworkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackTuningRuntimeTest {
    @Test fun meteringWinsAndPublicOrUnknownEndpointDoesNotBecomeHomeOnWifi() {
        assertEquals(PlaybackNetwork.Metered, classifyPlaybackNetwork(true, true))
        assertEquals(PlaybackNetwork.Metered, classifyPlaybackNetwork(true, false))
        assertEquals(PlaybackNetwork.Home, classifyPlaybackNetwork(false, true))
        val ula = java.net.InetAddress.getByName("fd12:3456::1")
        assertTrue(PlaybackNetworkPolicy.isUniqueLocalAddress(ula.address))
        assertEquals(PlaybackNetwork.Home, classifyPlaybackNetwork(false, PlaybackNetworkPolicy.isUniqueLocalAddress(ula.address)))
        assertEquals(PlaybackNetwork.Remote, classifyPlaybackNetwork(false, false))
    }

    @Test fun automaticReductionNeedsTwoSubstantialSamplesAndCooldownAndNeverRaises() {
        val overCap = BandwidthQualityGuard()
        assertNull(overCap.reduction(20_000_000, 24_900_000, 262_144, 1))
        assertNull(overCap.reduction(20_000_000, 24_900_000, 262_144, 2))
        assertEquals("<1 kbps", qualityBitrateLabel(80))
        val modest = BandwidthQualityGuard()
        assertNull(modest.reduction(20_000_000, 19_000_000, 262_144, 1))
        assertEquals(19_000_000L, modest.reduction(20_000_000, 19_000_000, 262_144, 2))
        val guard = BandwidthQualityGuard()
        assertNull(guard.reduction(20_000_000, 4_000_000, 1_024, 0))
        assertNull(guard.reduction(20_000_000, 4_000_000, 262_144, 1))
        assertEquals(4_000_000L, guard.reduction(20_000_000, 4_000_000, 262_144, 2))
        assertNull(guard.reduction(4_000_000, 2_000_000, 262_144, 1_000))
        assertNull(guard.reduction(4_000_000, 2_000_000, 262_144, 2_000))
        assertNull(guard.reduction(4_000_000, 2_000_000, 262_144, 30_002))
        assertNull(guard.reduction(2_000_000, 40_000_000, 262_144, 70_000))
        assertNull(guard.reduction(2_000_000, 40_000_000, 262_144, 71_000))
    }

    @Test fun bandwidthGuardCountsOnlySamplesTakenWhileThePlayerIsShortOfData() {
        // slow(nowMs, bufferedPositionMs, positionMs, speed): a 4 Mbps reading against a 20 Mbps cap.
        fun BandwidthQualityGuard.slow(now: Long, buffered: Long, position: Long, speed: Float = 1f) =
            reduction(20_000_000, 4_000_000, 262_144, now, buffered, position, speed)
        val filling = BandwidthQualityGuard()
        for (step in 0L..6L) assertNull(filling.slow(step * 1_000, 3_000 + step * 1_500, step * 1_000))
        // Leg 194 emulator: slow single windows while 13 s sat buffered ahead (decoder-bound playback, bursty loading).
        val healthy = BandwidthQualityGuard()
        for (step in 0L..6L) {
            assertNull(healthy.slow(step * 1_000, 20_000 + step * 500, 7_000 + step * 700))
        }
        // Short of data on every sample (4 s ahead and shrinking): lowered once the shortage has lasted 10 s, not before.
        val falling = BandwidthQualityGuard()
        for (step in 0L..9L) assertNull(falling.slow(step * 1_000, 14_000 + step * 900, 10_000 + step * 1_000))
        assertEquals(4_000_000L, falling.slow(10_000, 23_000, 20_000))
        val doubleSpeed = BandwidthQualityGuard()
        for (step in 0L..9L) {
            assertNull(doubleSpeed.slow(step * 1_000, 14_500 + step * 1_900, 10_000 + step * 2_000, 2f))
        }
        assertEquals(4_000_000L, doubleSpeed.slow(10_000, 33_500, 30_000, 2f))
        // A short start-up stall that recovers restarts the shortage clock: 7 s behind, caught up, then 10 s more.
        val interrupted = BandwidthQualityGuard()
        for (step in 0L..6L) assertNull(interrupted.slow(step * 1_000, 14_000 + step * 900, 10_000 + step * 1_000))
        assertNull(interrupted.slow(7_000, 22_000, 17_000)) // caught up
        for (step in 8L..16L) {
            assertNull(interrupted.slow(step * 1_000, 22_000 + (step - 7) * 900, 10_000 + step * 1_000))
        }
        assertEquals(4_000_000L, interrupted.slow(17_000, 31_000, 27_000))
    }

    @Test fun keepScenesDoesNotOfferEarlyContinuationAtCreditsBeforeATailScene() {
        val outro = MediaSegment("credits", SegmentType.Outro, 80_000L * 10_000, 100_000L * 10_000)
        assertEquals(80_000L, playbackNextTriggerMs(150_000, listOf(outro), true))
        assertEquals(120_000L, playbackNextTriggerMs(150_000, listOf(outro), false))
        assertNull(playbackNextTriggerMs(0, listOf(outro), false))
    }

    @Test fun realRendererSinkDisablesOnlyChosenEncodedFormatWhileKeepingDecodedOutput() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.sendStickyBroadcast(
            Intent(AudioManager.ACTION_HDMI_AUDIO_PLUG)
                .putExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, 1)
                .putExtra(AudioManager.EXTRA_ENCODINGS, intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_DTS))
                .putExtra(AudioManager.EXTRA_MAX_CHANNEL_COUNT, 8)
        )
        val ac3 = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AC3).setSampleRate(48_000).setChannelCount(2).build()
        val sink = object : PictureshowRenderersFactory(context, disabledPassthrough = setOf(PassthroughEncoding.AC3)) {
            fun sink() = buildAudioSink(context, false, false)
        }.sink()
        try {
            assertFalse(sink.supportsFormat(ac3), "Disabling AC3 must remove the actual encoded renderer bypass")
            assertTrue(sink.supportsFormat(ac3.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS).build()))
            assertTrue(
                sink.supportsFormat(ac3.buildUpon().setSampleMimeType(MimeTypes.AUDIO_RAW).setPcmEncoding(C.ENCODING_PCM_16BIT).build())
            )
        } finally {
            sink.release()
        }
        val provider = FormatAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build(), setOf(C.ENCODING_AC3))
        try {
            assertFailsWith<AudioOutputProvider.ConfigurationException> {
                provider.getOutputConfig(AudioOutputProvider.FormatConfig.Builder(ac3).build())
            }
        } finally {
            provider.release()
        }
    }
}
