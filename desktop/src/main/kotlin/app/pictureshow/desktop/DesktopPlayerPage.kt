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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

/** PLAY-29 on desktop: what a key press in the player means. Pure, so the rules are unit-tested. */
internal enum class PlayerKey { Toggle, Back, Forward, Fullscreen, Mute, VolumeUp, VolumeDown, Help, Info }

/**
 * Space/K play or pause, Left/Right and J/L seek 10 s, Up/Down change volume, F full screen, M mute, ? the
 * shortcut sheet. Nothing while Ctrl, Alt or Cmd is held (those belong to the system and the menu bar). Space and
 * the arrows act only when no control has focus: a focused button keeps Space, a focused slider keeps its arrows.
 */
internal fun playerKeyAction(key: Key, codePoint: Int, shift: Boolean, ctrl: Boolean, alt: Boolean, meta: Boolean, controlFocused: Boolean): PlayerKey? {
    if (ctrl || alt || meta) return null
    if (codePoint == '?'.code || (key == Key.Slash && shift)) return PlayerKey.Help
    fun surface(action: PlayerKey) = action.takeUnless { controlFocused }
    return when (key) {
        Key.Spacebar -> surface(PlayerKey.Toggle)
        Key.DirectionLeft -> surface(PlayerKey.Back)
        Key.DirectionRight -> surface(PlayerKey.Forward)
        Key.DirectionUp -> surface(PlayerKey.VolumeUp)
        Key.DirectionDown -> surface(PlayerKey.VolumeDown)
        Key.K -> PlayerKey.Toggle
        Key.J -> PlayerKey.Back
        Key.L -> PlayerKey.Forward
        Key.F -> PlayerKey.Fullscreen
        Key.M -> PlayerKey.Mute
        Key.I -> PlayerKey.Info
        else -> null
    }
}

/** The rows the Keyboard shortcuts sheet lists for the player (area 1's sheet reads them, so the two never drift). */
internal val PLAYER_SHORTCUT_ROWS: List<Pair<String, String>> = listOf(
    "Space / K" to "Play or pause",
    "← → / J L" to "Back or forward 10 seconds",
    "↑ ↓" to "Volume up or down",
    "M" to "Mute or unmute",
    "I" to "Playback info",
    "F" to "Full screen",
    "Esc" to "Close a menu, leave full screen, then leave the player",
    "?" to "Keyboard shortcuts"
)

/**
 * The window's key handler calls [handle] on the Player route before its own Escape rules. While a player page is
 * composed it owns the player keys; otherwise nothing is consumed.
 */
internal object PlayerKeyboard {
    @Volatile internal var handler: ((KeyEvent, () -> Unit) -> Boolean)? = null
    fun handle(e: KeyEvent, openHelp: () -> Unit): Boolean = e.type == KeyEventType.KeyDown && (handler?.invoke(e, openHelp) ?: false)
}

