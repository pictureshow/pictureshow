/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PLAY-60: mpv's profile removes container, audio and subtitle limits but keeps every video guard of the device. */
class MpvProfileTest {
    private val media3 = DeviceProfileBuilder.build(CapabilityFixtures.projector)
    private val mpv = DeviceProfileBuilder.buildMpv(CapabilityFixtures.projector)

    private fun combo(
        container: String = "mkv",
        video: String = "hevc",
        profile: String = "Main",
        bitDepth: Int = 8,
        range: String = "SDR",
        audio: String = "aac",
        channels: Int = 2,
        subtitles: List<String> = emptyList(),
        width: Int? = 1920,
        height: Int? = 1080
    ) = Combo(container, video, profile, bitDepth, range, audio, channels, subtitles, width, height, 23.976)

    private fun both(combo: Combo) = ProfileEvaluator.directPlayFailures(media3, combo) to ProfileEvaluator.directPlayFailures(mpv, combo)

    @Test fun containersAudioAndSubtitlesTheServerWouldConvertPlayUnderMpv() {
        for (combo in listOf(
            combo(container = "wmv", video = "h264", profile = "High", audio = "wmapro"),
            combo(container = "avi", video = "h264", profile = "High", audio = "wmav2"),
            combo(container = "m2ts", video = "h264", profile = "High", audio = "pcm_bluray", subtitles = listOf("pgssub"))
        )) {
            val (platform, engine) = both(combo)
            assertTrue(platform.isNotEmpty(), "Media3 profile should convert $combo")
            assertEquals(emptySet(), engine, "mpv profile should direct play $combo")
            assertTrue(platform.all { it.lowercase() in PlaybackEngineRule.MPV_SOLVABLE }, "$platform should be mpv-solvable")
        }
    }

    @Test fun olderCodecsDecodeInSoftwareUpTo1080p() {
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(mpv, combo(container = "avi", video = "msmpeg4v3", profile = "")))
        assertEquals(emptySet(), ProfileEvaluator.directPlayFailures(mpv, combo(container = "wmv", video = "vc1", profile = "Advanced")))
        val uhd = combo(container = "mkv", video = "vc1", profile = "Advanced", width = 3840, height = 2160)
        assertTrue(ProfileEvaluator.directPlayFailures(mpv, uhd).isNotEmpty())
    }

    @Test fun deviceVideoGuardsStay() {
        // The projector has no AV1 decoder and no Dolby Vision display: mpv doesn't change that.
        val av1 = combo(video = "av1")
        assertTrue("VideoCodecNotSupported" in ProfileEvaluator.directPlayFailures(mpv, av1))
        val dolbyVision = combo(profile = "Main 10", bitDepth = 10, range = "DOVI")
        assertEquals(ProfileEvaluator.directPlayFailures(media3, dolbyVision), ProfileEvaluator.directPlayFailures(mpv, dolbyVision))
        assertEquals(media3.transcodingProfiles, mpv.transcodingProfiles)
        assertEquals(media3.maxStreamingBitrate, mpv.maxStreamingBitrate)
    }

    @Test fun alwaysBurnInStillBurnsIn() {
        val always = CapabilityFixtures.projector.let {
            it.copy(settings = it.settings.copy(tuning = it.settings.tuning.copy(subtitleBurnIn = SubtitleBurnIn.Always)))
        }
        assertTrue(DeviceProfileBuilder.buildMpv(always).subtitleProfiles.all { it.method == SubtitleDeliveryMethod.Encode })
    }
}
