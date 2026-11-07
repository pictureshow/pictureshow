/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/** What a vertical D-pad press does in a grid laid out row by row. See CAT-19. */
sealed interface GridVerticalMove {
    /** Focus goes to the card at [index]. */
    data class Card(val index: Int) : GridVerticalMove

    /** The press waits for the page on its way, until [gridHoldRelease] says where it ends. */
    data object Hold : GridVerticalMove

    /** The press is spent and focus doesn't move. */
    data object Stay : GridVerticalMove

    /** Up from the first row: the caller decides what is above the grid. */
    data object Above : GridVerticalMove
}

/**
 * Up keeps the column, and leaves the grid from the first row. Down keeps the column. When nothing is loaded below in this
 * column it holds while [loadingMore]; otherwise a row below that doesn't reach this column lands on its last item, and
 * the last row stays.
 */
fun gridVerticalMove(index: Int, columns: Int, count: Int, down: Boolean, loadingMore: Boolean): GridVerticalMove {
    if (columns <= 0 || index !in 0 until count) return GridVerticalMove.Stay
    if (!down) return if (index >= columns) GridVerticalMove.Card(index - columns) else GridVerticalMove.Above
    val last = count - 1
    return when {
        index + columns <= last -> GridVerticalMove.Card(index + columns)
        loadingMore -> GridVerticalMove.Hold
        last / columns > index / columns -> GridVerticalMove.Card(last)
        else -> GridVerticalMove.Stay
    }
}

/** The card [gridVerticalMove] lands on, or null when it lands on none. */
fun gridVerticalTarget(index: Int, columns: Int, count: Int, down: Boolean, loadingMore: Boolean): Int? =
    (gridVerticalMove(index, columns, count, down, loadingMore) as? GridVerticalMove.Card)?.index

/**
 * Where a held Down from [index] ends, with [count] items loaded by then. The same for a page, an empty page, a failure and
 * the cap: the page is no longer awaited, so the press is a plain Down. Never holds again, so Down can't stay dead.
 */
fun gridHoldRelease(index: Int, columns: Int, count: Int): GridVerticalMove =
    gridVerticalMove(index, columns, count, down = true, loadingMore = false)

/** How long a held Down waits for its page before it lands on what's loaded. */
const val GRID_HOLD_CAP_MILLIS = 1_500L

/** The sorts a library grid offers, by the server's name for each, in the order the chips show them. */
val GridSorts = listOf("SortName" to "Name", "DateCreated" to "Date added", "PremiereDate" to "Release date")

/** The chips' own words for the sort in force. An unknown sort reads as the first. */
fun gridStripDetail(sortBy: String, descending: Boolean): String {
    val title = GridSorts.firstOrNull { it.first == sortBy }?.second ?: GridSorts.first().second
    return "$title, ${if (descending) "descending" else "ascending"}"
}
