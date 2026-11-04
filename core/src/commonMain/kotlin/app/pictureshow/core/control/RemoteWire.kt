/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val REMOTE_TEXT_LIMIT = 256
private const val REMOTE_MESSAGE_LIMIT = 65_536
private const val REMOTE_ITEM_LIMIT = 1_000

/** Exactly the commands this target implements. A command is advertised here only once its handler exists. */
val RemoteNavigationCommands = listOf("MoveUp", "MoveDown", "MoveLeft", "MoveRight", "Select", "Back", "GoHome")
val RemoteTrackCommands = listOf("SetAudioStreamIndex", "SetSubtitleStreamIndex")
val RemoteVolumeCommands = listOf("SetVolume", "VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute")

fun remoteAdvertisedCommands(fixedVolume: Boolean): List<String> =
    RemoteNavigationCommands + "SendString" + RemoteTrackCommands + if (fixedVolume) emptyList() else RemoteVolumeCommands

/** `POST /Sessions/Capabilities/Full`. The stream profile goes with PlaybackInfo when the target itself resolves a title. */
fun remoteCapabilitiesBody(enabled: Boolean, commands: List<String>): JsonObject = buildJsonObject {
    putJsonArray("PlayableMediaTypes") { if (enabled) add(JsonPrimitive("Video")) }
    putJsonArray("SupportedCommands") { if (enabled) commands.forEach { add(JsonPrimitive(it)) } }
    put("SupportsMediaControl", enabled)
    put("SupportsPersistentIdentifier", true)
}

@Serializable
data class RemoteCommand(
    val op: String,
    val items: List<String> = emptyList(),
    val ticks: Long? = null,
    val index: Int? = null,
    val level: Int? = null,
    val name: String? = null,
    val text: String? = null
)

@Serializable
data class RemoteRequest(val method: String, val path: String, val query: Map<String, String> = emptyMap(), val body: JsonObject? = null)

@Serializable
data class RemoteWire(val request: RemoteRequest? = null, val rejection: String? = null)

private val PlayCommands = mapOf("play-now" to "PlayNow", "play-next" to "PlayNext", "add-to-queue" to "PlayLast")
private val PlaystateCommands = mapOf(
    "pause" to "Pause",
    "play" to "Unpause",
    "stop" to "Stop",
    "next" to "NextTrack",
    "previous" to "PreviousTrack"
)

/** Text the target may receive: nonblank, bounded, and no control characters that could leave a one-line field. */
fun remoteTextRejection(text: String?): String? = when {
    text.isNullOrBlank() -> "invalid-text"
    text.length > REMOTE_TEXT_LIMIT -> "text-too-long"
    text.any { it.isISOControl() } -> "invalid-text"
    else -> null
}

/** Play is query parameters, not a JSON PlayRequest. General commands are JSON. Every path carries the attached session. */
fun remoteRequest(target: RemoteTarget, command: RemoteCommand): RemoteWire {
    val base = "Sessions/${target.session}"
    fun general(name: String, arguments: Map<String, String> = emptyMap()): RemoteWire {
        if (name !in target.commands) return RemoteWire(rejection = "unsupported")
        return RemoteWire(
            RemoteRequest(
                "POST",
                "$base/Command",
                body = buildJsonObject {
                    put("Name", name)
                    putJsonObject("Arguments") { arguments.forEach { (key, value) -> put(key, value) } }
                }
            )
        )
    }
    PlayCommands[command.op]?.let { play ->
        val items = command.items.map { normalizedId(it) ?: return RemoteWire(rejection = "invalid-item") }
        if (items.isEmpty() || items.size > REMOTE_ITEM_LIMIT) return RemoteWire(rejection = "invalid-item")
        if ((command.ticks ?: 0) < 0) return RemoteWire(rejection = "invalid-ticks")
        if (command.op != "play-now" && (command.ticks != null || command.index != null)) return RemoteWire(rejection = "invalid-position")
        val index = command.index
        if (index != null && index !in items.indices) return RemoteWire(rejection = "invalid-index")
        val query = linkedMapOf("playCommand" to play, "itemIds" to items.joinToString(","))
        command.ticks?.let { query["startPositionTicks"] = it.toString() }
        index?.let { query["startIndex"] = it.toString() }
        return RemoteWire(RemoteRequest("POST", "$base/Playing", query))
    }
    PlaystateCommands[command.op]?.let { return RemoteWire(RemoteRequest("POST", "$base/Playing/$it")) }
    return when (command.op) {
        "seek" -> {
            val ticks = command.ticks ?: return RemoteWire(rejection = "invalid-ticks")
            if (ticks < 0) return RemoteWire(rejection = "invalid-ticks")
            RemoteWire(RemoteRequest("POST", "$base/Playing/Seek", mapOf("seekPositionTicks" to ticks.toString())))
        }
        "audio" -> {
            val index = command.index?.takeIf { it >= 0 } ?: return RemoteWire(rejection = "invalid-index")
            general("SetAudioStreamIndex", mapOf("Index" to index.toString()))
        }
        "subtitle" -> {
            val index = command.index?.takeIf { it >= -1 } ?: return RemoteWire(rejection = "invalid-index")
            general("SetSubtitleStreamIndex", mapOf("Index" to index.toString()))
        }
        "volume" -> {
            val level = command.level?.takeIf { it in 0..100 } ?: return RemoteWire(rejection = "invalid-volume")
            general("SetVolume", mapOf("Volume" to level.toString()))
        }
        "general" -> {
            val name = command.name?.takeIf { it in RemoteNavigationCommands || it in RemoteVolumeCommands }
                ?: return RemoteWire(rejection = "unsupported")
            general(name)
        }
        "text" -> {
            remoteTextRejection(command.text)?.let { return RemoteWire(rejection = it) }
            general("SendString", mapOf("String" to requireNotNull(command.text)))
        }
        else -> RemoteWire(rejection = "unsupported")
    }
}

