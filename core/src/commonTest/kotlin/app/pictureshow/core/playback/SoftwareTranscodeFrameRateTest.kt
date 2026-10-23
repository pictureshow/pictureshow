/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoftwareTranscodeFrameRateTest {
    private val measuredSoftware = VideoDecoder(
        codec = VideoCodec.H264,
        profiles = setOf("Baseline", "Main", "High"),
        maxLevel = CodecLevel(4),
        maxWidth = 1920,
        maxHeight = 1080,
        maxFrameRate = 14.36,
        hardware = false,
        sustainablePerformance = VideoPerformancePoint(1920, 1080, 30.0)
    )

    @Test
    fun measuredThirtyFpsSoftwareTargetAvoidsDeclaredFourteenFpsTranscodesWithoutRelaxingDirectPlay() {
        val caps = CapabilityFixtures.basicStick.copy(videoDecoders = listOf(measuredSoftware))
        val profile = DeviceProfileBuilder.build(caps)
        val transcode = profile.transcodingProfiles.single()
        assertEquals("h264", transcode.videoCodec.split(',').first())
        assertTrue(
            transcode.conditions.any {
                it.property == ProfileConditionValue.VideoFramerate &&
                    it.condition == ProfileConditionType.GreaterThanEqual &&
                    it.value?.toDouble() == 30.0
            }
        )
        val directRateCaps = profile.codecProfiles.filter {
            it.type == CodecType.Video && it.codec == "h264" && it.applyConditions.isEmpty()
        }.flatMap { it.conditions }.filter { it.property == ProfileConditionValue.VideoFramerate }
        assertEquals(listOf(14.36), directRateCaps.map { it.value!!.toDouble() })
        assertTrue(directRateCaps.all { it.condition == ProfileConditionType.LessThanEqual })
        val direct = Combo("mkv", "h264", "High", 8, "SDR", "aac", 2, emptyList(), 1920, 1080, 14.0, 40)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, direct))
        assertEquals(setOf("VideoFramerateNotSupported"), ProfileEvaluator.directPlayFailures(profile, direct.copy(frameRate = 30.0)))
    }

    @Test
    fun hardwareFourKThirtyCapAndFullHdHighRateRemainIndependentOfSoftwareTargetPolicy() {
        val profile = DeviceProfileBuilder.build(CapabilityFixtures.projector)
        assertFalse(
            profile.transcodingProfiles.flatMap { it.conditions }.any {
                it.property == ProfileConditionValue.VideoFramerate && it.condition == ProfileConditionType.GreaterThanEqual
            }
        )
        val direct = Combo("mkv", "h264", "High", 8, "SDR", "aac", 2, emptyList(), 3840, 2160, 30.0, 51)
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(profile, direct))
        assertEquals(setOf("VideoFramerateNotSupported"), ProfileEvaluator.directPlayFailures(profile, direct.copy(frameRate = 60.0)))
        assertEquals(
            emptySet(),
            ProfileEvaluator.directPlayFailures(profile, direct.copy(width = 1920, height = 1080, frameRate = 60.0))
        )
    }

    @Test
    fun unusedSoftwareDecoderDoesNotRaiseSelectedHardwareTranscodeRate() {
        val caps = CapabilityFixtures.basicStick.copy(
            videoDecoders = listOf(measuredSoftware) + CapabilityFixtures.basicStick.videoDecoders
        )
        val profile = DeviceProfileBuilder.build(caps)
        assertFalse(
            profile.transcodingProfiles.flatMap { it.conditions }.any {
                it.property == ProfileConditionValue.VideoFramerate && it.condition == ProfileConditionType.GreaterThanEqual
            }
        )
    }
}
