/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Direction keys seek only on the playback surface. OK on the surface shows the controls on Play/Pause without
 * changing playback; so do UP/DOWN. A visible Ask segment overrides surface OK to skip once.
 * Focused controls keep normal D-pad activation/navigation.
 */
internal fun Modifier.playerRemoteKeys(
    enabled: Boolean,
    rootFocused: Boolean,
    seek: (Long) -> Unit,
    showControls: () -> Unit,
    interact: () -> Unit,
    back: () -> Unit,
    skip: (() -> Unit)? = null
): Modifier = onPreviewKeyEvent { event ->
    val key = event.key
    if (key == Key.Back) {
        if (event.type == KeyEventType.KeyUp) back()
        return@onPreviewKeyEvent true
    }
    if (!enabled) return@onPreviewKeyEvent false
    val center = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter
    if (rootFocused && event.type == KeyEventType.KeyDown && (key == Key.DirectionLeft || key == Key.DirectionRight)) {
        val step = if (event.nativeKeyEvent.repeatCount >= 5) 30_000L else 10_000L
        seek(if (key == Key.DirectionLeft) -step else step)
        true
    } else if (center && rootFocused) {
        if (event.type == KeyEventType.KeyUp) {
            if (skip != null) skip() else showControls()
            interact()
        }
        true
    } else if (rootFocused && event.type == KeyEventType.KeyDown && (key == Key.DirectionUp || key == Key.DirectionDown)) {
        showControls()
        interact()
        true
    } else {
        if (event.type == KeyEventType.KeyDown) interact()
        false
    }
}

/**
 * One remote press seeks once, whichever way it reaches the player. With a screen reader on, an arrow on the
 * hidden-controls surface can arrive as a key event, as the reader's scroll action, or (on a reader or service
 * that forwards the key and also scrolls) as both. Each seek leaves a claim that absorbs exactly one later seek
 * from the other path in the same direction within [windowMs]; a claim never absorbs a seek from its own path.
 * So a real second press is never swallowed: when a reader consumes the keys, every press is an action and none
 * is absorbed; when it forwards them, each press brings its own key and action, which pair with each other.
 * Only a reader that sent one press as a key alone and the next, within the window, as an action alone could
 * lose one; both halves of one press come from one input dispatch, well inside 250 ms.
 */
internal class OnePressSeek(private val windowMs: Long = 250L, private val now: () -> Long = android.os.SystemClock::uptimeMillis) {
    enum class Path { Key, Reader }

    private data class Claim(val path: Path, val forward: Boolean, val atMs: Long)

    private var claim: Claim? = null

    /** True when this seek should run; false when it is the other half of a press that already seeked. */
    fun admit(path: Path, deltaMs: Long): Boolean {
        val at = now()
        val forward = deltaMs > 0
        val previous = claim
        if (previous != null && previous.path != path && previous.forward == forward && at - previous.atMs <= windowMs) {
            claim = null
            return false
        }
        claim = Claim(path, forward, at)
        return true
    }
}
