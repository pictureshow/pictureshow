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

/** PLAY-05: BACK pressed shortly after controls auto-hide re-shows them instead of leaving the player. */
const val BACK_AUTO_HIDE_GRACE_MS = 3_000L

/** PLAY-05: how long the "BACK again stops playback" visual hint stays visible after Back hides controls. */
const val BACK_EXIT_HINT_MS = 3_000L

enum class TvPlayerBackAction {
    HideControls,
    ShowControls,
    Exit
}

/**
 * PLAY-05: while controls are up, Back hides them. When hidden, a Back arriving within [BACK_AUTO_HIDE_GRACE_MS]
 * of an *auto*-hide re-shows the controls instead of exiting. A deliberate Back hide clears [autoHiddenAtMs],
 * so a second Back exits immediately. A clock that went backwards never triggers the grace window.
 */
fun tvPlayerBackAction(controlsVisible: Boolean, autoHiddenAtMs: Long?, nowMs: Long): TvPlayerBackAction = when {
    controlsVisible -> TvPlayerBackAction.HideControls
    autoHiddenAtMs != null && nowMs - autoHiddenAtMs in 0..BACK_AUTO_HIDE_GRACE_MS -> TvPlayerBackAction.ShowControls
    else -> TvPlayerBackAction.Exit
}

enum class BackPressPath {
    Key,
    System
}

/**
 * PLAY-05: one physical BACK press acts once, even when the OS delivers it through both Compose key dispatch and the
 * system back dispatcher (e.g. Android 14+ predictive back). A claim absorbs at most one paired event from the other
 * path within [windowMs], never a second press from the same path.
 */
class BackPressGate(private val windowMs: Long = 250L) {
    private var lastPath: BackPressPath? = null
    private var lastAtMs: Long = 0L

    fun admit(path: BackPressPath, nowMs: Long): Boolean {
        val previous = lastPath
        if (previous != null && previous != path && nowMs - lastAtMs in 0..windowMs) {
            lastPath = null
            return false
        }
        lastPath = path
        lastAtMs = nowMs
        return true
    }
}
