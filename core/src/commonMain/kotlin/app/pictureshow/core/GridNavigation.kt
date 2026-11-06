/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

/**
 * The item a vertical D-pad move lands on in a grid laid out row by row, or null when the move leaves the grid or goes
 * nowhere. Up keeps the column and is null from the first row. Down keeps the column; when the row below is shorter and
 * doesn't reach this column it lands on that row's last item, and it is null from the last row.
 */
fun gridVerticalTarget(index: Int, columns: Int, count: Int, down: Boolean): Int? {
    if (columns <= 0 || index !in 0 until count) return null
    if (!down) return (index - columns).takeIf { it >= 0 }
    val last = count - 1
    return when {
        index + columns <= last -> index + columns
        last / columns > index / columns -> last
        else -> null
    }
}
