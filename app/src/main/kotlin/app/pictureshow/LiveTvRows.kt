/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.imageUrl
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.RecordingTone
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.recordingStatus
import app.pictureshow.core.livetv.recordingTone
import coil3.compose.AsyncImage
import java.time.Instant
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun LiveChannel.mediaItem() = MediaItem(id, name, type = "TvChannel", imageTags = imageTags, userData = userData)
internal fun LiveProgram.hasRecordingTimer(): Boolean = !timerId.isNullOrBlank()

internal fun liveInstant(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)

@Composable
internal fun LiveChannelMark(channel: LiveChannel, account: Account, tv: Boolean, compact: Boolean = false, showNumber: Boolean = true) {
    val art = imageUrl(account.server, channel.mediaItem(), 96)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(
                if (compact) {
                    if (tv) 32.dp else 28.dp
                } else {
                    44.dp
                }
            ).clip(RoundedCornerShape(6.dp)).background(LiveAccent.copy(alpha = .12f)),
            Alignment.Center
        ) {
            if (art != null) {
                AsyncImage(
                    art,
                    null,
                    Modifier.size(
                        if (compact) {
                            if (tv) 32.dp else 28.dp
                        } else {
                            44.dp
                        }
                    )
                )
            } else {
                LiveText(channel.name.take(1).uppercase(), tv)
            }
        }
        if (showNumber) LiveText(channel.number.orEmpty(), tv, small = true, maxLines = 1)
    }
}

@Composable
internal fun LiveChannelRow(
    channel: LiveChannel,
    programs: List<LiveProgram>,
    account: Account,
    tv: Boolean,
    now: Long,
    modifier: Modifier,
    canFavorite: Boolean,
    favoriteEnabled: Boolean = true,
    tuneEnabled: Boolean,
    guideUnavailable: Boolean = false,
    onFavorite: () -> Unit,
    onTune: () -> Unit
) {
    val time = liveTime()
    var menu by remember { mutableStateOf(false) }
    val current = programs.firstOrNull { canTune(it, now) } ?: channel.currentProgram?.takeIf { canTune(it, now) }
    val next = programs.firstOrNull { liveInstant(it.startDate) > now }
    val support: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText(current?.name ?: liveChannelIdleLabel(programs.isNotEmpty(), guideUnavailable), tv, maxLines = 1)
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                current?.let {
                    val duration = liveInstant(it.endDate) - liveInstant(it.startDate)
                    if (duration > 0) {
                        val fraction = ((now - liveInstant(it.startDate)).toFloat() / duration).coerceIn(0f, 1f)
                        val progressColor = if (tv) {
                            androidx.tv.material3.LocalContentColor.current
                        } else {
                            androidx.compose.material3.LocalContentColor.current
                        }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(progressColor.copy(alpha = .24f))) {
                            Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(progressColor))
                        }
                    }
                }
            }
            next?.let { LiveText("Next ${time.clock(it.startDate)} · ${it.name}", tv, small = true, maxLines = 1) }
        }
    }
    if (tv) {
        androidx.tv.material3.ListItem(
            selected = false, onClick = onTune, headlineContent = { LiveText(channel.name, true, maxLines = 1) },
            modifier = modifier.fillMaxWidth(),
            enabled = tuneEnabled,
            leadingContent = { LiveChannelMark(channel, account, true) }, supportingContent = support,
            trailingContent = { if (channel.userData.isFavorite) LiveText("★", true) },
            onLongClick = if (canFavorite && favoriteEnabled) {
                { menu = true }
            } else {
                null
            },
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f)
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(channel.name, false, maxLines = 1) },
            modifier = modifier.fillMaxWidth().alpha(if (tuneEnabled) 1f else .38f).clickable(enabled = tuneEnabled, onClick = onTune),
            leadingContent = { LiveChannelMark(channel, account, false) },
            supportingContent = support,
            trailingContent = if (canFavorite) {
                {
                    Box(Modifier.alpha(if (tuneEnabled && !favoriteEnabled) .38f else 1f)) {
                        LiveFavorite(channel.userData.isFavorite, onFavorite, favoriteEnabled)
                    }
                }
            } else {
                null
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    }
    if (menu) {
        LiveMenu(
            channel.name,
            tv,
            { menu = false },
            listOf(
                (if (channel.userData.isFavorite) "Remove favorite" else "Add favorite") to {
                    menu = false
                    onFavorite()
                }
            )
        )
    }
}

@Composable
internal fun LiveScheduleRow(schedule: LiveSchedule, tv: Boolean, modifier: Modifier, seriesRule: Boolean = false, onClick: () -> Unit) {
    val time = liveTime()
    val supporting: @Composable () -> Unit = {
        LiveText(
            "${schedule.channelName ?: "Channel ${schedule.channelId}"} · ${time.range(schedule.startDate, schedule.endDate)}",
            tv,
            small = true
        )
    }
    val badge: @Composable () -> Unit = {
        LiveStatus(liveScheduleStatus(schedule, seriesRule), tv, schedule.fields["Status"]?.jsonPrimitive?.contentOrNull)
    }
    if (tv) {
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = onClick,
            headlineContent = { LiveText(schedule.name, tv, maxLines = 1) },
            modifier = modifier.fillMaxWidth(),
            supportingContent = supporting,
            trailingContent = badge,
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f)
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(schedule.name, tv, maxLines = 1) },
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LiveText(
                            schedule.channelName ?: "Channel ${schedule.channelId}",
                            false,
                            Modifier.weight(1f, fill = false),
                            small = true,
                            maxLines = 1
                        )
                        LiveText(
                            " · " + time.range(schedule.startDate, schedule.endDate).replace(' ', '\u00a0'),
                            false,
                            Modifier.testTag("live:schedule:range:${schedule.id}"),
                            small = true,
                            maxLines = 1
                        )
                    }
                    badge()
                }
            }
        )
    }
}

