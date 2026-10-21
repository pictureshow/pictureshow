package app.pictureshow

import android.app.Application
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.HomeRow
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
                    CatalogHome(state, account, { nav.navigate(if (it.id == "library") "grid" else "detail") }, {}, {}, {})
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
