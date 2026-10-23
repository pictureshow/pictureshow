/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.Text
import app.pictureshow.core.MediaItem
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises the same route encodings, recovery guard and navigation operations as the app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class PlayerRecoveryNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun recover(autoNext: Boolean) {
        val first = MediaItem("one", "Episode one", "Episode")
        val current = if (autoNext) MediaItem("two", "Episode two", "Episode") else first
        lateinit var nav: NavHostController
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "home") {
                composable("home") { Text("Home") }
                composable("item?item={item}", arguments = listOf(navArgument("item") { type = NavType.StringType })) {
                    Text("Detail")
                }
                composable(
                    "player?item={item}&start={start}",
                    arguments = listOf(
                        navArgument("item") { type = NavType.StringType },
                        navArgument("start") { type = NavType.LongType }
                    )
                ) {
                    PlayerRestoration(first, onRestore = { recoverPlaybackEntry(nav, it) }) { onCurrent ->
                        SideEffect { onCurrent(current) }
                        Text("Playing")
                    }
                }
            }
        }
        compose.runOnIdle { navigatePlaybackEntry(nav, ResolvedPlaybackEntry.Open(first, 42_000)) }
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            val entry = requireNotNull(nav.currentBackStackEntry)
            assertEquals("item?item={item}", entry.destination.route)
            assertEquals(current.id, Json.decodeFromString<MediaItem>(requireNotNull(entry.arguments?.getString("item"))).id)
            assertTrue(nav.popBackStack())
        }
        compose.waitForIdle()
        if (autoNext) {
            compose.runOnIdle {
                val entry = requireNotNull(nav.currentBackStackEntry)
                assertEquals("item?item={item}", entry.destination.route)
                assertEquals(first.id, Json.decodeFromString<MediaItem>(requireNotNull(entry.arguments?.getString("item"))).id)
                assertTrue(nav.popBackStack())
            }
            compose.waitForIdle()
        }
        compose.runOnIdle { assertEquals("home", nav.currentBackStackEntry?.destination?.route) }
    }

    @Test
    fun restoredPlayerReusesItsDetailAndBackReturnsHome() = recover(autoNext = false)

    @Test
    fun restoredAutoNextPlayerOpensCurrentEpisodeAndBackTraversesOriginalDetailThenHome() = recover(autoNext = true)
}
