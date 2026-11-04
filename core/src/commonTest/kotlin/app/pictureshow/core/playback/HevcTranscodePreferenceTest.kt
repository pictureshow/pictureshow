/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class HevcTranscodePreferenceTest {
    private val base = CapabilityFixtures.basicStick
    private val hevc = base.videoDecoders.single { it.codec == VideoCodec.HEVC }

    private fun order(vararg decoders: VideoDecoder, display: DisplayCapabilities = base.display): String =
        DeviceProfileBuilder.build(base.copy(videoDecoders = listOf(base.videoDecoders.first()) + decoders, display = display))
            .transcodingProfiles.single().videoCodec

    @Test
    fun hardwareHevcAtDisplaySizeIsPreferredAndAvcRemainsFallback() {
        assertEquals("hevc,h264", order(hevc))
        assertEquals("hevc,h264", DeviceProfileBuilder.build(CapabilityFixtures.reportedProjector).transcodingProfiles.single().videoCodec)
    }

    @Test
    fun softwareUndersizedSlowAndUnknownProfileReportsKeepAvcFirst() {
        for (decoder in listOf(
            hevc.copy(hardware = false),
            hevc.copy(maxWidth = 1280, maxHeight = 720),
            hevc.copy(maxFrameRate = 15.0, maxFrameRateAtMaxSize = 15.0),
            hevc.copy(profiles = emptySet()),
            hevc.copy(maxLevel = null),
            hevc.copy(maxLevel = CodecLevel(3, 1)),
            hevc.copy(profiles = setOf("Main 10"))
        )) {
            assertEquals("h264,hevc", order(decoder), decoder.toString())
        }
        assertEquals("h264,hevc", order(hevc, display = DisplayCapabilities(3840, 2160, 60.0)))
    }

    @Test
    fun eligibilityUsesOnePhysicalMainDecoderRatherThanMergedSizeAndSpeed() {
        val bigSlow = hevc.copy(maxWidth = 3840, maxHeight = 2160, maxFrameRateAtMaxSize = 15.0)
        val smallFast = hevc
        val display = DisplayCapabilities(3840, 2160, 60.0)
        assertEquals("h264,hevc", order(bigSlow, smallFast, display = display))
        // The summary's dimensions and speed do not establish that the encoder's Main profile is supported.
        val main10 = bigSlow.copy(
            profiles = setOf("Main", "Main 10"),
            maxFrameRateAtMaxSize = 60.0,
            profileCapabilities = listOf(VideoProfileCapability("Main 10", CodecLevel(5, 1), 10))
        )
        assertEquals("h264,hevc", order(main10, display = display))
    }

    @Test
    fun mainLevelMustCoverDisplayWithoutBorrowingMainTenLevel() {
        val display = DisplayCapabilities(3840, 2160, 60.0)
        val decoder = hevc.copy(
            profiles = setOf("Main", "Main 10"),
            maxLevel = CodecLevel(5, 1),
            maxWidth = 3840,
            maxHeight = 2160,
            maxBitDepth = 10,
            profileCapabilities = listOf(
                VideoProfileCapability("Main", CodecLevel(4, 1), 8),
                VideoProfileCapability("Main 10", CodecLevel(5, 1), 10)
            )
        )
        assertEquals("h264,hevc", order(decoder, display = display))
        assertEquals("hevc,h264", order(decoder, display = base.display))
        assertEquals(
            "hevc,h264",
            order(
                decoder.copy(
                    maxFrameRateAtMaxSize = 30.0,
                    profileCapabilities = listOf(
                        VideoProfileCapability("Main", CodecLevel(5), 8),
                        VideoProfileCapability("Main 10", CodecLevel(5, 1), 10)
                    )
                ),
                display = display
            )
        )
        assertEquals(
            "h264,hevc",
            order(
                decoder.copy(
                    profileCapabilities = listOf(
                        VideoProfileCapability("Main", null, 8),
                        VideoProfileCapability("Main 10", CodecLevel(5, 1), 10)
                    )
                ),
                display = display
            )
        )
        assertEquals(
            "hevc,h264",
            order(
                hevc.copy(
                    maxLevel = CodecLevel(3, 1),
                    maxFrameRate = 30.0,
                    maxFrameRateAtMaxSize = 30.0
                ),
                display = DisplayCapabilities(1280, 720, 60.0)
            )
        )
    }

    @Test
    fun mainLevelCoversDisplayRateRatherThanDecoderPeak() {
        val display = DisplayCapabilities(1920, 1080, 60.0)
        for (peak in listOf(300.0, 600.0)) {
            val fast = hevc.copy(maxLevel = CodecLevel(5, 1), maxFrameRate = peak)
            assertEquals("hevc,h264", order(fast, display = display), "Decoder peak $peak must not penalize display-rate coverage")
            // Level 4 fits 1080p30's samples, but not 1080p60: retain the actual display-rate requirement.
            val lowerLevel = fast.copy(maxLevel = CodecLevel(4))
            assertEquals("h264,hevc", order(lowerLevel, display = display))
            assertEquals("hevc,h264", order(lowerLevel, display = display.copy(maxRefreshRate = 30.0)))
        }
    }

    @Test
    fun reportedHdrAndCompatibleDvBaseLayersRemainDirectWithInvalidAndDvOnlyGuarded() {
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.reportedProjector)
        val source = Combo("mkv", "hevc", "Main 10", 10, "HDR10", "aac", 2, emptyList(), 3840, 2160, 60.0, 153)
        for (range in listOf("SDR", "HDR10", "HLG", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus")) {
            assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, source.copy(rangeType = range)), range)
        }
        for (range in listOf("DOVI", "DOVIWithEL", "DOVIWithELHDR10Plus", "DOVIInvalid")) {
            assertEquals(
                setOf("VideoRangeTypeNotSupported"),
                ProfileEvaluator.directPlayFailures(profile, source.copy(rangeType = range)),
                range
            )
        }
    }
}
