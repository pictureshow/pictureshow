/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.control

import kotlinx.serialization.Serializable

/**
 * The queue of a Jellyfin play target. `current` and `upNext` are media IDs, which is all a target can observe.
 *
 * A viewer names one title; a relayed command can carry more. Given one episode, Jellyfin replaces the ID list with
 * that episode and the rest of its series, for every play command, before relaying it, so the first title of a command is
 * the viewer's and the others are the automatic continuation.
 */
@Serializable
data class RemoteQueueState(
    val queue: QueueState,
    val sequence: Long = 0,
    val current: String? = null,
    val upNext: List<String> = emptyList()
)

@Serializable
data class RemoteQueueEvent(
    val op: String,
    val items: List<String> = emptyList(),
    val watching: String? = null,
    val item: String? = null
)

fun reduceRemoteQueue(state: RemoteQueueState, event: RemoteQueueEvent): RemoteQueueState {
    val next = when (event.op) {
        "open" -> open(state, event.items)
        "add-next", "add-last" -> add(state, requireNotNull(event.watching), event.items, event.op == "add-next")
        "previous" -> if (state.queue.current == null) state else edit(state, "previous")
        // Follows what really started: the queue's next entry advances; anything else ends the queue.
        "observed" -> when (requireNotNull(event.item)) {
            state.current -> state
            state.upNext.firstOrNull() -> edit(state, "next")
            else -> clear(state)
        }
        "clear" -> clear(state)
        else -> error("Unknown remote-queue operation: ${event.op}")
    }
    val q = next.queue
    val media = q.entries.associate { it.id to it.media }
    return next.copy(current = media[q.current], upNext = q.order.drop(q.order.indexOf(q.current) + 1).mapNotNull(media::get))
}

private fun clear(s: RemoteQueueState) = s.copy(queue = QueueState(s.queue.server, s.queue.viewer, revision = s.queue.revision + 1))

private fun edit(s: RemoteQueueState, op: String, entries: List<String> = emptyList(), automatic: List<String> = emptyList()): RemoteQueueState {
    var sequence = s.sequence
    fun made(items: List<String>) = items.map { QueueEntry("r${++sequence}", it) }
    val q = s.queue
    val event = QueueEvent(
        op,
        revision = q.revision,
        generation = q.generation,
        entries = made(entries),
        automatic = made(automatic),
        completion = q.completion
    )
    return s.copy(queue = reduceQueue(q, event).queue, sequence = sequence)
}

private fun open(s: RemoteQueueState, items: List<String>): RemoteQueueState {
    val first = items.firstOrNull() ?: return s
    return edit(s, "play-now", listOf(first), items.drop(1))
}

/** Queueing from a viewing started by hand adopts that viewing as the queue's current entry first. */
private fun add(s: RemoteQueueState, watching: String, items: List<String>, next: Boolean): RemoteQueueState {
    val named = items.firstOrNull() ?: return s
    val adopted = if (s.current == watching) s else open(s, listOf(watching))
    val entries = if (repeatsNeighbour(adopted.queue, named, next)) emptyList() else listOf(named)
    return edit(adopted, if (next) "play-next" else "append", entries, items.drop(1))
}

/**
 * A target only sees media IDs, so the same title twice in a row looks like one title that never ended: it would neither
 * advance past the second copy nor stop repeating it. A copy elsewhere in the queue is fine, and stays.
 */
private fun repeatsNeighbour(q: QueueState, named: String, next: Boolean): Boolean {
    val media = q.entries.associate { it.id to it.media }
    val current = q.order.indexOf(q.current)
    val upcoming = q.order.drop(current + 1).filter { it !in q.automatic || media[it] != named }
    val at = if (next) 0 else upcoming.indexOfFirst { it in q.automatic }.takeIf { it >= 0 } ?: upcoming.size
    val before = upcoming.getOrNull(at - 1) ?: q.current
    return media[before] == named || media[upcoming.getOrNull(at)] == named
}
