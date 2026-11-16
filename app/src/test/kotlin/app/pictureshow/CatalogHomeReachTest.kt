/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule

/** HOME-09: a server out of reach keeps what Home showed; a pull holds its indicator until the read settles. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogHomeReachTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie")
    private val movies = MediaItem("movies", "Movies", "CollectionFolder", collectionType = "movies")
    private val service = CatalogModelsTest.FakeCatalog().apply {
        libraryViews = ClientResult.Success(ItemPage(listOf(movies)))
        resumeItems = ClientResult.Success(ItemPage(listOf(film)))
    }
    private val session = CatalogSession(
        service,
        AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
    )
    private val offline = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Could not reach the server."))

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun outOfReachKeepsTheRowsAndLibrariesItHad() = runTest(dispatcher) {
        val vm = CatalogHomeViewModel(account, session).also { store.put("home", it) }
        vm.reload()
        runCurrent()
        assertTrue(vm.state.value.rows.any { it.key == "watching" })
        service.libraryViews = offline
        service.resumeItems = offline
        service.nextItems = offline
        vm.reload()
        runCurrent()
        val state = vm.state.value
        assertTrue(state.unreachable)
        assertEquals(listOf(film), state.rows.first { it.key == "watching" }.items, "What was loaded earlier stays on the page")
        assertEquals(listOf(movies), state.libraries, "The Libraries tab keeps its tiles")
    }

    @Test fun aServerThatAnswersReplacesTheRows() = runTest(dispatcher) {
        val vm = CatalogHomeViewModel(account, session).also { store.put("home", it) }
        vm.reload()
        runCurrent()
        service.resumeItems = ClientResult.Success(ItemPage())
        service.libraryViews = ClientResult.Failure(ClientError(ErrorKind.SERVER, "The server refused."))
        vm.reload()
        runCurrent()
        val state = vm.state.value
        assertFalse(state.unreachable)
        assertTrue(state.rows.none { it.key == "watching" }, "A refusal or a fresh empty answer is not kept from earlier")
        assertTrue(state.libraries.isEmpty())
    }

    @Test fun aPullHoldsItsIndicatorUntilTheReadSettlesAndAnOrdinaryReadShowsNone() = runTest(dispatcher) {
        val vm = CatalogHomeViewModel(account, session).also { store.put("home", it) }
        runCurrent()
        val held = kotlinx.coroutines.CompletableDeferred<ClientResult<ItemPage>>()
        val fake = service
        val gated = object : app.pictureshow.core.CatalogService by fake {
            override suspend fun views(account: Account) = held.await()
        }
        val pulled = CatalogHomeViewModel(account, CatalogSession(gated, AccountStore(NoPersistence))).also { store.put("pulled", it) }
        runCurrent()
        assertFalse(pulled.state.value.refreshing, "Opening Home is not a pull")
        pulled.refresh()
        runCurrent()
        assertTrue(pulled.state.value.refreshing)
        held.complete(ClientResult.Success(ItemPage(listOf(movies))))
        runCurrent()
        assertFalse(pulled.state.value.refreshing)
        vm.reload()
        runCurrent()
        assertFalse(vm.state.value.refreshing)
    }

    private object NoPersistence : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    }
}
