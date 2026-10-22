/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.ItemPage
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.MediaItem
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual sign-in graph, Koin ViewModels, account persistence signals and delayed production home loading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SignInFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("server", "Screen room", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("sam", "Sam"), "fake-token")
    private val other = account.copy(user = User("robin", "Robin"))
    private val library = MediaItem("library", "Invented Library", "CollectionFolder", isFolder = true)
    private val homeReady = CompletableDeferred<Unit>()
    private val quickAuthorized = CompletableDeferred<Account>()
    private val discovered = CompletableDeferred<List<DiscoveredServer>>()
    private val catalogUsers = mutableListOf<String>()
    private val serviceEvents = mutableListOf<String>()
    private var signedIn: Pair<String, String>? = null
    private var gatedUser = account.user.id
    private val accounts = AccountStore(object : TextPersistence {
        private var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) {
            this.value = value
        }
    })
    private var koinStarted = false

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        if (koinStarted) stopKoin()
    }

    private fun launch() {
        val catalog = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun views(account: Account): ClientResult<ItemPage> {
                catalogUsers += account.user.id
                if (account.user.id == gatedUser) homeReady.await()
                return ClientResult.Success(ItemPage(listOf(library)))
            }
        }
        val service = object : JellyfinService {
            override suspend fun probe(address: String) = ClientResult.Success(server)
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(true)
            override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> {
                signedIn = username to password
                return ClientResult.Success(account)
            }
            override fun quickConnect(server: Server): Flow<QuickConnectState> = flow {
                serviceEvents += "quick:start"
                emit(QuickConnectState.Code("123456"))
                serviceEvents += "quick:code-emitted"
                val authorized = quickAuthorized.await()
                serviceEvents += "quick:authorization-released"
                emit(QuickConnectState.Authorized(authorized))
                awaitCancellation()
            }
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        startKoin {
            modules(
                module {
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { catalog }
                    single<ServerDiscovery> {
                        object : ServerDiscovery {
                            override suspend fun discover() = discovered.await()
                        }
                    }
                    viewModel { SessionViewModel(get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server) -> QuickConnectViewModel(server, get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account, session: CatalogSession) -> CatalogHomeViewModel(account, session) }
                }
            )
        }
        koinStarted = true
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() } }
        compose.waitForIdle()
    }

    private fun key(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun waitFor(description: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeoutMillis = 5_000, condition = condition)
        } catch (failure: Throwable) {
            val tree = runCatching { compose.onRoot(useUnmergedTree = true).printToString() }
                .getOrElse { "Could not collect tree: ${it.message}" }
            throw AssertionError(
                "Waiting for $description failed.\n" +
                    "accounts=${accounts.snapshots.value}\n" +
                    "catalogUsers=$catalogUsers; serviceEvents=$serviceEvents; " +
                    "homeReleased=${homeReady.isCompleted}; quickReleased=${quickAuthorized.isCompleted}; " +
                    "activityLifecycle=${compose.activity.lifecycle.currentState}; clockMs=${compose.mainClock.currentTime}\n$tree",
                failure
            )
        }
    }

    private fun waitForText(text: String) {
        waitFor("exactly one text node '$text'") { compose.onAllNodesWithText(text).fetchSemanticsNodes().size == 1 }
    }

    private fun assertFirstHomeCardAfterDelayedLoad(expectedUser: String = account.user.id) {
        // The focused profile row can scroll the lazy header out of composition. Identify this destination
        // by its root and the real gated request, rather than requiring offscreen loading-label semantics.
        waitFor("home and suspended catalog for $expectedUser") {
            expectedUser in catalogUsers && compose.onAllNodesWithTag("home").fetchSemanticsNodes().size == 1
        }
        compose.runOnIdle { assertFalse(homeReady.isCompleted) }
        // Finish the navigation transition while the external catalog request remains suspended.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("home").assertExists()
        // Android can automatically focus the only attached target as navigation replaces the sign-in screen.
        // This is not a D-pad choice. Reproduce it deterministically while the real catalog request is suspended.
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        compose.runOnIdle { homeReady.complete(Unit) }
        waitFor("first home library card focus for $expectedUser") {
            compose.onAllNodesWithTag("home:libraries:library").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Focused) == true
        }
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
    }

    @Test
    fun passwordSignInFocusesFirstHomeCardAfterItsAsynchronousLoad() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch()
        compose.onNodeWithContentDescription("Username").assertIsFocused()
        // The regression concerns destination focus after authentication. Target the production password
        // field directly rather than depending on BasicTextField's cursor/navigation key ordering.
        compose.onNodeWithContentDescription("Password").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("Password").assertIsFocused()
        key(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Password").performTextInput("invented-password")
        compose.onNodeWithContentDescription("Password").performImeAction()
        compose.waitForIdle()
        assertFirstHomeCardAfterDelayedLoad()
        compose.runOnIdle { assertEquals("Sam" to "invented-password", signedIn) }
    }

    @Test
    fun quickConnectSignInFocusesFirstHomeCardAfterItsAsynchronousLoad() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch()
        compose.onNodeWithText("Use Quick Connect").performClick()
        waitForText("123456")
        compose.runOnIdle { quickAuthorized.complete(account) }
        compose.waitForIdle()
        assertFirstHomeCardAfterDelayedLoad()
    }

    @Test
    fun switchingAccountFocusesFirstHomeCardAfterTheNewAccountLoad() {
        gatedUser = other.user.id
        runBlocking {
            accounts.remember(other)
            accounts.remember(account)
        }
        launch()
        compose.onNodeWithTag("home:libraries:library").assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithTag("home:switch-user").assertIsFocused()
        key(Key.DirectionCenter)
        waitForText("Robin on Screen room")
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithText("Robin on Screen room").performClick()
        assertFirstHomeCardAfterDelayedLoad(other.user.id)
    }

    @Test
    fun firstDiscoveredServerGetsFocusAfterASecondWhenTheUserHasNotMoved() {
        launch()
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { discovered.complete(listOf(DiscoveredServer(server.url, server.id, server.name))) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("Screen room · https://media.example.test").assertIsFocused()
    }

    @Test
    fun discoveryDoesNotTakeFocusAfterTheUserMovesToConnect() {
        launch()
        key(Key.DirectionDown)
        // Exercise the real page's interaction handler, then identify the resulting focus target explicitly;
        // this assertion concerns discovery cancellation rather than BasicTextField's cursor key ordering.
        compose.onNodeWithText("Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Connect").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { discovered.complete(listOf(DiscoveredServer(server.url, server.id, server.name))) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("Connect").assertIsFocused()
    }

    @Test
    fun discoveryDoesNotTakeFocusWhenTheUserMovesDuringItsGracePeriod() {
        launch()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { discovered.complete(listOf(DiscoveredServer(server.url, server.id, server.name))) }
        compose.mainClock.advanceTimeBy(200)
        key(Key.DirectionDown)
        // Exercise the real page's interaction handler, then identify the resulting focus target explicitly;
        // this assertion concerns discovery cancellation rather than BasicTextField's cursor key ordering.
        compose.onNodeWithText("Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Connect").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("Connect").assertIsFocused()
    }
}
