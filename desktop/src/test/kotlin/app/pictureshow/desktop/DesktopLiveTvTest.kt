/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package app.pictureshow.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import app.pictureshow.core.*
import app.pictureshow.core.livetv.*
import app.pictureshow.core.playback.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

class DesktopLiveTvTest {
    private class Fixture : AutoCloseable {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val requests = CopyOnWriteArrayList<Triple<String, String, String>>()
        val start = System.currentTimeMillis() - 120_000
        val end = start + 1_800_000
        var access = true; var manage = true; var failGuide = false; var channels = true
        var denyPolicy = false
        var holdFirstPlayback = false
        var subtitleRetry = false
        var failClose = false
        val negotiations = java.util.concurrent.atomic.AtomicInteger()
        val playbackEntered = java.util.concurrent.CountDownLatch(1)
        val playbackRelease = java.util.concurrent.CountDownLatch(1)
        val closedLive = CopyOnWriteArrayList<String>()
        val channel = """{"Id":"channel","Name":"Demo One","ChannelNumber":"2","CurrentProgram":{"Id":"program","Name":"Morning show","ChannelId":"channel","StartDate":"${iso(start)}","EndDate":"${iso(end)}","IsSeries":true}}"""
        val program = """{"Id":"program","Name":"Morning show","ChannelId":"channel","StartDate":"${iso(start)}","EndDate":"${iso(end)}","IsSeries":true}"""
        private fun mediaSource(count: Int, opened: Boolean) = buildJsonObject {
            put("Id", "source"); put("IsInfiniteStream", true); put("SupportsDirectPlay", true); put("Container", "ts")
            put("DefaultSubtitleStreamIndex", if (subtitleRetry) 1 else -1)
            if (opened) { put("LiveStreamId", if (subtitleRetry) "owned-$count" else "owned-live"); put("RequiresClosing", true) }
            else { put("RequiresOpening", true); put("OpenToken", "open-$count") }
            putJsonArray("MediaStreams") {
                addJsonObject { put("Index", 0); put("Type", "Video"); put("Codec", "h264") }
                if (subtitleRetry) addJsonObject {
                    put("Index", 1); put("Type", "Subtitle"); put("Codec", "srt"); put("IsExternal", true); put("IsForced", true)
                    if (count > 1) put("DeliveryUrl", "/subtitles/ready.srt")
                }
            }
        }
        val account get() = Account(Server("server", "Demo", "http://127.0.0.1:${server.address.port}", "12.4.0"), User("viewer", "Viewer"), "token")
        init {
            server.executor = executor
            server.createContext("/") { e ->
                val path = e.requestURI.path
                val body = e.requestBody.bufferedReader().readText()
                requests.add(Triple(e.requestMethod, path, body))
                if (path == "/LiveStreams/Close") closedLive += e.requestURI.rawQuery.orEmpty()
                if (holdFirstPlayback && path.endsWith("/PlaybackInfo")) { playbackEntered.countDown(); playbackRelease.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                var status = 200
                val response = when {
                    path == "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                    path == "/Users/viewer" -> if (denyPolicy) { status = 403; "" } else """{"Policy":{"EnableLiveTvAccess":$access,"EnableLiveTvManagement":$manage,"EnableContentDeletion":true,"EnableUserPreferenceAccess":true}}"""
                    path == "/LiveTv/Info" -> """{"IsEnabled":true}"""
                    path == "/LiveTv/Channels" -> if (channels) """{"Items":[$channel],"TotalRecordCount":1}""" else """{"Items":[],"TotalRecordCount":0}"""
                    path == "/LiveTv/Channels/channel" -> channel
                    path == "/LiveTv/Programs" -> if (failGuide) { status = 500; "" } else """{"Items":[$program],"TotalRecordCount":1}"""
                    path == "/LiveTv/Programs/program" -> program
                    path == "/LiveTv/Timers/Defaults" -> """{"PrePaddingSeconds":0,"PostPaddingSeconds":60,"Priority":0,"RecordNewOnly":true,"ProviderOpaque":"keep"}"""
                    path.startsWith("/LiveTv/Timers") || path == "/LiveTv/SeriesTimers" -> """{"Items":[],"TotalRecordCount":0}"""
                    path.endsWith("/PlaybackInfo") -> {
                        val count = negotiations.incrementAndGet()
                        """{"PlaySessionId":"session-$count","MediaSources":[${mediaSource(count, false)}]}"""
                    }
                    path == "/LiveStreams/Open" -> {
                        val count = Json.parseToJsonElement(body).jsonObject.getValue("OpenToken").jsonPrimitive.content.removePrefix("open-").toInt()
                        """{"MediaSource":${mediaSource(count, true)}}"""
                    }
                    path == "/LiveStreams/Close" -> { if (failClose) status = 500; "" }
                    path.startsWith("/Sessions/") || path.startsWith("/Videos/ActiveEncodings") -> ""
                    else -> """{"Items":[],"TotalRecordCount":0}"""
                }
                e.responseHeaders.set("Content-Type", "application/json")
                if (response.isEmpty()) e.sendResponseHeaders(status, -1) else {
                    val bytes = response.toByteArray(); e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.write(bytes)
                }
                e.close()
            }
            server.start()
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }
    private class Engine : DesktopPlaybackEngine {
        var opened = false; var closed = false; var position = 110.0; var paused = false; var ended = false
        var window: ObservedLiveWindow? = null
        val seeks = CopyOnWriteArrayList<Double>()
        override fun attach(window: java.awt.Window) {}
        override fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int) { opened = true }
        override fun snapshot() = EngineSnapshot(position, 99999.0, paused, opened && !closed, ended, liveWindow = window)
        override fun togglePause() { paused = !paused }
        override fun seek(seconds: Double) { seeks += seconds; position = seconds }
        override fun volume(value: Int) {}
        override fun fullscreen(enabled: Boolean) {}
        override fun close() { closed = true }
    }
    private fun dir() = Files.createTempDirectory("pictureshow-live-").toFile()
    private suspend fun controller(f: Fixture, engine: Engine = Engine()): DesktopController {
        val store = AccountStore(object : TextPersistence {
            var text: String? = null
            override suspend fun read() = text
            override suspend fun write(value: String) { text = value }
        })
        store.remember(f.account)
        val c = DesktopController(dir(), store = store, playbackFactory = { engine })
        c.start(); withTimeout(10_000) { c.state.first { it.route == DesktopRoute.Home && !it.busy } }
        c.open(MediaItem("tv", "Live TV", "UserView", collectionType = "LiVeTv", isFolder = true))
        withTimeout(10_000) { c.liveTv.state.first { !it.loading } }
        return c
    }
    @Test fun channelsGuideAndTypedRouteUseLiveApiAndNoLibraryBrowse(): Unit = runBlocking {
        Fixture().use { f -> controller(f).use { c ->
            assertEquals(DesktopRoute.LiveTv, c.state.value.route)
            assertEquals("Demo One", c.liveTv.state.value.channels.single().name)
            assertEquals("Morning show", c.liveTv.state.value.programs.single().name)
            assertTrue(c.liveTv.state.value.actionsAvailable(System.currentTimeMillis()))
            assertFalse(f.requests.any { it.second == "/Items/tv" })
            c.liveTv.tab(DesktopLiveTab.Guide); c.liveTv.favorites(); c.liveTv.search("missing")
            c.liveTv.clearFilters()
            assertEquals(DesktopLiveTab.Guide, c.liveTv.state.value.tab)
            assertEquals("", c.liveTv.state.value.search)
        } }
    }
    @Test fun cachedGuideIsReadableButCannotTuneAfterFailureAndDeniedPolicyPurgesIt(): Unit = runBlocking {
        Fixture().use { f -> controller(f).use { c ->
            val channel = c.liveTv.state.value.channels.single()
            f.failGuide = true; c.liveTv.refresh()
            withTimeout(10_000) { c.liveTv.state.first { !it.loading && it.offline } }
            assertEquals(1, c.liveTv.state.value.programs.size)
            assertFalse(c.liveTv.state.value.actionsAvailable(System.currentTimeMillis()))
            c.tuneChannel(channel); delay(100)
            assertFalse(f.requests.any { it.second.endsWith("/PlaybackInfo") })
            f.denyPolicy = true; c.liveTv.refresh()
            withTimeout(10_000) { c.liveTv.state.first { !it.loading && it.policy?.access == false } }
            assertTrue(c.liveTv.state.value.channels.isEmpty()); assertTrue(c.liveTv.state.value.programs.isEmpty())
        } }
    }
    @Test fun tuneNegotiatesLiveSourceClampsToObservedEdgeAndClosesWithoutWatchedCompletion(): Unit = runBlocking {
        Fixture().use { f ->
            val e = Engine(); e.window = ObservedLiveWindow(100_000, 20_000, 20_000, true)
            controller(f, e).use { c ->
                c.tuneChannel(c.liveTv.state.value.channels.single())
                withTimeout(10_000) { c.state.first { it.playback?.ready == true } }
                assertEquals("Demo One", c.state.value.playback?.liveChannel?.name)
                c.seek(5000.0); assertEquals(120.0, e.seeks.last())
                c.seek(-100.0); assertEquals(100.0, e.seeks.last())
                e.window = null; val before = e.seeks.size; c.seek(115.0); c.playerToggle()
                assertEquals(before, e.seeks.size); assertFalse(e.paused)
                e.ended = true
                withTimeout(10_000) { while (!e.closed) delay(25) }
                assertNull(c.state.value.playback?.end, "A live stream end must not create a VOD Finished card")
                c.stopPlayback()
                withTimeout(10_000) { c.state.first { it.route == DesktopRoute.LiveTv && !it.busy } }
                assertTrue(f.requests.any { it.second == "/LiveStreams/Close" })
                assertTrue(f.requests.any { it.second == "/Sessions/Playing/Stopped" && it.third.contains("owned-live") })
                assertFalse(f.requests.any { it.second.contains("PlayedItems") })
            }
        }
    }
    @Test fun rapidRetuneClosesTheDecisionReturnedAfterCancellation(): Unit = runBlocking {
        Fixture().use { f ->
            val engines = CopyOnWriteArrayList<Engine>()
            val store = AccountStore(object : TextPersistence {
                var text: String? = null
                override suspend fun read() = text
                override suspend fun write(value: String) { text = value }
            })
            store.remember(f.account)
            DesktopController(dir(), store = store, playbackFactory = { Engine().also { engines += it } }).use { c ->
                c.start(); withTimeout(10_000) { c.state.first { it.route == DesktopRoute.Home && !it.busy } }
                c.openLiveTv(); withTimeout(10_000) { c.liveTv.state.first { !it.loading } }
                f.holdFirstPlayback = true
                c.tuneChannel(c.liveTv.state.value.channels.single())
                assertTrue(withContext(Dispatchers.IO) { f.playbackEntered.await(5, java.util.concurrent.TimeUnit.SECONDS) })
                // Leaving while the HTTP response owns a server stream must still release its returned identity.
                c.home()
                f.playbackRelease.countDown()
                withTimeout(10_000) { while (f.closedLive.isEmpty()) delay(25) }
                withTimeout(10_000) { c.state.first { it.route == DesktopRoute.Home && !it.busy } }
                assertTrue(engines.all { it.closed })
                assertTrue(engines.none { it.opened })
                assertTrue(f.closedLive.single().contains("owned-live"))
            }
        }
    }
    @Test fun successfulSubtitleRetryClosesSupersededLiveSourceOnly(): Unit = runBlocking {
        Fixture().use { f ->
            f.subtitleRetry = true
            val engine = Engine()
            controller(f, engine).use { c ->
                c.tuneChannel(c.liveTv.state.value.channels.single())
                withTimeout(10_000) { c.state.first { it.playback?.ready == true || it.error != null } }
                assertNull(c.state.value.error)
                assertTrue(engine.opened)
                assertEquals(2, f.negotiations.get())
                assertTrue(f.closedLive.single().contains("owned-1"))
                assertFalse(f.closedLive.any { it.contains("owned-2") })
                c.stopPlayback()
                withTimeout(10_000) { c.state.first { it.route == DesktopRoute.LiveTv && !it.busy } }
                assertEquals(2, f.closedLive.size)
                assertTrue(f.closedLive.last().contains("owned-2"))
            }
        }
    }
    @Test fun failedSupersededSourceCleanupIsVisibleAndDoesNotStartPlayback(): Unit = runBlocking {
        Fixture().use { f ->
            f.subtitleRetry = true; f.failClose = true
            val engine = Engine()
            controller(f, engine).use { c ->
                c.tuneChannel(c.liveTv.state.value.channels.single())
                withTimeout(10_000) { c.state.first { it.error != null && !it.busy } }
                assertTrue(c.state.value.error.orEmpty().contains("previous live source"))
                assertFalse(engine.opened)
                assertTrue(engine.closed)
                assertTrue(f.closedLive.any { it.contains("owned-2") }, "Final source is also released on failed adoption")
            }
        }
    }
    @Test fun durablePendingJournalSurvivesNewInstance(): Unit = runBlocking {
        val directory = dir()
        DesktopLivePendingWrites(directory).add("account:program")
        assertTrue(DesktopLivePendingWrites(directory).contains("account:program"))
        DesktopLivePendingWrites(directory).remove("account:program")
        assertFalse(DesktopLivePendingWrites(directory).contains("account:program"))
    }
    @Test fun measuredMpvRangesNeverUseScheduledDuration() {
        val w = desktopLiveWindow("""{"seekable-ranges":[{"start":100,"end":120}]}""", true)!!
        assertEquals(100_000L, w.offsetMs); assertEquals(20_000L, w.durationMs); assertEquals(20_000L, w.goLivePosition())
        assertEquals(100_000L, desktopLiveWindow("""{"seekable-ranges":[{"start":100,"end":120},{"start":0,"end":10}]}""", true)!!.offsetMs)
        assertNull(desktopLiveWindow("{}", true))
        assertNull(desktopLiveWindow("""{"seekable-ranges":[{"start":120,"end":100}]}""", true))
        assertFalse(desktopLiveWindow("""{"seekable-ranges":[{"start":100,"end":120}]}""", false)!!.canTimeShift)
    }
    @Test fun nativeGuideCellsKeepGapsAndOverlaps() {
        val a = LiveProgram("a", channelId = "one", startDate = iso(0), endDate = iso(60000))
        val b = LiveProgram("b", channelId = "one", startDate = iso(30000), endDate = iso(90000))
        val cells = desktopGuideCells(listOf(a,b,a), 0, 120000)
        assertEquals(2, cells.size); assertEquals(listOf(0,1), cells.map { it.lane })
        assertEquals(.5f, cells[0].widthFraction); assertEquals(.25f, cells[1].startFraction)
    }
    @Test fun recordingEditorReviewsBeforeAnyWriteAndPreservesUnknownDefaults() = runComposeUiTest {
        var saves = 0
        var saved: LiveSchedule? = null
        val original = LiveSchedule(buildJsonObject { put("Name", "Morning show"); put("PrePaddingSeconds", 0); put("ProviderOpaque", "keep") })
        setContent { MaterialTheme { DesktopLiveScheduleEditor(original, false, false, false, true, {}, { saves++; saved = it }) } }
        onNodeWithTag("live:option:PrePaddingSeconds").performTextReplacement("30")
        onNodeWithTag("live:editor:review").performClick()
        runOnIdle { assertEquals(0, saves) }
        onNodeWithText("Records this airing once.").assertExists()
        onNodeWithTag("live:editor:confirm").performClick()
        runOnIdle { assertEquals(1, saves); assertEquals("keep", saved?.string("ProviderOpaque")); assertEquals("30", saved?.string("PrePaddingSeconds")) }
    }
    @Test fun staleReviewRefreshesWithoutLosingEditedOptions() = runComposeUiTest {
        val fresh = androidx.compose.runtime.mutableStateOf(false)
        var saved: LiveSchedule? = null
        var refreshes = 0
        val original = LiveSchedule(buildJsonObject { put("Name", "Morning show"); put("PrePaddingSeconds", 0) })
        setContent { MaterialTheme { DesktopLiveScheduleEditor(original, false, false, false, fresh.value, {}, { saved = it },
            onRefresh = { refreshes++; fresh.value = true }) } }
        onNodeWithTag("live:option:PrePaddingSeconds").performTextReplacement("30")
        onNodeWithTag("live:editor:review").performClick()
        onNodeWithTag("live:editor:confirm").assertIsNotEnabled()
        onNodeWithTag("live:editor:refresh").performClick()
        onNodeWithTag("live:editor:confirm").assertIsEnabled().performClick()
        runOnIdle { assertEquals(1, refreshes); assertEquals("30", saved?.string("PrePaddingSeconds")) }
    }
    @Test fun disabledAccessHidesSearchAndShowsRecovery() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob())
        val api = LiveTvClient(io.ktor.client.HttpClient(io.ktor.client.engine.okhttp.OkHttp), ClientIdentity("Test", "device"))
        val owner = DesktopLiveTv(api, scope, { null })
        setContent { MaterialTheme { LiveTvPage(DesktopLiveState(loading = false, policy = LivePolicy()), null, owner, {}, {}, {}) } }
        onNodeWithText("Live TV access is disabled").assertExists()
        onNodeWithTag("live:search").assertDoesNotExist()
        onNodeWithTag("live:refresh").assertDoesNotExist()
        scope.cancel()
    }
}
