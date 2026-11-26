/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SearchPresentationTest {
    @Test fun groupsKeepCanonicalOrderBoundEachTypeAndKeepSuccessWhenAnotherTypeFails() {
        val result = searchRows(
            mapOf(
                SearchKind.SONGS to ClientResult.Success(ItemPage(listOf(MediaItem("song", "Cloud", "Audio")))),
                SearchKind.SHOWS to ClientResult.Failure(ClientError(ErrorKind.NETWORK, "Offline")),
                SearchKind.MOVIES to ClientResult.Success(ItemPage((1..30).map { MediaItem("$it", "Film $it", "Movie") }))
            )
        )
        assertEquals(listOf("Movies", "Songs"), result.map { it.title })
        assertEquals(24, result.first().items.size)
        assertEquals("song", result.last().items.single().id)
    }

    @Test fun aKindWhoseTitleIsTheQueryLeadsWhateverArrivedFirst() {
        val result = searchRows(
            mapOf(
                SearchKind.SHOWS to ClientResult.Success(ItemPage(listOf(MediaItem("s", "The Americans", "Series")))),
                SearchKind.MOVIES to ClientResult.Success(ItemPage(listOf(MediaItem("m", "The Americanization of Emily", "Movie"))))
            ),
            "the americans"
        )
        assertEquals(listOf("Shows", "Movies"), result.map { it.title })
        val unordered = searchRows(
            mapOf(
                SearchKind.SHOWS to ClientResult.Success(ItemPage(listOf(MediaItem("s", "The Americans", "Series")))),
                SearchKind.MOVIES to ClientResult.Success(ItemPage(listOf(MediaItem("m", "The Americanization of Emily", "Movie"))))
            )
        )
        assertEquals(listOf("Movies", "Shows"), unordered.map { it.title })
    }
}
