/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

internal data class DiscoveryFocus(val container: Modifier, val firstServer: Modifier)

/** A discovery hint may take initial focus, but cannot take it back after the user starts interacting. */
@Composable
internal fun rememberDiscoveryFocus(firstServerId: String?, connecting: Boolean): DiscoveryFocus {
    val firstServer = remember { FocusRequester() }
    var interacted by rememberSaveable { mutableStateOf(false) }
    var offered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(firstServerId, interacted, connecting) {
        if (firstServerId != null && !interacted && !offered && !connecting) {
            delay(1_000)
            withFrameNanos { }
            firstServer.requestFocus()
            offered = true
        }
    }
    return DiscoveryFocus(
        container = Modifier.onPreviewKeyEvent {
            if (it.type == KeyEventType.KeyDown) interacted = true
            false
        }.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.pressed }) interacted = true
                }
            }
        },
        firstServer = Modifier.focusRequester(firstServer)
    )
}
