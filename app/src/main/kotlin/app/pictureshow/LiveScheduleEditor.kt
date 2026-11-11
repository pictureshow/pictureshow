/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    onSave: (LiveSchedule) -> Unit
) {
    val time = liveTime()
    var edits by remember(original) { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var confirmed by remember(original) { mutableStateOf(false) }
    var more by remember(original) { mutableStateOf(false) }
    var validationError by remember(original) { mutableStateOf<String?>(null) }
    val merged = JsonObject(original.fields + edits)
    val enabled = !confirmed && !busy
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
                "${if (series) "Record the series" else "Record this broadcast"}: ${original.name}. " +
                    "Completed recordings are retained if you cancel the schedule.",
                tv,
                textInset
            )
            validationError?.let { LiveText(it, tv, textInset, warning = true) }
            LiveAction(
                if (existing) "Confirm save changes" else "Confirm recording",
                tv,
                textInset.liveFocus("editor:confirm", tv),
                !busy
            ) {
                runCatching { editSchedule(original, JsonObject(edits), series) }
                    .onSuccess {
                        validationError = null
                        onSave(it)
                    }
                    .onFailure { validationError = it.message ?: "Review the recording options before saving." }
            }
            LiveAction("Review settings", tv, textInset, enabled = !busy) { confirmed = false }
        } else {
            LiveAction(
                "Review ${if (existing) "changes" else "recording"}",
                tv,
                textInset.liveFocus("editor:review", tv && firstPadding == null, enabled = !busy),
                enabled = !busy
            ) { confirmed = true }
        }
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
                    Modifier.liveFocus("editor:$key", tv && key == firstPadding && !confirmed)
                ) { edits = edits + (key to it) }
            }
        }
        if (series) {
            val boolOptions = listOf(
                Triple("RecordNewOnly", "Record", listOf(JsonPrimitive(true) to "New episodes", JsonPrimitive(false) to "All episodes")),
                Triple("RecordAnyChannel", "Channel", listOf(JsonPrimitive(false) to "This channel", JsonPrimitive(true) to "Any channel")),
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
                        enabled
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
                    enabled
                ) { edits = edits + ("KeepUpTo" to it) }
            }
            if ("KeepUntil" in original.fields) {
                val current = merged["KeepUntil"]?.jsonPrimitive?.contentOrNull
                val choices = listOf(JsonPrimitive("UntilDeleted") to "Never", JsonPrimitive("UntilSpaceNeeded") to "When space is needed")
                val label = when (current) {
                    "UntilDeleted" -> "Never"
                    "UntilSpaceNeeded" -> "When space is needed"
                    "UntilWatched" -> "After watching"
                    "UntilDate" -> "On the scheduled date"
                    else -> "Provider setting"
                }
                LiveOption("Delete", label, choices, tv, enabled) { edits = edits + ("KeepUntil" to it) }
            }
            if ("Days" in original.fields) {
                LiveText("Days", tv, textInset, small = true)
                val days = (merged["Days"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                FlowRow(textInset, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday").forEach { day ->
                        LiveChip("$day: ${if (day in days) "On" else "Off"}", day in days, tv, enabled) {
                            val next = if (day in days) days - day else days + day
                            edits = edits + ("Days" to JsonArray(next.map(::JsonPrimitive)))
                        }
                    }
                }
            }
        }
        if ("Priority" in original.fields) {
            Box(textInset) {
                LiveChip("More options", more, tv, enabled) { more = !more }
            }
            if (more) {
                LiveOption(
                    "Priority",
                    merged["Priority"]?.jsonPrimitive?.contentOrNull ?: "Default",
                    (-2..2).map {
                        JsonPrimitive(it) to
                            when (it) {
                                -2 -> "Lowest"
                                -1 -> "Low"
                                0 -> "Normal"
                                1 -> "High"
                                else -> "Highest"
                            }
                    },
                    tv,
                    enabled
                ) {
                    edits =
                        edits + ("Priority" to it)
                }
            }
        }
        LiveText("Some options depend on your server.", tv, textInset, small = true)
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
    onSelect: (JsonPrimitive) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    if (tv) {
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
        BoxOption(modifier) { TouchValueRow(title, value, enabled) { open = true } }
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
            }
        )
    }
}

@Composable
private fun BoxOption(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) { content() }
}
