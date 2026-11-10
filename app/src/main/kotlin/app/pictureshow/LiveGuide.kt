/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram

internal const val LIVE_GUIDE_WIDTH = 1080f
internal const val LIVE_GUIDE_TEXT_MIN = 72f
internal fun liveGuideTickX(tick: Int): Float = tick * (LIVE_GUIDE_WIDTH / 6)
internal fun liveGuideShowsText(width: Float): Boolean = width >= LIVE_GUIDE_TEXT_MIN

/** All rows share the same scroll position and mapping, including the single sticky ruler. */
@Composable
internal fun LiveGuide(
    channels: List<LiveChannel>,
    programs: List<LiveProgram>,
    account: Account,
    tv: Boolean,
    start: Long,
    now: Long,
    scroll: ScrollState,
    list: LazyListState,
    returnFocus: String?,
    restore: Boolean,
    onFocus: (String) -> Unit,
    onOpen: (LiveProgram) -> Unit
) {
    val time = liveTime()
    val rowHeight = (60 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val channelWidth = if (tv) 200.dp else 72.dp
    var preview by remember(programs) { mutableStateOf(programs.firstOrNull { "p:${it.id}" == returnFocus } ?: programs.firstOrNull()) }
    val end = start + LiveTvViewModel.WINDOW
    val nowX = (now - start).toFloat() / (end - start) * LIVE_GUIDE_WIDTH
    if (tv) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 6.dp).testTag("live:guide-preview"),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            LiveText(preview?.name ?: "Choose a program", true, heading = true, maxLines = 1)
            preview?.let {
                LiveText(
                    "${it.channelName ?: channels.firstOrNull { c ->
                        c.id == it.channelId
                    }?.name.orEmpty()} · ${time.range(it.startDate, it.endDate)}${it.episodeTitle?.let { title ->
                        " · $title"
                    }.orEmpty()}",
                    true,
                    small = true,
                    maxLines = 1
                )
                it.overview?.let { overview -> LiveText(overview, true, small = true, maxLines = 1) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        Box(Modifier.width(channelWidth).height(32.dp), contentAlignment = Alignment.CenterStart) { LiveText("Channel", tv, small = true) }
        Box(Modifier.weight(1f).clipToBounds()) {
            Box(Modifier.horizontalScroll(scroll).width(LIVE_GUIDE_WIDTH.dp).height(32.dp)) {
                (0..6).forEach { tick ->
                    Column(Modifier.offset(x = liveGuideTickX(tick).dp).testTag("live:tick:$tick")) {
                        LiveText(time.clock(start + tick * 30 * 60_000L), tv, small = true, maxLines = 1)
                        Box(Modifier.width(1.dp).height(6.dp).background(LiveMuted))
                    }
                }
            }
        }
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("live:guide-rows")) {
        itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
            val cells = liveGuideCells(programs.filter { it.channelId == channel.id }, start, end)
            val lanes = (cells.maxOfOrNull { it.lane } ?: 0) + 1
            Row(Modifier.fillMaxWidth().height(rowHeight * lanes)) {
                Row(
                    Modifier.width(channelWidth).height(rowHeight).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LiveChannelMark(channel, account, tv, compact = true)
                    if (tv) {
                        Column(Modifier.weight(1f)) {
                            LiveText(channel.name, true, maxLines = 1)
                            if (channel.userData.isFavorite) LiveText("★ Favorite", true, small = true)
                        }
                    }
                }
                Box(Modifier.weight(1f).clipToBounds()) {
                    Box(Modifier.horizontalScroll(scroll).width(LIVE_GUIDE_WIDTH.dp).height(rowHeight * lanes)) {
                        cells.forEachIndexed { cellIndex, cell ->
                            val show = cell.program
                            val key = "p:${show.id}"
                            val width = cell.widthFraction * LIVE_GUIDE_WIDTH
                            val label = "${show.name} · ${time.range(show.startDate, show.endDate)}"
                            val target = Modifier.offset(x = (cell.startFraction * LIVE_GUIDE_WIDTH).dp, y = rowHeight * cell.lane)
                                .width(width.dp).height(rowHeight - 2.dp)
                                .liveFocus(key, returnFocus == key || returnFocus == null && index == 0 && cellIndex == 0) {
                                    preview = show
                                    onFocus(key)
                                }.semantics { contentDescription = label }
                            val content: @Composable () -> Unit = {
                                Column(
                                    Modifier.padding(if (liveGuideShowsText(width)) 8.dp else 0.dp),
                                    verticalArrangement = Arrangement.spacedBy(1.dp)
                                ) {
                                    if (liveGuideShowsText(width)) {
                                        LiveText(show.name, tv, Modifier.testTag("live:cell-title:${show.id}"), maxLines = 2)
                                        if (rowHeight >= 70.dp) LiveText(time.clock(show.startDate), tv, small = true, maxLines = 1)
                                    }
                                    if (show.timerId != null || show.seriesTimerId != null) LiveText("●", tv, small = true)
                                }
                            }
                            if (tv) {
                                androidx.tv.material3.Surface(
                                    onClick = { onOpen(show) },
                                    modifier = target,
                                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
                                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                        containerColor = androidx.compose.ui.graphics.Color(0xFF252B39)
                                    ),
                                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                                ) { content() }
                            } else {
                                androidx.compose.material3.Surface(
                                    onClick = {
                                        preview = show
                                        onOpen(show)
                                    },
                                    modifier = target,
                                    shape = RoundedCornerShape(
                                        5.dp
                                    ),
                                    color = androidx.compose.ui.graphics.Color(0xFF252B39)
                                ) {
                                    content()
                                }
                            }
                        }
                        if (nowX >= 0 && nowX <= LIVE_GUIDE_WIDTH) {
                            Box(
                                Modifier.offset(
                                    x = nowX.dp
                                ).width(2.dp).height(rowHeight * lanes).background(LiveAccent).testTag("live:now-line")
                            )
                        }
                        if (cells.isEmpty()) LiveText("No guide data", tv, Modifier.padding(8.dp), small = true)
                    }
                }
            }
        }
    }
}
