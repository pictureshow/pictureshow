/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.music

import kotlinx.serialization.Serializable

/**
 * The viewer's intent, apart from anything the OS did to it (MUS-04). Resuming after an interruption needs both: the
 * audio resumes only if the viewer still wanted it and the OS gave it back.
 */
@Serializable
data class MusicSessionState(
    val intent: String = "stopped",
    val interrupted: Boolean = false,
    val ducked: Boolean = false,
    val effect: String = "none",
    val keys: List<String> = emptyList()
)

@Serializable
data class MusicSessionEvent(val op: String, val key: String? = null)

private const val RECENT_KEYS = 8

/** "play"/"pause"/"stop-keep"/"stop-clear" are effects on the output. None of them is ever produced twice for one cause. */
fun reduceMusicSession(state: MusicSessionState, event: MusicSessionEvent): MusicSessionState {
    val s = state.copy(effect = "none")
    val active = s.intent == "play" || s.intent == "pause"
    return when (event.op) {
        // A press arrives once by key and again as the same press from a second route (MUS-05).
        "key" -> {
            val key = event.key ?: return s.copy(effect = "ignore")
            if (key in s.keys) s.copy(effect = "ignore") else s.copy(effect = "accept", keys = (s.keys + key).takeLast(RECENT_KEYS))
        }
        "viewer-play" -> if (s.intent == "play" && !s.interrupted) s else s.copy(intent = "play", interrupted = false, effect = "play")
        "viewer-pause" -> if (!active || s.intent == "pause") s else s.copy(intent = "pause", effect = "pause")
        "interrupted" -> if (s.intent != "play" || s.interrupted) s else s.copy(interrupted = true, effect = "pause")
        "interruption-ended" -> when {
            !s.interrupted -> s
            s.intent == "play" -> s.copy(interrupted = false, effect = "play")
            else -> s.copy(interrupted = false)
        }
        // Another app kept the audio focus, or the headphones came out: both are a pause only the viewer undoes.
        "focus-lost", "noisy" -> if (s.intent != "play") s else s.copy(intent = "pause", interrupted = false, effect = "pause")
        "duck" -> s.copy(ducked = true)
        "unduck" -> s.copy(ducked = false)
        "route-connected" -> s
        "video-start" -> if (!active) s else s.copy(intent = "stopped", interrupted = false, ducked = false, effect = "stop-keep")
        "stop" -> if (!active) s else s.copy(intent = "stopped", interrupted = false, ducked = false, effect = "stop-keep")
        "queue-clear", "sign-out", "revoked" ->
            s.copy(intent = "stopped", interrupted = false, ducked = false, effect = "stop-clear")
        else -> error("Unknown music session operation: ${event.op}")
    }
}
