/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

@Serializable
data class QueueEntry(val id: String, val media: String, val downloaded: Boolean = false)

@Serializable
data class QueueState(
    val server: String,
    val viewer: String,
    val entries: List<QueueEntry> = emptyList(),
    val order: List<String> = entries.map { it.id },
    val current: String? = null,
    val history: List<String> = emptyList(),
    val visited: List<String> = history.distinct(),
    val positionTicks: Long = 0,
    val paused: Boolean = true,
    val repeat: String = "off",
    val shuffled: Boolean = false,
    val revision: Long = 0,
    val generation: Long = 0,
    val completion: Long = 0,
    val operations: List<String> = emptyList(),
    val failed: List<String> = emptyList()
)

@Serializable
data class QueueEvent(
    val op: String,
    val revision: Long = 0,
    val generation: Long = 0,
    val operationId: String = "",
    val entries: List<QueueEntry> = emptyList(),
    val entry: String? = null,
    val index: Int = 0,
    val permutation: List<String> = emptyList(),
    val repeat: String = "off",
    val ticks: Long = 0,
    val completion: Long = 0,
    val music: Boolean = false
)

@Serializable
data class QueueReduction(val queue: QueueState, val outcome: String = "unchanged", val removed: List<String> = emptyList())

private fun validQueue(q: QueueState): Boolean {
    val ids = q.entries.map { it.id }
    return q.server.isNotBlank() &&
        q.viewer.isNotBlank() &&
        q.entries.all { it.id.isNotBlank() && it.media.isNotBlank() } &&
        ids.distinct().size == ids.size &&
        q.order.size == ids.size &&
        q.order.toSet() == ids.toSet() &&
        (q.current == null || q.current in ids) &&
        q.history.all { it in ids } &&
        q.visited.all { it in ids } &&
        q.positionTicks >= 0 &&
        q.repeat in listOf("off", "one", "all") &&
        q.revision >= 0 &&
        q.generation >= 0 &&
        q.completion >= 0
}

