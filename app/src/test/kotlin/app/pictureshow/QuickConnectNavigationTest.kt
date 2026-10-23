/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.ErrorKind
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
class QuickConnectNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("server", "Screen room", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("viewer", "Viewer"), "fake-token")
    private val library = MediaItem("library", "Invented Library", "CollectionFolder", isFolder = true)
    private val homeReady = CompletableDeferred<Unit>()
    private val quickAuthorized = CompletableDeferred<Account>()
    private var quickInitiations = 0
    private val polledSecrets = mutableListOf<String>()
    private val discovered = CompletableDeferred<List<DiscoveredServer>>()
    private val catalogUsers = mutableListOf<String>()
    private val serviceEvents = mutableListOf<String>()
    private var probeResult: ClientResult<Server> = ClientResult.Success(server)
    private var probeReady: CompletableDeferred<Unit>? = null
    private var publicUsers = emptyList<User>()
    private var signedIn: Pair<String, String>? = null
    private var gatedUser = account.user.id
    private var savedAccounts: String? = null
    private val persistence = object : TextPersistence {
        override suspend fun read() = savedAccounts
        override suspend fun write(value: String) {
            savedAccounts = value
        }
    }
    private val accounts = AccountStore(persistence)
    private var koinStarted = false

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        if (koinStarted) stopKoin()
    }

    private val recreationContent: @androidx.compose.runtime.Composable () -> Unit = {
        MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() }
    }

    private fun launch(forRecreation: Boolean = false) {
        val catalog = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun views(account: Account): ClientResult<ItemPage> {
                catalogUsers += account.user.id
                if (account.user.id == gatedUser) homeReady.await()
                return ClientResult.Success(ItemPage(listOf(library)))
            }
        }
        val service = object : JellyfinService {
            override suspend fun probe(address: String): ClientResult<Server> {
                probeReady?.await()
                return probeResult
            }
            override suspend fun publicUsers(server: Server) = ClientResult.Success(publicUsers)
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(true)
            override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> {
                signedIn = username to password
                return ClientResult.Success(account)
            }
            override fun quickConnect(server: Server) = quickConnect(server, null) {}
            override fun quickConnect(
                server: Server,
                pending: app.pictureshow.core.PendingQuickConnect?,
                onPending: (app.pictureshow.core.PendingQuickConnect?) -> Unit
            ): Flow<QuickConnectState> = flow {
                serviceEvents += "quick:start"
                val attempt = pending ?: app.pictureshow.core.PendingQuickConnect("123456", "fake-secret").also { quickInitiations++ }
                onPending(attempt)
                polledSecrets += attempt.secret
                emit(QuickConnectState.Code(attempt.code))
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
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server, saved: androidx.lifecycle.SavedStateHandle) ->
                        QuickConnectViewModel(server, get(), get(), saved)
                    }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account, session: CatalogSession) -> CatalogHomeViewModel(account, session) }
                }
            )
        }
        koinStarted = true
        if (forRecreation) {
            compose.setContent(recreationContent)
        } else {
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() } }
        }
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
    fun navigationSavedStateRestoresPendingSecretIntoANewViewModel() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch(forRecreation = true)
        compose.onNodeWithText("Use Quick Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("123456")
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        val oldActivity = compose.activity
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {
                if (activity is ComponentActivity) activity.setContent(content = recreationContent)
            }
            override fun onActivityDestroyed(activity: android.app.Activity) {
                // Android already saved the Activity/NavController bundles. Drop retained models too,
                // so this tests process restoration rather than a configuration change's warm store.
                if (activity === oldActivity) oldActivity.viewModelStore.clear()
            }
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityResumed(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            compose.activityRule.scenario.recreate()
            waitForText("123456")
            compose.runOnIdle {
                assertEquals(2, serviceEvents.count { it == "quick:start" })
                assertEquals(1, quickInitiations)
                assertEquals(listOf("fake-secret", "fake-secret"), polledSecrets)
            }
            compose.runOnIdle { quickAuthorized.complete(account) }
            assertFirstHomeCardAfterDelayedLoad()
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
        }
    }

    @Test
    fun backAndReturnDoesNotInitiateAnotherCode() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch()
        compose.onNodeWithText("Use Quick Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("123456")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Use Quick Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("123456")
        compose.runOnIdle { assertEquals(1, serviceEvents.count { it == "quick:start" }) }
    }

    @Test
    fun backReturnsFocusToQuickConnectAndReusesItsAttempt() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch()
        compose.onNodeWithText("Use Quick Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("123456")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Use Quick Connect").assertIsFocused()
        key(Key.DirectionCenter)
        waitForText("123456")
        compose.runOnIdle { assertEquals(1, serviceEvents.count { it == "quick:start" }) }
        compose.runOnIdle { quickAuthorized.complete(account) }
        assertFirstHomeCardAfterDelayedLoad()
    }

    @Test
    fun backgroundExpiryExplainsSignInEvenWhileTheAccountChooserIsOpen() {
        homeReady.complete(Unit)
        runBlocking { accounts.remember(account) }
        launch()
        waitForText("Viewer · Screen room")
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("Who’s watching?")
        compose.runOnIdle {
            runBlocking {
                CatalogSession(CatalogModelsTest.FakeCatalog(), accounts).call<Unit>(account) {
                    ClientResult.Failure(ClientError(ErrorKind.INVALID_CREDENTIALS, "Invalid token"))
                }
            }
        }
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Your session ended. Sign in again.").assertExists()
    }

    @Test
    fun ordinarySignOutAndFirstSignInDoNotClaimTheSessionEnded() {
        runBlocking {
            accounts.remember(account)
            accounts.signOut(account.key)
        }
        launch()
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Sign in to Screen room").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Your session ended. Sign in again.").assertDoesNotExist()
    }

    @Test
    fun freshSignInDoesNotClaimTheSessionEnded() {
        launch()
        key(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Server address").performTextInput(server.url)
        compose.onNodeWithContentDescription("Server address").performImeAction()
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Your session ended. Sign in again.").assertDoesNotExist()
    }

    @Test
    fun switchingUsersDoesNotClaimTheSessionEnded() {
        homeReady.complete(Unit)
        runBlocking { accounts.remember(account) }
        launch()
        waitForText("Viewer · Screen room")
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("Sign in another user on Screen room")
        compose.onNodeWithText("Sign in another user on Screen room").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("Sign in to Screen room")
        compose.onNodeWithText("Your session ended. Sign in again.").assertDoesNotExist()
    }
}
