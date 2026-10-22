/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class PlaybackExtrasClientTest {
    private val account = Account(Server("s", "Test", "http://localhost/jellyfin", "12"), User("u", "Test"), "fictional-token")
    private val identity = ClientIdentity("TV", "device")

    @Test
    fun extrasParseNullableMetadataAndFutureSegmentTypesAndSendAuthenticatedBasePaths() = runTest {
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    seen += request.url.encodedPath
                    assertTrue(request.headers[HttpHeaders.Authorization]!!.contains(account.accessToken))
                    assertFalse(request.url.toString().contains(account.accessToken))
                    when {
                        "MediaSegments" in request.url.encodedPath -> respond(
                            """{"Items":[{"Id":"i","Type":"Intro","StartTicks":10000000,"EndTicks":50000000},{"Type":"FutureType"}]}"""
                        )
                        else -> respond(
                            """{"Id":"film","Chapters":[{"StartPositionTicks":50000000,"Name":null,"ImageTag":"image"}],"Trickplay":{"source":{"320":{"Width":320,"Height":180,"TileWidth":10,"TileHeight":10,"ThumbnailCount":50,"Interval":1000}}}}"""
                        )
                    }
                }
            }
        }
        val client = PlaybackExtrasClient(http, identity)
        val response = client.item(account, "film")
        val item = assertIs<ClientResult.Success<MediaItem>>(response, response.toString()).value
        assertEquals(5_000, item.chapters.single().startMs)
        assertEquals("", item.chapters.single().name)
        assertEquals(320, chooseTrickplay(item, "source", 280)?.width)
        assertNull(chooseTrickplay(item, "other-source", 280))
        val segments = assertIs<ClientResult.Success<List<MediaSegment>>>(client.segments(account, "film")).value
        assertEquals(SegmentType.Intro, segments[0].type)
        assertEquals(SegmentType.Unknown, segments[1].type)
        assertEquals(listOf("/jellyfin/Items/film", "/jellyfin/MediaSegments/film"), seen)
        http.close()
    }

    @Test
    fun adjacentEpisodeUsesCurrentItemAndServerOrderAndRetainsResumeAcrossSeasons() = runTest {
        val item = MediaItem("current", type = "Episode", seriesId = "series")
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("/jellyfin/Shows/series/Episodes", request.url.encodedPath)
                    assertEquals("current", request.url.parameters["adjacentTo"])
                    assertEquals("u", request.url.parameters["userId"])
                    respond(
                        """{"Items":[{"Id":"previous","Type":"Episode"},{"Id":"current","Type":"Episode"},{"Id":"next","Type":"Episode","ParentIndexNumber":2,"UserData":{"PlaybackPositionTicks":123000000}}]}"""
                    )
                }
            }
        }
        val client = PlaybackExtrasClient(http, identity)
        val response = client.nextEpisode(account, item)
        val next = assertIs<ClientResult.Success<MediaItem?>>(response, response.toString()).value!!
        assertEquals("next", next.id)
        assertEquals(123000000, next.userData.playbackPositionTicks)
        assertNull(assertIs<ClientResult.Success<MediaItem?>>(client.nextEpisode(account, MediaItem("movie"))).value)
        http.close()
    }

    @Test
    fun missingOptionalEndpointFailsSafelyAndImagesKeepBasePathAndSourceWithoutTokens() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond("", HttpStatusCode.NotFound) }
            }
        }
        assertEquals(
            ErrorKind.SERVER,
            assertIs<ClientResult.Failure>(PlaybackExtrasClient(http, identity).segments(account, "film")).error.kind
        )
        val url = Url(trickplaySheetUrl(account.server, "film", "source", 320, 2))
        assertEquals("/jellyfin/Videos/film/Trickplay/320/2.jpg", url.encodedPath)
        assertEquals("source", url.parameters["mediaSourceId"])
        assertFalse(url.toString().contains("token"))
        val chapter = Url(chapterImageUrl(account.server, "film", 3, "tag"))
        assertEquals("/jellyfin/Items/film/Images/Chapter/3", chapter.encodedPath)
        assertEquals("tag", chapter.parameters["tag"])
        http.close()
    }
}
