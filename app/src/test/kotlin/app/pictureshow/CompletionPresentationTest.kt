/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import app.pictureshow.core.Account
import app.pictureshow.core.ItemUserData
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
class CompletionPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun pendingDetailsGuardCachedActionsAndKeepRetryDuringRecovery() {
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "token")
        val item = MediaItem("film", "Fictional sample", "Movie", userData = ItemUserData(playbackPositionTicks = 300_000_000))
        val state = mutableStateOf(CatalogDetailState(account, item, loading = false))
        var retries = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    retries++
                    state.value = state.value.copy(loading = true, error = null, readAttempt = state.value.readAttempt + 1)
                })
            }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(playbackPending = true) }
        compose.onNodeWithText("Saving progress…").assertIsDisplayed()
        compose.onNodeWithText("Mark watched").assertDoesNotExist()
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.onNodeWithText("Resume from 0:30").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(error = "Could not load watch state.") }
        compose.onNodeWithText("Saving progress…").assertDoesNotExist()
        // Recovery retains last-published wording/slots, not authoritative watch state. Stale actions stay disabled.
        compose.onNodeWithText("Mark watched").assertIsNotEnabled()
        compose.onNodeWithText("Retry").assertIsFocused()
        compose.onNodeWithText("Retry").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.runOnIdle { kotlin.test.assertEquals(1, retries) }
        compose.onNodeWithText("Retrying…").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("Could not load watch state.").assertIsDisplayed()
        compose.onNodeWithText("Mark watched").assertIsNotEnabled()
        compose.onNodeWithText("Saving progress…").assertDoesNotExist()
        compose.runOnIdle {
            state.value =
                state.value.copy(item = item.copy(userData = ItemUserData(played = true)), loading = false, playbackPending = false)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("detail:play").performKeyInput { pressKey(Key.DirectionRight) }
        compose.onNodeWithTag("detail:played").assertIsFocused()
        compose.onNodeWithText("Mark unwatched").assertIsDisplayed()
    }
}