@Composable
fun PlayerPage(state: DesktopState, c: DesktopController, fullscreen: () -> Unit) {
    val p = state.playback ?: return
    var playerBottom by remember(p.decision) { mutableStateOf(0.0) }
    var videoTop by remember(p.decision) { mutableStateOf(0.0) }
    var videoBottom by remember(p.decision) { mutableStateOf(0.0) }
    LaunchedEffect(p.decision, playerBottom, videoTop, videoBottom) {
        if (playerBottom > 0.0 && videoBottom > videoTop) c.videoViewport(videoTop / playerBottom, (playerBottom - videoBottom) / playerBottom)
    }
    val surfaceFocus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<String?>(null) }
    var infoOpener by remember { mutableStateOf("playback-info") }
    var restoreInfoFocus by remember { mutableStateOf(false) }
    fun closeInfo() { menu = null; restoreInfoFocus = true }
    LaunchedEffect(menu, restoreInfoFocus) {
        if (menu == null && restoreInfoFocus) {
            withFrameNanos { }
            runCatching { if (infoOpener == "surface") surfaceFocus.requestFocus() else DesktopUiProbe.requestFocus[infoOpener]?.requestFocus() }
            restoreInfoFocus = false
        }
    }
    var seekTop by remember { mutableStateOf(0) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashSerial by remember { mutableStateOf(0) }
    LaunchedEffect(flashSerial) { if (flash != null) { delay(1500); flash = null } }
    // PLAY-29: the playback surface holds focus when a viewing opens, so every key works before anything is clicked.
    LaunchedEffect(p.decision) { withFrameNanos { }; runCatching { surfaceFocus.requestFocus() } }
    LaunchedEffect(p.end != null) { if (p.end != null && menu != "info") menu = null }
    LaunchedEffect(menu == "info") { c.playbackInformationOpen(menu == "info") }
    DisposableEffect(c) { onDispose { c.playbackInformationOpen(false) } }
    val current by rememberUpdatedState(p)
    val currentMenu by rememberUpdatedState(menu)
    val currentFullscreen by rememberUpdatedState(fullscreen)
    DisposableEffect(c) {
        PlayerKeyboard.handler = handler@{ e, openHelp ->
            val open = currentMenu
            if (e.key == Key.Escape) {
                if (open == null) return@handler false
                if (open == "info") closeInfo() else { menu = null; DesktopUiProbe.requestFocus[open]?.let { runCatching { it.requestFocus() } } }
                return@handler true
            }
            if (open != null) return@handler false
            val controlFocused = DesktopUiProbe.focus.values.any { it }
            val action = playerKeyAction(e.key, e.utf16CodePoint, e.isShiftPressed, e.isCtrlPressed, e.isAltPressed, e.isMetaPressed, controlFocused)
                ?: return@handler false
            // After the end only the window-level keys apply; the card's buttons own the rest.
            if (current.end != null && action != PlayerKey.Fullscreen && action != PlayerKey.Help) return@handler false
            when (action) {
                PlayerKey.Toggle -> c.playerToggle()
                PlayerKey.Back -> c.seekBy(-10.0)
                PlayerKey.Forward -> c.seekBy(10.0)
                PlayerKey.Fullscreen -> currentFullscreen()
                PlayerKey.Help -> openHelp()
                PlayerKey.Info -> {
                    if (!current.ready) return@handler true
                    infoOpener = DesktopUiProbe.focus.entries.firstOrNull { it.value }?.key ?: "surface"
                    menu = "info"
                }
                PlayerKey.Mute -> { c.toggleMute(); flash = if (c.state.value.playback?.muted == true) "Muted" else "Volume ${c.state.value.settings.volume}%"; flashSerial++ }
                PlayerKey.VolumeUp, PlayerKey.VolumeDown -> {
                    c.changeVolume(if (action == PlayerKey.VolumeUp) 5 else -5)
                    flash = if (c.state.value.playback?.muted == true) "Muted" else "Volume ${c.state.value.settings.volume}%"; flashSerial++
                }
            }
            true
        }
        onDispose { PlayerKeyboard.handler = null }
    }
    if (menu == "info") DesktopPlaybackInfoDialog(playbackInfoSections(p, state.settings.bitrateMbps), ::closeInfo)
    Column(Modifier.fillMaxSize().onGloballyPositioned { playerBottom = (it.positionInWindow().y + it.size.height).toDouble() }) {
        Box(Modifier.weight(1f).fillMaxWidth()
            .onGloballyPositioned { videoTop = it.positionInWindow().y.toDouble(); videoBottom = videoTop + it.size.height }
            .focusRequester(surfaceFocus).focusable()
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { if (awaitPointerEvent().type == PointerEventType.Press) runCatching { surfaceFocus.requestFocus() } } } }
            .semantics { contentDescription = "Video: ${p.item.name}" })
        Surface(color=MaterialTheme.colorScheme.surface.copy(alpha=.96f)) {
            val end = p.end
            if (end != null) EndCard(p, end, c) else Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(if (p.notice != null) 12.dp else 10.dp)) {
                p.notice?.takeIf { menu != "info" }?.let { notice ->
                    Column(
                        Modifier.widthIn(max = 720.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(notice.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                        Text("${notice.reason} ${notice.fix}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (p.isLiveChannel) livePlayerProgram(p.liveChannel, System.currentTimeMillis())?.name ?: p.item.name else p.item.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val video = p.decision.mediaSource.mediaStreams.firstOrNull { it.type == MediaStreamType.Video }
                    val sourceRange = PlaybackFormatInformation.range(video?.videoRangeType, video?.dvProfile)
                    DesktopTooltip("$sourceRange source · ${PlaybackFormatInformation.display(p.received.transfer, p.received.output)} · Playback info", above = true) {
                    AssistChip(onClick = { infoOpener = "format-badge"; menu = "info" }, border = null,
                        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        label = { Text(if (sourceRange == "Not reported") PlaybackInformation.codec(video?.codec) else sourceRange, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.padding(horizontal = 8.dp).widthIn(max = 240.dp).controlFocus().probe("format-badge").semantics { contentDescription = "Source format: $sourceRange. Open Playback info" })
                    }
                    flash?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                }
                if (p.isLiveChannel) {
                    p.liveChannel?.let { channel -> Text(listOfNotNull(channel.number, channel.name, livePlayerProgram(channel, System.currentTimeMillis())?.let { liveRange(it.startDate, it.endDate) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall) }
                    DesktopLiveTimeline(p, c)
                } else Row(Modifier.onGloballyPositioned { seekTop = it.positionInWindow().y.toInt() }, verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(clock(p.positionSeconds),Modifier.width(72.dp),textAlign=TextAlign.End,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                    SeekBar(p, c, Modifier.weight(1f))
                    Text(clock(p.durationSeconds),Modifier.width(72.dp),textAlign=TextAlign.Start,style=MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"))
                }

                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DesktopTooltip("Back 10 seconds", above = true) { IconButton(onClick={c.seekBy(-10.0)},enabled = !p.isLiveChannel || p.canTimeShift(),modifier = Modifier.controlFocus().probe("seek-back")) { Icon(DesktopGlyph.TransportBack,contentDescription="Back 10 seconds") } }
                        DesktopTooltip(if (p.paused) "Play" else "Pause", above = true) { FilledIconButton(onClick=c::playerToggle,enabled = !p.isLiveChannel || p.liveWindow?.canTimeShift == true,modifier = Modifier.controlFocus().probe("pause")) { Icon(if (p.paused) DesktopGlyph.Play else DesktopGlyph.Pause, contentDescription = if (p.paused) "Play" else "Pause") } }
                        DesktopTooltip("Forward 10 seconds", above = true) { IconButton(onClick={c.seekBy(10.0)},enabled = !p.isLiveChannel || p.canTimeShift(),modifier = Modifier.controlFocus().probe("seek-forward")) { Icon(DesktopGlyph.TransportForward,contentDescription="Forward 10 seconds") } }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrackMenu("Audio", "audio", DesktopGlyph.Audio,
                            p.audioTracks.zip(trackLabels(p.audioTracks)).map { (track, label) -> track.index to label }, p.audioIndex,
                            chipValue = p.audioTracks.firstOrNull { it.index == p.audioIndex }?.let(::shortTrackLabel) ?: "Off", note = null,
                            open = menu == "audio", onOpen = { menu = "audio" }, onDismiss = { menu = null }, ceiling = seekTop, onChoose = c::selectAudio)
                        TrackMenu("Subtitles", "subtitles", DesktopGlyph.Subtitles,
                            listOf(-1 to "Off") + p.subtitleTracks.zip(trackLabels(p.subtitleTracks)).map { (track, label) -> track.index to label }, p.subtitleIndex,
                            chipValue = if (p.subtitleIndex == -1) "Off" else p.subtitleTracks.firstOrNull { it.index == p.subtitleIndex }?.let(::shortTrackLabel) ?: "Off",
                            note = if (p.subtitleTracks.isEmpty()) "No subtitles in this file" else null,
                            open = menu == "subtitles", onOpen = { menu = "subtitles" }, onDismiss = { menu = null }, ceiling = seekTop, onChoose = c::selectSubtitle)
                        OutlinedButton(onClick = { infoOpener = "playback-info"; menu = "info" }, enabled = p.ready,
                            shape = RoundedCornerShape(50), modifier = Modifier.heightIn(min = 40.dp).controlFocus().probe("playback-info")) { Text("Playback info") }
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

/** PLAY-17 after a genuine end: Up next with Play now and a countdown, or Finished with Back to title and Replay. */
@Composable
private fun EndCard(p: DesktopPlaybackState, end: DesktopEndCard, c: DesktopController) {
    val next = end.next
    val primary = if (next != null) "end-play-now" else "end-back"
    LaunchedEffect(next?.id) { withFrameNanos { }; DesktopUiProbe.requestFocus[primary]?.let { runCatching { it.requestFocus() } } }
    Column(Modifier.fillMaxWidth().padding(20.dp).probe("end-card"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (next != null) {
            Text("Up next", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(episodeLabel(next).takeIf { next.type == "Episode" }, next.name).joinToString(" · "),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(end.secondsLeft?.let { "Playing in $it s" } ?: "${p.item.name} finished.", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = c::nextEpisode, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("end-play-now")) { Icon(DesktopGlyph.Play, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Play now") }
                if (end.secondsLeft != null) OutlinedButton(onClick = c::cancelEndCountdown, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("end-cancel")) { Text("Cancel") }
                else OutlinedButton(onClick = c::stopPlayback, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("end-back")) { Text("Back to title") }
            }
        } else {
            Text("Finished", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = c::stopPlayback, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("end-back")) { Text("Back to title") }
                OutlinedButton(onClick = c::replay, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("end-replay")) { Icon(DesktopGlyph.TransportBack, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Replay") }
            }
        }
    }
}

/** The seek bar: a thin thumb that grows on hover or drag, with the time under the pointer shown above the track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(p: DesktopPlaybackState, c: DesktopController, modifier: Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val dragged by interaction.collectIsDraggedAsState()
    val pressed by interaction.collectIsPressedAsState()
    var hoverX by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableStateOf(0) }
    var labelWidth by remember { mutableStateOf(0) }
    val large = hoverX != null || dragged || pressed
    val thumb = if (large) DpSize(12.dp, 28.dp) else DpSize(4.dp, 20.dp)
    val duration = p.durationSeconds.coerceAtLeast(1.0)
    val density = LocalDensity.current
    Box(modifier.onSizeChanged { width = it.width }.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Exit -> hoverX = null
                    PointerEventType.Enter, PointerEventType.Move -> hoverX = event.changes.firstOrNull()?.position?.x
                }
            }
        }
    }) {
        Slider(value = p.positionSeconds.toFloat().coerceIn(0f, duration.toFloat()), onValueChange = { c.seek(it.toDouble()) },
            valueRange = 0f..duration.toFloat(), interactionSource = interaction,
            thumb = { SliderDefaults.Thumb(interaction, thumbSize = thumb) },
            modifier = Modifier.fillMaxWidth().controlFocus(RoundedCornerShape(12.dp)).probe("seek").semantics { contentDescription = "Playback position" })
        val x = hoverX
        if (x != null && width > 0 && p.durationSeconds > 0) {
            val inset = with(density) { (thumb.width / 2).toPx() }
            val fraction = ((x - inset) / (width - 2 * inset).coerceAtLeast(1f)).coerceIn(0f, 1f)
            Surface(color = MaterialTheme.colorScheme.inverseSurface, shape = RoundedCornerShape(4.dp),
                modifier = Modifier.onSizeChanged { labelWidth = it.width }
                    .offset { IntOffset((x - labelWidth / 2f).roundToInt().coerceIn(0, (width - labelWidth).coerceAtLeast(0)), -with(density) { 22.dp.roundToPx() }) }) {
                Text(clock(fraction * p.durationSeconds), Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}

/**
 * Audio and Subtitles: the menu opens upward from its chip and stops above the seek row ([ceiling], window px),
 * so the timeline and times stay visible (D-15). Escape closes it back to the chip (PLAY-05).
 */
@Composable
private fun TrackMenu(label: String, tag: String, icon: androidx.compose.ui.graphics.vector.ImageVector, items: List<Pair<Int, String>>, selected: Int,
                      chipValue: String, note: String?, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, ceiling: Int, onChoose: (Int) -> Unit) {
    val density = LocalDensity.current
    Box {
        OutlinedButton(onClick = { if (open) onDismiss() else onOpen() }, contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.controlFocus().width(156.dp).probe(tag).semantics { contentDescription = "$label: $chipValue${note?.let { ". $it" } ?: ""}" }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(chipValue, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = MaterialTheme.typography.labelLarge.fontWeight)
                Spacer(Modifier.width(8.dp)); Icon(DesktopGlyph.ChevronDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        if (open) {
            val gap = with(density) { 8.dp.roundToPx() }
            val position = remember(ceiling, gap) { AboveSeekRow(ceiling, gap) }
            LaunchedEffect(Unit) { withFrameNanos { }; DesktopUiProbe.requestFocus["$tag:$selected"]?.let { runCatching { it.requestFocus() } } }
            Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
                val maxHeight = with(density) { (ceiling - 2 * gap).coerceAtLeast(160).toDp() }
                Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 3.dp, shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(min = 240.dp, max = 440.dp).heightIn(max = maxHeight)) {
                    Column(Modifier.width(IntrinsicSize.Max).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                        Text(label, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        items.forEach { (key, text) ->
                            DropdownMenuItem(text = { Text(text) }, onClick = { onDismiss(); onChoose(key) }, leadingIcon = { RadioButton(selected == key, null) },
                                modifier = Modifier.fillMaxWidth().controlFocus(RoundedCornerShape(4.dp)).probe("$tag:$key"))
                        }
                        note?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

/** Right-aligns the menu with its chip and puts its bottom [gap] above the seek row, inside the window. */
private class AboveSeekRow(private val ceiling: Int, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val bottom = (if (ceiling in 1 until anchorBounds.top) ceiling else anchorBounds.top) - gap
        return IntOffset(x, (bottom - popupContentSize.height).coerceAtLeast(0))
    }
}
internal fun clock(seconds:Double):String { val s=seconds.toLong().coerceAtLeast(0); return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60) }
