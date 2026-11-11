/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.livetv.LiveSchedule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class LiveSchedulePresentationTest {
    private val now = Instant.parse("2026-11-10T20:00:00Z").toEpochMilli()

    @Test fun endedCancelledRowsArePastAndActiveRowsAreNotCalledUpcoming() {
        assertEquals("Past", liveScheduleGroup(schedule("past", now - 120_000, now, "Cancelled"), now))
        assertEquals("Scheduled", liveScheduleGroup(schedule("active", now - 60_000, now + 60_000, "InProgress"), now))
        assertEquals("Upcoming", liveScheduleGroup(schedule("future", now + 1, now + 60_000), now))
        assertEquals("Scheduled", liveScheduleGroup(schedule("starts-now", now, now + 60_000), now))
    }

    @Test fun missingOrMalformedBoundsCannotEstablishAnUpcomingAiring() {
        listOf(
            schedule("missing"),
            schedule("reversed", now + 60_000, now + 30_000),
            LiveSchedule(
                buildJsonObject {
                    put("Id", "invalid")
                    put("StartDate", "bad")
                    put("EndDate", "bad")
                }
            )
        ).forEach { assertEquals("Scheduled", liveScheduleGroup(it, now)) }
    }

    @Test fun groupingPreservesEveryRowAndRuleIdentityWhileAiringsChangeSection() {
        val past = schedule("past", now - 120_000, now - 1, "Cancelled")
        val upcoming = schedule("future", now + 1, now + 60_000)
        val unknown = schedule("unknown")
        val secondPast = schedule("past2", now - 120_000, now - 2, "Error")
        val groups = liveScheduleGroups(listOf(schedule("future")), listOf(past, upcoming, unknown, secondPast), now)
        assertEquals(listOf("Series rules", "Scheduled", "Upcoming", "Past"), groups.map { it.title })
        assertEquals(listOf("past", "past2"), groups.last().schedules.map { it.id })
        assertEquals(5, groups.sumOf { it.schedules.size })
        assertTrue(groups.first().seriesRule)
        assertTrue(groups.drop(1).none { it.seriesRule })
        val key = liveScheduleFocusKey(upcoming, false)
        assertEquals("Upcoming", liveScheduleGroup(upcoming, now))
        assertEquals("Past", liveScheduleGroup(upcoming, now + 60_000))
        assertEquals(key, liveScheduleFocusKey(upcoming, false))
        assertEquals("s:timer:future", key)
        assertEquals("s:series:future", liveScheduleFocusKey(upcoming, true))
    }

    @Test fun nonGestureLoadsUseHeaderAndOnlyAnActivePhonePullUsesTheFloatingIndicator() {
        assertEquals(LiveRefreshPresentation(false, true), liveRefreshPresentation(true, false, false, false))
        assertEquals(LiveRefreshPresentation(true, false), liveRefreshPresentation(true, false, false, true))
        assertEquals(LiveRefreshPresentation(false, true), liveRefreshPresentation(true, false, true, true))
        assertEquals(LiveRefreshPresentation(false, true), liveRefreshPresentation(false, true, false, false))
        assertEquals(LiveRefreshPresentation(false, false), liveRefreshPresentation(false, false, false, true))
        assertFalse(liveRefreshPresentation(false, false, false, false).pullRefreshing)
    }

    private fun schedule(id: String, start: Long? = null, end: Long? = null, status: String = "New") = LiveSchedule(
        buildJsonObject {
            put("Id", id)
            put("Status", status)
            start?.let { put("StartDate", Instant.ofEpochMilli(it).toString()) }
            end?.let { put("EndDate", Instant.ofEpochMilli(it).toString()) }
        }
    )
}
