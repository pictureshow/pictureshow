/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemUserData
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogDetailCompletionPresentationTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(isFavorite = true))
    private val fresh = film.copy(userData = film.userData.copy(played = true))
    private val service = CatalogModelsTest.FakeCatalog()
    private val accounts = AccountStore(object : TextPersistence {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    })
    private val session = CatalogSession(service, accounts)

    @BeforeTest fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest fun teardown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test fun completionIntentPrecedesDestinationReadWithoutWaitingForTheHeldGet() = runTest(dispatcher) {
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            reads++
            held.await()
        }
        session.playbackPending(film.id, "report")
        // PlaybackSession invokes this after the fast successful Stopped report at natural end.
        val completion = launch {
            session.completePlayback(account, film.id)
            session.refresh(film.id, "report")
        }
        runCurrent()
        assertEquals(1, reads, "The completion read started after Stopped, before the destination read")
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        try {
            assertEquals(2, reads)
            assertFalse(completion.isCompleted)
            assertTrue(vm.state.value.loading)
            assertTrue(vm.state.value.playbackPending, "Presentation cannot release the authoritative read/report guard")
            assertFalse(vm.state.value.readSucceeded)
            assertTrue(
                vm.state.value.item?.userData?.played == true,
                "A held natural-end page must present the completion the viewer produced"
            )
            assertTrue(vm.state.value.item?.userData?.isFavorite == true, "The played-only intent must preserve other known user values")
            assertTrue(service.actions.isEmpty(), "An in-flight completion read cannot justify another played write")
        } finally {
            held.complete(ClientResult.Success(fresh))
            runCurrent()
        }
        assertTrue(completion.isCompleted)
        assertEquals(fresh.userData, vm.state.value.item?.userData)
        assertTrue(service.actions.isEmpty())
    }

    @Test fun completionIntentAfterDestinationReadBeginsUpdatesTheHeldPresentation() = runTest(dispatcher) {
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        var reads = 0
        service.detailBlock = {
            reads++
            held.await()
        }
        session.playbackPending(film.id, "report")
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        assertEquals(1, reads)
        val completion = launch {
            session.completePlayback(account, film.id)
            session.refresh(film.id, "report")
        }
        runCurrent()
        try {
            assertEquals(2, reads)
            assertFalse(completion.isCompleted)
            assertTrue(vm.state.value.loading)
            assertTrue(vm.state.value.playbackPending)
            assertTrue(vm.state.value.item?.userData?.played == true, "Completing later must produce the same held watched value")
            assertTrue(vm.state.value.item?.userData?.isFavorite == true)
            assertTrue(service.actions.isEmpty())
        } finally {
            held.complete(ClientResult.Success(fresh))
            runCurrent()
        }
        assertTrue(completion.isCompleted)
        assertEquals(fresh.userData, vm.state.value.item?.userData)
    }

    @Test fun acknowledgedCompletionFillsAClearedItemBeforeItsFreshReadReturns() = runTest(dispatcher) {
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        session.playbackPending(film.id, "report")
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        session.playbackCompleted(fresh)
        session.refresh(film.id, "report")
        runCurrent()
        try {
            assertTrue(vm.state.value.loading)
            assertTrue(vm.state.value.item?.userData?.played == true, "A confirmed completion must not be lost because item was cleared")
            assertTrue(vm.state.value.item?.userData?.isFavorite == true)
            assertFalse(vm.state.value.readSucceeded, "A completion patch is not a full destination read")
        } finally {
            held.complete(ClientResult.Success(fresh))
            runCurrent()
        }
    }

    @Test fun acknowledgedCompletionBeforeConstructionIsTheNewDestinationsBaseline() = runTest(dispatcher) {
        val held = CompletableDeferred<ClientResult<MediaItem>>()
        service.detailBlock = { held.await() }
        session.playbackPending(film.id, "report")
        session.playbackCompleted(fresh)
        session.refresh(film.id, "report")
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        try {
            assertTrue(vm.state.value.loading)
            assertEquals(fresh.userData, vm.state.value.item?.userData)
            assertFalse(vm.state.value.readSucceeded)
        } finally {
            held.complete(ClientResult.Success(fresh))
            runCurrent()
        }
    }
}
