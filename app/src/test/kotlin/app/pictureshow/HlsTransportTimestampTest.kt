/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pictureshow

import androidx.media3.common.util.TimestampAdjuster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise the shared 90 kHz clock used by HlsMediaChunk, TsExtractor and its PES readers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = android.app.Application::class)
class HlsTransportTimestampTest {
    private val wrap = 1L shl 33

    @Test
    fun negativeAudioPrerollWrapsWithoutLosingItsOffsetToVideo() {
        val clock = TimestampAdjuster(TimestampAdjuster.MODE_SHARED)
        clock.sharedInitializeOrWait(true, 0, 0)
        // A made-up 2048-sample delay at 48 kHz is -3840 ticks on the TS 90 kHz clock.
        assertEquals(0L, clock.adjustTsTimestamp(wrap - 3840))
        val videoStart = clock.adjustTsTimestamp(0)
        assertTrue(videoStart in 42_666L..42_667L)
        // Interleaved audio can precede the previous video's PTS, including across the wrap boundary.
        assertTrue(clock.adjustTsTimestamp(wrap - 1920) in 21_333L..21_334L)
        assertEquals(videoStart, clock.adjustTsTimestamp(0))
        assertEquals(videoStart + 1_000_000, clock.adjustTsTimestamp(90_000))
    }

    @Test
    fun segmentTimelineAnchorAndLaterPtsShareTheSameOffset() {
        val clock = TimestampAdjuster(TimestampAdjuster.MODE_SHARED)
        clock.sharedInitializeOrWait(true, 30_000_000, 0)
        assertEquals(30_000_000L, clock.adjustTsTimestamp(wrap - 3840))
        val firstVideo = clock.adjustTsTimestamp(0)
        assertTrue(firstVideo in 30_042_666L..30_042_667L)
        assertEquals(firstVideo + 15_000_000, clock.adjustTsTimestamp(15 * 90_000L))
        // Seeking back in the same discontinuity does not manufacture a 26-hour wrap jump.
        assertEquals(firstVideo + 5_000_000, clock.adjustTsTimestamp(5 * 90_000L))
    }
}
