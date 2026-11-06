/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The honesty of the move online: real store, real gateway, a server that counts what it's told. */
class OfflineHandoffProgressTest {
    @get:Rule val folder = TemporaryFolder()
    private val account = Account(Server("s", "S", "http://s", "12.4"), User("viewer-a", "A"), "token")
    private val entry = OfflineEntry(
        "i:original",
        "i",
        OfflineMediaKind.movie,
        preset = OfflinePreset.original,
        labels = OfflineLabels("Film")
    )

    private class Remote(var data: OfflineUserData = OfflineUserData()) : OfflineProgressRemote {
        val writes = mutableListOf<Pair<Long?, Boolean?>>()
        var viewer = true
        var reachable = true
        var garbled = false
        override suspend fun sameViewer(account: Account): Boolean {
            if (!reachable) throw IOException("down")
            return viewer
        }
        override suspend fun read(account: Account, itemId: String): OfflineUserData {
            if (garbled) throw IllegalArgumentException("not the server's answer")
            return data
        }
        override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? {
            writes += positionTicks to played
            data = data.copy(positionTicks = positionTicks ?: data.positionTicks, played = played ?: data.played)
            if (played == true) data = data.copy(playCount = data.playCount + 1)
            return 204
        }
    }

    private val store by lazy { OfflineProgressStore(File(folder.root, "progress.json"), account) }
    private var exits = 0
    private val gateway by lazy {
        OfflinePlaybackGateway(entry, File(folder.root, "media"), { null }, store, 1) { exits++ }
    }

    private fun tick(ticks: Long) = PlaybackProgressInfo("i", playMethod = PlayMethod.DirectPlay, positionTicks = ticks)
    private fun stop(ticks: Long) = PlaybackStopInfo("i", positionTicks = ticks)
    private fun handoff(remote: Remote) = OfflineStoreHandoff(gateway, store, "i", remote)

    @Test
    fun `the position reaches the server once, and a late report can't recreate a record`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 100, playCount = 3))
        gateway.progress(tick(400))
        assertEquals(OfflineSettleResult.Settled, handoff(remote).settle(500))
        assertEquals<List<Pair<Long?, Boolean?>>>(listOf(500L to null), remote.writes)
        // Now the online session has moved on, and the local session's stragglers arrive.
        remote.data = remote.data.copy(positionTicks = 900)
        gateway.progress(tick(510))
        gateway.stopped(stop(520))
        gateway.played()
        assertEquals(2, exits, "stopping and finishing each still ask for a sync")
        assertTrue(store.sync(remote))
        assertEquals<List<Pair<Long?, Boolean?>>>(listOf(500L to null), remote.writes, "nothing further")
        assertTrue(store.conflicts.value.isEmpty(), "no conflict with itself")
        assertFalse(remote.data.played, "no false Played")
        assertEquals(3, remote.data.playCount, "no double count")
        assertEquals(900L, remote.data.positionTicks)
    }

    @Test
    fun `progress someone else made wins the local record and leaves no untrue note`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 777))
        assertEquals(OfflineSettleResult.Conflict, handoff(remote).settle(500))
        assertTrue(remote.writes.isEmpty())
        assertTrue(store.conflicts.value.isEmpty())
        assertTrue(store.sync(remote))
        assertTrue(remote.writes.isEmpty())
    }

    @Test
    fun `a conflict already on record answers without a request`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 777))
        gateway.progress(tick(300))
        store.sync(remote)
        assertEquals(setOf("i"), store.conflicts.value.keys)
        remote.reachable = false
        assertEquals(OfflineSettleResult.Conflict, store.settle("i", remote))
    }

    @Test
    fun `no server, the wrong viewer or a stranger's answer all fail and keep every tick for later`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 100))
        val breakIt = listOf<() -> Unit>(
            { remote.reachable = false },
            {
                remote.reachable = true
                remote.viewer = false
            },
            {
                remote.viewer = true
                remote.garbled = true
            }
        )
        breakIt.forEachIndexed { index, make ->
            make()
            val position = 500L + index
            assertEquals(OfflineSettleResult.Failed, handoff(remote).settle(position), "case $index")
            assertTrue(remote.writes.isEmpty())
            assertEquals(position, store.resumeTicks("i"), "the place is kept")
            gateway.reopen()
            gateway.progress(tick(position + 10))
            assertEquals(position + 10, store.resumeTicks("i"), "local viewing carries on after reopening")
        }
        remote.garbled = false
        assertTrue(store.sync(remote))
        assertEquals<List<Pair<Long?, Boolean?>>>(listOf(512L to null), remote.writes)
    }

    @Test
    fun `a sealed gateway stays sealed until reopened, and a played report after reopening counts again`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 100))
        remote.reachable = false
        handoff(remote).settle(500)
        gateway.played()
        assertEquals(500L, store.resumeTicks("i"), "played didn't reach the record")
        gateway.reopen()
        gateway.played()
        assertEquals(0L, store.resumeTicks("i"), "finished on the download counts as ever")
    }

    @Test
    fun `settling with nothing owed answers without a request`() = runBlocking<Unit> {
        val remote = Remote()
        remote.reachable = false
        assertEquals(OfflineSettleResult.Settled, store.settle("i", remote))
    }

    private class OnlineServer : PlaybackGateway {
        var stops = 0
        var playeds = 0
        var answer: ClientResult<Unit> = ClientResult.Success(Unit)
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> = error("not here")
        override suspend fun start(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun progress(info: PlaybackProgressInfo) = ClientResult.Success(Unit)
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            stops++
            return answer
        }
        override suspend fun stopEncoding(sessionId: String) = ClientResult.Success(Unit)
        override suspend fun played(): ClientResult<Unit> {
            playeds++
            return ClientResult.Success(Unit)
        }
    }

    @Test
    fun `after the online session ends the device knows its progress, so a later download viewing is honest`() = runBlocking<Unit> {
        store.remember("i", OfflineUserData(positionTicks = 100))
        val remote = Remote(OfflineUserData(positionTicks = 100))
        handoff(remote).settle(500)
        val server = OnlineServer()
        val online = OfflineOnlineViewingGateway(server) { store.remember("i", remote.read(account, "i")) }
        // The online session reports through the delegate; the server moved to 900.
        remote.data = remote.data.copy(positionTicks = 900)
        online.stopped(stop(900))
        assertEquals(1, server.stops)
        assertEquals(900L, store.resumeTicks("i"), "the next local viewing resumes from the online point")
        // A new local viewing from there syncs cleanly, with no conflict against the device's own session.
        store.viewing("i", 2, 1_000)
        assertTrue(store.sync(remote))
        assertTrue(store.conflicts.value.isEmpty())
        assertEquals(1_000L, remote.data.positionTicks)
    }

    @Test
    fun `learning goes through every ending and can't break one`() = runBlocking<Unit> {
        var refreshes = 0
        val server = OnlineServer()
        val online = OfflineOnlineViewingGateway(server) {
            refreshes++
            throw IOException("offline again")
        }
        assertEquals(ClientResult.Success(Unit), online.stopped(stop(1)))
        assertEquals(ClientResult.Success(Unit), online.stopped(stop(2)) { })
        assertEquals(ClientResult.Success(Unit), online.played())
        assertEquals(3, refreshes)
        assertEquals(2, server.stops)
        assertEquals(1, server.playeds)
        // Everything else belongs to the normal online gateway.
        assertEquals(ClientResult.Success(Unit), online.start(tick(1)))
    }
}
