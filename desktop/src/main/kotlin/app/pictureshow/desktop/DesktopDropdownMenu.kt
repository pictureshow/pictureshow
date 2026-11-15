/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*

/** One owner per window, including menus inside dialogs and the player's custom-positioned popup. */
internal class DesktopMenuOwner {
    private val entries = linkedMapOf<Any, () -> Unit>()
    fun register(key: Any, dismiss: () -> Unit) { entries[key] = dismiss }
    fun remove(key: Any) { entries.remove(key) }
    fun escape(): Boolean {
        val last = entries.entries.lastOrNull() ?: return false
        entries.remove(last.key)
        last.value()
        return true
    }
    val isOpen: Boolean get() = entries.isNotEmpty()
}
internal val LocalDesktopMenus = compositionLocalOf<DesktopMenuOwner?> { null }

/** Escape is intercepted both by popup key dispatch and the window preview, before fallback Back. */
@Composable
internal fun DesktopMenuRegistration(open: Boolean, opener: FocusRequester, dismiss: () -> Unit): (KeyEvent) -> Boolean {
    val owner = LocalDesktopMenus.current
    val key = remember { Any() }
    val latest by rememberUpdatedState(dismiss)
    var restore by remember { mutableStateOf(false) }
    fun close() { latest(); restore = true }
    DisposableEffect(open, owner) {
        if (open) owner?.register(key, ::close)
        onDispose { owner?.remove(key) }
    }
    LaunchedEffect(open, restore) {
        if (!open && restore) { withFrameNanos { }; runCatching { opener.requestFocus() }; restore = false }
    }
    return { e ->
        if (e.key == Key.Escape && e.type == KeyEventType.KeyDown) {
            if (owner?.escape() != true) close()
            true
        } else e.key == Key.Escape && e.type == KeyEventType.KeyUp
    }
}

@Composable
internal fun DesktopDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, opener: FocusRequester, initialFocus: FocusRequester,
                                 modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val keys = DesktopMenuRegistration(expanded, opener, onDismissRequest)
    DropdownMenu(expanded, onDismissRequest, modifier = modifier.onPreviewKeyEvent(keys),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp, shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))) {
        // Request from the popup composition, after its item targets have attached. A focused
        // container makes directional search jump to the last child instead of the next item.
        LaunchedEffect(Unit) { withFrameNanos { }; initialFocus.requestFocus() }
        content()
    }
}
