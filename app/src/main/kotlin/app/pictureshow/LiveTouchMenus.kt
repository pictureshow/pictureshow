/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** The overflow belongs to its trigger; choosing Delete opens the separate scoped confirmation. */
@Composable
internal fun LiveTouchRecordingMenu(itemId: String, title: String, onDeleteRecording: () -> Unit) {
    var expanded by remember(itemId) { mutableStateOf(false) }
    Box(Modifier.testTag("live:recording:options:$itemId")) {
        LiveIcon("more", "Recording options for $title", false) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag("live:recording:menu:$itemId")
        ) {
            DropdownMenuItem(
                text = { Text("Delete recording…") },
                onClick = {
                    expanded = false
                    onDeleteRecording()
                },
                leadingIcon = { Icon(liveIcon("trash"), null) },
                colors = MenuDefaults.itemColors(
                    textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.testTag("live:recording:delete:$itemId")
            )
        }
    }
}

/** Native dialog slots own the keylines; choice rows expose the supplied current selection. */
@Composable
internal fun LiveTouchChoiceMenu(
    title: String,
    onDismiss: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    destructiveLabels: Set<String>,
    selectedIndex: Int?
) {
    val scroll = rememberScrollState()
    val canScroll by remember(scroll) { derivedStateOf { scroll.canScrollBackward || scroll.canScrollForward } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("live:menu"),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, Modifier.testTag("live:menu-title")) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                if (canScroll) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(scroll)
                        .selectableGroup().testTag("live:menu-choices")
                ) {
                    actions.forEachIndexed { index, action ->
                        val destructive = action.first in destructiveLabels
                        val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                        if (selectedIndex != null) {
                            TouchRadioRow(
                                selected = selectedIndex == index,
                                modifier = Modifier.heightIn(min = 56.dp).testTag("live:menu-choice:$index"),
                                onClick = action.second
                            ) {
                                Text(action.first, color = color, style = MaterialTheme.typography.bodyLarge)
                            }
                        } else {
                            TextButton(
                                onClick = action.second,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("live:menu-choice:$index"),
                                contentPadding = PaddingValues(vertical = 12.dp),
                                colors = ButtonDefaults.textButtonColors(contentColor = color)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (destructive) Icon(liveIcon("trash"), null, Modifier.size(24.dp))
                                    Text(action.first, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
                if (canScroll) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
