/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/**
 * The item a vertical D-pad move lands on in a grid laid out row by row, or null when the move leaves the grid or goes
 * nowhere yet. Up keeps the column and is null from the first row. Down keeps the column. When nothing is loaded below in
 * this column it is null while [loadingMore], so the caller can hold the press for the page on its way; otherwise a row
 * below that doesn't reach this column lands on its last item, and the last row is null.
 */
fun gridVerticalTarget(index: Int, columns: Int, count: Int, down: Boolean, loadingMore: Boolean): Int? {
    if (columns <= 0 || index !in 0 until count) return null
    if (!down) return (index - columns).takeIf { it >= 0 }
    val last = count - 1
    return when {
        index + columns <= last -> index + columns
        loadingMore -> null
        last / columns > index / columns -> last
        else -> null
    }
}
