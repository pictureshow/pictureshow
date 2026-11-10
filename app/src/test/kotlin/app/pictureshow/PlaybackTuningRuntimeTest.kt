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
        assertEquals(2_000_000L, guard.reduction(4_000_000, 2_000_000, 262_144, 30_002))
        assertNull(guard.reduction(2_000_000, 40_000_000, 262_144, 70_000))
        assertNull(guard.reduction(2_000_000, 40_000_000, 262_144, 71_000))
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
