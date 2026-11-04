/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable

private const val REMOTE_INPUT_LIFETIME_MS = 5_000L
private const val REMOTE_OPEN_LIFETIME_MS = 30_000L
private const val REMOTE_SEEN_LIMIT = 64
private const val REMOTE_HELD_LIMIT = 8
private const val REMOTE_NAV_PER_SECOND = 12

@Serializable
data class RemoteViewing(
    val item: String,
    val ready: Boolean = false,
    val durationTicks: Long = 0,
    val ticks: Long = 0,
    val paused: Boolean = false,
    val canSeek: Boolean = false
)

@Serializable
data class RemoteHeld(val message: RemoteInbound, val receivedAtMs: Long)

/** One thing for the app to do on the main thread. `generation` guards anything it must finish asynchronously. */
@Serializable
data class RemoteEffect(
    val op: String,
    val items: List<String> = emptyList(),
    val index: Int? = null,
    val ticks: Long? = null,
    val level: Int? = null,
    val name: String? = null,
    val text: String? = null,
    val generation: Long = 0
)

@Serializable
data class RemoteReceiveState(
    val server: String,
    val viewer: String,
    val generation: Long = 0,
    val connected: Boolean = true,
    val policy: String = "same-viewer",
    val advertised: List<String> = emptyList(),
    val seen: List<String> = emptyList(),
    val opening: Boolean = false,
    val openedAtMs: Long = 0,
    val openingItem: String? = null,
    val relayEnforcesRights: Boolean = false,
    val viewing: RemoteViewing? = null,
    val held: List<RemoteHeld> = emptyList(),
    val field: String = "none",
    val navWindowMs: Long = 0,
    val navCount: Int = 0,
    val effects: List<RemoteEffect> = emptyList(),
    val rejection: String? = null
)

@Serializable
data class RemoteReceiveEvent(
    val op: String,
    val message: RemoteInbound? = null,
    val nowMs: Long = 0,
    val viewing: RemoteViewing? = null,
    val field: String? = null,
    val server: String? = null,
    val viewer: String? = null,
    val policy: String? = null,
    val advertised: List<String>? = null,
    val generation: Long = 0,
    val ok: Boolean = false
)

private val PlayModes = mapOf("PlayNow" to "open", "PlayNext" to "queue-next", "PlayLast" to "queue-add")
private val TransportCommands = listOf("Pause", "Unpause", "PlayPause", "Seek", "Stop", "NextTrack", "PreviousTrack", "Rewind", "FastForward")
private const val REMOTE_SKIP_TICKS = 300_000_000L

/** Only 12.4 is measured refusing another viewer without the right; anything older or unknown is treated as not enforcing. */
fun remoteRelayEnforcesRights(serverVersion: String?): Boolean {
    val parts = serverVersion?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    val major = parts.getOrNull(0) ?: return false
    val minor = parts.getOrNull(1) ?: return false
    return major > 12 || (major == 12 && minor >= 4)
}

/**
 * The target's single gate. The server has relayed the message, so this checks what only the target can: its own viewer,
 * its own policy and advertised list, its current viewing, and whether the message is duplicate or too old. It decides; the app acts.
 */
fun reduceRemoteReceive(state: RemoteReceiveState, event: RemoteReceiveEvent): RemoteReceiveState {
    val s = state.copy(effects = emptyList(), rejection = null)
    return when (event.op) {
        "message" -> receive(s, requireNotNull(event.message), event.nowMs)
        "viewing" -> {
            val next = event.viewing
            // The player being replaced can still report ready; only the requested title ends the preparation.
            val arrived = next?.ready == true && (!s.opening || next.item == s.openingItem)
            val settled = s.copy(viewing = next, opening = s.opening && !arrived)
            if (!arrived) {
                settled.copy(held = if (next == null && !settled.opening) emptyList() else settled.held)
            } else {
                drain(settled, event.nowMs)
            }
        }
        "resolved" -> when {
            event.generation != s.generation -> s.copy(rejection = "stale-generation")
            event.ok -> s
            else -> s.copy(opening = false, held = emptyList(), rejection = "unavailable")
        }
        "field" -> s.copy(field = event.field?.takeIf { it in listOf("none", "search", "secret") } ?: "none")
        "policy" -> {
            val policy = event.policy?.takeIf { it in listOf("off", "same-viewer", "server-allowed") } ?: "same-viewer"
            s.copy(policy = policy, advertised = event.advertised ?: s.advertised, held = if (policy == "off") emptyList() else s.held)
        }
        "tick" -> s.copy(
            held = s.held.filter { event.nowMs - it.receivedAtMs < REMOTE_INPUT_LIFETIME_MS },
            opening = s.opening && event.nowMs - s.openedAtMs < REMOTE_OPEN_LIFETIME_MS
        )
        "disconnect" -> s.copy(connected = false, held = emptyList(), navCount = 0)
        "reconnect" -> s.copy(connected = true, generation = s.generation + 1, held = emptyList(), navCount = 0)
        "account" -> s.copy(
            server = requireNotNull(event.server),
            viewer = requireNotNull(event.viewer),
            generation = s.generation + 1,
            seen = emptyList(),
            opening = false,
            viewing = null,
            held = emptyList(),
            field = "none",
            navCount = 0
        )
        else -> error("Unknown remote-receive operation: ${event.op}")
    }
}

