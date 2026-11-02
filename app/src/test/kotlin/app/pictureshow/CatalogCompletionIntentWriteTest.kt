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

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogCompletionIntentWriteTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
    private val film = MediaItem("film", "Invented film", "Movie", userData = ItemUserData(isFavorite = true))
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

    @Test fun aRawFalseReadCannotReplaceCompletionPresentationWhileTheRequiredWriteIsHeld() = runTest(dispatcher) {
        val oldCompletionRead = CompletableDeferred<ClientResult<MediaItem>>()
        val write = CompletableDeferred<ClientResult<ItemUserData>>()
        var reads = 0
        service.detailBlock = {
            if (++reads == 1) oldCompletionRead.await() else ClientResult.Success(film)
        }
        service.actionBlock = { write.await() }
        session.playbackPending(film.id, "report")
        val completion = launch {
            session.completePlayback(account, film.id)
            session.refresh(film.id, "report")
        }
        runCurrent()
        val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
        runCurrent()
        try {
            assertEquals(
                listOf(Triple("played", film.id, true)),
                service.actions,
                "Completion must inspect raw played=false and retain its required write"
            )
            assertFalse(completion.isCompleted)
            assertTrue(vm.state.value.playbackPending, "Provisional presentation cannot release a pending played write")
            assertTrue(
                vm.state.value.item?.userData?.played == true,
                "A raw false read cannot undo the natural-end presentation while its write is outstanding"
            )
            assertTrue(vm.state.value.item?.userData?.isFavorite == true)
            val acknowledged = film.copy(userData = film.userData.copy(played = true))
            service.detailBlock = { ClientResult.Success(acknowledged) }
            write.complete(ClientResult.Success(acknowledged.userData))
            runCurrent()
            assertTrue(completion.isCompleted)
            assertFalse(vm.state.value.playbackPending)
            assertTrue(vm.state.value.item?.userData?.played == true)

            // The intent ends with completion; a later viewer mutation owns the value.
            service.actionBlock = null
            service.actionResult = ClientResult.Success(film.userData)
            service.detailBlock = { ClientResult.Success(film) }
            vm.played()
            runCurrent()
            assertEquals(listOf(Triple("played", film.id, true), Triple("played", film.id, false)), service.actions)
            assertFalse(vm.state.value.item!!.userData.played)
            session.changed(MediaItem("other", userData = ItemUserData(played = true)))
            runCurrent()
            assertFalse(vm.state.value.item!!.userData.played, "An unrelated update cannot resurrect the completed played-only projection")
        } finally {
            write.complete(ClientResult.Success(film.userData.copy(played = true)))
            oldCompletionRead.complete(ClientResult.Success(film.copy(userData = film.userData.copy(played = true))))
            runCurrent()
        }
    }
}
