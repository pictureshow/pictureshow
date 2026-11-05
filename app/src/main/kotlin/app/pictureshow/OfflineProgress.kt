/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.offline.OfflineConflict
import app.pictureshow.core.offline.OfflineOutboxEntry
import app.pictureshow.core.offline.OfflineOutboxEvent
import app.pictureshow.core.offline.OfflineUserData
import app.pictureshow.core.offline.reduceOfflineOutbox
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class OfflineProgressFile(
    val sequence: Long = 0,
    val lastKnown: Map<String, OfflineUserData> = emptyMap(),
    val outbox: Map<String, OfflineOutboxEntry> = emptyMap()
)

/** What the sync needs from the server, narrow so tests can answer with exact states. */
internal interface OfflineProgressRemote {
    suspend fun sameViewer(account: Account): Boolean
    suspend fun read(account: Account, itemId: String): OfflineUserData

    /** The HTTP status, or null when the answer never arrived. */
    suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int?
}

internal class OfflineApiProgressRemote(private val api: OfflineApi) : OfflineProgressRemote {
    override suspend fun sameViewer(account: Account) = api.sameViewer(account)
    override suspend fun read(account: Account, itemId: String) = api.userData(account, itemId)
    override suspend fun write(account: Account, itemId: String, positionTicks: Long?, played: Boolean?): Int? = try {
        api.writeUserData(account, itemId, app.pictureshow.core.offline.OfflineUserDataWrite(positionTicks, played))
        200
    } catch (e: OfflineApiException) {
        e.status
    } catch (e: IOException) {
        null
    }
}

