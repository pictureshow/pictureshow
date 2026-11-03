/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

@Serializable
data class OwnerState(
    val owner: ViewingIdentity,
    val local: ViewingIdentity = owner,
    val candidate: ViewingIdentity? = null,
    val phase: String = "active",
    val intent: ControlIntent = ControlIntent(),
    val route: String = "native",
    val generation: Long = owner.generation,
    val reportSequence: Long = -1,
    val effect: String = "none",
    val reason: String? = null
)

@Serializable
data class OwnerEvent(
    val op: String,
    val identity: ViewingIdentity? = null,
    val intent: ControlIntent? = null,
    val sequence: Long = 0,
    val ticks: Long? = null,
    val confirmed: Boolean = false
)

/** No network/output work happens here. local-paused is an observed adapter acknowledgement. */
fun reduceOwner(state: OwnerState, event: OwnerEvent): OwnerState {
    val s = state.copy(effect = "none", reason = null)
    fun rejected(reason: String) = s.copy(reason = reason)
    return when (event.op) {
        "replace", "viewer-switch" -> {
            val next = event.identity ?: return rejected("invalid-owner")
            if (!next.valid() || next.generation <= s.generation) return rejected("stale-generation")
            s.copy(
                owner = next,
                local = next,
                candidate = null,
                phase = "active",
                intent = event.intent ?: ControlIntent(),
                generation = next.generation,
                reportSequence = -1,
                route = "native"
            )
        }
        "report" -> if (event.identity != s.owner ||
            event.sequence <= s.reportSequence ||
            s.phase !in listOf("active", "selecting", "connecting", "preparing", "awaiting-ready")
        ) {
            rejected("stale-report")
        } else {
            s.copy(reportSequence = event.sequence, effect = "report")
        }
        "airplay", "native-route" -> if (event.identity != s.owner || s.owner.kind != "local" || s.phase != "active") {
            rejected("stale-owner")
        } else {
            s.copy(route = if (event.op == "airplay") "airplay" else "native")
        }
        "select" -> {
            val next = event.identity ?: return rejected("invalid-owner")
            if (s.phase !in listOf("active", "failed", "disconnected") ||
                !next.valid() ||
                next.kind == "local" ||
                next.server != s.owner.server ||
                next.viewer != s.owner.viewer ||
                next.generation <= s.generation
            ) {
                return rejected("invalid-target")
            }
            s.copy(candidate = next, phase = "selecting", generation = next.generation, intent = event.intent ?: s.intent)
        }
        "connect" -> if (event.identity != s.candidate || s.phase != "selecting") rejected("stale-target") else s.copy(phase = "connecting")
        "prepare" -> if (event.identity != s.candidate || s.phase != "connecting") {
            rejected("stale-target")
        } else {
            s.copy(
                phase = "preparing",
                effect = "pause-local"
            )
        }
        "local-paused" -> if (event.identity != s.candidate || s.phase != "preparing" || s.effect == "request-remote") {
            rejected("stale-target")
        } else {
            s.copy(phase = "awaiting-ready", effect = "request-remote")
        }
        "ready" -> {
            if (event.identity != s.candidate || s.phase != "awaiting-ready" || event.ticks != s.intent.ticks) {
                return rejected("not-ready")
            }
            s.copy(owner = requireNotNull(s.candidate), candidate = null, phase = "active", reportSequence = -1)
        }
        "fail", "timeout", "cancel" -> {
            if (event.identity != s.candidate || s.candidate == null) return rejected("stale-target")
            // Invalidate candidate callbacks while retaining a paused, resumable local snapshot.
            s.copy(candidate = null, phase = "failed", intent = s.intent.copy(paused = true), effect = "resume-here")
        }
        "disconnect" -> if (event.identity != s.owner) rejected("stale-owner") else s.copy(phase = "disconnected", effect = "refresh")
        "return" -> if (event.identity != s.owner || s.owner.kind == "local") {
            rejected("stale-owner")
        } else {
            s.copy(
                phase = "returning",
                effect = "stop-remote"
            )
        }
        "remote-stopped" -> if (event.identity != s.owner || s.phase != "returning") {
            rejected("stale-owner")
        } else if (event.confirmed) {
            s.copy(phase = "return-ready", effect = "resume-here")
        } else {
            s.copy(phase = "return-uncertain", effect = "require-choice", reason = "remote-may-continue")
        }
        "resume-here" -> {
            if (s.phase !in listOf("return-ready", "return-uncertain", "failed") ||
                (s.phase == "return-uncertain" && !event.confirmed)
            ) {
                return rejected("explicit-choice-required")
            }
            val local = event.identity ?: return rejected("invalid-owner")
            if (!local.valid() ||
                local.kind != "local" ||
                local.server != s.local.server ||
                local.viewer != s.local.viewer ||
                local.device != s.local.device ||
                local.generation <= s.generation
            ) {
                return rejected("stale-generation")
            }
            s.copy(
                owner = local,
                local = local,
                generation = local.generation,
                candidate = null,
                phase = "active",
                intent = s.intent.copy(paused = false),
                reportSequence = -1,
                effect = "play-local",
                route = "native"
            )
        }
        else -> error("Unknown session-owner operation: ${event.op}")
    }
}

