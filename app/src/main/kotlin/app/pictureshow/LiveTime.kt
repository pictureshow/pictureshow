/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One device-clock formatter for guide, details, schedules and playback metadata. */
internal class LiveTime(
    private val twentyFourHour: Boolean,
    private val locale: Locale = Locale.getDefault(),
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: Long = System.currentTimeMillis()
) {
    private val clock = DateTimeFormatter.ofPattern(
        DateFormat.getBestDateTimePattern(locale, if (twentyFourHour) "Hm" else "hm"),
        locale
    )

    fun clock(value: Long): String {
        val local = Instant.ofEpochMilli(value).atZone(zone)
        val ambiguous = zone.rules.getValidOffsets(local.toLocalDateTime()).size > 1
        return clock.format(local) + if (ambiguous) " ${local.offset}" else ""
    }

    fun clock(value: String?): String = instant(value)?.let(::clock) ?: "Time unavailable"

    fun day(value: Long): String {
        val date = Instant.ofEpochMilli(value).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (date) {
            today -> "Today"
            today.plusDays(1) -> "Tomorrow"
            else -> DateTimeFormatter.ofPattern("EEE d", locale).format(date)
        }
    }

    fun range(start: String?, end: String?): String {
        val from = instant(start) ?: return "Time unavailable"
        val to = instant(end) ?: return "Time unavailable"
        val date = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(to).atZone(zone).toLocalDate()
        // Keep both periods for a range crossing AM/PM; omit the repeated first period only when identical.
        var first = clock(from)
        val last = clock(to)
        if (!twentyFourHour && date == endDate) {
            val period = DateTimeFormatter.ofPattern("a", locale)
            val a = period.format(Instant.ofEpochMilli(from).atZone(zone))
            val b = period.format(Instant.ofEpochMilli(to).atZone(zone))
            if (a == b && first.endsWith(a) && last.endsWith(b)) first = first.removeSuffix(a).trimEnd()
        }
        return (if (date != today) "${day(from)} · " else "") + first + " – " +
            (if (endDate != date) "${day(to)} · " else "") + last
    }

    private fun instant(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
}

@Composable
internal fun liveTime(): LiveTime {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return LiveTime(
        DateFormat.is24HourFormat(context),
        androidx.core.os.ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    )
}

/** Calendar half-hour with the original overlap offset retained, including quarter-hour zones and DST folds. */
internal fun liveGuideWindowStart(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
    val local = Instant.ofEpochMilli(now).atZone(zone)
    return local.withMinute(local.minute / 30 * 30).withSecond(0).withNano(0).toInstant().toEpochMilli()
}
