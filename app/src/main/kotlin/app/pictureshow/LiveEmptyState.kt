/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class LiveEmptyAction(val label: String) {
    Back("Back"),
    Retry("Retry"),
    AllChannels("Show all channels"),
    Channels("Go to channels"),
    Guide("Browse guide"),
    ClearFilters("Clear filters")
}

internal data class LiveEmptyPresentation(
    val icon: String,
    val title: String,
    val body: String,
    val action: LiveEmptyAction,
    val showGuideNavigation: Boolean = false
)

/** Decide from observed permission/read results, never interpreting a failed read as missing server configuration. */
internal fun liveEmptyPresentation(
    state: LiveTvState,
    tab: String,
    filteredChannels: Int,
    favorites: Boolean,
    search: String
): LiveEmptyPresentation? {
    if (state.policy?.access == false) {
        return LiveEmptyPresentation(
            "warning",
            "Live TV isn't available on this account",
            "Ask your server administrator to allow Live TV access.",
            LiveEmptyAction.Back
        )
    }
    if (state.loading) return null
    if (tab == "Schedules" && state.policy?.manage == false) {
        return LiveEmptyPresentation(
            "warning",
            "Recording schedules aren't available",
            "Ask your server administrator to allow recording management.",
            LiveEmptyAction.Channels
        )
    }
    val hasContent = when (tab) {
        "Recordings" -> state.recordings.isNotEmpty()
        "Schedules" -> state.timers.isNotEmpty() || state.series.isNotEmpty()
        "Guide" -> state.programs.isNotEmpty()
        else -> state.channels.isNotEmpty()
    }
    if (state.offline && !hasContent) {
        return LiveEmptyPresentation(
            "warning",
            "Couldn't load Live TV",
            "Couldn't reach the server. Check your connection and try again.",
            LiveEmptyAction.Retry
        )
    }
    if (tab in listOf("Channels", "Guide")) {
        if (state.channels.isEmpty()) {
            return LiveEmptyPresentation(
                "tv",
                if (state.tunerEnabled == false) "No tuner configured" else "No channels available",
                "Ask your server administrator to add a tuner in Jellyfin's Live TV settings.",
                LiveEmptyAction.Retry
            )
        }
        if (filteredChannels == 0) {
            return if (favorites && search.isBlank()) {
                LiveEmptyPresentation(
                    "star-outline",
                    "No favorite channels yet",
                    "Star a channel to pin it here.",
                    LiveEmptyAction.AllChannels
                )
            } else {
                LiveEmptyPresentation(
                    "search",
                    "No matching channels",
                    "Try another channel name or clear your search and filters.",
                    LiveEmptyAction.ClearFilters
                )
            }
        }
        if (tab == "Guide" && state.programs.isEmpty()) {
            return LiveEmptyPresentation(
                "tv",
                liveGuideEmptyMessage(false),
                "Try another time, or ask your server administrator to set up a TV guide.",
                LiveEmptyAction.Channels,
                showGuideNavigation = true
            )
        }
    }
    if (tab == "Recordings" && state.recordings.isEmpty()) {
        return LiveEmptyPresentation(
            "tv",
            "No recordings yet",
            "Record a program from the guide to watch it here later.",
            LiveEmptyAction.Guide
        )
    }
    if (tab == "Schedules" && state.timers.isEmpty() && state.series.isEmpty()) {
        return LiveEmptyPresentation(
            "tv",
            "No scheduled recordings",
            "Choose a program in the guide to record once or as a series.",
            LiveEmptyAction.Guide
        )
    }
    return null
}

/** One content keyline and native type hierarchy for every empty, denied and failed-read surface. */
@Composable
internal fun LiveEmptyState(presentation: LiveEmptyPresentation, tv: Boolean, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(
            rememberScrollState()
        ).padding(horizontal = if (tv) 0.dp else 16.dp, vertical = 24.dp).testTag("live:empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        androidx.compose.material3.Icon(liveIcon(presentation.icon), null, Modifier.size(32.dp), tint = LiveMuted)
        val text = Modifier.widthIn(max = 480.dp)
        if (tv) {
            androidx.tv.material3.Text(
                presentation.title,
                text.semantics {
                    heading()
                },
                style = androidx.tv.material3.MaterialTheme.typography.titleMedium
            )
            androidx.tv.material3.Text(
                presentation.body,
                text,
                color = LiveMuted,
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium
            )
        } else {
            androidx.compose.material3.Text(
                presentation.title,
                text.semantics {
                    heading()
                },
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium
            )
            androidx.compose.material3.Text(
                presentation.body,
                text,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
            )
        }
        LiveAction(presentation.action.label, tv, Modifier.liveFocus("empty-action", tv), onClick = onAction)
    }
}

/** Raw transport status is diagnostic information, not an instruction for viewers. */
internal fun liveVisibleError(message: String): String = if (Regex("(?i)\\bhttp\\b|\\b[45][0-9]{2}\\b").containsMatchIn(message)) {
    "Couldn't complete this change. Check your connection and try again."
} else {
    message
}
