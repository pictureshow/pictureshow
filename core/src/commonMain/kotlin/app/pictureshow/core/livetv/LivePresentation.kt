/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.livetv

import app.pictureshow.core.MediaItem
import app.pictureshow.core.episodeLabel
import app.pictureshow.core.runtimeLabel

/** Time placement is shared; native clients choose their own pixel sizes and components. */
data class LiveGuideCell(val program: LiveProgram, val startFraction: Float, val widthFraction: Float, val lane: Int)

/** Empty time stays empty; overlapping broadcasts occupy separate lanes. */
fun liveGuideCells(programs: List<LiveProgram>, start: Long, end: Long): List<LiveGuideCell> {
    require(end > start)
    val lanes = mutableListOf<Long>()
    return programs.distinctBy { it.id }.sortedBy { liveInstant(it.startDate) }.mapNotNull { program ->
        val from = liveInstant(program.startDate)?.coerceAtLeast(start) ?: return@mapNotNull null
        val to = liveInstant(program.endDate)?.coerceAtMost(end) ?: return@mapNotNull null
        if (to <= from) return@mapNotNull null
        var lane = lanes.indexOfFirst { it <= from }
        if (lane < 0) {
            lane = lanes.size
            lanes.add(to)
        } else {
            lanes[lane] = to
        }
        LiveGuideCell(program, (from - start).toFloat() / (end - start), (to - from).toFloat() / (end - start), lane)
    }
}
fun liveGuideInitialFocus(cells: List<LiveGuideCell>, now: Long): String? = cells.firstOrNull { canTune(it.program, now) }?.program?.id
    ?: cells.firstOrNull { liveInstant(it.program.startDate)?.let { start -> start > now } == true }?.program?.id
    ?: cells.lastOrNull()?.program?.id

/** Invalid intervals or a failed/cancelled current timer never claim a recording is in progress. */
fun liveScheduleGroup(schedule: LiveSchedule, now: Long): String {
    val start = liveInstant(schedule.startDate)
    val end = liveInstant(schedule.endDate)
    return when {
        start == null || end == null || end <= start -> "Other schedules"
        end <= now -> "Past"
        start > now -> "Upcoming"
        schedule.status in listOf("New", "InProgress") -> "Recording now"
        else -> "Other schedules"
    }
}

data class LiveGuideTextWindow(val inset: Float, val width: Float)

/** Keep text in the visible interval without moving its time-placed programme cell. */
fun liveGuideTextWindow(cellX: Float, cellWidth: Float, viewportStart: Float, viewportWidth: Float): LiveGuideTextWindow {
    val width = cellWidth.coerceAtLeast(0f)
    val left = maxOf(cellX, viewportStart)
    val right = minOf(cellX + width, viewportStart + viewportWidth.coerceAtLeast(0f))
    return LiveGuideTextWindow((viewportStart - cellX).coerceIn(0f, width), (right - left).coerceAtLeast(0f))
}

/** Recording identity and provider-reported growth are shared; clients supply native date/clock formatting. */
fun liveRecordingTitle(item: MediaItem): String =
    item.episodeTitle?.takeIf(String::isNotBlank) ?: item.name.takeIf { it.isNotBlank() && it != item.seriesName }
        ?: recordingEpisode(item) ?: "Recording"

fun liveRecordingMetadata(item: MediaItem, day: (Long) -> String, clock: (Long) -> String): String {
    val start = liveInstant(item.recordingStartDate)
    val end = liveInstant(item.recordingEndDate)
    val growing = item.isInProgress || item.recordingStatus == "InProgress"
    return listOfNotNull(
        recordingEpisode(item)?.takeUnless { it == liveRecordingTitle(item) },
        if (growing && start != null && end != null && end > start) {
            "Ends ${clock(end)}"
        } else {
            start?.let { "${day(it)} · ${clock(it)}" }
        },
        runtimeLabel(item.runTimeTicks).takeIf(String::isNotBlank)
    ).joinToString(" · ")
}

/** A reported zero is authoritative; elapsed guide time never invents recording progress. */
fun liveRecordingProgress(item: MediaItem): Float? {
    if (!item.isInProgress && item.recordingStatus != "InProgress") return null
    return item.recordingCompletionPercentage?.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { (it / 100).toFloat() }
}

private fun recordingEpisode(item: MediaItem): String? =
    if (item.indexNumber != null || item.parentIndexNumber != null) episodeLabel(item) else null
