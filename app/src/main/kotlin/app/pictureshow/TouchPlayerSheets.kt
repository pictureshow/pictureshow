/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.SubtitleDeliveryMethod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchPlayerSheetContent(sheet: TouchPlayerSheet, host: PlaybackHostState, onDismiss: () -> Unit, onSeek: (Long) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("touch:sheet:$sheet")) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sheet.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            if (sheet == TouchPlayerSheet.Chapters) {
                val current = host.metadata.chapters.indexOfLast { it.startMs <= host.position }
                LazyColumn {
                    itemsIndexed(host.metadata.chapters) { index, chapter ->
                        TextButton(
                            onClick = {
                                onSeek(chapter.startMs)
                                onDismiss()
                            },
                            Modifier.fillMaxWidth()
                                .testTag("touch:chapter:$index").semantics { selected = index == current }
                        ) {
                            RadioButton(selected = index == current, onClick = null)
                            Text("${chapter.name.ifBlank { "Chapter ${index + 1}" }} · ${playerTime(chapter.startMs)}")
                        }
                    }
                }
            } else {
                val type = if (sheet == TouchPlayerSheet.Audio) MediaStreamType.Audio else MediaStreamType.Subtitle
                val streams = host.state.decision?.mediaSource?.mediaStreams.orEmpty().filter { it.type == type }
                val selectedIndex = if (type == MediaStreamType.Audio) host.state.audioIndex else host.state.subtitleIndex
                val labels = trackLabels(streams)
                val choices = listOf(-1 to "Off") + streams.mapIndexed { index, stream -> stream.index to labels[index] }
                LazyColumn {
                    itemsIndexed(choices) { _, choice ->
                        TextButton(
                            onClick = {
                                host.controller?.select(type, choice.first)
                                onDismiss()
                            },
                            Modifier.fillMaxWidth()
                                .testTag("touch:track:${choice.first}").semantics { selected = choice.first == selectedIndex }
                        ) {
                            RadioButton(selected = choice.first == selectedIndex, onClick = null)
                            Text(choice.second)
                        }
                    }
                    if (type == MediaStreamType.Subtitle) {
                        item {
                            val burnedIn =
                                streams.firstOrNull { it.index == selectedIndex }?.deliveryMethod == SubtitleDeliveryMethod.Encode
                            if (burnedIn) Text("These subtitles are burned into the video. Timing cannot be changed during playback.")
                            Text("Subtitle delay: ${host.subtitleDelayMs} ms")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(-100 to "Earlier", 0 to "Reset", 100 to "Later").forEach { (delta, label) ->
                                    TextButton(enabled = !burnedIn, onClick = {
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
        }
    }
}
