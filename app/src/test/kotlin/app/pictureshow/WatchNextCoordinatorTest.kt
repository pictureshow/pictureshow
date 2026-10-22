/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Activity
import android.app.Application
import androidx.tvprovider.media.tv.WatchNextProgram
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.CatalogService
import app.pictureshow.core.ClientError
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ErrorKind
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class WatchNextCoordinatorTest {
    private val account = Account(Server("server", "Fixture", "https://media.example.test", "12"), User("a", "A"), "token")
    private val item = MediaItem("0123456789abcdef0123456789abcdef", "Fixture movie", "Movie")
    private class Provider : WatchProvider {
        val programs = linkedMapOf<Long, WatchNextProgram>()
        var sequence = 0L
        override fun rows() = programs.map { (id, p) -> WatchRow(id, p.internalProviderId!!, true) }
        override fun insert(program: WatchNextProgram) {
            programs[++sequence] = program
        }
        override fun update(id: Long, program: WatchNextProgram) {
            programs[id] = program
        }
        override fun delete(id: Long) {
            programs.remove(id)
        }
    }
    private class Persistence : TextPersistence {
        var text: String? = null
        override suspend fun read() = text
        override suspend fun write(value: String) {
            text = value
        }
    }
    private class Dismissals : WatchDismissals {
        override fun contains(key: String) = false
        override fun add(key: String) = Unit
    }
    private fun TestScope.coordinator(accounts: AccountStore, service: CatalogService, provider: Provider) = WatchNextCoordinator(
        RuntimeEnvironment.getApplication(),
        accounts,
        service,
        backgroundScope,
        { provider },
        Dismissals()
    )

    @Test
    fun startupLifecycleAndCatalogRefreshPublishAndSignOutClearsRows() = runTest {
        val accounts = AccountStore(Persistence())
        accounts.remember(account)
        val service = CatalogModelsTest.FakeCatalog().apply { resumeItems = ClientResult.Success(ItemPage(listOf(item))) }
        val provider = Provider()
        val coordinator = coordinator(accounts, service, provider)
        coordinator.start()
        runCurrent()
        assertEquals(1, provider.programs.size)
        assertEquals(1, service.resumeCalls)
        coordinator.onActivityResumed(Activity())
        runCurrent()
        assertEquals(2, service.resumeCalls)
        coordinator.onActivityStopped(Activity())
        runCurrent()
        assertEquals(3, service.resumeCalls)
        CatalogSession(service, accounts, coordinator::refresh).refresh()
        runCurrent()
        assertEquals(4, service.resumeCalls)
        accounts.signOut(account.key)
        runCurrent()
        assertTrue(provider.programs.isEmpty())
    }

    @Test
    fun lateOldUserResponseCannotPublishAfterUserSwitchEvenIfRequestIgnoresCancellation() = runTest {
        val accounts = AccountStore(Persistence())
        accounts.remember(account)
        val waiting = CompletableDeferred<Unit>()
        var blocked = false
        val service = object : CatalogService by CatalogModelsTest.FakeCatalog() {
            override suspend fun resume(account: Account): ClientResult<ItemPage> {
                if (account.user.id == "a") {
                    blocked = true
                    withContext(NonCancellable) { waiting.await() }
                    return ClientResult.Success(ItemPage(listOf(item)))
                }
                return ClientResult.Success(ItemPage(listOf(item.copy(name = "B movie"))))
            }
        }
        val provider = Provider()
        coordinator(accounts, service, provider).start()
        runCurrent()
        assertTrue(blocked)
        val other = account.copy(user = User("b", "B"))
        accounts.remember(other)
        runCurrent()
        waiting.complete(Unit)
        runCurrent()
        assertEquals(listOf("B movie"), provider.programs.values.map { it.title })
        assertTrue(provider.programs.values.single().internalProviderId!!.startsWith(watchAccountKey(other)))
    }

    @Test
    fun transientFailurePreservesExistingRowsAndNewUserFailureClearsPreviousUser() = runTest {
        val accounts = AccountStore(Persistence())
        accounts.remember(account)
        val service = CatalogModelsTest.FakeCatalog().apply { resumeItems = ClientResult.Success(ItemPage(listOf(item))) }
        val provider = Provider()
        val coordinator = coordinator(accounts, service, provider)
        coordinator.start()
        runCurrent()
        assertEquals(1, provider.programs.size)
        service.nextItems = ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline"))
        coordinator.refresh()
        runCurrent()
        assertEquals(1, provider.programs.size)
        accounts.remember(account.copy(user = User("b", "B")))
        runCurrent()
        assertTrue(provider.programs.isEmpty())
    }
}
