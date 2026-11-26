/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Chapter
import app.pictureshow.core.displayRuntimeMillis
import app.pictureshow.core.playback.ObservedLiveWindow

/** Only deliberate interactions update the spoken position, so a focused timeline never chatters on every tick. */
@Composable
internal fun PlayerTimeline(
    positionMs: Long,
    announcedPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    ended: Boolean = false,
    arrowsSeek: Boolean = false,
    seeking: Boolean = false,
    chapters: List<Chapter> = emptyList(),
    live: Boolean = false,
    liveCanTimeShift: Boolean = false,
    liveExpired: Boolean = false,
    liveEdgeAvailable: Boolean = false,
    liveWindow: ObservedLiveWindow? = null,
    onGoLive: () -> Unit = {},
    onOpenSeek: () -> Unit = {}
) {
    if (live) {
        val readout = liveTimelineReadout(liveWindow, positionMs, paused, liveExpired, liveCanTimeShift)
        val spoken = remember(paused, announcedPositionMs, liveExpired, liveCanTimeShift, liveWindow?.goLivePosition() != null) {
            liveTimelineLabel(paused, liveCanTimeShift, liveExpired, liveWindow?.behindLiveMs(announcedPositionMs))
        }
        CurrentPositionAccessibility(setOf("player:position")) {
            liveTimelineLabel(paused, liveCanTimeShift, liveExpired, liveWindow?.behindLiveMs(positionMs))
        }
        Column(modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val endpoints = liveBufferEndpointLabels(liveWindow, positionMs, liveExpired)
                Text(endpoints.first, style = androidx.tv.material3.MaterialTheme.typography.labelMedium)
                if (endpoints.second.isNotEmpty()) {
                    Text(
                        endpoints.second,
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium
                    )
                }
            }
            LivePlayerTrack(liveWindow, positionMs, Color(0xFFB8C4FF), expired = liveExpired)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    liveTimelineVisualStatus(readout, liveWindow?.behindLiveMs(positionMs)),
                    modifier = Modifier.clearAndSetSemantics {}
                )
                Box(
                    modifier = Modifier.widthIn(min = 48.dp).testTag("player:position")
                        .clickable(enabled = liveCanTimeShift && !liveExpired, onClickLabel = "Seek in live buffer", onClick = onOpenSeek)
                        .semantics {
                            contentDescription = "Live playback position"
                            stateDescription = spoken
                        },
                    contentAlignment = Alignment.CenterEnd
                ) { Text(readout.clock, modifier = Modifier.clearAndSetSemantics {}) }
            }
        }
        return
    }
    CurrentPositionAccessibility(setOf("player:position")) {
        spokenPlaybackPosition(if (ended) completedPlaybackPosition(positionMs, durationMs) else positionMs, durationMs)
    }
    val accessibleControls = rememberAssistiveServiceInUse()
    val timelinePosition = if (ended) completedPlaybackPosition(positionMs, durationMs) else playbackEndPosition(positionMs, durationMs)
    var spokenPosition by remember(announcedPositionMs, ended) { mutableLongStateOf(if (ended) timelinePosition else announcedPositionMs) }
    val spokenTimelinePosition = playbackEndPosition(spokenPosition, durationMs)
    val total = displayRuntimeMillis(durationMs)
    val fraction = if (total != null) (timelinePosition.toFloat() / total).coerceIn(0f, 1f) else 0f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(20.dp).clearAndSetSemantics {}) {
            val trackHeight = (if (seeking) 9.dp else 7.dp).toPx()
            val top = (size.height - trackHeight) / 2f
            val corner = CornerRadius(trackHeight / 2f, trackHeight / 2f)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.24f),
                topLeft = Offset(0f, top),
                size = Size(size.width, trackHeight),
                cornerRadius = corner
            )
            val fillWidth = (size.width * fraction).coerceIn(0f, size.width)
            if (fillWidth > 0f) {
                val fillLeft = if (rtl) size.width - fillWidth else 0f
                drawRoundRect(
                    color = Color(0xFFB8C4FF),
                    topLeft = Offset(fillLeft, top),
                    size = Size(fillWidth, trackHeight),
                    cornerRadius = corner
                )
            }
            if (total != null && total > 0L) {
                val tickWidth = 2.dp.toPx()
                for (chapter in chapters) {
                    if (chapter.startMs in 1L until total) {
                        val chapterFraction = chapter.startMs.toFloat() / total.toFloat()
                        val x = if (rtl) size.width * (1f - chapterFraction) else size.width * chapterFraction
                        drawRect(
                            color = Color(0xB30A0D14),
                            topLeft = Offset((x - tickWidth / 2f).coerceIn(0f, size.width - tickWidth), top),
                            size = Size(tickWidth, trackHeight)
                        )
                    }
                }
                val thumbRadius = (if (seeking) 8.5.dp else 7.dp).toPx()
                val maxThumbX = (size.width - thumbRadius).coerceAtLeast(thumbRadius)
                val thumbX = (if (rtl) size.width - fillWidth else fillWidth).coerceIn(thumbRadius, maxThumbX)
                val centerY = size.height / 2f
                drawCircle(Color(0x990A0D14), radius = thumbRadius + 1.5.dp.toPx(), center = Offset(thumbX, centerY))
                drawCircle(Color.White, radius = thumbRadius, center = Offset(thumbX, centerY))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // The clock's compact bounds align with the controls below; a full-width bar target is
            // skipped by TV TalkBack's directional distance scoring.
            Box(
                Modifier.testTag("player:position").focusProperties { canFocus = accessibleControls }
                    .onFocusChanged { if (it.isFocused) spokenPosition = timelinePosition }
                    .clickable(onClickLabel = "Seek", onClick = onOpenSeek).semantics {
                        role = Role.Button
                        // Late screen-reader recovery after Done encounters the position before the title.
                        traversalIndex = -1f
                        contentDescription = "Playback position"
                        onClick(label = "Seek") {
                            onOpenSeek()
                            true
                        }
                        stateDescription =
                            if (total !=
                                null
                            ) {
                                "${playerTime(spokenTimelinePosition)} of ${playerTime(total)}"
                            } else {
                                playerTime(spokenTimelinePosition)
                            }
                    }
            ) { Text(playerClock(timelinePosition, durationMs, paused), modifier = Modifier.clearAndSetSemantics {}) }
            // Keep the clock and row geometry stable while arrows navigate a focused control.
            Text(
                "  ·  ← / → seek · hold for faster",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFB8C0D2),
                modifier = Modifier.alpha(if (arrowsSeek) 1f else 0f).clearAndSetSemantics {}
            )
            Spacer(Modifier.weight(1f))
            if (total != null && !ended) {
                val remaining = (total - timelinePosition).coerceAtLeast(0L)
                Text(
                    "-${playerTime(remaining)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD0D5E2),
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }
        }
    }
}

