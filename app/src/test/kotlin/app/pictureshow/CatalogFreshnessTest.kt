/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.BrowseQuery
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.ItemUserData
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

/** Drives retained ViewModels; no navigation recreation can hide the stale-card regression. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogFreshnessTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "token")
    private val parents = listOf("Series", "Season", "BoxSet").map { type ->
        MediaItem(type.lowercase(), type = type, userData = ItemUserData(unplayedItemCount = 6))
    }
    private val library = MediaItem("library", collectionType = "tvshows")
    private val leaf = MediaItem("episode", type = "Episode")
    private val fake = FreshCatalog()
    private lateinit var session: CatalogSession
    private lateinit var accounts: AccountStore
    private fun <T> ok(value: T) = ClientResult.Success(value)
    private fun fresh(item: MediaItem, count: Int = 4) = item.copy(userData = ItemUserData(unplayedItemCount = count, played = count == 0))
    private fun <T : ViewModel> own(vm: T) = vm.also { stores += ViewModelStore().apply { put("vm", it) } }

    private class FreshCatalog :
        CatalogService by CatalogModelsTest.FakeCatalog(),
        SearchService {
        val pages = mutableListOf<BrowseQuery>()
        val snapshots = mutableListOf<List<String>>()
        var page: suspend (BrowseQuery) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
        var snapshot: suspend (List<String>) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
        var search: suspend (String, SearchKind) -> ClientResult<ItemPage> = { _, _ -> ClientResult.Success(ItemPage()) }
        var person: suspend (Int) -> ClientResult<ItemPage> = { ClientResult.Success(ItemPage()) }
        override suspend fun items(account: Account, query: BrowseQuery): ClientResult<ItemPage> = if (query.ids.isEmpty()) {
            pages += query
            page(query)
        } else {
            snapshots += query.ids
            assertTrue(query.limit <= 100)
            snapshot(query.ids)
        }
        override suspend fun search(account: Account, query: String, kind: SearchKind) = search(query, kind)
        override suspend fun personItems(account: Account, personId: String, startIndex: Int, limit: Int) = person(startIndex)
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(dispatcher)
        accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        session = CatalogSession(fake, accounts)
    }

    @AfterTest
    fun cleanup() {
        stores.forEach(ViewModelStore::clear)
        Dispatchers.resetMain()
    }

    @Test
    fun retainedParentCardsRefreshInPlaceAfterPlaybackAndBothMutationDirections() = runTest(dispatcher) {
        fake.page = { ok(ItemPage(parents, 300)) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        for ((index, count) in listOf(4, 0, 6).withIndex()) {
            val gate = CompletableDeferred<ClientResult<ItemPage>>()
            fake.snapshot = { gate.await() }
            if (index == 0) session.refresh() else session.changed(leaf.copy(userData = ItemUserData(played = index == 1)))
            runCurrent()
            assertFalse(vm.state.value.loading, "Background counts must not publish loading")
            assertEquals(parents.map { it.id }, vm.state.value.items.map { it.id })
            gate.complete(ok(ItemPage(parents.reversed().map { fresh(it, count) })))
            advanceUntilIdle()
            assertEquals(List(3) { count }, vm.state.value.items.map { it.userData.unplayedItemCount })
            assertEquals(300, vm.state.value.total)
        }
        assertEquals(1, fake.pages.size, "No page reload or offset reset")
        assertEquals(List(3) { parents.map(MediaItem::id) }, fake.snapshots)
    }

    @Test
    fun pageFinishingAfterChangeGetsFreshCountsWithoutFightingNearEnd() = runTest(dispatcher) {
        val late = CompletableDeferred<ClientResult<ItemPage>>()
        fake.page = { if (it.startIndex == 0) ok(ItemPage(listOf(parents[0]), 3)) else withContext(NonCancellable) { late.await() } }
        fake.snapshot = { ids -> ok(ItemPage(parents.filter { it.id in ids }.map { fresh(it) })) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        vm.nearEnd(0)
        runCurrent()
        session.refresh()
        runCurrent()
        late.complete(ok(ItemPage(parents.drop(1), 3)))
        advanceUntilIdle()
        assertEquals(List(3) { 4 }, vm.state.value.items.map { it.userData.unplayedItemCount })
        repeat(3) { vm.nearEnd(2) }
        advanceUntilIdle()
        assertEquals(listOf(0, 1), fake.pages.map { it.startIndex })
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun olderSnapshotAndSortCannotReplaceNewerCountsOrCardIdentity() = runTest(dispatcher) {
        val old = CompletableDeferred<ClientResult<ItemPage>>()
        fake.page = { ok(ItemPage(if (it.descending) listOf(parents[2]) else parents, 3)) }
        fake.snapshot = { withContext(NonCancellable) { old.await() } }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        session.refresh()
        runCurrent()
        fake.snapshot = { ids -> ok(ItemPage(parents.filter { it.id in ids }.map { fresh(it, 0) })) }
        session.refresh()
        runCurrent()
        assertEquals(List(3) { 0 }, vm.state.value.items.map { it.userData.unplayedItemCount })
        vm.sort("DateCreated", true)
        runCurrent()
        old.complete(ok(ItemPage(parents.map { fresh(it, 5) })))
        advanceUntilIdle()
        assertEquals(listOf("boxset"), vm.state.value.items.map { it.id })
        assertEquals(6, vm.state.value.items.single().userData.unplayedItemCount)
    }

    @Test
    fun snapshotFailureRetainsCardsAndRetryDoesNotAppendAnotherPage() = runTest(dispatcher) {
        fake.page = { ok(ItemPage(parents, 3)) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        fake.snapshot = { ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Unavailable")) }
        session.refresh()
        advanceUntilIdle()
        assertEquals("Unavailable", vm.state.value.refreshError)
        assertEquals(parents, vm.state.value.items)
        fake.snapshot = { ok(ItemPage(parents.map { fresh(it) })) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(List(3) { 4 }, vm.state.value.items.map { it.userData.unplayedItemCount })
        assertEquals(null, vm.state.value.refreshError)
        assertEquals(1, fake.pages.size)
    }

    @Test
    fun searchParentsRefreshWithoutClearingRowsAndLateOldQueryCannotReturn() = runTest(dispatcher) {
        fake.search = { query, kind -> ok(ItemPage(if (kind == SearchKind.SHOWS) listOf(parents[0].copy(id = query)) else emptyList())) }
        val vm = own(SearchViewModel(account, session, accounts))
        vm.query("older", immediate = true)
        advanceUntilIdle()
        val old = CompletableDeferred<ClientResult<ItemPage>>()
        fake.snapshot = { withContext(NonCancellable) { old.await() } }
        session.refresh()
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals("older", vm.state.value.rows.single().items.single().id)
        vm.query("newer", immediate = true)
        runCurrent()
        fake.snapshot = { ids -> ok(ItemPage(listOf(fresh(parents[0].copy(id = ids.single()), 0)))) }
        session.changed(leaf)
        runCurrent()
        old.complete(ok(ItemPage(listOf(fresh(parents[0].copy(id = "older"))))))
        advanceUntilIdle()
        assertEquals("newer", vm.state.value.rows.single().items.single().id)
        assertEquals(0, vm.state.value.rows.single().items.single().userData.unplayedItemCount)
        assertTrue(vm.state.value.completed)
    }

    @Test
    fun changeDuringSearchAndPersonPageCannotLeaveAStaleParent() = runTest(dispatcher) {
        val searchGate = CompletableDeferred<Unit>()
        fake.search =
            { _, kind ->
                searchGate.await()
                ok(ItemPage(if (kind == SearchKind.COLLECTIONS) listOf(parents[2]) else emptyList()))
            }
        fake.person = { ok(ItemPage(listOf(parents[0]), 1)) }
        fake.snapshot = { ids -> ok(ItemPage(parents.filter { it.id in ids }.map { fresh(it) })) }
        val search = own(SearchViewModel(account, session, accounts))
        val person = own(PersonViewModel(account, MediaItem("person", type = "Person"), session))
        search.query("sample", immediate = true)
        runCurrent()
        session.refresh()
        runCurrent()
        searchGate.complete(Unit)
        advanceUntilIdle()
        assertEquals(4, search.state.value.rows.single().items.single().userData.unplayedItemCount)
        assertEquals(4, person.state.value.grid.items.single().userData.unplayedItemCount)
        assertFalse(search.state.value.loading)
        assertFalse(person.state.value.grid.loading)
    }

    @Test
    fun longLibraryRefreshBatchesOnlyLoadedIdsAndOldParentPatchesNeverReturnOnPaging() = runTest(dispatcher) {
        val cards = List(300) { parents[0].copy(id = "series-$it") }
        fake.page = { q -> ok(ItemPage(cards.drop(q.startIndex).take(60), 300)) }
        fake.snapshot = { ids -> ok(ItemPage(cards.filter { it.id in ids }.map { fresh(it) })) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        repeat(3) {
            vm.nearEnd(vm.state.value.items.lastIndex)
            advanceUntilIdle()
        }
        assertEquals(240, vm.state.value.items.size)
        session.changed(fresh(cards[0], 0))
        advanceUntilIdle()
        assertEquals(4, vm.state.value.items[0].userData.unplayedItemCount, "List projection wins on a directly changed parent")
        session.changed(leaf)
        advanceUntilIdle()
        assertEquals(listOf(100, 100, 40, 100, 100, 40), fake.snapshots.map { it.size })
        assertEquals(4, fake.pages.size)
        vm.nearEnd(239)
        advanceUntilIdle()
        assertEquals(4, vm.state.value.items[0].userData.unplayedItemCount, "A new page cannot replay an older parent patch")
        assertEquals(listOf(0, 60, 120, 180, 240), fake.pages.map { it.startIndex })
    }

    @Test
    fun searchRefreshFailureIsRetryableWithoutDiscardingRowsOrRepeatingTheQuery() = runTest(dispatcher) {
        var searches = 0
        fake.search = { _, kind ->
            searches++
            ok(ItemPage(if (kind == SearchKind.SHOWS) listOf(parents[0]) else emptyList()))
        }
        val vm = own(SearchViewModel(account, session, accounts))
        vm.query("sample", immediate = true)
        advanceUntilIdle()
        fake.snapshot = { ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Unavailable")) }
        session.refresh()
        advanceUntilIdle()
        assertEquals("Unavailable", vm.state.value.refreshError)
        assertFalse(vm.state.value.someFailed)
        assertFalse(vm.state.value.loading)
        assertEquals(parents[0], vm.state.value.rows.single().items.single())
        fake.snapshot = { ok(ItemPage(listOf(fresh(parents[0])))) }
        vm.retry()
        advanceUntilIdle()
        assertEquals(4, vm.state.value.rows.single().items.single().userData.unplayedItemCount)
        assertFalse(vm.state.value.someFailed)
        assertEquals(null, vm.state.value.refreshError)
        assertEquals(SearchKind.entries.size, searches)
    }

    @Test
    fun searchAndPersonRowsReceiveFavouriteAcknowledgementsAndLaterServerState() = runTest(dispatcher) {
        fake.search = { _, kind -> ok(ItemPage(if (kind == SearchKind.EPISODES) listOf(leaf) else emptyList())) }
        fake.person = { ok(ItemPage(listOf(leaf), 1)) }
        val search = own(SearchViewModel(account, session, accounts))
        val person = own(PersonViewModel(account, MediaItem("person", type = "Person"), session))
        search.query("sample", immediate = true)
        advanceUntilIdle()
        for (favorite in listOf(true, false)) {
            val updated = leaf.copy(userData = ItemUserData(isFavorite = favorite))
            fake.snapshot = { ok(ItemPage(listOf(updated))) }
            session.changed(updated)
            advanceUntilIdle()
            assertEquals(favorite, search.state.value.rows.single().items.single().userData.isFavorite)
            assertEquals(favorite, person.state.value.grid.items.single().userData.isFavorite)
        }
    }

    @Test
    fun snapshotFailureDoesNotBlockPagingAndOneDoubleRetryRepairsBothFailures() = runTest(dispatcher) {
        val first = listOf(parents[0])
        fake.page = { ok(ItemPage(first, 3)) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        fake.snapshot = { ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Counts unavailable")) }
        session.refresh()
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertEquals("Counts unavailable", vm.state.value.refreshError)
        fake.page = { ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Page unavailable")) }
        vm.nearEnd(0)
        advanceUntilIdle()
        assertEquals(listOf(0, 1), fake.pages.map { it.startIndex }, "Snapshot failure does not gate paging")
        assertEquals("Page unavailable", vm.state.value.error)
        val snapshotGate = CompletableDeferred<ClientResult<ItemPage>>()
        val pageGate = CompletableDeferred<ClientResult<ItemPage>>()
        fake.snapshot = { snapshotGate.await() }
        fake.page = { pageGate.await() }
        vm.retry()
        vm.retry()
        runCurrent()
        assertEquals(listOf(0, 1, 1), fake.pages.map { it.startIndex })
        assertEquals(2, fake.snapshots.size, "The second Retry does not duplicate an active snapshot")
        snapshotGate.complete(ok(ItemPage(first.map { fresh(it) })))
        pageGate.complete(ok(ItemPage(parents.drop(1).map { fresh(it) }, 3)))
        advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertEquals(null, vm.state.value.refreshError)
        assertEquals(List(3) { 4 }, vm.state.value.items.map { it.userData.unplayedItemCount })
    }

    @Test
    fun directLeafAcknowledgementWinsOnlyItsOwnRevisionWhenListProjectionDisagrees() = runTest(dispatcher) {
        val other = leaf.copy(id = "other")
        fake.page = { ok(ItemPage(listOf(leaf, other), 2)) }
        fake.snapshot = { ok(ItemPage(listOf(leaf, other))) }
        val vm = own(CatalogGridViewModel(account, library, session))
        advanceUntilIdle()
        session.changed(leaf.copy(userData = ItemUserData(played = true)))
        advanceUntilIdle()
        assertTrue(vm.state.value.items[0].userData.played, "Same-revision acknowledged mutation remains immediate")
        session.changed(other.copy(userData = ItemUserData(isFavorite = true)))
        advanceUntilIdle()
        assertFalse(vm.state.value.items[0].userData.played, "Older acknowledgement cannot mask a later list read")
        assertTrue(vm.state.value.items[1].userData.isFavorite)
    }

    @Test
    fun parentMutationRefreshKeepsSelectedSeasonAndEpisodeRowsWhileReadsArePending() = runTest(dispatcher) {
        val detailFake = CatalogModelsTest.FakeCatalog()
        val detailSession = CatalogSession(detailFake, accounts)
        val first = parents[1].copy(id = "first", seriesId = parents[0].id)
        val second = first.copy(id = "second")
        detailFake.detail = ok(parents[0])
        detailFake.seasonItems = ok(ItemPage(listOf(first, second)))
        detailFake.episodeBlock = { _, _ -> ok(ItemPage(listOf(leaf))) }
        val vm = own(CatalogDetailViewModel(account, parents[0], detailSession))
        advanceUntilIdle()
        vm.season(second)
        advanceUntilIdle()
        val gate = CompletableDeferred<ClientResult<MediaItem>>()
        detailFake.detailBlock = { gate.await() }
        detailSession.changed(leaf.copy(userData = ItemUserData(played = true)))
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals("second", vm.state.value.selectedSeasonId)
        assertEquals(listOf("episode"), vm.state.value.episodes.map { it.id })
        gate.complete(ok(fresh(parents[0])))
        advanceUntilIdle()
        assertEquals("second", vm.state.value.selectedSeasonId)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun retainedCollectionDetailAndSeriesSeasonRowRefreshAfterChildChanges() = runTest(dispatcher) {
        val detailFake = CatalogModelsTest.FakeCatalog()
        val detailSession = CatalogSession(detailFake, accounts)
        detailFake.detail = ok(parents[2])
        val collection = own(CatalogDetailViewModel(account, parents[2], detailSession))
        advanceUntilIdle()
        detailFake.detail = ok(fresh(parents[2]))
        detailSession.changed(leaf)
        advanceUntilIdle()
        assertEquals(4, collection.state.value.item!!.userData.unplayedItemCount)
        detailFake.detail = ok(parents[0])
        detailFake.seasonItems = ok(ItemPage(listOf(parents[1])))
        val series = own(CatalogDetailViewModel(account, parents[0], detailSession))
        advanceUntilIdle()
        detailFake.seasonItems = ok(ItemPage(listOf(fresh(parents[1], 0))))
        detailSession.changed(leaf.copy(userData = ItemUserData(played = true)))
        advanceUntilIdle()
        assertEquals(0, series.state.value.seasons.single().userData.unplayedItemCount)
    }
}
