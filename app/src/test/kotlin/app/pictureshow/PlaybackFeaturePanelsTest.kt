/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackFeaturePanelsTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun siblingModalBlocksOutsideTouchesAndKeepsItsOwnTouchAction() {
        var leaked = 0
        var selected = 0
        compose.setContent {
            PlayerSeekHost(
                true,
                player = { Box(Modifier.fillMaxSize().clickable { leaked++ }) },
                seek = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
                        Box(Modifier.size(100.dp).playerTouchClick { selected++ }.testTag("modal:action"))
                    }
                }
            )
        }
        compose.onRoot().performTouchInput { click(Offset(10f, 10f)) }
        compose.runOnIdle { assertEquals(0, leaked) }
        compose.onNodeWithTag("modal:action").performClick()
        compose.runOnIdle { assertEquals(1, selected) }
    }

    @Test fun speedPanelArrivesAtCurrentRateAndDpadAndTouchPickRates() {
        var picked = 0f
        compose.setContent {
            PictureshowTheme {
                PlayerPanelContent("Playback speed", false) {
                    SpeedChoices(PlaybackSpeedState(1.5f), true) { picked = it }
                }
            }
        }
        listOf(50, 75, 100, 125, 150, 175, 200).forEach {
            compose.onNodeWithTag("track:$it").assertIsDisplayed().assertHeightIsEqualTo(40.dp)
        }
        compose.onNodeWithTag("track:150").assertIsFocused().assertIsSelected()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionCenter)
        }
        assertEquals(1.25f, picked)
        compose.onNodeWithTag("track:50").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0.5f, picked) }
    }

    @Test fun infoPanelShowsNegotiatedMethodSelectedSubtitleAndReasonWithoutCredentials() {
        val source = MediaSourceInfo(
            container = "mkv",
            bitrate = 5_000_000,
            mediaStreams = listOf(
                MediaStream(type = MediaStreamType.Video, codec = "h264", width = 1920, height = 1080, bitRate = 4_500_000),
                MediaStream(type = MediaStreamType.Audio, index = 1, codec = "aac", bitRate = 128_000),
                MediaStream(type = MediaStreamType.Subtitle, index = 2, deliveryMethod = SubtitleDeliveryMethod.Encode)
            )
        )
        val decision = PlayDecision(
            "film",
            source,
            PlayMethod.Transcode,
            "https://example.test/master.m3u8?api_key=secret",
            mapOf(
                "Authorization" to "secret"
            ),
            "session",
            listOf("VideoBitrateNotSupported")
        )
        var closed = false
        compose.setContent {
            PictureshowTheme {
                PlayerPanelContent("Playback info", true, Modifier.width(600.dp).height(320.dp)) {
                    PlaybackInformationContent(decision, 1, 2, 4, null) { closed = true }
                }
            }
        }
        compose.onNodeWithText("Transcode").assertExists()
        compose.onNodeWithText("Subtitles: Burned in").assertExists()
        compose.onNodeWithText("Audio: AAC").assertExists()
        compose.onNodeWithText("Audio bitrate: 128 kbps").assertExists()
        compose.onNodeWithText("Quality is capped at 4 Mbps", substring = true).assertExists()
        compose.onNodeWithText("secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Close").assertIsFocused()
        compose.onNodeWithTag("playback:metadata-note").assertIsNotDisplayed()
        repeat(16) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag("playback:metadata-note").assertIsDisplayed()
        repeat(16) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }
        compose.onNodeWithText("Close").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(true, closed) }
    }
}
