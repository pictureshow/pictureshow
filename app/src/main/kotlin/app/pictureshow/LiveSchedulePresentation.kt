/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveSchedule
import java.time.Instant

internal data class LiveScheduleGroup(val title: String, val seriesRule: Boolean, val schedules: List<LiveSchedule>)

/** Group airings by observed timestamps; an absent/invalid interval cannot establish that an airing is upcoming or past. */
internal fun liveScheduleGroup(schedule: LiveSchedule, now: Long): String {
    val start = runCatching { Instant.parse(schedule.startDate).toEpochMilli() }.getOrNull()
    val end = runCatching { Instant.parse(schedule.endDate).toEpochMilli() }.getOrNull()
    val valid = start != null && end != null && end > start
    return when {
        valid && end != null && end <= now -> "Past"
        start != null && end != null && end > start && start > now -> "Upcoming"
        start != null && end != null && end > start && start <= now && schedule.status in listOf("New", "InProgress") -> "Recording now"
        else -> "Other schedules"
    }
}

/** Preserve every server row and its relative order, including ended cancelled/error rows. Rules remain a separate section. */
internal fun liveScheduleGroups(series: List<LiveSchedule>, timers: List<LiveSchedule>, now: Long): List<LiveScheduleGroup> =
    listOf("Recording now", "Upcoming", "Series rules", "Other schedules", "Past").map { title ->
        val rule = title == "Series rules"
        LiveScheduleGroup(title, rule, if (rule) series else timers.filter { liveScheduleGroup(it, now) == title })
    }

/** Time-dependent section labels never become part of a row's input or lazy-list identity. */
internal fun liveScheduleFocusKey(schedule: LiveSchedule, seriesRule: Boolean): String =
    "s:${if (seriesRule) "series" else "timer"}:${schedule.id}"

internal data class LiveRefreshPresentation(val pullRefreshing: Boolean, val headerLoading: Boolean)

/** Only the refresh callback dispatched by an actual pull gesture owns the floating pull indicator. */
internal fun liveRefreshPresentation(loading: Boolean, writing: Boolean, tv: Boolean, pulled: Boolean): LiveRefreshPresentation =
    LiveRefreshPresentation(loading && pulled && !tv, writing || loading && (tv || !pulled))
