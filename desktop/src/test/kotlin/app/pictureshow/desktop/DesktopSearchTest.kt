/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package app.pictureshow.desktop

import app.pictureshow.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.input.key.*
import androidx.compose.ui.test.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopSearchTest {
    private class Memory : TextPersistence {
        var text: String? = null
        override suspend fun read() = text
        override suspend fun write(value: String) { text = value }
    }
    private class Fixture : AutoCloseable {
        val executor = Executors.newFixedThreadPool(4)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val searches = CopyOnWriteArrayList<String>()
        lateinit var store: AccountStore
        @Volatile var failAll = false
        @Volatile var failShows = false
        @Volatile var unauthorized = false
        @Volatile var failAncestors = false
        @Volatile var noAncestors = false
        val movie = """{"Id":"a","Name":"Cloud","Type":"Movie","ProductionYear":2026}"""
        init {
            server.executor = executor
            server.createContext("/") { e ->
                val q = e.requestURI.rawQuery.orEmpty().split("&").associate {
                    val pair = it.split("=", limit = 2); pair[0] to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
                }
                val path = e.requestURI.path
                val term = q["searchTerm"]
                var status = 200
                val body = when {
                    term != null -> {
                        searches += term
                        if (term == "slow") Thread.sleep(600)
                        if (unauthorized) { status = 401; "{}" }
                        else if (failAll || failShows && q["includeItemTypes"] == "Series") { status = 503; "{}" }
                        else if (term != "Cloud" && term != "slow") """{"Items":[],"TotalRecordCount":0}"""
                        else when (q["includeItemTypes"]) {
                            "Movie" -> """{"Items":[$movie,${movie.replace("\"a\"", "\"b\"")}],"TotalRecordCount":2}"""
                            "Episode" -> """{"Items":[{"Id":"episode","Name":"Cloud episode","Type":"Episode"}],"TotalRecordCount":1}"""
                            else -> """{"Items":[],"TotalRecordCount":0}"""
                        }
                    }
                    path.endsWith("/Ancestors") && failAncestors -> { status = 503; "{}" }
                    path.endsWith("/Ancestors") && noAncestors -> "[]"
                    path.endsWith("/Ancestors") -> """[{"Id":"${if (path.contains("/a/")) "one" else "two"}","Name":"${if (path.contains("/a/")) "Movies" else "Open Movies"}","Type":"CollectionFolder"}]"""
                    path == "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                    path == "/Items/a" -> movie
                    path == "/UserViews" -> """{"Items":[],"TotalRecordCount":0}"""
                    else -> """{"Items":[],"TotalRecordCount":0}"""
                }
                e.responseHeaders.add("Content-Type", "application/json")
                runCatching { val bytes = body.toByteArray(); e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.use { it.write(bytes) } }
                e.close()
            }
            server.start()
        }
        suspend fun controller(): DesktopController {
            val account = Account(Server("demo", "Demo", "http://127.0.0.1:${server.address.port}", "12.0"), User("viewer", "Viewer"), "token")
            store = AccountStore(Memory()); store.load(); store.remember(account)
            return DesktopController(Files.createTempDirectory("pictureshow-search-").toFile(), store = store).also {
                it.start(); withTimeout(5000) { it.state.first { s -> s.route == DesktopRoute.Home && !s.busy } }
                it.search(); withTimeout(5000) { it.state.first { s -> s.route == DesktopRoute.Search && !s.busy } }
            }
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private suspend fun DesktopController.completed() = withTimeout(8000) { state.first { it.route == DesktopRoute.Search && it.search.completed && !it.busy } }

    @Test fun queryBoundsGroupingDuplicatesFailuresRetryAndHistoryUseTheCurrentAccount(): Unit = runBlocking {
        Fixture().use { f -> f.controller().use { c ->
            c.searchQuery("x"); delay(400); assertTrue(f.searches.isEmpty(), "one character sends no requests")
            c.searchQuery("Cloud", immediate = true)
            val found = c.completed().search
            assertEquals(listOf("Movies", "Episodes"), found.rows.map { it.title })
            assertEquals(mapOf("a" to "Movies", "b" to "Open Movies"), found.libraries)
            c.openSearchResult(found.rows.first().items.first())
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Detail && !it.busy } }
            c.back(); assertEquals("Cloud", c.state.value.search.query)
            assertEquals(2, c.state.value.search.rows.size)
            withTimeout(5000) { c.state.first { "Cloud" in it.search.recent } }
            f.failShows = true; c.searchQuery("Cloud", immediate = true)
            val partial = c.completed().search
            assertTrue(partial.someFailed); assertFalse(partial.allFailed); assertEquals(2, partial.rows.size)
            f.failAll = true; c.searchQuery("Cloud", immediate = true)
            assertTrue(c.completed().search.allFailed)
            f.failAll = false; f.failShows = false; c.retry()
            assertFalse(c.completed().search.someFailed)
            c.searchQuery("missing", immediate = true)
            val empty = c.completed().search
            assertTrue(empty.rows.isEmpty()); assertFalse(empty.someFailed)
            c.rememberSearch(clear = true)
            withTimeout(5000) { c.state.first { it.search.recent.isEmpty() } }
        } }
    }

    @Test fun rapidTypingDebouncesAndClearRejectsALateTransportResponse() = runBlocking {
        Fixture().use { f -> f.controller().use { c ->
            c.searchQuery("Cl"); c.searchQuery("Clo"); c.searchQuery("Cloud")
            c.completed(); assertEquals(setOf("Cloud"), f.searches.toSet())
            f.searches.clear(); c.searchQuery("slow", immediate = true)
            withTimeout(5000) { while (f.searches.isEmpty()) delay(10) }
            c.searchQuery(""); delay(900)
            assertEquals("", c.state.value.search.query)
            assertFalse(c.state.value.search.completed)
            assertTrue(c.state.value.search.rows.isEmpty())
            assertFalse(c.state.value.search.loading)
        } }
    }

    @Test fun backRestartsAnInterruptedQueryAndRecentSearchesSurviveTheOpenerSnapshot(): Unit = runBlocking {
        Fixture().use { f -> f.controller().use { c ->
            c.searchQuery("Cloud")
            c.settings()
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Settings && !it.busy } }
            c.back()
            assertEquals("Cloud", c.completed().search.query)
            c.openSearchResult(c.state.value.search.rows.first().items.first())
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Detail && !it.busy && "Cloud" in it.search.recent } }
            c.search()
            assertEquals("Cloud", c.state.value.search.query)
            assertEquals(listOf("Cloud"), c.state.value.search.recent)
            assertEquals(DesktopRoute.Search, c.state.value.route)
        } }
    }

    @Test fun expiredSearchUsesSignInAndLibraryReadFailureDiffersFromMissingMetadata(): Unit = runBlocking {
        Fixture().use { f -> f.controller().use { c ->
            f.noAncestors = true; c.searchQuery("Cloud", immediate = true)
            val absent = c.completed().search
            assertFalse(absent.libraryError)
            assertEquals(setOf("Library not provided"), absent.libraries.values.toSet())
            f.noAncestors = false; f.failAncestors = true; c.retry()
            assertTrue(c.completed().search.libraryError)
            f.unauthorized = true; c.retry()
            val expired = withTimeout(5000) { c.state.first { it.route == DesktopRoute.SignIn && !it.busy } }
            assertNull(expired.account)
            assertEquals(ErrorKind.EXPIRED, expired.errorKind)
        } }
    }

    @Test fun switchingAccountsClearsTheQueryAndKeepsRecentHistoryPrivate(): Unit = runBlocking {
        Fixture().use { f -> f.controller().use { c ->
            val first = requireNotNull(c.state.value.account)
            c.searchQuery("Cloud", immediate = true); c.completed(); c.rememberSearch()
            withTimeout(5000) { c.state.first { "Cloud" in it.search.recent } }
            val second = first.copy(user = User("second", "Second viewer"), accessToken = "other-token")
            f.store.remember(second); c.selectAccount(second.key)
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Home && !it.busy && it.account?.key == second.key } }
            c.search()
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Search && !it.busy } }
            assertEquals("", c.state.value.search.query); assertTrue(c.state.value.search.recent.isEmpty())
            c.selectAccount(first.key)
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Home && !it.busy && it.account?.key == first.key } }
            c.search()
            withTimeout(5000) { c.state.first { it.route == DesktopRoute.Search && !it.busy } }
            assertEquals(listOf("Cloud"), c.state.value.search.recent)
        } }
    }

    @Test fun desktopFindShortcutDoesNotStealPlayerFullscreenOrTypedQuestionMarks() {
        fun action(route: DesktopRoute, mac: Boolean, signedIn: Boolean = true) = shellFallbackAction(ShellKeyPress(Key.F, ctrl = !mac, meta = mac), route, true, false, mac, signedIn)
        assertEquals(ShellAction.Search, action(DesktopRoute.Home, true))
        assertEquals(ShellAction.Search, action(DesktopRoute.Search, false))
        assertNull(action(DesktopRoute.Player, true)); assertNull(action(DesktopRoute.Home, true, false))
        assertNull(shellFallbackAction(ShellKeyPress(Key.Slash, shift = true, char = '?'), DesktopRoute.Search, true, false, true, true))
        assertEquals(ShellAction.Fullscreen, shellFallbackAction(ShellKeyPress(Key.F), DesktopRoute.Player, false, false, true, true))
    }

    @Test fun focusedFieldDownEntersResultsUpReturnsTabMovesBetweenShelvesAndReturnOpens() = runComposeUiTest {
        Fixture().use { f ->
            val c = runBlocking { f.controller() }
            c.use {
                var preview: (KeyEvent) -> Boolean = { false }
                var fallback: (KeyEvent) -> Boolean = { false }
                setContent {
                    Box(Modifier.fillMaxSize().onPreviewKeyEvent { preview(it) }.onKeyEvent { fallback(it) }) {
                        DesktopApp(c, {}, false, { preview = it }, windowFallbackKeys = { fallback = it }, macShortcuts = true)
                    }
                }
                waitUntil(timeoutMillis = 5000) { DesktopUiProbe.focus["search-field"] == true }
                onNodeWithTag("search-field").performTextInput("Cloud")
                waitUntil(timeoutMillis = 8000) { c.state.value.search.completed }
                onNodeWithTag("search-field").performKeyInput { pressKey(Key.DirectionDown) }
                waitUntil(timeoutMillis = 5000) { DesktopUiProbe.focus["search:MOVIES:a"] == true }
                onNodeWithTag("search:MOVIES:a").performKeyInput { pressKey(Key.DirectionUp) }
                onNodeWithTag("search-field").assertIsFocused()
                onNodeWithTag("search-field").performKeyInput { pressKey(Key.DirectionDown) }
                onNodeWithTag("search:MOVIES:a").performKeyInput { pressKey(Key.Tab) }
                onNodeWithTag("search:EPISODES:episode").assertIsFocused()
                onNodeWithTag("search:EPISODES:episode").performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
                onNodeWithTag("search:MOVIES:a").assertIsFocused()
                onNodeWithTag("search:MOVIES:a").performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = 5000) { c.state.value.route == DesktopRoute.Detail && !c.state.value.busy }
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitUntil(timeoutMillis = 5000) { c.state.value.route == DesktopRoute.Search }
                waitUntil(timeoutMillis = 5000) { DesktopUiProbe.focus["search:MOVIES:a"] == true }
                onNodeWithTag("search-field").assertTextContains("Cloud")
                onNodeWithTag("nav:search").assertIsDisplayed()
                onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.F) } }
                waitUntil(timeoutMillis = 5000) { DesktopUiProbe.focus["search-field"] == true }
                onNodeWithTag("search-field").assertTextContains("Cloud")
                f.failAll = true
                c.searchQuery("Cloud", immediate = true)
                waitUntil(timeoutMillis = 8000) { c.state.value.search.allFailed }
                onNodeWithTag("search-retry").requestFocus()
                f.failAll = false
                onNodeWithTag("search-retry").performKeyInput { pressKey(Key.Enter) }
                waitUntil(timeoutMillis = 8000) { c.state.value.search.completed && !c.state.value.search.someFailed }
                onNodeWithTag("search-field").assertIsFocused()
            }
        }
    }
}
