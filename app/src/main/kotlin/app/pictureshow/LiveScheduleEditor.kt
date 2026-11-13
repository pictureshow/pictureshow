/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.editSchedule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
internal fun LiveScheduleEditor(
    original: LiveSchedule,
    series: Boolean,
    tv: Boolean,
    busy: Boolean,
    existing: Boolean,
    canSubmit: Boolean = true,
    onSave: (LiveSchedule) -> Unit
) {
    val time = liveTime()
    var edits by remember(original) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var confirmed by remember(original) { mutableStateOf(false) }
    var more by remember(original) { mutableStateOf(false) }
    var validationError by remember(original) { mutableStateOf<String?>(null) }
    val merged = JsonObject(original.fields + edits)
    val enabled = !confirmed && !busy
    val hasChanges = liveScheduleHasChanges(original, edits)
    val firstPadding = listOf("PrePaddingSeconds", "PostPaddingSeconds").firstOrNull { it in original.fields }
    val textInset = if (tv) Modifier.padding(start = 16.dp) else Modifier
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LiveText(original.name, tv, textInset, heading = true)
        LiveText(
            "${original.channelName ?: "Channel ${original.channelId}"} · ${time.range(original.startDate, original.endDate)}",
            tv,
            textInset,
            small = true
        )
        if (confirmed) {
            LiveText(
                if (existing) {
                    "Save changes to this ${if (series) "series rule" else "recording schedule"}. Completed recordings are kept."
                } else {
                    "${if (series) "Record the series" else "Record this broadcast"}: ${original.name}. " +
                        "Completed recordings are retained if you cancel the schedule."
                },
                tv,
                textInset
            )
            validationError?.let { LiveText(it, tv, textInset, warning = true) }
            FlowRow(textInset, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LiveAction(
                    if (existing) "Save changes" else "Confirm recording",
                    tv,
                    Modifier.testTag("live:editor:confirm").liveFocus(
                        "editor:confirm",
                        tv && (!existing || hasChanges),
                        enabled =
                        canSubmit && !busy
                    ),
                    liveScheduleCanConfirm(existing, hasChanges, busy, canSubmit)
                ) {
                    runCatching { editSchedule(original, JsonObject(edits), series) }
                        .onSuccess {
                            validationError = null
                            onSave(it)
                        }
                        .onFailure { validationError = it.message ?: "Review the recording options before saving." }
                }
                LiveAction("Edit options", tv, enabled = !busy, primary = false) { confirmed = false }
            }
        } else {
            LiveAction(
                liveScheduleReviewLabel(existing, hasChanges),
                tv,
                textInset.liveFocus("editor:review", tv && firstPadding == null, enabled = !busy),
                enabled = !busy
            ) { confirmed = true }
        }
        Column(
            Modifier.then(
                if (!tv && confirmed) {
                    Modifier.background(
                        androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh,
                        androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ).padding(vertical = 8.dp).testTag("live:review-receipt")
                } else {
                    Modifier
                }
            ),
            verticalArrangement = Arrangement.spacedBy(if (!tv && confirmed) 0.dp else 12.dp)
        ) {
            val padding = listOf(0, 1, 2, 5, 10, 15).map { minutes ->
                JsonPrimitive(minutes * 60) to if (minutes ==
                    0
                ) {
                    "Off"
                } else {
                    "$minutes min"
                }
            }
            listOf("PrePaddingSeconds" to "Start early", "PostPaddingSeconds" to "End late").forEach { (key, label) ->
                if (key in original.fields) {
                    val seconds = merged[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    LiveOption(
                        label,
                        if (seconds ==
                            0
                        ) {
                            "Off"
                        } else if (seconds % 60 == 0) {
                            "${seconds / 60} min"
                        } else {
                            "$seconds seconds"
                        },
                        padding,
                        tv,
                        enabled,
                        Modifier.liveFocus("editor:$key", tv && key == firstPadding && !confirmed),
                        readOnly = confirmed,
                        changed = liveScheduleFieldChanged(original, key, merged[key])
                    ) { edits = edits + (key to it) }
                }
            }
            if (series) {
                val boolOptions = listOf(
                    Triple(
                        "RecordNewOnly",
                        "Record",
                        listOf(JsonPrimitive(true) to "New episodes", JsonPrimitive(false) to "All episodes")
                    ),
                    Triple(
                        "RecordAnyChannel",
                        "Channel",
                        listOf(
                            JsonPrimitive(false) to "This channel",
                            JsonPrimitive(true) to "Any channel"
                        )
                    ),
                    Triple("RecordAnyTime", "Time", listOf(JsonPrimitive(false) to "This time", JsonPrimitive(true) to "Any time")),
                    Triple(
                        "SkipEpisodesInLibrary",
                        "Episodes in library",
                        listOf(
                            JsonPrimitive(true) to "Skip",
                            JsonPrimitive(false) to "Record"
                        )
                    )
                )
                boolOptions.forEach { (key, label, choices) ->
                    if (key in original.fields) {
                        val value = merged[key]?.jsonPrimitive?.booleanOrNull
                        LiveOption(
                            label,
                            choices.firstOrNull {
                                it.first.booleanOrNull == value
                            }?.second ?: "Provider setting",
                            choices,
                            tv,
                            enabled,
                            readOnly = confirmed,
                            changed = liveScheduleFieldChanged(original, key, merged[key])
                        ) {
                            edits =
                                edits + (key to it)
                        }
                    }
                }
                if ("KeepUpTo" in original.fields) {
                    val count = merged["KeepUpTo"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                    LiveOption(
                        "Keep",
                        if (count ==
                            0
                        ) {
                            "All"
                        } else {
                            "Latest $count"
                        },
                        listOf(0, 1, 3, 5, 10).map {
                            JsonPrimitive(it) to if (it ==
                                0
                            ) {
                                "All"
                            } else {
                                "Latest $it"
                            }
                        },
                        tv,
                        enabled,
                        readOnly = confirmed,
                        changed = liveScheduleFieldChanged(original, "KeepUpTo", merged["KeepUpTo"])
                    ) { edits = edits + ("KeepUpTo" to it) }
                }
                if ("KeepUntil" in original.fields) {
                    val current = merged["KeepUntil"]?.jsonPrimitive?.contentOrNull
                    val choices = listOf(
                        JsonPrimitive("UntilDeleted") to "Never",
                        JsonPrimitive("UntilSpaceNeeded") to "When space is needed"
                    )
                    val label = when (current) {
                        "UntilDeleted" -> "Never"
                        "UntilSpaceNeeded" -> "When space is needed"
                        "UntilWatched" -> "After watching"
                        "UntilDate" -> "On the scheduled date"
                        else -> "Provider setting"
                    }
                    LiveOption(
                        "Delete",
                        label,
                        choices,
                        tv,
                        enabled,
                        readOnly = confirmed,
                        changed =
                        liveScheduleFieldChanged(original, "KeepUntil", merged["KeepUntil"])
                    ) { edits = edits + ("KeepUntil" to it) }
                }
                if ("Days" in original.fields) {
                    val days = (merged["Days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                    if (confirmed) {
                        LiveOptionSummary(
                            "Days",
                            liveRecordingDaysLabel(days),
                            tv,
                            liveScheduleFieldChanged(original, "Days", merged["Days"])
                        )
                    } else {
                        LiveText("Days", tv, textInset, small = true)
                        FlowRow(
                            textInset,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            liveRecordingWeekdays.forEach { day ->
                                LiveChip("$day: ${if (day in days) "On" else "Off"}", day in days, tv, enabled = enabled) {
                                    val next = if (day in days) days - day else days + day
                                    edits = edits + ("Days" to JsonArray(next.map(::JsonPrimitive)))
                                }
                            }
                        }
                    }
                }
            }
            if ("Priority" in original.fields) {
                if (!confirmed) LiveOptionsDisclosure(more, tv, enabled) { more = !more }
                if (more || confirmed) {
                    LiveOption(
                        "Priority",
                        liveRecordingPriorityLabel(merged["Priority"]?.jsonPrimitive?.contentOrNull),
                        (-2..2).map { JsonPrimitive(it) to liveRecordingPriorityLabel(it.toString()) },
                        tv,
                        enabled,
                        readOnly = confirmed,
                        changed = liveScheduleFieldChanged(original, "Priority", merged["Priority"])
                    ) {
                        edits =
                            edits + ("Priority" to it)
                    }
                }
            }
        }
        if (tv && confirmed) {
            LiveTvReadOnlySummaryRow("Some options depend on your server.", null, Modifier.testTag("live:review:footer"), small = true)
        } else {
            LiveText("Some options depend on your server.", tv, textInset, small = true)
        }
    }
}

@Composable
private fun LiveOption(
    title: String,
    value: String,
    choices: List<Pair<JsonPrimitive, String>>,
    tv: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    changed: Boolean = false,
    onSelect: (JsonPrimitive) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    if (readOnly) {
        LiveOptionSummary(title, value, tv, changed)
    } else if (tv) {
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = { open = true },
            headlineContent = { LiveText(title, true) },
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            trailingContent = { LiveText(value, true) },
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f),
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    } else {
        BoxOption(modifier) {
            TouchValueRow(title, value, modifier = Modifier, enabled = enabled, trailing = {
                androidx.compose.material3.Icon(
                    touchActionIcon("chevron-right"),
                    null,
                    Modifier.size(20.dp),
                    tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }) { open = true }
        }
    }
    if (open) {
        LiveMenu(
            title,
            tv,
            { open = false },
            choices.map { (key, label) ->
                label to {
                    open = false
                    onSelect(key)
                }
            },
            selectedIndex = choices.indexOfFirst { it.second == value }
        )
    }
}

@Composable
private fun BoxOption(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}

internal val liveRecordingWeekdays = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

internal fun liveRecordingDaysLabel(days: Set<String>): String = when (days) {
    liveRecordingWeekdays.toSet() -> "Every day"
    liveRecordingWeekdays.take(5).toSet() -> "Weekdays"
    liveRecordingWeekdays.takeLast(2).toSet() -> "Weekends"
    else -> liveRecordingWeekdays.filter { it in days }.joinToString().ifEmpty { "No days selected" }
}

internal fun liveRecordingPriorityLabel(value: String?): String = when (value) {
    "-2" -> "Lowest"
    "-1" -> "Low"
    "0" -> "Normal"
    "1" -> "High"
    "2" -> "Highest"
    else -> "Provider setting"
}

@Composable
private fun LiveOptionSummary(title: String, value: String, tv: Boolean, changed: Boolean = false) {
    val modifier = Modifier.testTag("live:summary:$title").semantics(mergeDescendants = true) {}
    if (tv) {
        LiveTvReadOnlySummaryRow(title, value, modifier)
    } else {
        TouchValueSummary(title, value, modifier, changed)
    }
}

/** Read-only summaries still need real remote stops; focus never selects, edits or writes. */
internal fun liveReviewSummaryFocusable(tv: Boolean): Boolean = tv

@Composable
private fun LiveTvReadOnlySummaryRow(title: String, value: String?, modifier: Modifier, small: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    val colors = androidx.tv.material3.MaterialTheme.colorScheme
    CompositionLocalProvider(
        androidx.tv.material3.LocalContentColor provides if (focused) colors.inverseOnSurface else colors.onSurface
    ) {
        Row(
            modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(
                    if (focused) colors.inverseSurface else androidx.compose.ui.graphics.Color.Transparent,
                    androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                ).semantics(mergeDescendants = true) {}
                .onFocusChanged { focused = it.isFocused }
                .focusable(enabled = liveReviewSummaryFocusable(true))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            LiveText(title, true, Modifier.weight(1f), small = small)
            value?.let { LiveText(it, true) }
        }
    }
}

@Composable
private fun LiveOptionsDisclosure(expanded: Boolean, tv: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.testTag("live:editor:more").semantics {
        stateDescription = if (expanded) "Expanded" else "Collapsed"
        role = Role.Button
    }
    val icon: @Composable () -> Unit = {
        val target = Modifier.size(20.dp).graphicsLayer { rotationZ = liveDisclosureRotation(expanded) }
        if (tv) {
            androidx.tv.material3.Icon(touchActionIcon("chevron-right"), null, target)
        } else {
            androidx.compose.material3.Icon(touchActionIcon("chevron-right"), null, target)
        }
    }
    if (tv) {
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = onClick,
            headlineContent = { LiveText("More options", true) },
            trailingContent = icon,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f),
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    } else {
        Row(
            modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            androidx.compose.material3.Text(
                "More options",
                Modifier.weight(1f),
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
            )
            icon()
        }
    }
}

internal fun liveScheduleHasChanges(original: LiveSchedule, edits: Map<String, JsonElement>): Boolean =
    edits.any { (key, value) -> liveScheduleFieldChanged(original, key, value) }

internal fun liveScheduleReviewLabel(existing: Boolean, changed: Boolean): String = if (!existing) {
    "Review recording"
} else if (changed) {
    "Review changes"
} else {
    "Review schedule"
}

internal fun liveScheduleCanConfirm(existing: Boolean, changed: Boolean, busy: Boolean, canSubmit: Boolean = true): Boolean =
    canSubmit && !busy && (!existing || changed)

/** Weekdays are membership, not the insertion order produced while toggling chips. */
internal fun liveScheduleFieldChanged(original: LiveSchedule, key: String, value: JsonElement?): Boolean {
    val before = original.fields[key]
    return if (key == "Days" && before is JsonArray && value is JsonArray) before.toSet() != value.toSet() else before != value
}
