/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RecentSearchesTest {
    private class Memory : TextPersistence {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) {
            this.value = value
        }
    }
    private val first = Account(Server("server", "Test", "https://media.example.test", "12"), User("first", "Viewer"), "token")
    private val second = first.copy(user = User("second", "Other viewer"))

    @Test
    fun historyDedupesIgnoringCaseCapsAtEightIsolatesAccountsAndSurvivesRestart() = runTest {
        val disk = Memory()
        val store = AccountStore(disk)
        store.remember(first)
        store.remember(second)
        repeat(10) { store.recordSearch(first.key, "query $it") }
        store.recordSearch(first.key, "  QUERY 5  ")
        store.recordSearch(second.key, "other search")
        store.recordSearch(first.key, " x ")
        val restarted = AccountStore(disk).load()
        assertEquals(
            listOf("QUERY 5", "query 9", "query 8", "query 7", "query 6", "query 4", "query 3", "query 2"),
            restarted.recentSearches[first.key]
        )
        assertEquals(listOf("other search"), restarted.recentSearches[second.key])
        store.remember(first.copy(accessToken = "new-token"))
        assertEquals(restarted.recentSearches, store.snapshots.value.recentSearches)
    }

    @Test
    fun clearAndSignOutRemoveOnlyThatAccountsHistoryAndLateWritesCannotResurrectIt() = runTest {
        val disk = Memory()
        val store = AccountStore(disk)
        store.remember(first)
        store.remember(second)
        store.recordSearch(first.key, "first query")
        store.recordSearch(second.key, "second query")
        store.clearSearches(first.key)
        assertTrue(store.snapshots.value.recentSearches[first.key].isNullOrEmpty())
        store.recordSearch(first.key, "new query")
        store.signOut(first.key)
        store.recordSearch(first.key, "late query")
        val restarted = AccountStore(disk)
        assertEquals(mapOf(second.key to listOf("second query")), restarted.load().recentSearches)
        restarted.remember(first)
        assertTrue(restarted.snapshots.value.recentSearches[first.key].isNullOrEmpty())
    }
}
