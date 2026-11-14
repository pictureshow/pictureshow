/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveProgram
import app.pictureshow.core.livetv.canTune
import app.pictureshow.core.livetv.liveInstant

internal data class LiveGuideCell(val program: LiveProgram, val startFraction: Float, val widthFraction: Float, val lane: Int)

/** Empty time stays empty; overlapping broadcasts occupy separate lanes instead of hiding or inventing programs. */
internal fun liveGuideCells(programs: List<LiveProgram>, start: Long, end: Long): List<LiveGuideCell> {
    require(end > start)
    val laneEnds = mutableListOf<Long>()
    return programs.distinctBy { it.id }.sortedBy { liveInstant(it.startDate) }.mapNotNull { program ->
        val from = liveInstant(program.startDate)?.coerceAtLeast(start) ?: return@mapNotNull null
        val to = liveInstant(program.endDate)?.coerceAtMost(end) ?: return@mapNotNull null
        if (to <= from) return@mapNotNull null
        var lane = laneEnds.indexOfFirst { it <= from }
        if (lane < 0) {
            lane = laneEnds.size
            laneEnds.add(to)
        } else {
            laneEnds[lane] = to
        }
        LiveGuideCell(program, (from - start).toFloat() / (end - start), (to - from).toFloat() / (end - start), lane)
    }
}

/** Enter at what is on now; elapsed slots remain browsable without becoming the initial selection. */
internal fun liveGuideInitialFocus(cells: List<LiveGuideCell>, now: Long): String? =
    cells.firstOrNull { canTune(it.program, now) }?.program?.id
        ?: cells.firstOrNull { liveInstant(it.program.startDate)?.let { start -> start > now } == true }?.program?.id
        ?: cells.lastOrNull()?.program?.id
