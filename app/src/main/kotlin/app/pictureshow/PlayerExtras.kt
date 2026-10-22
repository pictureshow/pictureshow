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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
            modifier = Modifier.testTag("skip:${active.type}").onFocusChanged { focused = it.isFocused }
        ) { Text("Skip ${segmentLabel(active.type)}") }
        DisposableEffect(active) { onDispose { if (focused) restore() } }
    }
}

@Composable
internal fun ChapterPanel(account: Account, itemId: String, chapters: List<Chapter>, positionMs: Long, onSeek: (Long) -> Unit) {
    // Preserve original indexes: the image API uses the chapter's index in the server DTO.
    val current = chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)
    val initial = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = current)
    Column(Modifier.background(Color(0xF510131C)).padding(32.dp)) {
        Text("Chapters", color = Color.White, style = MaterialTheme.typography.headlineSmall)
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(chapters) { index, chapter ->
                Button(
                    onClick = { onSeek(chapter.startMs) },
                    modifier = Modifier.testTag("chapter:$index").then(if (index == current) Modifier.focusRequester(initial) else Modifier)
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
    LaunchedEffect(Unit) {
        if (chapters.isNotEmpty()) {
            list.scrollToItem(current)
            androidx.compose.runtime.withFrameNanos { }
            initial.requestFocus()
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
    compact: Boolean = false
) {
    var cancelled by remember(next.id) { mutableStateOf(false) }
    var played by remember(next.id) { mutableStateOf(false) }
    var remaining by remember(next.id) { mutableIntStateOf(countdownSeconds) }
    var focused by remember { mutableStateOf(false) }
    val play by rememberUpdatedState(onPlay)
    val restore by rememberUpdatedState(onFocusRemoved)
    LaunchedEffect(eligible) { if (!eligible) remaining = countdownSeconds }
    LaunchedEffect(eligible, ended, paused, cancelled) {
        if ((!eligible && !ended) || cancelled || played) return@LaunchedEffect
        if (!ended && paused) return@LaunchedEffect
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
            Text("Next episode · $remaining", color = Color.White, style = MaterialTheme.typography.titleMedium)
            imageUrl(account.server, next, 320, wide = true)?.takeUnless { compact }?.let { url ->
                AsyncImage(url, contentDescription = null, modifier = Modifier.width(240.dp).height(100.dp))
            }
            Text(next.name, color = Color.White)
            Button(onClick = {
                if (!played) {
                    played = true
                    play()
                }
            }, modifier = Modifier.testTag("next:play")) { Text("Play now") }
            Button(onClick = {
                cancelled = true
                onCancel()
            }, modifier = Modifier.testTag("next:cancel")) { Text("Cancel next episode") }
        }
        DisposableEffect(Unit) { onDispose { if (focused) restore() } }
    }
}
