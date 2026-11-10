/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.imageUrl
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.recordingStatus
import coil3.compose.AsyncImage
import java.time.Instant

internal fun LiveChannel.mediaItem() = MediaItem(id, name, type = "TvChannel", imageTags = imageTags, userData = userData)
internal fun LiveProgram.hasRecordingTimer(): Boolean = !timerId.isNullOrBlank()

internal fun liveInstant(value: String?): Long = runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(Long.MIN_VALUE)

@Composable
internal fun LiveChannelMark(channel: LiveChannel, account: Account, tv: Boolean, compact: Boolean = false) {
    val art = imageUrl(account.server, channel.mediaItem(), 96)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(
            Modifier.size(if (compact) 32.dp else 44.dp).clip(RoundedCornerShape(6.dp)).background(LiveAccent.copy(alpha = .12f)),
            Alignment.Center
        ) {
            if (art != null) {
                AsyncImage(art, null, Modifier.size(if (compact) 32.dp else 44.dp))
            } else {
                LiveText(channel.name.take(1).uppercase(), tv)
            }
        }
        LiveText(channel.number.orEmpty(), tv, small = true, maxLines = 1)
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
    tuneEnabled: Boolean,
    onFavorite: () -> Unit,
    onTune: () -> Unit
) {
    val time = liveTime()
    var menu by remember { mutableStateOf(false) }
    val current = programs.firstOrNull { canTune(it, now) } ?: channel.currentProgram?.takeIf { canTune(it, now) }
    val next = programs.firstOrNull { liveInstant(it.startDate) > now }
    val support: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            LiveText(current?.name ?: "No guide data", tv, maxLines = 1)
            current?.let {
                val duration = liveInstant(it.endDate) - liveInstant(it.startDate)
                if (duration > 0) {
                    val fraction = ((now - liveInstant(it.startDate)).toFloat() / duration).coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(LiveMuted.copy(alpha = .2f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(LiveAccent))
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
            onLongClick = if (canFavorite) {
                { menu = true }
            } else {
                null
            },
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(channel.name, false, maxLines = 1) },
            modifier = modifier.fillMaxWidth().clickable(enabled = tuneEnabled, onClick = onTune),
            leadingContent = { LiveChannelMark(channel, account, false) },
            supportingContent = support,
            trailingContent = if (canFavorite) {
                { LiveFavorite(channel.userData.isFavorite, onFavorite) }
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
internal fun LiveScheduleRow(schedule: LiveSchedule, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val time = liveTime()
    val supporting: @Composable () -> Unit = {
        LiveText(
            "${schedule.channelName ?: "Channel ${schedule.channelId}"} · ${time.range(schedule.startDate, schedule.endDate)}",
            tv,
            small = true
        )
    }
    val badge: @Composable () -> Unit = {
        LiveStatus(recordingStatus(schedule.status), tv)
    }
    if (tv) {
        androidx.tv.material3.ListItem(
            selected = false,
            onClick = onClick,
            headlineContent = { LiveText(schedule.name, tv) },
            modifier = modifier.fillMaxWidth(),
            supportingContent = supporting,
            trailingContent = badge,
            colors = androidx.tv.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(schedule.name, tv) },
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
            supportingContent = supporting,
            trailingContent = badge
        )
    }
}

@Composable
internal fun LiveStatus(label: String, tv: Boolean) {
    LiveText(
        label,
        tv,
        Modifier.clip(RoundedCornerShape(4.dp)).background(
            if (label.contains("Recording", true)) androidx.compose.ui.graphics.Color(0xFF813939) else LiveAccent.copy(alpha = .14f)
        ).padding(horizontal = 6.dp, vertical = 3.dp),
        small = true
    )
}

@Composable
internal fun LiveMenu(title: String, tv: Boolean, onDismiss: () -> Unit, actions: List<Pair<String, () -> Unit>>) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(LiveBackground).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LiveText(title, tv, heading = true)
            actions.forEachIndexed { index, action ->
                LiveAction(action.first, tv, Modifier.liveFocus("menu:$index", index == 0), onClick = action.second)
            }
            LiveAction("Close", tv, onClick = onDismiss)
        }
    }
}
