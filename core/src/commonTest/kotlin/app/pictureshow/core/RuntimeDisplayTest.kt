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
import kotlinx.coroutines.test.runTest

class RuntimeDisplayTest {
    @Test
    fun implausibleDurationsAreOmittedWithoutChangingOrdinaryOrBoundaryLabels() {
        assertEquals("", runtimeLabel(42_949_822_950_000))
        assertEquals("", runtimeLabel(Long.MAX_VALUE))
        assertEquals("", runtimeLabel(864_000_000_001))
        assertEquals("24h 0m", runtimeLabel(864_000_000_000))
        assertEquals("10h 30m", runtimeLabel(378_000_000_000))
        assertEquals("1h 2m", runtimeLabel(37_230_000_000))
        assertEquals("1m", runtimeLabel(600_000_000))
        assertEquals("1m", runtimeLabel(1))
        assertEquals("", runtimeLabel(null))
        assertEquals("", runtimeLabel(0))
        assertEquals("", runtimeLabel(-1))
    }

    @Test
    fun detailAndCardResponsesOmitOverflowRuntimeAndDoNotInventDurationFromChapters() = runTest {
        val item = """{"Id":"film","Name":"Fictional sample","Type":"Movie","RunTimeTicks":42949822950000,
            "MediaSources":[{"Id":"source","RunTimeTicks":42949822950000}],
            "Chapters":[{"StartPositionTicks":0,"Name":"Start"},{"StartPositionTicks":450000000,"Name":"Final chapter"}]}"""
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        if (request.url.encodedPath.endsWith("/film")) item else """{"Items":[$item]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
        }
        try {
            val account = Account(Server("server", "Test", "https://example.test", "12.0"), User("viewer", "Viewer"), "token")
            val client = JellyfinClient(http, ClientIdentity("TV", "device"))
            val detail = assertIs<ClientResult.Success<MediaItem>>(client.item(account, "film")).value
            val card = assertIs<ClientResult.Success<ItemPage>>(client.items(account, BrowseQuery("movies"))).value.items.single()
            assertEquals(42_949_822_950_000L, detail.runTimeTicks, "Keep raw server metadata for protocol uses")
            assertEquals(2, detail.chapters.size)
            assertEquals("", runtimeLabel(detail.runTimeTicks))
            assertEquals("", runtimeLabel(card.runTimeTicks))
            assertEquals("", runtimeLabel(detail.copy(runTimeTicks = null).runTimeTicks))
        } finally {
            http.close()
        }
    }
}
