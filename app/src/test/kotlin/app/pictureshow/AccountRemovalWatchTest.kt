/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.app.Application
import androidx.tvprovider.media.tv.WatchNextProgram
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.ClientResult
import app.pictureshow.core.ItemPage
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.TextPersistence
import app.pictureshow.core.User
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AccountRemovalWatchTest {
    @Test
    fun removingAnUnselectedViewerClearsOnlyTheirDismissalsThenCurrentRemovalClearsRows() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("first", "First viewer"), "fake-token")
        val other = account.copy(user = User("other", "Other viewer"))
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        accounts.remember(other)
        accounts.remember(account)
        val dismissals = StoredWatchDismissals(app)
        val firstDismissal = "${watchAccountKey(account)}:dismissed"
        val otherDismissal = "${watchAccountKey(other)}:dismissed"
        dismissals.add(firstDismissal)
        dismissals.add(otherDismissal)
        val programs = mutableMapOf<Long, WatchNextProgram>()
        var browsable = true
        val provider = object : WatchProvider {
            override fun rows() = programs.map { (id, p) -> WatchRow(id, p.internalProviderId!!, browsable) }
            override fun insert(program: WatchNextProgram) {
                programs[1] = program
            }
            override fun update(id: Long, program: WatchNextProgram) {
                programs[id] = program
            }
            override fun delete(id: Long) {
                programs.remove(id)
            }
        }
        val catalog = CatalogModelsTest.FakeCatalog().apply {
            resumeItems = ClientResult.Success(ItemPage(listOf(MediaItem("0123456789abcdef0123456789abcdef", "Fixture", "Movie"))))
        }
        WatchNextCoordinator(app, accounts, catalog, backgroundScope, { provider }, dismissals).start()
        runCurrent()
        assertEquals(1, programs.size)
        accounts.removeAccount(other.key)
        runCurrent()
        assertTrue(dismissals.contains(firstDismissal))
        assertFalse(dismissals.contains(otherDismissal))
        assertEquals(1, programs.size)
        browsable = false // A launcher dismissal is still in the provider when the viewer is removed.
        accounts.removeAccount(account.key)
        runCurrent()
        assertFalse(dismissals.contains(firstDismissal))
        assertTrue(programs.isEmpty())
        val prefs = app.getSharedPreferences("watch_next_dismissed", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun removedViewerRowsAreDeletedEvenWhenDismissalStorageFails() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val account = Account(Server("server", "Cinema", "https://media.example.test", "12"), User("viewer", "Viewer"), "fake-token")
        val accounts = AccountStore(object : TextPersistence {
            override suspend fun read(): String? = null
            override suspend fun write(value: String) = Unit
        })
        accounts.remember(account)
        val rows = mutableListOf(WatchRow(1, "${watchAccountKey(account)}:dismissed", false))
        val provider = object : WatchProvider {
            override fun rows() = rows.toList()
            override fun insert(program: WatchNextProgram) = Unit
            override fun update(id: Long, program: WatchNextProgram) = Unit
            override fun delete(id: Long) {
                rows.removeAll { it.id == id }
            }
        }
        val dismissals = object : WatchDismissals {
            override fun contains(key: String) = false
            override fun add(key: String): Unit = error("Storage unavailable")
            override fun retainAccounts(prefixes: Set<String>): Unit = error("Storage unavailable")
        }
        WatchNextCoordinator(app, accounts, CatalogModelsTest.FakeCatalog(), backgroundScope, { provider }, dismissals).start()
        runCurrent()
        assertEquals(1, rows.size)
        accounts.removeAccount(account.key)
        runCurrent()
        assertTrue(rows.isEmpty())
    }
}
