/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text

/** Only deliberate interactions update the spoken position, so a focused timeline never chatters on every tick. */
@Composable
internal fun PlayerTimeline(positionMs: Long, announcedPositionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier.fillMaxWidth().height(5.dp).background(Color.DarkGray).semantics {
            contentDescription = "Playback position"
            stateDescription =
                if (durationMs > 0) "${playerTime(announcedPositionMs)} of ${playerTime(durationMs)}" else playerTime(announcedPositionMs)
            if (durationMs > 0) {
                progressBarRangeInfo = ProgressBarRangeInfo((announcedPositionMs.toFloat() / durationMs).coerceIn(0f, 1f), 0f..1f)
                setProgress { value ->
                    if (value.isFinite()) {
                        onSeek((durationMs * value.coerceIn(0f, 1f)).toLong())
                        true
                    } else {
                        false
                    }
                }
            }
        }
    ) {
        Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
    }
}

/** Status labels may appear over the last decoded frame while an output is replaced. */
@Composable
internal fun PlaybackStatus(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier = modifier.background(Color(0xEE10131C)).padding(16.dp))
}
