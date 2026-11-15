/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.*
import kotlinx.serialization.json.*

/** Translate measured mpv timestamps into the shared observed-window model. No guide/runtime fallback. */
internal fun desktopLiveWindow(cache: String?, seekable: Boolean): ObservedLiveWindow? = runCatching {
    val state = Json.parseToJsonElement(cache ?: return null).jsonObject
    val ranges = state["seekable-ranges"]?.jsonArray ?: return null
    val range = ranges.lastOrNull()?.jsonObject ?: return null
    val start = range["start"]?.jsonPrimitive?.doubleOrNull ?: return null
    val end = range["end"]?.jsonPrimitive?.doubleOrNull ?: return null
    if (!start.isFinite() || !end.isFinite() || end <= start || start < 0) return null
    ObservedLiveWindow((start * 1000).toLong(), ((end - start) * 1000).toLong(), ((end - start) * 1000).toLong(), seekable)
}.getOrNull()

internal val DesktopPlaybackState.isLiveChannel: Boolean get() = liveChannel != null || decision.isLive
internal fun DesktopPlaybackState.canTimeShift(): Boolean = liveWindow?.canTimeShift == true && !bufferExpired

@Composable internal fun DesktopLiveTimeline(p: DesktopPlaybackState, c: DesktopController) {
    val w = p.liveWindow
    val position = ((p.positionSeconds * 1000).toLong() - (w?.offsetMs ?: 0)).coerceAtLeast(0)
    val behind = w?.behindLiveMs(position)
    val edge = w?.atLiveEdge(position, p.bufferExpired) == true && !p.paused
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when {
                p.bufferExpired -> "Buffer expired · Go live to resume"
                w == null || !w.canTimeShift -> "Time shift unavailable"
                p.paused -> "Paused${behind?.let { " · −${clock(it / 1000.0)}" } ?: ""}"
                behind != null && !edge -> "−${clock(behind / 1000.0)} behind live"
                else -> "At live edge"
            }, style = MaterialTheme.typography.bodySmall)
            if (edge || w?.goLivePosition() == null) Text("● LIVE", Modifier.semantics { contentDescription = if (edge) "Live, at live edge" else "Live stream" }, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            else TextButton(c::goLive, modifier = Modifier.controlFocus().probe("live:go-live")) { Text("Go live") }
        }
        if (p.canTimeShift() && w != null) Slider(
            value = position.toFloat().coerceIn(0f, w.defaultPositionMs.toFloat()),
            onValueChange = { c.seek((w.offsetMs + it.toLong()) / 1000.0) },
            valueRange = 0f..w.defaultPositionMs.toFloat().coerceAtLeast(1f), modifier = Modifier.fillMaxWidth().controlFocus().probe("live:seek").semantics { contentDescription = "Position in live buffer" })
        else LinearProgressIndicator(progress = { if (edge) 1f else 0f }, modifier = Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = "Live stream has no verified seekable buffer" })
    }
}
