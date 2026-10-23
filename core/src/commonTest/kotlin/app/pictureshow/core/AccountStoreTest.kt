package app.pictureshow.core

import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AccountStoreTest {
    private val first = Account(Server("server-1", "Family", "https://one.test/jellyfin", "12.0.0"), User("user-1", "Taylor"), "token-1")
    private val second = first.copy(user = User("user-2", "Jamie"), accessToken = "token-2")
    private val third = first.copy(server = first.server.copy(id = "server-2", url = "https://two.test"), accessToken = "token-3")

    @Test
    fun accountAndContainingStateNeverPrintTheAccessToken() {
        val account = first.copy(accessToken = "secret-access-token")
        listOf(account.toString(), AccountSnapshot(accounts = listOf(account)).toString(), QuickConnectState.Authorized(account).toString())
            .forEach { text ->
                assertFalse(text.contains(account.accessToken))
                assertTrue(text.contains("accessToken=[redacted]"))
            }
        assertEquals("secret-access-token", account.accessToken)
    }

    @Test
    fun severalServersAndUsersAndCurrentSelectionRoundTrip() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        assertEquals(AccountSnapshot(), store.load())
        store.remember(first)
        store.remember(second)
        store.remember(third)
        store.select(second.key)
        val restored = AccountStore(persistence).load()
        assertEquals(listOf(first, second, third), restored.accounts)
        assertEquals(second.key, restored.currentKey)
        assertEquals(second, restored.currentAccount)
    }

    @Test
    fun rememberingAnExistingAccountReplacesItsTokenWithoutDuplicatingIt() = runTest {
        val store = AccountStore(MemoryPersistence())
        store.remember(first)
        store.remember(second)
        val refreshed = first.copy(accessToken = "new-token", user = first.user.copy(name = "New name"))
        store.remember(refreshed)
        assertEquals(listOf(second, refreshed), store.load().accounts)
        assertEquals(refreshed, store.load().currentAccount)
    }

    @Test
    fun signingOutOnlyRemovesThatAccountAndClearsItsSelection() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        listOf(first, second, third).forEach { store.remember(it) }
        store.select(second.key)
        store.signOut(first.key)
        assertEquals(second, store.load().currentAccount)
        store.signOut(second.key)
        val restored = AccountStore(persistence).load()
        assertEquals(listOf(third), restored.accounts)
        assertNull(restored.currentKey)
    }

    @Test
    fun signedOutServerSurvivesReloadWithoutUserTokenOrRecentSearches() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        store.remember(first)
        store.recordSearch(first.key, "private search")
        store.signOut(first.key)
        val restored = AccountStore(persistence).load()
        assertEquals(listOf(first.server), restored.rememberedServers)
        assertEquals(first.server, restored.lastServer)
        assertTrue(restored.accounts.isEmpty())
        assertTrue(restored.recentSearches.isEmpty())
        assertNull(restored.currentAccount)
        for (privateValue in listOf(first.accessToken, first.user.id, first.user.name, "private search")) {
            assertFalse(persistence.value.orEmpty().contains(privateValue))
        }
    }

    @Test
    fun lastSignedOutServerComesFirstWithoutHidingOtherSavedServersOrDuplicatingEndpoints() = runTest {
        val store = AccountStore(MemoryPersistence())
        listOf(first, second, third).forEach { store.remember(it) }
        store.signOut(second.key)
        assertEquals(listOf(first.server, third.server), store.load().rememberedServers)
        store.signOut(third.key)
        assertEquals(listOf(third.server, first.server), store.load().rememberedServers)
        store.signOut("missing")
        assertEquals(third.server, store.load().lastServer)
    }

    @Test
    fun switchUserClearsSelectionAndKeepsSavedTokens() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        store.remember(first)
        store.select(null)
        val restored = AccountStore(persistence).load()
        assertEquals(listOf(first), restored.accounts)
        assertNull(restored.currentAccount)
    }

    @Test
    fun selectingAnUnknownAccountFailsWithoutPersisting() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        store.remember(first)
        val text = persistence.value
        assertFailsWith<IllegalArgumentException> { store.select("unknown") }
        assertEquals(text, persistence.value)
        assertEquals(first, store.load().currentAccount)
    }

    @Test
    fun failedWritesNeverPublishOrEraseAccounts() = runTest {
        val persistence = MemoryPersistence()
        val store = AccountStore(persistence)
        store.remember(first)
        persistence.failWrites = true
        store.snapshots.test {
            assertEquals(first, awaitItem().currentAccount)
            assertFailsWith<IllegalStateException> { store.remember(second) }
            assertFailsWith<IllegalStateException> { store.signOut(first.key) }
            expectNoEvents()
            assertEquals(first, store.load().currentAccount)
            assertEquals(first, AccountStore(persistence).load().currentAccount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun missingCurrentSelectionInStoredDataIsCleared() = runTest {
        val persistence = MemoryPersistence("""{"accounts":[],"currentKey":"deleted","futureField":true}""")
        assertEquals(AccountSnapshot(), AccountStore(persistence).load())
    }

    @Test
    fun failedReadsCanBeRetriedAndNeverOverwritePersistedAccounts() = runTest {
        val persistence = MemoryPersistence()
        val initial = AccountStore(persistence)
        initial.remember(first)
        val store = AccountStore(persistence)
        persistence.failReads = true
        assertFailsWith<IllegalStateException> { store.remember(second) }
        assertEquals(AccountSnapshot(), store.snapshots.value)
        persistence.failReads = false
        assertEquals(listOf(first), store.load().accounts)
    }

    @Test
    fun corruptStoredDataIsReportedWithoutOverwritingIt() = runTest {
        val persistence = MemoryPersistence("invalid json")
        val store = AccountStore(persistence)
        assertFailsWith<IllegalArgumentException> { store.remember(first) }
        assertEquals("invalid json", persistence.value)
        assertEquals(AccountSnapshot(), store.snapshots.value)
    }

    private class MemoryPersistence(var value: String? = null) : TextPersistence {
        var failWrites = false
        var failReads = false

        override suspend fun read(): String? {
            check(!failReads) { "Cannot read storage" }
            return value
        }

        override suspend fun write(value: String) {
            check(!failWrites) { "Disk full" }
            this.value = value
        }
    }
}
