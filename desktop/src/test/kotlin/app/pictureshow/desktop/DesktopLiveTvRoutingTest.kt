/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** D-5: opening the Live TV view never runs a library browse that can only come back empty. */
class DesktopLiveTvRoutingTest {
    @Test fun liveTvViewOpensItsOwnRouteWithoutAnItemsQuery(): Unit = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { e ->
            queries.add(e.requestURI.path + "?" + e.requestURI.rawQuery.orEmpty())
            val body = if (e.requestURI.path == "/Users/Me") """{"Id":"viewer","Name":"Viewer"}""" else """{"Items":[],"TotalRecordCount":0}"""
            val bytes = body.toByteArray(); e.responseHeaders.set("Content-Type", "application/json")
            e.sendResponseHeaders(200, bytes.size.toLong()); e.responseBody.write(bytes); e.close()
        }
        server.start()
        try {
            val store = AccountStore(object : TextPersistence {
                var value: String? = null
                override suspend fun read() = value
                override suspend fun write(value: String) { this.value = value }
            })
            store.remember(Account(Server("server", "Demo cinema", "http://127.0.0.1:${server.address.port}", "12.4.0"), User("viewer", "Viewer"), "token"))
            DesktopController(Files.createTempDirectory("pictureshow-livetv-test-").toFile(), store = store).use { c ->
                c.start(); withTimeout(10_000) { c.state.first { it.route == DesktopRoute.Home && !it.busy } }
                queries.clear()
                val live = MediaItem("live", "Live TV", "UserView", collectionType = "livetv", isFolder = true)
                c.open(live)
                val state = withTimeout(10_000) { c.state.first { it.route == DesktopRoute.LiveTv && !it.busy } }
                assertEquals(live, state.library)
                assertFalse(queries.any { it.contains("parentId=live") }, "No browse of the Live TV view: $queries")
                c.loadMore()
                assertFalse(queries.any { it.contains("parentId=live") }, "No paging of the Live TV view: $queries")
                // An ordinary library still browses.
                c.open(MediaItem("films", "Films", "CollectionFolder", collectionType = "movies", isFolder = true))
                withTimeout(10_000) { c.state.first { it.route == DesktopRoute.Library && it.library?.id == "films" && !it.busy } }
                assertEquals(true, queries.any { it.contains("films") }, "$queries")
            }
        } finally { server.stop(0) }
    }
}
