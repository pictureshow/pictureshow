/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import kotlin.math.abs

/** Keep a visible selection; otherwise choose a real visible programme in the same channel. */
internal fun liveGuideViewportFocus(
    cells: List<LiveGuideCell>,
    currentId: String?,
    viewportStart: Float,
    viewportWidth: Float,
    inset: Float = 0f
): String? {
    if (viewportWidth <= 0f) return null
    fun left(cell: LiveGuideCell) = inset + cell.startFraction * LIVE_GUIDE_WIDTH
    fun right(cell: LiveGuideCell) = left(cell) + cell.widthFraction * LIVE_GUIDE_WIDTH
    val visible = cells.filter { right(it) > viewportStart && left(it) < viewportStart + viewportWidth }
    val current = cells.firstOrNull { it.program.id == currentId }
    if (current != null && current in visible) return current.program.id
    val covering = visible.filter { left(it) <= viewportStart && right(it) > viewportStart }
    return (
        covering.firstOrNull { it.lane == current?.lane }
            ?: covering.firstOrNull()
            ?: visible.minWithOrNull(
                compareBy<LiveGuideCell> { abs(left(it) - viewportStart) }.thenBy { if (it.lane == current?.lane) 0 else 1 }
            )
        )?.program?.id
}

/** Labels never move their time tick or expose only a clipped suffix/prefix. */
internal fun liveGuideRulerLabelFits(x: Float, width: Float, viewportStart: Float, viewportWidth: Float): Boolean =
    width > 0f && x >= viewportStart + 8 && x + width <= viewportStart + viewportWidth - 8
