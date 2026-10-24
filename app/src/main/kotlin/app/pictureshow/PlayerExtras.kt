/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
    showButton: Boolean = true
) {
    val active = activeSegment(segments, positionMs, modes)
    val mode = active?.let { modes[it.type] ?: SegmentMode.Ask }
    val seek by rememberUpdatedState(onSeek)
    val restore by rememberUpdatedState(onFocusRemoved)
    // Do not repeat an automatic seek while the decoder is still moving to the requested position.
    LaunchedEffect(active, mode) {
        if (active != null && mode == SegmentMode.Automatic) seek(active.endMs)
    }
    if (active != null && mode == SegmentMode.Ask && showButton) {
        var focused by remember(active) { mutableStateOf(false) }
        Button(
            onClick = { seek(active.endMs) },
            modifier = Modifier.testTag("skip:${active.type}").semantics { liveRegion = LiveRegionMode.Polite }
                .onFocusChanged { focused = it.isFocused }
        ) { Text("Skip ${segmentLabel(active.type)}") }
        DisposableEffect(active) { onDispose { if (focused) restore() } }
    }
}

@Composable
internal fun ChapterPanel(account: Account, itemId: String, chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit) {
    // Preserve original indexes: the image API uses the chapter's index in the server DTO.
    val current = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val initial = remember { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val host = LocalView.current
    val accessible = rememberAccessibilityEnabled()
    var initialFocusReady by remember(accessible) { mutableStateOf(!accessible) }
    val initialCurrent = remember(accessible) { current }
    // Keep one arrival target while TalkBack acknowledges it; playback may cross a boundary.
    val focusIndex = if (initialFocusReady) current else initialCurrent
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    Column(
        Modifier.background(Color(0xF510131C)).padding(32.dp)
            .focusProperties { onEnter = { if (chapters.isNotEmpty()) initial.requestFocus() } }
            .focusGroup()
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
                    onClick = { onSeek(chapter.startMs) },
                    modifier = Modifier.testTag("chapter:$index").semantics {
                        selected = index == focusIndex
                        traversalIndex =
                            if (index == focusIndex) -1f else 0f
                    }.then(
                        if (index ==
                            focusIndex
                        ) {
                            Modifier.focusRequester(initial)
                        } else {
                            Modifier
                        }
                    ).then(
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
            if (chapters.isNotEmpty()) {
                list.scrollToItem(focusIndex)
                androidx.compose.runtime.withFrameNanos { }
                initial.requestFocus()
            }
            if (!initialFocusReady) {
                awaitInitialPlayerPanelFocus(host)
                initialFocusReady = true
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
    autoPlay: Boolean = true
) {
    var cancelled by remember(next.id) { mutableStateOf(false) }
    var played by remember(next.id) { mutableStateOf(false) }
    var remaining by remember(next.id) { mutableIntStateOf(countdownSeconds) }
    var focused by remember { mutableStateOf(false) }
    val play by rememberUpdatedState(onPlay)
    val restore by rememberUpdatedState(onFocusRemoved)
    val untilEnd = remainingMs != null
    LaunchedEffect(eligible) { if (!eligible) remaining = countdownSeconds }
    LaunchedEffect(eligible, ended, paused, cancelled, untilEnd, autoPlay) {
        if (!autoPlay || (!eligible && !ended) || cancelled || played) return@LaunchedEffect
        if (!ended && (paused || untilEnd)) return@LaunchedEffect
        while (!ended && remaining > 0) {
            delay(1_000)
            remaining--
        }
        if (!played) {
            played = true
            play()
        }
    }
    if (visible && (eligible || ended) && !cancelled && !played) {
        Column(
            Modifier.width(380.dp).background(Color(0xF510131C)).padding(20.dp)
                .onFocusChanged { focused = it.hasFocus }.focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val seconds = remainingMs?.let { ((it + 999) / 1_000).coerceAtLeast(0) } ?: remaining
            Text(
                if (autoPlay) "Next episode · $seconds" else "Next episode",
                modifier = if (autoPlay) {
                    Modifier.semantics { heading() }
                } else {
                    Modifier.clearAndSetSemantics {
                        heading()
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Next episode, ${catalogAnnouncement(next)}"
                    }
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            imageUrl(account.server, next, 320, wide = true)?.takeUnless { compact }?.let { url ->
                AsyncImage(url, contentDescription = null, modifier = Modifier.width(240.dp).height(100.dp))
            }
            Text(next.name, color = Color.White)
            Button(
                onClick = {
                    if (!played) {
                        played = true
                        play()
                    }
                },
                modifier = Modifier.testTag("next:play").semantics {
                    contentDescription = "Play next episode, ${catalogAnnouncement(next)}"
                }
            ) { Text("Play now") }
            Button(onClick = {
                cancelled = true
                onCancel()
            }, modifier = Modifier.testTag("next:cancel")) { Text("Cancel next episode") }
        }
        DisposableEffect(Unit) { onDispose { if (focused) restore() } }
    }
}
