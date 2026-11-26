/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.pictureshow.core.Account
import app.pictureshow.core.Chapter
import app.pictureshow.core.MediaItem
import app.pictureshow.core.MediaSegment
import app.pictureshow.core.SegmentMode
import app.pictureshow.core.SegmentType
import app.pictureshow.core.activeSegment
import app.pictureshow.core.chapterImageUrl
import app.pictureshow.core.imageUrl
import app.pictureshow.core.nextEpisodeCountdownProgress
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

internal fun segmentLabel(type: SegmentType): String = when (type) {
    SegmentType.Intro -> "intro"
    SegmentType.Outro -> "credits"
    SegmentType.Recap -> "recap"
    SegmentType.Preview -> "preview"
    SegmentType.Commercial -> "commercial"
    SegmentType.Unknown -> "segment"
}

@Composable
internal fun SegmentControl(
    segments: List<MediaSegment>,
    positionMs: Long,
    modes: Map<SegmentType, SegmentMode>,
    onSeek: (Long) -> Unit,
    onFocusRemoved: () -> Unit,
    showButton: Boolean = true,
    button: (@Composable (String, () -> Unit) -> Unit)? = null
) {
    val active = activeSegment(segments, positionMs, modes)
    val mode = active?.let { modes[it.type] ?: SegmentMode.Ask }
    val seek by rememberUpdatedState(onSeek)
    val restore by rememberUpdatedState(onFocusRemoved)
    var focused by remember { mutableStateOf(false) }
    val shown = active != null && mode == SegmentMode.Ask && showButton
    // Capture this before apply detaches the focused node and publishes Focused=false.
    val removedWithFocus = !shown && focused
    SideEffect {
        if (removedWithFocus) {
            focused = false
            restore()
        }
    }
    // Do not repeat an automatic seek while the decoder is still moving to the requested position.
    LaunchedEffect(active, mode) {
        if (active != null && mode == SegmentMode.Automatic) seek(active.endMs)
    }
    if (active != null && mode == SegmentMode.Ask && showButton) {
        if (button != null) {
            button("Skip ${segmentLabel(active.type)}") { seek(active.endMs) }
        } else {
            Button(
                scale = playerButtonScale(),
                onClick = { seek(active.endMs) },
                modifier = Modifier.testTag("skip:${active.type}").semantics { liveRegion = LiveRegionMode.Polite }
                    .onFocusChanged { focused = it.isFocused }
            ) { Text("Skip ${segmentLabel(active.type)}") }
        }
    }
}

@Composable
internal fun ChapterPanel(account: Account, itemId: String, chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit) {
    // Preserve original indexes: the image API uses the chapter's index in the server DTO.
    val current = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val entry = remember { PlayerPanelEntryFocus() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val host = LocalView.current
    val accessible = rememberAssistiveServiceInUse()
    var initialFocusReady by remember(accessible) { mutableStateOf(!accessible) }
    val initialCurrent = remember(accessible) { current }
    // Keep one arrival target while TalkBack acknowledges it; playback may cross a boundary.
    val focusIndex = if (initialFocusReady) current else initialCurrent
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    Column(
        Modifier.background(Color(0xF510131C)).padding(32.dp).then(with(entry) { Modifier.entry(focusIndex) })
    ) {
        Text(
            "Chapters",
            color = Color.White,
            style = MaterialTheme.typography.headlineSmall,
            // The window/pane announces Chapters; entry focus chooses the current row.
            modifier = Modifier.clearAndSetSemantics { hideFromAccessibility() }
        )
        LazyColumn(
            state = list,
            modifier = Modifier.semantics {
                isTraversalGroup = true
                traversalIndex = 0f
            },
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(chapters) { index, chapter ->
                Button(
                    scale = playerButtonScale(),
                    onClick = { onSeek(chapter.startMs) },
                    modifier = Modifier.testTag("chapter:$index").semantics {
                        selected = index == focusIndex
                        traversalIndex =
                            if (index == focusIndex) -1f else 0f
                    }.then(with(entry) { Modifier.row(index) }).then(
                        if (!initialFocusReady && index != initialCurrent) {
                            Modifier.clearAndSetSemantics { hideFromAccessibility() }
                        } else {
                            Modifier
                        }
                    )
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        chapter.imageTag?.let { tag ->
                            AsyncImage(
                                chapterImageUrl(account.server, itemId, index, tag),
                                contentDescription = null,
                                modifier = Modifier.width(128.dp).height(72.dp)
                            )
                        }
                        Text("${chapter.name.ifBlank { "Chapter ${index + 1}" }} · ${playerTime(chapter.startMs)}")
                    }
                }
            }
        }
    }
    LaunchedEffect(windowFocused, accessible) {
        if (windowFocused) {
            if (!initialFocusReady) {
                awaitInitialPlayerPanelFocus(host)
                initialFocusReady = true
                entry.allowNavigation()
            }
        }
    }
}

