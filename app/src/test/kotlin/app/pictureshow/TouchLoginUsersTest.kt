/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phone review P-21 (AUTH-04): public users as chips, at most eight until asked, and a tapped one names who signs in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class TouchLoginUsersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("fixture-server", "Fixture server", "https://media.example.test", "12.0.0")

    @Test
    fun chipsPickAUserAndTypingAMatchingNameKeepsTheField() {
        var state by mutableStateOf(LoginState(loading = false, users = List(10) { User("u$it", "Viewer $it") }))
        compose.setContent {
            MaterialTheme {
                TouchLogin(state, server, false, { state = state.copy(username = it) }, { state = state.copy(password = it) }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("touch:user:Viewer 7").assertExists()
        compose.onNodeWithTag("touch:user:Viewer 8").assertDoesNotExist()
        compose.onNodeWithTag("touch:users-all").performClick()
        compose.onNodeWithTag("touch:user:Viewer 9").assertExists()
        // Typing a public user's name is still typing: the field stays.
        compose.onNodeWithTag("touch:username").performTextReplacement("Viewer 3")
        compose.onNodeWithTag("touch:username").assertExists()
        compose.onNodeWithTag("touch:signing-in-as").assertDoesNotExist()
        compose.onNodeWithTag("touch:user:Viewer 2").performScrollTo().performClick()
        compose.onNodeWithTag("touch:signing-in-as").assertExists()
        compose.onNodeWithTag("touch:username").assertDoesNotExist()
        compose.runOnIdle { assertEquals("Viewer 2", state.username) }
        compose.onNodeWithText("Not you?").performClick()
        compose.onNodeWithTag("touch:username").assertExists()
        compose.runOnIdle { assertEquals("", state.username) }
    }
}