private fun receive(s: RemoteReceiveState, message: RemoteInbound, nowMs: Long): RemoteReceiveState {
    if (message.kind == "keepalive" || message.kind == "ignored") return s
    if (message.kind == "malformed") return s.copy(rejection = "malformed")
    if (!s.connected) return s.copy(rejection = "disconnected")
    val id = message.id ?: return s.copy(rejection = "malformed")
    if (id in s.seen) return s.copy(rejection = "duplicate")
    val marked = s.copy(seen = (s.seen + id).takeLast(REMOTE_SEEN_LIMIT))
    when (s.policy) {
        "off" -> return marked.copy(rejection = "disabled")
        "same-viewer" -> {
            val controller = message.controller ?: return marked.copy(rejection = "no-controller")
            if (controller != normalizedId(s.viewer)) return marked.copy(rejection = "other-viewer")
        }
        else -> {
            val controller = message.controller ?: return marked.copy(rejection = "no-controller")
            // 10.10 and 10.11 relay another viewer's command with or without the right (measured), so only same-viewer is safe there.
            if (controller != normalizedId(s.viewer) && !s.relayEnforcesRights) return marked.copy(rejection = "other-viewer")
        }
    }
    return when (message.kind) {
        "play" -> play(marked, message, nowMs)
        "playstate" -> transport(marked, message, nowMs)
        "general" -> general(marked, message, nowMs)
        else -> marked.copy(rejection = "malformed")
    }
}

private fun play(s: RemoteReceiveState, message: RemoteInbound, nowMs: Long): RemoteReceiveState {
    val op = PlayModes[message.command] ?: return s.copy(rejection = "unsupported")
    if (op == "open") {
        return s.copy(
            opening = true,
            openedAtMs = nowMs,
            openingItem = message.items.getOrNull(message.index ?: 0),
            held = emptyList(),
            effects = listOf(RemoteEffect(op, message.items, message.index ?: 0, message.ticks, generation = s.generation))
        )
    }
    // Starting playback from a queue edit would surprise whoever is watching: QUE-02 keeps it stopped until a deliberate Play.
    if (s.viewing == null && !s.opening) return s.copy(rejection = "no-active-viewing")
    return s.copy(effects = listOf(RemoteEffect(op, message.items, generation = s.generation)))
}

private fun transport(s: RemoteReceiveState, message: RemoteInbound, nowMs: Long): RemoteReceiveState {
    if (message.command !in TransportCommands) return s.copy(rejection = "unsupported")
    if (message.command == "Seek" && (message.ticks == null || message.ticks < 0)) return s.copy(rejection = "invalid-ticks")
    if (s.opening || s.viewing?.ready == false) {
        if (message.command == "Stop") {
            return s.copy(opening = false, held = emptyList(), effects = listOf(RemoteEffect("stop", generation = s.generation)))
        }
        // Preparation is short, but not instant: hold briefly in order rather than lose or misapply it.
        if (s.held.size >= REMOTE_HELD_LIMIT) return s.copy(rejection = "busy")
        return s.copy(held = s.held + RemoteHeld(message, nowMs))
    }
    val viewing = s.viewing ?: return s.copy(rejection = "no-active-viewing")
    return s.copy(effects = listOfNotNull(transportEffect(s, viewing, message))).let {
        if (it.effects.isEmpty()) it.copy(rejection = rejectedTransport(viewing, message)) else it
    }
}

