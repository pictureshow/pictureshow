/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.playback.ObservedLiveWindow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LivePlayerReadoutTest {
    private val window = ObservedLiveWindow(100_000, 30_000, 27_000, true)

    @Test fun visibleReadoutUsesOneMeasuredEdgeDecisionForBothStrings() {
        assertEquals(LiveTimelineReadout("Live", ""), liveTimelineReadout(window, 26_000, false, false, true))
        assertEquals(LiveTimelineReadout("Live", ""), liveTimelineReadout(window, 25_000, false, false, true))
        assertEquals(LiveTimelineReadout("Paused · At live edge", ""), liveTimelineReadout(window, 25_000, true, false, true))
        assertEquals(LiveTimelineReadout("", "−0:03"), liveTimelineReadout(window, 24_000, false, false, true))
        assertEquals(LiveTimelineReadout("Paused", "−0:03"), liveTimelineReadout(window, 24_000, true, false, true))
        assertEquals("Paused · 3 sec behind live", liveTimelineLabel(true, true, false, 3_000))
    }

    @Test fun unavailableAndExpiredRangesNeverShowAnInventedDistanceOrLiveClaim() {
        assertEquals("Live position unavailable", liveTimelineLabel(false, true, false, null))
        assertEquals("Live position unavailable", liveTimelineLabel(true, true, false, null))
        assertEquals(LiveTimelineReadout("Time shift unavailable", ""), liveTimelineReadout(null, 0, false, false, false))
        assertEquals(
            LiveTimelineReadout("Live position unavailable", ""),
            liveTimelineReadout(window.copy(defaultPositionMs = -1), 26_000, false, false, true)
        )
        assertEquals(LiveTimelineReadout("Live position unavailable", ""), liveTimelineReadout(window, 31_000, false, false, true))
        assertEquals(LiveTimelineReadout("Paused · Buffer expired", ""), liveTimelineReadout(window, 27_000, true, true, true))
    }

    @Test fun forwardGuardUsesTheActualEdgeWithoutChangingOrdinaryPlayback() {
        assertFalse(liveForwardSeekAllowed(true, window, 27_000, false))
        assertFalse(liveForwardSeekAllowed(true, window, 25_000, false))
        assertTrue(liveForwardSeekAllowed(true, window, 24_000, false))
        assertFalse(liveForwardSeekAllowed(true, window, 24_000, true))
        assertFalse(liveForwardSeekAllowed(true, null, 24_000, false))
        assertFalse(liveForwardSeekAllowed(true, window, 31_000, false), "Out-of-window positions do not authorize a forward request")
        assertFalse(liveForwardSeekAllowed(true, window.copy(seekable = false), 24_000, false))
        assertTrue(liveForwardSeekAllowed(false, null, 24_000, true), "VOD retains its existing seek rules")
    }
}
