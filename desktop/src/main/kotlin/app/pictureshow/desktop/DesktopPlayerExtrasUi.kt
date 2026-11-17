/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import kotlinx.coroutines.delay

/** PLAY-15 Ask: one named Skip button while a marked segment plays. */
@Composable
internal fun SkipSegmentButton(p: DesktopPlaybackState, c: DesktopController, modes: Map<SegmentType, SegmentMode>) {
    val action = desktopSegmentAction(p.extras, (p.positionSeconds * 1000).toLong(), modes)
    if (action !is DesktopSegmentAction.Ask || p.end != null) return
    Button(onClick = c::skipSegment, modifier = Modifier.padding(start = 8.dp).heightIn(min = 36.dp).controlFocus().probe("skip-segment")) {
        Text(desktopSkipLabel(action.segment.type), maxLines = 1)
    }
}

/** PLAY-30 / SET-17: the next episode counting down over the credits, with Play now and Cancel. */
@Composable
internal fun CreditsCard(p: DesktopPlaybackState, c: DesktopController) {
    val left = p.extras.creditsSecondsLeft ?: return
    val next = p.nextEpisode ?: return
    Row(Modifier.fillMaxWidth().probe("credits-card"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Up next · Playing in $left s", Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(listOfNotNull(episodeLabel(next).takeIf { next.type == "Episode" }, next.name).joinToString(" · "), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(onClick = c::nextEpisode, modifier = Modifier.heightIn(min = 40.dp).controlFocus().probe("credits-play-now")) { Text("Play now") }
        OutlinedButton(onClick = c::cancelCreditsCountdown, modifier = Modifier.heightIn(min = 40.dp).controlFocus().probe("credits-cancel")) { Text("Cancel") }
    }
}

/** PLAY-30: the question in the end card's place. Keep watching takes focus, so Enter answers it. */
@Composable
internal fun StillWatchingCard(end: DesktopEndCard, c: DesktopController) {
    val next = end.next ?: return
    LaunchedEffect(next.id) { withFrameNanos { }; DesktopUiProbe.requestFocus["still-watching-continue"]?.let { runCatching { it.requestFocus() } } }
    Column(Modifier.fillMaxWidth().padding(20.dp).probe("still-watching"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Are you still watching?", Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = MaterialTheme.typography.titleMedium)
        Text("Next: ${listOfNotNull(episodeLabel(next).takeIf { next.type == "Episode" }, next.name).joinToString(" · ")}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = c::continueWatching, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("still-watching-continue")) { Text("Keep watching") }
            OutlinedButton(onClick = c::stopPlayback, modifier = Modifier.controlFocus().heightIn(min = 44.dp).probe("still-watching-stop")) { Text("Stop") }
        }
    }
}

/** PLAY-25: the seven speeds, as a menu beside Audio and Subtitles. */
@Composable
internal fun SpeedMenu(p: DesktopPlaybackState, c: DesktopController, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, ceiling: Int) {
    if (p.isLiveChannel) return
    val choices = PlaybackSpeeds.choices
    TrackMenu("Speed", "speed", ExtrasGlyph.Speed, choices.mapIndexed { i, s -> i to PlaybackSpeeds.label(s) },
        choices.indexOf(p.extras.speed).coerceAtLeast(0), chipValue = PlaybackSpeeds.label(p.extras.speed), note = p.extras.speedMessage,
        open = open, onOpen = onOpen, onDismiss = onDismiss, ceiling = ceiling, chipWidth = 104.dp, refocusOnChoose = true) { c.setSpeed(choices[it]) }
}

/** PLAY-13: chapters, focus on the one playing; choosing seeks once and returns focus to this button. */
@Composable
internal fun ChaptersMenu(p: DesktopPlaybackState, c: DesktopController, open: Boolean, onOpen: () -> Unit, onDismiss: () -> Unit, ceiling: Int) {
    val chapters = p.extras.chapters
    if (chapters.isEmpty() || p.isLiveChannel) return
    TrackMenu("Chapters", "chapters", ExtrasGlyph.Chapters, chapters.mapIndexed { i, ch -> i to desktopChapterLabel(ch, i) },
        desktopCurrentChapter(chapters, (p.positionSeconds * 1000).toLong()), chipValue = "Chapters", note = null,
        open = open, onOpen = onOpen, onDismiss = onDismiss, ceiling = ceiling, chipWidth = 132.dp, refocusOnChoose = true, onChoose = c::seekChapter)
}

/** PLAY-11 and SUB-DL inside the Subtitles menu: timing for the selected subtitle, then Download subtitles. */
@Composable
internal fun SubtitleMenuFooter(p: DesktopPlaybackState, c: DesktopController, onDownload: () -> Unit) {
    val section = SubtitleTiming.section(p.subtitleIndex >= 0, desktopSubtitleBurnedIn(p.decision, p.subtitleIndex))
    if (section != SubtitleTiming.Section.Hidden) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (section == SubtitleTiming.Section.BurnedIn) {
                Text(SubtitleTiming.BURNED_IN, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val delay = p.extras.subtitleDelayMs
                Text(SubtitleTiming.label(delay), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.probe("subtitle-timing"), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { c.stepSubtitleDelay(-1) }, enabled = SubtitleTiming.canStep(delay, -1), modifier = Modifier.menuItemFocus().probe("subtitle-earlier")) { Text("Earlier") }
                    OutlinedButton(onClick = { c.stepSubtitleDelay(0) }, enabled = delay != 0, modifier = Modifier.menuItemFocus().probe("subtitle-reset")) { Text("Reset") }
                    OutlinedButton(onClick = { c.stepSubtitleDelay(1) }, enabled = SubtitleTiming.canStep(delay, 1), modifier = Modifier.menuItemFocus().probe("subtitle-later")) { Text("Later") }
                }
                Text(SubtitleTiming.HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (subtitleDownloadOffered(p)) {
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(text = { Text("Download subtitles…") }, onClick = onDownload, modifier = Modifier.fillMaxWidth().menuItemFocus().probe("subtitle-download"))
    }
}

/** SUB-DL is offered for server titles of these types (not Live TV). */
internal fun subtitleDownloadOffered(p: DesktopPlaybackState) =
    !p.isLiveChannel && !p.decision.isLive && p.item.type in setOf("Movie", "Episode", "Video", "MusicVideo")

/**
 * PLAY-14: the trickplay frame above the hover time. It hides 2 s after the pointer stops; a sheet that fails to load
 * only removes the picture, never the time label or the seek.
 */
@Composable
internal fun TrickplayPreview(p: DesktopPlaybackState, c: DesktopController, fraction: Float, x: Float, trackWidth: Int) {
    val info = p.extras.trickplay ?: return
    val positionMs = (fraction * p.durationSeconds * 1000).toLong()
    val tile = trickplayTile(info, positionMs) ?: return
    val sheets = remember(p.item.id, info) { mutableStateMapOf<Int, ImageBitmap?>() }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(positionMs / info.interval.coerceAtLeast(1)) { visible = true; delay(2000); visible = false }
    LaunchedEffect(tile.sheet) {
        if (tile.sheet !in sheets) sheets[tile.sheet] = c.trickplaySheet(tile.sheet)?.let { bytes ->
            runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }
    val image = sheets[tile.sheet] ?: return
    if (!visible) return
    val width: Dp = 192.dp
    val height: Dp = width * info.height / info.width.coerceAtLeast(1)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val w = with(density) { width.roundToPx() }
    val h = with(density) { height.roundToPx() }
    Surface(shape = RoundedCornerShape(6.dp), tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier
        .offset { IntOffset((x - w / 2f).toInt().coerceIn(0, (trackWidth - w).coerceAtLeast(0)), -h - with(density) { 30.dp.roundToPx() }) }
        .size(width, height).semantics { contentDescription = "Preview at ${clock(positionMs / 1000.0)}" }.probe("trickplay-preview")) {
        Canvas(Modifier.fillMaxSize()) {
            drawImage(image, srcOffset = IntOffset(tile.column * info.width, tile.row * info.height), srcSize = IntSize(info.width, info.height),
                dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        }
    }
}

private fun extrasGlyph(name: String, body: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        path(fill = null, stroke = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.White), strokeLineWidth = 2f,
            strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) { body() }
    }.build()

internal object ExtrasGlyph {
    /** Two forward chevrons. */
    val Speed by lazy { extrasGlyph("Speed") { moveTo(4f, 6f); lineTo(10f, 12f); lineTo(4f, 18f); moveTo(12f, 6f); lineTo(18f, 12f); lineTo(12f, 18f) } }
    /** A bulleted list. */
    val Chapters by lazy { extrasGlyph("Chapters") { moveTo(4f, 6f); lineTo(5f, 6f); moveTo(9f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(5f, 12f); moveTo(9f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(5f, 18f); moveTo(9f, 18f); lineTo(20f, 18f) } }
}
