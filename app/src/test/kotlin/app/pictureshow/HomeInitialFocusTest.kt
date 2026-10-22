/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeInitialFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Sam"), "fake-token")
    private val loaded = CatalogHomeState(
        rows = listOf(HomeRow("libraries", "Libraries", listOf(MediaItem("library", "Invented Library", "CollectionFolder")))),
        loading = false
    )

    @Test
    fun automaticProfileFocusInAnEmptyStartupDoesNotConsumeTheFirstCardIntent() {
        var state by mutableStateOf(CatalogHomeState(loading = false))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { CatalogHome(state, account, {}, {}, {}, {}) } }
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        compose.runOnIdle { state = state.copy(loading = true) }
        compose.runOnIdle { state = loaded }
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
    }

    @Test
    fun deliberateProfileNavigationDuringLoadingIsPreservedWhenRowsArrive() {
        var state by mutableStateOf(CatalogHomeState(loading = true))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { CatalogHome(state, account, {}, {}, {}, {}) } }
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
        compose.runOnIdle { state = loaded }
        // Loading completion does not move the user's deliberate choice back to Switch user.
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
    }
}
