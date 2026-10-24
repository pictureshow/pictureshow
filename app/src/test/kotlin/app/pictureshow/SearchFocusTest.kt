/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SearchFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("server", "Test", "https://media.example.test", "12"), User("user", "Viewer"), "token")
    private val movies = (0..12).map { MediaItem("movie-$it", "Copper Observatory $it", "Movie") }
    private val person = MediaItem("person", "Aster Quill", "Person")
    private val results =
        SearchState(
            query = "cloud",
            rows = listOf(HomeRow("MOVIES", "Movies", movies), HomeRow("PEOPLE", "People", listOf(person))),
            completed = true
        )

    private fun key(key: Key) {
        if (key == Key.Back) {
            compose.runOnIdle {
                compose.activity.dispatchKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK)
                )
                compose.activity.dispatchKeyEvent(
                    android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK)
                )
            }
        } else {
            compose.onRoot().performKeyInput { pressKey(key) }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Harness(start: String = "home", keyboard: Boolean = true) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            Box(
                Modifier.fillMaxSize().catalogSearchKey(
                    isCatalogSearchRoute(entry?.destination?.route) || entry?.destination?.route == "person"
                ) { nav.navigate("search") }
            ) {
                NavHost(nav, startDestination = start) {
                    composable("home") {
                        CatalogHome(CatalogHomeState(rows = listOf(HomeRow("latest", "Latest", movies)), loading = false), account, {
                            nav.navigate("item")
                        }, {}, {}, {}, onSearch = { nav.navigate("search") })
                    }
                    composable("search") {
                        SearchScreen(results, account, keyboard, {}, {}, {}, {}, {}, {
                            nav.navigate(
                                if (it.type ==
                                    "Person"
                                ) {
                                    "person"
                                } else {
                                    "item"
                                }
                            )
                        })
                    }
                    composable("item") {
                        CatalogDetail(CatalogDetailState(account, movies.first(), loading = false), {
                        }, {}, {}, {}, {})
                    }
                    composable("person") {
                        PersonScreen(PersonState(CatalogGridState(account, person, movies, 13), person), {
                            nav.navigate("item")
                        }, {}, {}, { nav.popBackStack() })
                    }
                    composable("player") {
                        CatalogDetail(CatalogDetailState(account, movies.first(), loading = false), {
                        }, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    @Test
    fun homeKeepsInitialCardAndUpReachesSearchWhichReturnsToTheHomeEntry() {
        compose.setContent { Harness() }
        compose.onNodeWithTag("home:latest:movie-0").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:search").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:field").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("search:field").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("home:search").assertIsFocused()
    }

    @Test
    fun dedicatedSearchKeyWorksFromHomeAndDetailButIsExcludedFromPlayer() {
        compose.setContent { Harness() }
        key(Key.Search)
        compose.onNodeWithTag("search:field").assertIsFocused()
        key(Key.DirectionDown)
        key(Key.Back)
        key(Key.Back)
        compose.onNodeWithTag("home:latest:movie-0").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Search)
        compose.onNodeWithTag("search:field").assertIsFocused()
    }

    @Test
    fun resultDetailBackRestoresSameScrolledCardWithQueryAndRowsIntact() {
        compose.setContent { Harness(start = "search") }
        key(Key.DirectionDown)
        repeat(6) { key(Key.DirectionRight) }
        compose.onNodeWithTag("search:MOVIES:movie-6").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("search:MOVIES:movie-6").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("search:field").assertTextEquals("cloud")
        key(Key.Back)
        compose.onNodeWithTag("search:field").assertIsFocused()
    }

    @Test
    fun personOpensFilmographyAndBackRestoresThePersonCard() {
        compose.setContent { Harness(start = "search") }
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:PEOPLE:person").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("person:name").assertIsDisplayed()
        compose.onNodeWithTag("grid:movie-0").assertIsFocused()
        key(Key.DirectionRight)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("grid:movie-1").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("search:PEOPLE:person").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun systemQueryWithClosedKeyboardInitiallyFocusesFirstResult() {
        compose.setContent { Harness(start = "search", keyboard = false) }
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
    }

    @Test
    fun playerDoesNotHandleTheDedicatedSearchKey() {
        compose.setContent { Harness(start = "player") }
        key(Key.Search)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.onNodeWithTag("search:field").assertDoesNotExist()
    }

    @Test
    fun retryReturnsFocusToFieldDuringLoadingThenToRecoveredResult() {
        var state by mutableStateOf(SearchState(query = "cloud", completed = true, allFailed = true))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SearchScreen(state, account, false, {}, {}, {}, {}, {
                    state =
                        state.copy(completed = false, allFailed = false, loading = true)
                }, {})
            }
        }
        compose.onNodeWithTag("search:field").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:retry").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:field").assertIsFocused()
        compose.runOnIdle { state = results }
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
    }

    @Test
    fun partialFailureNeverSaysNoResultsAndOffersRetryWithOrWithoutRows() {
        var retries = 0
        var state by mutableStateOf(SearchState(query = "cloud", completed = true, someFailed = true))
        compose.setContent {
            MaterialTheme {
                SearchScreen(state, account, false, {}, {}, {}, {}, { retries++ }, {})
            }
        }
        compose.onNodeWithText("No results for “cloud”").assertDoesNotExist()
        compose.onNodeWithText("Some results could not load. Try again.").assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:retry").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle {
            kotlin.test.assertEquals(1, retries)
            state = results.copy(someFailed = true)
        }
        compose.onNodeWithText("Some results could not load. Try again.").assertIsDisplayed()
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsDisplayed()
        compose.onNodeWithTag("search:retry").assertIsDisplayed()
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("search:retry").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("search:retry").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithTag("search:field").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:retry").assertIsFocused()
    }

    @Test
    fun personRetryFocusesRecoveredFilmographyCard() {
        var state by mutableStateOf(PersonState(CatalogGridState(account, person, error = "offline"), person))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PersonScreen(state, {}, {}, { state = state.copy(grid = state.grid.copy(loading = true, error = null)) }, {})
            }
        }
        compose.onNodeWithText("Retry").assertIsFocused()
        key(Key.DirectionCenter)
        compose.runOnIdle { state = state.copy(grid = state.grid.copy(loading = false, items = movies, total = 13)) }
        compose.onNodeWithTag("grid:movie-0").assertIsFocused()
    }

    @Test
    fun keyboardSearchActionSubmitsAndMovesToResults() {
        var submitted = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SearchScreen(results, account, true, {}, { submitted++ }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("search:field").performImeAction()
        compose.runOnIdle { org.junit.Assert.assertEquals(1, submitted) }
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
    }

    @Test
    fun emptyPersonHasAReachableBackAction() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PersonScreen(PersonState(CatalogGridState(account, person), person), {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("person:back").assertIsFocused()
    }

    @Test
    fun recentChipRunsQueryAndClearIsReachableWithDpad() {
        var state by mutableStateOf(SearchState(recent = listOf("cloud", "lantern")))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SearchScreen(state, account, true, {}, {}, { state = results.copy(query = it) }, { state = SearchState() }, {}, {})
            }
        }
        key(Key.DirectionDown)
        compose.onNodeWithTag("recent:0").assertIsFocused()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        compose.onNodeWithTag("search:clear").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:field").assertIsFocused()
        compose.runOnIdle { state = SearchState(recent = listOf("cloud")) }
        key(Key.DirectionDown)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:MOVIES:movie-0").assertIsFocused()
    }
}