/** Pure proposal. The commit reducer below persists it before any observer sees the edit. */
fun reduceQueue(q: QueueState, event: QueueEvent): QueueReduction {
    val known = listOf(
        "play-now", "play-next", "append", "remove", "move", "clear-upcoming", "clear-all", "repeat", "shuffle", "unshuffle",
        "end", "next", "previous", "error", "retry", "play", "pause", "position"
    )
    if (event.op !in known) error("Unknown queue operation: ${event.op}")
    if (!validQueue(q)) return QueueReduction(q, "invalid-queue")
    if (event.generation != q.generation) return QueueReduction(q, "stale-generation")
    if (event.operationId.isNotEmpty() && event.operationId in q.operations) return QueueReduction(q, "duplicate")
    if (event.revision != q.revision) return QueueReduction(q, "refresh-required")
    if (q.revision == Long.MAX_VALUE) return QueueReduction(q, "revision-exhausted")
    if (q.completion == Long.MAX_VALUE && event.op in listOf("play-now", "remove", "clear-all", "previous", "play", "retry")) {
        return QueueReduction(q, "completion-exhausted")
    }
    fun changed(next: QueueState, outcome: String = "changed"): QueueReduction = QueueReduction(
        next.copy(revision = q.revision + 1, operations = q.operations + listOf(event.operationId).filter(String::isNotEmpty)),
        outcome
    )
    fun invalid(reason: String) = QueueReduction(q, reason)
    val ids = q.entries.map { it.id }
    val currentIndex = q.order.indexOf(q.current)
    return when (event.op) {
        "play-now", "play-next", "append" -> {
            val block = event.entries.map { it.id }
            if (block.isEmpty() ||
                block.distinct().size != block.size ||
                event.entries.any { it.id.isBlank() || it.media.isBlank() } ||
                (event.op != "play-now" && block.any { it in ids })
            ) {
                return invalid("invalid-entries")
            }
            if (event.op == "play-now") {
                return changed(
                    q.copy(
                        entries = event.entries,
                        order = block,
                        current = block.first(),
                        history = emptyList(),
                        visited = emptyList(),
                        positionTicks = 0,
                        paused = false,
                        shuffled = false,
                        completion = q.completion + 1,
                        failed = emptyList()
                    )
                )
            }
            val at = if (q.current == null) {
                0
            } else if (event.op == "play-next") {
                currentIndex + 1
            } else {
                q.order.size
            }
            val order = q.order.toMutableList().also { it.addAll(at, block) }
            val canonicalAt = if (q.current == null) {
                0
            } else if (event.op == "play-next") {
                ids.indexOf(q.current) + 1
            } else {
                ids.size
            }
            val canonical = q.entries.toMutableList().also { it.addAll(canonicalAt, event.entries) }
            changed(q.copy(entries = canonical, order = order, paused = if (q.current == null) true else q.paused))
        }
        "remove" -> {
            if (event.entry !in ids) return invalid("missing-entry")
            val order = q.order.filter { it != event.entry }
            val removedCurrent = event.entry == q.current
            val current = if (removedCurrent) order.getOrNull(currentIndex) ?: order.lastOrNull() else q.current
            changed(
                q.copy(
                    entries = q.entries.filter { it.id != event.entry },
                    order = order,
                    current = current,
                    history = q.history.filter { it != event.entry && it != current },
                    visited = q.visited.filter { it != event.entry && it != current },
                    positionTicks = if (removedCurrent) 0 else q.positionTicks,
                    completion = if (removedCurrent) q.completion + 1 else q.completion,
                    paused = if (current == null) true else q.paused,
                    failed = q.failed.filter { it != event.entry }
                )
            )
        }
        "move" -> {
            if (event.entry !in ids || event.index !in ids.indices) return invalid("invalid-move")
            val entries = q.entries.toMutableList()
            val moving = entries.removeAt(ids.indexOf(event.entry))
            entries.add(event.index, moving)
            // Reorder only upcoming when shuffled; the stored audible permutation remains authoritative.
            changed(q.copy(entries = entries, order = if (q.shuffled) q.order else entries.map { it.id }))
        }
        "clear-upcoming" -> {
            val keep = (q.history + listOfNotNull(q.current)).toSet()
            changed(
                q.copy(
                    entries = q.entries.filter { it.id in keep },
                    order = q.order.filter { it in keep },
                    visited = q.visited.filter {
                        it in
                            keep
                    }
                )
            )
        }
        "clear-all" -> changed(
            q.copy(
                entries = emptyList(), order = emptyList(), current = null, history = emptyList(), visited = emptyList(), positionTicks = 0,
                paused = true, failed = emptyList(), shuffled = false, completion = q.completion + 1
            )
        )
        "repeat" -> if (event.repeat !in listOf("off", "one", "all")) invalid("invalid-repeat") else changed(q.copy(repeat = event.repeat))
        "shuffle" -> {
            val visited = (q.visited + listOfNotNull(q.current)).toSet()
            val upcoming = ids.filter { it !in visited }
            if (event.permutation.size != upcoming.size ||
                event.permutation.toSet() != upcoming.toSet()
            ) {
                return invalid("invalid-permutation")
            }
            changed(q.copy(order = q.order.filter { it in visited } + event.permutation, shuffled = true))
        }
        "unshuffle" -> {
            val visited = (q.visited + listOfNotNull(q.current)).toSet()
            changed(q.copy(order = q.order.filter { it in visited } + ids.filter { it !in visited }, shuffled = false))
        }
        "position" -> if (event.ticks < 0) invalid("invalid-ticks") else changed(q.copy(positionTicks = event.ticks))
        "pause" -> changed(q.copy(paused = true))
        "play" -> changed(
            q.copy(
                current = q.current ?: q.order.firstOrNull(),
                paused = q.order.isEmpty(),
                failed = emptyList(),
                completion =
                q.completion + 1
            )
        )
        "retry" -> if (q.current ==
            null
        ) {
            invalid("no-current")
        } else {
            changed(q.copy(paused = false, failed = emptyList(), completion = q.completion + 1))
        }
        "previous" -> {
            if (q.current == null) return invalid("no-current")
            if (q.positionTicks > 30_000_000) return changed(q.copy(positionTicks = 0, failed = emptyList(), completion = q.completion + 1))
            val prior = q.history.lastOrNull() ?: q.order.getOrNull(currentIndex - 1)
                ?: q.order.lastOrNull().takeIf { q.repeat == "all" } ?: q.current
            changed(
                q.copy(
                    current = prior,
                    visited = q.order.take(q.order.indexOf(prior)),
                    completion = q.completion + 1,
                    positionTicks = 0,
                    history = if (q.history.lastOrNull() == prior) q.history.dropLast(1) else q.history,
                    failed = emptyList()
                )
            )
        }
        else -> {
            if (event.completion != q.completion) return invalid("duplicate-completion")
            if (q.current == null) return invalid("no-current")
            if (q.completion == Long.MAX_VALUE) return invalid("completion-exhausted")
            val error = event.op == "error"
            if (error && !event.music) return changed(q.copy(paused = true, completion = q.completion + 1), "video-error")
            if (event.op == "end" && q.repeat == "one") {
                return changed(q.copy(positionTicks = 0, completion = q.completion + 1, failed = emptyList()))
            }
            val failed = if (error) (q.failed + q.current).distinct() else emptyList()
            val upcoming = q.order.drop(currentIndex + 1) + if (q.repeat == "all") q.order.take(currentIndex + 1) else emptyList()
            val next = upcoming.firstOrNull { it !in failed }
            if (next ==
                null
            ) {
                return changed(
                    q.copy(paused = true, completion = q.completion + 1, failed = failed),
                    if (error) "exhausted" else "stopped"
                )
            }
            changed(
                q.copy(
                    current = next,
                    history = q.history + q.current,
                    visited = if (q.order.indexOf(next) <= currentIndex) emptyList() else (q.visited + q.current).distinct(),
                    positionTicks = 0,
                    completion = q.completion + 1,
                    failed = failed
                ),
                if (error) "skipped-unavailable" else "changed"
            )
        }
    }
}

