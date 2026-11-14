/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

/** Visible cell interval, measured from the cell's leading edge in the same units as its inputs. */
internal data class LiveGuideTextWindow(val inset: Float, val width: Float)

/** Keep titles inside the viewport without changing the programme's time-placed cell. */
internal fun liveGuideTextWindow(cellX: Float, cellWidth: Float, viewportStart: Float, viewportWidth: Float): LiveGuideTextWindow {
    val width = cellWidth.coerceAtLeast(0f)
    val left = maxOf(cellX, viewportStart)
    val right = minOf(cellX + width, viewportStart + viewportWidth.coerceAtLeast(0f))
    return LiveGuideTextWindow(
        (viewportStart - cellX).coerceIn(0f, width),
        (right - left).coerceAtLeast(0f)
    )
}
