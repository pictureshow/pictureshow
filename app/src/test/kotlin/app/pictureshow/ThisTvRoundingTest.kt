/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import app.pictureshow.core.playback.VideoPerformancePoint
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ThisTvRoundingTest {
    // Values as Android reports them: achievable-rate arithmetic, a float refresh rate widened to double, whole limits.
    private val capabilities = DeviceCapabilities(
        "Android TV",
        listOf(
            VideoDecoder(
                VideoCodec.HEVC,
                setOf("Main"),
                maxWidth = 3840,
                maxHeight = 2160,
                maxFrameRate = 119.11764705882354,
                maxFrameRateAtMaxSize = 59.94005994005994,
                sustainablePerformance = VideoPerformancePoint(1920, 1080, 23.976023976023978)
            ),
            VideoDecoder(VideoCodec.VC1, setOf("Advanced"), maxWidth = 1920, maxHeight = 1080, maxFrameRate = 960.0)
        ),
        DisplayCapabilities(3840, 2160, 60.000003814697266),
        listOf(AudioDecoder(AudioCodec.AAC, 2, false)),
        AudioOutput(2, setOf(PassthroughEncoding.AC3)),
        PlaybackPreferences().deviceSettings()
    )

    @Test fun summaryShowsRatesAsPeopleWriteThem() {
        val summary = thisTvReport(capabilities, "0.0.0", "11 (API 30)", "HDMI", emptyList()).summary
        listOf(
            "3840 × 2160 at 59.94 fps; up to 119.12 fps at 1080p or below",
            "Real-time evidence: 1920 × 1080 at 23.98 fps",
            "1920 × 1080 at 960 fps; up to 960 fps at 1080p or below",
            "Display maximum reported mode: 3840 × 2160 at 60 Hz"
        ).forEach { assertTrue(summary.contains(it), "Missing \"$it\" in $summary") }
        assertFalse(Regex("\\d\\.\\d{3,}").containsMatchIn(summary), "Unrounded rate in $summary")
        assertFalse(summary.contains("960.0") || summary.contains("60.0 Hz"), "Trailing .0 in $summary")
    }

    @Test fun exportedProfileKeepsTheRawValues() {
        val report = thisTvReport(capabilities, "0.0.0", "11 (API 30)", "HDMI", emptyList())
        assertEquals(Json(PlaybackJson) { prettyPrint = true }.encodeToString(DeviceProfileBuilder.build(capabilities)), report.profileJson)
        assertTrue(report.sharedText.endsWith(report.profileJson))
    }
}
