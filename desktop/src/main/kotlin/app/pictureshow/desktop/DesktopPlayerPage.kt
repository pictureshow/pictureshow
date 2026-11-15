/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)


package app.pictureshow.desktop

import androidx.compose.foundation.*
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import app.pictureshow.ui.*
import java.util.concurrent.ConcurrentHashMap

@Composable
fun PlayerPage(state: DesktopState, c: DesktopController, fullscreen: () -> Unit) {
    val p = state.playback ?: return
    var playerBottom by remember(p.decision) { mutableStateOf(0.0) }
    var videoTop by remember(p.decision) { mutableStateOf(0.0) }
    var videoBottom by remember(p.decision) { mutableStateOf(0.0) }
    LaunchedEffect(p.decision, playerBottom, videoTop, videoBottom) {
        if (playerBottom > 0.0 && videoBottom > videoTop) c.videoViewport(videoTop / playerBottom, (playerBottom - videoBottom) / playerBottom)
    }
    Column(Modifier.fillMaxSize().onGloballyPositioned { playerBottom = (it.positionInWindow().y + it.size.height).toDouble() }) {
        Spacer(Modifier.weight(1f).onGloballyPositioned {
            videoTop = it.positionInWindow().y.toDouble()
            videoBottom = videoTop + it.size.height
        })
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
            Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(if (p.notice != null) 12.dp else 10.dp)) {
                p.notice?.let { notice ->
                    Column(
                        Modifier.widthIn(max = 720.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(notice.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text("${notice.reason} ${notice.fix}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(p.item.name,style=MaterialTheme.typography.titleMedium)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(clock(p.positionSeconds),Modifier.width(72.dp),textAlign=TextAlign.End,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                    Slider(value=p.positionSeconds.toFloat().coerceIn(0f,p.durationSeconds.coerceAtLeast(1.0).toFloat()),onValueChange={c.seek(it.toDouble())},valueRange=0f..p.durationSeconds.coerceAtLeast(1.0).toFloat(),modifier=Modifier.controlFocus(RoundedCornerShape(12.dp)).weight(1f).probe("seek").semantics { contentDescription="Playback position" })
                    Text(clock(p.durationSeconds),Modifier.width(72.dp),textAlign=TextAlign.Start,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                }

                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopTooltip("Back 10 seconds", above = true) { IconButton(onClick={c.seek((p.positionSeconds-10).coerceAtLeast(0.0))},modifier = Modifier.controlFocus().probe("seek-back")) { Icon(DesktopGlyph.TransportBack,contentDescription="Back 10 seconds") } }
                        DesktopTooltip(if (p.paused) "Play" else "Pause", above = true) { FilledIconButton(onClick=c::playerToggle,modifier = Modifier.controlFocus().probe("pause")) { Icon(if (p.paused) DesktopGlyph.Play else DesktopGlyph.Pause, contentDescription = if (p.paused) "Play" else "Pause") } }
                        DesktopTooltip("Forward 10 seconds", above = true) { IconButton(onClick={c.seek(p.positionSeconds+10)},modifier = Modifier.controlFocus().probe("seek-forward")) { Icon(DesktopGlyph.TransportForward,contentDescription="Forward 10 seconds") } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("Audio",p.audioTracks.zip(trackLabels(p.audioTracks)).map{(track,label)->track.index to label},p.audioIndex,"audio",buttonValue = p.audioTracks.firstOrNull { it.index == p.audioIndex }?.let(::shortTrackLabel) ?: "Off", buttonWidth = 156.dp, fixedWidth = true, onChoose = c::selectAudio, icon = DesktopGlyph.Audio)
                        Choice("Subtitles",listOf(-1 to "Off")+p.subtitleTracks.zip(trackLabels(p.subtitleTracks)).map{(track,label)->track.index to label},p.subtitleIndex,"subtitles",buttonValue = if (p.subtitleIndex == -1) "Off" else p.subtitleTracks.firstOrNull { it.index == p.subtitleIndex }?.let(::shortTrackLabel) ?: "Off", buttonWidth = 156.dp, fixedWidth = true, onChoose = c::selectSubtitle, icon = DesktopGlyph.Subtitles)
                        DesktopTooltip("Fullscreen", above = true) { IconButton(onClick=fullscreen,modifier = Modifier.controlFocus().probe("player-fullscreen")) { Icon(DesktopGlyph.Fullscreen,contentDescription="Fullscreen") } }
                        if(p.nextEpisode!=null) OutlinedButton(onClick=c::nextEpisode,modifier = Modifier.controlFocus().heightIn(min=40.dp).probe("next-episode")) { Text("Next episode") }
                        DesktopTooltip("Stop playback", above = true) { IconButton(onClick=c::stopPlayback,modifier = Modifier.controlFocus().probe("stop")) { Icon(DesktopGlyph.Stop,contentDescription="Stop playback") } }
                    }

                }
                if(!p.ready) Text("Opening video…",modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
            }
        }
    }
}
internal fun clock(seconds:Double):String { val s=seconds.toLong().coerceAtLeast(0); return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60) }
