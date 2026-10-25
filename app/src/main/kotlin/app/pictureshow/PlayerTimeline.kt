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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.pictureshow.core.displayRuntimeMillis

/** Only deliberate interactions update the spoken position, so a focused timeline never chatters on every tick. */
@Composable
internal fun PlayerTimeline(
    positionMs: Long,
    announcedPositionMs: Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    ended: Boolean = false,
    onOpenSeek: () -> Unit = {}
) {
    CurrentPositionAccessibility(setOf("player:position")) {
        spokenPlaybackPosition(if (ended) completedPlaybackPosition(positionMs, durationMs) else positionMs, durationMs)
    }
    val accessibleControls = rememberAccessibilityEnabled()
    val timelinePosition = if (ended) completedPlaybackPosition(positionMs, durationMs) else playbackEndPosition(positionMs, durationMs)
    var spokenPosition by remember(announcedPositionMs, ended) { mutableLongStateOf(if (ended) timelinePosition else announcedPositionMs) }
    val spokenTimelinePosition = playbackEndPosition(spokenPosition, durationMs)
    val total = displayRuntimeMillis(durationMs)
    val fraction = if (total != null) (timelinePosition.toFloat() / total).coerceIn(0f, 1f) else 0f
    Column(modifier.fillMaxWidth().semantics { testTagsAsResourceId = true }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().height(5.dp).background(Color.DarkGray).clearAndSetSemantics {}) {
            Box(Modifier.fillMaxWidth(fraction).height(5.dp).background(Color(0xFFB8C4FF)))
        }
        Row {
            // The clock's compact bounds align with the controls below; a full-width 5 dp target is
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
            Text("  ·  ← / → seek 10 seconds", modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** Status labels may appear over the last decoded frame while an output is replaced. */
@Composable
internal fun PlaybackStatus(label: String, modifier: Modifier = Modifier) {
    Text(label, modifier = modifier.background(Color(0xEE10131C)).padding(16.dp))
}