/** One viewer's durable outbox (OFF-07, OFF-08): writes commit to disk before anything touches the server. */
internal class OfflineProgressStore(private val file: File, private val account: Account) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableConflicts = MutableStateFlow(emptyMap<String, OfflineConflict>())
    private var cached: OfflineProgressFile? = null

    /** Items where the server's progress was kept. */
    val conflicts: StateFlow<Map<String, OfflineConflict>> = mutableConflicts

    private fun load(): OfflineProgressFile = cached ?: run {
        val loaded = runCatching { json.decodeFromString<OfflineProgressFile>(file.readText()) }.getOrDefault(OfflineProgressFile())
        // A crash mid-write resolves through a read first; the reducer decides.
        val restarted = loaded.copy(
            outbox = loaded.outbox.mapValues { (_, entry) ->
                reduceOfflineOutbox(entry, OfflineOutboxEvent("process-restart")).entry ?: entry
            }
        )
        cached = restarted
        publish(restarted)
        restarted
    }

    private fun save(value: OfflineProgressFile) {
        file.writeAtomically(json.encodeToString(OfflineProgressFile.serializer(), value))
        cached = value
        publish(value)
    }

    private fun publish(value: OfflineProgressFile) {
        mutableConflicts.value = value.outbox.mapNotNull { (id, entry) -> entry.conflict?.let { id to it } }.toMap()
    }

    /** A viewing resumes from what this device last saw. */
    suspend fun resumeTicks(itemId: String): Long = mutex.withLock {
        val saved = load()
        val entry = saved.outbox[itemId]
        when {
            entry?.played == true -> 0
            entry?.positionTicks != null -> entry.positionTicks ?: 0
            else -> saved.lastKnown[itemId]?.takeIf { !it.played }?.positionTicks ?: 0
        }
    }

    suspend fun watched(itemIds: List<String>): Set<String> = mutex.withLock {
        val saved = load()
        itemIds.filter { id -> saved.outbox[id]?.played ?: saved.lastKnown[id]?.played ?: false }.toSet()
    }

    suspend fun remember(itemId: String, data: OfflineUserData) = mutex.withLock {
        val saved = load()
        save(saved.copy(lastKnown = saved.lastKnown + (itemId to data)))
    }

    suspend fun newSequence(): Long = mutex.withLock {
        val saved = load()
        val next = saved.sequence + 1
        save(saved.copy(sequence = next))
        next
    }

    suspend fun viewing(itemId: String, sequence: Long, positionTicks: Long) = apply(itemId, "viewing", positionTicks, sequence)

    suspend fun completed(itemId: String, sequence: Long) = apply(itemId, "completed", null, sequence)

    private suspend fun apply(itemId: String, op: String, positionTicks: Long?, sequence: Long) = mutex.withLock {
        val saved = load()
        val base = saved.lastKnown[itemId] ?: OfflineUserData()
        val result = reduceOfflineOutbox(
            saved.outbox[itemId],
            OfflineOutboxEvent(
                op,
                server = account.server.id,
                viewer = account.user.id,
                itemId = itemId,
                positionTicks = positionTicks,
                sequence = sequence,
                base = base
            )
        )
        if (result.ignored == null) save(saved.copy(outbox = saved.outbox.withEntry(itemId, result.entry)))
    }

    suspend fun dismissConflict(itemId: String) = mutex.withLock {
        val saved = load()
        val entry = saved.outbox[itemId] ?: return@withLock
        val result = reduceOfflineOutbox(entry, OfflineOutboxEvent("dismiss-conflict"))
        save(saved.copy(outbox = saved.outbox.withEntry(itemId, result.entry)))
    }

    /**
     * Same viewer first, then read before every write. A failure leaves the record to try again later; a crash during a
     * write is read first by the reducer, so an unknown outcome can't bump PlayCount.
     */
    suspend fun sync(remote: OfflineProgressRemote): Boolean = mutex.withLock {
        val dirty = load().outbox.filterValues { it.phase != "conflict" && it.phase != "synced" }
        if (dirty.isEmpty()) return@withLock true
        val same = runCatching { remote.sameViewer(account) }.getOrNull() ?: return@withLock false
        if (!same) return@withLock false
        var clean = true
        dirty.forEach { (itemId, start) ->
            try {
                syncOne(remote, itemId, start)
            } catch (e: IOException) {
                clean = false
            }
        }
        clean
    }

    private suspend fun syncOne(remote: OfflineProgressRemote, itemId: String, start: OfflineOutboxEntry) {
        var entry: OfflineOutboxEntry? = start
        var step = reduceOfflineOutbox(entry, OfflineOutboxEvent("reconnect", server = account.server.id, viewer = account.user.id))
        // Bounded: read, write, read back, and one reconcile if the answer was lost.
        repeat(6) {
            if (step.ignored != null) return
            entry = step.entry
            when (step.effect) {
                "read" -> {
                    val current = remote.read(account, itemId)
                    remember(itemId, current, entry)
                    step = reduceOfflineOutbox(entry, OfflineOutboxEvent("server-state", current = current))
                }
                "write" -> {
                    // Durable before the request leaves: a kill between the two is an ambiguous write, read first.
                    persist(itemId, step.entry)
                    val write = step.write
                    val status = remote.write(account, itemId, write?.positionTicks, write?.played)
                    if (status == null || status !in 200..299) {
                        persist(itemId, step.entry)
                        throw IOException("Write unconfirmed")
                    }
                    step = reduceOfflineOutbox(entry, OfflineOutboxEvent("write-result", status = status))
                }
                "readback" -> {
                    val current = remote.read(account, itemId)
                    remember(itemId, current, entry)
                    step = reduceOfflineOutbox(entry, OfflineOutboxEvent("readback", current = current))
                }
                else -> {
                    persist(itemId, step.entry)
                    return
                }
            }
        }
        persist(itemId, step.entry)
    }

    private fun remember(itemId: String, data: OfflineUserData, entry: OfflineOutboxEntry?) {
        if (entry != null) cached = load().let { it.copy(lastKnown = it.lastKnown + (itemId to data)) }
    }

    private fun persist(itemId: String, entry: OfflineOutboxEntry?) {
        val saved = load()
        save(saved.copy(outbox = saved.outbox.withEntry(itemId, entry)))
    }

    private fun Map<String, OfflineOutboxEntry>.withEntry(itemId: String, entry: OfflineOutboxEntry?) =
        if (entry == null) this - itemId else this + (itemId to entry)
}
