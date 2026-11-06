/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

/**
 * How far an observed start may sit from the requested position and still count as the requested viewing. A receiver
 * lands on a keyframe or segment boundary: Jellyfin's copied-video HLS keeps the source GOP, commonly up to ~10 s, and
 * the position has already moved on by the time PLAYING is reported.
 */
const val CAST_READY_TOLERANCE_TICKS: Long = 150_000_000

/**
 * How near a seek must land to confirm it. Below the shortest step the controls offer (10 s), so the position from
 * before a short seek can't confirm it. A landing further out reads as not confirmed and shows what the receiver says.
 */
const val CAST_SEEK_TOLERANCE_TICKS: Long = 50_000_000

/** What the receiver reported, stamped with the viewing it belongs to. mediaSession < 0 means it holds no media. */
@Serializable
data class CastObservation(
    val mediaSession: Long = -1,
    /** The generation our LOAD carried; negative when this media is not ours. */
    val generation: Long = -1,
    val playerState: String = "idle",
    val idleReason: String? = null,
    val ticks: Long = 0,
    /** Null when the receiver reports no duration: a live or unknown length, never a zero-length video. */
    val durationTicks: Long? = null,
    val entry: String? = null,
    val activeTracks: List<Int> = emptyList(),
    /** Queue entries in the receiver's own order. */
    val queue: List<String> = emptyList(),
    val commands: List<String> = listOf("play", "pause", "seek", "stop")
)

@Serializable
data class CastState(
    val owner: OwnerState,
    /** idle, connecting, connected, suspended or lost. */
    val link: String = "idle",
    /** The receiver media session this viewing is bound to. */
    val mediaSession: Long? = null,
    /** Live receiver state, or null when none has arrived since the link last changed. There is no local clock here. */
    val observed: CastObservation? = null,
    val commands: CommandState? = null,
    /** none, sent, confirmed or unknown. */
    val stop: String = "none",
    /** end (Stop casting), here (Play here) or ended (the receiver ended it). */
    val stopPurpose: String? = null,
    /** none, pending or done: the sender's report and encoding cleanup for the remote viewing. */
    val cleanup: String = "none",
    val returnPaused: Boolean? = null,
    val returnTicks: Long? = null,
    /** Why a handoff failed, as a fixed category. */
    val recovery: String? = null,
    /** What the viewer is told about the receiver, as a fixed category. */
    val notice: String? = null,
    /** The last status held no media and gave no reason. One of those isn't an answer yet: a second one is. */
    val emptyStatus: Boolean = false,
    val effect: String = "none",
    val reason: String? = null
)

@Serializable
data class CastEvent(
    val op: String,
    val identity: ViewingIdentity? = null,
    val intent: ControlIntent? = null,
    val observation: CastObservation? = null,
    val id: Long = 0,
    val nowMs: Long = 0,
    val value: String? = null,
    val entry: String? = null,
    val sequence: Long = 0,
    val ticks: Long? = null,
    val confirmed: Boolean = false,
    val recovery: String? = null
)

/**
 * The Cast sender's one serialized reducer. Ownership rules stay in [reduceOwner] and command results in
 * [reduceCommand]; this adds the receiver link and observation. No network or output work happens here.
 */
