/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class DetailFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Demo", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented feature", "Movie", overview = "An invented description.")
    private val failure = "The server could not complete the request (503)."

    @Test fun accessibleCatalogArrivalPublishesPrimaryFocusBeforeTheDefaultFadeEnds() = arrivalScenario(fromPlayback = false)

    @Test fun accessiblePlayerRecoveryKeepsTheExistingTransitionBeforeItsFocusHandoff() = arrivalScenario(fromPlayback = true)

    @Test fun sightedCatalogArrivalKeepsTheExistingTransition() = arrivalScenario(fromPlayback = false, accessible = false)

    @Test fun writeFailureIsAccessibleOnlyWhileItsNavigationEntryIsResumed() {
        lateinit var nav: NavHostController
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, readSucceeded = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                nav = rememberNavController()
                NavHost(nav, startDestination = "player") {
                    composable("player") { BasicText("Player") }
                    composable("detail") {
                        CatalogDetail(
                            state.value,
                            {},
                            {},
                            {},
                            {},
                            {}
                        )
                    }
                    composable("elsewhere") { BasicText("Another page") }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { nav.navigate("detail") }
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle { state.value = state.value.copy(error = failure) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("detail:write-error").assertDoesNotExist()
        compose.onNodeWithTag("detail:write-retry").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Change failed. $failure").assertIsDisplayed()
        compose.onNodeWithTag("detail:write-retry").assertIsFocused()
        compose.runOnIdle { nav.navigate("elsewhere") }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("detail:write-error").assertDoesNotExist()
        compose.onNodeWithTag("detail:write-retry").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Another page").assertIsDisplayed()
    }

    @Test fun seriesArrivalDoesNotPublishItsHeadingBeforeThePrimaryActionCanReceiveFocus() {
        val series = MediaItem("series", "Invented series", "Series")
        val state = mutableStateOf(CatalogDetailState(account, series, loading = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText(series.name).assertDoesNotExist()
        compose.runOnIdle {
            state.value = state.value.copy(
                loading = false,
                readSucceeded = true,
                nextUp = listOf(MediaItem("episode", "First episode", "Episode"))
            )
        }
        compose.onNodeWithText(series.name).assertIsDisplayed()
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    private fun arrivalScenario(fromPlayback: Boolean, accessible: Boolean = true) {
        lateinit var nav: NavHostController
        val arguments = listOf(
            navArgument("item") {
                type = NavType.StringType
                defaultValue = "fixture"
            }
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme {
                nav = rememberNavController()
                NavHost(
                    nav,
                    startDestination = if (fromPlayback) "player?item={item}" else "home",
                    enterTransition = { catalogEnterTransition(accessible, initialState.destination.route, targetState.destination.route) },
                    exitTransition = { catalogExitTransition(accessible, initialState.destination.route, targetState.destination.route) }
                ) {
                    composable("home") { BasicText("Library") }
                    composable("player?item={item}", arguments) { BasicText("Player") }
                    composable("item?item={item}", arguments) {
                        CatalogDetail(CatalogDetailState(account, film, loading = false), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { nav.navigate("item?item=fixture") }
        compose.mainClock.advanceTimeBy(400)
        if (fromPlayback || !accessible) {
            compose.onNodeWithTag("detail:play").assertDoesNotExist()
        } else {
            compose.onNodeWithTag("detail:play").assertIsFocused()
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun gridDoesNotPublishAWindowPaneThatCanQueueAheadOfTheNextDetailsAction() {
        compose.setContent {
            PictureshowTheme {
                CatalogGrid(
                    CatalogGridState(account, MediaItem("shows", "Shows", "CollectionFolder"), listOf(film)),
                    {},
                    {},
                    { _, _ -> },
                    {}
                )
            }
        }
        compose.onNodeWithTag("grid").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.PaneTitle))
    }

    @Test fun acceptedPlayRequestAnnouncesItsLabelWithoutRepeatingItAsState() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText("Play requested").assertIsDisplayed()
    }

    @Test fun acceptedWatchedRequestKeepsActualValueAndAnnouncesItsRequestedLabel() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:played-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:played-pending").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText("Watched change requested").assertIsDisplayed()
    }

    @Test fun acceptedFavoriteRequestKeepsActualValueAndAnnouncesItsRequestedLabel() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:favorite-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:favorite-pending").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Not favorite"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText("Favorite change requested").assertIsDisplayed()
    }

    @Test fun acceptedRequestPriorityDoesNotSpreadToPeersOrRepeatOnRefocus() {
        var accepted = 0
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = {
                        accepted++
                        true
                    }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play-pending")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithTag("detail:from-start-pending")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("detail:play-pending")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
            .performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.runOnIdle { assertEquals(1, accepted) }
    }

    @Test fun pendingPrimaryBecomesReadyOnTheSameFocusedNode() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
        }
        val id = compose.onNodeWithTag("detail:play-pending").fetchSemanticsNode().id
        compose.onNodeWithTag("detail:play-pending")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Ready"))
        assertEquals(id, compose.onNodeWithTag("detail:play").fetchSemanticsNode().id)
    }

    @Test fun lowerSelectionAndHeaderStayPutWhileTheWriteFailureIsReadableAndLive() {
        val tallFilm = film.copy(
            name = "An invented feature with a long wrapping title to put its lower action below the first viewport",
            productionYear = 2026,
            officialRating = "TV-14",
            runTimeTicks = 600_000_000
        )
        val state = mutableStateOf(CatalogDetailState(account, tallFilm, loading = true, playbackPending = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = tallFilm, onPendingAction = { true }) }
        }
        compose.onNodeWithTag("detail:from-start-pending").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:from-start").assertIsFocused().assertIsDisplayed()
        val before = compose.onNodeWithTag("detail:from-start").getUnclippedBoundsInRoot()
        val hero = compose.onNodeWithTag("detail:hero").getUnclippedBoundsInRoot()
        compose.runOnIdle { state.value = state.value.copy(error = failure) }
        compose.onNodeWithTag("detail:from-start").assertIsFocused().assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("detail:from-start").getUnclippedBoundsInRoot())
        assertEquals(hero, compose.onNodeWithTag("detail:hero").getUnclippedBoundsInRoot())
        val reason = compose.onNodeWithText("Change failed. $failure").getUnclippedBoundsInRoot()
        assertTrue(reason.top.value >= 0 && reason.bottom.value <= 540, "Write reason must stay in the viewport: $reason")
        compose.onNodeWithText("Change failed. $failure")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithTag("detail:write-error")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Change failed. $failure Retry is available.")
                )
            )
    }

    @Test fun oneFailurePresentationDoesNotReplaceTheReasonWhileItIsBeingSpoken() {
        val first = "The server has not finished loading. Retry to reload the item."
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = first, readFailed = true))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(error = "The server took too long to respond. Try again.") }
        compose.onNodeWithTag("detail:read-error").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(first)))
        )
        compose.onNodeWithTag("detail:retry")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. $first"))
    }

    @Test fun automaticReadReplacementCannotChangeTheVisibleFailureEpisode() {
        val first = "The server has not finished loading. Retry to reload the item."
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = first, readFailed = true, readAttempt = 1))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
        // An automatic post-report replacement can finish before Compose observes its loading
        // emission. Its transport generation is new; the viewer has not requested another try.
        compose.runOnIdle {
            state.value = state.value.copy(readAttempt = 2, error = "The server took too long to respond. Try again.")
        }
        compose.onNodeWithTag("detail:read-error").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Text, listOf(androidx.compose.ui.text.AnnotatedString(first)))
        )
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. $first"))
    }

    @Test fun acceptedRetryStartsANewFailureEpisodeAndSuccessClearsIt() {
        val first = "The server has not finished loading. Retry to reload the item."
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = first, readFailed = true, readAttempt = 1))
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(state.value, {}, {}, {}, {}, {
                    state.value = state.value.copy(loading = true, error = null, readFailed = false, readAttempt = 2)
                })
            }
        }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:retry")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Loading item. Please wait."))
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = failure, readFailed = true) }
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Retry. Loading failed. $failure"))
        compose.onNodeWithText(first).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(loading = false, error = null, readFailed = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:retry").assertDoesNotExist()
        compose.onNodeWithTag("detail:read-error").assertDoesNotExist()
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test fun aNewWriteAttemptCannotKeepThePreviousFailedChangesReason() {
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, readSucceeded = true))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(error = failure, writeAttempt = 1) }
        compose.onNodeWithText("Change failed. $failure").assertExists()
        // A fast transport can coalesce away the next optimistic error=null emission.
        compose.runOnIdle { state.value = state.value.copy(error = "Connection lost.", writeAttempt = 2) }
        compose.onNodeWithTag("detail:write-error")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf("Change failed. Connection lost. Retry is available.")
                )
            )
        compose.onNodeWithText("Change failed. $failure").assertDoesNotExist()
    }

    @Test fun passiveProgressBeforeTheReadSucceedsRetainsItsHiddenSemantics() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = true, playbackPending = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.onNodeWithText("Saving progress…").assertDoesNotExist()
    }

    @Test fun passiveReportingProgressDoesNotQueueSpeechAheadOfTheActionsState() {
        compose.setContent {
            PictureshowTheme {
                CatalogDetail(
                    CatalogDetailState(account, film, loading = false, playbackPending = true, readSucceeded = true),
                    {},
                    {},
                    {},
                    {},
                    {},
                    origin = film,
                    onPendingAction = { true }
                )
            }
        }
        compose.onNodeWithText("Item loaded. Saving progress…")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }
}
