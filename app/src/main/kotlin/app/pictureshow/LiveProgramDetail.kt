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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.LiveSchedule
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.recordingStatus

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
    onSchedule: (LiveSchedule, Boolean) -> Unit
) {
    val time = liveTime()
    val channel = state.channels.firstOrNull { it.id == program.channelId }
    val current = canTune(program, now)
    val timer = state.timers.firstOrNull { it.programId == program.id || it.id == program.timerId }
    val seriesRule = state.series.firstOrNull { it.id == program.seriesTimerId && it.id.isNotBlank() }
    val item = MediaItem(program.id, program.name, imageTags = program.imageTags, backdropImageTags = program.backdropImageTags)
    val artwork: @Composable (Modifier) -> Unit = { modifier ->
        Box(
            modifier.testTag(
                "live:program-artwork"
            ).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(LiveAccent.copy(alpha = .1f))
        ) {
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
            timer?.let { scheduled ->
                Row(
                    Modifier.testTag("live:program-recording-status"),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (scheduled.status == "New") LiveRecordingDot()
                    LiveStatus(liveProgramRecordingLabel(scheduled.status), tv, scheduled.status)
                }
            }
            if (!current && liveInstant(program.startDate) > now) LiveText("Starts at ${time.clock(program.startDate)}", tv, small = true)
            program.overview?.takeIf { it.isNotBlank() }?.let { LiveText(it, tv) }
            LiveProgramActions(
                tv = tv,
                canWatch = current && channel != null,
                canRecord = state.policy?.manage == true,
                hasSeries = program.isSeries,
                watch = { modifier, primary ->
                    LiveProgramAction(
                        "Watch now",
                        tv,
                        primary,
                        modifier.liveFocus("program:watch", tv),
                        enabled = canAct,
                        playIcon = true
                    ) { channel?.let { onPlay(it.mediaItem()) } }
                },
                record = { modifier, primary ->
                    if (timer != null) {
                        LiveProgramAction(
                            if (timer.status == "InProgress") "● Recording · options" else "Cancel recording…",
                            tv,
                            primary,
                            modifier.liveFocus("program:record", liveProgramRecordInitialFocus(tv, primary)),
                            enabled = canAct && !state.writing
                        ) { onSchedule(timer, false) }
                    } else {
                        LiveProgramAction(
                            "Record once…",
                            tv,
                            primary,
                            modifier.liveFocus("program:record", liveProgramRecordInitialFocus(tv, primary)),
                            enabled = canAct && !state.writing && liveInstant(program.endDate) > now,
                            onClick = onOnce
                        )
                    }
                },
                series = { modifier, primary ->
                    LiveProgramAction(
                        if (seriesRule != null) "Manage series…" else "Record series…",
                        tv,
                        primary,
                        modifier,
                        enabled = canAct && !state.writing && liveInstant(program.endDate) > now,
                        onClick = { if (seriesRule != null) onSchedule(seriesRule, true) else onSeries() }
                    )
                }
            )
            if (state.policy?.manage != true) LiveText("Recording management requires server permission.", tv, small = true)
        }
    }
    val contentModifier = Modifier.padding(horizontal = liveProgramContentInset(tv).dp)
    if (tv) {
        Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            artwork(Modifier.width(320.dp))
            Column(Modifier.weight(1f)) { information() }
        }
    } else {
        Column(contentModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            artwork(Modifier.fillMaxWidth())
            information()
        }
    }
}

@Composable
internal fun LiveProgramRow(program: LiveProgram, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val time = liveTime()
    val supporting: @Composable () -> Unit = {
        Column {
            LiveText(time.range(program.startDate, program.endDate), tv, small = true)
            if (program.hasRecordingTimer()) LiveText("Recording scheduled", tv, small = true)
        }
    }
    if (tv) {
        androidx.tv.material3.ListItem(
            false,
            onClick,
            { LiveText(program.name, tv) },
            modifier.fillMaxWidth(),
            supportingContent = supporting,
            trailingContent = { if (program.hasRecordingTimer()) LiveRecordingDot() },
            scale = androidx.tv.material3.ListItemDefaults.scale(focusedScale = 1f)
        )
    } else {
        androidx.compose.material3.ListItem(
            headlineContent = { LiveText(program.name, tv) },
            modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
            supportingContent = supporting,
            trailingContent = { if (program.hasRecordingTimer()) LiveRecordingDot() }
        )
    }
}

/** The primary recording action takes TV entry focus only when Watch is absent. */
internal fun liveProgramRecordInitialFocus(tv: Boolean, primary: Boolean): Boolean = tv && primary

/** The destination owns32dp; TV programme content reaches the shared48dp page keyline. */
internal fun liveProgramContentInset(tv: Boolean): Int = if (tv) 16 else 0

/** Keep an explicit recording fact separate from its action, preserving every non-scheduled server status. */
internal fun liveProgramRecordingLabel(status: String): String = if (status == "New") "Recording scheduled" else recordingStatus(status)
