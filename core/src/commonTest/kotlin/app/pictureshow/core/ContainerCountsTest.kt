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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ContainerCountsTest {
    @Test
    fun onlyConfirmedEmptyContainersLoseWatchedState() {
        for (type in listOf("BoxSet", "Playlist", "Folder", "CollectionFolder", "Series", "Season")) {
            val item = MediaItem("item", type = type, userData = ItemUserData(played = true))
            assertTrue(item.isWatched, "Unknown cardinality retains server behavior for $type")
            for (empty in listOf(item.copy(childCount = 0), item.copy(recursiveItemCount = 0))) {
                assertTrue(empty.isEmptyContainer)
                assertFalse(empty.isWatched)
            }
            assertTrue(item.copy(childCount = 0, recursiveItemCount = 2).isWatched)
            assertTrue(item.copy(childCount = 2, recursiveItemCount = 0).isWatched)
            assertTrue(item.copy(childCount = 2).isWatched)
        }
        assertTrue(MediaItem("movie", type = "Movie", childCount = 0, userData = ItemUserData(played = true)).isWatched)
    }

    @Test
    fun catalogAndSearchRequestCountsAndDecodeZeroWithoutTreatingOmittedAsEmpty() = runTest {
        val account = Account(Server("s", "Server", "https://example.test", "12.0.0"), User("u", "Viewer"), "fictional")
        val requestedFields = mutableListOf<List<String>>()
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    requestedFields += request.url.parameters["fields"].orEmpty().split(',')
                    respond(
                        """{"Items":[{"Id":"empty","Type":"BoxSet","ChildCount":0,"RecursiveItemCount":0,"UserData":{"Played":true}},
                {"Id":"unknown","Type":"BoxSet"}],"TotalRecordCount":2}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val client = JellyfinClient(http, ClientIdentity("TV", "device"))
            val pages = listOf(client.items(account, BrowseQuery("library")), client.search(account, "empty", SearchKind.COLLECTIONS))
            assertEquals(2, requestedFields.size)
            for (fields in requestedFields) {
                assertTrue("ChildCount" in fields, "Every card request must ask for container cardinality")
                assertTrue("RecursiveItemCount" in fields)
            }
            for (result in pages) {
                val items = assertIs<ClientResult.Success<ItemPage>>(result).value.items
                assertEquals(0, items[0].childCount)
                assertEquals(0, items[0].recursiveItemCount)
                assertNull(items[1].childCount)
                assertNull(items[1].recursiveItemCount)
            }
        } finally {
            http.close()
        }
    }
}