/** Series rules have no per-airing Status on Jellyfin; a missing field is not an unknown server enum. */
internal fun liveScheduleStatus(schedule: LiveSchedule, seriesRule: Boolean): String =
    schedule.fields["Status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let(::recordingStatus)
        ?: if (seriesRule) "Series rule" else "Status unavailable"

@Composable
internal fun LiveStatus(label: String, tv: Boolean, status: String? = null) {
    val tone = recordingTone(status)
    val container: androidx.compose.ui.graphics.Color
    val content: androidx.compose.ui.graphics.Color
    if (tv) {
        val colors = androidx.tv.material3.MaterialTheme.colorScheme
        container = when (tone) {
            RecordingTone.Error -> colors.errorContainer
            RecordingTone.Live -> colors.primaryContainer
            RecordingTone.Muted -> colors.surfaceVariant
            else -> colors.surfaceVariant
        }
        content = when (tone) {
            RecordingTone.Error -> colors.onErrorContainer
            RecordingTone.Live -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        }
    } else {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        container = when (tone) {
            RecordingTone.Error -> colors.errorContainer
            RecordingTone.Live -> colors.primaryContainer
            RecordingTone.Muted -> colors.surfaceContainerHigh
            else -> colors.surfaceVariant
        }
        content = when (tone) {
            RecordingTone.Error -> colors.onErrorContainer
            RecordingTone.Live -> colors.onPrimaryContainer
            else -> colors.onSurfaceVariant
        }
    }
    Row(
        Modifier.clip(RoundedCornerShape(4.dp)).background(container).padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (tone == RecordingTone.Error) {
            if (tv) {
                androidx.tv.material3.Icon(liveIcon("warning"), null, Modifier.size(16.dp), tint = content)
            } else {
                androidx.compose.material3.Icon(liveIcon("warning"), null, Modifier.size(16.dp), tint = content)
            }
        } else if (tone == RecordingTone.Live) {
            Box(
                Modifier.size(
                    6.dp
                ).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xFFFF6B6B))
            )
        }
        if (tv) {
            androidx.tv.material3.Text(
                label,
                color = content,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                style = androidx.tv.material3.MaterialTheme.typography.bodySmall
            )
        } else {
            androidx.compose.material3.Text(label, color = content, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun LiveMenu(
    title: String,
    tv: Boolean,
    onDismiss: () -> Unit,
    actions: List<Pair<String, () -> Unit>>,
    destructiveLabels: Set<String> = emptySet(),
    selectedIndex: Int? = null
) {
    if (!tv) {
        LiveTouchChoiceMenu(title, onDismiss, actions, destructiveLabels, selectedIndex)
        return
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.heightIn(max = if (tv) 460.dp else 640.dp).clip(RoundedCornerShape(12.dp)).background(LiveBackground)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveText(title, tv, heading = true)
            actions.forEachIndexed { index, action ->
                val destructive = action.first in destructiveLabels
                androidx.tv.material3.ListItem(
                    selected = false,
                    onClick = action.second,
                    headlineContent = {
                        androidx.tv.material3.Text(
                            action.first,
                            color = if (destructive) {
                                androidx.tv.material3.MaterialTheme.colorScheme.error
                            } else {
                                androidx.tv.material3.LocalContentColor.current
                            }
                        )
                    },
                    leadingContent = if (destructive) {
                        {
                            androidx.tv.material3.Icon(
                                liveIcon("trash"),
                                null,
                                tint = androidx.tv.material3.MaterialTheme.colorScheme.error
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().liveFocus("menu:$index", index == 0),
                    colors = androidx.tv.material3.ListItemDefaults.colors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedContainerColor = if (destructive) {
                            androidx.tv.material3.MaterialTheme.colorScheme.errorContainer
                        } else {
                            androidx.tv.material3.MaterialTheme.colorScheme.inverseSurface
                        }
                    ),
                    scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f)
                )
            }
            LiveQuietAction("Close", tv, onClick = onDismiss)
        }
    }
}

/** Missing current coverage is not the same as an absent guide. */
internal fun liveChannelIdleLabel(hasPrograms: Boolean, unavailable: Boolean = false): String = when {
    unavailable -> "Guide unavailable"
    hasPrograms -> "Nothing on now"
    else -> "No guide data"
}

/** Only a successful empty guide establishes missing guide data; a failed read cannot diagnose server configuration. */
internal fun liveGuideEmptyMessage(unavailable: Boolean): String = if (unavailable) "Guide unavailable" else NO_LIVE_GUIDE
