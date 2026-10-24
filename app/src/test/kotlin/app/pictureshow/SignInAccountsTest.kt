/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertTrue
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

/** Sign-in messages, arrival/return focus, account removal and real navigation stack behavior. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SignInAccountsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = Server("server", "Screen room", "https://media.example.test", "12.0.0")
    private val account = Account(server, User("viewer-one", "Viewer one"), "fake-token")
    private val other = account.copy(user = User("viewer-two", "Viewer two"))
    private val library = MediaItem("library", "Invented Library", "CollectionFolder", isFolder = true)
    private val homeReady = CompletableDeferred<Unit>()
    private val quickAuthorized = CompletableDeferred<Account>()
    private val discovered = CompletableDeferred<List<DiscoveredServer>>()
    private val catalogUsers = mutableListOf<String>()
    private val catalogTokens = mutableListOf<String>()
    private val serviceEvents = mutableListOf<String>()
    private var probeResult: ClientResult<Server> = ClientResult.Success(server)
    private var probeReady: CompletableDeferred<Unit>? = null
    private var publicUsers = emptyList<User>()
    private var authResult: ClientResult<Account> = ClientResult.Success(account)
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
    private var textScale = 1f

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
                catalogTokens += account.accessToken
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
                return authResult
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
                    viewModel { SessionViewModel(get(), get()) }
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
        if (forRecreation) {
            compose.setContent(recreationContent)
        } else {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, textScale)) {
                    MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() }
                }
            }
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

    private fun click(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
    }

    private fun chooser() {
        runBlocking { accounts.remember(account) }
        homeReady.complete(Unit)
        launch()
        waitForText("Viewer one · Screen room")
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        waitForText("Who’s watching?")
    }

    private fun assertStatus(text: String) {
        compose.onNodeWithText(text).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText(text).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        compose.onNodeWithText(text).onParent().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        compose.onNodeWithText(text).onParent().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text).onParent().assertIsFocused()
    }

    @Test
    fun emptyDiscoveryStatusIsLiveAndReachableByDpad() {
        discovered.complete(emptyList())
        launch()
        waitForText("No servers found. You can enter an address above.")
        assertStatus("No servers found. You can enter an address above.")
        compose.onNodeWithText("Connect").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun connectionErrorIsLiveAndReachableByDpad() {
        textScale = 1.3f
        val failure = "Could not reach the server. Check your connection and try again."
        discovered.complete(emptyList())
        probeResult = ClientResult.Failure(ClientError(ErrorKind.NETWORK, failure))
        launch()
        click("Connect")
        waitForText(failure)
        assertStatus(failure)
        compose.onNodeWithText("Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionDown)
        compose.onNodeWithText(failure).onParent().assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithText("No servers found. You can enter an address above.").onParent().assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithText(failure).onParent().assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithText("Connect").assertIsFocused()
    }

    @Test
    fun refusedSignInIsLiveAndReachableAfterSignIn() {
        textScale = 1.3f
        val refusal = "Sign-in was refused. Check your username and password or sign in again."
        authResult = ClientResult.Failure(ClientError(ErrorKind.INVALID_CREDENTIALS, refusal))
        chooser()
        click("Sign in another user on Screen room")
        click("Use Quick Connect")
        waitForText("123456")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        compose.onNodeWithContentDescription("Username").performTextInput("viewer")
        click("Sign in")
        waitForText(refusal)
        compose.onNodeWithText("Sign in").assertIsFocused()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion))
        assertStatus(refusal)
        click("Sign in")
        key(Key.DirectionDown)
        compose.onNodeWithText(refusal).onParent().assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithText("Use Quick Connect").assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithText(refusal).onParent().assertIsFocused()
        key(Key.DirectionUp)
        compose.onNodeWithText("Sign in").assertIsFocused()
    }

    @Test
    fun quickConnectCodeGetsArrivalFocusAndWaitingStatusCanBeRead() {
        chooser()
        click("Sign in another user on Screen room")
        click("Use Quick Connect")
        waitForText("123456")
        compose.onNodeWithContentDescription("Quick Connect code 1 2 3 4 5 6")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
            .onParent().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)).assertIsFocused()
        key(Key.DirectionDown)
        compose.onNodeWithText("Waiting for approval. Expired codes are renewed automatically.").onParent().assertIsFocused()
        assertStatus("Waiting for approval. Expired codes are renewed automatically.")
    }

    @Test
    fun remoteFieldsExposeEditableSemanticsBeforeOpeningKeyboard() {
        launch()
        compose.onNodeWithContentDescription("Server address")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        // Accessibility can set text without a hardware activation; keyboard remains an explicit action.
        compose.onNodeWithContentDescription("Server address").performSemanticsAction(SemanticsActions.SetText) {
            assertTrue(it(androidx.compose.ui.text.AnnotatedString(server.url)))
        }
        compose.onNodeWithContentDescription("Server address").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(server.url)
            )
        )
        compose.onNodeWithContentDescription("Server address").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        compose.onNodeWithContentDescription("Server address").performImeAction()
        waitForText("Sign in to Screen room")
        listOf("Username", "Password").forEach {
            compose.onNodeWithContentDescription(it).assert(SemanticsMatcher.expectValue(SemanticsProperties.IsEditable, true))
        }
        compose.onNodeWithContentDescription("Password").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    }

    @Test
    fun backFromLoginRestoresTheEntryThatOpenedIt() {
        chooser()
        click("Sign in another user on Screen room")
        waitForText("Sign in to Screen room")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Who’s watching?")
        compose.onNodeWithText("Sign in another user on Screen room").assertIsFocused()
    }

    @Test
    fun rememberedAccountsReturnsToOneChooserAndDropsStaleServerPages() {
        chooser()
        repeat(3) {
            click("Add a server")
            waitForText("Find your Jellyfin server")
            key(Key.DirectionCenter)
            compose.onNodeWithContentDescription("Server address").performTextInput("bad.example.test")
            click("Remembered accounts")
            waitForText("Who’s watching?")
        }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Viewer one · Screen room")
        // A new Add a server is fresh, rather than a retained bad-address entry.
        compose.onNodeWithTag("home:switch-user").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        key(Key.DirectionCenter)
        click("Add a server")
        compose.onNodeWithContentDescription("Server address").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString("")
            )
        )
    }

    @Test
    fun expiredCurrentAccountCanReturnToChooserRetryAndBeRemoved() {
        runBlocking { accounts.remember(account.copy(accessToken = "", expired = true)) }
        launch()
        waitForText("Sign in to Screen room")
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForText("Who’s watching?")
        click("Viewer one on Screen room")
        waitForText("Sign in to Screen room")
        compose.onNodeWithContentDescription("Username").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText,
                androidx.compose.ui.text.AnnotatedString(account.user.name)
            )
        )
        click("Back")
        waitForText("Who’s watching?")
        click("Remove Viewer one on Screen room")
        click("Remove account")
        waitForText("Find your Jellyfin server")
        assertTrue(accounts.snapshots.value.accounts.isEmpty())
        assertEquals(listOf(server), accounts.snapshots.value.rememberedServers)
    }

    @Test
    fun removeAccountRequiresConfirmationAndKeepsOtherAccounts() {
        runBlocking { accounts.remember(other) }
        chooser()
        click("Remove Viewer two on Screen room")
        waitForText("Remove remembered account?")
        compose.onNodeWithText("Cancel").assertIsFocused()
        click("Cancel")
        compose.onNodeWithText("Remove Viewer two on Screen room").assertIsFocused()
        assertEquals(2, accounts.snapshots.value.accounts.size)
        click("Remove Viewer two on Screen room")
        click("Remove account")
        waitForText("Who’s watching?")
        assertEquals(listOf(account), accounts.snapshots.value.accounts)
        compose.onNodeWithText("Remove Viewer two on Screen room").assertDoesNotExist()
    }

    @Test
    fun removeServerExplainsAllAccountsAndKeepsAnotherServer() {
        val elsewhere = other.copy(server = server.copy(id = "elsewhere", url = "https://other.example.test", name = "Other room"))
        runBlocking {
            accounts.remember(elsewhere)
            accounts.remember(other)
        }
        chooser()
        click("Remove server Screen room")
        waitForText("Remove server?")
        compose.onNodeWithText("This removes Screen room and 2 remembered accounts from this TV.").assertExists()
        click("Remove server")
        waitForText("Who’s watching?")
        assertEquals(listOf(elsewhere), accounts.snapshots.value.accounts)
        assertEquals(listOf(elsewhere.server), accounts.snapshots.value.rememberedServers)
    }

    @Test
    fun quickConnectAsCurrentAccountLeavesCodePageEvenWhenTokenIsUnchanged() = sameAccountQuickConnect(account)

    @Test
    fun quickConnectAsCurrentAccountWithNewTokenLeavesNoSignInBackStack() =
        sameAccountQuickConnect(account.copy(accessToken = "replacement-token"))

    private fun sameAccountQuickConnect(authorized: Account) {
        chooser()
        click("Sign in another user on Screen room")
        click("Use Quick Connect")
        waitForText("123456")
        quickAuthorized.complete(authorized)
        waitForText("Viewer one · Screen room")
        compose.onNodeWithText("123456").assertDoesNotExist()
        assertEquals(authorized, accounts.snapshots.value.currentAccount)
        assertEquals(authorized.accessToken, catalogTokens.last(), "Home must load with the newly saved credential")
        // A completed sign-in installs Home as the root, so Back cannot reveal login or its code.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Sign in to Screen room").assertDoesNotExist()
        compose.onNodeWithText("123456").assertDoesNotExist()
    }
}
