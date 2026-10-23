/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.SearchKind
import app.pictureshow.core.SearchService
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class SearchModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val account = Account(Server("s", "Test", "https://media.example.test", "12"), User("u", "Viewer"), "token")
    private val failure = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "offline"))
    private val movie = MediaItem("movie", "Copper Observatory", "Movie")
    private val fake = FakeSearch()
    private lateinit var accounts: AccountStore
    private lateinit var session: CatalogSession

    class FakeSearch :
        CatalogService by CatalogModelsTest.FakeCatalog(),
        SearchService {
        val queries = mutableListOf<Pair<String, SearchKind>>()
        val pages = mutableListOf<Int>()
        var searchBlock: suspend (String, SearchKind) -> ClientResult<ItemPage> = { _, _ -> ClientResult.Success(ItemPage()) }
        var pageBlock: suspend (Int) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
        override suspend fun search(account: Account, query: String, kind: SearchKind): ClientResult<ItemPage> {
            queries += query to kind
            return searchBlock(query, kind)
        }
        override suspend fun item(account: Account, id: String) = ClientResult.Success(MediaItem(id, "Aster Quill", "Person"))
        override suspend fun personItems(account: Account, personId: String, startIndex: Int, limit: Int): ClientResult<ItemPage> {
            assertEquals("person", personId)
            assertEquals(60, limit)
            pages += startIndex
            return pageBlock(startIndex)
        }
    }

    private fun <T : ViewModel> keep(vm: T): T = vm.also { stores += ViewModelStore().apply { put("vm", it) } }
    private fun model() = keep(SearchViewModel(account, session, accounts))

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
        accounts = AccountStore(object : TextPersistence {
            var text: String? = null
            override suspend fun read() = text
            override suspend fun write(value: String) {
                text = value
            }
        })
        session = CatalogSession(fake, accounts)
    }

    @AfterTest
    fun teardown() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test
    fun savedEntryRestoresLatestQueryAndResultsIncludingAnIntentionalClear() = runTest(dispatcher) {
        fake.searchBlock = { query, _ -> ClientResult.Success(ItemPage(listOf(movie.copy(id = query)))) }
        val saved = SavedStateHandle()
        val vm = model()
        vm.initialize(saved, "original")
        vm.query("edited")
        advanceUntilIdle()
        val restored = model()
        restored.initialize(SavedStateHandle(mapOf("searchQuery" to saved.get<String>("searchQuery"))), "original")
        advanceUntilIdle()
        assertEquals("edited", restored.state.value.query)
        assertTrue(restored.state.value.rows.all { it.items.single().id == "edited" })
        assertTrue(restored.state.value.completed)
        vm.query("")
        val cleared = model()
        cleared.initialize(SavedStateHandle(mapOf("searchQuery" to saved.get<String>("searchQuery"))), "original")
        advanceUntilIdle()
        assertEquals("", cleared.state.value.query)
        assertTrue(cleared.state.value.rows.isEmpty())
        assertFalse(cleared.state.value.completed)
        cleared.initialize(saved, "must not reapply")
        assertEquals("", cleared.state.value.query)
    }

    @Test
    fun debounceMinimumAndParallelPerTypeQueriesUseTrimmedTerm() = runTest(dispatcher) {
        val vm = model()
        vm.query(" a ")
        advanceUntilIdle()
        assertTrue(fake.queries.isEmpty())
        vm.query("  cl ")
        advanceTimeBy(200)
        vm.query("  cloud ")
        advanceTimeBy(299)
        runCurrent()
        assertTrue(fake.queries.isEmpty())
        val gate = CompletableDeferred<Unit>()
        fake.searchBlock = { _, _ ->
            gate.await()
            ClientResult.Success(ItemPage(listOf(movie)))
        }
        advanceTimeBy(1)
        runCurrent()
        assertEquals(SearchKind.entries.map { "cloud" to it }, fake.queries)
        assertTrue(vm.state.value.loading)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(5, vm.state.value.rows.size)
    }

    @Test
    fun lateEarlierResponseNeverReplacesNewResultsAndClearCancelsInflightWork() = runTest(dispatcher) {
        val vm = model()
        val old = CompletableDeferred<Unit>()
        fake.searchBlock = { query, _ ->
            if (query == "older") withContext(NonCancellable) { old.await() }
            ClientResult.Success(ItemPage(listOf(movie.copy(id = query))))
        }
        vm.query("older")
        advanceTimeBy(300)
        runCurrent()
        vm.query("newer")
        advanceTimeBy(300)
        runCurrent()
        assertEquals("newer", vm.state.value.rows.first().items.single().id)
        old.complete(Unit)
        advanceUntilIdle()
        assertEquals("newer", vm.state.value.rows.first().items.single().id)
        var cancelled = false
        fake.searchBlock = { _, _ ->
            try {
                delay(10_000)
                ClientResult.Success(ItemPage(listOf(movie)))
            } finally {
                cancelled = true
            }
        }
        vm.query("pending")
        advanceTimeBy(300)
        runCurrent()
        vm.query("")
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals("", vm.state.value.query)
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.completed)
    }

    @Test
    fun rowOrderPartialFailureAllEmptyAndAllFailedRetryRemainDistinct() = runTest(dispatcher) {
        val vm = model()
        fake.searchBlock = { _, kind ->
            when (kind) {
                SearchKind.SHOWS -> failure
                SearchKind.COLLECTIONS -> ClientResult.Success(ItemPage())
                else -> ClientResult.Success(ItemPage(List(30) { movie.copy(id = "$it") }))
            }
        }
        vm.query("cloud")
        advanceUntilIdle()
        assertEquals(listOf("Movies", "Episodes", "People"), vm.state.value.rows.map { it.title })
        assertTrue(vm.state.value.rows.all { it.items.size == 24 })
        assertFalse(vm.state.value.allFailed)
        fake.searchBlock = { _, _ -> ClientResult.Success(ItemPage()) }
        vm.query("empty")
        advanceUntilIdle()
        assertTrue(vm.state.value.completed)
        assertTrue(vm.state.value.rows.isEmpty())
        assertFalse(vm.state.value.allFailed)
        fake.searchBlock = { _, _ -> failure }
        vm.query("offline")
        advanceUntilIdle()
        assertTrue(vm.state.value.allFailed)
        fake.searchBlock = { _, kind -> ClientResult.Success(ItemPage(if (kind == SearchKind.MOVIES) listOf(movie) else emptyList())) }
        vm.retry()
        runCurrent()
        assertEquals(listOf(movie), vm.state.value.rows.single().items)
        assertFalse(vm.state.value.allFailed)
    }

    @Test
    fun typingDoesNotRecordButSubmitAndOpeningDoAndRecentSelectionAloneDoesNot() = runTest(dispatcher) {
        accounts.remember(account)
        val vm = model()
        vm.query("cloud")
        advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("cloud"), vm.state.value.recent)
        vm.query("another", immediate = true)
        advanceUntilIdle()
        assertEquals(listOf("cloud"), vm.state.value.recent)
        vm.opened()
        advanceUntilIdle()
        assertEquals(listOf("another", "cloud"), vm.state.value.recent)
        vm.clearRecent()
        advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
        vm.query("x")
        vm.submit()
        advanceUntilIdle()
        assertTrue(vm.state.value.recent.isEmpty())
    }

    @Test
    fun personPagingGuardsConcurrencyDedupesStopsAndRetriesSameOffset() = runTest(dispatcher) {
        var failPage = true
        fake.pageBlock = { offset ->
            delay(10)
            if (offset ==
                0
            ) {
                ClientResult.Success(ItemPage(List(60) { movie.copy(id = "movie-$it") }, 62))
            } else if (failPage) {
                failure
            } else {
                ClientResult.Success(ItemPage(listOf(movie.copy(id = "movie-59"), movie.copy(id = "movie-61")), 62, 60))
            }
        }
        val vm = keep(PersonViewModel(account, MediaItem("person", "Aster Quill", "Person"), session))
        runCurrent()
        vm.nearEnd(0)
        advanceUntilIdle()
        assertEquals(listOf(0), fake.pages)
        vm.nearEnd(59)
        advanceUntilIdle()
        assertEquals("offline", vm.state.value.grid.error)
        vm.nearEnd(59)
        advanceUntilIdle()
        assertEquals(listOf(0, 60), fake.pages)
        failPage = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(0, 60, 60), fake.pages)
        assertEquals(61, vm.state.value.grid.items.size)
        vm.nearEnd(61)
        advanceUntilIdle()
        assertEquals(3, fake.pages.size)
    }
}
