/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlin.test.*

/** AUTH-07 on desktop: another account on a saved server skips the address step (D-13). */
class DesktopAccountsControllerTest {
    private object NoDiscovery : ServerDiscovery {
        override suspend fun discover(): List<DiscoveredServer> = emptyList()
    }

    @Test fun addAccountOnASavedServerOpensItsSignInAndBackReturnsToTheChooser(): Unit = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { ex ->
            paths += ex.requestURI.path
            val body = if (ex.requestURI.path == "/Users/Public") """[{"Id":"u1","Name":"ux-mei"},{"Id":"u2","Name":"ux-tom"}]""" else "{}"
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(if (ex.requestURI.path == "/Users/Public") 200 else 404, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        http.start()
        try {
            val server = Server("srv", "Home Media", "http://127.0.0.1:${http.address.port}", "10.11.0")
            DesktopController(Files.createTempDirectory("pictureshow-desktop-accounts-").toFile(), discovery = NoDiscovery).use { c ->
                c.accounts()
                c.await { it.route == DesktopRoute.Accounts && !it.busy }
                c.addAccountOn(server)
                val signIn = c.await { it.route == DesktopRoute.SignIn && !it.busy && it.publicUsers.isNotEmpty() }
                assertEquals(server, signIn.server)
                assertEquals(listOf("ux-mei", "ux-tom"), signIn.publicUsers.map { it.name })
                assertNull(signIn.error)
                assertEquals(listOf("/Users/Public"), paths.toList(), "no address probe: the server is already known")
                c.back()
                c.await { it.route == DesktopRoute.Accounts }
            }
        } finally { http.stop(0) }
    }

    private suspend fun DesktopController.await(condition: (DesktopState) -> Boolean): DesktopState =
        withTimeout(10_000) { while (!condition(state.value)) delay(20); state.value }
}
