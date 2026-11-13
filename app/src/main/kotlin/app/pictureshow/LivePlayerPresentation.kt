/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.ObservedLiveWindow

/** The track ends at the measured decoder default, not the potentially later window-duration tail. */
internal fun liveTrackPositionFraction(window: ObservedLiveWindow?, positionMs: Long, expired: Boolean = false): Float? {
    val measured = window?.takeIf { it.canTimeShift && it.contains(positionMs) && !expired } ?: return null
    val edge = measured.goLivePosition()?.takeIf { it > 0 } ?: return null
    return if (measured.atLiveEdge(positionMs)) 1f else (positionMs.toDouble() / edge).toFloat().coerceIn(0f, 1f)
}

/** Both ends describe the observed buffer; there is no wall-clock mapping to a scheduled programme. */
@Composable
internal fun LivePlayerTrack(
    window: ObservedLiveWindow?,
    positionMs: Long,
    accent: Color,
    modifier: Modifier = Modifier,
    expired: Boolean = false
) {
    val fraction = liveTrackPositionFraction(window, positionMs, expired)
    Canvas(modifier.fillMaxWidth().height(12.dp).testTag("player:live-track").clearAndSetSemantics {}) {
        val y = size.height / 2
        val stroke = 4.dp.toPx()
        drawLine(Color.White.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        fraction?.let {
            drawLine(accent, Offset(0f, y), Offset(size.width * it, y), stroke, StrokeCap.Round)
            drawLine(Color.White, Offset(size.width, 0f), Offset(size.width, size.height), 2.dp.toPx())
        }
    }
}

internal fun behindLiveClock(behindMs: Long?): String = behindMs?.let { "−${playerTime(it)}" } ?: "Live"

internal fun liveBehindDescription(behindMs: Long): String {
    val seconds = behindMs.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    return if (minutes > 0) "$minutes min ${seconds % 60} sec behind live" else "$seconds sec behind live"
}

/** Reserve room for both live action labels, including the viewer's font scaling. */
@Composable
internal fun liveControlWidth(): Dp = (96 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp

internal data class LiveTimelineReadout(val status: String, val clock: String)

/** The same measured edge decision controls both visual strings; speech retains the detailed distance. */
internal fun liveTimelineReadout(
    window: ObservedLiveWindow?,
    positionMs: Long,
    paused: Boolean,
    expired: Boolean,
    canTimeShift: Boolean
): LiveTimelineReadout = when {
    expired -> LiveTimelineReadout("Paused · Buffer expired", "")
    !canTimeShift -> LiveTimelineReadout("Time shift unavailable", "")
    window?.behindLiveMs(positionMs) == null -> LiveTimelineReadout("Live position unavailable", "")
    window.atLiveEdge(positionMs, expired) -> LiveTimelineReadout(if (paused) "Paused · At live edge" else "Live", "")
    else -> LiveTimelineReadout(if (paused) "Paused" else "", behindLiveClock(window.behindLiveMs(positionMs)))
}

internal fun liveForwardSeekAllowed(isLive: Boolean, window: ObservedLiveWindow?, positionMs: Long, expired: Boolean): Boolean =
    !isLive || (window?.contains(positionMs) == true && !expired && !window.atLiveEdge(positionMs, expired))

/** Status has full contrast and no input action; a paused edge still needs an explicit resume action. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun LiveEdgeBadge(tv: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier.semantics(mergeDescendants = true) { contentDescription = "Live" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
    ) {
        Canvas(Modifier.size(6.dp).clearAndSetSemantics {}) { drawCircle(Color(0xFFFF6B6B)) }
        if (tv) {
            androidx.tv.material3.Text("Live", color = Color.White, modifier = Modifier.clearAndSetSemantics {})
        } else {
            androidx.compose.material3.Text("Live", color = Color.White, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** The native TV button shares transport sizing, alignment, focus scale and reserved status width. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerLiveEdgeChip(window: ObservedLiveWindow?, positionMs: Long, expired: Boolean, paused: Boolean, onGoLive: () -> Unit) {
    val atEdge = window?.atLiveEdge(positionMs, expired) == true
    Box(Modifier.width(liveControlWidth()).heightIn(min = 48.dp).testTag("player:live-slot"), contentAlignment = Alignment.Center) {
        if (atEdge && !paused) {
            LiveEdgeBadge(tv = true, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player:go-live"))
        } else {
            PlayerActionButton(
                onClick = onGoLive,
                liveStyle = true,
                fillContentWidth = true,
                enabled = window?.goLivePosition() != null,
                scale = playerButtonScale(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player:go-live")
            ) {
                Canvas(Modifier.size(6.dp).clearAndSetSemantics {}) { drawCircle(Color(0xFFFF6B6B)) }
                androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                androidx.tv.material3.Text("Go live")
            }
        }
    }
}

/** A scrub range ends at the measured live target; an unknown target retains only the measured window range. */
internal fun liveBufferScrubEnd(window: ObservedLiveWindow): Long =
    window.goLivePosition()?.takeIf { it > 0 } ?: window.durationMs.coerceAtLeast(0)

/** Native thumb and custom fill use the same two-second edge threshold. */
internal fun liveBufferSliderPosition(window: ObservedLiveWindow, positionMs: Long, expired: Boolean): Float {
    val end = liveBufferScrubEnd(window)
    return liveTrackPositionFraction(window, positionMs, expired)?.let { it * end.toFloat() }
        ?: positionMs.coerceIn(0, end).toFloat()
}
