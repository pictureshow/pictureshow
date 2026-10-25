/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onParent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, qualifiers = "w960dp-h540dp-land-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ServerHeadingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @After fun cleanup() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        stopKoin()
    }

    @Test fun largeTextStatusAndFollowingActionsKeepTheServerHeadingInView() {
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        val server = Server("server", "Fixture", "https://media.example.test", "12")
        val service = object : JellyfinService {
            override suspend fun probe(address: String) = ClientResult.Success(server)
            override suspend fun publicUsers(server: Server) = ClientResult.Success(emptyList<User>())
            override suspend fun authenticate(server: Server, username: String, password: String): ClientResult<Account> = error("Not used")
            override suspend fun quickConnectEnabled(server: Server) = ClientResult.Success(false)
            override fun quickConnect(server: Server) = emptyFlow<QuickConnectState>()
            override suspend fun me(account: Account) = ClientResult.Success(account.user)
            override suspend fun logout(account: Account) = ClientResult.Success(Unit)
        }
        startKoin {
            modules(
                module {
                    single { accounts }
                    single<JellyfinService> { service }
                    single<CatalogService> { CatalogModelsTest.FakeCatalog() }
                    single<ServerDiscovery> {
                        object : ServerDiscovery {
                            override suspend fun discover() = (1..3).map {
                                app.pictureshow.core.DiscoveredServer("https://server$it.example.test", "server-$it", "Fixture $it")
                            }
                        }
                    }
                    viewModel { SessionViewModel(get(), get()) }
                    viewModel { ServerViewModel(get(), get()) }
                }
            )
        }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                PictureshowTheme { Pictureshow() }
            }
        }
        compose.onNodeWithContentDescription("Server address").assertExists()
        val title = compose.onNodeWithText("Find your Jellyfin server")
        val initialBounds = title.getUnclippedBoundsInRoot()
        compose.onNodeWithText("Connect").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onRoot().performKeyInput { repeat(4) { pressKey(Key.DirectionDown) } }
        compose.mainClock.advanceTimeBy(1_000)
        compose.onNodeWithText("Discovery complete.").onParent().assertIsFocused().assertIsDisplayed()
        assertTrue(
            title.getUnclippedBoundsInRoot() == initialBounds,
            "The heading must stay fully visible when the status scrolls into view"
        )
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Search again").assertIsFocused().assertIsDisplayed()
        assertTrue(
            title.getUnclippedBoundsInRoot() == initialBounds,
            "Following actions remain reachable without scrolling away the heading"
        )
    }
}