/**
 * What the position control says until the viewer acts again: captured on pause or resume, and once per seek.
 * The caller composes this only while the controls are up, so hiding them forgets it and opening captures anew.
 * A seek's preview expiring or the player finishing the seek is not news, and a changed state description on
 * the focused control is read aloud, so neither captures a new position.
 *
 * [seekRequest] is the newest target the viewer asked for, or null with none in flight. A new value is a new seek,
 * and so is the same value after none, such as Left at the start a second time.
 */
@Composable
internal fun rememberAnnouncedPosition(paused: Boolean, seekRequest: Long?, durationMs: Long, ended: Boolean, positionMs: Long): Long {
    val seeks = remember { SeekCount() }
    if (seekRequest != seeks.request) {
        if (seekRequest != null) seeks.count++
        seeks.request = seekRequest
    }
    return remember(paused, seeks.count, durationMs, ended) { seekRequest ?: positionMs }
}

private class SeekCount {
    var request: Long? = null
    var count = 0
}

/** Status labels may appear over the last decoded frame while an output is replaced. */
@Composable
internal fun PlaybackStatus(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier = modifier.background(Color(0xEE10131C)).padding(16.dp))
}

internal fun liveTimelineLabel(paused: Boolean, canTimeShift: Boolean, expired: Boolean, behindMs: Long? = null): String = when {
    expired -> "Paused · Buffer expired. Go live to resume."
    !canTimeShift -> "Live · Time shift unavailable"
    behindMs == null -> "Live position unavailable"
    paused && behindMs != null && behindMs > 2_000 -> "Paused · ${liveBehindDescription(behindMs)}"
    paused -> "Paused · At live edge"
    behindMs != null && behindMs > 2_000 -> liveBehindDescription(behindMs)
    else -> "Live"
}

/** Keep the compact negative clock, with explicit units nearby to distinguish seconds from programme minutes. */
internal fun liveTimelineVisualStatus(readout: LiveTimelineReadout, behindMs: Long?): String =
    if (readout.clock.isNotEmpty() && behindMs != null) {
        listOf(readout.status, liveBehindDescription(behindMs)).filter(String::isNotBlank).joinToString(" · ")
    } else {
        readout.status
    }

/** Endpoint words never claim an available live target when the decoder cannot establish it. */
internal fun liveBufferEndpointLabels(window: ObservedLiveWindow?, positionMs: Long, expired: Boolean): Pair<String, String> = when {
    expired -> "Buffer expired" to ""
    window?.canTimeShift != true -> "Time shift unavailable" to ""
    liveTrackPositionFraction(window, positionMs, expired) != null -> "Available buffer · start" to "Live edge"
    else -> "Measured buffer" to "Live edge unavailable"
}
