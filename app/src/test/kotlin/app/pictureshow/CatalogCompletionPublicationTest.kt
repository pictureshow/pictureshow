/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemUserData
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogCompletionPublicationTest {
    @get:Rule
    val timeLimit = PerTestTimeLimit(180)

    @Test
    fun aHandlerDeliveredFullReadIsAppliedBeforeItsReportRefresh() {
        val main = Handler(Looper.getMainLooper()).asCoroutineDispatcher().immediate
        Dispatchers.setMain(main)
        val scope = CoroutineScope(SupervisorJob() + main)
        val store = ViewModelStore()
        val account = Account(Server("s", "Test", "https://example.test", "12"), User("u", "Viewer"), "fake")
        val film = MediaItem("film", "Invented film", "Movie")
        val fresh = film.copy(overview = "Authoritative metadata", userData = ItemUserData(played = true))
        val oldRead = CompletableDeferred<ClientResult<MediaItem>>()
        val viewerRead = CompletableDeferred<ClientResult<MediaItem>>()
        val extraRead = CompletableDeferred<ClientResult<MediaItem>>()
        val service = CatalogModelsTest.FakeCatalog()
        var reads = 0
        service.detailBlock = {
            when (++reads) {
                1 -> oldRead.await()
                2 -> viewerRead.await()
                else -> extraRead.await()
            }
        }
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) {}
        })
        val session = CatalogSession(service, accounts)
        try {
            session.playbackPending(film.id, "report")
            val report = scope.launch {
                session.completePlayback(account, film.id)
                session.refresh(film.id, "report")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val vm = CatalogDetailViewModel(account, film, session).also { store.put("detail", it) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, reads)
            // A real network result posts to Android's Handler; a test-only unconfined
            // continuation can hide reentrant report/StateFlow delivery inside this result.
            Thread { viewerRead.complete(ClientResult.Success(fresh)) }.also {
                it.start()
                it.join()
            }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, reads, "The acknowledged full read must not start another item request")
            assertTrue(report.isCompleted)
            assertTrue(vm.state.value.readSucceeded)
            assertFalse(vm.state.value.loading)
            assertFalse(vm.state.value.playbackPending)
            assertEquals(fresh.overview, vm.state.value.item?.overview)
            assertTrue(service.actions.isEmpty(), "Played=true must not produce another Played write")
        } finally {
            oldRead.complete(ClientResult.Success(fresh))
            extraRead.complete(ClientResult.Success(fresh))
            shadowOf(Looper.getMainLooper()).idle()
            store.clear()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            Dispatchers.resetMain()
        }
    }
}
