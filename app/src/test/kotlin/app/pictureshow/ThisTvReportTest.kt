/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.playback.AudioCodec
import app.pictureshow.core.playback.AudioDecoder
import app.pictureshow.core.playback.AudioOutput
import app.pictureshow.core.playback.DeviceCapabilities
import app.pictureshow.core.playback.DeviceProfile
import app.pictureshow.core.playback.DeviceProfileBuilder
import app.pictureshow.core.playback.DisplayCapabilities
import app.pictureshow.core.playback.HdrFormat
import app.pictureshow.core.playback.PassthroughEncoding
import app.pictureshow.core.playback.PlaybackJson
import app.pictureshow.core.playback.VideoCodec
import app.pictureshow.core.playback.VideoDecoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ThisTvReportTest {
    private val capabilities = DeviceCapabilities(
        "Android TV",
        listOf(
            VideoDecoder(
                VideoCodec.HEVC, setOf("Main 10"), maxWidth = 3840, maxHeight = 2160,
                maxFrameRate = 60.0, maxFrameRateAtMaxSize = 30.0, maxBitDepth = 10,
                hdrFormats = setOf(HdrFormat.HDR10, HdrFormat.DOLBY_VISION), dolbyVisionProfiles = setOf(8)
            )
        ),
        DisplayCapabilities(3840, 2160, 60.0, setOf(HdrFormat.HDR10)),
        listOf(AudioDecoder(AudioCodec.DTS, 8, false)),
        AudioOutput(2, setOf(PassthroughEncoding.AC3)),
        PlaybackPreferences(bitrateMbps = 40).deviceSettings()
    )

    @Test fun reportShowsCapabilitiesAndTheExactProtocolProfile() {
        val report = thisTvReport(capabilities, "0.1.0", "11 (API 30)", "HDMI", listOf("dts"))
        assertEquals(DeviceProfileBuilder.build(capabilities), PlaybackJson.decodeFromString<DeviceProfile>(report.profileJson))
        listOf(
            "HEVC", "hardware", "3840", "2160", "30 fps", "HDR10", "DOLBY_VISION", "8",
            "HDMI", "AC3", "PCM", "2", "dts", "40 Mbps", "0.1.0", "API 30"
        ).forEach {
            assertTrue(report.summary.contains(it), "Missing $it in ${report.summary}")
        }
    }

    @Test fun fixtureAccountCannotReachSharedDiagnostics() = kotlinx.coroutines.test.runTest {
        val fixture = Account(
            Server("private-server-id", "Private Library", "https://private.example.test", "12"),
            User("private-user-id", "Private Person"),
            "fixture-access-token"
        )
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diagnostic-fixture", android.content.Context.MODE_PRIVATE)
        val accountStore = AccountStore(object : TextPersistence {
            override suspend fun read() = prefs.getString("accounts", null)
            override suspend fun write(value: String) {
                check(prefs.edit().putString("accounts", value).commit())
            }
        })
        accountStore.remember(fixture)
        prefs.edit().putString("device-id", "fixture-device-id").putString("library-title", "Private Movie").commit()
        assertEquals(fixture, accountStore.snapshots.value.currentAccount)
        val report = captureThisTv(context, PlaybackPreferences(bitrateMbps = 40))
        val shared = thisTvShareIntent(report)
        assertEquals("text/plain", shared.type)
        val text = shared.getStringExtra(android.content.Intent.EXTRA_TEXT)!!
        assertEquals(report.sharedText, text)
        listOf(
            fixture.server.id,
            fixture.server.name,
            fixture.server.url,
            fixture.user.id,
            fixture.user.name,
            fixture.accessToken,
            "fixture-device-id",
            "Private Movie"
        ).forEach { assertFalse(text.contains(it)) }
        assertTrue(text.contains("DirectPlayProfiles"))
    }
}