fun reduceCast(state: CastState, event: CastEvent): CastState {
    val s = state.copy(effect = "none", reason = null)
    return when (event.op) {
        "select" -> select(s, event)

        "pick" -> pick(s, event)

        "link-connecting" -> if (s.owner.candidate != null && s.owner.phase == "selecting") {
            viaOwner(s, OwnerEvent("connect", identity = s.owner.candidate)) { it.copy(link = "connecting") }
        } else {
            s.copy(link = "connecting")
        }

        "link-connected" -> linkConnected(s)

        "link-suspended" -> s.copy(
            link = "suspended",
            owner = s.ownerAtLastPosition(),
            observed = null,
            emptyStatus = false,
            commands = s.commands?.let { reduceCommand(it, CommandEvent("disconnect", owner = it.owner)).copy(effect = "none") }
        )

        "link-lost" -> linkLost(s)

        "link-failed" -> linkFailed(s)

        "local-paused" -> viaOwner(s, OwnerEvent("local-paused", identity = s.owner.candidate)) {
            // The start position is where local output actually paused, not where the viewer first opened the chooser.
            it.copy(owner = it.owner.copy(intent = it.owner.intent.copy(ticks = event.ticks ?: it.owner.intent.ticks)))
        }

        "load-failed", "load-timeout", "cancel" -> {
            val op = when (event.op) {
                "load-failed" -> "fail"
                "load-timeout" -> "timeout"
                else -> "cancel"
            }
            val recovery = event.recovery ?: when (event.op) {
                "load-failed" -> "receiver-load-failed"
                "load-timeout" -> "receiver-timeout"
                else -> "cancelled"
            }
            viaOwner(s, OwnerEvent(op, identity = s.owner.candidate)) { it.copy(recovery = recovery) }
        }

        "observe" -> observe(s, event)

        "progress" -> progress(s, event)

        "command" -> command(s, event)

        "command-timeout" -> {
            val commands = s.commands ?: return s.copy(reason = "stale-owner")
            val after = reduceCommand(commands, CommandEvent("timeout", owner = commands.owner, id = event.id, nowMs = event.nowMs))
            s.copy(commands = after, effect = if (after.effect == "refresh") "request-status" else "none")
        }

        "command-rejected" -> {
            val commands = s.commands ?: return s.copy(reason = "stale-owner")
            val pending = commands.pending
            // Only the command this answers. A late refusal of an old command says nothing about the newer one.
            if (pending == null || pending.id != event.id || pending.result != "sent") {
                s.copy(reason = "stale-command")
            } else {
                s.copy(commands = commands.copy(pending = pending.copy(result = "rejected"), seekTarget = null))
            }
        }

        "disconnect" -> viaOwner(s, OwnerEvent("disconnect", identity = s.owner.owner)) {
            it.copy(
                link = "idle",
                owner = it.ownerAtLastPosition(),
                observed = null,
                mediaSession = null,
                commands = null,
                notice = "receiver-continues",
                effect = "detach"
            )
        }

        "stop" -> beginStop(s, "end")

        "play-here" -> beginStop(s, "here")

        "retry-stop" -> if (s.stop == "unknown") beginStop(s, s.stopPurpose ?: "end") else s.copy(reason = "stale-stop")

        "stop-timeout" -> if (s.stop != "sent") s.copy(reason = "stale-stop") else stopUnconfirmed(s)

        "cleanup-done" -> if (s.cleanup == "pending") s.copy(cleanup = "done") else s.copy(reason = "stale-cleanup")

        "resume-here" -> viaOwner(s, OwnerEvent("resume-here", identity = event.identity, confirmed = event.confirmed)) { after ->
            // reduceOwner resumes playing. Play here returns with the pause intent it recorded when the viewer asked.
            val paused = after.returnPaused
            val ticks = after.returnTicks
            after.copy(
                owner = after.owner.copy(
                    intent = after.owner.intent.copy(
                        paused = paused ?: after.owner.intent.paused,
                        ticks = ticks ?: after.owner.intent.ticks
                    )
                ),
                link = if (after.link == "connected") "connected" else "idle",
                mediaSession = null,
                commands = null,
                // A Stop still unconfirmed stays visible through the explicit choice.
                stop = if (s.stop == "unknown") "unknown" else "none",
                recovery = null
            )
        }

        "report" -> {
            val target = event.identity ?: s.owner.owner
            if (s.owner.owner.kind == "cast" && (s.link != "connected" || s.observed == null)) {
                s.copy(reason = "not-attached")
            } else {
                viaOwner(s, OwnerEvent("report", identity = target, sequence = event.sequence)) { it }
            }
        }

        "viewer-switch", "replace" -> viaOwner(s, OwnerEvent(event.op, identity = event.identity, intent = event.intent)) {
            it.copy(
                link = "idle",
                mediaSession = null,
                observed = null,
                commands = null,
                stop = "none",
                stopPurpose = null,
                effect = "detach"
            )
        }

        else -> error("Unknown cast-handoff operation: ${event.op}")
    }
}

