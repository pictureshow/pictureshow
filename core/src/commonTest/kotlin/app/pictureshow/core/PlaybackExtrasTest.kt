/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackExtrasTest {
    private val intro = MediaSegment("intro", SegmentType.Intro, 100_000_000, 200_000_000)
    private val outro = MediaSegment("credits", SegmentType.Outro, 900_000_000, 1_000_000_000)

    @Test
    fun implausibleSegmentsCannotSkipOrTriggerNextEpisode() {
        val overflowIntro = MediaSegment("bad", SegmentType.Intro, 0, 5_153_978_750_000)
        val overflowOutro = MediaSegment("bad-end", SegmentType.Outro, 42_949_722_950_000, 42_949_822_950_000)
        assertNull(activeSegment(listOf(overflowIntro), 0))
        assertNull(activeSegment(listOf(overflowOutro), overflowOutro.startMs))
        assertNull(creditsStartMs(4_294_982_295, listOf(overflowOutro)))
        for ((type, minutes) in listOf(
            SegmentType.Intro to 11,
            SegmentType.Recap to 11,
            SegmentType.Preview to 11,
            SegmentType.Commercial to 31,
            SegmentType.Outro to 31
        )) {
            val bad = MediaSegment("long", type, 0, minutes * 60L * 10_000_000)
            assertNull(activeSegment(listOf(bad), 0), "$type must not skip $minutes minutes")
            if (type == SegmentType.Outro) {
                assertNull(creditsStartMs(7_200_000, listOf(bad)))
                assertEquals(7_190_000, nextEpisodeTriggerMs(7_200_000, listOf(bad)))
            }
        }
    }

    @Test
    fun ordinarySegmentsRetainAskAutomaticOffAndCreditsBehavior() {
        for ((type, seconds) in listOf(
            SegmentType.Intro to 7,
            SegmentType.Recap to 90,
            SegmentType.Preview to 120,
            SegmentType.Commercial to 240,
            SegmentType.Outro to 600
        )) {
            val segment = MediaSegment("ordinary", type, 0, seconds * 10_000_000L)
            assertEquals(segment, activeSegment(listOf(segment), 1))
            assertEquals(segment, activeSegment(listOf(segment), 1, mapOf(type to SegmentMode.Automatic)))
            assertNull(activeSegment(listOf(segment), 1, mapOf(type to SegmentMode.Off)))
            assertNull(activeSegment(listOf(segment), segment.endMs))
            if (type == SegmentType.Outro) assertEquals(0, creditsStartMs(600_000, listOf(segment)))
        }
    }

    @Test
    fun activeSegmentUsesHalfOpenBoundariesAndIgnoresOffUnknownAndInvalidSegments() {
        val segments = listOf(intro.copy(type = SegmentType.Unknown), intro.copy(endTicks = 0), intro)
        assertNull(activeSegment(segments, 9_999))
        assertEquals(intro, activeSegment(segments, 10_000))
        assertEquals(intro, activeSegment(segments, 19_999))
        assertNull(activeSegment(segments, 20_000))
        assertNull(activeSegment(segments, 15_000, mapOf(SegmentType.Intro to SegmentMode.Off)))
        assertEquals(intro, activeSegment(segments, 15_000, mapOf(SegmentType.Intro to SegmentMode.Automatic)))
    }

    @Test
    fun nextEpisodeUsesOutroStartOtherwiseLastTenSecondsAndRequiresKnownDuration() {
        assertEquals(90_000, nextEpisodeTriggerMs(100_000, listOf(outro)))
        assertEquals(90_000, nextEpisodeTriggerMs(100_000, listOf(intro)))
        assertEquals(0, nextEpisodeTriggerMs(8_000, emptyList()))
        assertNull(nextEpisodeTriggerMs(0, listOf(outro)))
        assertEquals(90_000, nextEpisodeTriggerMs(100_000, listOf(outro.copy(startTicks = 2_000_000_000))))
    }

    @Test
    fun creditsStartComesOnlyFromAnOutroInsideTheItem() {
        assertEquals(90_000, creditsStartMs(100_000, listOf(intro, outro)))
        assertEquals(90_000, creditsStartMs(100_000, listOf(outro.copy(endTicks = 1_050_000_000))))
        assertNull(creditsStartMs(100_000, listOf(intro)))
        assertNull(creditsStartMs(100_000, listOf(outro.copy(startTicks = 2_000_000_000))))
        assertNull(creditsStartMs(100_000, listOf(outro.copy(endTicks = 0))))
    }

    @Test
    fun tilesUseMillisecondsRowMajorSheetsAndClampTheLastPartialSheet() {
        val info = TrickplayInfo(320, 180, 3, 2, 14, 1_000)
        assertEquals(TrickplayTile(info, 0, 0, 0), trickplayTile(info, -10))
        assertEquals(TrickplayTile(info, 0, 2, 1), trickplayTile(info, 5_999))
        assertEquals(TrickplayTile(info, 1, 0, 0), trickplayTile(info, 6_000))
        assertEquals(TrickplayTile(info, 2, 1, 0), trickplayTile(info, Long.MAX_VALUE))
        assertNull(trickplayTile(info.copy(interval = 0), 10))
        assertNull(trickplayTile(info.copy(tileWidth = Int.MAX_VALUE), 10))
    }
}
