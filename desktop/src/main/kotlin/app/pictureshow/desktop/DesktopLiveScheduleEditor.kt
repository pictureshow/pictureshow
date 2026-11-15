/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.pictureshow.core.livetv.*
import kotlinx.serialization.json.*

/** Edit only provider-supplied fields; review is a separate deliberate step before the core submits anything. */
@Composable
internal fun DesktopLiveScheduleEditor(original: LiveSchedule, series: Boolean, existing: Boolean, busy: Boolean,
                                      fresh: Boolean, onDismiss: () -> Unit, onSave: (LiveSchedule) -> Unit) {
    var edits by remember(original) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var review by remember(original) { mutableStateOf(false) }
    var error by remember(original) { mutableStateOf<String?>(null) }
    val values = original.fields + edits
    val changed = edits.any { (key, value) ->
        if (key == "Days") (original.fields[key] as? JsonArray)?.toSet() != (value as? JsonArray)?.toSet()
        else original.fields[key] != value
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (review) if (existing) "Review changes" else "Review recording" else if (series) "Series recording options" else "Recording options") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(original.name, style = MaterialTheme.typography.titleMedium)
            Text("${original.channelName ?: "Selected channel"} · ${liveRange(original.startDate, original.endDate)}")
            if (review) Text(when {
                existing && series -> "Updates this series rule. Completed recordings are kept."
                existing -> "Updates this scheduled airing. Completed recordings are kept."
                series -> "Creates a series rule using the options below. The server decides which airings can be recorded."
                else -> "Records this airing once."
            })
            val numeric = listOf("PrePaddingSeconds" to "Start early (seconds)", "PostPaddingSeconds" to "End late (seconds)", "Priority" to "Priority") +
                if (series) listOf("KeepUpTo" to "Episodes to keep (0 = unlimited)") else emptyList()
            numeric.forEach { (key, label) ->
                if (key in original.fields) {
                    val value = values[key]?.jsonPrimitive?.content.orEmpty()
                    if (review) ReviewOption(label, value, edits[key] != null && original.fields[key] != values[key])
                    else OutlinedTextField(value, { edits = edits + (key to JsonPrimitive(it)) }, label = { Text(label) }, singleLine = true, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().controlFocus().probe("live:option:$key"))
                } else if (!review) Text("$label: not supplied by this server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (series) {
                listOf("RecordNewOnly" to "New episodes only", "RecordAnyTime" to "Any airing time", "RecordAnyChannel" to "Any channel", "SkipEpisodesInLibrary" to "Skip episodes already in library").forEach { (key, label) ->
                    val value = (values[key] as? JsonPrimitive)?.booleanOrNull
                    if (key in original.fields) {
                        if (review) ReviewOption(label, value?.let { if (it) "On" else "Off" } ?: "Provider setting", edits[key] != null && original.fields[key] != values[key])
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, Modifier.weight(1f).padding(top = 12.dp)); Switch(value == true, { edits = edits + (key to JsonPrimitive(it)) }, enabled = !busy && value != null, modifier = Modifier.controlFocus().probe("live:option:$key"))
                        }
                    } else if (!review) Text("$label: not supplied by this server", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if ("Days" in original.fields) {
                    val selected = (values["Days"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
                    if (review) ReviewOption("Days", selected.joinToString(", ").ifBlank { "No days selected" }, changed && edits["Days"] != null)
                    else {
                        Text("Days", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").forEach { day ->
                                FilterChip(day in selected, { val next = if (day in selected) selected - day else selected + day; edits = edits + ("Days" to JsonArray(next.map(::JsonPrimitive))) },
                                    label = { Text(day.take(3)) }, enabled = !busy, modifier = Modifier.controlFocus().semantics { contentDescription = day })
                            }
                        }
                    }
                }
                if ("KeepUntil" in original.fields) {
                    val current = (values["KeepUntil"] as? JsonPrimitive)?.contentOrNull
                    val labels = mapOf("UntilDeleted" to "Never delete automatically", "UntilSpaceNeeded" to "Delete when space is needed", "UntilWatched" to "Delete after watching", "UntilDate" to "Delete on the scheduled date")
                    if (review) ReviewOption("Retention", labels[current] ?: "Provider setting", edits["KeepUntil"] != null && original.fields["KeepUntil"] != values["KeepUntil"])
                    else {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton({ open = true }, enabled = !busy, modifier = Modifier.controlFocus()) { Text(labels[current] ?: "Provider retention setting") }
                            DropdownMenu(open, { open = false }) {
                                listOf("UntilDeleted", "UntilSpaceNeeded").forEach { choice -> DropdownMenuItem(text = { Text(labels.getValue(choice)) }, leadingIcon = { RadioButton(current == choice, null) }, onClick = { edits = edits + ("KeepUntil" to JsonPrimitive(choice)); open = false }) }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (!fresh) Text("Refresh Live TV before saving. Your options remain here for review.")
            Text("Some options depend on your server. Unchanged provider settings are preserved.", style = MaterialTheme.typography.bodySmall)
        } },
        dismissButton = { TextButton({ if (review) review = false else onDismiss() }, enabled = !busy, modifier = Modifier.controlFocus()) { Text(if (review) "Edit options" else "Cancel") } },
        confirmButton = {
            Button({
                runCatching {
                    val typed = edits.mapValues { (key, value) ->
                        if (key in setOf("PrePaddingSeconds", "PostPaddingSeconds", "Priority", "KeepUpTo")) JsonPrimitive(value.jsonPrimitive.content.toInt()) else value
                    }
                    editSchedule(original, JsonObject(typed), series)
                }.onSuccess { draft -> error = null; if (review) onSave(draft) else review = true }
                    .onFailure { error = "Use whole numbers for padding, priority and episode limits. Select at least one day." }
            }, enabled = !busy && (!review || fresh && (!existing || changed)), modifier = Modifier.controlFocus().probe(if (review) "live:editor:confirm" else "live:editor:review")) {
                Text(if (busy) "Saving…" else if (!review) if (existing && !changed) "Review schedule" else "Review recording" else if (existing) "Save changes" else "Confirm recording")
            }
        })
}
@Composable private fun ReviewOption(label: String, value: String, changed: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (changed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}
