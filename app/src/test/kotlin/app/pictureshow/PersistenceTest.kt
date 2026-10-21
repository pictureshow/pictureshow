package app.pictureshow

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.pictureshow.core.Account
import app.pictureshow.core.AccountStore
import app.pictureshow.core.Server
import app.pictureshow.core.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersistenceTest {
    @Test
    fun realDataStoreReopensMultipleAccountsAndStableDeviceId() = runTest {
        val directory = Files.createTempDirectory("pictureshow-test").toFile()
        val file = directory.resolve("accounts.preferences_pb")
        var scope = CoroutineScope(coroutineContext + SupervisorJob())
        var preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        try {
            val server = Server("s1", "Home", "https://media.example.test", "12.0.0")
            val first = Account(server, User("u1", "Pat"), "fake-one")
            val second = Account(server.copy(id = "s2"), User("u2", "Robin"), "fake-two")
            val store = AccountStore(AccountPersistence(preferences))
            store.remember(first)
            store.remember(second)
            store.select(first.key)
            val identity = DeviceIdentityStore(preferences)
            val ids = List(8) { async { identity.getOrCreate() } }.awaitAll()
            assertEquals(1, ids.distinct().size)
            assertTrue(ids.first().isNotBlank())
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            scope = CoroutineScope(coroutineContext + SupervisorJob())
            preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val reopened = AccountStore(AccountPersistence(preferences))
            assertEquals(listOf(first, second), reopened.load().accounts)
            assertEquals(first, reopened.snapshots.value.currentAccount)
            assertEquals(ids.first(), DeviceIdentityStore(preferences).getOrCreate())
            reopened.signOut(first.key)
            assertEquals(listOf(second), reopened.snapshots.value.accounts)
            assertNull(reopened.snapshots.value.currentKey)
        } finally {
            scope.cancel()
            scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            directory.deleteRecursively()
        }
    }
}
