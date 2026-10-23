/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Settings within reach of where home opens, Sign out apart from it, and Back that climbs home before leaving. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class HomeSettingsAndBackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val movies = (0..8).map { MediaItem("film-$it", "Invented Film $it", "Movie") }
    private val home = CatalogHomeState(
        rows = listOf(
            HomeRow("resume", "Continue watching", movies, wide = true),
            HomeRow("nextup", "Next up", movies, wide = true),
            HomeRow("libraries", "Libraries", listOf(MediaItem("library", "Cinema", "CollectionFolder", isFolder = true))),
            HomeRow("latest:library", "Latest in Cinema", movies)
        ),
        loading = false
    )
    private var signOuts = 0

    @Composable
    private fun Harness(state: CatalogHomeState = home) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") {
                    CatalogHome(state, account, {}, {}, {}, { signOuts++ }, onSettings = { nav.navigate("settings") })
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

    @Test
    fun settingsIsUpAndRightFromWhereHomeOpensAndBackReturnsToIt() {
        compose.setContent { Harness() }
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:settings").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("home:settings").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
    }

    @Test
    fun signOutSitsApartFromSettings() {
        compose.setContent { Harness() }
        // Settings is on screen where home opens, beside Search; the account buttons are below every shelf.
        compose.onNodeWithTag("home:settings").assertIsDisplayed()
        val search = compose.onNodeWithTag("home:search").getUnclippedBoundsInRoot()
        val settings = compose.onNodeWithTag("home:settings").getUnclippedBoundsInRoot()
        assertEquals(search.top, settings.top)
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
    fun backFromTheHeaderLeaves() {
        compose.setContent { Harness() }
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused()
        key(Key.Back)
        assertTrue(compose.activity.isFinishing)
    }

    @Test
    fun settingsWaitsWhileSignOutIsRunning() {
        // Opening Settings mid-sign-out would land on a screen whose account is about to disappear.
        compose.setContent { Harness(home.copy(busy = true)) }
        compose.onNodeWithTag("home:settings").assertIsNotEnabled()
    }
}
