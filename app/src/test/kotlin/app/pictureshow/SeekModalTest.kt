/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SeekModalTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun seekKeepsTheActivityWindowAndHidesTheUnderlyingPlayerFromNativeAccessibility() {
        val clock = mutableLongStateOf(30_000)
        val open = mutableStateOf(false)
        lateinit var activity: View
        lateinit var panel: View
        compose.setContent {
            activity = LocalView.current
            PictureshowTheme {
                PlayerSeekHost(open.value, player = {
                    Button({}, Modifier.testTag("background")) { Text("Player action") }
                }, seek = {
                    PlayerPanel("Seek", {}, inActivityWindow = true) {
                        panel = LocalView.current
                        SeekPanel(clock.longValue, 120_000, {}, {})
                    }
                })
            }
        }
        val background = compose.onNodeWithTag("background").fetchSemanticsNode().id
        compose.runOnIdle { open.value = true }
        compose.onNodeWithTag("player:panel").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.PaneTitle))
        compose.onNodeWithTag("seek:back")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Seek. Back 10 seconds")))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        val back = compose.onNodeWithTag("seek:back").fetchSemanticsNode().id
        compose.runOnIdle { clock.longValue = 78_000 }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle {
            assertSame(activity, panel, "Seek must not cause an activity-window return announcement")
            assertFalse(activity.accessibilityNodeProvider.createAccessibilityNodeInfo(background)?.isVisibleToUser == true)
            assertEquals(
                "1:18 of 2:00",
                checkNotNull(panel.accessibilityNodeProvider.createAccessibilityNodeInfo(back)).stateDescription.toString()
            )
        }
    }

    @Test fun remoteFocusCannotLeaveSeekAndDoneClosesItOnce() {
        val open = mutableStateOf(true)
        var closes = 0
        var backgroundFocused = false
        compose.setContent {
            PictureshowTheme {
                PlayerSeekHost(open.value, player = {
                    Button({}, Modifier.testTag("background").onFocusChanged { backgroundFocused = it.isFocused }) { Text("Player action") }
                }, seek = {
                    PlayerPanel("Seek", {
                        closes++
                        open.value = false
                    }, inActivityWindow = true) {
                        SeekPanel(0, 60_000, {}, {
                            close {
                                closes++
                                open.value = false
                            }
                        })
                    }
                })
            }
        }
        compose.onNodeWithTag("seek:back").assertIsFocused().performKeyInput {
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionLeft)
        }
        compose.runOnIdle { assertFalse(backgroundFocused) }
        compose.onNodeWithTag("seek:back").assertIsFocused()
        compose.onNodeWithText("Done").performSemanticsAction(SemanticsActions.OnClick) { check(it()) }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertFalse(open.value)
        }
    }

    @Test fun backDismissesSeekThroughTheActivityWithoutAnExitOrWindowHandoff() {
        val open = mutableStateOf(true)
        var closes = 0
        compose.setContent {
            PictureshowTheme {
                PlayerSeekHost(open.value, player = {}, seek = {
                    PlayerPanel("Seek", {
                        closes++
                        open.value = false
                    }, inActivityWindow = true) {
                        SeekPanel(0, 60_000, {}, {})
                    }
                })
            }
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(1, closes)
            assertFalse(open.value)
        }
    }
}
