/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** IDs belong to this server. Participants are only the names the authorized server returned. */
data class SyncGroup(val id: String, val name: String, val state: String, val participants: List<String>, val lastUpdatedTicks: Long)

data class SyncQueueEntry(val itemId: String, val playlistItemId: String)

data class SyncQueue(
    val entries: List<SyncQueueEntry>,
    val currentIndex: Int,
    val startPositionTicks: Long,
    val isPlaying: Boolean,
    val repeatMode: String,
    val shuffleMode: String,
    val lastUpdateTicks: Long
) {
    val current: SyncQueueEntry? get() = entries.getOrNull(currentIndex)
}

data class SyncCommand(
    val groupId: String,
    val playlistItemId: String,
    val command: String,
    val whenMs: Long,
    val positionTicks: Long?,
    val emittedAtTicks: Long
)

sealed interface SyncEvent {
    val groupId: String?
    val messageId: String?

    data class Joined(override val groupId: String, override val messageId: String?, val group: SyncGroup) : SyncEvent

    data class Queue(override val groupId: String, override val messageId: String?, val queue: SyncQueue) : SyncEvent

    data class State(override val groupId: String, override val messageId: String?, val state: String, val reason: String) : SyncEvent

    data class Participant(override val groupId: String, override val messageId: String?, val name: String, val joined: Boolean) : SyncEvent

    data class Left(override val groupId: String, override val messageId: String?) : SyncEvent

    data class Denied(override val groupId: String?, override val messageId: String?, val reason: String) : SyncEvent

    data class Command(override val groupId: String, override val messageId: String?, val command: SyncCommand) : SyncEvent

    data class Invalid(override val groupId: String?, override val messageId: String?) : SyncEvent
}

private val syncStates = setOf("Idle", "Waiting", "Paused", "Playing")
private val syncCommands = setOf("Unpause", "Pause", "Seek", "Stop")
private val repeatModes = setOf("RepeatNone", "RepeatOne", "RepeatAll")
private val shuffleModes = setOf("Sorted", "Shuffle")

