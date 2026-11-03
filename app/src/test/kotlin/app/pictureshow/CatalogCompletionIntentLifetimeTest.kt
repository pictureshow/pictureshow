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
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogCompletionIntentLifetimeTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(isFavorite = true))
    private val episode = film.copy(id = "episode", type = "Episode", seriesId = "series")
    private val service = CatalogModelsTest.FakeCatalog()
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    })
    private val session = CatalogSession(service, accounts)
    private val failure = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Connection failed"))

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun aViewerChoiceAcceptedDuringTheReadSupersedesAutomaticCompletion() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        session.playbackPending(film.id, "report")
        val completion = launch { session.completePlayback(account, film.id, "report") }
        runCurrent()
        assertTrue(vm.state.value.item!!.userData.played, "Natural end remains presented before a viewer choice")
        vm.playedRequested()
        held.complete(ClientResult.Success(film))
        runCurrent()
        assertTrue(completion.isCompleted)
        assertTrue(service.actions.isEmpty(), "Completion must not POST against an accepted later unwatch")
        assertFalse(film.id in session.changes.value.completionIntents)
        assertFalse(vm.state.value.item!!.userData.played, "Fresh unplayed data must reach the queued no-change decision")
    }

    @Test fun aChoiceAcceptedBeforeTheCompletionCheckStillOwnsTheReport() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        session.playbackPending(film.id, "report")
        session.playedChoice(film.id)
        session.completePlayback(account, film.id, "report")
        assertTrue(service.actions.isEmpty(), "A fast viewer request can precede a slow Stopped acknowledgement")
        assertFalse(film.id in session.changes.value.completionIntents)
    }

    @Test fun aLaterPlaybackReportCanCompleteAfterAnEarlierViewerChoice() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        session.playbackPending(film.id, "old")
        session.playedChoice(film.id)
        session.playbackPending(film.id, "new")
        session.completePlayback(account, film.id, "old")
        assertTrue(service.actions.isEmpty(), "The old report cannot borrow the new report baseline")
        session.completePlayback(account, film.id, "new")
        assertTrue(service.actions == listOf(Triple("played", film.id, true)), "Viewer precedence ends with its report, not forever")
    }

    @Test fun aFinishedReportCannotBorrowTheCurrentChoiceRevision() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        session.playbackPending(film.id, "report")
        session.refresh(film.id, "report")
        session.completePlayback(account, film.id, "report")
        assertTrue(service.actions.isEmpty(), "A late callback from a retired report has no completion authority")
        assertFalse(film.id in session.changes.value.completionIntents)
    }

    @Test fun failedCompletionReadRestoresThePresentedValueEvenWhenTheRefreshAlsoFails() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        session.playbackPending(film.id, "report")
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        assertTrue(vm.state.value.item!!.userData.played)
        held.complete(failure)
        runCurrent()
        assertTrue(completion.isCompleted)
        assertFalse(film.id in session.changes.value.completionIntents, "A failed read cannot retain a completion claim")
        assertFalse(vm.state.value.item!!.userData.played, "Removing intent must undo the already-rendered projection")
        assertTrue(vm.state.value.item!!.userData.isFavorite)
        session.refresh(film.id, "report")
        runCurrent()
        assertTrue(vm.state.value.readFailed)
        assertFalse(vm.state.value.item!!.userData.played, "A second failure cannot resurrect the completion claim")
    }

    @Test fun failedPlayedWriteRestoresTheLatestRawValueWithoutWaitingForAnotherRead() = runTest(dispatcher) {
        service.detail = ClientResult.Success(film)
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        val write = CompletableDeferred<ClientResult<ItemUserData>>()
        service.actionBlock = { write.await() }
        session.playbackPending(film.id, "report")
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        assertTrue(vm.state.value.item!!.userData.played, "In-flight completion still presents the natural end")
        write.complete(failure)
        runCurrent()
        assertTrue(completion.isCompleted)
        assertFalse(film.id in session.changes.value.completionIntents)
        assertFalse(vm.state.value.item!!.userData.played, "A refused played write must not keep offering Mark unwatched")
    }

    @Test fun cancelledCompletionAlsoReleasesItsProvisionalIntent() = runTest(dispatcher) {
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        assertTrue(film.id in session.changes.value.completionIntents)
        completion.cancel()
        runCurrent()
        assertFalse(film.id in session.changes.value.completionIntents)
    }

    @Test fun rawSeriesEpisodeReadEndsAnUnownedIntentBeforeUnrelatedChanges() = runTest(dispatcher) {
        checkRawEpisodeRead("Series")
    }

    @Test fun rawSeasonEpisodeReadEndsAnUnownedIntentBeforeUnrelatedChanges() = runTest(dispatcher) {
        checkRawEpisodeRead("Season")
    }

    private suspend fun checkRawEpisodeRead(type: String) {
        val parent = MediaItem(if (type == "Series") "series" else "season", "Parent", type, seriesId = "series")
        service.detail = ClientResult.Success(episode.copy(userData = episode.userData.copy(played = true)))
        session.completePlayback(account, episode.id)
        service.detail = ClientResult.Success(parent)
        service.seasonItems = ClientResult.Success(ItemPage(listOf(MediaItem("season", type = "Season", seriesId = "series"))))
        service.episodeBlock = { _, _ -> ClientResult.Success(ItemPage(listOf(episode))) }
        val vm = CatalogDetailViewModel(account, parent, session).also { store.put(type, it) }
        dispatcher.scheduler.runCurrent()
        assertFalse(episode.id in session.changes.value.completionIntents, "The raw episode read owns its value once no report is pending")
        assertFalse(vm.state.value.episodes.single().userData.played)
        session.playbackPending("unrelated")
        dispatcher.scheduler.runCurrent()
        assertFalse(vm.state.value.episodes.single().userData.played, "A later intent-only emission must not override the raw episode")
    }

    @Test fun anEpisodeReadDuringCompletionKeepsTheNaturalEndUntilFailure() = runTest(dispatcher) {
        checkInFlightEpisode(withReport = true)
    }

    @Test fun anActiveCompletionWithoutAReportAlsoSurvivesAnAuthoritativeEpisodeRead() = runTest(dispatcher) {
        checkInFlightEpisode(withReport = false)
    }

    private suspend fun checkInFlightEpisode(withReport: Boolean) = kotlinx.coroutines.coroutineScope {
        val season = MediaItem("season", "Season", "Season", seriesId = "series")
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        if (withReport) session.playbackPending(episode.id, "report", setOf(season.id))
        val completion = launch { session.completePlayback(account, episode.id) }
        dispatcher.scheduler.runCurrent()
        service.detailBlock = { ClientResult.Success(season) }
        service.episodeBlock = { _, _ -> ClientResult.Success(ItemPage(listOf(episode))) }
        val vm = CatalogDetailViewModel(account, season, session).also { store.put("season", it) }
        dispatcher.scheduler.runCurrent()
        try {
            assertTrue(vm.state.value.episodes.single().userData.played, "A raw false read cannot erase an in-flight natural end")
        } finally {
            held.complete(failure)
            dispatcher.scheduler.runCurrent()
        }
        assertTrue(completion.isCompleted)
        assertFalse(vm.state.value.episodes.single().userData.played, "Failure restores that episode's latest raw value")
    }

    @Test fun anOlderFailedCompletionCannotRemoveANewerCompletionIntent() = runTest(dispatcher) {
        val older = CompletableDeferred<ClientResult<MediaItem>>()
        val newer = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = { if (++reads == 1) older.await() else newer.await() }
        val first = launch { session.completePlayback(account, film.id) }
        runCurrent()
        val second = launch { session.completePlayback(account, film.id) }
        runCurrent()
        older.complete(failure)
        runCurrent()
        assertTrue(first.isCompleted)
        assertTrue(film.id in session.changes.value.completionIntents, "An older result cannot cancel the newer natural end")
        newer.complete(failure)
        runCurrent()
        assertTrue(second.isCompleted)
        assertFalse(film.id in session.changes.value.completionIntents)
    }

    @Test fun anAcknowledgedRefreshCannotClearAnotherActiveCompletionForTheSameItem() = runTest(dispatcher) {
        val older = CompletableDeferred<ClientResult<MediaItem>>()
        val newer = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = { if (++reads == 1) older.await() else newer.await() }
        val first = launch { session.completePlayback(account, film.id) }
        runCurrent()
        val second = launch { session.completePlayback(account, film.id) }
        runCurrent()
        older.complete(ClientResult.Success(film.copy(userData = film.userData.copy(played = true))))
        runCurrent()
        assertTrue(first.isCompleted)
        session.refresh()
        try {
            assertTrue(film.id in session.changes.value.completionIntents, "Publishing one ACK must not settle another active owner")
        } finally {
            newer.complete(failure)
            runCurrent()
        }
        assertTrue(second.isCompleted)
        assertFalse(film.id in session.changes.value.completionIntents)
    }

    @Test fun accountSessionsDoNotShareProvisionalPlayedStateForTheSameItemId() = runTest(dispatcher) {
        val other = account.copy(user = User("other", "Other viewer"))
        accounts.remember(account)
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        val completion = launch { session.completePlayback(account, film.id) }
        runCurrent()
        accounts.remember(other)
        // The root keys CatalogSession by current account key and token.
        val otherSession = CatalogSession(service, accounts)
        val vm = CatalogDetailViewModel(other, film, otherSession).also { store.put("other", it) }
        runCurrent()
        assertTrue(otherSession.changes.value.completionIntents.isEmpty())
        assertFalse(vm.state.value.item!!.userData.played)
        held.complete(failure)
        runCurrent()
        assertTrue(completion.isCompleted)
        assertTrue(otherSession.changes.value.completionIntents.isEmpty())
        assertFalse(vm.state.value.item!!.userData.played)
    }
}
