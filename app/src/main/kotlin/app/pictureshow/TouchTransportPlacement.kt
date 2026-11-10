/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/** Keep transport over the visible video, using free space beside cards before resorting to letterbox space. */
internal fun touchTransportPlacement(
    width: Int,
    height: Int,
    panelHeight: Int,
    video: Rect,
    blockers: List<Rect>,
    transportWidth: Int,
    transportHeight: Int
): IntOffset {
    val bottom = (height - panelHeight).coerceAtLeast(0).toFloat()
    val frame = Rect(video.left, video.top, video.right, minOf(video.bottom, bottom).coerceAtLeast(video.top))
    val preferred = frame.center
    val halfWidth = transportWidth / 2f
    val halfHeight = transportHeight / 2f
    fun place(area: Rect): Offset? {
        var free = listOf(area)
        for (blocker in blockers) {
            free = free.flatMap { room ->
                if (!room.overlaps(blocker)) {
                    listOf(room)
                } else {
                    val overlap = room.intersect(blocker)
                    listOf(
                        Rect(room.left, room.top, overlap.left, room.bottom),
                        Rect(overlap.right, room.top, room.right, room.bottom),
                        Rect(room.left, room.top, room.right, overlap.top),
                        Rect(room.left, overlap.bottom, room.right, room.bottom)
                    )
                }
            }.filter { it.width >= transportWidth && it.height >= transportHeight }
        }
        return free.filter { it.width >= transportWidth && it.height >= transportHeight }.map {
            Offset(
                preferred.x.coerceIn(it.left + halfWidth, it.right - halfWidth),
                preferred.y.coerceIn(it.top + halfHeight, it.bottom - halfHeight)
            )
        }.minByOrNull { (it - preferred).getDistanceSquared() }
    }
    val center = place(frame) ?: place(Rect(0f, 0f, width.toFloat(), bottom)) ?: preferred
    return IntOffset((center.x - width / 2f).roundToInt(), (center.y - halfHeight).roundToInt().coerceAtLeast(0))
}
