/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class CatalogFreshnessHttpTest {
    private val account = Account(Server("s", "Test", "https://example.test/base", "12"), User("u", "Viewer"), "token")

    @Test
    fun parentDetailsUseListUserDataWhileKeepingFullDetailMetadata() = runTest {
        for (type in listOf("Series", "Season", "BoxSet")) {
            val calls = mutableListOf<String>()
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        calls += request.url.encodedPath
                        respond(
                            if (request.url.encodedPath.endsWith("/parent")) {
                                """{"Id":"parent","Type":"$type","Overview":"Full details","UserData":{"UnplayedItemCount":6}}"""
                            } else {
                                assertEquals("parent", request.url.parameters["ids"])
                                """{"Items":[{"Id":"parent","UserData":{"UnplayedItemCount":4,"IsFavorite":true}}]}"""
                            },
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    }
                }
            }
            try {
                val result =
                    assertIs<ClientResult.Success<MediaItem>>(JellyfinClient(http, ClientIdentity("TV", "device")).item(account, "parent"))
                assertEquals(4, result.value.userData.unplayedItemCount, type)
                assertEquals("Full details", result.value.overview)
                assertEquals(listOf("/base/Items/parent", "/base/Items"), calls)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun parentProjectionFailureIsVisibleInsteadOfReturningTheStaleCount() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    if (request.url.encodedPath.endsWith("/parent")) {
                        respond(
                            """{"Id":"parent","Type":"BoxSet","UserData":{"UnplayedItemCount":6}}""",
                            headers = headersOf(HttpHeaders.ContentType, "application/json")
                        )
                    } else {
                        respond("{}", io.ktor.http.HttpStatusCode.ServiceUnavailable)
                    }
                }
            }
        }
        try {
            val result = JellyfinClient(http, ClientIdentity("TV", "device")).item(account, "parent")
            assertEquals(ErrorKind.SERVER, assertIs<ClientResult.Failure>(result).error.kind)
        } finally {
            http.close()
        }
    }

    @Test
    fun cardSnapshotsUseExplicitIdsWithoutParentOrPageFilters() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("/base/Items", request.url.encodedPath)
                    assertEquals("series,season,collection", request.url.parameters["ids"])
                    assertNull(request.url.parameters["parentId"])
                    assertNull(request.url.parameters["startIndex"])
                    assertNull(request.url.parameters["includeItemTypes"])
                    assertEquals("true", request.url.parameters["enableUserData"])
                    assertEquals("3", request.url.parameters["limit"])
                    respond("""{"Items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }
        try {
            assertIs<ClientResult.Success<ItemPage>>(
                JellyfinClient(http, ClientIdentity("TV", "device")).items(
                    account,
                    BrowseQuery("", limit = 3, ids = listOf("series", "season", "collection"))
                )
            )
        } finally {
            http.close()
        }
    }
}
