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

/** The live marker is the measured default position, which can precede the end of the decoder's window. */
@Composable
internal fun LivePlayerTrack(window: ObservedLiveWindow?, positionMs: Long, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(12.dp).testTag("player:live-track").clearAndSetSemantics {}) {
        val y = size.height / 2
        val stroke = 4.dp.toPx()
        drawLine(Color.White.copy(alpha = .28f), Offset(0f, y), Offset(size.width, y), stroke, StrokeCap.Round)
        if (window != null && window.durationMs > 0) {
            val fraction = (positionMs.toFloat() / window.durationMs).coerceIn(0f, 1f)
            drawLine(accent, Offset(0f, y), Offset(size.width * fraction, y), stroke, StrokeCap.Round)
            window.goLivePosition()?.let { edge ->
                val x = size.width * edge.toFloat() / window.durationMs
                drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
            }
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
            androidx.tv.material3.Button(
                onClick = onGoLive,
                enabled = window?.goLivePosition() != null,
                scale = playerButtonScale(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("player:go-live")
            ) { androidx.tv.material3.Text("Go live") }
        }
    }
}