private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull
private fun JsonObject.number(name: String): Long? = (get(name) as? JsonPrimitive)?.longOrNull
private fun JsonObject.flag(name: String): Boolean? = (get(name) as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.index(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull
private fun JsonObject.requiredText(name: String): String = requireNotNull(text(name)).also { require(it.isNotBlank()) }
private fun JsonObject.objectAt(name: String): JsonObject = get(name) as? JsonObject ?: error("Missing object")
private fun JsonElement.textValue(): String = (this as? JsonPrimitive)?.contentOrNull ?: error("Missing string")

fun parseSyncGroup(value: JsonObject): SyncGroup = SyncGroup(
    value.requiredText("GroupId"),
    value.text("GroupName") ?: error("Missing group name"),
    value.requiredText("State").also { require(it in syncStates) },
    (value["Participants"] as? JsonArray ?: error("Missing participants")).map { it.textValue() },
    requireNotNull(syncTimestampTicks(value.requiredText("LastUpdatedAt")))
)

fun parseSyncQueue(value: JsonObject): SyncQueue {
    val entries = (value["Playlist"] as? JsonArray ?: error("Missing playlist")).map {
        val row = it as? JsonObject ?: error("Invalid entry")
        SyncQueueEntry(row.requiredText("ItemId"), row.requiredText("PlaylistItemId"))
    }
    require(entries.map { it.playlistItemId }.toSet().size == entries.size)
    val index = value.index("PlayingItemIndex") ?: error("Missing current entry")
    require(if (entries.isEmpty()) index in -1..0 else index in entries.indices)
    return SyncQueue(
        entries,
        index,
        requireNotNull(value.number("StartPositionTicks")).also { require(it >= 0) },
        requireNotNull(value.flag("IsPlaying")),
        value.requiredText("RepeatMode").also { require(it in repeatModes) },
        value.requiredText("ShuffleMode").also { require(it in shuffleModes) },
        requireNotNull(syncTimestampTicks(value.requiredText("LastUpdate")))
    )
}

/**
 * Generated SDK wrapper names changed in 10.11; the actual socket discriminator did not.
 * Unrelated messages return null. A malformed/unknown SyncPlay event asks the owner to resnapshot paused.
 */
fun parseSyncMessage(text: String): SyncEvent? {
    var id: String? = null
    var group: String? = null
    var recognized = false
    return try {
        val envelope = Json.parseToJsonElement(text) as? JsonObject ?: return null
        val type = envelope.text("MessageType") ?: return null
        if (type != "SyncPlayCommand" && type != "SyncPlayGroupUpdate") return null
        recognized = true
        id = envelope.text("MessageId")?.takeIf { it.isNotBlank() }
        val data = envelope.objectAt("Data")
        group = data.text("GroupId")?.takeIf { it.isNotBlank() }
        if (type == "SyncPlayCommand") {
            val groupId = requireNotNull(group)
            val command = data.requiredText("Command").also { require(it in syncCommands) }
            val ticks = data.number("PositionTicks")
            require(ticks == null || ticks >= 0)
            require(command == "Stop" || ticks != null)
            SyncEvent.Command(
                groupId,
                id,
                SyncCommand(
                    groupId,
                    data.requiredText("PlaylistItemId"),
                    command,
                    requireNotNull(syncTimestampMillis(data.requiredText("When"))),
                    ticks,
                    requireNotNull(syncTimestampTicks(data.requiredText("EmittedAt")))
                )
            )
        } else {
            val groupId = requireNotNull(group)
            when (val updateType = data.requiredText("Type")) {
                "GroupJoined" -> {
                    val info = parseSyncGroup(data.objectAt("Data"))
                    require(info.id == groupId)
                    SyncEvent.Joined(groupId, id, info)
                }

                "PlayQueue" -> SyncEvent.Queue(groupId, id, parseSyncQueue(data.objectAt("Data")))

                "StateUpdate" -> {
                    val state = data.objectAt("Data")
                    SyncEvent.State(
                        groupId,
                        id,
                        state.requiredText("State").also {
                            require(it in syncStates)
                        },
                        state.requiredText("Reason")
                    )
                }

                "UserJoined", "UserLeft" -> SyncEvent.Participant(
                    groupId,
                    id,
                    requireNotNull(data["Data"]).textValue(),
                    updateType == "UserJoined"
                )

                "GroupLeft" -> SyncEvent.Left(groupId, id)

                "NotInGroup", "GroupDoesNotExist", "LibraryAccessDenied", "CreateGroupDenied", "JoinGroupDenied" ->
                    SyncEvent.Denied(groupId, id, updateType)

                else -> SyncEvent.Invalid(groupId, id)
            }
        }
    } catch (_: Exception) {
        if (recognized) SyncEvent.Invalid(group, id) else null
    }
}

/** Generation is supplied by the socket owner, never trusted from an event payload. */
data class SyncMembership(
    val generation: Long = 0,
    val groupId: String? = null,
    val phase: String = "Idle",
    val group: SyncGroup? = null,
    val queue: SyncQueue? = null,
    val command: SyncCommand? = null,
    val lastCommandTicks: Long? = null,
    val observedState: String? = null,
    val seenMessageIds: List<String> = emptyList(),
    val notice: String? = null
) {
    val active: Boolean get() = phase !in setOf("Leaving", "Error") && (groupId != null || phase == "Joining" || phase == "Reconnecting")
    val hasSnapshot: Boolean get() = group != null && queue != null
}

/**
 * A new queue can arrive while the last observed group phase is Idle, before the server's wait barrier.
 * Only the exact entry explicitly stopped in that Idle phase suppresses readiness; another entry must report.
 */
fun syncReportsReadiness(phase: String, currentEntryId: String, stoppedEntryId: String?): Boolean =
    phase != "Idle" || currentEntryId != stoppedEntryId

/** While an entry is opening, matching media alone is insufficient: the renderer must own the current playlist entry. */
fun syncRendererOwnsEntry(pendingEntryId: String?, renderedEntryId: String?, currentEntryId: String): Boolean =
    pendingEntryId == null || renderedEntryId == currentEntryId

/** A delayed renderer can bind only to the exact membership, playlist entry and media that requested it. */
fun syncOpenMatches(
    openGeneration: Long,
    currentGeneration: Long,
    openEntryId: String,
    currentEntryId: String?,
    openItemId: String,
    currentItemId: String?
): Boolean = openGeneration == currentGeneration && openEntryId == currentEntryId && openItemId == currentItemId

fun beginSyncJoin(state: SyncMembership, groupId: String? = null): SyncMembership =
    SyncMembership(generation = state.generation + 1, groupId = groupId, phase = "Joining")

/** The local resume stays in the renderer; this transition never sends Stop to the group. */
fun leaveSync(state: SyncMembership): SyncMembership = SyncMembership(generation = state.generation + 1)

fun recoverSync(state: SyncMembership, notice: String = "Synchronization needs a fresh group snapshot."): SyncMembership =
    state.copy(phase = "Reconnecting", queue = null, command = null, notice = notice)

fun reduceSyncEvent(state: SyncMembership, event: SyncEvent, generation: Long): SyncMembership {
    if (generation != state.generation || !state.active) return state
    if (state.groupId != null && event.groupId != null && state.groupId != event.groupId) return state
    if (event.messageId != null && event.messageId in state.seenMessageIds) return state
    val seen = event.messageId?.let { (state.seenMessageIds + it) } ?: state.seenMessageIds
    val next = state.copy(seenMessageIds = seen, command = null)
    return when (event) {
        is SyncEvent.Joined -> {
            if (state.group != null && event.group.lastUpdatedTicks < state.group.lastUpdatedTicks) {
                recoverSync(next)
            } else {
                next.copy(
                    groupId = event.groupId,
                    group = event.group.copy(state = state.observedState ?: event.group.state),
                    phase = if (state.queue == null) "Joining" else state.observedState ?: event.group.state,
                    notice = null
                )
            }
        }

        is SyncEvent.Queue -> {
            if (state.queue != null && event.queue.lastUpdateTicks < state.queue.lastUpdateTicks) {
                recoverSync(next)
            } else {
                next.copy(
                    groupId = event.groupId,
                    queue = event.queue,
                    phase = if (state.group == null) "Joining" else state.observedState ?: state.group.state,
                    notice = null
                )
            }
        }

        is SyncEvent.State -> next.copy(
            observedState = event.state,
            group = state.group?.copy(state = event.state),
            phase = if (state.hasSnapshot) event.state else "Joining"
        )

        is SyncEvent.Participant -> next.copy(
            group = state.group?.let {
                it.copy(participants = if (event.joined) (it.participants + event.name).distinct() else it.participants - event.name)
            }
        )

        is SyncEvent.Command -> {
            val command = event.command
            when {
                state.lastCommandTicks != null && command.emittedAtTicks < state.lastCommandTicks -> recoverSync(next)

                command.command == "Stop" && state.group != null && state.queue == null -> next.copy(
                    command = command,
                    lastCommandTicks = command.emittedAtTicks,
                    phase = "Idle"
                )

                !state.hasSnapshot -> recoverSync(next)

                command.emittedAtTicks < requireNotNull(state.queue).lastUpdateTicks -> next

                command.playlistItemId != state.queue?.current?.playlistItemId -> recoverSync(next)

                else -> next.copy(
                    command = command,
                    lastCommandTicks = command.emittedAtTicks,
                    phase = when (command.command) {
                        "Unpause" -> "Playing"
                        "Stop" -> "Idle"
                        "Pause" -> "Paused"
                        else -> state.phase
                    }
                )
            }
        }

        is SyncEvent.Left -> leaveSync(next)

        is SyncEvent.Denied -> next.copy(
            phase = "Error",
            queue = null,
            notice = when (event.reason) {
                "LibraryAccessDenied" -> "This viewer cannot access the group's media. Leave or ask the server administrator for access."
                "GroupDoesNotExist", "NotInGroup" -> "This group is no longer available. Choose a group again."
                else -> "This viewer is not allowed to create or join this group."
            }
        )

        is SyncEvent.Invalid -> recoverSync(next)
    }
}

/** RFC3339 timestamps retain 100 ns ordering even when two events fall within one millisecond. */
fun syncTimestampTicks(text: String): Long? {
    return try {
        val match =
            Regex("^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,7}))?(Z|[+-]\\d{2}:\\d{2})$").matchEntire(text)
                ?: return null
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        val hour = match.groupValues[4].toInt()
        val minute = match.groupValues[5].toInt()
        val second = match.groupValues[6].toInt()
        require(year in 1..9999 && month in 1..12 && hour in 0..23 && minute in 0..59 && second in 0..59)
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val monthDays = listOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        require(day in 1..monthDays[month - 1])
        val previousYear = year - 1L
        val days = previousYear * 365 + previousYear / 4 - previousYear / 100 + previousYear / 400 +
            monthDays.take(month - 1).sum() + day - 1 - 719162
        val zone = match.groupValues[8]
        val offsetSeconds = if (zone == "Z") {
            0
        } else {
            val zoneHours = zone.substring(1, 3).toInt()
            val zoneMinutes = zone.substring(4, 6).toInt()
            require(zoneHours in 0..23 && zoneMinutes in 0..59)
            (zoneHours * 3600 + zoneMinutes * 60) * if (zone[0] == '-') -1 else 1
        }
        (days * 86400 + hour * 3600 + minute * 60 + second - offsetSeconds) * 10_000_000 +
            match.groupValues[7].padEnd(7, '0').toLong()
    } catch (_: Exception) {
        null
    }
}

fun syncTimestampMillis(text: String): Long? = syncTimestampTicks(text)?.let { if (it >= 0) it / 10_000 else (it - 9999) / 10_000 }

/** Names used by platform adapters. */
fun syncUtcMillis(text: String): Long? = syncTimestampMillis(text)
