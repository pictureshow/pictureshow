/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.MediaItem
import app.pictureshow.core.isEmptyContainer
import app.pictureshow.core.isWatched
import app.pictureshow.core.resumeLabel

/** A single stable announcement, independent of artwork load, truncation and decorative badges. */
internal fun catalogAnnouncement(item: MediaItem): String = buildList {
    add(item.name)
    if (item.type == "Episode") {
        item.seriesName?.takeIf(String::isNotBlank)?.let(::add)
        item.parentIndexNumber?.let { add("Season $it") }
        item.indexNumber?.let { add("Episode $it") }
    }
    item.productionYear?.let { add(it.toString()) }
    if (item.isWatched) {
        add("Watched")
    } else if (!item.isEmptyContainer && item.userData.playbackPositionTicks > 0) {
        add(resumeLabel(item.userData.playbackPositionTicks))
    }
    item.userData.unplayedItemCount?.takeUnless { item.isEmptyContainer }?.let { add("$it unplayed") }
    if (item.userData.isFavorite) add("Favorite")
}.joinToString(", ")

/** A read-only TV field must open the IME through an accessibility click too, without a hardware key event. */
internal fun Modifier.editWithKeyboard(onEdit: () -> Unit): Modifier = semantics {
    onClick(label = "Edit with keyboard") {
        onEdit()
        true
    }
}

/** Keep a shelf's heading and cards together before moving to the next shelf. */
internal fun Modifier.accessibilityGroup(): Modifier = semantics { isTraversalGroup = true }

/** Long technical text is D-pad focusable so it can scroll; make that focus visible too. */
@androidx.compose.runtime.Composable
internal fun Modifier.readableFocus(): Modifier {
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    return this.onFocusChanged { focused = it.isFocused }
        .border(
            2.dp,
            if (focused) androidx.tv.material3.MaterialTheme.colorScheme.border else androidx.compose.ui.graphics.Color.Transparent
        )
        .padding(4.dp).focusable()
}

/** TV screen readers may use D-pad navigation without touch exploration. Observe any enabled service. */
@androidx.compose.runtime.Composable
internal fun rememberAccessibilityEnabled(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    val manager = androidx.compose.runtime.remember(context) {
        context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    }
    var enabled by androidx.compose.runtime.remember(manager) { androidx.compose.runtime.mutableStateOf(manager.isEnabled) }
    androidx.compose.runtime.DisposableEffect(manager) {
        val listener = android.view.accessibility.AccessibilityManager.AccessibilityStateChangeListener { enabled = it }
        manager.addAccessibilityStateChangeListener(listener)
        onDispose { manager.removeAccessibilityStateChangeListener(listener) }
    }
    return enabled
}
