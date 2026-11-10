/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

/** Native TV chip follows the same D-pad and assistive focus scale as the rest of the transport. */
@OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)
@Composable
internal fun PlayerLiveEdgeChip(window: ObservedLiveWindow?, positionMs: Long, expired: Boolean, paused: Boolean, onGoLive: () -> Unit) {
    val atEdge = window?.atLiveEdge(positionMs, expired) == true
    androidx.tv.material3.AssistChip(
        onClick = onGoLive,
        enabled = window?.goLivePosition() != null && (!atEdge || paused),
        scale = androidx.tv.material3.AssistChipDefaults.scale(focusedScale = if (rememberAssistiveServiceInUse()) 1f else 1.1f),
        modifier = Modifier.testTag("player:go-live").semantics { contentDescription = if (atEdge) "Live" else "Go live" }
    ) { androidx.tv.material3.Text(if (atEdge) "LIVE" else "Go live") }
}
