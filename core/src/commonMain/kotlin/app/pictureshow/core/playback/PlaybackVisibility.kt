/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

/** Native stopped activities require visibility; a background browser tab may use another platform policy. */
object PlaybackVisibility {
    fun playWhenReady(requested: Boolean, foregroundRequired: Boolean, foreground: Boolean, pauseLatched: Boolean): Boolean =
        requested && !pauseLatched && (!foregroundRequired || foreground)
}

/** PLAY-03: how long after the controls hide a D-pad viewer who brings them back returns to the action they were on. */
const val LAST_ACTION_WINDOW_MS = 30_000L

/**
 * PLAY-03 (TV review T-3): controls shown again from the surface within [LAST_ACTION_WINDOW_MS] of hiding focus the action
 * that last had focus, so a viewer walking to Playback info doesn't start again from Play/Pause. Later, or with no
 * recorded hide, they open on Play/Pause as before. A clock that went backwards never restores.
 */
fun restoresLastAction(hiddenAtMs: Long?, nowMs: Long): Boolean = hiddenAtMs != null && nowMs - hiddenAtMs in 0..LAST_ACTION_WINDOW_MS
