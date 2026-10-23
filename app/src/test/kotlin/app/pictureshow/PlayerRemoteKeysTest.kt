/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
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
class PlayerRemoteKeysTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())

    @Test
    fun centerOnTheSurfaceShowsControlsWithoutChangingPlayback() {
        val root = FocusRequester()
        var focused by mutableStateOf(false)
        var shows = 0
        var seeks = 0L
        var clicks = 0
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.focusRequester(root).onFocusChanged { focused = it.isFocused }
                        .playerRemoteKeys(true, focused, { seeks += it }, { shows++ }, {}, back = {})
                        .focusable()
                ) {
                    Button(onClick = { clicks++ }) { Text("Control") }
                }
            }
        }
        compose.runOnIdle { root.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle {
            assertEquals(1, shows)
            assertEquals(0L, seeks)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun upAndDownOnTheSurfaceShowTheControlsWithoutSeeking() {
        val root = FocusRequester()
        var focused by mutableStateOf(false)
        var shows = 0
        var seeks = 0L
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.focusRequester(root).onFocusChanged { focused = it.isFocused }
                        .playerRemoteKeys(true, focused, { seeks += it }, { shows++ }, {}, back = {})
                        .focusable()
                ) {
                    Button(onClick = {}) { Text("Control") }
                }
            }
        }
        compose.runOnIdle { root.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.runOnIdle { assertEquals(1, shows) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.runOnIdle {
            assertEquals(2, shows)
            assertEquals(0L, seeks)
        }
    }

    @Test
    fun directionKeysMeantForOpenControlsAreNotTurnedIntoSeeksAndRestartTheTimer() {
        var interactions = 0
        var shows = 0
        var seeks = 0L
        compose.setContent {
            MaterialTheme {
                Column(
                    Modifier.playerRemoteKeys(true, false, { seeks += it }, { shows++ }, { interactions++ }, back = {})
                        .focusable()
                ) {
                    Button(onClick = {}) { Text("Control") }
                }
            }
        }
        compose.onNodeWithText("Control").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Control").assertIsFocused()
        compose.onRoot().performKeyInput {
            pressKey(Key.DirectionDown)
            pressKey(Key.DirectionUp)
            pressKey(Key.DirectionLeft)
            pressKey(Key.DirectionCenter)
        }
        compose.runOnIdle {
            assertEquals(4, interactions)
            assertEquals(0, shows)
            assertEquals(0L, seeks)
        }
    }
}
