/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertFalse

class SoftwareTranscodeEvidenceTest {
    @Test
    fun missingSoftwarePerformanceEvidenceDoesNotRaiseTheDeclaredRate() {
        assertNoFloor(null)
    }

    @Test
    fun performanceBelowThirtyCannotSupportAThirtyFpsTarget() {
        assertNoFloor(VideoPerformancePoint(1920, 1080, 29.99))
        assertNoFloor(VideoPerformancePoint(1920, 1080, 24.0))
        assertNoFloor(VideoPerformancePoint(1920, 1080, 0.0))
    }

    @Test
    fun lowerResolutionPerformanceDoesNotAuthorizeFullHdThirty() {
        assertNoFloor(VideoPerformancePoint(1280, 720, 60.0))
        assertNoFloor(VideoPerformancePoint(1919, 1080, 30.0))
        assertNoFloor(VideoPerformancePoint(1920, 1079, 30.0))
    }

    private fun assertNoFloor(point: VideoPerformancePoint?) {
        val decoder = VideoDecoder(
            codec = VideoCodec.H264,
            profiles = setOf("Baseline", "Main", "High"),
            maxLevel = CodecLevel(4),
            maxWidth = 1920,
            maxHeight = 1080,
            maxFrameRate = 14.36,
            hardware = false,
            sustainablePerformance = point
        )
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.basicStick.copy(videoDecoders = listOf(decoder)))
        assertFalse(
            profile.transcodingProfiles.flatMap { it.conditions }.any {
                it.property == ProfileConditionValue.VideoFramerate && it.condition == ProfileConditionType.GreaterThanEqual
            },
            "A declared14.36fps target needs a sufficient real-time envelope before requesting30fps: $point"
        )
    }
}
