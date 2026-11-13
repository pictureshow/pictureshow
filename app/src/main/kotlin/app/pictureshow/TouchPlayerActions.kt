/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal fun touchLiveSliderAvailable(window: app.pictureshow.core.playback.ObservedLiveWindow?, expired: Boolean): Boolean =
    window != null && window.canTimeShift && !expired

internal const val LIVE_SEEK_MARK_DP = 32
internal const val LIVE_SEEK_DISABLED_ALPHA = .38f
internal const val LIVE_SEEK_SCRIM_ALPHA = .4f

@Composable
internal fun touchPlayerToggleColors(live: Boolean): androidx.compose.material3.IconButtonColors = if (live) {
    IconButtonDefaults.filledIconButtonColors(
        containerColor = Color.Black.copy(alpha = LIVE_SEEK_SCRIM_ALPHA),
        contentColor = Color.White,
        disabledContainerColor = Color.Black.copy(alpha = LIVE_SEEK_SCRIM_ALPHA),
        disabledContentColor = Color.White.copy(alpha = LIVE_SEEK_DISABLED_ALPHA)
    )
} else {
    IconButtonDefaults.filledIconButtonColors()
}

@Composable
internal fun PlayerIconButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: String? = null,
    liveSeek: Boolean = false,
    onClick: () -> Unit
) {
    IconButton(
        onClick,
        modifier.size(48.dp)
            .then(if (liveSeek) Modifier.background(Color.Black.copy(alpha = LIVE_SEEK_SCRIM_ALPHA), CircleShape) else Modifier)
            .semantics {
                contentDescription = label
                state?.let { stateDescription = it }
            },
        enabled = enabled,
        colors = if (liveSeek) {
            IconButtonDefaults.iconButtonColors(
                contentColor = Color.White,
                disabledContentColor = Color.White.copy(alpha = LIVE_SEEK_DISABLED_ALPHA)
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        }
    ) {
        if (liveSeek) LiveSeekMark(forward = icon == "forward10") else Icon(playerActionIcon(icon), null, Modifier.size(24.dp))
    }
}

/** A circular replay/forward mark with an actual numeral; the button owns its speech and disabled tint. */
@Composable
private fun LiveSeekMark(forward: Boolean) {
    val tint = androidx.compose.material3.LocalContentColor.current
    Box(Modifier.size(LIVE_SEEK_MARK_DP.dp).testTag("touch:live-seek-mark").clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(32.dp)) {
            val unit = size.width / 32f
            scale(if (forward) -1f else 1f, 1f) {
                drawArc(
                    tint,
                    -70f,
                    290f,
                    false,
                    topLeft = Offset(5f * unit, 6f * unit),
                    size = Size(22f * unit, 22f * unit),
                    style = Stroke(2f * unit, cap = StrokeCap.Round)
                )
                drawPath(
                    Path().apply {
                        moveTo(5f * unit, 6f * unit)
                        lineTo(12f * unit, 6f * unit)
                        lineTo(5f * unit, 13f * unit)
                        close()
                    },
                    tint
                )
            }
        }
        Text("10", color = tint, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

/** The live action/status has one reserved slot beside the measured position range. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun TouchLivePlayerTimeline(host: PlaybackHostState, ui: TouchControlsState, paused: Boolean) {
    val window = host.liveWindow
    val position = ui.scrubMs ?: host.position
    val readout = liveTimelineReadout(window, position, paused, host.liveBufferExpired, host.liveCanTimeShift)
    val atEdge = window?.atLiveEdge(position, host.liveBufferExpired) == true
    val announced = key(window?.canTimeShift, window?.goLivePosition() != null) {
        rememberAnnouncedPosition(
            paused = paused,
            seekRequest = ui.scrubMs ?: host.seekPreview ?: host.state.seekTargetMs,
            // A growing live window is a decoder tick, not a deliberate seek or a reason to speak again.
            durationMs = 0,
            ended = false,
            positionMs = host.position
        )
    }
    val spoken = remember(paused, announced, host.liveBufferExpired, host.liveCanTimeShift, window?.goLivePosition() != null) {
        liveTimelineLabel(paused, host.liveCanTimeShift, host.liveBufferExpired, window?.behindLiveMs(announced))
    }
    CurrentPositionAccessibility(setOf("touch:scrubber", "touch:live-status")) {
        liveTimelineLabel(
            paused,
            host.liveCanTimeShift,
            host.liveBufferExpired,
            host.liveWindow?.behindLiveMs(ui.scrubMs ?: host.position)
        )
    }
    val accent = MaterialTheme.colorScheme.primary
    Column(Modifier.semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth().testTag("touch:live-position"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (window != null && touchLiveSliderAvailable(window, host.liveBufferExpired)) {
                Slider(
                    value = liveBufferSliderPosition(window, position, host.liveBufferExpired),
                    valueRange = 0f..liveBufferScrubEnd(window).toFloat(),
                    enabled = window.canTimeShift && !host.liveBufferExpired,
                    onValueChange = {
                        ui.scrubMs = it.toLong()
                        ui.interact()
                    },
                    onValueChangeFinished = {
                        ui.scrubMs?.let(host::seekTo)
                        ui.scrubMs = null
                    },
                    track = { LivePlayerTrack(window, position, accent, expired = host.liveBufferExpired) },
                    modifier = Modifier.weight(1f).testTag("touch:scrubber").semantics {
                        contentDescription = "Live playback position"
                        stateDescription = spoken
                    }
                )
            } else {
                LivePlayerTrack(window, position, accent, Modifier.weight(1f), expired = host.liveBufferExpired)
            }
            Box(Modifier.width(liveControlWidth()).heightIn(min = 48.dp).testTag("touch:live-slot"), contentAlignment = Alignment.Center) {
                if (atEdge && !paused) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("touch:go-live")
                            .semantics(mergeDescendants = true) { contentDescription = "Live" },
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Canvas(Modifier.size(6.dp).clearAndSetSemantics {}) { drawCircle(Color(0xFFFF6B6B)) }
                        Text("Live", color = Color.White, modifier = Modifier.clearAndSetSemantics {})
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            host.goLive()
                            ui.interact()
                        },
                        enabled = window?.goLivePosition() != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("touch:go-live")
                    ) { Text("Go live") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                readout.status,
                color = Color.White.copy(alpha = .8f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("touch:live-status").clearAndSetSemantics {
                    contentDescription = "Live playback status"
                    stateDescription = spoken
                }
            )
            Text(
                readout.clock,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag("touch:behind-live").clearAndSetSemantics {}
            )
        }
    }
}
