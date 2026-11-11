/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogAnnouncementTest {
    @Test fun episodeIdentityAndWatchedStateAreSpokenOnce() {
        assertEquals(
            "An Episode, A Series, Season 2, Episode 3, Watched",
            catalogAnnouncement(
                MediaItem(
                    "episode",
                    "An Episode",
                    "Episode",
                    seriesName = "A Series",
                    parentIndexNumber = 2,
                    indexNumber = 3,
                    userData = ItemUserData(played = true, playbackPositionTicks = 600_000_000)
                )
            )
        )
    }

    @Test fun emptyContainerDoesNotAnnounceVacuousWatchedResumeOrCount() {
        assertEquals(
            "Empty collection",
            catalogAnnouncement(
                MediaItem(
                    "empty",
                    "Empty collection",
                    "BoxSet",
                    childCount = 0,
                    userData = ItemUserData(played = true, playbackPositionTicks = 600_000_000, unplayedItemCount = 0)
                )
            )
        )
    }

    @Test fun seasonUnplayedCountAndFavoriteAreUsefulState() {
        assertEquals(
            "Season 2, 4 unplayed, Favorite",
            catalogAnnouncement(
                MediaItem("season", "Season 2", "Season", userData = ItemUserData(unplayedItemCount = 4, isFavorite = true))
            )
        )
    }
}