private fun rejectedTransport(viewing: RemoteViewing, message: RemoteInbound): String? =
    if (message.command in listOf("Seek", "Rewind", "FastForward") && !viewing.canSeek) "not-seekable" else null

private fun transportEffect(s: RemoteReceiveState, viewing: RemoteViewing, message: RemoteInbound): RemoteEffect? {
    fun effect(op: String, ticks: Long? = null) = RemoteEffect(op, ticks = ticks, generation = s.generation)
    fun bounded(ticks: Long) = if (viewing.durationTicks > 0) ticks.coerceIn(0, viewing.durationTicks) else ticks.coerceAtLeast(0)
    return when (message.command) {
        // A desired state: repeating it reports where things stand instead of toggling.
        "Pause" -> effect(if (viewing.paused) "report" else "pause")
        "Unpause" -> effect(if (viewing.paused) "unpause" else "report")
        "PlayPause" -> effect(if (viewing.paused) "unpause" else "pause")
        "Stop" -> effect("stop")
        "NextTrack" -> effect("next")
        "PreviousTrack" -> effect("previous")
        "Seek" -> if (viewing.canSeek) effect("seek", bounded(requireNotNull(message.ticks))) else null
        "Rewind" -> if (viewing.canSeek) effect("seek", bounded(viewing.ticks - REMOTE_SKIP_TICKS)) else null
        "FastForward" -> if (viewing.canSeek) effect("seek", bounded(viewing.ticks + REMOTE_SKIP_TICKS)) else null
        else -> null
    }
}

private fun drain(s: RemoteReceiveState, nowMs: Long): RemoteReceiveState {
    val fresh = s.held.filter { nowMs - it.receivedAtMs < REMOTE_INPUT_LIFETIME_MS }
    var viewing = requireNotNull(s.viewing)
    val effects = fresh.mapNotNull { held ->
        transportEffect(s, viewing, held.message)?.also {
            if (it.op == "pause") viewing = viewing.copy(paused = true)
            if (it.op == "unpause") viewing = viewing.copy(paused = false)
        }
    }
    return s.copy(held = emptyList(), viewing = viewing, effects = effects)
}

private fun general(s: RemoteReceiveState, message: RemoteInbound, nowMs: Long): RemoteReceiveState {
    val name = requireNotNull(message.command)
    if (name !in s.advertised) return s.copy(rejection = "unsupported")
    fun effect(op: String, level: Int? = null, text: String? = null, key: String? = null, index: Int? = null) =
        s.copy(effects = listOf(RemoteEffect(op, level = level, text = text, name = key, index = index, generation = s.generation)))
    return when (name) {
        in RemoteNavigationCommands -> {
            val sameWindow = nowMs - s.navWindowMs < 1_000
            val count = if (sameWindow) s.navCount + 1 else 1
            if (count > REMOTE_NAV_PER_SECOND) return s.copy(rejection = "rate-limited")
            effect("nav", key = name).copy(navWindowMs = if (sameWindow) s.navWindowMs else nowMs, navCount = count)
        }
        "SendString" -> {
            if (s.field == "secret") return s.copy(rejection = "secret-field")
            if (s.field != "search") return s.copy(rejection = "no-active-field")
            val text = message.arguments["String"]
            remoteTextRejection(text)?.let { return s.copy(rejection = it) }
            effect("text", text = text)
        }
        "SetAudioStreamIndex", "SetSubtitleStreamIndex" -> {
            if (s.viewing?.ready != true) return s.copy(rejection = "no-active-viewing")
            val index = message.arguments["Index"]?.toIntOrNull()?.takeIf { it >= if (name == "SetAudioStreamIndex") 0 else -1 }
                ?: return s.copy(rejection = "invalid-index")
            effect(if (name == "SetAudioStreamIndex") "audio" else "subtitle", index = index)
        }
        "SetVolume" -> {
            val level = message.arguments["Volume"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: return s.copy(rejection = "invalid-volume")
            effect("volume", level = level)
        }
        in RemoteVolumeCommands -> effect("volume-key", key = name)
        else -> s.copy(rejection = "unsupported")
    }
}
