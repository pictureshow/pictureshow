/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.playback.ConversionNotice
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** PLAY-27: a brief, polite, non-focusable notice, once per viewing, with a Settings switch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ConversionNoticeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun decision(vararg reasons: String) = PlayDecision(
        "film",
        MediaSourceInfo(),
        PlayMethod.Transcode,
        "https://server.example/videos/film/master.m3u8?VideoCodec=h264&AudioCodec=aac",
        emptyMap(),
        "session",
        reasons.toList()
    )

    @Test fun noticeAppearsOnceWithoutTakingFocusAndHidesAfterItsTime() {
        val codec = requireNotNull(ConversionNotice.of(decision("VideoCodecNotSupported", "AudioCodecNotSupported"), -1))
        val range = requireNotNull(ConversionNotice.of(decision("VideoRangeTypeNotSupported"), -1))
        var notice by mutableStateOf<ConversionNotice?>(null)
        var allowed by mutableStateOf(false)
        var pressed = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val focus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize()) {
                    Button(onClick = { pressed++ }, modifier = Modifier.focusRequester(focus).testTag("play")) { Text("Play") }
                    ConversionNoticeHost(notice, allowed, Modifier.align(Alignment.TopStart)) {}
                }
                LaunchedEffect(Unit) { focus.requestFocus() }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("play").assertIsFocused()

        // Not yet ready: a qualifying decision waits rather than flashing behind "Preparing playback".
        notice = codec
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        allowed = true
        compose.mainClock.advanceTimeBy(100)
        val node = compose.onNodeWithTag("player:conversion-notice").fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertNull(node.config.getOrNull(SemanticsProperties.Focused), "the notice must not be an input-focus target")
        compose.onNodeWithText(ConversionNotice.TITLE).assertExists()
        compose.onNodeWithText(codec.message).assertExists()
        compose.onNodeWithText(ConversionNotice.DETAILS).assertExists()
        // The remote still drives the focused control.
        compose.onNodeWithTag("play").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { assertEquals(1, pressed) }

        compose.mainClock.advanceTimeBy(CONVERSION_NOTICE_MS)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()

        // A renegotiation later in the same viewing does not repeat the notice.
        notice = null
        compose.mainClock.advanceTimeBy(100)
        notice = range
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("player:conversion-notice").assertDoesNotExist()
    }

    @Test fun touchingTheNoticeAsksForPlaybackInfo() {
        val notice = requireNotNull(ConversionNotice.of(decision("VideoCodecNotSupported"), -1))
        var details = 0
        compose.setContent { MaterialTheme { ConversionNoticeCard(notice) { details++ } } }
        compose.onNodeWithTag("player:conversion-notice").performClick()
        compose.runOnIdle { assertEquals(1, details) }
    }

    @Test fun settingsSwitchIsBesideThisTvAndTurnsTheNoticeOff() {
        var state by mutableStateOf(PlaybackSettingsState(loading = false))
        compose.setContent {
            MaterialTheme {
                PlaybackSettingsScreen(
                    state,
                    "PCM only",
                    onLocal = { state = state.copy(local = it(state.local)) },
                    onServer = { _, _ -> },
                    onRetry = {},
                    onBack = {}
                )
            }
        }
        assertTrue(state.local.conversionNotice, "default is on")
        assertTrue(PlaybackPreferences.decode().conversionNotice, "a missing stored value is on")
        assertEquals(false, PlaybackPreferences.decode(conversionNotice = false).conversionNotice)
        compose.onNodeWithText("Tell me when the server converts video: On").assertExists()
        val toggle = compose.onNodeWithTag("settings:conversion-notice").fetchSemanticsNode()
        assertEquals("On", toggle.config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithTag("settings:this-tv").requestFocusNow()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithTag("settings:conversion-notice").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(false, state.local.conversionNotice) }
        compose.onNodeWithText("Tell me when the server converts video: Off").assertExists()
    }

    private fun SemanticsNodeInteraction.requestFocusNow() {
        performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        assertIsFocused()
    }
}