@Serializable
data class QueueStoreState(
    val committed: QueueState,
    val pending: QueueState? = null,
    val retry: QueueState? = null,
    val ticket: Long = 0,
    val effect: String = "none",
    val reason: String? = null,
    val removed: List<String> = emptyList()
)

@Serializable
data class QueueStoreEvent(
    val op: String,
    val edit: QueueEvent? = null,
    val ticket: Long = 0,
    val saved: QueueState? = null,
    val server: String? = null,
    val viewer: String? = null,
    val accessible: List<String> = emptyList(),
    val offline: Boolean = false
)

/** Adapter supplies validation results, persists pending, then acknowledges the exact ticket. */
fun reduceQueueStore(state: QueueStoreState, event: QueueStoreEvent): QueueStoreState {
    val s = state.copy(effect = "none", reason = null)
    when (event.op) {
        "persisted", "persist-failed" -> {
            if (event.ticket != s.ticket || s.pending == null) return s.copy(reason = "stale-ticket")
            return if (event.op == "persisted") {
                s.copy(committed = s.pending, pending = null, retry = null, effect = "publish")
            } else {
                s.copy(
                    pending = null,
                    retry = s.pending,
                    reason = "persist-failed",
                    effect = "offer-retry"
                )
            }
        }
        "edit", "restore", "partition", "retry-persist" -> Unit
        else -> error("Unknown queue-store operation: ${event.op}")
    }
    if (s.ticket == Long.MAX_VALUE) return s.copy(reason = "ticket-exhausted")
    if (event.op == "partition") {
        val server = event.server?.takeIf(String::isNotBlank) ?: return s.copy(reason = "invalid-partition")
        val viewer = event.viewer?.takeIf(String::isNotBlank) ?: return s.copy(reason = "invalid-partition")
        if (s.committed.generation == Long.MAX_VALUE) return s.copy(reason = "generation-exhausted")
        return s.copy(
            committed = QueueState(server, viewer, generation = s.committed.generation + 1),
            pending = null,
            retry = null,
            ticket = s.ticket + 1,
            removed = emptyList()
        )
    }
    if (s.pending != null) return s.copy(reason = "commit-pending")
    if (event.op == "retry-persist") {
        val retry = s.retry ?: return s.copy(reason = "nothing-to-retry")
        return s.copy(pending = retry, retry = null, ticket = s.ticket + 1, effect = "persist")
    }
    val result = if (event.op == "edit") {
        reduceQueue(s.committed, event.edit ?: return s.copy(reason = "missing-edit"))
    } else {
        val saved = event.saved ?: return s.copy(reason = "missing-saved")
        if (saved.server != s.committed.server || saved.viewer != s.committed.viewer) return s.copy(reason = "wrong-partition")
        if (!validQueue(saved) || maxOf(saved.generation, s.committed.generation) == Long.MAX_VALUE) return s.copy(reason = "invalid-queue")
        val entries = saved.entries.filter { it.id in event.accessible && (!event.offline || it.downloaded) }
        val keep = entries.map { it.id }.toSet()
        val order = saved.order.filter { it in keep }
        val current = saved.current?.takeIf { it in keep } ?: order.firstOrNull()
        QueueReduction(
            saved.copy(
                entries = entries,
                order = order,
                current = current,
                history = saved.history.filter { it in keep && it != current },
                visited = saved.visited.filter { it in keep && it != current },
                paused = true,
                positionTicks = if (current == saved.current) saved.positionTicks else 0,
                failed = emptyList(),
                generation = maxOf(saved.generation, s.committed.generation) + 1,
                operations = emptyList()
            ),
            "changed",
            saved.entries.map { it.id }.filter { it !in keep }
        )
    }
    if (result.outcome !in
        listOf("changed", "video-error", "exhausted", "stopped", "skipped-unavailable")
    ) {
        return s.copy(reason = result.outcome)
    }
    return s.copy(pending = result.queue, retry = null, ticket = s.ticket + 1, effect = "persist", removed = result.removed)
}

