/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.ObservedLiveWindow
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class LivePlayerPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun liveTvStatusSpeaksOnlyDeliberatePositionChanges() {
        val position = mutableLongStateOf(10_000)
        compose.setContent {
            PictureshowTheme {
                PlayerTimeline(
                    position.longValue,
                    10_000,
                    3_600_000,
                    paused = true,
                    live = true,
                    liveCanTimeShift = true,
                    liveWindow = ObservedLiveWindow(100_000, 30_000, 27_000, true)
                )
            }
        }
        assertEquals("−0:17", behindLiveClock(17_000))
        compose.onNodeWithText("−0:17").assertDoesNotExist()
        val initial = compose.onNodeWithTag("player:position").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals("Paused · 17 sec behind live", initial)
        compose.runOnIdle { position.longValue = 11_000 }
        assertEquals("−0:16", behindLiveClock(16_000))
        compose.onNodeWithText("−0:16").assertDoesNotExist()
        assertEquals(initial, compose.onNodeWithTag("player:position").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun liveMarkerUsesMeasuredDefaultPositionRatherThanGuideOrWindowEnd() {
        compose.setContent {
            PictureshowTheme {
                Box(Modifier.background(Color.Black)) {
                    PlayerTimeline(
                        10_000,
                        10_000,
                        3_600_000,
                        live = true,
                        liveCanTimeShift = true,
                        liveWindow = ObservedLiveWindow(100_000, 30_000, 27_000, true)
                    )
                }
            }
        }
        val pixels = compose.onNodeWithTag("player:live-track").captureToImage().toPixelMap()
        val marker = pixels[pixels.width - 1, pixels.height / 2]
        assertTrue(marker.red > .95f && marker.green > .95f && marker.blue > .95f, "Measured default is the track endpoint: $marker")
        val filled = pixels[(pixels.width * .35f).toInt(), pixels.height / 2]
        assertTrue(filled.red > .6f, "Fill uses 10/27 of the measured target, not 10/30 of the later duration tail: $filled")
        val beyond = pixels[(pixels.width * .96f).toInt(), pixels.height / 2]
        assertTrue(beyond.red < .9f, "Unplayed buffer before the measured live edge: $beyond")
    }

    @Test fun unavailableAndExpiredBuffersNeverNameAnAvailableLiveEdge() {
        val window = androidx.compose.runtime.mutableStateOf<ObservedLiveWindow?>(ObservedLiveWindow(0, 30_000, 27_000, true))
        val expired = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            PictureshowTheme {
                PlayerTimeline(
                    10_000,
                    10_000,
                    30_000,
                    live = true,
                    liveCanTimeShift = window.value?.canTimeShift == true,
                    liveExpired = expired.value,
                    liveWindow = window.value
                )
            }
        }
        compose.onNodeWithText("LIVE").assertIsDisplayed()
        compose.onNodeWithText("Time unavailable").assertIsDisplayed()
        compose.runOnIdle { window.value = ObservedLiveWindow(0, 30_000, -1, true) }
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Time unavailable").assertIsDisplayed()
        compose.runOnIdle { window.value = null }
        compose.onNodeWithText("LIVE").assertDoesNotExist()
        compose.onNodeWithText("Time unavailable").assertDoesNotExist()
        compose.onNodeWithText("Time shift unavailable").assertIsDisplayed()
        compose.runOnIdle { expired.value = true }
        compose.onNodeWithText("Buffer expired").assertIsDisplayed()
        compose.onNodeWithText("LIVE").assertDoesNotExist()
    }

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test
    fun focusedLivePositionPaintsAVisiblePerimeterBehindLiveAndAtTheEdge() {
        val position = mutableLongStateOf(10_000)
        // This component render test supplies the TV keyboard input environment explicitly.
        // It verifies native focus/paint, not Android's hardware input-mode switch integration.
        val keyboard = object : InputModeManager {
            override val inputMode = InputMode.Keyboard
            override fun requestInputMode(inputMode: InputMode): Boolean = inputMode == InputMode.Keyboard
        }
        assertTrue(keyboard.requestInputMode(InputMode.Keyboard))
        assertEquals(InputMode.Keyboard, keyboard.inputMode)
        compose.setContent {
            CompositionLocalProvider(LocalInputModeManager provides keyboard) {
                PictureshowTheme {
                    Column(Modifier.width(360.dp).background(Color.Black)) {
                        PlayerTimeline(
                            position.longValue,
                            position.longValue,
                            30_000,
                            paused = true,
                            live = true,
                            liveCanTimeShift = true,
                            liveWindow = ObservedLiveWindow(100_000, 30_000, 27_000, true)
                        )
                        androidx.tv.material3.Button(onClick = {}, modifier = Modifier.testTag("other")) {
                            androidx.tv.material3.Text("Another control")
                        }
                    }
                }
            }
        }
        val target = compose.onNodeWithTag("player:position")
        fun brightPerimeter(): Pair<Int, Int> {
            val pixels = target.captureToImage().toPixelMap()
            var bright = 0
            var sampled = 0
            for (x in 4 until pixels.width - 4) {
                for (y in listOf(0, pixels.height - 1)) {
                    val pixel = pixels[x, y]
                    sampled++
                    if (pixel.red > .85f && pixel.green > .85f && pixel.blue > .85f) bright++
                }
            }
            return bright to sampled
        }
        for (at in listOf(10_000L, 27_000L)) {
            compose.runOnIdle { position.longValue = at }
            compose.onNodeWithTag("other").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.waitForIdle()
            target.assertIsNotFocused()
            val (unfocused, samples) = brightPerimeter()
            assertTrue(samples > 20, "Measure actual target perimeter pixels")
            assertTrue(unfocused < samples / 10, "No focus perimeter away from focus: $unfocused/$samples")
            assertEquals(InputMode.Keyboard, keyboard.inputMode)
            target.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            compose.waitForIdle()
            target.assertIsFocused()
            val (focused, focusedSamples) = brightPerimeter()
            assertTrue(focused > focusedSamples * .8f, "Focused live position is visibly outlined at $at: $focused/$focusedSamples")
        }
    }

    @Test fun liveChipIsABadgeAtTheEdgeAndAnExplicitResumeWhenPaused() {
        val position = mutableLongStateOf(27_000)
        val paused = androidx.compose.runtime.mutableStateOf(false)
        var requests = 0
        compose.setContent {
            PictureshowTheme {
                PlayerLiveEdgeChip(ObservedLiveWindow(100_000, 30_000, 27_000, true), position.longValue, false, paused.value) {
                    requests++
                }
            }
        }
        compose.onNodeWithContentDescription("Live").assertIsDisplayed()
        val badge = compose.onNodeWithTag("player:go-live").fetchSemanticsNode().config
        assertTrue(!badge.contains(SemanticsActions.OnClick), "Playing edge is a status without a click action")
        assertTrue(!badge.contains(SemanticsActions.RequestFocus), "Playing edge is not a remote focus stop")
        compose.runOnIdle { paused.value = true }
        val chip = compose.onNodeWithTag("player:go-live").assertIsEnabled()
        chip.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        chip.performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, requests)
        }
        chip.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        compose.runOnIdle {
            assertEquals(2, requests)
            position.longValue = 10_000
        }
        compose.onNodeWithText("Go live").assertIsDisplayed()
        compose.onNodeWithTag("player:go-live").assertIsEnabled()
    }
}