/** A reattach after the sender process died starts from what the receiver's media says it belongs to. */
fun castReattachState(cast: ViewingIdentity, local: ViewingIdentity, intent: ControlIntent = ControlIntent()): CastState = CastState(
    owner = OwnerState(owner = cast, local = local, phase = "disconnected", intent = intent, generation = cast.generation),
    notice = "receiver-continues"
)

private fun select(s: CastState, event: CastEvent): CastState =
    viaOwner(s, OwnerEvent("select", identity = event.identity, intent = event.intent)) {
        it.copy(
            mediaSession = null,
            observed = null,
            commands = null,
            stop = "none",
            stopPurpose = null,
            returnPaused = null,
            returnTicks = null,
            recovery = null,
            notice = null,
            emptyStatus = false
        )
    }

/**
 * The viewer picked a device. A sender holds one viewing, so a live one is settled here before anything starts: what is
 * already playing where it was asked for stays, a viewing this link can stop gives way, and a detached one on another
 * device stays the viewer's call, because stopping it means joining a device another app may have taken since.
 */
private fun pick(s: CastState, event: CastEvent): CastState {
    val owner = s.owner
    val target = event.identity ?: return s.copy(reason = "invalid-target")
    if (owner.candidate != null || owner.phase !in listOf("active", "failed", "disconnected")) return s.copy(reason = "handoff-busy")
    if (owner.owner.kind != "cast" || owner.phase == "failed") return select(s, event)
    val sameReceiver = owner.owner.device == target.device
    val wanted = event.intent?.entry
    val sameEntry = wanted != null && wanted == (s.observed?.entry ?: owner.intent.entry)
    return when {
        owner.phase == "disconnected" && !sameReceiver -> s.copy(reason = "detached-elsewhere")
        owner.phase == "disconnected" && sameEntry -> s.copy(effect = "reconnect", reason = "already-casting")
        // Joined first: failing to reach it mustn't cost the viewing that may still be playing there. Then it's a replace on the link.
        owner.phase == "disconnected" -> s.copy(effect = "reconnect", reason = "rejoin-to-replace")
        s.link != "connected" -> s.copy(reason = "link-not-ready")
        sameReceiver && sameEntry -> s.copy(effect = "show-controls", reason = "already-casting")
        sameReceiver -> replace(s, event, "replace-on-link")
        else -> replace(s, event, "replace-elsewhere")
    }
}

/** The old viewing is over from here on, so its owner goes back to local and a failed start reads like any other. */
private fun replace(s: CastState, event: CastEvent, effect: String): CastState {
    val selected = select(s.copy(owner = s.owner.copy(owner = s.owner.local, phase = "active", reportSequence = -1)), event)
    if (selected.reason != null) return s.copy(reason = selected.reason)
    return selected.copy(link = if (effect == "replace-on-link") "connected" else "idle", cleanup = "none", effect = effect)
}

/** Applies an owner event. A rejection leaves everything else alone; [next] sees the owner's result and effect. */
private fun viaOwner(s: CastState, event: OwnerEvent, next: (CastState) -> CastState): CastState {
    val owner = reduceOwner(s.owner, event)
    if (owner.reason != null && owner.reason != "remote-may-continue") return s.copy(owner = owner, reason = owner.reason)
    return next(s.copy(owner = owner, effect = owner.effect, reason = owner.reason))
}

private fun linkConnected(state: CastState): CastState {
    val s = state.copy(emptyStatus = false)
    val candidate = s.owner.candidate
    if (candidate != null) {
        return if (s.owner.phase == "connecting") {
            viaOwner(s, OwnerEvent("prepare", identity = candidate)) { it.copy(link = "connected") }
        } else {
            s.copy(link = "connected")
        }
    }
    if (s.owner.owner.kind != "cast") return s.copy(link = "connected")
    // Back on the link, nothing is known until the receiver says so; controls wait for that.
    return s.copy(
        link = "connected",
        owner = s.ownerAtLastPosition(),
        mediaSession = null,
        observed = null,
        commands = s.commands?.let { reduceCommand(it, CommandEvent("disconnect", owner = it.owner)).copy(effect = "none") },
        effect = "request-status"
    )
}

