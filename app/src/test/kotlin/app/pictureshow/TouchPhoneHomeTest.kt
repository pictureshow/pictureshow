/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Phone Home, Libraries and the bar: HOME-09, HOME-10, TOUCH-02 (P-2, P-3, P-10, P-20 of the 2026-11-15 review). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchPhoneHomeTest {
    @get:Rule(order = -2)
    val timeLimit = PerTestTimeLimit(180)

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account =
        Account(Server("server", "Pictureshow Demo", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake")
    private val movie = MediaItem("movie", "A Film", "Movie")
    private val movies = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")
    private val unreachable = "Could not reach the server. Check your connection and try again."

    @Test fun pullingHomeDownReadsAgain() {
        var refreshes = 0
        var retries = 0
        val state = CatalogHomeState(rows = listOf(HomeRow("resume", "Continue watching", listOf(movie))), loading = false)
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, { retries++ }, onRefresh = { refreshes++ }) } }
        compose.onNodeWithTag("touch:home").performTouchInput { swipeDown() }
        compose.runOnIdle {
            assertEquals(1, refreshes, "The pull is the viewer asking for a fresh read")
            assertEquals(0, retries)
        }
    }

    @Test fun pullingLibrariesDownReadsAgain() {
        var refreshes = 0
        val state = CatalogHomeState(libraries = listOf(movies), loading = false)
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, {}, librariesOnly = true, onRefresh = { refreshes++ }) } }
        compose.onNodeWithTag("touch:home").performTouchInput { swipeDown() }
        compose.runOnIdle { assertEquals(1, refreshes) }
    }

    @Test fun outOfReachWithRowsKeepsThemAndSaysSoInOneLineWithTheWayToDownloads() {
        var watched = 0
        var retries = 0
        val state = CatalogHomeState(
            rows = listOf(HomeRow("resume", "Continue watching", listOf(movie))),
            loading = false,
            error = unreachable,
            unreachable = true
        )
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, { retries++ }, onWatchDownloads = { watched++ }) } }
        compose.onNodeWithText("Can't reach Pictureshow Demo. Showing what was loaded earlier.").assertIsDisplayed()
        compose.onNodeWithText(unreachable).assertDoesNotExist()
        compose.onNodeWithTag("touch:card:movie").assertIsDisplayed()
        compose.onNodeWithTag("home:watch-downloads").performClick()
        compose.onNodeWithTag("home:retry").performClick()
        compose.runOnIdle {
            assertEquals(1, watched)
            assertEquals(1, retries)
        }
    }

    @Test fun outOfReachWithRowsAndNoDownloadsOffersOnlyRetry() {
        val state = CatalogHomeState(
            rows = listOf(HomeRow("resume", "Continue watching", listOf(movie))),
            loading = false,
            error = unreachable,
            unreachable = true
        )
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, {}) } }
        compose.onNodeWithText("Can't reach Pictureshow Demo. Showing what was loaded earlier.").assertIsDisplayed()
        compose.onNodeWithTag("home:watch-downloads").assertDoesNotExist()
        compose.onNodeWithTag("home:retry").assertIsDisplayed()
    }

    @Test fun librariesOutOfReachKeepsTheTilesAndSaysTheSame() {
        val state = CatalogHomeState(libraries = listOf(movies), loading = false, error = unreachable, unreachable = true)
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, {}, {}, librariesOnly = true, onWatchDownloads = {}) } }
        compose.onNodeWithText("Can't reach Pictureshow Demo. Showing what was loaded earlier.").assertIsDisplayed()
        compose.onNodeWithTag("touch:card:movies").assertIsDisplayed()
        compose.onNodeWithTag("home:watch-downloads").assertIsDisplayed()
    }

    @Test fun headingsThatLeadSomewhereAreLinksAndTheRestArePlainHeadings() {
        var libraries = 0
        var opened: MediaItem? = null
        val state = CatalogHomeState(
            rows = listOf(
                HomeRow("resume", "Continue watching", listOf(movie), wide = true),
                HomeRow("libraries", "Libraries", listOf(movies), library = true),
                HomeRow("latest:movies", "Latest in Movies", listOf(movie))
            ),
            libraries = listOf(movies),
            loading = false
        )
        compose.setContent { MaterialTheme { TouchCatalogHome(state, account, { opened = it }, {}, onLibraries = { libraries++ }) } }
        compose.onNodeWithText("Continue watching").assertHasNoClickAction()
        compose.onNodeWithTag("touch:shelf-link:Libraries").performClick()
        compose.runOnIdle { assertEquals(1, libraries) }
        compose.onNodeWithTag("touch:home").performScrollToNode(hasTestTag("touch:shelf-link:Latest in Movies"))
        compose.onNodeWithTag("touch:shelf-link:Latest in Movies").performClick()
        compose.runOnIdle { assertEquals(movies, opened) }
    }

    // P-3: the review saw "Librarie / s" at font 2.0; Material 3 wants the whole label visible up to 2x.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w360dp-h780dp-port-xhdpi")
    @Test
    fun barLabelsStayWholeOnOneLineAtTwiceTheTextSize() = assertLabelsWhole()

    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w840dp-h600dp-land-xhdpi")
    @Test
    fun railLabelsStayWholeOnOneLineAtTwiceTheTextSize() = assertLabelsWhole()

    private fun assertLabelsWhole() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme { TouchShell(showNavigation = true, selected = "libraries", onTab = {}) { Text("Page") } }
            }
        }
        touchTabs.forEach { (_, label) ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(label), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(1, layout.lineCount, "$label must not wrap")
            assertFalse(layout.hasVisualOverflow, "$label must be whole, not clipped")
            val shown = layout.layoutInput.style.fontSize.value * layout.layoutInput.density.fontScale
            assertTrue(shown >= 11.99f, "$label stays at least its 1x size, was $shown")
        }
    }

    // P-20 / TOUCH-02: the root a page was opened under stays lit, through tab switches and Back.
    @Test fun innerPagesKeepTheRootTheyWereOpenedUnderLit() {
        lateinit var open: (String) -> Unit
        lateinit var back: () -> Unit
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                open = { nav.navigate(it) }
                back = { nav.popBackStack() }
                TouchShell(showNavigation = true, selected = rememberTouchLitTab(entry), onTab = { selectTouchTab(nav, it) }) {
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") { Text("Home page") }
                        composable("libraries") { Text("Libraries page") }
                        composable("settings") { Text("Settings page") }
                        composable("item/{id}") { Column { Text("Item ${it.arguments?.getString("id")}") } }
                    }
                }
            }
        }
        fun lit(label: String) {
            compose.onNode(hasText(label) and isSelectable()).assertIsSelected()
            touchTabs.map { it.second }.filter { it != label }.forEach {
                compose.onNode(hasText(it) and isSelectable()).assertIsNotSelected()
            }
        }
        compose.runOnIdle { open("item/from-home") }
        compose.onNodeWithText("Item from-home").assertIsDisplayed()
        lit("Home")
        compose.onNode(hasText("Libraries") and isSelectable()).performClick()
        compose.onNodeWithText("Libraries page").assertIsDisplayed()
        compose.runOnIdle { open("item/movies") }
        compose.runOnIdle { open("item/film") }
        compose.onNodeWithText("Item film").assertIsDisplayed()
        lit("Libraries")
        compose.onNode(hasText("Settings") and isSelectable()).performClick()
        compose.onNodeWithText("Settings page").assertIsDisplayed()
        lit("Settings")
        // Back in the Libraries tab, its place is restored and it is still Libraries' page.
        compose.onNode(hasText("Libraries") and isSelectable()).performClick()
        compose.onNodeWithText("Item film").assertIsDisplayed()
        lit("Libraries")
        compose.runOnIdle { back() }
        compose.onNodeWithText("Item movies").assertIsDisplayed()
        lit("Libraries")
    }

    // TOUCH-02a: Search keeps its own place, whatever Home opened in between, and Back from it stays inside Search.
    @Test fun theSearchTabReturnsToItsOwnPlaceAndNeverToAPageAnotherTabOpened() {
        lateinit var open: (String) -> Unit
        lateinit var back: () -> Unit
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                open = { nav.navigate(it) }
                back = { nav.popBackStack() }
                TouchShell(showNavigation = true, selected = rememberTouchLitTab(entry), onTab = { selectTouchTab(nav, it) }) {
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") { Text("Home page") }
                        composable("libraries") { Text("Libraries page") }
                        composable("settings") { Text("Settings page") }
                        composable("search?query={query}&keyboard={keyboard}") { Text("Search page") }
                        composable("item/{id}") { Column { Text("Item ${it.arguments?.getString("id")}") } }
                    }
                }
            }
        }
        fun tab(label: String) = compose.onNode(hasText(label) and isSelectable()).performClick()
        // A title opened from Home, then Search: the search page, not Home's title.
        compose.runOnIdle { open("item/from-home") }
        tab("Search")
        compose.onNodeWithText("Search page").assertIsDisplayed()
        // A result, then Home (its own title again) and another title there, then Search: the result, and Back reaches
        // the search page.
        compose.runOnIdle { open("item/result") }
        tab("Home")
        compose.onNodeWithText("Item from-home").assertIsDisplayed()
        compose.runOnIdle { open("item/home-title") }
        tab("Search")
        compose.onNodeWithText("Item result").assertIsDisplayed()
        compose.runOnIdle { back() }
        compose.onNodeWithText("Search page").assertIsDisplayed()
        // Search's root, then Home, a title there and Home again, then Search: the search page.
        tab("Home")
        compose.runOnIdle { open("item/second") }
        tab("Home")
        compose.onNodeWithText("Home page").assertIsDisplayed()
        tab("Search")
        compose.onNodeWithText("Search page").assertIsDisplayed()
        // Reselecting Search from inside it reaches its root; Home then Search returns there, not to the result.
        compose.runOnIdle { open("item/again") }
        tab("Search")
        compose.onNodeWithText("Search page").assertIsDisplayed()
        tab("Home")
        tab("Search")
        compose.onNodeWithText("Search page").assertIsDisplayed()
        compose.runOnIdle { back() }
        compose.onNodeWithText("Home page").assertIsDisplayed()
        // Home's own title closed by reselecting Home, a result, Libraries and its title, then Search: the result.
        compose.runOnIdle { open("item/home-again") }
        tab("Home")
        tab("Search")
        compose.runOnIdle { open("item/kept") }
        tab("Libraries")
        compose.runOnIdle { open("item/library-title") }
        tab("Home")
        compose.runOnIdle { open("item/home-third") }
        tab("Search")
        compose.onNodeWithText("Item kept").assertIsDisplayed()
        compose.runOnIdle { back() }
        compose.onNodeWithText("Search page").assertIsDisplayed()
        tab("Libraries")
        compose.onNodeWithText("Item library-title").assertIsDisplayed()
    }

    // TOUCH-02a: Home keeps its place like every other tab, and reselecting it, or leaving from its root, forgets it.
    @Test fun theHomeTabReturnsToTheTitleItWasShowing() {
        lateinit var open: (String) -> Unit
        lateinit var back: () -> Unit
        compose.setContent {
            MaterialTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                open = { nav.navigate(it) }
                back = { nav.popBackStack() }
                TouchShell(showNavigation = true, selected = rememberTouchLitTab(entry), onTab = { selectTouchTab(nav, it) }) {
                    NavHost(navController = nav, startDestination = "home") {
                        composable("home") { Text("Home page") }
                        composable("libraries") { Text("Libraries page") }
                        composable("settings") { Text("Settings page") }
                        composable("search?query={query}&keyboard={keyboard}") { Text("Search page") }
                        composable("item/{id}") { Column { Text("Item ${it.arguments?.getString("id")}") } }
                    }
                }
            }
        }
        fun tab(label: String) = compose.onNode(hasText(label) and isSelectable()).performClick()
        compose.runOnIdle { open("item/film") }
        compose.runOnIdle { open("item/extra") }
        tab("Libraries")
        compose.onNodeWithText("Libraries page").assertIsDisplayed()
        tab("Home")
        compose.onNodeWithText("Item extra").assertIsDisplayed()
        compose.onNode(hasText("Home") and isSelectable()).assertIsSelected()
        compose.runOnIdle { back() }
        compose.onNodeWithText("Item film").assertIsDisplayed()
        // A result opened in Search in between still comes back with Search, and Home with Home.
        tab("Search")
        compose.runOnIdle { open("item/result") }
        tab("Home")
        compose.onNodeWithText("Item film").assertIsDisplayed()
        tab("Search")
        compose.onNodeWithText("Item result").assertIsDisplayed()
        tab("Home")
        compose.onNodeWithText("Item film").assertIsDisplayed()
        // Reselecting Home reaches its root; Settings and back then shows the root, not the old title.
        tab("Home")
        compose.onNodeWithText("Home page").assertIsDisplayed()
        tab("Settings")
        tab("Home")
        compose.onNodeWithText("Home page").assertIsDisplayed()
    }

    @Test fun ownerIsTheRootItselfElseWhatWasStoredElseWhatWasLitBefore() {
        assertEquals("home" to false, touchOwner("home", "settings", "libraries"))
        assertEquals("settings" to false, touchOwner("license/3", null, "home"))
        assertEquals("libraries" to false, touchOwner("item/a", "libraries", "home"))
        assertEquals("home" to true, touchOwner("item/a", null, "home"))
        assertEquals(null to false, touchOwner("item/a", null, null))
    }

    // HOME-09: a network change is a moment the server's reach may have changed; the page's starting networks aren't.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun networkChangesCountOnceSettledAndNotAtTheStart() = runTest {
        val networks = MutableStateFlow(setOf("wifi"))
        val seen = mutableListOf<Set<String>>()
        val job = launch { networks.settledNetworkChanges(500).collect { seen += it } }
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(seen.isEmpty(), "The networks a page starts with are not a change")
        networks.value = setOf("wifi", "mobile")
        advanceTimeBy(100)
        networks.value = setOf("mobile")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(setOf("mobile")), seen, "A handover settles into one change")
        networks.value = emptySet()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(setOf("mobile"), emptySet()), seen, "Losing every network is a change too")
        job.cancel()
    }
}
