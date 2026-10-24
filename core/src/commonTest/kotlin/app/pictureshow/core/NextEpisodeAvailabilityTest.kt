/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class NextEpisodeAvailabilityTest {
    private val account = Account(Server("server", "Test", "http://localhost/base", "12"), User("user", "Test"), "test-token")
    private val identity = ClientIdentity("TV", "device")

    @Test
    fun missingEpisodeFilterPrecedesAdjacencyRegardlessOfAccountDisplayPreference() = runTest {
        // Jellyfin includes virtual episodes for API keys and users who display missing episodes.
        // Filtering AFTER adjacency loses the available successor beyond the virtual placeholder.
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    assertEquals("/base/Shows/series/Episodes", request.url.encodedPath)
                    assertEquals("current", request.url.parameters["adjacentTo"])
                    assertEquals("user", request.url.parameters["userId"])
                    assertEquals("false", request.url.parameters["isMissing"])
                    val includesMissing = request.headers[HttpHeaders.Authorization]!!.contains("key-token")
                    val successor = if (!includesMissing || request.url.parameters["isMissing"] == "false") {
                        "available"
                    } else {
                        "virtual"
                    }
                    respond("""{"Items":[{"Id":"current","Type":"Episode"},{"Id":"$successor","Type":"Episode"}]}""")
                }
            }
        }
        try {
            for (token in listOf("user-token", "key-token")) {
                val result = PlaybackExtrasClient(http, identity).nextEpisode(
                    account.copy(accessToken = token),
                    MediaItem("current", type = "Episode", seriesId = "series")
                )
                assertEquals("available", assertIs<ClientResult.Success<MediaItem?>>(result).value?.id, token)
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun nextMatchesServerAdjacencyThroughInsertedSpecialAndSeasonBoundaryIncludingLastEpisode() = runTest {
        // The server's aired order places a special between regular episodes. Never re-sort by season/episode.
        val episodes = listOf(
            MediaItem("season-one-end", type = "Episode", seriesId = "series", parentIndexNumber = 1, indexNumber = 9),
            MediaItem("inserted-special", type = "Episode", seriesId = "series", parentIndexNumber = 0, indexNumber = 2),
            MediaItem("season-two-start", type = "Episode", seriesId = "series", parentIndexNumber = 2, indexNumber = 1),
            MediaItem("season-two-next", type = "Episode", seriesId = "series", parentIndexNumber = 2, indexNumber = 2)
        )
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    val index = episodes.indexOfFirst { it.id == request.url.parameters["adjacentTo"] }
                    val adjacent = episodes.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(episodes.size))
                    val items = adjacent.joinToString(",") {
                        """{"Id":"${it.id}","Type":"Episode","ParentIndexNumber":${it.parentIndexNumber},"IndexNumber":${it.indexNumber}}"""
                    }
                    respond("""{"Items":[$items]}""")
                }
            }
        }
        try {
            val client = PlaybackExtrasClient(http, identity)
            for ((index, episode) in episodes.withIndex()) {
                assertEquals(
                    episodes.getOrNull(index + 1)?.id,
                    assertIs<ClientResult.Success<MediaItem?>>(client.nextEpisode(account, episode)).value?.id,
                    "Adjacent successor for ${episode.id}"
                )
            }
        } finally {
            http.close()
        }
    }

    @Test
    fun diagnosticsExplainChoiceAndFailureWithoutAccountMetadataOrCredentials() = runTest {
        val logs = mutableListOf<String>()
        var calls = 0
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    calls++
                    if (calls == 1) {
                        respond(
                            """{"Items":[{"Id":"current","Name":"Private title","Type":"Episode"},{"Id":"next","Name":"Private title","Type":"Episode"}]}"""
                        )
                    } else {
                        respond("Private error body", io.ktor.http.HttpStatusCode.Unauthorized)
                    }
                }
            }
        }
        try {
            val client = PlaybackExtrasClient(http, identity, diagnostic = logs::add)
            val item = MediaItem("current", name = "Private title", type = "Episode", seriesId = "series")
            assertEquals("next", assertIs<ClientResult.Success<MediaItem?>>(client.nextEpisode(account, item)).value?.id)
            assertIs<ClientResult.Failure>(client.nextEpisode(account, item))
            val prefix = "nextEpisode current=current request=GET Shows/series/Episodes?" +
                "adjacentTo=current&isMissing=false&enableUserData=true&fields=Overview"
            assertEquals(
                listOf(
                    "$prefix returned=[current,next] chosen=next",
                    "$prefix returned=unavailable chosen=none error=EXPIRED"
                ),
                logs
            )
        } finally {
            http.close()
        }
    }
}