private fun linkLost(s: CastState): CastState {
    val candidate = s.owner.candidate
    return when {
        candidate != null -> viaOwner(s, OwnerEvent("fail", identity = candidate)) { it.copy(link = "lost", recovery = "link-lost") }

        s.stop == "sent" -> stopUnconfirmed(s).copy(link = "lost", observed = null)

        s.owner.owner.kind == "cast" && s.owner.phase == "active" ->
            viaOwner(s, OwnerEvent("disconnect", identity = s.owner.owner)) {
                it.copy(
                    link = "lost",
                    owner = it.ownerAtLastPosition(),
                    observed = null,
                    mediaSession = null,
                    commands = null,
                    notice = "link-lost",
                    effect = "none"
                )
            }

        else -> s.copy(link = "lost", observed = null)
    }
}

/**
 * Detaching forgets the observation, and the owner's intent is the only thing left that a later Stop or Resume here can
 * read. Left alone that is where the handoff began, however long the receiver has played since.
 */
private fun CastState.ownerAtLastPosition(): OwnerState {
    val last = observed ?: return owner
    return owner.copy(intent = owner.intent.copy(ticks = last.ticks, paused = pausedOf(last) ?: owner.intent.paused))
}

/**
 * The receiver's clock between its statuses. A status says where it was when sent and nothing arrives while it simply plays,
 * so this is what moves the screen, the reports and what Stop saves. Only a viewing that is playing moves: paused and loading
 * hold still, and a seek on its way is settled by the status that answers it. Zero is what the SDK says with nothing to go
 * on, and is never worth a resume point.
 */
private fun progress(s: CastState, event: CastEvent): CastState {
    val ticks = event.ticks ?: return s.copy(reason = "invalid-observation")
    val observed = s.observed
    if (observed == null ||
        s.owner.candidate != null ||
        s.owner.owner.kind != "cast" ||
        s.owner.phase != "active" ||
        s.link != "connected"
    ) {
        return s.copy(reason = "stale-observation")
    }
    val pending = s.commands?.pending
    if (observed.playerState != "playing" || ticks <= 0 || (pending?.result == "sent" && pending.ticks != null)) {
        return s.copy(reason = "position-held")
    }
    val bounded = observed.durationTicks?.takeIf { it > 0 }?.let { minOf(ticks, it) } ?: ticks
    return s.copy(
        observed = observed.copy(ticks = bounded),
        commands = s.commands?.let { it.copy(observed = it.observed.copy(ticks = bounded)) }
    )
}

/** A connect that never succeeded: nothing was playing there, so this isn't a lost connection. */
private fun linkFailed(s: CastState): CastState {
    val candidate = s.owner.candidate
    return when {
        candidate != null -> viaOwner(s, OwnerEvent("fail", identity = candidate)) { it.copy(link = "lost", recovery = "connect-failed") }

        s.owner.owner.kind == "cast" && s.owner.phase == "disconnected" -> s.copy(link = "lost", notice = "connect-failed")

        else -> s.copy(reason = "stale-link")
    }
}

private fun beginStop(s: CastState, purpose: String): CastState {
    if (s.owner.owner.kind != "cast") return s.copy(reason = "stale-owner")
    val observed = s.observed
    val paused = when (observed?.playerState) {
        "playing" -> false
        "paused" -> true
        else -> s.owner.intent.paused
    }
    return viaOwner(s, OwnerEvent("return", identity = s.owner.owner)) {
        it.copy(
            stop = "sent",
            stopPurpose = purpose,
            cleanup = "pending",
            // A retry keeps what the first request recorded: the receiver may have moved on since.
            returnPaused = s.returnPaused ?: paused,
            returnTicks = s.returnTicks ?: observed?.ticks ?: s.owner.intent.ticks
        )
    }
}

private fun stopUnconfirmed(s: CastState): CastState =
    viaOwner(s, OwnerEvent("remote-stopped", identity = s.owner.owner, confirmed = false)) {
        it.copy(stop = "unknown", effect = if (s.stopPurpose == "here") "require-choice" else "stop-unknown")
    }

