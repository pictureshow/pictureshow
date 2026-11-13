/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedDetailChoiceTest {
    @Test fun seriesUsesNextUpBeforeUnplayedAndSeasonIgnoresUnrelatedNextUp() {
        val played = MediaItem("played", type = "Episode", userData = ItemUserData(played = true))
        val unplayed = MediaItem("unplayed", type = "Episode")
        val next = MediaItem("next", type = "Episode")
        val episodes = listOf(played, unplayed)
        assertEquals(next, detailPlayItem(MediaItem("series", type = "Series"), listOf(next), episodes))
        assertEquals(unplayed, detailPlayItem(MediaItem("season", type = "Season"), listOf(next), episodes))
        assertEquals(played, detailPlayItem(MediaItem("season", type = "Season"), emptyList(), listOf(played)))
        assertNull(detailPlayItem(MediaItem("folder", type = "Folder"), listOf(next), episodes))
        assertNull(detailPlayItem(MediaItem("empty", type = "Series"), emptyList(), emptyList()))
    }

    @Test fun playableLeavesKeepTheirExactFreshMetadata() {
        listOf("Movie", "Episode", "Video").forEach { type ->
            val item = MediaItem("fresh", type = type, userData = ItemUserData(playbackPositionTicks = 123_000))
            assertEquals(item, detailPlayItem(item, emptyList(), emptyList()))
        }
    }
}
