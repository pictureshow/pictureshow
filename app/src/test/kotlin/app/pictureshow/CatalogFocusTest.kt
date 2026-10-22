package app.pictureshow

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.ClientResult
import app.pictureshow.core.HomeRow
import app.pictureshow.core.ItemPage
import app.pictureshow.core.JellyfinClient
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class CatalogFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val account = Account(Server("s", "Screen room", "https://media.example.test", "12.0.0"), User("u", "Jamie"), "fake")
    private val movies = (0..89).map { MediaItem("film-$it", "Invented Film $it", "Movie") }
    private val home = CatalogHomeState(
        rows = listOf(
            HomeRow("resume", "Continue watching", movies.take(9), wide = true),
            HomeRow("nextup", "Next up", movies.take(9), wide = true),
            HomeRow("libraries", "Libraries", listOf(MediaItem("library", "Cinema", "CollectionFolder", isFolder = true))),
            HomeRow("latest:library", "Latest in Cinema", movies.take(9))
        ),
        loading = false
    )

    private val stores = mutableListOf<ViewModelStore>()

    @After
    fun clearViewModels() {
        compose.runOnIdle { stores.forEach { it.clear() } }
    }

    private fun refreshingHome(service: CatalogModelsTest.FakeCatalog) {
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        lateinit var vm: CatalogHomeViewModel
        compose.runOnIdle {
            vm = CatalogHomeViewModel(account, CatalogSession(service, accounts))
            stores += ViewModelStore().also { it.put("home", vm) }
        }
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "home") {
                    composable("home") {
                        val screen by vm.state.collectAsStateWithLifecycle()
                        CatalogHome(screen, account, { nav.navigate("detail") }, {}, {}, {}, onResume = vm::reload)
                    }
                    composable("detail") {
                        CatalogDetail(CatalogDetailState(account, movies.first(), loading = false), {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    private fun key(key: Key) {
        if (key == Key.Back) {
            // Back is an Activity event. Compose's semantic-root injector only dispatches to its own View.
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
    private fun Harness(start: String = "home", state: CatalogHomeState = home) {
        MaterialTheme(colorScheme = darkColorScheme()) {
            val nav = rememberNavController()
            NavHost(nav, startDestination = start) {
                composable("home") {
                    CatalogHome(
                        state,
                        account,
                        { nav.navigate(if (it.id == "library") "grid" else "detail") },
                        {},
                        {},
                        {},
                        onSettings = { nav.navigate("settings") }
                    )
                }
                composable("settings") {
                    PlaybackSettingsScreen(PlaybackSettingsState(), "PCM only", {}, { _, _ -> }, {}) { nav.popBackStack() }
                }
                composable("grid") {
                    CatalogGrid(CatalogGridState(account, MediaItem("library", "Cinema"), movies, 90), {
                        nav.navigate("detail")
                    }, {}, { _, _ -> }, {})
                }
                composable("detail") {
                    CatalogDetail(CatalogDetailState(account, movies.first(), loading = false), {}, {}, {}, {}, {})
                }
            }
        }
    }

    @Test
    fun seriesInitiallyFocusesNextUpBeforeTheSelectedSeason() {
        val seasons = listOf(MediaItem("season-1", "Season 1", "Season"))
        val next = MediaItem("next-episode", "An Invented Episode", "Episode")
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        MediaItem("series", "An Invented Series", "Series"),
                        loading = false,
                        nextUp = listOf(next),
                        seasons = seasons,
                        selectedSeasonId = "season-1",
                        episodes = listOf(next)
                    ),
                    {},
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithTag("detail:next:next-episode").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("detail:refresh").assertDoesNotExist()
    }

    @Test
    fun seriesFocusRestSelectsSeasonAfterDelayAndEpisodeBackRestoresFocus() {
        val seasons = (1..3).map { MediaItem("season-$it", "Season $it", "Season") }
        fun episode(season: String) = MediaItem("episode-$season", "An Invented Episode", "Episode")
        var state by mutableStateOf(
            CatalogDetailState(
                account,
                MediaItem("series", "An Invented Series", "Series"),
                loading = false,
                seasons = seasons,
                selectedSeasonId = "season-2",
                episodes = listOf(episode("season-2"))
            )
        )
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "series") {
                    composable("series") {
                        CatalogDetail(
                            state,
                            { nav.navigate("episode") },
                            { season -> state = state.copy(selectedSeasonId = season.id, episodes = listOf(episode(season.id))) },
                            {},
                            {},
                            {}
                        )
                    }
                    composable("episode") {
                        CatalogDetail(CatalogDetailState(account, episode("season-3"), loading = false), {}, {}, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("detail:seasons:season-2").assertIsFocused().assertIsSelected()
            .assertContentDescriptionEquals("Season 2, selected season")
        compose.onNodeWithText("Season 2 ✓").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        key(Key.DirectionRight)
        compose.onNodeWithTag("detail:seasons:season-3").assertIsFocused()
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle { assertEquals("season-2", state.selectedSeasonId) }
        compose.mainClock.advanceTimeBy(100)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("season-3", state.selectedSeasonId) }
        compose.onNodeWithTag("detail:seasons:season-3").assertIsFocused().assertIsSelected()
        key(Key.DirectionDown)
        compose.onNodeWithTag("detail:episodes:episode-season-3").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("detail:episodes:episode-season-3").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun seasonWithoutSeasonPickerInitiallyFocusesItsFirstEpisode() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CatalogDetail(
                    CatalogDetailState(
                        account,
                        MediaItem("season", "Season 1", "Season"),
                        loading = false,
                        episodes = listOf(MediaItem("episode", "An Invented Episode", "Episode"))
                    ),
                    {},
                    {},
                    {},
                    {},
                    {}
                )
            }
        }
        compose.onNodeWithTag("detail:episodes:episode").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithTag("detail:refresh").assertDoesNotExist()
    }

    @Test
    fun realCatalog401RoutesToServerSignInWithUsernameAndKeepsOtherAccounts() = runBlocking {
        val accounts = AccountStore(object : TextPersistence {
            var value: String? = null
            override suspend fun read() = value
            override suspend fun write(value: String) {
                this.value = value
            }
        })
        val other = account.copy(user = User("other", "Robin"))
        accounts.remember(other)
        accounts.remember(account)
        val http = HttpClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        val session = CatalogSession(JellyfinClient(http, ClientIdentity("TV", "fake-device")), accounts)
        session.call(account) { views(account) }
        val snapshot = accounts.snapshots.value
        val route = accountDestination(snapshot)
        assertTrue(route.startsWith("login?"))
        val uri = Uri.parse("pictureshow://$route")
        assertEquals(account.user.name, uri.getQueryParameter("username"))
        assertTrue(uri.getQueryParameter("server")!!.contains(account.server.url))
        assertEquals(other, snapshot.accounts.first())
        assertEquals("home", accountDestination(snapshot.copy(accounts = listOf(account))))
        http.close()
    }

    @Test
    @Config(qualifiers = "w960dp-h540dp-land-television-xhdpi")
    // Native font metrics reproduce the TV header height; legacy graphics can hide this clipping.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun firstHomeRowKeepsWholeHeaderVisibleAfterStartupAndReturningFromLowerRows() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        compose.setContent { Harness() }
        fun settle() {
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        fun assertHeaderVisible() {
            val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
            assertEquals(1, focused.size)
            assertTrue(focused.single().config[SemanticsProperties.TestTag].startsWith("home:resume:"))
            val viewport = compose.onNodeWithTag("home").getUnclippedBoundsInRoot()
            listOf(
                compose.onNodeWithTag("home:search"),
                compose.onNodeWithText("Pictureshow"),
                compose.onNodeWithText("Jamie · Screen room")
            ).forEach { node ->
                // Unclipped layout bounds catch a sliver that assertIsDisplayed would accept.
                val bounds = node.getUnclippedBoundsInRoot()
                assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right, "Header clipped horizontally: $bounds")
                assertTrue(bounds.top >= viewport.top, "Header clipped above viewport: $bounds")
                assertTrue(bounds.bottom <= viewport.bottom, "Header below viewport: $bounds")
            }
        }
        settle()
        assertHeaderVisible()
        key(Key.DirectionDown)
        settle()
        key(Key.DirectionDown)
        settle()
        compose.onNodeWithTag("home:libraries:library").assertIsFocused().assertIsDisplayed()
        // Lower rows must continue to scroll the header away.
        assertTrue(compose.onAllNodesWithTag("home:search").fetchSemanticsNodes().all { it.boundsInRoot.height == 0f })
        key(Key.DirectionUp)
        settle()
        key(Key.DirectionUp)
        settle()
        assertHeaderVisible()
        key(Key.DirectionRight)
        settle()
        assertHeaderVisible()
        key(Key.DirectionCenter)
        settle()
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        settle()
        assertHeaderVisible()
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun tvHomeKeepsLowerShelfSelectionWhenItDetachesWhileMovingUpAndDown() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        val folders = (0..2).map { MediaItem("folder-$it", "Folder $it", "CollectionFolder", isFolder = true) }
        val stable = home.copy(
            rows = listOf(
                home.rows[0].copy(items = movies.take(1)),
                home.rows[2].copy(items = folders),
                home.rows[3],
                HomeRow("shows", "Latest in Shows", listOf(MediaItem("series", "A Show", "Series")))
            )
        )
        compose.setContent { Harness(state = stable) }
        fun move(direction: Key) {
            key(direction)
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitForIdle()
        }
        move(Key.DirectionDown)
        move(Key.DirectionDown)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
        repeat(5) { move(Key.DirectionRight) }
        compose.onNodeWithTag("home:latest:library:film-5").assertIsFocused()
        move(Key.DirectionUp)
        move(Key.DirectionUp)
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused()
        move(Key.DirectionDown)
        move(Key.DirectionDown)
        compose.onNodeWithTag("home:latest:library:film-5").assertIsFocused()
        // No refresh or reorder: the same immutable items back both visits.
        assertEquals(movies.take(9), stable.rows[2].items)
    }

    @Test
    fun homeInitiallyFocusesFirstCardAndDpadMovesBetweenRows() {
        compose.setContent { Harness() }
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:resume:film-1").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:nextup:film-0").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:resume:film-1").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun openingDetailAndPressingBackRestoresSameHomeCardAndScroll() {
        compose.setContent { Harness() }
        repeat(5) { key(Key.DirectionRight) }
        compose.onNodeWithTag("home:resume:film-5").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("home:resume:film-5").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionLeft)
        compose.onNodeWithTag("home:resume:film-4").assertIsFocused()
    }

    @Test
    fun returningHomeRefreshesServerRowsAndKeepsTheCardWhenItsPositionChanges() {
        val service = CatalogModelsTest.FakeCatalog().apply {
            resumeItems = ClientResult.Success(ItemPage(movies.take(9)))
            nextItems = ClientResult.Success(ItemPage(listOf(movies[10])))
        }
        refreshingHome(service)
        repeat(5) { key(Key.DirectionRight) }
        compose.onNodeWithTag("home:resume:film-5").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        var before = 0
        compose.runOnIdle {
            before = service.resumeCalls
            service.resumeItems = ClientResult.Success(ItemPage(listOf(movies[0], movies[5], movies[1], movies[2])))
            service.nextItems = ClientResult.Success(ItemPage(listOf(movies[20])))
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(before, service.resumeCalls) }
        key(Key.Back)
        compose.runOnIdle { assertTrue(service.resumeCalls > before) }
        compose.onNodeWithTag("home:resume:film-5").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:nextup:film-20").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun returningHomeAfterServerRemovesFocusedCardKeepsItsPositionInTheRow() {
        val service = CatalogModelsTest.FakeCatalog().apply {
            resumeItems = ClientResult.Success(ItemPage(movies.take(9)))
            nextItems = ClientResult.Success(ItemPage(listOf(movies[10])))
        }
        refreshingHome(service)
        repeat(5) { key(Key.DirectionRight) }
        compose.onNodeWithTag("home:resume:film-5").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        compose.runOnIdle {
            service.resumeItems = ClientResult.Success(ItemPage(movies.take(9).filterNot { it.id == "film-5" }))
            service.nextItems = ClientResult.Success(ItemPage(listOf(movies[21])))
        }
        key(Key.Back)
        compose.onNodeWithTag("home:resume:film-5").assertDoesNotExist()
        assertEquals(
            listOf("home:resume:film-6"),
            compose.onAllNodes(isFocused()).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        )
        compose.onNodeWithTag("home:resume:film-6").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:nextup:film-21").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun gridRestoresFocusedCardAfterDetailBack() {
        compose.setContent { Harness(start = "grid") }
        compose.onNodeWithTag("grid:film-0").assertIsFocused()
        repeat(4) { key(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        // A 960dp window fits five columns after padding and gaps; four Down presses reach index 20.
        val selected = "film-21"
        compose.onNodeWithTag("grid:$selected").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("detail:play").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("grid:$selected").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun removedResumeCardRestoresAnExistingVisibleCard() {
        var state by mutableStateOf(home)
        compose.setContent { Harness(state = state) }
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:resume:film-1").assertIsFocused()
        compose.runOnIdle { state = home.copy(rows = home.rows.map { if (it.key == "resume") it.copy(items = listOf(movies[0])) else it }) }
        compose.onNodeWithTag("home:resume:film-0").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun settingsIsReachedInAccountAreaAndBackRestoresItsButton() {
        compose.setContent { Harness(state = home.copy(rows = home.rows.drop(2))) }
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.DirectionRight)
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:settings").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithTag("settings:bitrate").assertIsFocused()
        key(Key.Back)
        compose.onNodeWithTag("home:settings").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun freshAccountCanReachProfileAndSignOutWithOnlyDpad() {
        compose.setContent { Harness(state = home.copy(rows = home.rows.drop(2))) }
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:switch-user").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionRight)
        compose.onNodeWithTag("home:sign-out").assertIsFocused().assertIsDisplayed()
        key(Key.DirectionUp)
        compose.onNodeWithTag("home:latest:library:film-0").assertIsFocused()
    }
}