private fun stopConfirmed(s: CastState): CastState {
    val returning = if (s.owner.phase == "returning") {
        s
    } else {
        viaOwner(s, OwnerEvent("return", identity = s.owner.owner)) { it }
    }
    return viaOwner(returning, OwnerEvent("remote-stopped", identity = s.owner.owner, confirmed = true)) {
        it.copy(
            stop = "confirmed",
            cleanup = "pending",
            effect = if (s.stopPurpose ==
                "here"
            ) {
                "resume-here"
            } else {
                "cleanup"
            },
            observed = null
        )
    }
}

/** The receiver ended this viewing itself. Nothing local starts; the viewer may choose Resume here. */
private fun receiverEnded(s: CastState, notice: String, ticks: Long?): CastState = stopConfirmed(
    s.copy(
        stopPurpose = "ended",
        returnPaused = true,
        returnTicks = ticks ?: s.observed?.ticks ?: s.owner.intent.ticks
    )
).copy(notice = notice)

private fun endedNotice(reason: String?) = when (reason) {
    "finished" -> "receiver-finished"
    "error" -> "receiver-error"
    else -> "receiver-stopped"
}

private val OBSERVING_PHASES = listOf("awaiting-ready", "returning", "return-uncertain", "disconnected", "active")

private fun observe(state: CastState, event: CastEvent): CastState {
    val o = event.observation ?: return state.copy(reason = "invalid-observation")
    val s = if (o.mediaSession < 0) state else state.copy(emptyStatus = false)
    val target = s.owner.candidate ?: s.owner.owner
    val idle = o.playerState == "idle" || o.mediaSession < 0
    // After the choice to play here despite an unconfirmed Stop, a later idle still settles the cleanup.
    if (target.kind != "cast") {
        return if (s.stop == "unknown" && idle) {
            s.copy(stop = "confirmed", effect = "cleanup")
        } else {
            s.copy(reason = "stale-observation")
        }
    }
    if (s.link != "connected" || s.owner.phase !in OBSERVING_PHASES) return s.copy(reason = "stale-observation")
    val waiting = s.owner.phase == "awaiting-ready"
    // A receiver with no media is an answer once a viewing exists; before the LOAD lands it is only the empty receiver.
    if (o.mediaSession < 0) {
        return when {
            waiting -> s.copy(reason = "stale-observation")
            s.stop == "sent" || s.stop == "unknown" -> stopConfirmed(s)
            // No media and no reason can be an SDK that hasn't synced yet, and ending stops a viewing that may be fine: ask once.
            o.idleReason == null && !s.emptyStatus -> s.copy(emptyStatus = true, effect = "request-status", reason = "unconfirmed-empty")
            else -> receiverEnded(s, endedNotice(o.idleReason), null)
        }
    }
    if (o.generation != target.generation) {
        val older = o.generation in 0 until target.generation
        return when {
            older || waiting -> s.copy(reason = "stale-observation")

            s.stop == "sent" || s.stop == "unknown" -> stopConfirmed(s)

            // Someone else loaded their media on this receiver: this viewing is over, and that media isn't ours to show.
            else -> receiverEnded(s, if (idle) endedNotice(o.idleReason) else "receiver-taken", null)
        }
    }
    // Session ids rise. An older one is a status from before; a newer one under this generation is this same viewing,
    // restarted by the receiver, and must not freeze the controls.
    if (s.mediaSession != null && o.mediaSession < s.mediaSession) return s.copy(reason = "stale-observation")
    val bound = s.copy(mediaSession = o.mediaSession, observed = o)
    // A receiver that has let go can say zero, and that mustn't wipe the resume point. What it said last stands.
    val endedAt = o.ticks.takeIf { it > 0 } ?: s.observed?.ticks ?: s.owner.intent.ticks
    return when (s.owner.phase) {
        "awaiting-ready" -> awaitingReady(bound, o)

        "returning", "return-uncertain" -> if (idle) stopConfirmed(bound) else bound

        "disconnected" -> if (idle) {
            receiverEnded(bound, endedNotice(o.idleReason), endedAt)
        } else {
            // Reattach: reduceOwner's disconnect only detached controls, so the same owner and generation carry on.
            bound.copy(
                owner = bound.owner.copy(phase = "active", effect = "none"),
                commands = freshCommands(target, o),
                notice = null
            )
        }

        "active" -> if (idle) {
            receiverEnded(bound, endedNotice(o.idleReason), endedAt)
        } else {
            bound.copy(commands = observeCommands(bound.commands ?: freshCommands(target, o), o, event.nowMs))
        }

        else -> bound
    }
}

