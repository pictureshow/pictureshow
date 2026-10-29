/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ApiTimeouts
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogRetryBudgetTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val item = MediaItem("episode", "Episode", "Episode")
    private val service = CatalogModelsTest.FakeCatalog()
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    })
    private val session = CatalogSession(service, accounts)
    private val failure = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "First read failed"))

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun backgroundRefreshInheritsTheRetryDeadlineAndASecondRetryWorks() = backgroundReplacement(true)

    @Test fun retainedContainerRefreshKeepsRetryWorkingAndInheritsItsDeadline() = backgroundReplacement(false)

    private fun backgroundReplacement(reportRefresh: Boolean) = runTest(dispatcher) {
        val screenItem = if (reportRefresh) item else MediaItem("series", "Series", "Series")
        service.detail = failure
        val vm = CatalogDetailViewModel(account, screenItem, session).also { store.put("detail", it) }
        runCurrent()
        val obsolete = CompletableDeferred<ClientResult<MediaItem>>()
        val replacement = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            reads++
            withContext(NonCancellable) { if (reads == 1) obsolete.await() else replacement.await() }
        }
        vm.reload()
        runCurrent()
        advanceTimeBy(ApiTimeouts.REQUEST_MILLIS - 700)
        if (reportRefresh) session.refresh() else session.changed(screenItem)
        runCurrent()
        assertEquals(2, reads, "Background refresh must still replace the older read")
        assertTrue(vm.state.value.loading, "Background refresh cannot make a working Retry look idle")
        advanceTimeBy(700)
        runCurrent()
        assertFalse(vm.state.value.loading, "Retry must end within its original budget")
        assertNotNull(vm.state.value.error)
        service.detailBlock = null
        val fresh = screenItem.copy(name = "Fresh after second press")
        service.detail = ClientResult.Success(fresh)
        vm.reload()
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        assertEquals(fresh, vm.state.value.item)
        obsolete.complete(ClientResult.Success(item.copy(name = "Obsolete")))
        replacement.complete(ClientResult.Success(item.copy(name = "Also expired")))
        runCurrent()
        assertEquals(fresh, vm.state.value.item, "Late canceled reads cannot overwrite the second Retry")
    }

    @Test fun retryBudgetIncludesTheDependentSeasonRead() = runTest(dispatcher) {
        val season = MediaItem("season", "Season", "Season", seriesId = "series")
        service.detail = failure
        val vm = CatalogDetailViewModel(account, season, session).also { store.put("detail", it) }
        runCurrent()
        val episodes = CompletableDeferred<ClientResult<ItemPage>>()
        service.detailBlock = {
            kotlinx.coroutines.delay(20_000)
            ClientResult.Success(season)
        }
        service.episodeBlock = { _, _ -> withContext(NonCancellable) { episodes.await() } }
        vm.reload()
        runCurrent()
        advanceTimeBy(ApiTimeouts.REQUEST_MILLIS)
        runCurrent()
        assertFalse(vm.state.value.loading, "Dependent reads share the press budget")
        assertNotNull(vm.state.value.error)
        episodes.complete(ClientResult.Success(ItemPage(listOf(item))))
        runCurrent()
        assertTrue(vm.state.value.episodes.isEmpty(), "The expired season response is superseded")
        assertNotNull(vm.state.value.error)
    }

    @Test fun aNewRetryAfterAPartialSeriesFailureGetsItsOwnFullBudget() = runTest(dispatcher) {
        val series = MediaItem("series", "Series", "Series")
        val season = MediaItem("season", "Season", "Season", seriesId = "series")
        service.detail = failure
        val vm = CatalogDetailViewModel(account, series, session).also { store.put("detail", it) }
        runCurrent()
        service.detail = ClientResult.Success(series)
        service.nextItems = failure
        service.seasonItems = ClientResult.Success(ItemPage(listOf(season)))
        val heldEpisodes = CompletableDeferred<ClientResult<ItemPage>>()
        service.episodeBlock = { _, _ -> heldEpisodes.await() }
        vm.reload()
        runCurrent()
        assertNotNull(vm.state.value.error, "A dependent failure offers Retry even before episodes finish")
        advanceTimeBy(25_000)
        service.nextItems = ClientResult.Success(ItemPage(emptyList()))
        vm.reload()
        runCurrent()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(vm.state.value.loading, "The second press owns a new deadline")
        assertNull(vm.state.value.error)
        heldEpisodes.complete(ClientResult.Success(ItemPage(listOf(item))))
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
        advanceTimeBy(ApiTimeouts.REQUEST_MILLIS)
        runCurrent()
        assertNull(vm.state.value.error, "A successful read cancels its deadline")
    }

    @Test fun aSeasonSelectionCannotSettleRetryBeforeTheParentReadsFinish() = seasonSelectionWhileParentIsHeld(false)

    @Test fun aFailedSeasonSelectionCannotCancelTheParentReadDeadline() = seasonSelectionWhileParentIsHeld(true)

    private fun seasonSelectionWhileParentIsHeld(episodeFails: Boolean) = runTest(dispatcher) {
        val series = MediaItem("series", "Series", "Series")
        val selected = MediaItem("chosen", "Chosen season", "Season", seriesId = "series")
        service.detail = failure
        val vm = CatalogDetailViewModel(account, series, session).also { store.put("detail", it) }
        runCurrent()
        val parentSeasons = CompletableDeferred<ClientResult<ItemPage>>()
        service.detail = ClientResult.Success(series)
        service.seasonsBlock = { withContext(NonCancellable) { parentSeasons.await() } }
        service.episodeBlock = { _, _ -> if (episodeFails) failure else ClientResult.Success(ItemPage(listOf(item))) }
        vm.reload()
        runCurrent()
        advanceTimeBy(5_000)
        vm.season(selected)
        runCurrent()
        if (episodeFails) {
            assertNotNull(vm.state.value.error)
        } else {
            assertTrue(vm.state.value.loading, "Episode success cannot settle the pending parent reads")
        }
        advanceTimeBy(ApiTimeouts.REQUEST_MILLIS - 5_000)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertEquals(
            "The server took too long to respond. Try again.",
            vm.state.value.error,
            "The complete page still owns its original budget"
        )
        parentSeasons.complete(ClientResult.Success(ItemPage(listOf(selected))))
        runCurrent()
        assertNotNull(vm.state.value.error, "The canceled parent cannot clear the deadline error")
    }
}