/** Opaque secret references belong to private adapter storage; credentials never enter a queue or diagnostics. */
@Serializable
data class TemporarySession(
    val server: String,
    val viewer: String,
    val device: String,
    val sender: String,
    val generation: Long,
    val phase: String = "creating",
    val secretRef: String? = null
)

@Serializable
data class TemporaryState(val records: List<TemporarySession> = emptyList(), val effect: String = "none", val reason: String? = null)

@Serializable
data class TemporaryEvent(val op: String, val binding: TemporarySession, val returnedViewer: String? = null, val secretRef: String? = null)

/** One record per stable identity; provisioning is blocked until durable revocation is acknowledged. */
fun reduceTemporary(state: TemporaryState, event: TemporaryEvent): TemporaryState {
    val s = state.copy(effect = "none", reason = null)
    val b = event.binding
    val index = s.records.indexOfFirst { it.server == b.server && it.viewer == b.viewer && it.device == b.device }
    val record = s.records.getOrNull(index)
    fun update(next: TemporarySession, effect: String) =
        s.copy(records = s.records.toMutableList().also { it[index] = next }, effect = effect)
    if (event.op == "create") {
        if (index >= 0) return s.copy(reason = "cleanup-required")
        if (listOf(b.server, b.viewer, b.device, b.sender).any(String::isBlank) ||
            b.generation < 0
        ) {
            return s.copy(reason = "invalid-binding")
        }
        return s.copy(records = s.records + b.copy(phase = "creating", secretRef = null), effect = "provision")
    }
    if (event.op !in listOf("issued", "use", "end", "persisted", "logout-ok", "logout-failed", "reconnect")) {
        error("Unknown temporary-session operation: ${event.op}")
    }
    if (record == null || record.generation != b.generation || record.sender != b.sender) return s.copy(reason = "stale-binding")
    return when (event.op) {
        "issued" -> {
            if (record.phase !in listOf("creating", "cancelled-creating") ||
                event.secretRef.isNullOrBlank()
            ) {
                return s.copy(reason = "invalid-issue")
            }
            val matches = event.returnedViewer == record.viewer && record.phase != "cancelled-creating"
            update(
                record.copy(phase = if (matches) "active" else "pending-persist", secretRef = event.secretRef),
                if (matches) "use" else "persist-revocation"
            )
        }
        "use" -> if (record.phase == "active") s.copy(effect = "use") else s.copy(reason = "cleanup-required")
        "end" -> if (record.secretRef == null) {
            // An in-flight issue must settle before its slot can be reused.
            update(record.copy(phase = "cancelled-creating"), "await-issue")
        } else {
            update(record.copy(phase = "pending-persist"), "persist-revocation")
        }
        "persisted" -> if (record.phase ==
            "pending-persist"
        ) {
            update(record.copy(phase = "revoking"), "logout")
        } else {
            s.copy(reason = "invalid-phase")
        }
        "logout-ok" -> if (record.phase ==
            "revoking"
        ) {
            s.copy(records = s.records.filterIndexed { i, _ -> i != index }, effect = "erase-secret")
        } else {
            s.copy(reason = "invalid-phase")
        }
        "logout-failed" -> if (record.phase ==
            "revoking"
        ) {
            update(record.copy(phase = "pending-revoke"), "pending")
        } else {
            s.copy(reason = "invalid-phase")
        }
        "reconnect" -> if (record.phase in listOf("pending-revoke", "revoking")) update(record.copy(phase = "revoking"), "logout") else s
        else -> s
    }
}