@Serializable
data class TransferState(
    val queue: QueueState,
    val intent: ControlIntent,
    val effect: String = "none",
    val unavailable: List<String> = emptyList()
)

@Serializable
data class TransferEvent(
    val op: String,
    val capabilities: List<String> = emptyList(),
    val matchingAudio: List<Int> = emptyList(),
    val matchingSubtitles: List<Int> = emptyList(),
    val confirmed: Boolean = false,
    val authoritative: QueueState? = null
)

fun reduceTransfer(state: TransferState, event: TransferEvent): TransferState {
    val s = state.copy(effect = "none", unavailable = emptyList())
    if (event.op == "attach") {
        val q = event.authoritative ?: return s.copy(effect = "refresh")
        if (!validQueue(q) || q.server != s.queue.server || q.viewer != s.queue.viewer) return s.copy(effect = "reject-partition")
        return s.copy(
            queue = q,
            intent = ControlIntent(q.current, q.positionTicks, q.paused, q.order, q.repeat, if (q.shuffled) q.order else emptyList())
        )
    }
    if (event.op != "prepare") error("Unknown queue-transfer operation: ${event.op}")
    if (s.intent.entry != s.queue.current || s.intent.queue != s.queue.order) return s.copy(effect = "refresh")
    val audioMatches = "audio" in event.capabilities && s.intent.audio in event.matchingAudio
    val subtitleMatches = "subtitle" in event.capabilities && s.intent.subtitle in event.matchingSubtitles
    val required = listOfNotNull(
        "queue",
        "repeat".takeIf { s.queue.repeat != "off" },
        "shuffle".takeIf { s.queue.shuffled },
        "audio".takeIf { s.intent.audio != null },
        "subtitle".takeIf { s.intent.subtitle != null }
    )
    val unavailable = required.filter {
        it !in event.capabilities || (it == "audio" && !audioMatches) || (it == "subtitle" && !subtitleMatches)
    }
    if (unavailable.isNotEmpty() && !event.confirmed) return s.copy(effect = "require-replacement", unavailable = unavailable)
    return s.copy(
        effect = "transfer",
        unavailable = unavailable,
        intent = s.intent.copy(
            audio = s.intent.audio.takeIf { audioMatches },
            subtitle = s.intent.subtitle.takeIf { subtitleMatches },
            repeat = if ("repeat" in event.capabilities) s.intent.repeat else "off",
            shuffle = if ("shuffle" in event.capabilities) s.intent.shuffle else emptyList(),
            queue = if ("queue" in event.capabilities) s.intent.queue else listOfNotNull(s.intent.entry)
        )
    )
}
