/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.chapterImageUrl
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlaybackInformation
import app.pictureshow.core.playback.PlaybackSpeeds
import app.pictureshow.core.playback.SubtitleDeliveryMethod
import coil3.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchPlayerSheetContent(
    sheet: TouchPlayerSheet,
    host: PlaybackHostState,
    account: Account? = null,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("touch:sheet:$sheet")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sheet.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                TextButton(onClick = onDismiss, Modifier.heightIn(min = 48.dp)) { Text("Done") }
            }
            when (sheet) {
                TouchPlayerSheet.Chapters -> TouchChapterChoices(host, account, onDismiss, onSeek)
                TouchPlayerSheet.Audio -> TouchTrackChoices(MediaStreamType.Audio, host, onDismiss)
                TouchPlayerSheet.Subtitles -> TouchTrackChoices(MediaStreamType.Subtitle, host, onDismiss)
                TouchPlayerSheet.Speed -> TouchSpeedChoices(host, onDismiss)
                TouchPlayerSheet.Info -> TouchPlaybackInformation(host)
            }
        }
    }
}

@Composable
private fun TouchChoiceRow(label: String, selected: Boolean, tag: String, description: String? = null, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
            this.selected = selected
            description?.let { contentDescription = it }
        }
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.weight(1f))
    }
}

@Composable
private fun TouchChapterChoices(host: PlaybackHostState, account: Account?, onDismiss: () -> Unit, onSeek: (Long) -> Unit) {
    val chapters = host.metadata.chapters
    val current = chapters.indexOfLast { it.startMs <= host.position }
    // PLAY-13: the current chapter is on screen when the sheet opens, however long the list.
    LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))) {
        itemsIndexed(chapters) { index, chapter ->
            TextButton(
                onClick = {
                    onSeek(chapter.startMs)
                    onDismiss()
                },
                Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("touch:chapter:$index").semantics { selected = index == current }
            ) {
                RadioButton(selected = index == current, onClick = null)
                val tag = chapter.imageTag
                if (tag != null && account != null) {
                    AsyncImage(
                        chapterImageUrl(account.server, host.item.id, index, tag),
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).width(96.dp).height(54.dp)
                    )
                }
                Text("${chapter.name.ifBlank { "Chapter ${index + 1}" }} · ${playerTime(chapter.startMs)}", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TouchTrackChoices(type: MediaStreamType, host: PlaybackHostState, onDismiss: () -> Unit) {
    val decision = host.state.decision
    val preparation = rememberPlayerTrackMenus(decision?.mediaSourceId, decision?.mediaSource?.mediaStreams.orEmpty())
    val menu = preparation.menus?.takeIf { decision != null }?.get(type)
    if (menu == null) {
        // Labels are prepared off the main thread and a title that's still loading has none yet (leg 79).
        Text("Waiting for playback information…", Modifier.heightIn(min = 48.dp))
        return
    }
    val selectedIndex = if (type == MediaStreamType.Audio) host.state.audioIndex else host.state.subtitleIndex
    val first = menu.choices.indexOfFirst { it.first == selectedIndex }.coerceAtLeast(0)
    LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = first)) {
        itemsIndexed(menu.choices, key = { _, choice -> choice.first }) { _, choice ->
            TouchChoiceRow(choice.second, choice.first == selectedIndex, "touch:track:${choice.first}") {
                host.controller?.select(type, choice.first)
                onDismiss()
            }
        }
        if (type == MediaStreamType.Subtitle) {
            item {
                val burnedIn = menu.streams.firstOrNull { it.index == selectedIndex }?.deliveryMethod == SubtitleDeliveryMethod.Encode
                if (burnedIn) Text("These subtitles are burned into the video. Timing and appearance cannot be changed during playback.")
                Text("Subtitle delay: ${host.subtitleDelayMs} ms")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-100 to "Earlier", 0 to "Reset", 100 to "Later").forEach { (delta, label) ->
                        TextButton(enabled = !burnedIn, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            host.subtitleDelayMs =
                                if (delta == 0) 0 else (host.subtitleDelayMs + delta).coerceIn(-10_000, 10_000)
                            host.output.setSubtitleDelayMs(host.subtitleDelayMs)
                        }) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchSpeedChoices(host: PlaybackHostState, onDismiss: () -> Unit) {
    val speed by host.output.speed.collectAsState()
    LazyColumn {
        items(availablePlaybackSpeeds(speed)) { value ->
            val label = PlaybackSpeeds.label(value)
            // With encoded audio 1× is the only row, so it carries the reason the others are gone (PLAY-25).
            val selected = value == speed.speed
            val reason = speed.message?.takeIf { selected }?.let { "$label. $it" }
            TouchChoiceRow(label, selected, "touch:speed:${(value * 100).toInt()}", reason) {
                host.output.setSpeed(value)
                onDismiss()
            }
        }
        item {
            Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(PLAYBACK_SPEED_NOTE)
                speed.message?.let { Text(it) }
                if (host.state.decision?.method == PlayMethod.Transcode) Text(PlaybackSpeeds.TRANSCODE)
            }
        }
    }
}

@Composable
private fun TouchPlaybackInformation(host: PlaybackHostState) {
    val decision = host.state.decision
    if (decision == null) {
        Text("Waiting for playback information…", Modifier.heightIn(min = 48.dp))
        return
    }
    val tracks = rememberReceivedTracks(host.player)
    Column(Modifier.verticalScroll(rememberScrollState()).testTag("touch:info"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(PlaybackInformation.method(decision), style = MaterialTheme.typography.titleMedium)
        playbackInformationSections(
            decision,
            host.state.audioIndex,
            host.state.subtitleIndex,
            host.localPreferences.bitrateMbps,
            tracks
        ).forEach { section ->
            Text(
                section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp).semantics { heading() }
            )
            section.lines.forEach { Text(it) }
        }
        Text(PLAYBACK_INFORMATION_NOTE, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}
