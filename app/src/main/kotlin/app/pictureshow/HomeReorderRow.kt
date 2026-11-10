/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

/** A row is one reorder target; the visibility and pin commands retain their own roles. */
@Composable
internal fun HomeReorderRow(
    key: String,
    title: String,
    shown: Boolean,
    tv: Boolean,
    moving: Boolean,
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    toggle: () -> Unit,
    pickOrDrop: () -> Unit,
    move: (Int) -> Unit,
    endDrag: () -> Unit,
    unpin: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val currentMove by rememberUpdatedState(move)
    val currentEnd by rememberUpdatedState(endDrag)
    val currentPick by rememberUpdatedState(pickOrDrop)
    var height by remember { mutableIntStateOf(1) }
    var drag by remember { mutableFloatStateOf(0f) }
    var overflow by remember { mutableStateOf(false) }
    val semantics = Modifier.semantics {
        stateDescription = if (moving) {
            "Moving. Up or Down to move; OK to drop"
        } else if (shown) {
            "Shown"
        } else {
            "Hidden"
        }
        customActions = buildList {
            add(
                CustomAccessibilityAction(if (shown) "Hide row" else "Show row") {
                    toggle()
                    true
                }
            )
            if (canMoveUp) {
                add(
                    CustomAccessibilityAction("Move up") {
                        move(-1)
                        endDrag()
                        true
                    }
                )
            }
            if (canMoveDown) {
                add(
                    CustomAccessibilityAction("Move down") {
                        move(1)
                        endDrag()
                        true
                    }
                )
            }
            if (unpin != null) {
                add(
                    CustomAccessibilityAction("Unpin") {
                        unpin()
                        true
                    }
                )
            }
        }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).onSizeChanged { height = it.height }
            .testTag("home:row:$key").onPreviewKeyEvent { event ->
                if (tv && moving && event.key in listOf(Key.DirectionUp, Key.DirectionDown)) {
                    if (event.type == KeyEventType.KeyDown) move(if (event.key == Key.DirectionUp) -1 else 1)
                    true
                } else {
                    false
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (tv) {
            androidx.tv.material3.IconButton(
                onClick = toggle,
                enabled = enabled,
                modifier = Modifier.size(48.dp).testTag("home:visibility:$key")
            ) {
                androidx.tv.material3.Icon(touchActionIcon(if (shown) "eye" else "eye-off"), if (shown) "Hide $title" else "Show $title")
            }
            TvSettingsRow(
                title,
                null,
                modifier = Modifier.weight(1f).then(semantics).testTag("home:reorder:$key"),
                selected = moving,
                enabled = enabled,
                onClick = pickOrDrop,
                trailing = { androidx.tv.material3.Icon(touchActionIcon("drag"), if (moving) "Drop row" else "Reorder row") }
            )
            if (unpin != null) {
                androidx.tv.material3.IconButton(
                    onClick = { overflow = !overflow },
                    enabled = enabled,
                    modifier = Modifier.size(48.dp).testTag("home:overflow:$key")
                ) {
                    androidx.tv.material3.Icon(touchActionIcon("more"), "Options for $title")
                }
            }
        } else {
            IconToggleButton(
                checked = shown,
                onCheckedChange = { toggle() },
                enabled = enabled,
                modifier = Modifier.testTag("home:visibility:$key")
            ) {
                Icon(touchActionIcon(if (shown) "eye" else "eye-off"), if (shown) "Hide $title" else "Show $title")
            }
            Text(title, Modifier.weight(1f).then(semantics), style = MaterialTheme.typography.bodyLarge)
            IconButton(
                onClick = pickOrDrop,
                enabled = enabled,
                modifier = Modifier.testTag("home:reorder:$key").pointerInput(key, enabled) {
                    if (enabled) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                drag = 0f
                                currentPick()
                            },
                            onDragEnd = { currentEnd() },
                            onDragCancel = { currentEnd() },
                            onDrag = { change, amount ->
                                change.consume()
                                drag += amount.y
                                if (kotlin.math.abs(drag) >= height) {
                                    val delta = if (drag > 0) 1 else -1
                                    currentMove(delta)
                                    drag -= delta * height
                                }
                            }
                        )
                    }
                }
            ) { Icon(touchActionIcon("drag"), "Reorder $title") }
            if (unpin != null) {
                IconButton(
                    onClick = { overflow = true },
                    enabled = enabled,
                    modifier = Modifier.testTag("home:overflow:$key")
                ) { Icon(touchActionIcon("more"), "Options for $title") }
            }
        }
        // Only pinned rows have this overflow. It never expands the normal row into a stack of bars.
        if (tv && overflow) {
            TvSettingsChoicePicker(
                "Row options",
                "Unpin",
                listOf("Unpin"),
                { it },
                { unpin?.invoke() },
                { overflow = false }
            )
        }
        DropdownMenu(expanded = !tv && overflow, onDismissRequest = { overflow = false }) {
            DropdownMenuItem(text = { Text("Unpin") }, onClick = {
                overflow = false
                unpin?.invoke()
            })
        }
    }
}
