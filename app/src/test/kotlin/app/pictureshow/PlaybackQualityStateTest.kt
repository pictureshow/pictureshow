/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import android.app.Application
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.playback.PlaybackNetwork
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.cancel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackQualityStateTest {
    private fun createHost() = PlaybackHostState(
        MediaItem(id = "item", name = "Film"),
        74_000,
        Media3PlaybackOutput(RuntimeEnvironment.getApplication(), Server("s", "Server", "https://example.test", "12")),
        {},
        {}
    ).also {
        it.network = PlaybackNetwork.Remote
        it.stateSource =
            androidx.compose.runtime.mutableStateOf(
                PlaybackState(
                    preparing = false,
                    decision = app.pictureshow.core.playback.PlayDecision(
                        "item",
                        app.pictureshow.core.playback.MediaSourceInfo(bitrate = 8_000_000),
                        app.pictureshow.core.playback.PlayMethod.DirectPlay,
                        "https://example.test/film",
                        emptyMap(),
                        null
                    )
                )
            )
    }

    @Test fun autoRestoresNetworkCapAndNeedsTwoFreshSamplesInsteadOfReusingOldGuard() {
        val host = createHost()
        try {
            host.bandwidthSample(6_000_000, 262_144, 1)
            assertNull(host.estimatedBandwidth)
            host.bandwidthSample(6_000_000, 262_144, 2)
            assertEquals(4_000_000, host.effectiveSettings.maxStreamingBitrate)
            assertEquals("Lowered to 4 Mbps because the connection slowed. Choose Quality → Auto to try again.", host.adaptiveQualityNotice)
            host.setQualityOverride(null) // Auto while already automatic is a deliberate fresh attempt.
            assertNull(host.estimatedBandwidth)
            assertNull(host.adaptiveQualityNotice)
            assertEquals(20_000_000, host.effectiveSettings.maxStreamingBitrate)
            host.bandwidthSample(6_000_000, 262_144, 3)
            assertEquals(20_000_000, host.effectiveSettings.maxStreamingBitrate)
            host.bandwidthSample(6_000_000, 262_144, 4)
            assertEquals(4_000_000, host.effectiveSettings.maxStreamingBitrate)
        } finally {
            host.reportScope.cancel()
            host.output.release()
        }
    }

    @Test fun lowBitrateAndUnknownStreamsDoNotTriggerAnAdaptiveChange() {
        val host = createHost()
        try {
            for (rate in listOf(1_430_000, 4_000_000, null)) {
                host.stateSource = androidx.compose.runtime.mutableStateOf(
                    PlaybackState(
                        preparing = false,
                        decision = app.pictureshow.core.playback.PlayDecision(
                            "item",
                            app.pictureshow.core.playback.MediaSourceInfo(bitrate = rate),
                            app.pictureshow.core.playback.PlayMethod.DirectPlay,
                            "https://example.test/film",
                            emptyMap(),
                            null
                        )
                    )
                )
                host.bandwidthSample(427_500, 262_144, 1)
                host.bandwidthSample(427_500, 262_144, 2)
                assertNull(host.estimatedBandwidth)
                assertNull(host.adaptiveQualityNotice)
                assertEquals(20_000_000, host.effectiveSettings.maxStreamingBitrate)
                host.setQualityOverride(null)
            }
        } finally {
            host.reportScope.cancel()
            host.output.release()
        }
    }

    @Test fun transcodeUrlCeilingDoesNotCauseFalseReductionOnLowRateClock() {
        val host = createHost()
        try {
            host.network = PlaybackNetwork.Home
            host.stateSource = androidx.compose.runtime.mutableStateOf(
                PlaybackState(
                    preparing = false,
                    decision = app.pictureshow.core.playback.PlayDecision(
                        "item",
                        app.pictureshow.core.playback.MediaSourceInfo(bitrate = 1_010_000),
                        app.pictureshow.core.playback.PlayMethod.Transcode,
                        "https://example.test/film?VideoCodec=h264&VideoBitrate=199800000&AudioBitrate=128000",
                        emptyMap(),
                        null
                    )
                )
            )
            for (estimate in listOf(150_000_000L, 500_000L)) {
                host.resetAutomaticQuality()
                host.bandwidthSample(estimate, 262_144, 1)
                host.bandwidthSample(estimate, 262_144, 2)
                assertNull(host.estimatedBandwidth)
                assertNull(host.adaptiveQualityNotice)
                assertEquals(200_000_000, host.effectiveSettings.maxStreamingBitrate)
            }
        } finally {
            host.reportScope.cancel()
            host.output.release()
        }
    }

    @Test fun arbitraryChoiceIsExplicitAndViewingScopedAndRejectsUnsupportedValues() {
        val host = createHost()
        val anotherViewing = createHost()
        try {
            host.setQualityOverride(40)
            assertEquals(40, host.qualityOverrideMbps)
            assertEquals("40 Mbps", host.qualityLabel)
            assertEquals(40_000_000, host.effectiveSettings.maxStreamingBitrate)
            host.bandwidthSample(1_000_000, 262_144, 1)
            host.bandwidthSample(1_000_000, 262_144, 2)
            assertEquals(40_000_000, host.effectiveSettings.maxStreamingBitrate)
            assertNull(host.adaptiveQualityNotice)
            assertEquals("Auto", anotherViewing.qualityLabel)
            assertEquals(20_000_000, anotherViewing.effectiveSettings.maxStreamingBitrate)
            assertEquals(74_000L, host.position)
            host.setQualityOverride(80)
            assertEquals(80, host.qualityOverrideMbps)
            assertEquals(80_000_000, host.effectiveSettings.maxStreamingBitrate)
            assertFailsWith<IllegalArgumentException> { host.setQualityOverride(81) }
            assertEquals(80, host.qualityOverrideMbps)
        } finally {
            host.reportScope.cancel()
            anotherViewing.reportScope.cancel()
            host.output.release()
            anotherViewing.output.release()
        }
    }
}
