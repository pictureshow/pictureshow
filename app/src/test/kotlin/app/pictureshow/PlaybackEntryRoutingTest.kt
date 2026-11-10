/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pictureshow.core.MediaItem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackEntryRoutingTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun syncEntryIdentitySurvivesNavigationRestorationWithoutStampingOrdinaryPlayback() {
        lateinit var nav: NavHostController
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { BasicText("Home") }
                composable("item?item={item}", listOf(navArgument("item") { type = NavType.StringType })) { BasicText("Detail") }
                composable(
                    "player?item={item}&start={start}",
                    listOf(navArgument("item") { type = NavType.StringType }, navArgument("start") { type = NavType.LongType })
                ) { BasicText("Player") }
            }
        }
        val item = MediaItem("0123456789abcdef0123456789abcdef", "An Invented Film", "Movie")
        val first = SyncOpen(17L, "entry-a", item.id, 9_007_199_254_740_993L)
        compose.runOnIdle {
            navigatePlaybackEntry(nav, ResolvedPlaybackEntry.Open(item, first.ticks / 10_000, first))
        }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle {
            val destination = checkNotNull(nav.currentBackStackEntry)
            assertEquals("player?item={item}&start={start}", destination.destination.route)
            assertEquals(first, destination.savedStateHandle.syncOpen(), "restoration preserves exact 64-bit ticks and server entry")
            assertNull(nav.previousBackStackEntry?.savedStateHandle?.syncOpen(), "the stamp belongs to the player, not its detail")
            assertFalse(destination.arguments?.keySet().orEmpty().any { it.startsWith("sync.") })
        }
        val second = first.copy(entryId = "entry-b", ticks = first.ticks + 1)
        compose.runOnIdle {
            navigatePlaybackEntry(nav, ResolvedPlaybackEntry.Open(item, second.ticks / 10_000, second))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                second,
                nav.currentBackStackEntry?.savedStateHandle?.syncOpen(),
                "same-media entries keep distinct server identities"
            )
            navigatePlaybackEntry(nav, ResolvedPlaybackEntry.Open(item, 42_000))
        }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(42_000L, nav.currentBackStackEntry?.arguments?.getLong("start"))
            assertNull(nav.currentBackStackEntry?.savedStateHandle?.syncOpen(), "ordinary playback must not inherit a prior server stamp")
        }
    }

    @Test
    fun entryBuildsHomeDetailPlayerStackAndBackReturnsToTheAuthorizedDetail() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { BasicText("Home") }
                composable("existing") { BasicText("Old destination") }
                composable("item?item={item}", listOf(navArgument("item") { type = NavType.StringType })) { BasicText("Detail") }
                composable(
                    "player?item={item}&start={start}",
                    listOf(navArgument("item") { type = NavType.StringType }, navArgument("start") { type = NavType.LongType })
                ) { BasicText("Player") }
            }
        }
        val item = MediaItem("0123456789abcdef0123456789abcdef", "An Invented Film", "Movie")
        compose.runOnIdle {
            nav.navigate("existing")
            navigatePlaybackEntry(nav, ResolvedPlaybackEntry.Open(item, 123_456))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("player?item={item}&start={start}", nav.currentDestination?.route)
            assertEquals(123_456L, nav.currentBackStackEntry?.arguments?.getLong("start"))
            assertEquals(item, Json.decodeFromString<MediaItem>(nav.currentBackStackEntry?.arguments?.getString("item")!!))
            assertTrue(nav.popBackStack())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("item?item={item}", nav.currentDestination?.route)
            assertEquals(item, Json.decodeFromString<MediaItem>(nav.currentBackStackEntry?.arguments?.getString("item")!!))
            assertTrue(nav.popBackStack())
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("home", nav.currentDestination?.route) }
    }
}
