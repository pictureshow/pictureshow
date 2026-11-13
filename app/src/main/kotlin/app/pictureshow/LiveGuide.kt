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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.pictureshow.core.Account
import app.pictureshow.core.livetv.LiveChannel
import app.pictureshow.core.livetv.LiveProgram
import kotlinx.coroutines.flow.collectLatest

internal const val LIVE_GUIDE_WIDTH = 1080f
internal const val LIVE_GUIDE_TEXT_MIN = 72f
internal fun liveGuideTickX(tick: Int): Float = tick * (LIVE_GUIDE_WIDTH / 6)
internal fun liveGuideShowsText(width: Float): Boolean = width >= LIVE_GUIDE_TEXT_MIN
internal fun liveGuideLaneHeight(tv: Boolean, fontScale: Float): Float = (if (tv) 56 else 60) * fontScale.coerceAtLeast(1f)
internal fun liveGuideRulerHeight(tv: Boolean, fontScale: Float): Float = if (tv) 48 * fontScale.coerceAtLeast(1f) else 36f

/** All rows share the same scroll position and mapping, including the single sticky ruler. */
@Composable
internal fun LiveGuideContent(
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
    guideUnavailable: Boolean = false,
    channelHeader: @Composable () -> Unit = { LiveText("Channel", tv, small = true) },
    onOpen: (LiveProgram) -> Unit
) {
    val time = liveTime()
    val rowHeight = liveGuideLaneHeight(tv, LocalDensity.current.fontScale).dp
    val density = LocalDensity.current
    val channelWidth = if (tv) (200 * density.fontScale.coerceAtLeast(1f)).dp else 80.dp
    val inset = if (tv) 8f else 0f
    val canvasWidth = LIVE_GUIDE_WIDTH + 2 * inset
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    val end = start + LiveTvViewModel.WINDOW
    val nowX = (now - start).toFloat() / (end - start) * LIVE_GUIDE_WIDTH
    val rows = remember(programs, start, end) {
        programs.groupBy { it.channelId }.mapValues { (_, shows) -> liveGuideCells(shows, start, end) }
    }
    val firstChannel = channels.firstOrNull { rows[it.id].orEmpty().isNotEmpty() }?.id
    var activeChannel by remember { mutableStateOf<String?>(null) }
    var focusedProgram by remember { mutableStateOf<String?>(null) }
    var viewportFocus by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rows, scroll, viewportWidth, density.density) {
        snapshotFlow { Triple(scroll.value, scroll.isScrollInProgress, focusedProgram) }.collectLatest { position ->
            if (position.third == null || position.second || viewportWidth <= 0f) return@collectLatest
            // Let native bring-into-view and focus changes settle before reconciling a manual time scroll.
            repeat(2) { withFrameNanos { } }
            if (scroll.isScrollInProgress || scroll.value != position.first || focusedProgram != position.third) return@collectLatest
            val cells = activeChannel?.let(rows::get).orEmpty()
            val candidate = liveGuideViewportFocus(cells, position.third, position.first / density.density, viewportWidth, inset)
            if (candidate != null && candidate != position.third) viewportFocus = "p:$candidate"
        }
    }
    val textMeasurer = rememberTextMeasurer()
    val rulerStyle = if (tv) {
        androidx.tv.material3.MaterialTheme.typography.bodySmall
    } else {
        androidx.compose.material3.MaterialTheme.typography.bodySmall
    }
    val rulerHeight = liveGuideRulerHeight(tv, density.fontScale).dp
    Row(Modifier.fillMaxWidth().height(rulerHeight).testTag("live:guide-ruler")) {
        Box(Modifier.width(channelWidth).height(rulerHeight), contentAlignment = Alignment.CenterStart) { if (tv) channelHeader() }
        Box(Modifier.weight(1f)) {
            Box(Modifier.horizontalScroll(scroll).width(canvasWidth.dp).height(rulerHeight)) {
                (0..6).forEach { tick ->
                    val tickX = inset + liveGuideTickX(tick)
                    val label = time.clock(start + tick * 30 * 60_000L)
                    val labelWidth = remember(label, rulerStyle, textMeasurer, density.density) {
                        textMeasurer.measure(AnnotatedString(label), style = rulerStyle, softWrap = false, maxLines = 1).size.width /
                            density.density
                    }
                    val labelFits by remember(tickX, labelWidth, scroll, viewportWidth, density.density) {
                        derivedStateOf { liveGuideRulerLabelFits(tickX, labelWidth, scroll.value / density.density, viewportWidth) }
                    }
                    Box(Modifier.offset(x = tickX.dp).height(rulerHeight).testTag("live:tick:$tick")) {
                        if (labelFits) {
                            LiveText(
                                label,
                                tv,
                                Modifier.align(Alignment.CenterStart).testTag("live:tick-label:$tick"),
                                small = true,
                                maxLines = 1
                            )
                        }
                        Box(Modifier.align(Alignment.BottomStart).width(1.dp).height(6.dp).background(LiveMuted))
                    }
                }
                if (nowX in 0f..LIVE_GUIDE_WIDTH) {
                    Box(
                        Modifier.offset(
                            x = (inset + nowX).dp
                        ).width(2.dp).height(rulerHeight).background(LiveAccent).testTag("live:ruler-now")
                    )
                }
            }
        }
    }
    LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("live:guide-rows")) {
        itemsIndexed(channels, key = { _, channel -> channel.id }) { _, channel ->
            val cells = rows[channel.id].orEmpty()
            val lanes = (cells.maxOfOrNull { it.lane } ?: 0) + 1
            val initial = liveGuideInitialFocus(cells, now)
            Row(Modifier.fillMaxWidth().height(rowHeight * lanes).testTag("live:guide-row:${channel.id}")) {
                Row(
                    Modifier.width(channelWidth).height(rowHeight).padding(end = if (tv) 0.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (tv) {
                        LiveChannelMark(channel, account, true, compact = true)
                        Column(Modifier.weight(1f)) {
                            LiveText(channel.name, true, maxLines = 1)
                            if (channel.userData.isFavorite) LiveText("★ Favorite", true, small = true)
                        }
                    } else {
                        Column(
                            Modifier.fillMaxWidth().semantics {
                                contentDescription = "Channel ${channel.number.orEmpty()}, ${channel.name}"
                            },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LiveChannelMark(channel, account, false, compact = true, showNumber = false)
                            androidx.compose.material3.Text(
                                channel.name,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                Box(Modifier.weight(1f).onSizeChanged { viewportWidth = with(density) { it.width.toDp().value } }) {
                    Box(Modifier.horizontalScroll(scroll).width(canvasWidth.dp).height(rowHeight * lanes)) {
                        cells.forEach { cell ->
                            val show = cell.program
                            val key = "p:${show.id}"
                            val width = cell.widthFraction * LIVE_GUIDE_WIDTH
                            val label = "${show.name} · ${time.range(show.startDate, show.endDate)}" +
                                if (show.hasRecordingTimer()) " · Recording scheduled" else ""
                            val x = inset + cell.startFraction * LIVE_GUIDE_WIDTH
                            var isFocused by remember(show.id) { mutableStateOf(false) }
                            val target = Modifier.offset(x = x.dp, y = rowHeight * cell.lane + if (tv) 4.dp else 0.dp)
                                .width(width.dp).height(rowHeight - if (tv) 8.dp else 2.dp)
                                .graphicsLayer {
                                    val shift = if (tv && isFocused) {
                                        liveGuideFocusShift(x, width - 2, scroll.value / density.density, viewportWidth)
                                    } else {
                                        0f
                                    }
                                    translationX = with(density) { shift.dp.toPx() }
                                }
                                .onFocusChanged {
                                    isFocused = it.isFocused
                                    if (it.isFocused) {
                                        focusedProgram = show.id
                                    } else if (focusedProgram == show.id) {
                                        focusedProgram = null
                                    }
                                }
                                .liveFocus(
                                    key,
                                    viewportFocus?.let { it == key }
                                        ?: (
                                            restore &&
                                                returnFocus == key ||
                                                returnFocus == null &&
                                                channel.id == firstChannel &&
                                                show.id == initial
                                            )
                                ) {
                                    activeChannel = channel.id
                                    viewportFocus = null
                                    onFocus(key)
                                }.semantics { contentDescription = label }
                            // Surface owns the focus scale, while the outer layer owns its edge translation.
                            // Intersect the painted content bounds, then convert that interval back to local dp.
                            val textWindow by remember(scroll, x, width, tv, isFocused, viewportWidth, density.density) {
                                derivedStateOf {
                                    val viewportStart = scroll.value / density.density
                                    val contentWidth = (width - 2).coerceAtLeast(0f)
                                    val scale = if (tv && isFocused) 1.1f else 1f
                                    val shift = if (tv && isFocused) {
                                        liveGuideFocusShift(x, contentWidth, viewportStart, viewportWidth)
                                    } else {
                                        0f
                                    }
                                    val paintedStart = x + 1 - contentWidth * (scale - 1) / 2 + shift
                                    val visible = liveGuideTextWindow(paintedStart, contentWidth * scale, viewportStart, viewportWidth)
                                    LiveGuideTextWindow(visible.inset / scale, visible.width / scale)
                                }
                            }
                            val content: @Composable () -> Unit = {
                                Box(Modifier.fillMaxSize()) {
                                    if (liveGuideShowsText(textWindow.width)) {
                                        Column(
                                            Modifier.offset { androidx.compose.ui.unit.IntOffset(textWindow.inset.dp.roundToPx(), 0) }
                                                .width(textWindow.width.dp).fillMaxHeight()
                                                .testTag("live:cell-text:${show.id}")
                                                .padding(
                                                    start = 8.dp,
                                                    end = if (show.hasRecordingTimer()) 14.dp else 8.dp,
                                                    top = if (tv) 4.dp else 8.dp,
                                                    bottom = if (tv) 4.dp else 8.dp
                                                ),
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            if (tv) {
                                                androidx.tv.material3.Text(
                                                    show.name,
                                                    Modifier.testTag("live:cell-title:${show.id}"),
                                                    maxLines = 2,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    style = androidx.tv.material3.MaterialTheme.typography.bodyMedium
                                                )
                                            } else {
                                                LiveText(show.name, false, Modifier.testTag("live:cell-title:${show.id}"), maxLines = 1)
                                                LiveText(time.clock(show.startDate), false, small = true, maxLines = 1)
                                            }
                                        }
                                    }
                                    if (show.hasRecordingTimer()) {
                                        Box(
                                            Modifier.align(
                                                Alignment.TopEnd
                                            ).padding(top = 6.dp, end = 6.dp).testTag("live:cell-recording:${show.id}")
                                        ) {
                                            LiveRecordingDot()
                                        }
                                    }
                                }
                            }
                            val color = when {
                                liveInstant(show.endDate) <= now -> androidx.compose.ui.graphics.Color(0xFF1B202C)
                                app.pictureshow.core.livetv.canTune(show, now) -> androidx.compose.ui.graphics.Color(0xFF333C50)
                                else -> androidx.compose.ui.graphics.Color(0xFF252B39)
                            }
                            if (tv) {
                                androidx.tv.material3.Surface(
                                    onClick = { onOpen(show) },
                                    modifier = target.padding(horizontal = 1.dp),
                                    shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
                                    colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                        containerColor = color
                                    ),
                                    scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.1f)
                                ) { content() }
                            } else {
                                androidx.compose.material3.Surface(
                                    onClick = {
                                        onOpen(show)
                                    },
                                    modifier = target.padding(horizontal = 1.dp),
                                    shape = RoundedCornerShape(
                                        5.dp
                                    ),
                                    color = color
                                ) {
                                    content()
                                }
                            }
                        }
                        if (nowX >= 0 && nowX <= LIVE_GUIDE_WIDTH) {
                            Box(
                                Modifier.offset(
                                    x = (inset + nowX).dp
                                ).width(2.dp).height(rowHeight * lanes).background(LiveAccent).testTag("live:now-line")
                            )
                        }
                        if (cells.isEmpty()) {
                            LiveText(
                                liveChannelIdleLabel(false, guideUnavailable),
                                tv,
                                Modifier.padding(8.dp),
                                small = true
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Only the focus treatment shifts at a viewport edge; the broadcast/time coordinates stay exact. */
internal fun liveGuideFocusShift(x: Float, width: Float, viewportLeft: Float, viewportWidth: Float): Float {
    val growth = width.coerceAtLeast(0f) * .05f
    val minimum = viewportLeft + 8 - (x - growth)
    val maximum = viewportLeft + viewportWidth - 8 - (x + width + growth)
    val requested = if (minimum <= maximum) 0f.coerceIn(minimum, maximum) else minimum
    val limit = growth + 8
    return requested.coerceIn(-limit, limit)
}
