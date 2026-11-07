/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import app.pictureshow.core.control.QueueEntry
import app.pictureshow.core.control.QueueState
import kotlinx.serialization.Serializable

/** Each block of entries gets fresh identities, so the same song twice is two entries (QUE-01). */
fun musicEntries(media: List<String>, firstSequence: Long): List<QueueEntry> =
    media.mapIndexed { index, id -> QueueEntry("m${firstSequence + index}", id) }

/** Lehmer's generator over a seed, written out so every platform draws the same order from the same seed. */
fun musicPermutation(ids: List<String>, seed: Long): List<String> {
    val result = ids.toMutableList()
    var state = (seed.mod(2147483646L)) + 1
    for (i in result.size - 1 downTo 1) {
        state = state * 48271 % 2147483647
        val j = (state % (i + 1)).toInt()
        val swap = result[i]
        result[i] = result[j]
        result[j] = swap
    }
    return result
}

/**
 * What the output holds: the current entry and what follows it, a wrap under Repeat All included. It's short so
 * negotiation runs per track as it comes near, and long enough that the next track is ready before this one ends,
 * which is what gapless needs. A failed entry is left out, since the queue won't return to it on this traversal.
 */
fun musicWindow(q: QueueState, size: Int = 3): List<String> {
    val current = q.current ?: return emptyList()
    val at = q.order.indexOf(current)
    if (at < 0) return emptyList()
    if (q.repeat == "one") return listOf(current)
    val after = q.order.drop(at + 1) + if (q.repeat == "all") q.order.take(at) else emptyList()
    return listOf(current) + after.filter { it !in q.failed }.take((size - 1).coerceAtLeast(0))
}

/** Whether the output repeats by itself. One entry under Repeat All has nothing to follow it but itself. */
fun musicOutputRepeats(q: QueueState): Boolean = q.current != null && (q.repeat == "one" || (q.repeat == "all" && q.order.size == 1))

@Serializable
data class WindowPlan(
    /** The current entry isn't what the output holds: replace everything and start there. */
    val replace: Boolean = false,
    val removeBefore: Int = 0,
    val removeAfter: Int = 0,
    val add: List<String> = emptyList()
)

/**
 * The smallest edit from what the output holds to [target]. Anything already correct stays, and the playing item is
 * never touched, so an edit behind or ahead of the audible track can't interrupt it.
 */
fun planWindow(held: List<String>, playing: String?, target: List<String>): WindowPlan {
    val head = target.firstOrNull() ?: return WindowPlan(replace = held.isNotEmpty())
    if (playing != head || head !in held) return WindowPlan(replace = true, add = target)
    val at = held.indexOf(head)
    val after = held.drop(at + 1)
    val wanted = target.drop(1)
    val keep = after.zip(wanted).takeWhile { (a, b) -> a == b }.size
    return WindowPlan(removeBefore = at, removeAfter = after.size - keep, add = wanted.drop(keep))
}

@Serializable
data class MusicPlayRequest(
    /** The album, playlist or list this play came from; null is a single track with no context. */
    val context: String?,
    val queueEntries: Int,
    val queueContext: String?,
    val remoteAttached: Boolean = false,
    val castAttached: Boolean = false
)

/**
 * "play": start now. "jump": the same context is playing, so move inside it. "confirm-replace": another context's
 * queue would be lost. "confirm-destination": something else is attached, and its queue is never overwritten silently.
 */
fun musicPlayDecision(r: MusicPlayRequest): String = when {
    r.remoteAttached || r.castAttached -> "confirm-destination"
    r.queueEntries == 0 -> "play"
    r.context != null && r.context == r.queueContext -> "jump"
    else -> "confirm-replace"
}

/** What's left to play on this traversal, for the Now playing "up next" list. */
fun musicUpcoming(q: QueueState): List<String> {
    val at = q.order.indexOf(q.current)
    return if (at < 0) q.order else q.order.drop(at + 1)
}
