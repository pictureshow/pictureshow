/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.livetv.recordingStatus
import java.time.Instant

internal fun LazyListScope.liveRecordingShelves(
    state: LiveTvState,
    account: Account,
    tv: Boolean,
    restoreFocus: Boolean,
    returnFocus: String?,
    now: Long,
    onFocused: (String) -> Unit,
    onRecording: (MediaItem) -> Unit,
    onOptions: (MediaItem) -> Unit
) {
    if (!state.loading && state.recordings.isEmpty()) item { LiveText("No recordings available.", tv) }
    state.recordings.groupBy { it.seriesName?.takeIf(String::isNotBlank) ?: "Recordings" }.forEach { (group, recordings) ->
        item("recording-heading:$group") { LiveText(group, tv, heading = true) }
        item("recording-cards:$group") {
            // Native TV cards scale around the entire artwork/caption. Keep their raised bounds inside the shelf.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                itemsIndexed(recordings, key = { _, item -> item.id }) { index, item ->
                    val key = "r:${item.id}"
                    val firstGroup = state.recordings.firstOrNull()?.seriesName?.takeIf(String::isNotBlank) ?: "Recordings"
                    val target = Modifier.liveFocus(
                        key,
                        tv &&
                            !state.loading &&
                            (restoreFocus && returnFocus == key || returnFocus == null && index == 0 && group == firstGroup)
                    ) { onFocused(key) }
                    val canDelete = item.canDelete == true || state.policy?.deleteContent == true && item.canDelete != false
                    LiveRecordingCard(item, account, tv, now, canDelete, target, { onRecording(item) }, { onOptions(item) })
                }
            }
        }
    }
}

@Composable
private fun LiveRecordingCard(
    item: MediaItem,
    account: Account,
    tv: Boolean,
    now: Long,
    canDelete: Boolean,
    modifier: Modifier,
    onOpen: () -> Unit,
    onOptions: () -> Unit
) {
    val time = liveTime()
    val status = if (item.isInProgress) "InProgress" else item.recordingStatus
    val label = status?.takeUnless { it == "Completed" }?.let(::recordingStatus)
    val metadata = liveRecordingMetadata(item, time)
    val fraction = liveRecordingProgress(item, now)
    val growing = item.isInProgress || item.recordingStatus == "InProgress"
    val target = modifier.width(if (tv) 240.dp else 244.dp).semantics {
        stateDescription = listOfNotNull(
            liveRecordingTitle(item).takeUnless { it == item.name },
            label,
            metadata.takeIf(String::isNotBlank)
        ).joinToString(" · ")
        if (tv && canDelete) onLongClick(label = "Recording options", action = null)
    }.then(
        if (tv && canDelete) {
            Modifier.onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MENU) {
                    if (event.nativeKeyEvent.action == KeyEvent.ACTION_UP) onOptions()
                    true
                } else {
                    false
                }
            }
        } else {
            Modifier
        }
    )
    val caption: @Composable () -> Unit = {
        Column(Modifier.padding(if (tv) 10.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            RecordingCaptionText(liveRecordingTitle(item), tv, title = true)
            RecordingCaptionText(metadata, tv, title = false)
            Row(
                Modifier.fillMaxWidth().heightIn(min = (48 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f)) {
                    if (label != null) LiveStatus(label, tv, status)
                }
                if (!tv && canDelete) {
                    LiveIcon("more", "Recording options for ${liveRecordingTitle(item)}", false, onClick = onOptions)
                }
            }
        }
    }
    if (tv) {
        CatalogCard(
            item,
            account,
            true,
            onOpen,
            target,
            onLongClick = onOptions.takeIf { canDelete },
            caption = caption,
            artworkProgress = fraction,
            showProgress = !growing || fraction != null
        )
    } else {
        TouchCatalogCard(
            item,
            account,
            true,
            onOpen,
            target,
            reserveLines = false,
            caption = caption,
            artworkProgress = fraction,
            showProgress = !growing || fraction != null
        )
    }
}

@Composable
private fun RecordingCaptionText(value: String, tv: Boolean, title: Boolean) {
    val lines = if (title && !tv) 1 else 2
    if (tv) {
        androidx.tv.material3.Text(
            value,
            Modifier.testTag(if (title) "live:recording:title" else "live:recording:metadata"),
            minLines = lines,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            style = with(androidx.tv.material3.MaterialTheme.typography) {
                if (title) titleSmall else bodySmall
            }
        )
    } else {
        androidx.compose.material3.Text(
            value,
            Modifier.testTag(if (title) "live:recording:title" else "live:recording:metadata"),
            minLines = lines,
            maxLines = lines,
            overflow = TextOverflow.Ellipsis,
            style = with(androidx.compose.material3.MaterialTheme.typography) {
                if (title) titleSmall else bodySmall
            }
        )
    }
}

internal fun liveRecordingTitle(item: MediaItem): String =
    item.episodeTitle?.takeIf(String::isNotBlank) ?: item.name.takeIf { it.isNotBlank() && it != item.seriesName }
        ?: recordingEpisode(item) ?: "Recording"

internal fun liveRecordingMetadata(item: MediaItem, time: LiveTime): String {
    val start = recordingInstant(item.recordingStartDate)
    val end = recordingInstant(item.recordingEndDate)
    val growing = item.isInProgress || item.recordingStatus == "InProgress"
    val date = start ?: recordingInstant(item.premiereDate)
    return listOfNotNull(
        recordingEpisode(item)?.takeUnless { it == liveRecordingTitle(item) },
        if (growing && start != null && end != null && end > start) {
            "Ends ${time.clock(end)}"
        } else {
            date?.let { "${time.day(it)} · ${time.clock(it)}" }
        },
        item.runTimeTicks?.takeIf { it > 0 }?.let { if (it < 600_000_000) "<1 min" else "${it / 600_000_000} min" }
    ).joinToString(" · ")
}

/** A reported zero is authoritative, even when the elapsed guide window would suggest growth. */
internal fun liveRecordingProgress(item: MediaItem, now: Long): Float? {
    if (!item.isInProgress && item.recordingStatus != "InProgress") return null
    item.recordingCompletionPercentage?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { return (it / 100).toFloat() }
    val start = recordingInstant(item.recordingStartDate) ?: return null
    val end = recordingInstant(item.recordingEndDate) ?: return null
    if (end <= start || now < start || now > end) return null
    val duration = end.toDouble() - start.toDouble()
    return if (duration > 0) ((now.toDouble() - start.toDouble()) / duration).toFloat() else null
}

private fun recordingInstant(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

private fun recordingEpisode(item: MediaItem): String? = item.indexNumber?.let { episode ->
    item.parentIndexNumber?.let { "S$it E$episode" } ?: "Episode $episode"
}
