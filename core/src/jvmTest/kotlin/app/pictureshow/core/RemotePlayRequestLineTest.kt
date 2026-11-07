/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.core

import app.pictureshow.core.control.REMOTE_PLAY_ITEM_LIMIT
import app.pictureshow.core.control.RemoteClient
import app.pictureshow.core.control.RemoteCommand
import app.pictureshow.core.control.RemoteTarget
import app.pictureshow.core.control.remoteRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * REM-03: Play's ids travel in the query, and Jellyfin's Kestrel answers 414 to a request line over 8 KB (measured on
 * 10.10.7: 220 ids answered, 232 got 414). This measures the line Ktor really builds, encoding included.
 */
class RemotePlayRequestLineTest {
    @Test fun aCappedPlayFitsKestrelsRequestLineBehindALongBasePath() = runBlocking {
        var line = 0
        var ids = 0
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    line = "POST ${request.url.encodedPathAndQuery} HTTP/1.1".length
                    ids = request.url.parameters.getValue("itemIds").split(",").size
                    respond("", HttpStatusCode.NoContent)
                }
            }
        }
        // A reverse proxy's base path, a long session id, a 64-bit position and a start index: the worst case we send.
        val account = Account(
            Server("server", "Server", "https://media.example.test/a/reverse/proxy/base/path/for/jellyfin", "12.4.0"),
            User("user", "Viewer"),
            "token"
        )
        val target = RemoteTarget(session = "f".repeat(64), device = "tv", name = "TV", viewer = "user", sameViewer = true)
        val items = (1..1_200).map { "%032x".format(it) }
        val wire = remoteRequest(target, RemoteCommand("play-now", items, ticks = Long.MAX_VALUE, index = 600))
        val status = RemoteClient(http, ClientIdentity("Phone", "device")).send(account, requireNotNull(wire.request))
        http.close()
        assertEquals(204, status)
        assertEquals(REMOTE_PLAY_ITEM_LIMIT, ids)
        assertTrue(line <= 8_192 - 1_024, "request line $line bytes leaves a kilobyte under Kestrel's 8 KB")
    }
}
