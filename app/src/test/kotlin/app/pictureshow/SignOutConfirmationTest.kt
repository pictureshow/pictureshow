/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** AUTH-10 on TV: Sign out asks first, starts on Cancel, and says the account stays saved. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SignOutConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun asksFirstStartsOnCancelAndOnlyConfirmSignsOut() {
        var cancelled = 0
        var confirmed = 0
        compose.setContent {
            MaterialTheme { SignOutConfirmation("Ana", onCancel = { cancelled++ }, onConfirm = { confirmed++ }) }
        }
        compose.onNodeWithText("Sign out of Ana?").assertIsDisplayed()
        compose.onNodeWithText("You'll need the password to sign in again. Your account stays saved on this TV.").assertIsDisplayed()
        compose.onNodeWithTag("sign-out:cancel").assertIsFocused()
        compose.onNodeWithTag("sign-out:cancel").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1 to 0, cancelled to confirmed) }
        compose.onNodeWithTag("sign-out:confirm-button").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { assertEquals(1 to 1, cancelled to confirmed) }
    }

    @Test fun aCardSaysWhyItNeedsSigningInAgain() {
        val account = app.pictureshow.core.Account(
            app.pictureshow.core.Server("s", "Home", "https://media.example.test/", "10.11"),
            app.pictureshow.core.User("u", "Ana"),
            ""
        )
        assertEquals("Signed out · Sign in again", accountCardStatus(account.copy(expired = true, signedOut = true)))
        assertEquals("Session ended · Sign in again", accountCardStatus(account.copy(expired = true)))
        assertEquals(null, accountCardStatus(account.copy(accessToken = "t")))
    }
}
