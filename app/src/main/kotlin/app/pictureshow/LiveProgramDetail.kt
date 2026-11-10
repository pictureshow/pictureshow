/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.canTune

@Composable
internal fun LiveProgramDetail(
    program: LiveProgram,
    state: LiveTvState,
    account: Account,
    tv: Boolean,
    canAct: Boolean,
    now: Long,
    onPlay: (MediaItem) -> Unit,
    onOnce: () -> Unit,
    onSeries: () -> Unit,
    onSchedule: (LiveSchedule) -> Unit
) {
    val time = liveTime()
    val channel = state.channels.firstOrNull { it.id == program.channelId }
    val current = canTune(program, now)
    val timer = state.timers.firstOrNull { it.programId == program.id || it.id == program.timerId }
    val item = MediaItem(program.id, program.name, imageTags = program.imageTags, backdropImageTags = program.backdropImageTags)
    val artwork: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(LiveAccent.copy(alpha = .1f))) {
            if (tv) {
                CatalogArtwork(item, account, true, Modifier.matchParentSize())
            } else {
                TouchCatalogArtwork(item, account, true, Modifier.matchParentSize())
            }
        }
    }
    val information: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            LiveText(program.name, tv, heading = true)
            LiveText(
                listOfNotNull(
                    channel?.let { "Ch ${it.number.orEmpty()} ${it.name}" },
                    time.range(program.startDate, program.endDate),
                    program.seasonNumber?.let { season -> program.episodeNumber?.let { "S$season E$it" } },
                    program.episodeTitle
                ).joinToString(" · "),
                tv,
                small = true
            )
            if (!current && liveInstant(program.startDate) > now) LiveText("Starts at ${time.clock(program.startDate)}", tv, small = true)
            program.overview?.takeIf { it.isNotBlank() }?.let { LiveText(it, tv) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (current &&
                    channel != null
                ) {
                    LiveAction("Watch now", tv, Modifier.liveFocus("program:watch", tv), canAct) { onPlay(channel.mediaItem()) }
                }
                if (state.policy?.manage == true) {
                    if (timer !=
                        null
                    ) {
                        LiveAction(
                            if (timer.status ==
                                "InProgress"
                            ) {
                                "● Recording · options"
                            } else {
                                "Cancel recording…"
                            },
                            tv,
                            enabled = canAct && !state.writing
                        ) { onSchedule(timer) }
                    } else {
                        LiveAction(
                            "Record once…",
                            tv,
                            Modifier.liveFocus("program:record", tv && !current),
                            canAct && !state.writing && liveInstant(program.endDate) > now,
                            onOnce
                        )
                    }
                    if (program.isSeries) {
                        LiveAction(
                            "Record series…",
                            tv,
                            enabled =
                            canAct && !state.writing && liveInstant(program.endDate) > now,
                            onClick = onSeries
                        )
                    }
                }
            }
            if (state.policy?.manage != true) LiveText("Recording management requires server permission.", tv, small = true)
        }
    }
    if (tv) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            artwork(Modifier.width(320.dp))
            Column(Modifier.weight(1f)) { information() }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            artwork(Modifier.fillMaxWidth())
            information()
        }
    }
}

@Composable
internal fun LiveProgramRow(program: LiveProgram, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val time = liveTime()
    if (tv) {
        androidx.tv.material3.ListItem(
            false,
            onClick,
            { LiveText(program.name, tv) },
            modifier.fillMaxWidth(),
            supportingContent = { LiveText(time.range(program.startDate, program.endDate), tv, small = true) },
            trailingContent = { if (program.timerId != null || program.seriesTimerId != null) LiveText("●", tv) }
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(program.name, tv) },
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
            supportingContent = { LiveText(time.range(program.startDate, program.endDate), tv, small = true) },
            trailingContent = { if (program.timerId != null || program.seriesTimerId != null) LiveText("●", tv) }
        )
    }
}
