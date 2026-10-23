/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/** All fixture content is invented; captures from an actual library are not distributed. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchClientTest {
    private fun TestScope.engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = MockEngine(
        MockEngineConfig().apply {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler(handler)
        }
    )
    private val account =
        Account(
            Server("screen", "Test server", "https://media.example.test/base/jellyfin/", "12.0.0"),
            User("viewer", "Test viewer"),
            "test-token"
        )
    private val identity = ClientIdentity("TV", "test-device")
    private val term = "cloud \"orb\" & # 日本語"

    @Test
    fun perTypeAndPeopleRequestsKeepBasePathEncodeTermAndIncludeEveryParameter() = runTest {
        SearchKind.entries.forEach { kind ->
            val http = HttpClient(
                engine { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertEquals("/base/jellyfin/" + if (kind == SearchKind.PEOPLE) "Persons" else "Items", request.url.encodedPath)
                    val expected = mutableMapOf("userId" to "viewer", "searchTerm" to term, "limit" to "24")
                    if (kind !=
                        SearchKind.PEOPLE
                    ) {
                        expected +=
                            mapOf(
                                "recursive" to "true",
                                "includeItemTypes" to kind.itemType,
                                "enableTotalRecordCount" to "true",
                                "fields" to "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount"
                            )
                    }
                    assertEquals(expected, request.url.parameters.entries().associate { it.key to it.value.single() })
                    assertTrue(!request.url.encodedQuery.contains(" "))
                    assertTrue(request.url.encodedQuery.contains("%26"))
                    assertTrue(request.url.encodedQuery.contains("%23"))
                    assertTrue(request.url.encodedQuery.contains("%22"))
                    assertTrue(request.url.encodedQuery.contains("%E6%97%A5"))
                    assertEquals("", request.url.fragment)
                    assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("test-token"))
                    respond(
                        """{"Items":[],"TotalRecordCount":0,"StartIndex":0}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            )
            try {
                assertIs<ClientResult.Success<ItemPage>>(JellyfinClient(http, identity).search(account, "  $term  ", kind))
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun personItemsAndPersonDetailUseCorrectPathsAndPaging() = runTest {
        val http = HttpClient(
            engine { request ->
                assertEquals(HttpMethod.Get, request.method)
                if (request.url.encodedPath.endsWith("/Items")) {
                    assertEquals("/base/jellyfin/Items", request.url.encodedPath)
                    assertEquals(
                        mapOf(
                            "userId" to "viewer", "personIds" to "person & #", "recursive" to "true", "includeItemTypes" to "Movie,Series",
                            "sortBy" to "ProductionYear,SortName", "sortOrder" to "Descending", "startIndex" to "60", "limit" to "60",
                            "enableTotalRecordCount" to "true",
                            "fields" to "PrimaryImageAspectRatio,ProductionYear,Overview,Genres,ChildCount,RecursiveItemCount"
                        ),
                        request.url.parameters.entries().associate { it.key to it.value.single() }
                    )
                    respond(
                        """{"Items":[{"Id":"story","Name":"Copper Observatory","Type":"Movie"}],"TotalRecordCount":61,"StartIndex":60}"""
                    )
                } else {
                    assertEquals("/base/jellyfin/Items/person%20&%20%23", request.url.encodedPath)
                    assertEquals(mapOf("userId" to listOf("viewer")), request.url.parameters.entries().associate { it.key to it.value })
                    respond(
                        """{"Id":"person & #","Name":"Aster Quill","Type":"Person",
                        "Overview":"Invented performer.","ImageTags":{"Primary":"portrait"},"ExternalUrls":[]}"""
                    )
                }
            }
        )
        try {
            val client = JellyfinClient(http, identity)
            assertEquals(60, assertIs<ClientResult.Success<ItemPage>>(client.personItems(account, "person & #", 60)).value.startIndex)
            assertEquals(
                "portrait",
                assertIs<ClientResult.Success<MediaItem>>(client.item(account, "person & #")).value.imageTags["Primary"]
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun parsesAllSearchShapesIncludingMissingNullAndUnknownOptionalFields() = runTest {
        val fixtures = mapOf(
            SearchKind.MOVIES to
                """{"Id":"movie","Name":"Copper Observatory","Type":"Movie","ProductionYear":2024,
                "ImageTags":{"Primary":"poster"},"Overview":"An invented observatory.","PrimaryImageAspectRatio":0.67,"UserData":{"IsFavorite":true}}""",
            SearchKind.SHOWS to
                """{"Id":"show","Name":"Lantern Archipelago","Type":"Series","IsFolder":true,
                "ImageTags":{"Primary":"show-art"},"UserData":{"UnplayedItemCount":3}}""",
            SearchKind.EPISODES to
                """{"Id":"episode","Name":"The Clock Reef","Type":"Episode",
                "SeriesName":"Lantern Archipelago","SeriesId":"show","ParentIndexNumber":1,"IndexNumber":2,
                "ParentThumbItemId":"show","ParentThumbImageTag":"thumb","ParentBackdropItemId":"show","ParentBackdropImageTags":["backdrop"],
                "SeriesPrimaryImageTag":"poster","UserData":{"PlaybackPositionTicks":30000000}}""",
            SearchKind.COLLECTIONS to """{"Id":"collection","Type":"BoxSet","Name":"Paper Voyages","IsFolder":true}""",
            SearchKind.PEOPLE to
                """{"Id":"person","Name":"Aster Quill","Type":"Person","ImageTags":null,"Overview":null,"UserData":null,"ChannelId":null}"""
        )
        fixtures.forEach { (kind, fixture) ->
            val http = HttpClient(engine { respond("""{"Items":[$fixture],"TotalRecordCount":1,"StartIndex":0}""") })
            try {
                val page = assertIs<ClientResult.Success<ItemPage>>(JellyfinClient(http, identity).search(account, term, kind)).value
                val item = page.items.single()
                assertEquals(kind.itemType, item.type)
                when (kind) {
                    SearchKind.MOVIES -> {
                        assertEquals(2024, item.productionYear)
                        assertTrue(item.userData.isFavorite)
                    }
                    SearchKind.SHOWS -> assertEquals(3, item.userData.unplayedItemCount)
                    SearchKind.EPISODES -> {
                        assertEquals("S1:E2", episodeLabel(item))
                        assertEquals("Lantern Archipelago", item.seriesName)
                        assertEquals("thumb", item.parentThumbImageTag)
                    }
                    SearchKind.COLLECTIONS -> assertTrue(item.isFolder)
                    SearchKind.PEOPLE -> {
                        assertTrue(item.imageTags.isEmpty())
                        assertEquals(null, item.overview)
                    }
                }
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun undersizedQueriesNeverReachNetworkAndEmptyPagesParse() = runTest {
        var calls = 0
        val http = HttpClient(
            engine {
                calls++
                respond("""{"Items":[],"TotalRecordCount":0}""")
            }
        )
        try {
            val client = JellyfinClient(http, identity)
            client.search(account, " x ", SearchKind.MOVIES)
            assertEquals(0, calls)
            assertTrue(assertIs<ClientResult.Success<ItemPage>>(client.search(account, "xx", SearchKind.COLLECTIONS)).value.items.isEmpty())
            assertEquals(1, calls)
        } finally {
            http.close()
        }
    }
}
