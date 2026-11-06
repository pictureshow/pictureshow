/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pictureshow.core.Account
import app.pictureshow.core.AccountSnapshot
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog

/** Drive touch semantics, modal cancellation and saved state rather than checking source strings. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchFormsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("fixture-server", "Fixture server", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("fixture-viewer", "Viewer"), "fixture-token")
    private val snapshot = AccountSnapshot(listOf(account), account.key, lastServer = server)

    @Test fun serverEntrySubmitsImeAndReportsDiscoveryAndConnectionErrors() {
        var state by mutableStateOf(ServerState(scanning = true))
        var connected = ""
        compose.setContent {
            MaterialTheme {
                TouchServers(state, AccountSnapshot(), { state = state.copy(address = it) }, { connected = it }, {}, {}, {})
            }
        }
        compose.onNodeWithText("Looking for servers…").assertExists()
        compose.onNodeWithTag("touch:server-address").performTextInput("https://media.example.test")
        compose.onNodeWithTag("touch:server-address").performImeAction()
        compose.runOnIdle { assertEquals("https://media.example.test", connected) }
        compose.runOnIdle { state = state.copy(scanning = false, connecting = true, error = "Could not connect.") }
        compose.onNodeWithText("Connecting…").assertIsNotEnabled()
        compose.onNodeWithText("Could not connect.").assertExists()
        compose.onNodeWithText("No servers found. Enter an address above, or search again.").assertExists()
    }

    @Test fun loginRetainsInputDuringRestorationAndDoneSubmitsPassword() {
        var state by mutableStateOf(LoginState(loading = false, quickConnect = true))
        var submitted = ""
        var quick = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                TouchLogin(
                    state,
                    server,
                    false,
                    { state = state.copy(username = it) },
                    { state = state.copy(password = it) },
                    { submitted = "${state.username}:${state.password}" },
                    { quick++ },
                    {}
                )
            }
        }
        compose.onNodeWithTag("touch:username").performTextInput("Viewer")
        compose.onNodeWithTag("touch:password").performTextInput("fixture-password")
        compose.onNodeWithText("Show").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Hide").assertExists()
        compose.onNodeWithTag("touch:password").performImeAction()
        compose.runOnIdle { assertEquals("Viewer:fixture-password", submitted) }
        compose.onNodeWithText("Use Quick Connect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, quick) }
        compose.runOnIdle { state = state.copy(busy = true, error = "Sign-in failed.") }
        compose.onNodeWithText("Signing in…").assertIsNotEnabled()
        compose.onNodeWithText("Sign-in failed.").assertExists()
    }

    @Test fun quickConnectShowsPendingCodeRenewalDisabledAndRetryStates() {
        var state by mutableStateOf(QuickState())
        var retries = 0
        compose.setContent { MaterialTheme { TouchQuickConnect(state, server, { retries++ }, {}) } }
        compose.onNodeWithText("Getting a code…").assertExists()
        compose.runOnIdle { state = QuickState(code = "123456", busy = false, renewed = true) }
        compose.onNodeWithTag("touch:quick-code").assertIsDisplayed()
        compose.onNodeWithText("The previous code expired. Enter the new code above.").assertExists()
        compose.onNodeWithText("Waiting for approval…").assertExists()
        compose.runOnIdle { state = QuickState(busy = false, disabled = true) }
        compose.onNodeWithText("Quick Connect is disabled on this server. Go back to use your username and password.").assertExists()
        compose.onNodeWithText("Waiting for approval…").assertDoesNotExist()
        compose.runOnIdle { state = QuickState(busy = false, error = "Network unavailable.") }
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    // Five filled buttons read alike, Remove included: one way forward, and the rest quieter.
    @Test fun accountsHaveOnePrimaryAndItIsTheCurrentAccount() {
        val other = Account(server, User("other-viewer", "Other"), "other-token")
        val both = AccountSnapshot(listOf(other, account), account.key, lastServer = server)
        assertEquals(account.key, touchAccountsPrimary(both))
        assertEquals(other.key, touchAccountsPrimary(both.copy(currentKey = null)))
        assertEquals(null, touchAccountsPrimary(both.copy(accounts = emptyList(), currentKey = null)))
        var state by mutableStateOf(both)
        compose.setContent { MaterialTheme { TouchAccounts(AccountsState(), state, {}, {}, {}, {}, {}) } }
        compose.waitForIdle()
        compose.onAllNodesWithTag("touch:accounts:primary").assertCountEquals(1)
        compose.onNodeWithTag("touch:accounts:primary").assertTextContains("Continue as Viewer")
        compose.runOnIdle { state = both.copy(accounts = emptyList(), currentKey = null) }
        compose.waitForIdle()
        compose.onNodeWithTag("touch:accounts:primary").assertTextContains("Add a server")
    }

    @Test fun removalSurvivesRestorationAndBackCancelsWithoutRemoving() {
        var removals = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { TouchAccounts(AccountsState(), snapshot, {}, { removals++ }, {}, {}, {}) }
        }
        compose.onNodeWithText("Remove Viewer on Fixture server").performScrollTo().performClick()
        compose.onNodeWithText("Remove remembered account?").assertExists()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Remove remembered account?").assertExists()
        // AlertDialog owns its own window and Back dispatcher; activity Back bypasses it.
        compose.runOnIdle {
            (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("Remove remembered account?").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, removals) }
        compose.onNodeWithText("Remove Viewer on Fixture server").performScrollTo().performClick()
        compose.onNodeWithTag("touch:confirm-removal").performClick()
        compose.runOnIdle { assertEquals(1, removals) }
    }

    @Test fun accountsExposeSwitchExpiredAndServerRemovalAndEmptyStates() {
        var selected: Account? = null
        var removed: Server? = null
        var current by mutableStateOf(snapshot.copy(accounts = listOf(account.copy(expired = true))))
        compose.setContent {
            MaterialTheme { TouchAccounts(AccountsState(), current, { selected = it }, {}, { removed = it }, {}, {}) }
        }
        compose.onNodeWithText("Sign in again as Viewer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, selected?.expired) }
        compose.onNodeWithText("Remove server Fixture server").performScrollTo().performClick()
        compose.onNodeWithTag("touch:confirm-removal").performClick()
        compose.runOnIdle {
            assertEquals(server, removed)
            current = AccountSnapshot()
        }
        compose.onNodeWithText("No remembered accounts. Sign in to start watching.").assertExists()
    }

    @Test fun settingsDialogSelectsValueAndShowsLoadingSaveAndRetryStates() {
        var state by mutableStateOf(PlaybackSettingsState())
        var chosen: Int? = null
        var retries = 0
        compose.setContent {
            MaterialTheme {
                TouchSettings(
                    state, "PCM", { change -> chosen = change(state.local).bitrateMbps }, { _, _ -> }, { retries++ }, {}, {}, {}, {}
                )
            }
        }
        compose.onNodeWithText("Auto (200 Mbps)").performScrollTo().performClick()
        compose.onNodeWithText("10 Mbps").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(10, chosen) }
        compose.onNodeWithText("Loading server preferences…").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("No preference").onFirst().assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(loading = false, error = "Could not read preferences.") }
        compose.onNodeWithText("Retry").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            state = state.copy(saving = true)
        }
        compose.onNodeWithText("Saving server preferences…").performScrollTo().assertIsDisplayed()
    }

    @Test fun aboutLicensesAndLicenseTextAreAccessibleWithoutRemoteInstructions() {
        var screen by mutableStateOf(0)
        val library = LicenseEntry("fixture-library", "Fixture library", "1.0", "MPL-2.0", "Fixture license notice.")
        compose.setContent {
            MaterialTheme {
                when (screen) {
                    0 -> TouchAbout({ screen = 1 }, {})
                    1 -> TouchLicenses(listOf(library), { screen = 2 }, { screen = 0 })
                    else -> TouchLicense(library) { screen = 1 }
                }
            }
        }
        compose.onNodeWithText("Open-source licenses").performScrollTo().performClick()
        compose.onNodeWithText("Fixture library 1.0 · MPL-2.0").performScrollTo().performClick()
        compose.onNodeWithText("Fixture license notice.").assertIsDisplayed()
    }

    @Test fun openingLicenseBodyKeepsSettingsSelectedInTouchNavigation() {
        val library = LicenseEntry("fixture-library", "Fixture library", "1.0", "MPL-2.0", "Fixture license notice.")
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                TouchShell(showNavigation = true, selected = touchTabFor(entry?.destination?.route), onTab = {}) {
                    NavHost(navController = nav, startDestination = "licenses") {
                        composable("licenses") {
                            TouchLicenses(listOf(library), { nav.navigate("license/$it") }, {})
                        }
                        composable("license/{index}") {
                            TouchLicense(library) { nav.popBackStack() }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Settings").assertIsSelected()
        compose.onNodeWithText("Fixture library 1.0 · MPL-2.0").performScrollTo().performClick()
        compose.onNodeWithText("Fixture license notice.").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsSelected()
        compose.onNodeWithText("Home").assertIsNotSelected()
        compose.onNodeWithText("Search").assertIsNotSelected()
    }

    @Test fun supporterOnlyOffersPurchaseForAnOfferAndExplainsDeviceUnavailability() {
        var state by mutableStateOf<SupporterState>(SupporterState.Loading)
        var purchases = 0
        compose.setContent { MaterialTheme { TouchSupporter(state, { purchases++ }, {}) } }
        compose.onNodeWithText("Checking Google Play…").assertExists()
        compose.runOnIdle { state = SupporterState.Offer(SupporterProduct("$2.99", "P1M")) }
        compose.onNodeWithText("$2.99 / month").assertExists()
        compose.onNodeWithText("Become a supporter").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, purchases)
            state = SupporterState.Pending
        }
        compose.onNodeWithText("Become a supporter").assertDoesNotExist()
        compose.onNodeWithText("Waiting for Google Play to confirm your payment.").assertExists()
        compose.runOnIdle { state = SupporterState.Active("fixture-code") }
        compose.onNodeWithTag("touch:supporter-code").assertExists()
        compose.runOnIdle { state = SupporterState.Unavailable(BillingFailure.Unavailable) }
        compose.onNodeWithText(
            "Subscribing needs Google Play billing, which is unavailable on this device or its Google account."
        ).assertExists()
        compose.onNodeWithText("Become a supporter").assertDoesNotExist()
    }
}
