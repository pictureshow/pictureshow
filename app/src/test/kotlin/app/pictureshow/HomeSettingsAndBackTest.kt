/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Settings within reach of where home opens, Sign out apart from it, and Back that climbs home before leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeSettingsAndBackTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val movies = (0..8).map { MediaItem("film-$it", "Invented Film $it", "Movie") }
    private val home = CatalogHomeState(
        rows = listOf(
            HomeRow("resume", "Continue watching", movies, wide = true),
            HomeRow("nextup", "Next up", movies, wide = true),
            HomeRow("libraries", "Libraries", listOf(MediaItem("library", "Cinema", "CollectionFolder", isFolder = true)), library = true),
            HomeRow("latest:library", "Latest in Cinema", movies)
        ),
        loading = false
    )
    private var signOuts = 0
    private var switchUsers = 0

    @Composable
    private fun Harness(state: CatalogHomeState = home) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    CatalogHome(state, account, {}, {}, { switchUsers++ }, { signOuts++ }, onSettings = { nav.navigate("settings") })
                }
                composable("settings") {
                    PlaybackSettingsScreen(PlaybackSettingsState(), "PCM only", {}, { _, _ -> }, {}) { nav.popBackStack() }
                }
            }
        }
    }

    private fun key(key: Key) {
        if (key == Key.Back) {
            compose.runOnIdle {
                listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP).forEach {
                    compose.activity.dispatchKeyEvent(android.view.KeyEvent(it, android.view.KeyEvent.KEYCODE_BACK))
                }
            }
        } else {
            compose.onRoot().performKeyInput { pressKey(key) }
        }
        compose.waitForIdle()
    }

    /** Down through the rail until [tag] has focus (HOME-24 lists libraries between Home and Live TV). */
    private fun railDownTo(tag: String) {
        repeat(12) {
            if (compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Focused) == true) return
            key(Key.DirectionDown)
        }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    @Test
    fun settingsIsInTheRailFromWhereHomeOpensAndBackReturnsToIt() {
        compose.setContent { Harness() }
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
        // HOME-24: Up from the first shelf opens the rail on Search; Settings is its last entry.
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused()
        railDownTo("home:settings")
        compose.onNodeWithTag("home:settings").assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("settings:advanced:Network quality").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("home:settings").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
    }

    @Test
    fun leftAtAShelfStartOpensTheRailOnHomeAndRightReturnsToTheCard() {
        compose.setContent { Harness() }
        key(Key.DirectionDown)
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:nextup:film-1").assertIsFocused()
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:nextup:film-0").assertIsFocused()
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:home").assertIsFocused().assertIsDisplayed()
        // Libraries from the Libraries row are rail entries too.
        compose.onNodeWithTag("home:library:library").assertIsDisplayed()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:nextup:film-0").assertIsFocused()
        key(Key.DirectionLeft)
        key(Key.Back)
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("home:nextup:film-0").assertIsFocused()
    }

    @Test
    fun signOutSitsApartFromSettings() {
        compose.setContent { Harness() }
        // Settings is in the rail, on screen where home opens; the account buttons are below every shelf.
        compose.onNodeWithTag("home:settings").assertIsDisplayed()
        val search = compose.onNodeWithTag("home:search").getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag("home:settings").getUnclippedBoundsInRoot()
        assertEquals(search.left, settings.left)
        // From Switch user, Right reaches Sign out and nothing lies beyond it.
        repeat(4) { key(Key.DirectionDown) }
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
        assertEquals(0, signOuts)
    }

    @Test
    fun backBelowTheFirstShelfReturnsToTheTopThenLeaves() {
        compose.setContent { Harness() }
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:resume:film-1").assertIsFocused()
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
        key(Key.Back)
        assertFalse(compose.activity.isFinishing)
        // The top of home: the first shelf where the viewer left it, with the header on screen.
        compose.onNodeWithTag("home:resume:film-1").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("home:search").assertIsDisplayed()
        compose.onNodeWithTag("home:hero").assertIsDisplayed()
        key(Key.Back)
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun backFromTheAccountButtonsAlsoReturnsToTheTop() {
        compose.setContent { Harness() }
        repeat(4) { key(Key.DirectionDown) }
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.Back)
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun backFromTheRailClosesItThenLeaves() {
        compose.setContent { Harness() }
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused()
        key(Key.Back)
        assertFalse(compose.activity.isFinishing)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
        key(Key.Back)
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun settingsWaitsWhileSignOutIsRunning() {
        // Opening Settings mid-sign-out would land on a screen whose account is about to disappear.
        compose.setContent { Harness(home.copy(busy = true)) }
        compose.onNodeWithTag("home:settings").assertIsNotEnabled()
    }

    @Test
    fun aPressPastTheEndOfARowStaysInThatRowSoSignOutIsNeverOneRightAway() {
        // Short shelves leave Sign out to the right of their last card, the layout a phone's D-pad found it in.
        val short = home.copy(
            rows = listOf(
                HomeRow("libraries", "Libraries", listOf(MediaItem("library", "Cinema", "CollectionFolder", isFolder = true))),
                HomeRow("latest:library", "Latest in Cinema", movies.take(2))
            )
        )
        compose.setContent { Harness(short) }
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
        // Left at a shelf's start is the rail (HOME-24), never Sign out.
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:home").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:home").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:latest:library:film-1").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:latest:library:film-1").assertIsFocused()
        assertEquals(0, signOuts)
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:sign-out").assertIsFocused()
    }

    /** The account chip opens its own window, so keys go to the topmost root. */
    private fun menuKey(key: Key) {
        compose.onAllNodes(isRoot()).onLast().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun openAccountMenu() {
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:account").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("home:account-menu").assertIsDisplayed()
        compose.onNodeWithTag("home:account:switch-user").assertIsFocused()
    }

    @Test
    fun atLargeTypeTheRailKeepsOneLineEntries() {
        // The scale is process-wide under Robolectric, so later test classes would inherit it without the reset.
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            compose.setContent { Harness() }
            val search = compose.onNodeWithTag("home:search").getUnclippedBoundsInRoot()
            val settings = compose.onNodeWithTag("home:settings").getUnclippedBoundsInRoot()
            assertEquals(search.bottom - search.top, settings.bottom - settings.top, "rail entries keep one height")
            assertTrue(settings.bottom <= 540.dp, "Settings stays on screen at large type")
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    @Test
    fun theAccountLeadsTheRailAndNamesWhoIsSignedInButNotTheServer() {
        // T-1: two of three viewers searched the header for Sign out and gave up. Who is signed in leads the rail.
        // Round 1: the server's name (often a container id) is not part of the account label; the menu still says it.
        compose.setContent { Harness() }
        val account = compose.onNodeWithTag("home:account").getUnclippedBoundsInRoot()
        val search = compose.onNodeWithTag("home:search").getUnclippedBoundsInRoot()
        assertEquals(account.left, search.left)
        assertTrue(account.bottom <= search.top, "the account comes first in the rail")
        compose.onNodeWithTag("home:account").assert(hasContentDescription("Account: Jamie"))
        key(Key.DirectionUp)
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:account").assertIsFocused()
        // Nothing lies above it.
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:account").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
    }

    @Test
    fun theAccountMenuOffersSwitchUserSignOutAndSettingsInThatOrder() {
        compose.setContent { Harness() }
        openAccountMenu()
        compose.onNodeWithText("Signed in on Screen room").assertIsDisplayed()
        menuKey(Key.DirectionDown)
        compose.onNodeWithTag("home:account:sign-out").assertIsFocused()
        menuKey(Key.DirectionDown)
        compose.onNodeWithTag("home:account:settings").assertIsFocused()
        menuKey(Key.DirectionCenter)
        compose.onNodeWithTag("home:account-menu").assertDoesNotExist()
        compose.onNodeWithTag("settings:advanced:Network quality").assertIsFocused()
        assertEquals(0, signOuts)
        assertEquals(0, switchUsers)
    }

    @Test
    fun signOutFromTheAccountMenuSignsOutOnce() {
        compose.setContent { Harness() }
        openAccountMenu()
        menuKey(Key.DirectionDown)
        menuKey(Key.DirectionCenter)
        compose.onNodeWithTag("home:account-menu").assertDoesNotExist()
        // AUTH-10: it asks first, starting on Cancel.
        compose.onNodeWithTag("sign-out:cancel").assertIsFocused()
        assertEquals(0, signOuts)
        compose.onNodeWithTag("sign-out:confirm-button").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, signOuts)
        assertEquals(0, switchUsers)
    }

    @Test
    fun switchUserFromTheAccountMenuOpensTheAccountChoice() {
        compose.setContent { Harness() }
        openAccountMenu()
        menuKey(Key.DirectionCenter)
        compose.onNodeWithTag("home:account-menu").assertDoesNotExist()
        assertEquals(1, switchUsers)
        assertEquals(0, signOuts)
    }

    @Test
    fun backClosesTheAccountMenuAndReturnsToTheChip() {
        compose.setContent { Harness() }
        openAccountMenu()
        val top = compose.onAllNodes(isRoot()).onLast().fetchSemanticsNode().root as ViewRootForTest
        val window = (top.view.parent as DialogWindowProvider).window
        compose.runOnIdle {
            window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            window.callback.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.waitForIdle()
        compose.onNodeWithTag("home:account-menu").assertDoesNotExist()
        compose.onNodeWithTag("home:account").assertIsFocused()
        assertFalse(compose.activity.isFinishing)
        assertEquals(0, signOuts + switchUsers)
    }
}
