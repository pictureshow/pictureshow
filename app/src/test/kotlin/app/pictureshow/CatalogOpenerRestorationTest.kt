/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Accessibility activation need not move input focus. Back must use the activated opener anyway. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogOpenerRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Test", "https://media.example.test", "12"), User("user", "Viewer"), "token")
    private val films = (0..5).map { MediaItem("film-$it", "Fixture $it", "Movie") }
    private val people = (0..2).map { MediaItem("person-$it", "Fixture $it", "Person") }
    private val rows = listOf(HomeRow("films", "Movies", films), HomeRow("shows", "Shows", films.map { it.copy(type = "Series") }))
    private var searchState by mutableStateOf(
        SearchState(query = "fixture", rows = listOf(HomeRow("PEOPLE", "People", people)), completed = true)
    )
    private var keyboardRequest by mutableStateOf(0)
    private lateinit var nav: NavHostController
    private lateinit var homeLifecycle: LifecycleOwner
    private lateinit var host: View
    private lateinit var focusManager: FocusManager
    private val restoration = StateRestorationTester(compose)
    private var home by mutableStateOf(CatalogHomeState(rows, loading = false))

    private fun start(search: Boolean = false) {
        restoration.setContent {
            MaterialTheme {
                host = LocalView.current
                focusManager = LocalFocusManager.current
                nav = rememberNavController()
                NavHost(nav, startDestination = if (search) "search" else "home") {
                    composable("home") {
                        homeLifecycle = LocalLifecycleOwner.current
                        CatalogHome(home, account, {
                            nav.navigate("detail")
                        }, {}, { nav.navigate("detail") }, {}, onSearch = { nav.navigate("search") })
                    }
                    composable("search") {
                        SearchScreen(
                            searchState,
                            account, true, {}, {}, {}, {}, {}, { nav.navigate("detail") }, keyboardRequest = keyboardRequest
                        )
                    }
                    composable("detail") { CatalogDetail(CatalogDetailState(account, films.first(), loading = false), {}, {}, {}, {}, {}) }
                }
            }
        }
    }

    private fun open(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    private fun back() {
        compose.runOnIdle { nav.popBackStack() }
        compose.waitForIdle()
    }

    @Test fun searchKeyDuringColdReloadKeepsTheFieldWhenResultsArrive() {
        start(search = true)
        open("search:PEOPLE:person-1")
        compose.runOnIdle { searchState = SearchState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.runOnIdle { keyboardRequest++ }
        compose.onNodeWithTag("search:field").assertIsFocused()
        compose.runOnIdle {
            searchState =
                SearchState(query = "fixture", rows = listOf(HomeRow("PEOPLE", "People", people)), completed = true)
        }
        compose.onNodeWithTag("search:field").assertIsFocused()
    }

    @Test fun coldSearchKeepsThePersonWhileTheSavedQueryAndResultsReload() {
        start(search = true)
        open("search:PEOPLE:person-1")
        compose.runOnIdle { searchState = SearchState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.runOnIdle { searchState = SearchState(query = "fixture", loading = true) }
        compose.runOnIdle {
            searchState =
                SearchState(query = "fixture", rows = listOf(HomeRow("PEOPLE", "People", people)), completed = true)
        }
        compose.onNodeWithTag("search:PEOPLE:person-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun earlyRemoteInputDuringColdHomeLoadingReachesSearchAndKeepsItWhenRowsArrive() {
        start()
        open("home:shows:film-1")
        compose.runOnIdle { home = CatalogHomeState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag("home:search").assertIsFocused()
        compose.runOnIdle { home = CatalogHomeState(rows, loading = false) }
        compose.onNodeWithTag("home:search").assertIsFocused()
    }

    @Test fun coldSearchFailureOffersRetryInsteadOfLeavingNoInputTarget() {
        start(search = true)
        open("search:PEOPLE:person-1")
        compose.runOnIdle { searchState = SearchState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.runOnIdle { searchState = SearchState(query = "fixture", allFailed = true) }
        compose.onNodeWithTag("search:retry").assertIsFocused().assertIsDisplayed()
    }

    @Test fun coldSearchDoesNotOfferRecentSearchesBeforeItsOpenerReloads() {
        val manager = compose.activity.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setEnabled(true)
        try {
            start(search = true)
            open("search:PEOPLE:person-1")
            compose.runOnIdle { searchState = SearchState(loading = true, recent = listOf("previous")) }
            restoration.emulateSavedInstanceStateRestore()
            compose.mainClock.autoAdvance = false
            back()
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("recent:0").assertDoesNotExist()
            compose.onNodeWithText("Recent searches").assertDoesNotExist()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("search:field").assertIsFocused()
            compose.onNodeWithTag("recent:0").assertExists()
        } finally {
            compose.mainClock.autoAdvance = true
            shadow.setEnabled(false)
        }
    }

    private fun withSpokenAccessibility(block: () -> Unit) {
        val manager = compose.activity.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setEnabledAccessibilityServiceList(
            listOf(
                android.accessibilityservice.AccessibilityServiceInfo().apply {
                    feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_SPOKEN
                }
            )
        )
        shadow.setEnabled(true)
        shadow.setTouchExplorationEnabled(true)
        try {
            block()
        } finally {
            compose.mainClock.autoAdvance = true
            shadow.setTouchExplorationEnabled(false)
            shadow.setEnabled(false)
            shadow.setEnabledAccessibilityServiceList(emptyList())
        }
    }

    @Test fun homeDrawsWhileItsOpenerWaitsForTheDestinationToResume() = withSpokenAccessibility {
        start()
        open("home:films:film-1")
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.runOnIdle { assertTrue(homeLifecycle.lifecycle.currentState != Lifecycle.State.RESUMED) }
        compose.onNodeWithTag("home:films:film-1").assertIsNotFocused()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("home:films:film-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun incomingBackDispatchDuringReloadDoesNotReplaceThePendingOpener() = withSpokenAccessibility {
        start()
        open("home:shows:film-1")
        compose.runOnIdle { home = CatalogHomeState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            kotlin.test.assertFalse(compose.activity.isFinishing)
        }
        compose.runOnIdle { home = CatalogHomeState(rows, loading = false) }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("home:shows:film-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun incomingBackDispatchWhileSearchReturnsKeepsThePersonOpener() = withSpokenAccessibility {
        start()
        compose.onNodeWithTag("home:search").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("search:field").assertIsFocused()
        open("search:PEOPLE:person-1")
        compose.runOnIdle { searchState = SearchState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            assertTrue(nav.currentDestination?.route == "search")
            kotlin.test.assertFalse(compose.activity.isFinishing)
        }
        compose.runOnIdle {
            searchState = SearchState(query = "fixture", rows = listOf(HomeRow("PEOPLE", "People", people)), completed = true)
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("search:PEOPLE:person-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun disabledSavedActionFallsBackToAnAvailableLabelledAction() = withSpokenAccessibility {
        start()
        repeat(rows.size) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        open("home:switch-user")
        compose.runOnIdle { home = CatalogHomeState(rows, loading = false, busy = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.onNodeWithTag("home:search").assertIsFocused().assertIsDisplayed()
    }

    @Test fun cardAccessibilityAcknowledgementRevealsOtherActionsBeforeTimeout() = withSpokenAccessibility {
        start()
        open("home:films:film-1")
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(900)
        val card = compose.onNodeWithTag("home:films:film-1").assertIsFocused().fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(host.accessibilityNodeProvider.performAction(card.id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home:search").assertExists()
    }

    @Test fun buttonAccessibilityAcknowledgementRevealsOtherActionsBeforeTimeout() = withSpokenAccessibility {
        start()
        compose.onNodeWithTag("home:search").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("search:field").assertIsFocused()
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(900)
        val button = compose.onNodeWithTag("home:search").assertIsFocused().fetchSemanticsNode()
        compose.runOnIdle {
            assertTrue(host.accessibilityNodeProvider.performAction(button.id, AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null))
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home:settings").assertExists()
    }

    @Test fun platformFocusMovementCannotReplaceTheOpenerDuringAccessibilityHandoff() = withSpokenAccessibility {
        start()
        open("home:films:film-1")
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(900)
        compose.runOnIdle { focusManager.moveFocus(FocusDirection.Up) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("home:films:film-1").assertIsFocused()
    }

    @Test fun coldLastShelfWaitsForPlacementBeforeReleasingFocusReceiver() = withSpokenAccessibility {
        val shelves = (0..7).map { HomeRow("shelf-$it", "Shelf $it", films) }
        home = CatalogHomeState(shelves, loading = false)
        start()
        repeat(7) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        repeat(3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) } }
        val opener = "home:shelf-7:film-3"
        open(opener)
        compose.runOnIdle { home = CatalogHomeState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        }
        compose.mainClock.advanceTimeBy(900)
        compose.runOnIdle {
            home = CatalogHomeState(shelves.map { it.copy(items = it.items.map { item -> item.copy() }) }, loading = false)
        }
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            listOf("home:search", "home:settings", "home:switch-user").forEach { tag ->
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    kotlin.test.assertFalse(node.config.getOrNull(SemanticsProperties.Focused) == true, "Header stole focus: $tag")
                }
            }
        }
        compose.onNodeWithTag(opener).assertIsFocused().assertIsDisplayed()
    }

    @Test fun returningHomeOffersOnlyItsOpenerThenRevealsHeaderOnViewerInput() {
        val manager = compose.activity.getSystemService(
            android.content.Context.ACCESSIBILITY_SERVICE
        ) as android.view.accessibility.AccessibilityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setEnabled(true)
        try {
            start()
            open("home:films:film-1")
            compose.mainClock.autoAdvance = false
            back()
            compose.mainClock.advanceTimeBy(900)
            compose.onNodeWithTag("home:films:film-1").assertIsFocused()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
            compose.onNodeWithTag("home:search").assertDoesNotExist()
            compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithTag("home:films:film-2").assertIsFocused()
            compose.onNodeWithTag("home:search").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        } finally {
            compose.mainClock.autoAdvance = true
            shadow.setEnabled(false)
        }
    }

    @Test fun firstRemoteArrowDuringSearchHandoffMovesToTheNextPerson() = withSpokenAccessibility {
        start(search = true)
        open("search:PEOPLE:person-1")
        compose.mainClock.autoAdvance = false
        back()
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("search:PEOPLE:person-1").assertIsFocused()
        compose.onNodeWithTag("search:field").assertDoesNotExist()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithTag("search:PEOPLE:person-2").assertIsFocused()
        compose.onNodeWithTag("search:field").assertExists()
    }

    @Test fun accessibilitySearchActivationReturnsToHomeSearchWithoutPriorInputFocus() {
        start()
        compose.onNodeWithTag("home:search").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithTag("search:field").assertIsFocused()
        back()
        compose.onNodeWithTag("home:search").assertIsFocused().assertIsDisplayed()
    }

    @Test fun accessibilityPersonActivationRestoresThatPersonWithoutPriorInputFocus() {
        start(search = true)
        compose.onNodeWithTag("search:field").assertIsFocused()
        open("search:PEOPLE:person-1")
        back()
        compose.onNodeWithTag("search:PEOPLE:person-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun accessibilityHomeActivationSurvivesSavedStackAndDelayedReload() {
        start()
        open("home:shows:film-1")
        compose.runOnIdle { home = CatalogHomeState(loading = true) }
        restoration.emulateSavedInstanceStateRestore()
        back()
        compose.runOnIdle { home = CatalogHomeState(rows, loading = false) }
        compose.onNodeWithTag("home:shows:film-1").assertIsFocused().assertIsDisplayed()
    }

    @Test fun removedHomeOpenerUsesItsOldPositionInTheSameRowAfterRestoration() {
        start()
        open("home:shows:film-1")
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { home = home.copy(rows = listOf(rows[0], rows[1].copy(items = rows[1].items.filterNot { it.id == "film-1" }))) }
        back()
        compose.onNodeWithTag("home:shows:film-2").assertIsFocused().assertIsDisplayed()
    }
}