/** One socket message after shape validation. Nothing here has been authorized yet. */
@Serializable
data class RemoteInbound(
    val kind: String,
    val id: String? = null,
    val controller: String? = null,
    val command: String? = null,
    val items: List<String> = emptyList(),
    val ticks: Long? = null,
    val index: Int? = null,
    val arguments: Map<String, String> = emptyMap(),
    val keepAliveSeconds: Int? = null,
    val reason: String? = null
)

private val RemoteJson = Json { ignoreUnknownKeys = true }

private fun malformed(reason: String) = RemoteInbound("malformed", reason = reason)

private fun JsonElement?.textValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/** Numbers only: Jellyfin sends ticks as JSON integers. A fraction, exponent or string is not a position. */
private fun JsonElement?.wholeNumber(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.takeIf { it.longOrNull != null && it.content.all { c -> c.isDigit() || c == '-' } }?.longOrNull

fun parseRemoteMessage(text: String): RemoteInbound {
    if (text.length > REMOTE_MESSAGE_LIMIT) return malformed("oversize")
    val root = try {
        RemoteJson.parseToJsonElement(text) as? JsonObject ?: return malformed("not-an-object")
    } catch (_: SerializationException) {
        return malformed("not-json")
    }
    val type = root["MessageType"].textValue() ?: return malformed("no-type")
    val id = root["MessageId"].textValue()?.takeIf(String::isNotBlank)
    val data = root["Data"]
    fun body(): JsonObject? = data as? JsonObject
    return when (type) {
        "ForceKeepAlive" -> {
            val seconds = (data as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return malformed("bad-keepalive")
            if (seconds !in 1..3_600) malformed("bad-keepalive") else RemoteInbound("keepalive", id, keepAliveSeconds = seconds)
        }
        "Play" -> {
            val play = body() ?: return malformed("no-data")
            val command = play["PlayCommand"].textValue() ?: return malformed("no-command")
            val rawItems = play["ItemIds"] as? JsonArray ?: return malformed("no-items")
            if (rawItems.isEmpty() || rawItems.size > REMOTE_ITEM_LIMIT) return malformed("bad-items")
            val items = rawItems.map { normalizedId(it.textValue()) ?: return malformed("bad-items") }
            val ticks = play["StartPositionTicks"]?.let { it.wholeNumber() ?: return malformed("bad-ticks") }
            val index = play["StartIndex"]?.let { it.wholeNumber()?.takeIf { value -> value in items.indices }?.toInt() ?: return malformed("bad-index") }
            if ((ticks ?: 0) < 0) return malformed("bad-ticks")
            RemoteInbound("play", id, normalizedId(play["ControllingUserId"].textValue()), command, items, ticks, index)
        }
        "Playstate" -> {
            val state = body() ?: return malformed("no-data")
            val command = state["Command"].textValue() ?: return malformed("no-command")
            val ticks = state["SeekPositionTicks"]?.let { it.wholeNumber() ?: return malformed("bad-ticks") }
            RemoteInbound("playstate", id, normalizedId(state["ControllingUserId"].textValue()), command, ticks = ticks)
        }
        "GeneralCommand" -> {
            val general = body() ?: return malformed("no-data")
            val name = general["Name"].textValue() ?: return malformed("no-command")
            val arguments = (general["Arguments"] as? JsonObject).orEmpty().mapValues { (_, value) ->
                (value as? JsonPrimitive)?.contentOrNull ?: return malformed("bad-arguments")
            }
            RemoteInbound("general", id, normalizedId(general["ControllingUserId"].textValue()), name, arguments = arguments)
        }
        else -> RemoteInbound("ignored", id, command = type)
    }
}
