/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerIconButton(
    icon: String,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    state: String? = null,
    onClick: () -> Unit
) {
    IconButton(
        onClick,
        modifier.size(48.dp).semantics {
            contentDescription = label
            state?.let { stateDescription = it }
        },
        enabled = enabled
    ) { Icon(playerActionIcon(icon), null, Modifier.size(24.dp)) }
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
            if (window != null && window.durationMs > 0) {
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
                LivePlayerTrack(null, 0, accent, Modifier.weight(1f))
            }
            Box(Modifier.width(liveControlWidth()).heightIn(min = 48.dp).testTag("touch:live-slot"), contentAlignment = Alignment.Center) {
                if (atEdge && !paused) {
                    LiveEdgeBadge(tv = false, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("touch:go-live"))
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
