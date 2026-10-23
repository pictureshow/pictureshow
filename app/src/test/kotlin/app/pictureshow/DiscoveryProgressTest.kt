/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientResult
import app.pictureshow.core.DiscoveredServer
import app.pictureshow.core.JellyfinService
import app.pictureshow.core.QuickConnectState
import app.pictureshow.core.Server
import app.pictureshow.core.ServerDiscovery
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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

/** The real server page lists each server as it answers, while the search is still running. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DiscoveryProgressTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val household = DiscoveredServer("https://media.example.test", "server", "Screen room")
    private val second = DiscoveredServer("https://other.example.test", "server-2", "Other room")
    private val firstAnswer = CompletableDeferred<Unit>()
    private val secondAnswer = CompletableDeferred<Unit>()
    private val windowClosed = CompletableDeferred<Unit>()
    private var answers = listOf(household, household, second)

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        stopKoin()
    }

    private fun launch() {
        val service = object : JellyfinService {
            override suspend fun probe(address: String): ClientResult<Server> =
                ClientResult.Success(Server("server", "Screen room", address, "12.0.0"))
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(false)
            override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> = error("not used")
            override fun quickConnect(server: Server): Flow<QuickConnectState> = emptyFlow()
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        val discovery = object : ServerDiscovery {
            override suspend fun discover() = error("the page asks for servers as they answer")
            override suspend fun discover(onFound: (DiscoveredServer) -> Unit): List<DiscoveredServer> {
                firstAnswer.await()
                // A server can answer once per network interface; the page lists it once.
                answers.take(2).forEach(onFound)
                secondAnswer.await()
                answers.drop(2).forEach(onFound)
                windowClosed.await()
                return answers.distinctBy { it.id }
            }
        }
        val accounts = AccountStore(
            object : TextPersistence {
                override suspend fun read(): String? = null
                override suspend fun write(value: String) = Unit
            }
        )
        startKoin {
            modules(
                module {
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { CatalogModelsTest.FakeCatalog() }
                    single<ServerDiscovery> { discovery }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                }
            )
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Pictureshow() } }
        compose.waitForIdle()
    }

    private fun count(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().size

    @Test
    fun eachServerAppearsWhenItAnswersAndTheFirstTakesFocusASecondLater() {
        launch()
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.onNodeWithText("Looking for servers…").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { firstAnswer.complete(Unit) }
        compose.mainClock.advanceTimeBy(100)
        // Listed while the search is still running, and only once although it answered twice.
        compose.onNodeWithText("Screen room · https://media.example.test").assertIsDisplayed()
        compose.onNodeWithText("Looking for servers…").assertIsDisplayed()
        assertEquals(1, count("Screen room · https://media.example.test"))
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Screen room · https://media.example.test").assertIsFocused()
        // A later server joins the list without taking focus from the first.
        compose.runOnIdle { secondAnswer.complete(Unit) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("Other room · https://other.example.test").assertIsDisplayed()
        compose.onNodeWithText("Screen room · https://media.example.test").assertIsFocused()
        compose.runOnIdle { windowClosed.complete(Unit) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Discovery complete.").assertIsDisplayed()
        assertEquals(1, count("Screen room · https://media.example.test"))
        assertEquals(1, count("Other room · https://other.example.test"))
    }

    @Test
    fun aWindowThatEndsEmptyStillSaysNoServersFound() {
        answers = emptyList()
        launch()
        compose.runOnIdle {
            firstAnswer.complete(Unit)
            secondAnswer.complete(Unit)
            windowClosed.complete(Unit)
        }
        compose.waitForIdle()
        compose.onNodeWithText("No servers found. You can enter an address above.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Server address").assertIsFocused()
    }
}
