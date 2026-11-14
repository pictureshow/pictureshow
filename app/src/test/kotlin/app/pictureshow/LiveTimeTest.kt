/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class, shadows = [LiveClockPatternShadow::class])
class LiveTimeTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = Instant.parse("2026-11-10T18:00:00Z").toEpochMilli()

    @Test fun clockFollowsDeviceChoiceAndConciseRangesKeepPeriodTransitions() {
        val twelve = LiveTime(false, Locale.US, zone, today)
        val full = twelve.range("2026-11-10T20:14:00Z", "2026-11-10T20:42:00Z").replace('\u202f', ' ').replace('\u00a0', ' ')
        assertTrue(full.startsWith("3:14"), full)
        assertTrue(full.endsWith("3:42 PM"), full)
        assertEquals(1, Regex("PM").findAll(full).count())
        assertFalse(full.contains("-05:00"))
        val boundary = twelve.range("2026-11-10T16:55:00Z", "2026-11-10T17:10:00Z")
        assertTrue(boundary.contains("AM"), boundary)
        assertTrue(boundary.contains("PM"), boundary)
        val twentyFour = LiveTime(true, Locale.US, zone, today)
        assertEquals("15:14", twentyFour.clock("2026-11-10T20:14:00Z"))
    }

    @Test fun onlyAmbiguousDstHourDisplaysOffsetAndDatesDoNotRepeatToday() {
        val time = LiveTime(false, Locale.US, zone, today)
        assertTrue(time.clock("2026-11-01T05:30:00Z").endsWith("-04:00"))
        assertTrue(time.clock("2026-11-01T06:30:00Z").endsWith("-05:00"))
        assertFalse(time.clock("2026-11-01T07:30:00Z").contains("-05:00"))
        assertEquals("Tomorrow", time.day(Instant.parse("2026-11-11T18:00:00Z").toEpochMilli()))
        assertTrue(time.range("2026-11-11T20:00:00Z", "2026-11-11T21:00:00Z").startsWith("Tomorrow · "))
        assertFalse(time.range("2026-11-10T20:00:00Z", "2026-11-10T21:00:00Z").contains("Today"))
    }

    @Test fun rulerUsesTheExactCellMappingAndSliversNeverLayOutText() {
        assertEquals(listOf(0f, 180f, 360f, 540f, 720f, 900f, 1080f), (0..6).map(::liveGuideTickX))
        assertFalse(liveGuideShowsText(2f))
        assertFalse(liveGuideShowsText(71.99f))
        assertTrue(liveGuideShowsText(72f))
        assertTrue(liveGuideShowsText(240f))
    }
}
