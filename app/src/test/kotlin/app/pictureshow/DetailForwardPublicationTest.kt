/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DetailForwardPublicationTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val series = MediaItem("series", "Invented series", "Series", childCount = 1, overview = "Arrival overview")
    private val episode = MediaItem("episode", "Invented episode", "Episode")

    @Test fun aColdQueuedSeriesKeepsMetadataHiddenUntilItsPublishedPrimaryGetsFocus() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(CatalogDetailState(account, series, loading = true), {
                }, {}, {}, {}, {}, origin = series, onPendingAction = { true })
            }
        }
        var publishedUnfocused = false
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val nodes = compose.onAllNodesWithTag("detail:play-pending").fetchSemanticsNodes()
            if (nodes.isNotEmpty() && !nodes.single().config[SemanticsProperties.Focused]) {
                publishedUnfocused = true
                compose.onNodeWithText(series.name).assertDoesNotExist()
                compose.onNodeWithText(series.overview!!).assertDoesNotExist()
            }
        }
        assertTrue(publishedUnfocused, "Guarded controls still publish unfocused before the native focus event")
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithText(series.name).assertIsDisplayed()
        compose.onNodeWithText(series.overview!!).assertIsDisplayed()
    }

    @Test fun knownSeriesToggleDataAvoidsAProvisionalLabelDuringPlainArrival() {
        compose.mainClock.autoAdvance = false
        val state = mutableStateOf(CatalogDetailState(account, series, loading = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = series, onPendingAction = { true }) }
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        val primaryId = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        compose.onNodeWithTag("detail:played-pending-label", useUnmergedTree = true).assertTextEquals("Mark watched")
        compose.runOnIdle {
            state.value = state.value.copy(loading = false, readSucceeded = true, nextUp = listOf(episode), episodes = listOf(episode))
        }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        assertEquals(primaryId, compose.onNodeWithTag("detail:play").fetchSemanticsNode().id)
        compose.onNodeWithTag("detail:played-label", useUnmergedTree = true).assertTextEquals("Mark watched")
    }

    @Test fun aReaderBackReturnDoesNotWaitForTheDecorativeFade() = backReturn(accessible = true)

    @Test fun aSightedBackReturnDoesNotWaitForTheDecorativeFade() = backReturn(accessible = false)

    @Test fun ordinaryReaderCatalogBackKeepsItsExistingFade() = backReturn(accessible = true, playback = false)

    private fun backReturn(accessible: Boolean, playback: Boolean = true) {
        compose.mainClock.autoAdvance = false
        lateinit var nav: NavHostController
        val args = listOf(
            navArgument("item") {
                type = NavType.StringType
                defaultValue = "fixture"
            }
        )
        compose.setContent {
            PictureshowTheme {
                nav = rememberNavController()
                NavHost(
                    nav,
                    startDestination = "item?item={item}",
                    enterTransition = { catalogEnterTransition(accessible, initialState.destination.route, targetState.destination.route) },
                    exitTransition = { catalogExitTransition(accessible, initialState.destination.route, targetState.destination.route) },
                    popEnterTransition = {
                        catalogEnterTransition(accessible, initialState.destination.route, targetState.destination.route, returning = true)
                    },
                    popExitTransition = {
                        catalogExitTransition(accessible, initialState.destination.route, targetState.destination.route, returning = true)
                    }
                ) {
                    composable("item?item={item}", args) {
                        CatalogDetail(CatalogDetailState(account, episode, loading = false), {}, {}, {}, {}, {})
                    }
                    composable("player?item={item}", args) { BasicText("Player") }
                    composable("grid?item={item}", args) { BasicText("Grid") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { nav.navigate(if (playback) "player?item=fixture" else "grid?item=fixture") }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText(if (playback) "Player" else "Grid").assertIsDisplayed()
        compose.runOnIdle { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(400)
        if (!playback) {
            assertTrue(compose.onAllNodesWithTag("detail:play").fetchSemanticsNodes().none { it.config[SemanticsProperties.Focused] })
            compose.mainClock.advanceTimeBy(450)
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }
}
