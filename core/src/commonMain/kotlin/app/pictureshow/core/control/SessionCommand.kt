/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

@Serializable
data class PendingCommand(
    val id: Long,
    val operation: String,
    val entry: String?,
    val ticks: Long?,
    val paused: Boolean?,
    val sentAtMs: Long,
    val stopped: Boolean? = null,
    val result: String = "sent"
)

@Serializable
data class CommandState(
    val owner: ViewingIdentity,
    val capabilities: List<String> = listOf("play", "pause", "seek", "stop"),
    val authorized: Boolean = true,
    val fresh: Boolean = true,
    val observed: ControlIntent = ControlIntent(),
    val durationTicks: Long = Long.MAX_VALUE,
    val sequence: Long = 0,
    val seekTarget: Long? = null,
    val pending: PendingCommand? = null,
    val effect: String = "none",
    val rejection: String? = null
)

@Serializable
data class CommandEvent(
    val op: String,
    val owner: ViewingIdentity? = null,
    val id: Long = 0,
    val nowMs: Long = 0,
    val value: String? = null,
    val entry: String? = null,
    val paused: Boolean? = null,
    val stopped: Boolean? = null,
    val capabilities: List<String> = emptyList(),
    val authorized: Boolean = true
)

/**
 * How long a sent command may take to show on the target before it reads as unconfirmed. A transport change shows within
 * a report or two. Starting a title also waits for the target to wake, negotiate playback and buffer before its start
 * report reaches the server: on a real TV the start alone took up to 6.2 s (rc33, 53 starts), and a 5 s window read a
 * title already playing as "didn't confirm".
 */
fun confirmWindowMs(operation: String): Long = if (operation == "play-now") 12_000 else 5_000

/** One caller serializes events. Effects describe one dispatch, never a retry queue. */
fun reduceCommand(state: CommandState, event: CommandEvent): CommandState {
    val s = state.copy(effect = "none", rejection = null)
    fun rejected(reason: String) = s.copy(rejection = reason)
    if (event.op !in listOf("intent", "http", "observe", "timeout", "denied", "disconnect", "refresh")) {
        error("Unknown session-command operation: ${event.op}")
    }
    if (event.owner != s.owner) return rejected("stale-owner")
    return when (event.op) {
        "disconnect" -> s.copy(fresh = false, seekTarget = null, pending = s.pending?.copy(result = "unknown"), effect = "refresh")
        "denied" -> s.copy(
            authorized = false,
            seekTarget = null,
            capabilities = emptyList(),
            pending = s.pending?.copy(result = "rejected")
        )
        "refresh" -> {
            val ticks = event.value?.let(::exactLong)
            if (ticks == null || ticks < 0 || ticks > s.durationTicks) return rejected("invalid-ticks")
            s.copy(
                fresh = true,
                seekTarget = null,
                authorized = event.authorized,
                capabilities = event.capabilities.distinct(),
                observed = s.observed.copy(ticks = ticks, entry = event.entry, paused = event.paused ?: true),
                pending = s.pending?.copy(result = if (s.pending.result == "sent") "unknown" else s.pending.result)
            )
        }
        "http" -> s // A 204 only says dispatched. It is never observation of playback.
        "timeout" -> {
            val pending = s.pending ?: return s
            if (event.id != pending.id ||
                pending.result != "sent" ||
                event.nowMs < pending.sentAtMs ||
                event.nowMs - pending.sentAtMs < confirmWindowMs(pending.operation)
            ) {
                return s
            }
            s.copy(pending = pending.copy(result = "unknown"), fresh = false, seekTarget = null, effect = "refresh")
        }
        "observe" -> {
            val pending = s.pending ?: return s
            val ticks = event.value?.let(::exactLong) ?: return rejected("invalid-ticks")
            if (ticks < 0 || ticks > s.durationTicks) return rejected("invalid-ticks")
            if (event.id != pending.id || pending.result != "sent") return s
            if (event.nowMs < pending.sentAtMs || event.nowMs - pending.sentAtMs >= confirmWindowMs(pending.operation)) {
                return s.copy(pending = pending.copy(result = "unknown"), fresh = false, seekTarget = null, effect = "refresh")
            }
            val matches = (pending.paused == null || event.paused == pending.paused) &&
                (pending.ticks == null || ticks == pending.ticks) &&
                (pending.entry == null || event.entry == pending.entry) &&
                (pending.stopped == null || event.stopped == pending.stopped)
            val observed = s.observed.copy(entry = event.entry, ticks = ticks, paused = event.paused ?: s.observed.paused)
            s.copy(
                observed = observed,
                seekTarget = s.seekTarget.takeUnless { it == ticks },
                pending = pending.copy(result = if (matches) "confirmed" else "sent")
            )
        }
        else -> {
            if (!s.authorized) return rejected("denied")
            if (!s.fresh) return rejected("refresh-required")
            if (event.id <= s.sequence || event.nowMs < 0) return rejected("stale-command")
            val operation = event.value ?: return rejected("unsupported")
            val capability = if (operation in listOf("seek-absolute", "seek-relative")) "seek" else operation
            if (capability !in s.capabilities ||
                operation !in listOf(
                    "play",
                    "pause",
                    "stop",
                    "seek-absolute",
                    "seek-relative",
                    "play-now"
                )
            ) {
                return rejected("unsupported")
            }
            if (operation == "play-now" && event.entry.isNullOrBlank()) return rejected("missing-entry")
            val seeking = operation.startsWith("seek-")
            val amount = if (seeking) event.entry?.let(::exactLong) ?: return rejected("invalid-ticks") else null
            if (operation == "seek-absolute" && requireNotNull(amount) < 0) return rejected("invalid-ticks")
            val baseline = s.seekTarget ?: s.observed.ticks
            val target = when (operation) {
                "seek-relative" -> boundedAdd(baseline, requireNotNull(amount), s.durationTicks.coerceAtLeast(0))
                "seek-absolute" -> requireNotNull(amount).coerceAtMost(s.durationTicks.coerceAtLeast(0))
                else -> null
            }
            val paused = when (operation) {
                "pause", "stop" -> true
                "play", "play-now" -> false
                else -> null
            }
            s.copy(
                sequence = event.id,
                seekTarget = target ?: s.seekTarget,
                pending = PendingCommand(
                    event.id,
                    operation,
                    event.entry.takeUnless { seeking },
                    target,
                    paused,
                    event.nowMs,
                    stopped = true.takeIf { operation == "stop" }
                ),
                effect = "dispatch"
            )
        }
    }
}
