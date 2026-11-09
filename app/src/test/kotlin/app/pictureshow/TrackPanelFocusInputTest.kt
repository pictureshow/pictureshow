/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TrackPanelFocusInputTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val entry = FocusRequester()
    private lateinit var focus: FocusManager
    private var choices by mutableStateOf(listOf(-1 to "Off") + (0..43).map { it to "Subtitle $it" })

    private fun open(selected: Int = 3, windowFocused: Boolean = false) {
        compose.setContent {
            focus = LocalFocusManager.current
            val parent = LocalWindowInfo.current
            val window = object : WindowInfo by parent {
                override val isWindowFocused: Boolean get() = windowFocused
            }
            CompositionLocalProvider(LocalWindowInfo provides window) {
                PictureshowTheme {
                    Column(Modifier.focusRequester(entry).focusGroup()) { TrackChoices(choices, selected, {}) }
                }
            }
        }
        compose.onNodeWithTag("track:$selected").assertIsFocused()
    }

    private fun reenter() {
        compose.runOnIdle {
            focus.clearFocus(force = true)
            assertTrue(entry.requestFocus())
        }
    }

    @Test fun firstArrowFromTheLastOfFortyFourStreamsFindsThePreviousRow() {
        open(selected = 43)
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("track:42").assertIsFocused()
    }

    @Test fun settledWindowStillKeepsArrivalSelectionUntilTheFirstRealArrow() {
        choices = listOf(-1 to "Off", 1 to "English", 2 to "Commentary")
        open(selected = 2, windowFocused = true)
        compose.mainClock.advanceTimeBy(100)
        // A later native owner request is not a user choice of the first geometric candidate.
        compose.runOnIdle {
            assertTrue(entry.requestFocus())
            assertTrue(entry.requestFocus())
        }
        compose.onNodeWithTag("track:2").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("track:1").assertIsFocused()
    }

    @Test fun explicitAccessibilityFocusCanLeaveArrivalRowAndFirstArrowAfterReentryMovesOnce() {
        open()
        // An accessibility focus action need not deliver a physical key or pointer event first.
        compose.onNodeWithTag("track:4").performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
        compose.onNodeWithTag("track:4").assertIsFocused()
        reenter()
        compose.onNodeWithTag("track:4").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("track:5").assertIsFocused()
    }

    @Test fun removedFocusedRowReturnsToAComposedChoiceAndNavigationStillWorks() {
        open()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("track:4").assertIsFocused()
        compose.runOnIdle { choices = choices.filterNot { it.first == 4 } }
        reenter()
        // The framework may already choose a neighbor when the focused node is removed. Preserve
        // a real composed choice, rather than requesting the detached row or prescribing that fallback.
        val focusedTags = compose.onAllNodes(isFocused()).fetchSemanticsNodes().mapNotNull {
            it.config.getOrNull(SemanticsProperties.TestTag)?.takeIf { tag -> tag.startsWith("track:") }
        }
        assertEquals(1, focusedTags.size)
        val focusedIndex = focusedTags.single().removePrefix("track:").toInt()
        assertTrue(choices.any { it.first == focusedIndex })
        val offset = choices.indexOfFirst { it.first == focusedIndex }
        val nextIndex = choices[(offset + 1).coerceAtMost(choices.lastIndex)].first
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("track:$nextIndex").assertIsFocused()
    }
}
