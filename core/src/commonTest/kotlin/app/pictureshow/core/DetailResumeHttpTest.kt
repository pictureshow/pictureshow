/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/** Fictional shape of Jellyfin 12's individual versus batch UserData projection. */
class DetailResumeHttpTest {
    @Test
    fun detailsUseTheSameUserDataProjectionAsResumeIncludingClearedAndWatchedPositions() = runTest {
        val account = Account(Server("server", "Test", "https://example.test/base", "12.0"), User("viewer", "Viewer"), "token")
        for (batchData in listOf(
            """{"PlaybackPositionTicks":1947850000,"Played":false,"PlayedPercentage":6.3,"IsFavorite":true}""",
            """{"PlaybackPositionTicks":0,"Played":true,"PlayedPercentage":100,"IsFavorite":false}""",
            """{"PlaybackPositionTicks":0,"Played":false,"IsFavorite":false}"""
        )) {
            val calls = mutableListOf<String>()
            val stale = """{"Id":"special","Name":"Fictional special","Type":"Episode","ParentIndexNumber":0,
                "RunTimeTicks":30900000000,"Overview":"Full detail metadata","UserData":{"PlaybackPositionTicks":1,"Played":false}}"""
            val entry = """{"Id":"special","Type":"Episode","UserData":$batchData}"""
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        assertEquals(HttpMethod.Get, request.method)
                        assertEquals("viewer", request.url.parameters["userId"])
                        assertTrue(request.headers[HttpHeaders.Authorization]!!.contains("Token=\"token\""))
                        val path = request.url.encodedPath
                        calls += path
                        val body = when (path) {
                            "/base/Items/special" -> stale
                            "/base/Items" -> {
                                assertEquals("special", request.url.parameters["ids"])
                                """{"Items":[$entry],"TotalRecordCount":1}"""
                            }
                            "/base/UserItems/Resume" -> """{"Items":[$entry],"TotalRecordCount":1}"""
                            else -> error("Unexpected endpoint $path")
                        }
                        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            }
            try {
                val client = JellyfinClient(http, ClientIdentity("TV", "device"))
                val detail = assertIs<ClientResult.Success<MediaItem>>(client.item(account, "special")).value
                val resume = assertIs<ClientResult.Success<ItemPage>>(client.resume(account)).value.items.single()
                assertEquals(resume.userData, detail.userData)
                assertEquals("Full detail metadata", detail.overview)
                assertEquals(listOf("/base/Items/special", "/base/Items", "/base/UserItems/Resume"), calls)
            } finally {
                http.close()
            }
        }
    }

    @Test
    fun emptyBatchCannotReplaceDetailsWithAnotherItemsState() = runTest {
        val http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { request ->
                    respond(
                        if (request.url.encodedPath.endsWith("/film")) {
                            """{"Id":"film","Type":"Movie","UserData":{"PlaybackPositionTicks":1200000000}}"""
                        } else {
                            """{"Items":[{"Id":"other","UserData":{"PlaybackPositionTicks":9999999999}}]}"""
                        },
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val client = JellyfinClient(http, ClientIdentity("TV", "device"))
            val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
            assertEquals(
                1200000000L,
                assertIs<ClientResult.Success<MediaItem>>(client.item(account, "film")).value.userData.playbackPositionTicks
            )
        } finally {
            http.close()
        }
    }

    @Test
    fun unavailableBatchKeepsUsableDetailsButRevokedCredentialsStillExpire() = runTest {
        val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
        for (status in listOf(io.ktor.http.HttpStatusCode.ServiceUnavailable, io.ktor.http.HttpStatusCode.Unauthorized)) {
            val http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { request ->
                        if (request.url.encodedPath.endsWith("/film")) {
                            respond(
                                """{"Id":"film","Type":"Movie","Overview":"Available details"}""",
                                headers = headersOf(HttpHeaders.ContentType, "application/json")
                            )
                        } else {
                            respond("{}", status)
                        }
                    }
                }
            }
            try {
                val result = JellyfinClient(http, ClientIdentity("TV", "device")).item(account, "film")
                if (status == io.ktor.http.HttpStatusCode.Unauthorized) {
                    assertEquals(ErrorKind.EXPIRED, assertIs<ClientResult.Failure>(result).error.kind)
                } else {
                    assertEquals("Available details", assertIs<ClientResult.Success<MediaItem>>(result).value.overview)
                }
            } finally {
                http.close()
            }
        }
    }
}
