/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

    @Test fun settlementAnnouncesOnceOnTheSelectedActionWithoutLeavingReadyOnPeers() {
        val speech = mutableListOf<String>()
        val host = object : View(compose.activity) {
            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { assertEquals(listOf("Ready"), speech) }
        compose.onNodeWithTag("detail:played").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unwatched"))
        compose.runOnIdle {
            state.value = state.value.copy(item = film.copy(userData = film.userData.copy(played = true)))
        }
        compose.onNodeWithTag("detail:played")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Watched"))
        compose.onNodeWithTag("detail:play").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.runOnIdle { assertEquals(listOf("Ready"), speech) }
    }

    @Test fun anEmptyFreshSeasonCannotAnnounceItsDisabledPlayIsReady() {
        val speech = mutableListOf<String>()
        val host = object : View(compose.activity) {
            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series", childCount = 1)
        val state =
            mutableStateOf(
                CatalogDetailState(account, season, loading = true, playbackPending = true, episodes = listOf(film.copy(type = "Episode")))
            )
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = season, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.runOnIdle {
            state.value =
                state.value.copy(
                    item = season.copy(childCount = 0),
                    episodes = emptyList(),
                    loading = false,
                    playbackPending = false,
                    playbackReportFinished = true,
                    readSucceeded = true
                )
        }
        compose.runOnIdle { assertTrue(speech.isEmpty(), "An unavailable Play must not be announced as Ready: $speech") }
    }

    @Test fun settlementDoesNotAnnounceOverTheReadersIndependentTextSelection() {
        val speech = mutableListOf<String>()
        val host = object : View(compose.activity) {
            override fun getAccessibilityNodeProvider() = object : AccessibilityNodeProvider() {
                @Suppress("DEPRECATION")
                override fun findFocus(focus: Int): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
                    className = "android.widget.TextView"
                    isVisibleToUser = true
                }
            }

            @Suppress("DEPRECATION")
            override fun announceForAccessibility(text: CharSequence) {
                speech += text.toString()
            }
        }
        val state = mutableStateOf(CatalogDetailState(account, film, loading = true, playbackPending = true))
        compose.setContent {
            CompositionLocalProvider(LocalView provides host) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}, origin = film, onPendingAction = { true }) }
            }
        }
        compose.onNodeWithTag("detail:play-pending").assertIsFocused()
        compose.runOnIdle { state.value = state.value.copy(loading = false, playbackPending = false, readSucceeded = true) }
        compose.onNodeWithTag("detail:play").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.runOnIdle { assertTrue(speech.isEmpty()) }
    }

    @Test fun unknownSeasonPlaceholderCannotClaimAWatchedDirectionOrValue() {
        val season = MediaItem("season", "Invented season", "Season", seriesId = "series")
        val state = mutableStateOf(CatalogDetailState(account, season, loading = false, error = failure, readFailed = true))
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithText("Mark watched").assertDoesNotExist()
        compose.onNodeWithText("Mark unwatched").assertDoesNotExist()
        compose.onNodeWithText("Watched unavailable", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("detail:played", useUnmergedTree = true).assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Watched")))
        compose.onNodeWithTag("detail:played", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
        compose.runOnIdle {
            state.value = state.value.copy(
                item = season.copy(userData = season.userData.copy(played = true)),
                episodes = listOf(film.copy(type = "Episode")),
                error = null,
                readFailed = false,
                readSucceeded = true
            )
        }
        compose.onNodeWithText("Mark unwatched").assertIsDisplayed()
        compose.onNodeWithTag("detail:played")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Watched"))
    }

    @Suppress("DEPRECATION")
    @Test
    fun ordinaryArrivalFiltersOnlyTheEmptyHostFocusUntilHandoffFinishes() {
        val series = MediaItem("series", "Invented series", "Series")
        val state = mutableStateOf(CatalogDetailState(account, series, loading = true))
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle = registry
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        lateinit var host: View
        lateinit var parent: android.view.ViewGroup
        var inheritedInstalled = false
        val forwarded = mutableListOf<android.view.accessibility.AccessibilityEvent>()
        val inherited = object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(
                parent: android.view.ViewGroup,
                child: View,
                event: android.view.accessibility.AccessibilityEvent
            ): Boolean {
                forwarded += event
                return true
            }
        }
        compose.setContent {
            host = LocalView.current
            if (!inheritedInstalled) {
                parent = host.parent as android.view.ViewGroup
                androidx.core.view.ViewCompat.setAccessibilityDelegate(parent, inherited)
                inheritedInstalled = true
            }
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
            }
        }
        fun event(klass: String) = android.view.accessibility.AccessibilityEvent.obtain(
            android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED
        ).apply { className = klass }
        compose.runOnIdle {
            val delegate = requireNotNull(androidx.core.view.ViewCompat.getAccessibilityDelegate(parent))
            val empty = event("android.view.ViewGroup")
            val before = forwarded.size
            assertTrue(
                !delegate.onRequestSendAccessibilityEvent(parent, host, empty),
                "An empty host record must not select the first text node"
            )
            assertEquals(before, forwarded.size)
            val actual = event("android.view.View")
            assertTrue(delegate.onRequestSendAccessibilityEvent(parent, host, actual))
            assertTrue(forwarded.last() === actual)
        }
        compose.runOnIdle {
            state.value = state.value.copy(
                loading = false,
                readSucceeded = true,
                nextUp = listOf(MediaItem("episode", "First episode", "Episode"))
            )
        }
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle {
            val delegate = requireNotNull(androidx.core.view.ViewCompat.getAccessibilityDelegate(parent))
            val ordinary = event("android.view.ViewGroup")
            assertTrue(delegate.onRequestSendAccessibilityEvent(parent, host, ordinary))
            assertTrue(forwarded.last() === ordinary)
        }
        compose.runOnUiThread { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        compose.runOnIdle {
            val delegate = requireNotNull(androidx.core.view.ViewCompat.getAccessibilityDelegate(parent))
            val later = event("android.view.ViewGroup")
            assertTrue(
                delegate.onRequestSendAccessibilityEvent(parent, host, later),
                "Leaving an arrived page must not reactivate its initial host filter"
            )
            assertTrue(forwarded.last() === later)
        }
    }

    @Test fun arrivalMetadataStaysHiddenWhileThePrimaryIsPublishedUnfocused() {
        val series = MediaItem("series", "Invented series", "Series", productionYear = 2026, overview = "Arrival overview")
        val state = mutableStateOf(CatalogDetailState(account, series, loading = true))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            state.value = state.value.copy(
                loading = false,
                readSucceeded = true,
                nextUp = listOf(MediaItem("episode", "First episode", "Episode"))
            )
        }
        var publishedUnfocused = false
        repeat(12) {
            compose.mainClock.advanceTimeByFrame()
            val nodes = compose.onAllNodesWithTag("detail:play").fetchSemanticsNodes()
            if (nodes.isNotEmpty() && !nodes.single().config[SemanticsProperties.Focused]) {
                publishedUnfocused = true
                compose.onNodeWithText(series.name).assertDoesNotExist()
                compose.onNodeWithText("2026").assertDoesNotExist()
                compose.onNodeWithText("Arrival overview").assertDoesNotExist()
            }
        }
        assertTrue(publishedUnfocused, "The existing handoff must still publish the action unfocused")
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithText(series.name).assertIsDisplayed()
        compose.onNodeWithText("2026").assertIsDisplayed()
        compose.onNodeWithText("Arrival overview").assertIsDisplayed()
    }

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

    @Test fun acceptedWatchedRequestSpeaksTheRequestWithoutRepeatingItsOldValue() {
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
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNodeWithText("Watched change requested").assertIsDisplayed()
    }

    @Test fun acceptedFavoriteRequestSpeaksTheRequestWithoutRepeatingItsOldValue() {
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
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
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
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
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

    @Test fun idleFailureRefinementsKeepTheFocusedRetryDescriptionIdentical() {
        val first = "The server has not finished loading. Retry to reload the item."
        val state = mutableStateOf(CatalogDetailState(account, film, loading = false, error = first, readFailed = true))
        compose.setContent { PictureshowTheme { CatalogDetail(state.value, {}, {}, {}, {}, {}) } }
        val before = compose.onNodeWithTag("detail:retry").fetchSemanticsNode()
        val description = before.config[SemanticsProperties.StateDescription]
        compose.runOnIdle { state.value = state.value.copy(error = "The server took too long to respond. Try again.", readAttempt = 2) }
        compose.mainClock.advanceTimeBy(10_000)
        compose.onNodeWithTag("detail:retry").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description))
        assertEquals(before.id, compose.onNodeWithTag("detail:retry").fetchSemanticsNode().id)
        compose.onNodeWithText(first).assertIsDisplayed()
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
