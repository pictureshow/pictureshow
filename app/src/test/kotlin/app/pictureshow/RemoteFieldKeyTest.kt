/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class RemoteFieldKeyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun arrowKeepsFieldFocusedThroughDownThenMovesExactlyOnceOnReleaseAfterEditing() {
        compose.setContent {
            PictureshowTheme {
                Column {
                    Button(onClick = {}) { Text("Remembered server") }
                    RemoteField("Server address", "", {}, initial = true)
                    Button(onClick = {}) { Text("Connect") }
                    Button(onClick = {}) { Text("Search again") }
                }
            }
        }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onRoot().performKeyInput { keyDown(Key.DirectionDown) }
        // Losing field focus on Down hides the IME before Up; TalkBack can then claim that Up.
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.onRoot().performKeyInput { keyUp(Key.DirectionDown) }
        compose.onNodeWithText("Connect").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Connect").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Remembered server").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
    }
}
