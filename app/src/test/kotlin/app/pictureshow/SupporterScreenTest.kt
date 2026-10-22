/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SupporterScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun remoteSettingsSupportPurchaseAndBothEntriesRestoreFocus() {
        val directory = Files.createTempDirectory("supporter-focus").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val billing = FakeSupporterBilling().apply { offer = BillingReply.Ok(SupporterProduct("¥487", "P1M")) }
        val store = SupporterStore(PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("settings.preferences_pb") })
        val controller = SupporterController(billing, store, scope)
        try {
            compose.setContent {
                MaterialTheme {
                    val nav = rememberNavController()
                    NavHost(nav, "settings") {
                        composable("settings") {
                            PlaybackSettingsScreen(
                                PlaybackSettingsState(),
                                "PCM",
                                {},
                                { _, _ -> },
                                {},
                                onSupport = { nav.navigate("supporter") },
                                onAbout = { nav.navigate("about") },
                                onBack = {}
                            )
                        }
                        supporterScreen(nav, controller)
                        aboutScreens(nav)
                    }
                }
            }
            compose.onNodeWithTag("settings:bitrate").assertIsFocused()
            compose.onRoot().performKeyInput {
                pressKey(Key.DirectionUp)
                pressKey(Key.DirectionUp)
            }
            compose.onNodeWithTag("settings:supporter").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitUntil(5_000) { controller.state.value is SupporterState.Offer }
            compose.onNodeWithText("¥487 / month").assertIsDisplayed()
            compose.onNodeWithTag("supporter:purchase").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.runOnIdle { assertEquals(1, billing.launches) }
            back()
            compose.onNodeWithTag("settings:supporter").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.onNodeWithTag("settings:about").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithTag("about:licenses").assertIsFocused()
            back()
            compose.onNodeWithTag("settings:about").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
            compose.onNodeWithTag("settings:supporter").assertIsFocused()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
            compose.onNodeWithTag("supporter:purchase").assertIsFocused()
            back()
            compose.onNodeWithTag("settings:supporter").assertIsFocused()
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private fun back() {
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
    }

    @Test fun pendingAndUnavailableHaveNoPurchaseActionAndRemoteBackWorks() {
        var backs = 0
        var state by androidx.compose.runtime.mutableStateOf<SupporterState>(SupporterState.Pending)
        compose.setContent { MaterialTheme { SupporterScreen(state, {}, { backs++ }) } }
        compose.onNodeWithText("Waiting for Google Play to confirm your payment.").assertIsDisplayed()
        compose.onNodeWithTag("supporter:purchase").assertDoesNotExist()
        compose.runOnIdle { state = SupporterState.Unavailable(BillingFailure.ProductMissing) }
        compose.onNodeWithText(compose.activity.getString(R.string.supporter_product_unavailable)).assertIsDisplayed()
        compose.onNodeWithTag("supporter:purchase").assertDoesNotExist()
        compose.onNodeWithTag("supporter:back").assertDoesNotExist()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun supporterCodeAndRequestAddressAreReadableAndManageHasStoreFallback() {
        compose.setContent { MaterialTheme { SupporterScreen(SupporterState.Active("GPA.1234-5678-9012-34567"), {}, {}) } }
        compose.onNodeWithText("GPA.1234-5678-9012-34567").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithText("Email info@legitimateapps.com with your request and supporter code.").assertIsDisplayed()
        compose.onNodeWithTag("supporter:purchase").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText(
            compose.activity.getString(R.string.supporter_manage_fallback)
        ).assertIsDisplayed()
    }
}
