/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal fun liveCompactHeight(heightDp: Int, tv: Boolean): Boolean = !tv && heightDp < 480

internal fun liveDisclosureRotation(expanded: Boolean): Float = if (expanded) -90f else 90f

internal fun liveRecordingDeleteMessage(name: String): String = "“$name” will be permanently deleted. Recording schedules stay in place."

@Composable
internal fun LiveRecordingDeleteDialog(
    name: String,
    tv: Boolean,
    writing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    val dismiss = { if (!writing) onDismiss() }
    val body = liveRecordingDeleteMessage(name)
    if (!tv) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = dismiss,
            title = { androidx.compose.material3.Text("Delete this recording?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Text(body)
                    error?.let { androidx.compose.material3.Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(dismiss, enabled = !writing, modifier = Modifier.testTag("live:delete:cancel")) {
                    androidx.compose.material3.Text("Cancel")
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onDelete, enabled = !writing, modifier = Modifier.testTag("live:delete:confirm")) {
                    androidx.compose.material3.Text("Delete", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            },
            modifier = Modifier.testTag("live:delete-dialog")
        )
    } else {
        val cancel = remember { FocusRequester() }
        androidx.compose.ui.window.Dialog(onDismissRequest = dismiss) {
            Column(
                Modifier.width(480.dp).background(LiveBackground, RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState()).padding(24.dp).testTag("live:delete-dialog")
            ) {
                androidx.tv.material3.Text("Delete this recording?", style = androidx.tv.material3.MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                androidx.tv.material3.Text(body)
                error?.let { androidx.tv.material3.Text(it, color = androidx.tv.material3.MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    androidx.tv.material3.Button(
                        dismiss,
                        enabled = !writing,
                        modifier = Modifier.focusRequester(cancel).testTag("live:delete:cancel")
                    ) {
                        androidx.tv.material3.Text("Cancel")
                    }
                    androidx.tv.material3.Button(
                        onDelete,
                        enabled = !writing,
                        modifier = Modifier.testTag("live:delete:confirm"),
                        colors = androidx.tv.material3.ButtonDefaults.colors(
                            containerColor = androidx.tv.material3.MaterialTheme.colorScheme.errorContainer,
                            contentColor = androidx.tv.material3.MaterialTheme.colorScheme.onErrorContainer,
                            focusedContainerColor = androidx.tv.material3.MaterialTheme.colorScheme.errorContainer,
                            focusedContentColor = androidx.tv.material3.MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) { androidx.tv.material3.Text("Delete") }
                }
            }
            LaunchedEffect(Unit) { cancel.requestFocus() }
        }
    }
}

@Composable
internal fun LiveGuideWarning(message: String, tv: Boolean, refreshEnabled: Boolean, onRefresh: () -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (tv) 0.dp else 16.dp, vertical = 4.dp)
            .background(colors.errorContainer, RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .testTag("live:guide-warning"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(touchActionIcon("info"), null, tint = colors.onErrorContainer)
        androidx.compose.material3.Text(
            message,
            Modifier.weight(1f),
            color = colors.onErrorContainer,
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall
        )
        if (!tv) {
            androidx.compose.material3.TextButton(
                onRefresh,
                enabled = refreshEnabled,
                modifier = Modifier.semantics { contentDescription = "Refresh" }
            ) {
                androidx.compose.material3.Text("Refresh", color = colors.onErrorContainer)
            }
        }
    }
}
