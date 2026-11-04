/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.control

import kotlinx.serialization.Serializable

@Serializable
data class ModeState(
    val ownerKind: String = "local",
    val media: String = "video",
    val explicitAudio: Boolean = false,
    val group: String? = null,
    val requestedRoute: String? = null,
    val leaving: Boolean = false,
    val effect: String = "none"
)

@Serializable
data class ModeEvent(val op: String, val route: String? = null)

fun reduceMode(state: ModeState, event: ModeEvent): ModeState {
    val s = state.copy(effect = "none")
    return when (event.op) {
        "background" -> s.copy(
            effect = when {
                s.ownerKind != "local" -> "keep-receiver"
                s.media == "audio" && s.explicitAudio -> "keep-audio"
                else -> "pause-video"
            }
        )
        "transport" -> s.copy(effect = if (s.group != null) "group-request" else "owner-command")
        "route" -> {
            require(event.route in listOf("cast", "airplay")) { "Unknown route" }
            s.copy(requestedRoute = event.route, leaving = false, effect = if (s.group != null) "require-leave" else "select-route")
        }
        "cancel" -> s.copy(requestedRoute = null, leaving = false)
        "leave" -> if (s.requestedRoute == null || s.group == null) s else s.copy(leaving = true, effect = "leave-group")
        "left" -> if (!s.leaving || s.requestedRoute == null) s else s.copy(group = null, leaving = false, effect = "select-route")
        else -> error("Unknown playback-mode operation: ${event.op}")
    }
}
