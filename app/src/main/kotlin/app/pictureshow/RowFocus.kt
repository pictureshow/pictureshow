/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

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
 * A shelf with stops for a reader beside its first and last cards, "Start of Libraries" and "End of Libraries". A reader's
 * Left and Right prefer the next node in line with the card, and otherwise accept any node further that way: another
 * row's card, a heading, Sign out. A stop is in line, and it reaches the shelf's edge, so nothing lies beyond it.
 *
 * A stop takes no input focus, draws nothing and exists only while its card has focus, so a reader moving between rows
 * can't land on one. [StopInset] keeps it shorter than the cards, so a reader moving Down from the heading finds the
 * card first.
 *
 * [content] puts endCard(index) on each card.
 */
@Composable
internal fun ShelfWithEnds(title: String, state: LazyListState, lastIndex: Int, content: @Composable (endCard: (Int) -> Modifier) -> Unit) {
    var firstFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    val firstCard = remember { Modifier.onFocusChanged { firstFocused = it.isFocused } }
    val lastCard = remember { Modifier.onFocusChanged { lastFocused = it.isFocused } }
    Box(Modifier.fillMaxWidth()) {
        content { index -> (if (index == 0) firstCard else Modifier).then(if (index == lastIndex) lastCard else Modifier) }
        val density = LocalDensity.current
        if (firstFocused) {
            val width by remember(state) { derivedStateOf { shelfStartEnd(state.layoutInfo) } }
            width?.takeIf { it > 0 }?.let {
                Box(
                    Modifier.matchParentSize().padding(vertical = StopInset).wrapContentWidth(Alignment.Start)
                        .width(with(density) { it.toDp() }).semantics { contentDescription = "Start of $title" }
                )
            }
        }
        if (lastFocused) {
            val start by remember(state) { derivedStateOf { shelfEndStart(state.layoutInfo) } }
            start?.let {
                Box(
                    Modifier.matchParentSize().padding(vertical = StopInset).padding(start = with(density) { it.toDp() })
                        .semantics { contentDescription = "End of $title" }
                )
            }
        }
    }
}

/** Shelf padding, 16.dp, plus enough that a stop's top is further than a card's from any heading above. */
private val StopInset = 40.dp
