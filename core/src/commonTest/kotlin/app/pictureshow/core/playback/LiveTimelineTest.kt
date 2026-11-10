/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTimelineTest {
    @Test fun guideDurationCannotExpandObservedBuffer() {
        val window = ObservedLiveWindow(100_000, 30_000, 27_000, true)
        assertTrue(window.contains(0))
        assertTrue(window.contains(30_000))
        assertFalse(window.contains(30_001))
        assertFalse(window.contains(-1))
        assertEquals(27_000L, window.goLivePosition())
    }

    @Test fun slidingRangeExpiresPausedAbsolutePositionWithoutInventingResume() {
        val next = ObservedLiveWindow(120_000, 30_000, 27_000, true)
        assertTrue(next.pausedPositionExpired(119_999))
        assertFalse(next.pausedPositionExpired(120_000))
        assertFalse(next.pausedPositionExpired(150_000))
        assertTrue(next.pausedPositionExpired(150_001))
        assertFalse(next.pausedPositionExpired(null))
    }

    @Test fun unknownOrNonseekableWindowCannotPromiseTimeShift() {
        assertFalse(ObservedLiveWindow(0, 30_000, 27_000, false).contains(2_000))
        assertFalse(ObservedLiveWindow(0, 0, 0, true).canTimeShift)
        assertNull(ObservedLiveWindow(0, 0, 0, true).goLivePosition())
        assertNull(ObservedLiveWindow(0, 30_000, 30_001, true).goLivePosition())
    }
}