private fun awaitingReady(s: CastState, o: CastObservation): CastState {
    val candidate = requireNotNull(s.owner.candidate)
    return when (o.playerState) {
        "idle" -> when (o.idleReason) {
            null -> s.copy(reason = "not-ready")
            "error" -> viaOwner(s, OwnerEvent("fail", identity = candidate)) { it.copy(recovery = "receiver-load-failed", observed = null) }
            else -> viaOwner(s, OwnerEvent("fail", identity = candidate)) { it.copy(recovery = "receiver-interrupted", observed = null) }
        }

        "playing", "paused" -> {
            val intended = s.owner.intent.ticks
            val distance = if (o.ticks > intended) o.ticks - intended else intended - o.ticks
            if (distance > CAST_READY_TOLERANCE_TICKS) {
                s.copy(reason = "position-outside-tolerance")
            } else {
                // reduceOwner compares ticks exactly. The tolerance decision is made here and the intent ticks passed on.
                viaOwner(s, OwnerEvent("ready", identity = candidate, ticks = intended)) {
                    it.copy(commands = freshCommands(candidate, o), recovery = null, effect = "release-local")
                }
            }
        }

        else -> s.copy(reason = "not-ready")
    }
}

private fun pausedOf(o: CastObservation): Boolean? = when (o.playerState) {
    "playing" -> false
    "paused" -> true
    else -> null
}

private fun freshCommands(owner: ViewingIdentity, o: CastObservation) = CommandState(
    owner = owner,
    capabilities = o.commands,
    observed = ControlIntent(entry = o.entry, ticks = o.ticks, paused = pausedOf(o) ?: true),
    durationTicks = o.durationTicks?.takeIf { it > 0 } ?: Long.MAX_VALUE
)

private fun observeCommands(commands: CommandState, o: CastObservation, nowMs: Long): CommandState {
    val paused = pausedOf(o)
    val duration = o.durationTicks?.takeIf { it > 0 } ?: Long.MAX_VALUE
    if (!commands.fresh) {
        return reduceCommand(
            commands.copy(durationTicks = duration),
            CommandEvent(
                "refresh",
                owner = commands.owner,
                value = o.ticks.coerceIn(0, duration).toString(),
                entry = o.entry,
                paused = paused ?: commands.observed.paused,
                capabilities = o.commands
            )
        )
    }
    val pending = commands.pending
    val after = if (pending == null) {
        commands
    } else {
        // A seek lands near its target, never on it. reduceCommand matches exactly, so the tolerance is decided here.
        val landed = pending.ticks?.let { target ->
            val distance = if (o.ticks > target) o.ticks - target else target - o.ticks
            target.takeIf { distance <= CAST_SEEK_TOLERANCE_TICKS }
        }
        reduceCommand(
            commands,
            CommandEvent(
                "observe",
                owner = commands.owner,
                id = pending.id,
                nowMs = nowMs,
                value = (landed ?: o.ticks).coerceIn(0, commands.durationTicks).toString(),
                entry = o.entry,
                paused = paused,
                stopped = false
            )
        )
    }
    return after.copy(
        observed = after.observed.copy(ticks = o.ticks, entry = o.entry, paused = paused ?: after.observed.paused),
        durationTicks = duration,
        capabilities = o.commands
    )
}

private fun command(s: CastState, event: CastEvent): CastState {
    val commands = s.commands
    if (commands == null || s.owner.owner.kind != "cast" || s.owner.phase != "active" || s.link != "connected") {
        return s.copy(reason = "not-attached")
    }
    val after = reduceCommand(
        commands,
        CommandEvent("intent", owner = commands.owner, id = event.id, nowMs = event.nowMs, value = event.value, entry = event.entry)
    )
    return s.copy(commands = after, effect = if (after.effect == "dispatch") "send-command" else "none", reason = after.rejection)
}
