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
