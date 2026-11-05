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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemPage
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.MediaItem
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.SearchKind
import app.pictureshow.core.SearchService
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import app.pictureshow.core.UserConfiguration
import app.pictureshow.core.UserConfigurationService
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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

/** Drives the shipping touch NavHost and retained models: these tests fail if route restoration bootstraps home. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w400dp-h850dp-port-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TouchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val screenContent: @Composable () -> Unit = {
        TouchTheme {
            TouchPictureshow(searchEntry = searchEntry, entrySequence = sequence, consumeSearchEntry = { searchEntry = null })
        }
    }
    private var searchEntry by mutableStateOf<String?>(null)
    private var sequence by mutableStateOf(0L)
    private val server = Server("test-server", "Test server", "https://media.example.test", "12")
    private val account = Account(server, User("viewer", "Viewer"), "test-token")
    private val otherAccount = Account(server, User("other-viewer", "Other viewer"), "other-test-token")
    private var authenticatedAccount = account
    private var approveQuickConnect = false
    private val authenticatedInput = mutableListOf<Pair<String, String>>()
    private val homeAccounts = mutableListOf<String>()
    private val homeSessions = mutableListOf<Account>()
    private val searchSessions = mutableListOf<Account>()
    private val itemReads = mutableListOf<String>()
    private var persisted: String? = null
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read() = persisted
        override suspend fun write(value: String) {
            persisted = value
        }
    })
    private val catalog = object : CatalogService by CatalogModelsTest.FakeCatalog(), SearchService {
        override suspend fun views(account: Account): ClientResult<ItemPage> {
            homeAccounts += account.key
            homeSessions += account
            return ClientResult.Success(ItemPage())
        }
        override suspend fun search(account: Account, query: String, kind: SearchKind): ClientResult<ItemPage> {
            searchSessions += account
            return ClientResult.Success(
                ItemPage(if (kind == SearchKind.MOVIES) listOf(MediaItem(query, "$query result", "Movie")) else emptyList())
            )
        }
        override suspend fun item(account: Account, id: String): ClientResult<MediaItem> {
            itemReads += id
            return ClientResult.Success(MediaItem(id, "$id result", "Movie"))
        }
        override suspend fun personItems(account: Account, personId: String, startIndex: Int, limit: Int) = ClientResult.Success(ItemPage())
    }

    @After
    fun tearDown() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        stopKoin()
    }

    private fun launch(signedIn: Boolean = false, expired: Boolean = false, activityRecreation: Boolean = false) {
        if (signedIn || expired) {
            runBlocking {
                accounts.remember(account)
                if (expired) accounts.expire(account.key, account.accessToken)
            }
        }
        val service = object : JellyfinService {
            override suspend fun probe(address: String) = ClientResult.Success(server)
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(true)
            override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> {
                authenticatedInput += username to password
                return ClientResult.Success(authenticatedAccount)
            }
            override fun quickConnect(server: Server) = flow {
                emit(QuickConnectState.Code("123456"))
                if (approveQuickConnect) emit(QuickConnectState.Authorized(account))
            }
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        startKoin {
            modules(
                module {
                    single { inertRemoteController() }
                    single { inertCastSender() }
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { catalog }
                    single {
                        PlaybackPreferencesStore(object : DataStore<Preferences> {
                            private val values = MutableStateFlow(emptyPreferences())
                            override val data = values
                            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                                transform(values.value).also { values.value = it }
                        })
                    }
                    single<UserConfigurationService> {
                        object : UserConfigurationService {
                            override suspend fun configuration(account: Account) =
                                ClientResult.Success(UserConfiguration(JsonObject(emptyMap())))
                            override suspend fun saveConfiguration(account: Account, configuration: UserConfiguration) =
                                ClientResult.Success(Unit)
                        }
                    }
                    single<ServerDiscovery> {
                        object : ServerDiscovery {
                            override suspend fun discover() = emptyList<app.pictureshow.core.DiscoveredServer>()
                        }
                    }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                    viewModel { AccountsViewModel(get()) }
                    viewModel { (server: Server) -> LoginViewModel(server, get(), get()) }
                    viewModel { (server: Server, saved: SavedStateHandle) ->
                        QuickConnectViewModel(server, get(), get(), saved)
                    }
                    viewModel { (account: Account) -> HomeViewModel(account, get(), get()) }
                    viewModel { (account: Account) ->
                        PlaybackSettingsViewModel(account, get(), get(), get())
                    }
                    viewModel { (account: Account, session: CatalogSession) ->
                        CatalogHomeViewModel(account, session)
                    }
                    viewModel { (account: Account, session: CatalogSession) ->
                        SearchViewModel(account, session, get())
                    }
                    viewModel { (account: Account, item: MediaItem, session: CatalogSession) ->
                        CatalogDetailViewModel(account, item, session)
                    }
                }
            )
        }
        if (activityRecreation) compose.setContent(screenContent) else restoration.setContent(screenContent)
        waitFor(
            if (signedIn) {
                "What would you like to watch?"
            } else if (expired) {
                "Sign in to Test server"
            } else {
                "Welcome to Pictureshow"
            }
        )
    }

    private fun waitFor(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun waitForCard(label: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun back() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun assertInput(tag: String, value: String) {
        compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(value)))
    }

    private fun recreateActivity() {
        val application = ApplicationProvider.getApplicationContext<Application>()
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
        application.registerActivityLifecycleCallbacks(callback)
        try {
            compose.activityRule.scenario.recreate()
            compose.waitForIdle()
        } finally {
            application.unregisterActivityLifecycleCallbacks(callback)
        }
    }

    private fun openLogin() {
        compose.onNodeWithTag("touch:server-address").performTextReplacement(server.url)
        compose.onNodeWithText("Connect").performClick()
        waitFor("Sign in to Test server")
    }

    @Test
    fun loginRouteAndTypedUsernameSurviveRestorationAndBackPreservesAddress() {
        launch()
        openLogin()
        compose.onNodeWithTag("touch:username").performTextReplacement("typed-viewer")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("Sign in to Test server")
        assertInput("touch:username", "typed-viewer")
        back()
        waitFor("Welcome to Pictureshow")
        assertInput("touch:server-address", server.url)
    }

    @Test
    fun realActivityRecreationRetainsLoginUsernamePasswordAndRoute() {
        launch(activityRecreation = true)
        openLogin()
        compose.onNodeWithTag("touch:username").performTextReplacement("typed-viewer")
        compose.onNodeWithTag("touch:password").performTextReplacement("fixture-password")
        recreateActivity()
        waitFor("Sign in to Test server")
        assertInput("touch:username", "typed-viewer")
        // Inspect only a synthetic fixture's editable semantic value, never an actual credential.
        assertInput("touch:password", "fixture-password")
    }

    @Test
    fun quickConnectBackReturnsToTypedLoginInsteadOfDroppingTheSignInFlow() {
        launch()
        openLogin()
        compose.onNodeWithTag("touch:username").performTextReplacement("typed-viewer")
        compose.onNodeWithText("Use Quick Connect").performScrollTo().performClick()
        waitFor("Quick Connect")
        compose.onNodeWithTag("touch:quick-code").assertExists()
        back()
        waitFor("Sign in to Test server")
        assertInput("touch:username", "typed-viewer")
    }

    @Test
    fun searchDetailSurvivesRestorationAndSystemBackReturnsToEditedQuery() {
        launch(signedIn = true)
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("touch:search:query").performTextReplacement("first")
        waitForCard("first result")
        compose.onNodeWithTag("touch:search:query").performTextReplacement("edited")
        waitForCard("edited result")
        compose.onNodeWithContentDescription("edited result").performClick()
        waitFor("edited result")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("edited result")
        back()
        assertInput("touch:search:query", "edited")
        waitForCard("edited result")
    }

    @Test
    fun clearedExternalSearchStaysClearedAfterRestorationAndFreshIntentReplacesIt() {
        launch(signedIn = true)
        compose.runOnIdle {
            searchEntry = "original"
            sequence++
        }
        waitForCard("original result")
        compose.onNodeWithTag("touch:search:query").performTextReplacement("")
        restoration.emulateSavedInstanceStateRestore()
        assertInput("touch:search:query", "")
        compose.onNodeWithContentDescription("original result").assertDoesNotExist()
        compose.runOnIdle {
            searchEntry = "fresh"
            sequence++
        }
        waitForCard("fresh result")
        assertInput("touch:search:query", "fresh")
    }

    @Test
    fun successfulPasswordSignInInstallsAccountAndLeavesLoginForHome() {
        launch()
        openLogin()
        compose.onNodeWithTag("touch:username").performTextReplacement("Viewer")
        compose.onNodeWithTag("touch:password").performTextReplacement("fixture-password")
        compose.onNodeWithText("Sign in", substring = false).performScrollTo().performClick()
        waitFor("What would you like to watch?")
        compose.onNodeWithTag("touch:username").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("Viewer" to "fixture-password"), authenticatedInput)
            assertEquals(account.key, accounts.snapshots.value.currentKey)
            assertEquals(account.key, homeAccounts.last())
        }
        compose.onNodeWithText("Search").performClick()
        back()
        waitFor("What would you like to watch?")
    }

    @Test
    fun approvedQuickConnectInstallsAccountAndLeavesCodeForHome() {
        approveQuickConnect = true
        launch()
        openLogin()
        compose.onNodeWithText("Use Quick Connect").performScrollTo().performClick()
        waitFor("What would you like to watch?")
        compose.onNodeWithTag("touch:quick-code").assertDoesNotExist()
        compose.onNodeWithTag("touch:username").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(authenticatedInput.isEmpty(), "Quick Connect must not use password authentication")
            assertEquals(account.key, accounts.snapshots.value.currentKey)
            assertEquals(account.key, homeAccounts.last())
        }
    }

    @Test
    fun switchingRememberedAccountInstallsItsSessionAndCannotRestorePreviousAccountsSearchOrDetail() {
        runBlocking { accounts.remember(otherAccount) }
        launch(signedIn = true)
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("touch:search:query").performTextReplacement("private-query")
        waitForCard("private-query result")
        compose.onNodeWithContentDescription("private-query result").performClick()
        waitFor("private-query result")
        // Selecting Settings saves the old tab's stack; identity replacement must clear that saved stack too.
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Accounts").performScrollTo().performClick()
        compose.onNodeWithText("Switch to Other viewer").performScrollTo().performClick()
        waitFor("What would you like to watch?")
        compose.runOnIdle {
            assertEquals(otherAccount.key, accounts.snapshots.value.currentKey)
            assertEquals(otherAccount.key, homeAccounts.last())
        }
        compose.onNodeWithText("Search").performClick()
        assertInput("touch:search:query", "")
        compose.onNodeWithContentDescription("private-query result").assertDoesNotExist()
        compose.onNodeWithText("private-query result").assertDoesNotExist()
        back()
        waitFor("What would you like to watch?")
    }

    // The arrival read owns the page's budget; a second read at RESUMED would cancel it, and later returns would restart it.
    @Test
    fun detailReadsOnceOnArrival() {
        launch(signedIn = true)
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("touch:search:query").performTextReplacement("arrival")
        waitForCard("arrival result")
        compose.onNodeWithContentDescription("arrival result").performClick()
        compose.waitUntil(5_000) { itemReads.isNotEmpty() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf("arrival"), itemReads) }
    }

    // Touch used to reload on every RESUMED, which read home twice on arrival; TV keeps its first read and refreshes after a real return.
    @Test
    fun homeReadsOnceOnArrivalAndAgainWhenTheViewerComesBack() {
        launch(signedIn = true)
        waitFor("What would you like to watch?")
        compose.runOnIdle { assertEquals(1, homeAccounts.size) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitForIdle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { homeAccounts.size == 2 }
    }

    @Test
    fun sameUserReauthenticationClearsSavedSearchAndUsesTheReplacementCredentials() {
        val replacement = account.copy(accessToken = "replacement-test-token", deviceId = "replacement-test-device")
        authenticatedAccount = replacement
        launch(signedIn = true)
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("touch:search:query").performTextReplacement("old-session")
        waitForCard("old-session result")
        compose.runOnIdle { assertEquals(account, searchSessions.last()) }
        compose.onNodeWithContentDescription("old-session result").performClick()
        waitFor("old-session result")
        // Settings saves Search and detail with ViewModels that captured the original Account.
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Accounts").performScrollTo().performClick()
        compose.onNodeWithText("Sign in another user on Test server").performScrollTo().performClick()
        waitFor("Sign in to Test server")
        compose.onNodeWithTag("touch:username").performTextReplacement("Viewer")
        compose.onNodeWithTag("touch:password").performTextReplacement("replacement-fixture-password")
        compose.onNodeWithText("Sign in", substring = false).performScrollTo().performClick()
        waitFor("What would you like to watch?")
        compose.runOnIdle {
            assertEquals(listOf("Viewer" to "replacement-fixture-password"), authenticatedInput)
            assertEquals(account.key, accounts.snapshots.value.currentKey)
            assertEquals(replacement, accounts.snapshots.value.currentAccount)
            assertEquals(replacement, homeSessions.last())
        }
        compose.onNodeWithText("Search").performClick()
        assertInput("touch:search:query", "")
        compose.onNodeWithContentDescription("old-session result").assertDoesNotExist()
        compose.onNodeWithText("old-session result").assertDoesNotExist()
        compose.runOnIdle { searchSessions.clear() }
        compose.onNodeWithTag("touch:search:query").performTextReplacement("new-session")
        waitForCard("new-session result")
        compose.runOnIdle {
            assertTrue(searchSessions.isNotEmpty(), "The replacement search must reach the catalog")
            assertTrue(searchSessions.all { it == replacement }, "Every new search must use the replacement token and device")
        }
        back()
        waitFor("What would you like to watch?")
    }

    @Test
    fun expiredLoginAtGraphRootBackOffersAccountManagement() {
        launch(expired = true)
        back()
        waitFor("Accounts")
        compose.onNodeWithText("Sign in again as Viewer").assertExists()
    }

    @Test
    fun librariesTabReturnsHomeOnSystemBackAfterRestoration() {
        launch(signedIn = true)
        compose.onNodeWithText("Libraries").performClick()
        waitFor("No libraries are available for this account.")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("No libraries are available for this account.")
        back()
        waitFor("What would you like to watch?")
    }
}
