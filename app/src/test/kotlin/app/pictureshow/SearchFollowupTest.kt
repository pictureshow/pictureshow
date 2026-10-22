/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.HomeRow
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SearchFollowupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var nav: NavHostController
    private var imeBottom = -1
    private var inputActive = false
    private var inputSessions = 0
    private val account = Account(Server("server", "Test", "https://media.example.test", "12"), User("user", "Viewer"), "token")
    private val movie = MediaItem("movie", "Copper Observatory", "Movie")

    private fun key(key: Key) {
        if (key == Key.Back) {
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        } else {
            compose.onRoot().performKeyInput { pressKey(key) }
        }
        compose.waitForIdle()
    }

    private fun assertKeyboard(visible: Boolean) {
        compose.runOnIdle {
            val imm = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            assertEquals(visible, shadowOf(imm).isSoftInputVisible, "IME show/hide must reach InputMethodManager")
        }
    }

    // API 30 hides through WindowInsetsController, which does not clear the IMM shadow's visibility.
    // Observe the delegated platform session for close/reopen rather than treating that flag as an IME window.
    private fun assertInputActive(active: Boolean) {
        compose.runOnIdle { assertEquals(active, inputActive, "Editable platform input session") }
    }

    private fun external(query: String, action: String = Intent.ACTION_SEARCH) {
        compose.runOnIdle {
            val term = requireNotNull(parseSearchIntent(Intent(action).putExtra(SearchManager.QUERY, query)))
            navigateSearchEntry(nav, term)
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Harness() {
        MaterialTheme {
            imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
            nav = rememberNavController()
            val entry by nav.currentBackStackEntryAsState()
            var keyboardRequest by remember { mutableStateOf(0) }
            InterceptPlatformTextInput(interceptor = { request, next ->
                inputActive = true
                inputSessions++
                try {
                    next.startInputMethod(request)
                } finally {
                    inputActive = false
                }
            }) {
                NavHost(
                    nav,
                    startDestination = "home",
                    modifier = Modifier.catalogSearchKey(isCatalogSearchRoute(entry?.destination?.route)) {
                        if (entry?.destination?.route?.substringBefore('?') == "search") {
                            keyboardRequest++
                        } else {
                            nav.navigate(searchRoute())
                        }
                    }
                ) {
                    composable("home") {
                        CatalogHome(
                            CatalogHomeState(rows = listOf(HomeRow("latest", "Latest", listOf(movie))), loading = false),
                            account,
                            { nav.navigate("item") },
                            {},
                            {},
                            {},
                            onSearch = { nav.navigate(searchRoute()) }
                        )
                    }
                    composable("item") {
                        CatalogDetail(CatalogDetailState(account, movie, loading = false), {}, {}, {}, {}, {})
                    }
                    composable(
                        "search?query={query}&keyboard={keyboard}",
                        arguments = listOf(
                            navArgument("query") {
                                type = NavType.StringType
                                defaultValue = ""
                            },
                            navArgument("keyboard") {
                                type = NavType.BoolType
                                defaultValue = true
                            }
                        )
                    ) { search ->
                        val query = search.arguments?.getString("query").orEmpty()
                        val result = movie.copy(id = query.ifEmpty { "typed" }, name = "$query result")
                        SearchScreen(
                            SearchState(query, listOf(HomeRow("MOVIES", "Movies", listOf(result))), completed = true),
                            account, search.arguments?.getBoolean("keyboard") ?: true,
                            {}, {}, {}, {}, {}, {}, keyboardRequest
                        )
                    }
                }
            }
        }
    }

    @Test
    fun focusedEditableFieldLetsAppBackLeaveWithZeroImeInset() {
        compose.setContent { Harness() }
        key(Key.DirectionUp)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:field").assertIsFocused()
        assertKeyboard(true)
        assertInputActive(true)
        compose.runOnIdle { assertEquals(0, imeBottom, "Floating TV keyboards can have no IME inset") }
        // Robolectric has no real IME window. This is the next Back delivered to the app after
        // the IME consumed the first Back; the field can still have an editable input session.
        key(Key.Back)
        compose.onNodeWithTag("home:search").assertIsFocused()
    }

    @Test
    fun homeEntryShowsImeWithoutCenterButReturningToFieldRequiresCenter() {
        compose.setContent { Harness() }
        key(Key.DirectionUp)
        key(Key.DirectionCenter)
        compose.onNodeWithTag("search:field").assertIsFocused()
        assertKeyboard(true)
        assertInputActive(true)
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:MOVIES:typed").assertIsFocused()
        assertInputActive(false)
        key(Key.Back)
        compose.onNodeWithTag("search:field").assertIsFocused()
        assertInputActive(false)
        val previousSessions = inputSessions
        key(Key.DirectionCenter)
        assertInputActive(true)
        compose.runOnIdle { assertTrue(inputSessions > previousSessions) }
    }

    @Test
    fun dedicatedKeyShowsImeOnEntryAndCanReopenItOnCurrentSearch() {
        compose.setContent { Harness() }
        key(Key.Search)
        compose.onNodeWithTag("search:field").assertIsFocused()
        assertKeyboard(true)
        assertInputActive(true)
        key(Key.DirectionDown)
        compose.onNodeWithTag("search:MOVIES:typed").assertIsFocused()
        assertInputActive(false)
        val previousSessions = inputSessions
        key(Key.Search)
        compose.onNodeWithTag("search:field").assertIsFocused()
        assertInputActive(true)
        compose.runOnIdle { assertTrue(inputSessions > previousSessions) }
    }

    @Test
    fun externalQueryReplacesCurrentSearchAndBackReturnsDirectlyHome() {
        compose.setContent { Harness() }
        external("  cloud  ")
        compose.onNodeWithTag("search:MOVIES:cloud").assertIsFocused()
        assertKeyboard(false)
        external("lantern", ASSISTANT_SEARCH_ACTION)
        compose.onNodeWithTag("search:field").assertTextEquals("lantern")
        compose.onNodeWithTag("search:MOVIES:lantern").assertIsFocused()
        compose.runOnIdle { assertEquals("home", nav.previousBackStackEntry?.destination?.route) }
        key(Key.Back)
        key(Key.Back)
        compose.onNodeWithTag("home:latest:movie").assertIsFocused()
    }

    @Test
    fun externalQueryFromDetailOpensSearchAndKeepsDetailBelowIt() {
        compose.setContent { Harness() }
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        external("cloud", ASSISTANT_SEARCH_ACTION)
        compose.onNodeWithTag("search:MOVIES:cloud").assertIsFocused()
        compose.runOnIdle { assertEquals("item", nav.previousBackStackEntry?.destination?.route) }
        key(Key.Back)
        key(Key.Back)
        compose.onNodeWithTag("detail:play").assertIsFocused()
    }

    @Test
    fun externalQueryClosesEditableSearchAndRepeatedQueryDoesNotStack() {
        compose.setContent { Harness() }
        key(Key.Search)
        assertKeyboard(true)
        assertInputActive(true)
        external("cloud")
        assertInputActive(false)
        compose.onNodeWithTag("search:MOVIES:cloud").assertIsFocused()
        external("cloud", ASSISTANT_SEARCH_ACTION)
        compose.onNodeWithTag("search:MOVIES:cloud").assertIsFocused()
        compose.runOnIdle { assertEquals("home", nav.previousBackStackEntry?.destination?.route) }
    }

    @Test
    fun detailUsesOneDecimalRatingInTheCurrentLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            compose.setContent {
                MaterialTheme {
                    CatalogDetail(
                        CatalogDetailState(
                            account,
                            movie.copy(productionYear = 2012, officialRating = "R", communityRating = 6.894, runTimeTicks = 102600000000),
                            loading = false
                        ),
                        {},
                        {},
                        {},
                        {},
                        {}
                    )
                }
            }
            compose.onNodeWithText("2012 · R · ★ 6,9 · 2h 51m").assertExists()
        } finally {
            Locale.setDefault(previous)
        }
    }
}
