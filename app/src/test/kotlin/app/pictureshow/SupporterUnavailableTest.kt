/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.MaterialTheme
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** A TV without Play billing still explains supporting, says why it can't subscribe, and lands the remote somewhere. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SupporterUnavailableTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun text(id: Int) = compose.activity.getString(id)

    @Test
    fun billingUnavailableExplainsSupportingAndWhyWithAFocusedReason() {
        var backs = 0
        compose.setContent { MaterialTheme { SupporterScreen(SupporterState.Unavailable(BillingFailure.Unavailable), {}, { backs++ }) } }
        // What supporting is and what supporters get: the Offer screen's approved copy, word for word.
        listOf(R.string.supporter_title, R.string.supporter_description, R.string.supporter_no_guarantee).forEach {
            compose.onNodeWithText(text(it)).assertIsDisplayed()
        }
        compose.onNodeWithText(text(R.string.supporter_billing_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("supporter:unavailable").assertIsFocused()
        // Remote keys keep focus on the screen instead of landing nowhere.
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithTag("supporter:unavailable").assertIsFocused()
        compose.onNodeWithTag("supporter:purchase").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun everyUnavailableReasonKeepsTheExplanationAndItsOwnReason() {
        mapOf(
            BillingFailure.Network to R.string.supporter_network_unavailable,
            BillingFailure.ProductMissing to R.string.supporter_product_unavailable,
            BillingFailure.Other to R.string.supporter_error
        ).let { reasons ->
            val state = androidx.compose.runtime.mutableStateOf<SupporterState>(SupporterState.Loading)
            compose.setContent { MaterialTheme { SupporterScreen(state.value, {}, {}) } }
            reasons.forEach { (reason, message) ->
                compose.runOnIdle { state.value = SupporterState.Unavailable(reason) }
                compose.onNodeWithText(text(R.string.supporter_description)).assertIsDisplayed()
                compose.onNodeWithText(text(message)).assertIsDisplayed()
                compose.onNodeWithTag("supporter:unavailable").assertIsFocused()
            }
        }
    }

    @Test
    fun billingAvailableOfferIsUnchanged() {
        compose.setContent { MaterialTheme { SupporterScreen(SupporterState.Offer(SupporterProduct("$2.99", "P1M")), {}, {}) } }
        listOf(
            R.string.supporter_title,
            R.string.supporter_description,
            R.string.supporter_no_guarantee,
            R.string.supporter_renewal
        ).forEach { compose.onNodeWithText(text(it)).assertIsDisplayed() }
        compose.onNodeWithText("$2.99 / month").assertIsDisplayed()
        compose.onNodeWithTag("supporter:purchase").assertIsFocused()
        compose.onNodeWithTag("supporter:back").assertExists()
        compose.onNodeWithTag("supporter:unavailable").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.supporter_billing_unavailable)).assertDoesNotExist()
    }
}
