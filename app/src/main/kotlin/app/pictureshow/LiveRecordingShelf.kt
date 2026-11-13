/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.view.KeyEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.livetv.recordingStatus
import app.pictureshow.core.runtimeLabel
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
    onOptions: (MediaItem) -> Unit,
    focus: LiveRecordingFocus? = null
) {
    if (!state.loading && state.recordings.isEmpty()) item { LiveListText("No recordings available.", tv) }
    state.recordings.groupBy { it.seriesName?.takeIf(String::isNotBlank) ?: "Recordings" }.forEach { (group, recordings) ->
        item("recording-shelf:$group") {
            Column(
                Modifier.onGloballyPositioned { focus?.onShelfPositioned(group, it) }
                    .padding(bottom = if (tv) 0.dp else 12.dp).testTag("live:recording-shelf:$group"),
                verticalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp)
            ) {
                Box(Modifier.onSizeChanged { focus?.onHeadingSize(group, it.height) }.testTag("live:recording-heading:$group")) {
                    LiveListText(group, tv, heading = true)
                }
                val row: @Composable () -> Unit = {
                    // Native card scaling stays within the shelf, and horizontal scrolling keeps the platform policy.
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = if (tv) 20.dp else 0.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        itemsIndexed(recordings, key = { _, item -> item.id }) { index, item ->
                            val key = "r:${item.id}"
                            val firstGroup = state.recordings.firstOrNull()?.seriesName?.takeIf(String::isNotBlank) ?: "Recordings"
                            val target = Modifier.onGloballyPositioned { focus?.onCardPositioned(item.id, it) }.liveFocus(
                                key,
                                tv &&
                                    !state.loading &&
                                    (restoreFocus && returnFocus == key || returnFocus == null && index == 0 && group == firstGroup)
                            ) {
                                focus?.onCardFocused(group, item.id)
                                onFocused(key)
                            }
                            val canDelete = item.canDelete == true || state.policy?.deleteContent == true && item.canDelete != false
                            LiveRecordingCard(item, account, tv, now, canDelete, target, { onRecording(item) }, { onOptions(item) })
                        }
                    }
                }
                if (focus != null) focus.HorizontalContent(row) else row()
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
    val fraction = liveRecordingProgress(item)
    val growing = item.isInProgress || item.recordingStatus == "InProgress"
    val target = modifier.width(if (tv) 240.dp else 244.dp).semantics {
        stateDescription = listOfNotNull(
            liveRecordingTitle(item).takeUnless { tv || it == item.name },
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
        val density = LocalDensity.current
        val reserve = if (tv) {
            with(androidx.tv.material3.MaterialTheme.typography) {
                liveRecordingCaptionHeight(density.fontScale, titleSmall.lineHeight.value, bodySmall.lineHeight.value).dp
            }
        } else {
            null
        }
        Column(
            Modifier.then(reserve?.let { Modifier.height(it) } ?: Modifier)
                .padding(if (tv) 10.dp else 12.dp).testTag("live:recording:caption:${item.id}"),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            RecordingCaptionText(liveRecordingTitle(item), tv, title = true)
            RecordingCaptionText(metadata, tv, title = false)
            if (!tv) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = (48 * density.fontScale.coerceAtLeast(1f)).dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) { if (label != null) LiveStatus(label, false, status) }
                    if (canDelete) LiveTouchRecordingMenu(item.id, liveRecordingTitle(item), onOptions)
                }
            }
        }
    }
    val statusOverlay: (@Composable BoxScope.() -> Unit)? = if (tv && label != null) {
        {
            Box(Modifier.align(Alignment.TopStart).padding(6.dp).widthIn(max = 120.dp).testTag("live:recording:status:${item.id}")) {
                LiveStatus(label, true, status)
            }
        }
    } else {
        null
    }
    if (tv) {
        CatalogCard(
            item.copy(name = liveRecordingTitle(item)),
            account,
            true,
            onOpen,
            target,
            onLongClick = onOptions.takeIf { canDelete },
            caption = caption,
            artworkProgress = fraction,
            showProgress = !growing || fraction != null,
            artworkOverlay = statusOverlay
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
            minLines = 1,
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
    val date = start
    return listOfNotNull(
        recordingEpisode(item)?.takeUnless { it == liveRecordingTitle(item) },
        if (growing && start != null && end != null && end > start) {
            "Ends ${time.clock(end)}"
        } else {
            date?.let { "${time.day(it)} · ${time.clock(it)}" }
        },
        runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank)
    ).joinToString(" · ")
}

/** A reported zero is authoritative, even when the elapsed guide window would suggest growth. */
internal fun liveRecordingProgress(item: MediaItem): Float? {
    if (!item.isInProgress && item.recordingStatus != "InProgress") return null
    return item.recordingCompletionPercentage?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { (it / 100).toFloat() }
}

private fun recordingInstant(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

private fun recordingEpisode(item: MediaItem): String? =
    if (item.indexNumber != null || item.parentIndexNumber != null) episodeLabel(item) else null

/** Reserve extra title/metadata lines after the content, with no TV-only touch control row. */
internal fun liveRecordingCaptionHeight(fontScale: Float, titleLineHeight: Float, metadataLineHeight: Float): Float =
    (titleLineHeight * 2 + metadataLineHeight * 2) * fontScale + 24
