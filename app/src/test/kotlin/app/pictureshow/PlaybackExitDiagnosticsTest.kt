/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.pictureshow.core.Account
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PlaybackExitDiagnosticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hostOwnedObservationSurvivesPlayerRemovalAndReadsRealDetailFocusWithAccessibilityOff() {
        lateinit var nav: NavHostController
        lateinit var exit: () -> Unit
        val account = Account(Server("server", "Test", "https://example.test", "12"), User("viewer", "Viewer"), "fake")
        val item = MediaItem("film", "Private invented title", "Movie")
        shadowOf(compose.activity.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager).setEnabled(false)
        compose.setContent {
            PictureshowTheme {
                nav = rememberNavController()
                exit = rememberPlaybackExit(nav)
                NavHost(nav, startDestination = "item?item={item}") {
                    composable(
                        "item?item={item}",
                        listOf(
                            navArgument("item") {
                                type = NavType.StringType
                                defaultValue = "fixture"
                            }
                        )
                    ) {
                        CatalogDetail(CatalogDetailState(account, item, loading = false), {}, {}, {}, {}, {})
                    }
                    composable("player") { BasicText("Player") }
                }
            }
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { nav.navigate("player") }
        compose.onNodeWithText("Player").assertExists()
        ShadowLog.clear()
        compose.runOnIdle { finishPlayback(null, exit) }
        compose.onNodeWithText("Player").assertDoesNotExist()
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_100)
        compose.waitForIdle()
        val line = lines().single()
        assertTrue(line.contains("sampleMs=1000 destination=item lifecycle=RESUMED"), line)
        assertTrue(line.endsWith("contentFocused=true"), line)
        assertFalse(line.contains("Private") || line.contains("fixture") || line.contains("{item}"), line)
        compose.runOnIdle { assertEquals("item?item={item}", nav.currentDestination?.route) }
    }

    @Test fun aScreenWithoutAnInputTargetReportsNoContentFocus() = focusScenario(target = false)

    @Test fun zeroSizeFocusedTargetDoesNotCountAsContentFocus() = focusScenario(zero = true)

    @Test fun semanticallyHiddenFocusedTargetDoesNotCountAsContentFocus() = focusScenario(hidden = true)

    private fun focusScenario(target: Boolean = true, zero: Boolean = false, hidden: Boolean = false) {
        lateinit var host: View
        compose.setContent {
            host = LocalView.current
            val focus = remember { FocusRequester() }
            Box(Modifier.fillMaxSize().then(if (hidden) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                if (target) {
                    Box(Modifier.size(if (zero) 0.dp else 100.dp).focusRequester(focus).focusable().testTag("target"))
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        if (target) compose.onNodeWithTag("target", useUnmergedTree = true).assertIsFocused()
        compose.runOnIdle { assertEquals(false, playbackContentFocused(host)) }
    }

    private fun lines() = ShadowLog.getLogsForTag("PictureshowPlayback").map { it.msg }.filter { it.startsWith("exit-destination ") }
}
