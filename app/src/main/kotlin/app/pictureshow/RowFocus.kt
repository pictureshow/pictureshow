/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.horizontalScrollAxisRange
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A press along a row stays in that row. Past its end Compose searches the whole screen and accepts anything whose
 * edges lie further that way, so a short shelf hands Right to an unrelated button below it, Sign out on home.
 *
 * TalkBack on a TV never lets this run: it consumes every direction key and picks the next node itself by comparing
 * node bounds, without asking Compose, so focusProperties don't reach it either. [ShelfWithEnds] gives it bounds
 * that hold the row.
 */
@Composable
internal fun Modifier.staysInRow(first: Boolean, last: Boolean): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val forward = if (rtl) Key.DirectionLeft else Key.DirectionRight
    val backward = if (rtl) Key.DirectionRight else Key.DirectionLeft
    return onPreviewKeyEvent {
        it.type == KeyEventType.KeyDown && (last && it.key == forward || first && it.key == backward)
    }
}

/** Where the empty space before a shelf's first card ends, in pixels from the shelf's start; null once that card is off. */
internal fun shelfStartEnd(layout: LazyListLayoutInfo): Int? {
    val first = layout.visibleItemsInfo.firstOrNull() ?: return null
    if (first.index != 0) return null
    return first.offset - layout.viewportStartOffset
}

/** Where the empty space after a shelf's last card starts, in pixels from the shelf's start; null until that card is showing. */
internal fun shelfEndStart(layout: LazyListLayoutInfo): Int? {
    val last = layout.visibleItemsInfo.lastOrNull() ?: return null
    if (last.index != layout.totalItemsCount - 1) return null
    return last.offset + last.size - layout.viewportStartOffset
}

/**
 * Stops belong to the same horizontal scroll container as the cards. TalkBack checks that ancestry before
 * traversing an edge: a sibling of LazyRow is outside the scrollable, so a long row can skip it even when
 * its bounds are in line. Each lazy item wraps its card with [endCard]; the invisible stop occupies the
 * existing gutter without adding a lazy item or changing card indices.
 *
 * A stop takes no input focus, draws nothing and exists only while its card has focus. Its inset keeps
 * it further from a heading than the card, so Down reaches the card first.
 */
@Composable
internal fun ShelfWithEnds(
    title: String,
    state: LazyListState,
    lastIndex: Int,
    content: @Composable (endCard: @Composable (Int, @Composable (Modifier) -> Unit) -> Unit, rowSemantics: Modifier) -> Unit
) {
    val scope = rememberCoroutineScope()
    val rowSemantics = Modifier.semantics {
        // Include the two reader stops in the logical range. Older TV readers otherwise
        // replace an unavailable horizontal action with a vertical ancestor's linear scroll.
        horizontalScrollAxisRange = ScrollAxisRange(
            value = { state.firstVisibleItemIndex + 1f + state.firstVisibleItemScrollOffset / 100000f },
            maxValue = { lastIndex + 2f },
            reverseScrolling = false
        )
        scrollBy { x, _ ->
            if (!x.isFinite() || x == 0f || (x < 0 && !state.canScrollBackward) || (x > 0 && !state.canScrollForward)) {
                false
            } else {
                scope.launch { state.animateScrollBy(x) }
                true
            }
        }
    }
    content({ index, card ->
        var focused by remember { mutableStateOf(false) }
        val focus = Modifier.onFocusChanged { focused = it.isFocused }
        LaunchedEffect(focused, index, lastIndex) {
            // Bring-into-view can consume a shelf's edge padding. Restore its gutter when an end
            // card takes focus so the reader's stop has real, visible bounds beside that card.
            if (focused && (index == 0 || index == lastIndex)) state.requestScrollToItem(index)
        }
        Box {
            card(focus)
            val density = LocalDensity.current
            if (focused && index == 0) {
                val width by remember(state) { derivedStateOf { shelfStartEnd(state.layoutInfo) } }
                width?.takeIf { it > 0 }?.let {
                    val gutter = with(density) { it.toDp() }
                    Box(
                        Modifier.matchParentSize().padding(vertical = StopInset).wrapContentWidth(Alignment.Start, unbounded = true)
                            .offset(x = -gutter).width(gutter).semantics { contentDescription = "Start of $title" }
                    )
                }
            }
            if (focused && index == lastIndex) {
                val width by remember(state) {
                    derivedStateOf { shelfEndStart(state.layoutInfo)?.let { state.layoutInfo.viewportSize.width - it } }
                }
                width?.takeIf { it > 0 }?.let {
                    val gutter = with(density) { it.toDp() }
                    Box(
                        Modifier.matchParentSize().padding(vertical = StopInset).wrapContentWidth(Alignment.End, unbounded = true)
                            .offset(x = gutter).width(gutter).semantics { contentDescription = "End of $title" }
                    )
                }
            }
        }
    }, rowSemantics)
}

/** Enough inset that Down from a heading reaches the card before either stop. */
private val StopInset = 40.dp
