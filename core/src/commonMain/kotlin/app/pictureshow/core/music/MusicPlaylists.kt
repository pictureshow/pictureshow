/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A new playlist is its creator's alone unless they say otherwise. The server's create default is public, which shows it
 * to everyone on the server, so this is always sent and never left to the default (MUS-09).
 */
fun createPlaylistBody(name: String, viewer: String, items: List<String>, isPublic: Boolean = false): JsonObject = buildJsonObject {
    put("Name", JsonPrimitive(name))
    put("MediaType", JsonPrimitive("Audio"))
    put("UserId", JsonPrimitive(viewer))
    put("Ids", JsonArray(items.map(::JsonPrimitive)))
    put("IsPublic", JsonPrimitive(isPublic))
}

/** Names only what changes: the server leaves an absent IsPublic as it was, so a rename can't change who sees a playlist. */
fun renamePlaylistBody(name: String): JsonObject = buildJsonObject { put("Name", JsonPrimitive(name)) }

/**
 * One saved-playlist edit at a time (MUS-09). "unknown" means the server may have done it: the only way out is reading
 * the list back, since sending an append again could add it twice. Entry IDs are the server's, so one song twice is two rows.
 */
@Serializable
data class PlaylistEditState(
    val phase: String = "idle",
    val entries: List<String> = emptyList(),
    val pending: String? = null,
    val effect: String = "none",
    val message: String? = null
)

@Serializable
data class PlaylistEditEvent(val op: String, val edit: String? = null, val entries: List<String> = emptyList())

fun reducePlaylistEdit(state: PlaylistEditState, event: PlaylistEditEvent): PlaylistEditState {
    val s = state.copy(effect = "none", message = null)
    return when (event.op) {
        "loaded" -> s.copy(phase = "idle", entries = event.entries, pending = null)
        "submit" -> when (s.phase) {
            "unknown" -> s.copy(effect = "read-back", message = "Checking whether the last change saved.")
            "sending", "reading" -> s.copy(message = "Still saving the last change.")
            "read-only" -> s.copy(message = "You can't edit this playlist.")
            else -> s.copy(phase = "sending", pending = event.edit, effect = "send")
        }
        "sent" -> if (s.phase == "sending") s.copy(phase = "reading", effect = "read-back") else s
        "denied" -> if (s.phase == "sending") s.copy(phase = "idle", pending = null, message = "The server didn't allow that change.") else s
        "failed" -> if (s.phase == "sending") s.copy(phase = "idle", pending = null, message = "That change didn't save. Try again.") else s
        "ambiguous" -> if (s.phase == "sending") {
            s.copy(phase = "unknown", effect = "read-back", message = "The server didn't answer, so the change may have saved.")
        } else {
            s
        }
        "read" -> if (s.phase == "reading" || s.phase == "unknown") {
            val changed = event.entries != s.entries
            s.copy(
                phase = "idle",
                entries = event.entries,
                pending = null,
                message = when {
                    s.phase == "unknown" && changed -> "That change saved."
                    s.phase == "unknown" -> "That change didn't save. Try again."
                    !changed -> "The server didn't apply that change."
                    else -> null
                }
            )
        } else {
            s
        }
        "read-failed" -> if (s.phase == "reading" || s.phase == "unknown") {
            s.copy(phase = "unknown", message = "Couldn't read the playlist back. Check it before changing it again.")
        } else {
            s
        }
        "read-only" -> s.copy(phase = "read-only")
        else -> error("Unknown playlist edit operation: ${event.op}")
    }
}
