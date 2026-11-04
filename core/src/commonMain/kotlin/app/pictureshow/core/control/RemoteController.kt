/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

/** Observed position runs on after a seek lands, and a report trails it. Earlier than this means it did not land. */
private const val SEEK_EARLY_TICKS = 10_000_000L
private const val SEEK_LATE_TICKS = 60_000_000L

/** Next, previous, track and volume have their own observation; a generic playback report cannot confirm them. */
@Serializable
data class RemoteFollowUp(
    val id: Long,
    val op: String,
    val sentAtMs: Long,
    val baselineItem: String? = null,
    val audio: Int? = null,
    val subtitle: Int? = null,
    val volume: Int? = null,
    val result: String = "sent"
)

@Serializable
data class RemoteControllerState(
    val server: String,
    val viewer: String,
    val phase: String = "detached",
    val generation: Long = 0,
    val target: RemoteTarget? = null,
    val replacement: String? = null,
    val command: CommandState? = null,
    val followUp: RemoteFollowUp? = null,
    val effect: String = "none",
    val rejection: String? = null
)

@Serializable
data class RemoteControllerEvent(
    val op: String,
    val target: RemoteTarget? = null,
    val targets: List<RemoteTarget> = emptyList(),
    val nowMs: Long = 0,
    val id: Long = 0,
    val value: String? = null,
    val entry: String? = null,
    val index: Int? = null,
    val level: Int? = null,
    val status: Int = 0,
    val server: String? = null,
    val viewer: String? = null
)

private val TargetTransport = listOf("play", "pause", "seek", "stop", "play-now")
private val FollowUps = listOf("next", "previous", "audio", "subtitle", "volume", "queue")

private fun CommandState.syncFrom(now: RemoteNowPlaying?): CommandState = copy(
    capabilities = if (now?.canSeek == false) TargetTransport - "seek" else TargetTransport,
    durationTicks = now?.durationTicks?.takeIf { it > 0 } ?: Long.MAX_VALUE,
    observed = observed.copy(entry = now?.item, ticks = now?.positionTicks ?: 0, paused = now?.paused ?: true)
)

private fun RemoteTarget.identity(server: String, generation: Long) =
    ViewingIdentity(server, viewer ?: "shared", device, session, generation, "jellyfin")

/**
 * The controller's view of one target. A send only proves dispatch; everything shown as true comes from the target's own
 * reports in a later `GET /Sessions`. Nothing is retried or queued here, so reconnecting can never replay a command.
 */
fun reduceRemoteController(state: RemoteControllerState, event: RemoteControllerEvent): RemoteControllerState {
    val s = state.copy(effect = "none", rejection = null)
    return when (event.op) {
        "attach" -> {
            val target = requireNotNull(event.target)
            val generation = s.generation + 1
            s.copy(
                phase = "attached",
                generation = generation,
                target = target,
                replacement = null,
                followUp = null,
                command = CommandState(target.identity(s.server, generation), capabilities = TargetTransport).syncFrom(target.now)
            )
        }
        "detach" -> s.copy(phase = "detached", target = null, replacement = null, command = null, followUp = null)
        "account" -> if (event.server == s.server && event.viewer == s.viewer) {
            s
        } else {
            RemoteControllerState(requireNotNull(event.server), requireNotNull(event.viewer), generation = s.generation + 1)
        }
        "refresh" -> refresh(s, event)
        "resume" -> {
            val command = s.command ?: return s
            s.copy(
                command = reduceCommand(command, CommandEvent("disconnect", owner = command.owner)).copy(effect = "none"),
                followUp = s.followUp?.unresolved(),
                effect = if (s.phase == "detached") "none" else "refresh"
            )
        }
        "signed-out" -> RemoteControllerState(s.server, s.viewer, phase = "signed-out", generation = s.generation + 1)
        "intent" -> intent(s, event)
        "sent" -> sent(s, event)
        "tick" -> tick(s, event)
        else -> error("Unknown remote-controller operation: ${event.op}")
    }
}

private fun RemoteFollowUp.unresolved() = if (result == "sent") copy(result = "unknown") else this

private fun lost(s: RemoteControllerState, phase: String, replacement: String? = null): RemoteControllerState {
    val command = s.command
    return s.copy(
        phase = phase,
        replacement = replacement,
        followUp = s.followUp?.unresolved(),
        command = command?.let { reduceCommand(it, CommandEvent("disconnect", owner = it.owner)).copy(effect = "none") }
    )
}

