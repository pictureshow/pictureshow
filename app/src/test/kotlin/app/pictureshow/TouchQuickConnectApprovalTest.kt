/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.pictureshow.core.Account
import app.pictureshow.core.QuickConnectApprovalResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** AUTH-15 on a phone: a pasted code works, a short one never reaches the server, each outcome reads plainly. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h850dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchQuickConnectApprovalTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val account = Account(Server("s", "Home", "https://jf.invalid", "10.10.0"), User("u", "Viewer"), "token")

    private fun show(answer: QuickConnectApprovalResult): MutableList<String> {
        val sent = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TouchQuickConnectApproval(account, "Home", { _, code ->
                    sent += code
                    answer
                }) {}
            }
        }
        return sent
    }

    @Test fun aPastedCodeIsApprovedAndNamesTheViewer() {
        val sent = show(QuickConnectApprovalResult.APPROVED)
        compose.onNodeWithTag("qc-approve:code").performTextInput(" ab c1 23\n")
        compose.onNodeWithTag("qc-approve:code").assertTextContains("ABC123")
        compose.onNodeWithTag("qc-approve:submit").performClick()
        compose.onNodeWithTag("qc-approve:result").assertTextContains("Approved. The TV signs in as Viewer", substring = true)
        compose.runOnIdle { assertEquals(listOf("ABC123"), sent) }
    }

    @Test fun aShortCodeIsExplainedWithoutAsking() {
        val sent = show(QuickConnectApprovalResult.APPROVED)
        compose.onNodeWithTag("qc-approve:code").performTextInput("abc")
        compose.onNodeWithTag("qc-approve:submit").performClick()
        compose.onNodeWithTag("qc-approve:result").assertTextContains("Codes are 6 letters and numbers", substring = true)
        compose.runOnIdle { assertEquals(emptyList<String>(), sent) }
    }

    @Test fun quickConnectTurnedOffSaysWhoCanTurnItOn() {
        show(QuickConnectApprovalResult.DISABLED)
        compose.onNodeWithTag("qc-approve:code").performTextInput("ABC123")
        compose.onNodeWithTag("qc-approve:submit").performClick()
        compose.onNodeWithTag("qc-approve:result").assertTextContains("Quick Connect is turned off on this server", substring = true)
    }

    @Test fun anEmptyFieldCannotSubmit() {
        show(QuickConnectApprovalResult.APPROVED)
        compose.onNodeWithTag("qc-approve:submit").assertIsNotEnabled()
    }
}
