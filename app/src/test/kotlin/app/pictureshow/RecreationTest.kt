/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.MaterialTheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
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

/** Restores the production NavHost, rather than testing an isolated navigation predicate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class RecreationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private var searchEntry by mutableStateOf<String?>(null)
    private var entrySequence by mutableStateOf(0L)
    private val screenContent: @Composable () -> Unit = {
        MaterialTheme { Pictureshow(searchEntry = searchEntry, entrySequence = entrySequence, consumeSearchEntry = { searchEntry = null }) }
    }
    private val search = SearchModelsTest.FakeSearch().apply {
        searchBlock = { query, kind ->
            ClientResult.Success(
                app.pictureshow.core.ItemPage(
                    if (kind == app.pictureshow.core.SearchKind.MOVIES) {
                        listOf(app.pictureshow.core.MediaItem(query, "$query result", "Movie"))
                    } else {
                        emptyList()
                    }
                )
            )
        }
    }
    private val server = Server("server", "Screen room", "https://media.example.test", "12")
    private val account = Account(server, User("viewer", "Viewer"), "fake-token")
    private var saved: String? = null
    private var discovered = emptyList<app.pictureshow.core.DiscoveredServer>()
    private var authentication: CompletableDeferred<Account>? = null
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read() = saved
        override suspend fun write(value: String) {
            saved = value
        }
    })

    @After
    fun tearDown() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        stopKoin()
    }

    private fun launch(signedIn: Boolean = false, activityRecreation: Boolean = false) {
        if (signedIn) runBlocking { accounts.remember(account) }
        val service = object : JellyfinService {
            override suspend fun probe(address: String) = ClientResult.Success(server)
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(false)
            override suspend fun authenticate(server: Server, username: String, password: String) =
                ClientResult.Success(authentication?.await() ?: account)
            override fun quickConnect(server: Server) = emptyFlow<QuickConnectState>()
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        startKoin {
            modules(
                module {
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { search }
                    single<ServerDiscovery> {
                        object : ServerDiscovery {
                            override suspend fun discover() = discovered
                        }
                    }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (account: Account, session: CatalogSession) -> SearchViewModel(account, session, get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account, session: CatalogSession) -> CatalogHomeViewModel(account, session) }
                }
            )
        }
        if (activityRecreation) compose.setContent(screenContent) else restoration.setContent(screenContent)
        waitFor(if (signedIn) "Viewer · Screen room" else "Find your Jellyfin server")
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun click(label: String) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
    }

    private fun type(field: String, value: String) {
        compose.onNodeWithContentDescription(field).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithContentDescription(field).performTextInput(value)
    }

    // ComponentActivity is the empty host provided by ui-test-manifest. Reinstall the production
    // content in its replacement, as MainActivity.onCreate does, while Android restores its owners.
    private fun recreateActivity() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is ComponentActivity) activity.setContent(content = screenContent)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callback)
        try {
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
        } finally {
            app.unregisterActivityLifecycleCallbacks(callback)
        }
    }

    private fun externalSearch(query: String) {
        compose.runOnIdle {
            searchEntry = query
            entrySequence++
        }
        waitFor("$query result")
    }

    private fun replaceSearch(query: String) {
        compose.onNodeWithTag("search:field").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("search:field").performTextReplacement(query)
        compose.waitForIdle()
    }

    @Test
    fun clearedExternalQueryStaysEmptyAfterActivityRecreationAndFreshIntentReplacesIt() {
        launch(signedIn = true, activityRecreation = true)
        externalSearch("original")
        replaceSearch("")
        recreateActivity()
        compose.onNodeWithTag("search:field").assertTextEquals("")
        compose.onNodeWithText("original result").assertDoesNotExist()
        externalSearch("fresh")
        compose.onNodeWithTag("search:field").assertTextEquals("fresh")
        // Even the same original intent is a fresh entry, not a restored query.
        externalSearch("original")
        compose.onNodeWithTag("search:field").assertTextEquals("original")
    }

    @Test
    fun editedExternalQueryAndResultsSurviveActivityRecreation() {
        launch(signedIn = true, activityRecreation = true)
        externalSearch("original")
        replaceSearch("edited")
        waitFor("edited result")
        recreateActivity()
        compose.onNodeWithTag("search:field").assertTextEquals("edited")
        waitFor("edited result")
        compose.onNodeWithText("original result").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [24, 30])
    fun signedOutServerIsOfferedWithInitialFocusAfterRecreationAndOneOkOpensLogin() {
        discovered = listOf(app.pictureshow.core.DiscoveredServer("https://other.example.test", "other", "Other server"))
        launch(signedIn = true, activityRecreation = true)
        compose.runOnIdle { runBlocking { accounts.signOut(account.key) } }
        waitFor("Find your Jellyfin server")
        recreateActivity()
        compose.onNodeWithText("Other server · https://other.example.test").assertExists()
        compose.onNodeWithText("Sign in to Screen room").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").assertExists()
    }

    @Test
    fun addingAServerStillStartsAtTheAddressWhenNoServerWasSignedOut() {
        launch(signedIn = true)
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Who’s watching?")
        click("Add a server")
        waitFor("Find your Jellyfin server")
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.onNodeWithText("Screen room · remembered").assertExists()
    }

    @Test
    fun signedOutServerKeepsOneOkSignInWhenAnotherServersAccountRemains() {
        val other = account.copy(server = server.copy(id = "other", name = "Other server", url = "https://other.example.test"))
        runBlocking { accounts.remember(other) }
        launch(signedIn = true, activityRecreation = true)
        compose.runOnIdle { runBlocking { accounts.signOut(account.key) } }
        waitFor("Who’s watching?")
        compose.onNodeWithText("Viewer on Other server").assertExists()
        compose.onNodeWithText("Sign in another user on Screen room").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").assertExists()
    }

    @Test
    fun activityRecreationKeepsLoginPasswordAndBackStack() {
        launch(activityRecreation = true)
        type("Server address", server.url)
        click("Connect")
        waitFor("Sign in to Screen room")
        type("Username", "typed-viewer")
        type("Password", "typed-password")
        recreateActivity()
        waitFor("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").assertTextEquals("typed-viewer")
        compose.onNodeWithContentDescription("Password").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("typed-password"))
        )
        click("Back")
        waitFor("Find your Jellyfin server")
        compose.onNodeWithContentDescription("Server address").assertTextEquals(server.url)
    }

    @Test
    fun inFlightSignInCompletesThroughActivityRecreation() {
        authentication = CompletableDeferred()
        launch(activityRecreation = true)
        type("Server address", server.url)
        click("Connect")
        waitFor("Sign in to Screen room")
        type("Username", "typed-viewer")
        type("Password", "typed-password")
        click("Sign in")
        waitFor("Signing in…")
        recreateActivity()
        waitFor("Signing in…")
        compose.runOnIdle { authentication!!.complete(account) }
        waitFor("Viewer · Screen room")
    }

    @Test
    fun activityRecreationKeepsSignedInScreenAndSignOutStillResets() {
        launch(signedIn = true, activityRecreation = true)
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Who’s watching?")
        recreateActivity()
        waitFor("Who’s watching?")
        compose.runOnIdle { runBlocking { accounts.signOut(account.key) } }
        waitFor("Find your Jellyfin server")
    }

    @Test
    fun loginRouteAndUsernameSurviveSavedStateRestorationAndBackKeepsServerAddress() {
        launch()
        type("Server address", server.url)
        click("Connect")
        waitFor("Sign in to Screen room")
        type("Username", "typed-viewer")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").assertTextEquals("typed-viewer")
        click("Back")
        waitFor("Find your Jellyfin server")
        compose.onNodeWithContentDescription("Server address").assertTextEquals(server.url)
    }

    @Test
    fun signedInAccountChooserSurvivesRestorationAndRealExpiryStillResetsIt() {
        launch(signedIn = true)
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        waitFor("Who’s watching?")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Who’s watching?")
        compose.runOnIdle { runBlocking { accounts.expire(account.key, account.accessToken) } }
        waitFor("Sign in to Screen room")
    }
}