private fun refresh(s: RemoteControllerState, event: RemoteControllerEvent): RemoteControllerState {
    val attached = s.target ?: return s
    val sameDevice = event.targets.firstOrNull { it.device == attached.device && it.viewer == attached.viewer }
    val row = sameDevice?.takeIf { it.session == attached.session }
    if (s.phase != "attached") {
        // Refresh may discover the session again, but only a deliberate attach changes anything.
        return when {
            s.phase == "denied" || s.phase == "detached" -> s
            sameDevice != null -> s.copy(phase = "target-restarted", replacement = sameDevice.session)
            else -> s.copy(phase = "target-gone", replacement = null)
        }
    }
    if (row == null) return lost(s, if (sameDevice != null) "target-restarted" else "target-gone", sameDevice?.session)
    val command = requireNotNull(s.command)
    val now = row.now
    val ticks = (now?.positionTicks ?: 0).toString()
    val owner = command.owner
    val updated = when {
        !command.fresh -> reduceCommand(
            command.copy(durationTicks = Long.MAX_VALUE),
            CommandEvent("refresh", owner, value = ticks, entry = now?.item, paused = now?.paused, capabilities = TargetTransport)
        ).copy(effect = "none")
        command.pending?.result == "sent" -> {
            val pending = requireNotNull(command.pending)
            val confirmedSeek = pending.ticks?.takeIf {
                now != null && now.positionTicks in (it - SEEK_EARLY_TICKS)..(it + SEEK_LATE_TICKS)
            }
            reduceCommand(
                command.copy(durationTicks = Long.MAX_VALUE),
                CommandEvent(
                    "observe",
                    owner,
                    id = pending.id,
                    nowMs = event.nowMs,
                    value = (confirmedSeek ?: now?.positionTicks ?: 0).toString(),
                    entry = now?.item,
                    paused = if (now != null) now.paused else true.takeIf { pending.stopped == true },
                    stopped = (now == null).takeIf { pending.stopped == true }
                )
            )
        }
        else -> command
    }
    val follow = s.followUp?.let { pending ->
        if (pending.result != "sent") return@let pending
        val done = when (pending.op) {
            "next", "previous" -> now != null && (pending.baselineItem == null || now.item != pending.baselineItem)
            "audio" -> now?.audio == pending.audio
            "subtitle" -> now?.subtitle == pending.subtitle
            "volume" -> now?.volume == pending.volume
            // A session row carries no queue: a queue edit can only ever be sent, never shown as done.
            else -> false
        }
        if (done) pending.copy(result = "confirmed") else pending
    }
    return tick(
        s.copy(target = row, command = updated.copy(effect = "none", rejection = null).syncFrom(now), followUp = follow),
        event
    ).let { if (updated.effect == "refresh") it.copy(effect = "refresh") else it }
}

private fun intent(s: RemoteControllerState, event: RemoteControllerEvent): RemoteControllerState {
    if (s.phase != "attached") return s.copy(rejection = if (s.phase == "denied") "denied" else "not-attached")
    val command = requireNotNull(s.command)
    val op = requireNotNull(event.value)
    if (op !in FollowUps) {
        val next = reduceCommand(command, CommandEvent("intent", command.owner, event.id, event.nowMs, op, event.entry))
        return s.copy(command = next, effect = next.effect, rejection = next.rejection)
    }
    if (!command.authorized) return s.copy(rejection = "denied")
    if (!command.fresh) return s.copy(rejection = "refresh-required")
    if (event.id <= command.sequence) return s.copy(rejection = "stale-command")
    val required = when (op) {
        "audio" -> "SetAudioStreamIndex"
        "subtitle" -> "SetSubtitleStreamIndex"
        "volume" -> "SetVolume"
        else -> null
    }
    if (required != null && required !in requireNotNull(s.target).commands) return s.copy(rejection = "unsupported")
    return s.copy(
        command = command.copy(sequence = event.id),
        followUp = RemoteFollowUp(
            event.id,
            op,
            event.nowMs,
            baselineItem = command.observed.entry,
            audio = event.index.takeIf { op == "audio" },
            subtitle = event.index.takeIf { op == "subtitle" },
            volume = event.level.takeIf { op == "volume" }
        ),
        effect = "dispatch"
    )
}

private fun sent(s: RemoteControllerState, event: RemoteControllerEvent): RemoteControllerState {
    val command = s.command ?: return s
    val current = command.pending?.id == event.id || s.followUp?.id == event.id
    if (!current) return s
    return when (event.status) {
        in 200..299 -> s
        401 -> RemoteControllerState(s.server, s.viewer, phase = "signed-out", generation = s.generation + 1)
        403 -> s.copy(
            phase = "denied",
            command = reduceCommand(command, CommandEvent("denied", owner = command.owner)),
            followUp = s.followUp?.unresolved()
        )
        404 -> lost(s, "target-gone")
        // Unknown means unknown. A retry could pause twice or seek twice, so refresh and let the viewer decide.
        else -> {
            val settled = command.pending?.takeIf { it.id == event.id && it.result == "sent" }?.let { command.copy(pending = it.copy(result = "unknown")) } ?: command
            s.copy(
                command = settled.copy(fresh = false, seekTarget = null),
                followUp = s.followUp?.takeIf { it.id == event.id }?.unresolved() ?: s.followUp,
                effect = "refresh"
            )
        }
    }
}

private fun tick(s: RemoteControllerState, event: RemoteControllerEvent): RemoteControllerState {
    val command = s.command ?: return s
    val pending = command.pending
    val expired = if (pending != null) {
        reduceCommand(command, CommandEvent("timeout", command.owner, pending.id, event.nowMs))
    } else {
        command
    }
    val follow = s.followUp?.let { if (it.result == "sent" && event.nowMs - it.sentAtMs >= 5_000) it.copy(result = "unknown") else it }
    return s.copy(command = expired.copy(effect = "none"), followUp = follow, effect = if (expired.effect == "refresh") "refresh" else s.effect)
}