@Composable
internal fun NextEpisodeControl(
    account: Account,
    next: MediaItem,
    eligible: Boolean,
    ended: Boolean,
    paused: Boolean,
    onFocusRemoved: () -> Unit,
    onPlay: () -> Unit,
    countdownSeconds: Int = 10,
    onCancel: () -> Unit = {},
    visible: Boolean = true,
    compact: Boolean = false,
    // Time left in this episode when there are no credits to skip: the card shows it and waits for the end.
    remainingMs: Long? = null,
    autoPlay: Boolean = true,
    showCountdown: Boolean = autoPlay,
    countdownWindowMs: Long? = null,
    card: (@Composable (Long, () -> Unit, () -> Unit) -> Unit)? = null,
    /** PLAY-30: when the countdown would start the next episode by itself, ask first if the viewer has been away. */
    stillWatching: app.pictureshow.core.playback.StillWatching = app.pictureshow.core.playback.StillWatching.Off,
    stillWatchingPrompt: (@Composable (() -> Unit, () -> Unit) -> Unit)? = null
) {
    var cancelled by remember(next.id) { mutableStateOf(false) }
    var asking by remember(next.id) { mutableStateOf(false) }
    var played by remember(next.id) { mutableStateOf(false) }
    var remaining by remember(next.id) { mutableIntStateOf(countdownSeconds) }
    var focused by remember { mutableStateOf(false) }
    val play by rememberUpdatedState(onPlay)
    val restore by rememberUpdatedState(onFocusRemoved)
    val untilEnd = remainingMs != null
    LaunchedEffect(eligible) { if (!eligible) remaining = countdownSeconds }
    LaunchedEffect(eligible, ended, paused, cancelled, untilEnd, autoPlay, asking) {
        if (!autoPlay || (!eligible && !ended) || cancelled || played || asking) return@LaunchedEffect
        if (!ended && (paused || untilEnd)) return@LaunchedEffect
        while (!ended && remaining > 0) {
            delay(1_000)
            remaining--
        }
        if (!played) {
            if (ViewerInput.stillWatchingDue(stillWatching)) {
                asking = true
            } else {
                played = true
                ViewerInput.automaticallyAdvanced()
                play()
            }
        }
    }
    val keepWatching = {
        if (!played) {
            played = true
            play()
        }
    }
    val stopWatching = {
        val hadFocus = focused
        cancelled = true
        onCancel()
        if (hadFocus) {
            focused = false
            restore()
        }
    }
    if (asking && !cancelled && !played) {
        if (stillWatchingPrompt != null) {
            stillWatchingPrompt(keepWatching, stopWatching)
        } else {
            TvStillWatchingPrompt(next.name, keepWatching, stopWatching, Modifier.onFocusChanged { focused = it.hasFocus })
        }
        return
    }
    val shown = visible && (eligible || ended) && !cancelled && !played
    val removedWithFocus = !shown && focused
    SideEffect {
        if (removedWithFocus) {
            focused = false
            restore()
        }
    }
    if (shown) {
        if (card != null) {
            val seconds = remainingMs?.let { ((it + 999) / 1_000).coerceAtLeast(0) } ?: remaining.toLong()
            card(seconds, {
                if (!played) {
                    played = true
                    play()
                }
            }, {
                cancelled = true
                onCancel()
            })
        } else {
            val shape = RoundedCornerShape(16.dp)
            val initialWindowMs = remember(next.id, eligible) { remainingMs?.coerceAtLeast(1_000L) }
            val seconds = remainingMs?.let { ((it + 999) / 1_000).coerceAtLeast(0) } ?: remaining
            val totalWindowMs = if (remainingMs != null) {
                maxOf(countdownWindowMs ?: initialWindowMs ?: 10_000L, remainingMs, 1_000L)
            } else {
                countdownSeconds.coerceAtLeast(1) * 1_000L
            }
            val currentRemainingMs = remainingMs ?: (remaining * 1_000L)
            val ringProgress = nextEpisodeCountdownProgress(currentRemainingMs, totalWindowMs)
            Column(
                Modifier
                    .width(420.dp)
                    .background(Color(0xF0121622), shape)
                    .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                    .padding(16.dp)
                    .onFocusChanged { focused = it.hasFocus }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val thumbWidth = if (compact) 112.dp else 148.dp
                    val thumbHeight = if (compact) 63.dp else 83.dp
                    val thumbUrl = imageUrl(account.server, next, 320, wide = true)
                    if (thumbUrl != null || showCountdown) {
                        Box(
                            Modifier
                                .width(thumbWidth)
                                .height(thumbHeight)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1D2333)),
                            contentAlignment = Alignment.Center
                        ) {
                            thumbUrl?.let { url ->
                                AsyncImage(
                                    url,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            }
                            if (showCountdown) {
                                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
                                Box(
                                    Modifier.size(46.dp).testTag("next:countdown-ring"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Canvas(Modifier.fillMaxSize().clearAndSetSemantics {}) {
                                        val stroke = 3.dp.toPx()
                                        drawCircle(Color(0xCC0D111C))
                                        drawArc(
                                            color = Color.White.copy(alpha = 0.25f),
                                            startAngle = 0f,
                                            sweepAngle = 360f,
                                            useCenter = false,
                                            style = Stroke(width = stroke)
                                        )
                                        drawArc(
                                            color = Color(0xFFB8C4FF),
                                            startAngle = -90f,
                                            sweepAngle = 360f * ringProgress,
                                            useCenter = false,
                                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                                        )
                                    }
                                    Text(
                                        "${seconds}s",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clearAndSetSemantics {}
                                    )
                                }
                            }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (showCountdown) "Next episode · $seconds s left" else "Next episode",
                            modifier = if (showCountdown) {
                                Modifier.semantics { heading() }
                            } else {
                                Modifier.clearAndSetSemantics {
                                    heading()
                                    liveRegion = LiveRegionMode.Polite
                                    contentDescription = "Next episode, ${catalogAnnouncement(next)}"
                                }
                            },
                            color = Color(0xFFD0D5E2),
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (next.parentIndexNumber != null && next.indexNumber != null) {
                            Text(
                                "S${next.parentIndexNumber} · E${next.indexNumber}",
                                color = Color(0xFFB0B8CC),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clearAndSetSemantics {}
                            )
                        }
                        Text(
                            next.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        scale = playerButtonScale(),
                        onClick = {
                            if (!played) {
                                played = true
                                play()
                            }
                        },
                        modifier = Modifier.testTag("next:play").semantics {
                            contentDescription = "Play next episode, ${catalogAnnouncement(next)}"
                        }
                    ) { Text("Play now", modifier = Modifier.clearAndSetSemantics {}) }
                    Button(
                        scale = playerButtonScale(),
                        onClick = {
                            val hadFocus = focused
                            cancelled = true
                            onCancel()
                            // The caller may remove this whole card, so its SideEffect would not run.
                            if (hadFocus) {
                                focused = false
                                restore()
                            }
                        },
                        modifier = Modifier.testTag("next:cancel")
                    ) { Text("Cancel next episode") }
                }
            }
        }
    }
}
