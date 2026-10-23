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
 * changing playback; so do UP/DOWN. Focused controls keep normal D-pad activation/navigation.
 */
internal fun Modifier.playerRemoteKeys(
    enabled: Boolean,
    rootFocused: Boolean,
    seek: (Long) -> Unit,
    showControls: () -> Unit,
    interact: () -> Unit,
    back: () -> Unit
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
        if (event.type == KeyEventType.KeyUp) showControls()
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
