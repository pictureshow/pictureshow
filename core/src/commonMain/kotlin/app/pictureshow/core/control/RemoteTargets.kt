/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:kotlinx.serialization.UseSerializers(DecimalLongSerializer::class)

package app.pictureshow.core.control

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** What the target last reported to the server. A controller never infers it from what it sent. */
@Serializable
data class RemoteNowPlaying(
    val item: String,
    val title: String? = null,
    val durationTicks: Long = 0,
    val positionTicks: Long = 0,
    val paused: Boolean = false,
    val canSeek: Boolean = false,
    val audio: Int? = null,
    val subtitle: Int? = null,
    val volume: Int? = null,
    val muted: Boolean = false,
    val tracks: List<RemoteTrack> = emptyList()
)

/** A stream of what is playing, offered only to the viewer who is watching it. */
@Serializable
data class RemoteTrack(val type: String, val index: Int, val label: String)

/** One session the server says this viewer may control and that really can play. */
@Serializable
data class RemoteTarget(
    val session: String,
    val device: String,
    val name: String,
    val client: String = "",
    val viewer: String? = null,
    val viewerName: String? = null,
    val sameViewer: Boolean = false,
    val duplicateName: Boolean = false,
    val commands: List<String> = emptyList(),
    val now: RemoteNowPlaying? = null
)

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonElement?.flag(): Boolean = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true

private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull

private fun JsonElement?.ticks(): Long = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.coerceAtLeast(0) ?: 0

private fun JsonElement?.strings(): List<String> = (this as? JsonArray).orEmpty().mapNotNull { it.text() }

private fun tracks(streams: JsonElement?): List<RemoteTrack> = (streams as? JsonArray).orEmpty().mapNotNull { element ->
    val stream = element as? JsonObject ?: return@mapNotNull null
    val type = stream["Type"].text()?.takeIf { it == "Audio" || it == "Subtitle" } ?: return@mapNotNull null
    val index = stream["Index"].int() ?: return@mapNotNull null
    RemoteTrack(type, index, stream["DisplayTitle"].text() ?: stream["Language"].text() ?: "$type $index")
}

/** Jellyfin writes the same GUID dashed or undashed depending on the DTO. */
internal fun normalizedId(value: String?): String? {
    val compact = value?.lowercase()?.replace("-", "") ?: return null
    return compact.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
}

/**
 * Projects a `GET /Sessions?controllableByUserId=` reply. The server decides the rights; this only filters real capability
 * and hides another viewer's title, which the controller's own account may not be allowed to see.
 */
fun remoteTargets(sessions: JsonArray, viewer: String, selfDevice: String): List<RemoteTarget> {
    val me = normalizedId(viewer)
    val rows = sessions.mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val session = row["Id"].text()?.takeIf { id -> id.all { it.isLetterOrDigit() || it == '-' } } ?: return@mapNotNull null
        val device = row["DeviceId"].text() ?: return@mapNotNull null
        if (device == selfDevice || !row["SupportsRemoteControl"].flag()) return@mapNotNull null
        if ("Video" !in row["PlayableMediaTypes"].strings()) return@mapNotNull null
        val user = normalizedId(row["UserId"].text())?.takeUnless { id -> id.all { it == '0' } }
        val same = user != null && user == me
        val nowItem = row["NowPlayingItem"] as? JsonObject
        val item = normalizedId(nowItem?.get("Id").text())
        val state = row["PlayState"] as? JsonObject
        RemoteTarget(
            session = session,
            device = device,
            name = row["DeviceName"].text() ?: "Unnamed device",
            client = row["Client"].text().orEmpty(),
            viewer = user,
            viewerName = row["UserName"].text().takeIf { user != null },
            sameViewer = same,
            commands = row["SupportedCommands"].strings().distinct(),
            now = item?.let {
                RemoteNowPlaying(
                    item = it,
                    title = nowItem?.get("Name").text().takeIf { same },
                    durationTicks = nowItem?.get("RunTimeTicks").ticks(),
                    positionTicks = state?.get("PositionTicks").ticks(),
                    paused = state?.get("IsPaused").flag(),
                    canSeek = state?.get("CanSeek").flag(),
                    audio = state?.get("AudioStreamIndex").int(),
                    subtitle = state?.get("SubtitleStreamIndex").int(),
                    volume = state?.get("VolumeLevel").int(),
                    muted = state?.get("IsMuted").flag(),
                    tracks = if (same) tracks(nowItem?.get("MediaStreams")) else emptyList()
                )
            }
        )
    }
    val names = rows.groupingBy { it.name }.eachCount()
    return rows.map { it.copy(duplicateName = (names[it.name] ?: 0) > 1) }
        .sortedWith(compareBy({ !it.sameViewer }, { it.name }, { it.session }))
}
