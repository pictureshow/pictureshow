/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineUserData
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineProgressTest {
    @get:Rule val folder = TemporaryFolder()
    private val account = Account(Server("s", "S", "http://s", "12.4"), User("viewer-a", "A"), "token")

    private inner class Remote(var data: OfflineUserData = OfflineUserData()) : OfflineProgressRemote {
        var viewer = true
        val writes = mutableListOf<Pair<Long?, Boolean?>>()
        var loseAnswer = false
        var reachable = true
        var ignoreWrites = false

        override suspend fun sameViewer(account: Account): Boolean {
            if (!reachable) throw IOException("down")
            return viewer
        }

        override suspend fun read(account: Account, itemId: String): OfflineUserData = data

        override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? {
            writes += positionTicks to played
            if (!ignoreWrites) {
                data = data.copy(positionTicks = positionTicks ?: data.positionTicks, played = played ?: data.played)
                if (played == true) data = data.copy(playCount = data.playCount + 1)
            }
            return if (loseAnswer) null else 204
        }
    }

    private val file: File get() = File(folder.root, "progress.json")
    private fun store() = OfflineProgressStore(file, account)

    @Test
    fun `an unchanged server takes the new position and only that`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 100))
        store.viewing("i", 1, 500)
        val remote = Remote(OfflineUserData(positionTicks = 100))
        assertTrue(store.sync(remote))
        assertEquals(listOf(500L to null), remote.writes)
        assertEquals(500L, remote.data.positionTicks)
    }

    @Test
    fun `a server that moved wins and the viewer is told`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 100))
        store.viewing("i", 1, 500)
        val remote = Remote(OfflineUserData(positionTicks = 900))
        store.sync(remote)
        assertTrue(remote.writes.isEmpty())
        assertEquals(900L, remote.data.positionTicks)
        assertEquals(setOf("i"), store.conflicts.value.keys)
        store.dismissConflict("i")
        assertTrue(store.conflicts.value.isEmpty())
    }

    @Test
    fun `a value already there is not written again`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 100))
        store.completed("i", 1)
        val remote = Remote(OfflineUserData(positionTicks = 0, played = true, playCount = 3))
        store.sync(remote)
        assertTrue(remote.writes.isEmpty())
        assertEquals(3, remote.data.playCount)
    }

    @Test
    fun `a lost answer is reconciled by a read and never written blind`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData())
        store.completed("i", 1)
        val remote = Remote()
        remote.loseAnswer = true
        assertFalse(store.sync(remote))
        assertEquals(1, remote.writes.size)
        val count = remote.data.playCount
        // Process died here. The new process reads first and finds the write took.
        remote.loseAnswer = false
        val reopened = store()
        reopened.sync(remote)
        assertEquals("no second write", 1, remote.writes.size)
        assertEquals(count, remote.data.playCount)
    }

    @Test
    fun `a write the server didn't keep is not acknowledged`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 100))
        store.viewing("i", 1, 500)
        val remote = Remote(OfflineUserData(positionTicks = 100))
        remote.ignoreWrites = true
        store.sync(remote)
        assertEquals(setOf("i"), store.conflicts.value.keys)
    }

    @Test
    fun `the outbox survives a restart before any sync`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 100))
        store.viewing("i", 1, 500)
        val reopened = store()
        assertEquals(500L, reopened.resumeTicks("i"))
        val remote = Remote(OfflineUserData(positionTicks = 100))
        reopened.sync(remote)
        assertEquals(listOf(500L to null), remote.writes)
    }

    @Test
    fun `nothing is written for another viewer or an unreachable server`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData())
        store.viewing("i", 1, 500)
        val remote = Remote()
        remote.viewer = false
        assertFalse(store.sync(remote))
        remote.viewer = true
        remote.reachable = false
        assertFalse(store.sync(remote))
        assertTrue(remote.writes.isEmpty())
    }

    @Test
    fun `an older viewing can't overwrite a newer one`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData())
        store.viewing("i", 5, 900)
        store.viewing("i", 4, 100)
        assertEquals(900L, store.resumeTicks("i"))
    }

    @Test
    fun `resume is the local position, zero once watched`() = runBlocking<Unit> {
        val store = store()
        store.remember("i", OfflineUserData(positionTicks = 300))
        assertEquals(300L, store.resumeTicks("i"))
        store.viewing("i", 1, 700)
        assertEquals(700L, store.resumeTicks("i"))
        store.completed("i", 2)
        assertEquals(0L, store.resumeTicks("i"))
    }
}
