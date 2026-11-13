/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

class DesktopControllerTest {
    private class MemoryPersistence(var value: String? = null) : TextPersistence {
        var failure = false
        override suspend fun read(): String? { if (failure) error("read refused"); return value }
        override suspend fun write(value: String) { if (failure) error("write refused"); this.value = value }
    }
    private class Engine : DesktopPlaybackEngine {
        var opened = false; var closed = false; var position = 12.0; var paused = false; var ready = true
        var openedAudio: Int? = null; var openedSubtitle: Int? = null
        var failSubtitleSelection = false
        var throwOpen = false; var throwSnapshot = false; var throwClose = false
        override fun attach(window: java.awt.Window) {}
        override fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int) { opened = true; openedAudio = audio; openedSubtitle = subtitle; this.position = position; if (throwOpen) error("startup failed after media opened") }
        override fun snapshot(): EngineSnapshot { if (throwSnapshot) error("native property unavailable"); return EngineSnapshot(position, 180.0, paused, opened && !closed && ready) }
        override fun togglePause() { paused = !paused }
        override fun seek(seconds: Double) { position = seconds }
        override fun volume(value: Int) {}
        override fun selectAudio(index: Int) = true
        override fun selectSubtitle(index: Int) = !failSubtitleSelection
        override fun fullscreen(enabled: Boolean) {}
        override fun close() { closed = true; if (throwClose) error("GPU drain failed") }
    }
    private class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val activeEncodingQueries = CopyOnWriteArrayList<String>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"
        var failLatest = false
        var slowMovie = false
        var unauthorized = false
        var itemUnauthorized = false
        var transcode = false
        @Volatile var defaultSubtitleIndex: Int? = null
        @Volatile var deliverSubtitleOnExplicitRequest = false
        @Volatile var deliverDefaultSubtitleUrl = false
        @Volatile var embedBitmapOnExplicitRequest = false
        @Volatile var subtitleCodec = "subrip"
        @Volatile var holdStart = false
        val startEntered = java.util.concurrent.CountDownLatch(1)
        val startRelease = java.util.concurrent.CountDownLatch(1)
        val reportCompletions = CopyOnWriteArrayList<String>()
        init {
            server.executor = executor
            server.createContext("/") { e ->
                val path = e.requestURI.path
                val request = e.requestBody.bufferedReader().use { it.readText() }
                requests.add(path to request)
                if (path == "/Videos/ActiveEncodings") activeEncodingQueries.add(e.requestURI.rawQuery.orEmpty())
                var status = 200
                val body = when {
                    itemUnauthorized && path in setOf("/Users/viewer/Items/movie", "/Items/movie") -> { status = 401; "" }
                    path == "/System/Info/Public" -> """{"Id":"server","ServerName":"Demo cinema","ProductName":"Jellyfin Server","Version":"12.4.0"}"""
                    path == "/Users/Public" -> "[]"
                    path == "/QuickConnect/Enabled" -> "true"
                    path == "/QuickConnect/Initiate" -> """{"Code":"123456","Secret":"test-quick-secret"}"""
                    path == "/QuickConnect/Connect" -> """{"Authenticated":true}"""
                    path == "/Users/AuthenticateWithQuickConnect" -> """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"test-token"}"""
                    path == "/Users/AuthenticateByName" -> if (request.contains("wrong")) { status = 401; "" } else """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"test-token"}"""
                    path == "/Users/Me" -> if (unauthorized) { status = 401; "" } else """{"Id":"viewer","Name":"Viewer"}"""
                    path == "/UserViews" -> """{"Items":[{"Id":"library","Name":"Movies","Type":"CollectionFolder","CollectionType":"movies","IsFolder":true}],"TotalRecordCount":1}"""
                    path == "/UserItems/Resume" || path == "/Shows/NextUp" -> """{"Items":[],"TotalRecordCount":0}"""
                    path == "/Items/Latest" -> if (failLatest) { status = 500; "" } else "[$movie]"
                    path == "/Users/viewer/Items/movie" || path == "/Items/movie" -> { if (slowMovie) Thread.sleep(350); movie }
                    path.endsWith("/PlaybackInfo") && transcode -> playbackInfo(request, transcoding = true)
                    path.endsWith("/PlaybackInfo") -> playbackInfo(request)
                    path == "/Items" || path == "/Users/viewer/Items" -> """{"Items":[$movie],"TotalRecordCount":1}"""
                    path.contains("/FavoriteItems/") -> """{"IsFavorite":true}"""
                    path.contains("/PlayedItems/") -> """{"Played":true}"""
                    path.startsWith("/Sessions/") || path.startsWith("/Videos/ActiveEncodings") -> ""
                    else -> { status = 404; "" }
                }
                if (path == "/Sessions/Playing" && holdStart) { startEntered.countDown(); check(startRelease.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                e.responseHeaders.set("Content-Type", "application/json")
                if (body.isEmpty()) e.sendResponseHeaders(status, -1) else { val bytes = body.toByteArray(); e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.write(bytes) }
                e.close()
                if (path.startsWith("/Sessions/Playing")) reportCompletions.add(path)
            }
            server.start()
        }
        private fun playbackInfo(request: String, transcoding: Boolean = false): String {
            val explicitSubtitle = Regex("\\\"SubtitleStreamIndex\\\"\\s*:\\s*(\\d+)").find(request)?.groupValues?.get(1)?.toIntOrNull()
            val delivery = when {
                deliverSubtitleOnExplicitRequest && explicitSubtitle == 2 -> ",\"DeliveryMethod\":\"External\",\"DeliveryUrl\":\"/Videos/movie/Subtitles/2/Stream.srt\""
                deliverDefaultSubtitleUrl && explicitSubtitle == null && defaultSubtitleIndex == 2 -> ",\"DeliveryMethod\":\"External\",\"DeliveryUrl\":\"/Videos/movie/Subtitles/2/Stream.srt\""
                embedBitmapOnExplicitRequest && explicitSubtitle == 2 && request.contains("\"Format\":\"pgssub\"") -> ",\"DeliveryMethod\":\"Embed\""
                else -> ""
            }
            val defaultSubtitle = defaultSubtitleIndex?.toString() ?: "null"
            val playbackMethod = if (transcoding) "\"TranscodingUrl\":\"/Videos/movie/master.m3u8?VideoCodec=h264&TranscodeReasons=VideoCodecNotSupported\",\"SupportsTranscoding\":true" else "\"SupportsDirectPlay\":true"
            return """{"PlaySessionId":"session","MediaSources":[{"Id":"source","Container":"mkv",$playbackMethod,"RunTimeTicks":1800000000,"DefaultAudioStreamIndex":1,"DefaultSubtitleStreamIndex":$defaultSubtitle,"MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"},{"Type":"Audio","Index":1,"Codec":"aac","Language":"eng"},{"Type":"Subtitle","Index":2,"Codec":"$subtitleCodec","Language":"eng"$delivery}]}]}"""
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
        companion object { const val movie = """{"Id":"movie","Name":"The first picture","Type":"Movie","RunTimeTicks":1800000000,"UserData":{"PlaybackPositionTicks":900000000}}""" }
    }
    private suspend fun DesktopController.await(predicate: (DesktopState) -> Boolean): DesktopState = withTimeout(10000) { state.first(predicate) }
    private suspend fun DesktopController.login(f: Fixture) {
        start(); await { it.route == DesktopRoute.Servers && !it.busy }
        connect(f.url); await { it.route == DesktopRoute.SignIn && !it.busy }
        signIn("Viewer", "correct"); await { it.route == DesktopRoute.Home && !it.busy }
    }
    private fun directory() = Files.createTempDirectory("pictureshow-desktop-test-").toFile()

    @Test fun defaultTextSubtitleWithoutUrlRetriesPlaybackInfoBeforeOpening(): Unit = runBlocking {
        Fixture().use { f ->
            f.defaultSubtitleIndex = 2
            f.deliverSubtitleOnExplicitRequest = true
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.playback?.ready == true }

                val requests = f.requests.filter { it.first.endsWith("/PlaybackInfo") }.map { it.second }
                assertEquals(2, requests.size, "A missing default text delivery URL should trigger one targeted renegotiation")
                assertFalse(requests.first().contains("\"SubtitleStreamIndex\":2"), "First request should use the unqualified default")
                assertTrue(requests.last().contains("\"SubtitleStreamIndex\":2"))
                assertTrue(requests.last().contains("\"MediaSourceId\":\"source\""), "Retry must preserve the selected media source")
                assertTrue(requests.last().contains("\"AudioStreamIndex\":1"), "Retry must preserve the chosen audio")
                assertTrue(requests.last().contains("\"StartTimeTicks\":900000000"), "Retry must preserve resume position")
                assertEquals(1, engine.openedAudio)
                assertEquals(2, engine.openedSubtitle)
                assertEquals(2, c.state.value.playback?.subtitleIndex)
                withTimeout(10000) { while (f.requests.none { it.first == "/Sessions/Playing" }) delay(20) }
                assertEquals(1, f.requests.count { it.first == "/Sessions/Playing" })
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
            }
        }
    }

    @Test fun defaultTextSubtitleStillMissingUrlFailsBeforePublishingPlaybackAndClosesPlayer(): Unit = runBlocking {
        Fixture().use { f ->
            f.defaultSubtitleIndex = 2
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.route == DesktopRoute.Player && !it.busy && it.error?.contains("safe URL for the selected text subtitle") == true }

                assertEquals(2, f.requests.count { it.first.endsWith("/PlaybackInfo") }, "Renegotiation is bounded to one retry")
                assertTrue(engine.closed, "Prepared player must be closed when delivery cannot be resolved")
                assertFalse(engine.opened, "Do not open playback with a false selected-subtitle state")
                assertNull(c.state.value.playback)
                assertEquals(0, f.requests.count { it.first == "/Sessions/Playing" })
            }
        }
    }

    @Test fun missingDefaultSubtitleUrlStopsRejectedTranscodeBeforeOpeningAndOnlyOnce(): Unit = runBlocking {
        Fixture().use { f ->
            f.transcode = true
            f.defaultSubtitleIndex = 2
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.route == DesktopRoute.Player && !it.busy && it.error?.contains("safe URL for the selected text subtitle") == true }

                assertEquals(2, f.requests.count { it.first.endsWith("/PlaybackInfo") })
                assertTrue(engine.closed)
                assertFalse(engine.opened)
                assertNull(c.state.value.playback)
                val stops = f.requests.filter { it.first == "/Videos/ActiveEncodings" }
                assertEquals(1, stops.size, "Rejected pre-open negotiation must release its transcoding session once")
                assertTrue(f.activeEncodingQueries.single().contains("playSessionId=session"))
                assertEquals(0, f.requests.count { it.first == "/Sessions/Playing" })
            }
        }
    }

    @Test fun defaultBitmapSubtitleRetriesWithOnlyItsSelectedEmbedProfile(): Unit = runBlocking {
        Fixture().use { f ->
            f.defaultSubtitleIndex = 2
            f.subtitleCodec = "hdmv_pgs_subtitle"
            f.embedBitmapOnExplicitRequest = true
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.playback?.ready == true }

                val requests = f.requests.filter { it.first.endsWith("/PlaybackInfo") }.map { it.second }
                assertEquals(2, requests.size, "A selected bitmap track should negotiate one codec-specific embedded profile")
                assertTrue(requests.first().contains("\"Format\":\"srt\""))
                assertFalse(requests.first().contains("\"Method\":\"Embed\""), "Text baseline must not advertise bitmap Embed formats")
                assertTrue(requests.last().contains("\"Format\":\"pgssub\""))
                assertTrue(requests.last().contains("\"Method\":\"Embed\""))
                assertFalse(requests.last().contains("\"Format\":\"srt\""), "Bitmap retry must not re-advertise text delivery")
                assertEquals(2, engine.openedSubtitle)
                assertEquals(2, c.state.value.playback?.subtitleIndex)
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
            }
        }
    }

    @Test fun explicitSubtitleOffIsPreservedAndDoesNotRenegotiateTheServerDefault(): Unit = runBlocking {
        Fixture().use { f ->
            f.defaultSubtitleIndex = 2
            f.deliverDefaultSubtitleUrl = true
            val first = Engine().apply { failSubtitleSelection = true }
            val second = Engine()
            var creates = 0
            DesktopController(directory(), playbackFactory = { if (creates++ == 0) first else second }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.playback?.ready == true }
                c.selectSubtitle(-1)
                c.await { it.playback?.ready == true && it.playback.subtitleIndex == -1 }

                val requests = f.requests.filter { it.first.endsWith("/PlaybackInfo") }.map { it.second }
                assertEquals(2, requests.size, "Off should cause only the existing selection reprepare")
                assertTrue(requests.last().contains("\"SubtitleStreamIndex\":-1"))
                assertEquals(-1, second.openedSubtitle, "The server default must not replace explicit Off")
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
            }
        }
    }

    @Test fun passwordLoginClearsAuthenticationBackStackAndLoadsRealCoreHome(): Unit = runBlocking {
        Fixture().use { f ->
            val dir = directory()
            DesktopController(dir).use { c ->
                c.login(f)
                assertEquals(listOf("libraries", "latest:library"), c.state.value.homeRows.map { it.key })
                c.back(); c.await { it.route == DesktopRoute.Home && !it.busy }
                assertNotNull(c.state.value.account)
                assertTrue(File(dir, "accounts.json").readText().contains("Viewer"))
            }
        }
    }
    @Test fun rejectedPasswordRemainsRetryable(): Unit = runBlocking {
        Fixture().use { f -> DesktopController(directory()).use { c ->
            c.start(); c.await { !it.busy }; c.connect(f.url); c.await { it.route == DesktopRoute.SignIn && !it.busy }
            c.signIn("Viewer", "wrong"); c.await { it.error != null && !it.busy }
            assertEquals(DesktopRoute.SignIn, c.state.value.route)
            assertEquals(ErrorKind.INVALID_CREDENTIALS, c.state.value.errorKind)
            assertEquals("Sign-in was refused. Check your username and password or sign in again.", c.state.value.error)
            c.retry()
            assertNull(c.state.value.error, "Credential refusal should be corrected in the fields, not resubmitted by Retry")
            assertEquals(DesktopRoute.SignIn, c.state.value.route)
            c.signIn("Viewer", "correct"); c.await { it.route == DesktopRoute.Home && !it.busy }
        } }
    }
    @Test fun expiredSavedTokenIsNotReportedAsWrongTypedPassword(): Unit = runBlocking {
        Fixture().use { f -> DesktopController(directory()).use { c ->
            c.login(f)
            f.itemUnauthorized = true
            c.detail(MediaItem("movie", "The first picture", "Movie"))
            c.await { it.route == DesktopRoute.SignIn && !it.busy && it.error != null }
            assertNull(c.state.value.account)
            assertEquals(ErrorKind.EXPIRED, c.state.value.errorKind)
            assertEquals("Your session ended. Sign in again.", c.state.value.error)
            assertTrue(c.state.value.homeRows.isEmpty())
            assertNull(c.state.value.detail)
            c.back(); c.await { !it.busy }
            assertNull(c.state.value.account, "Back cannot restore the expired viewer from catalog history")
            assertTrue(c.state.value.homeRows.isEmpty())
            assertFalse(c.state.value.route in setOf(DesktopRoute.Home, DesktopRoute.Library, DesktopRoute.Detail, DesktopRoute.Player))
        } }
    }
    @Test fun selectingNewlyExpiredSavedViewerOpensSignInOnceAndPreservesOtherViewer(): Unit = runBlocking {
        Fixture().use { first -> Fixture().use { second ->
            val store = AccountStore(MemoryPersistence())
            val a = Account(Server("first", "First cinema", first.url, "12.4.0"), User("viewer", "Viewer"), "first-test-token")
            val b = Account(Server("second", "Second cinema", second.url, "12.4.0"), User("viewer", "Viewer"), "second-test-token")
            store.remember(b); store.remember(a)
            second.unauthorized = true
            DesktopController(directory(), store = store).use { c ->
                c.start(); c.await { it.route == DesktopRoute.Home && !it.busy }
                c.accounts(); c.await { it.route == DesktopRoute.Accounts && !it.busy }
                c.selectAccount(b.key)
                c.await { it.route == DesktopRoute.SignIn && !it.busy }
                assertEquals(b.server, c.state.value.server)
                assertEquals(ErrorKind.EXPIRED, c.state.value.errorKind)
                assertEquals(a, store.snapshots.value.accounts.first { it.key == a.key })
                assertEquals(a.key, store.snapshots.value.currentKey)
                assertTrue(store.snapshots.value.accounts.first { it.key == b.key }.expired)
            }
        } }
    }
    @Test fun unreadableAccountStoreOffersRetryWithoutOverwritingData(): Unit = runBlocking {
        val memory = MemoryPersistence("do not discard").apply { failure = true }
        DesktopController(directory(), store = AccountStore(memory)).use { c ->
            c.start(); c.await { it.error != null && !it.busy }
            assertEquals(DesktopRoute.Loading, c.state.value.route)
            assertEquals("do not discard", memory.value)
            memory.value = null; memory.failure = false; c.retry(); c.await { it.route == DesktopRoute.Servers && !it.busy }
        }
    }
    @Test fun partialHomeFailureKeepsSuccessfulLibraries(): Unit = runBlocking {
        Fixture().use { f -> f.failLatest = true; DesktopController(directory()).use { c ->
            c.login(f)
            assertEquals(listOf("libraries"), c.state.value.homeRows.map { it.key })
            assertNotNull(c.state.value.error)
        } }
    }
    @Test fun departedDetailCannotReplaceHomeAfterDelayedReply(): Unit = runBlocking {
        Fixture().use { f -> DesktopController(directory()).use { c ->
            c.login(f); f.slowMovie = true
            c.detail(MediaItem("movie", "Old title", "Movie")); c.home()
            c.await { it.route == DesktopRoute.Home && !it.busy }; delay(500)
            assertEquals(DesktopRoute.Home, c.state.value.route)
        } }
    }
    @Test fun playbackResumesAndReportsThenStopsBeforeReturningToFreshDetail(): Unit = runBlocking {
        Fixture().use { f ->
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(MediaItem("movie", "The first picture", "Movie", runTimeTicks = 1_800_000_000, userData = ItemUserData(playbackPositionTicks = 900_000_000)))
                c.await { it.playback?.ready == true }
                withTimeout(10000) { while (f.requests.none { it.first == "/Sessions/Playing" }) delay(20) }
                assertEquals(90.0, engine.position)
                engine.position = 112.0
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
                assertTrue(engine.closed)
                val stops = f.requests.filter { it.first == "/Sessions/Playing/Stopped" }
                assertEquals(1, stops.size)
                assertTrue(stops.single().second.contains("1120000000"))
                assertEquals("The first picture", c.state.value.detail?.name)
            }
        }
    }
    @Test fun delayedStartChoiceAndStopAreSerializedWithoutLateProgress(): Unit = runBlocking {
        Fixture().use { f ->
            f.holdStart = true
            val engine = Engine().apply { ready = false }
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.route == DesktopRoute.Player && it.playback != null && !it.busy }
                c.selectSubtitle(2); engine.ready = true
                assertTrue(f.startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                c.selectSubtitle(-1); c.playerToggle(); c.stopPlayback()
                delay(150)
                assertEquals(listOf("/Sessions/Playing"), f.requests.filter { it.first.startsWith("/Sessions/Playing") }.map { it.first })
                f.startRelease.countDown()
                c.await { it.route == DesktopRoute.Detail && !it.busy }
                val reports = f.requests.filter { it.first.startsWith("/Sessions/Playing") }
                assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Stopped"), reports.map { it.first })
                assertTrue(reports.first().second.contains("\"SubtitleStreamIndex\":2"))
                assertEquals(listOf("/Sessions/Playing", "/Sessions/Playing/Stopped"), f.reportCompletions.toList())
                delay(200)
                assertEquals(2, f.requests.count { it.first.startsWith("/Sessions/Playing") })
            }
        }
    }
    @Test fun progressWaitsForStartAndReadsLatestViewerSelection(): Unit = runBlocking {
        Fixture().use { f ->
            f.holdStart = true
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
                assertTrue(f.startEntered.await(10, java.util.concurrent.TimeUnit.SECONDS))
                c.selectSubtitle(2)
                delay(100)
                assertFalse(f.requests.any { it.first == "/Sessions/Playing/Progress" })
                f.startRelease.countDown()
                withTimeout(10000) { while (f.requests.none { it.first == "/Sessions/Playing/Progress" }) delay(20) }
                val progress = f.requests.first { it.first == "/Sessions/Playing/Progress" }
                assertTrue(progress.second.contains("\"SubtitleStreamIndex\":2"))
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
                val order = f.reportCompletions.toList()
                assertEquals("/Sessions/Playing", order.first()); assertEquals("/Sessions/Playing/Stopped", order.last())
            }
        }
    }
    @Test fun missingNativeLibraryIsARecoverablePlayerError(): Unit = runBlocking {
        Fixture().use { f -> DesktopController(directory(), playbackFactory = { throw UnsatisfiedLinkError("test native absent") }).use { c ->
            c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
            c.await { it.route == DesktopRoute.Player && it.error != null && !it.busy }
            assertNull(c.state.value.playback)
            c.back(); c.await { it.route == DesktopRoute.Detail && !it.busy }
            assertEquals("The first picture", c.state.value.detail?.name)
        } }
    }
    @Test fun nativeCleanupFailuresCannotSkipStopOrEncoderReleaseAndNextPlay(): Unit = runBlocking {
        Fixture().use { f ->
            f.transcode = true
            val first = Engine(); val second = Engine(); var creates = 0
            DesktopController(directory(), playbackFactory = { if (creates++ == 0) first else second }).use { c ->
                c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
                withTimeout(10000) { while (f.requests.none { it.first == "/Sessions/Playing" }) delay(20) }
                first.throwSnapshot = true; first.throwClose = true
                c.stopPlayback(); c.await { it.route == DesktopRoute.Detail && !it.busy }
                assertTrue(first.closed)
                assertEquals(1, f.requests.count { it.first == "/Sessions/Playing/Stopped" })
                assertEquals(1, f.requests.count { it.first == "/Videos/ActiveEncodings" })
                c.play(MediaItem("movie", "The first picture", "Movie")); c.await { it.playback?.ready == true }
                assertTrue(second.opened)
            }
        }
    }
    @Test fun partiallyOpenedTranscodeReleasesEncoderAndAllowsNextPlay(): Unit = runBlocking {
        Fixture().use { f ->
            f.transcode = true
            val first = Engine().apply { throwOpen = true }; val second = Engine(); var creates = 0
            DesktopController(directory(), playbackFactory = { if (creates++ == 0) first else second }).use { c ->
                c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
                c.await { it.route == DesktopRoute.Player && it.error != null && !it.busy }
                assertTrue(first.closed)
                assertEquals(1, f.requests.count { it.first == "/Videos/ActiveEncodings" })
                assertEquals(0, f.requests.count { it.first == "/Sessions/Playing" })
                c.back(); c.await { it.route == DesktopRoute.Detail && !it.busy }
                c.play(MediaItem("movie", "The first picture", "Movie")); c.await { it.playback?.ready == true }
                assertTrue(second.opened)
            }
        }
    }
    @Test fun navigatingHomeEndsTheViewingBeforeLoadingCatalog(): Unit = runBlocking {
        Fixture().use { f ->
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f); c.play(MediaItem("movie", "The first picture", "Movie"))
                withTimeout(10000) { while (f.requests.none { it.first == "/Sessions/Playing" }) delay(20) }
                c.home(); c.await { it.route == DesktopRoute.Home && !it.busy }
                assertTrue(engine.closed); assertNull(c.state.value.playback)
                assertEquals(1, f.requests.count { it.first == "/Sessions/Playing/Stopped" })
            }
        }
    }
    @Test fun signingOutKeepsPublicServerButDeletesToken(): Unit = runBlocking {
        Fixture().use { f ->
            val dir = directory()
            DesktopController(dir).use { c ->
                c.login(f); c.signOut(); c.await { it.account == null && !it.busy }
                val persisted = File(dir, "accounts.json").readText()
                assertFalse(persisted.contains("test-token")); assertTrue(persisted.contains("Demo cinema"))
            }
        }
    }

    @Test fun quickConnectBackAndReopenRetainsAttemptAndAcceptsOnce(): Unit = runBlocking {
        Fixture().use { f -> DesktopController(directory()).use { c ->
            c.start(); c.await { !it.busy }; c.connect(f.url); c.await { it.route == DesktopRoute.SignIn && !it.busy }
            c.quickConnect(); c.await { it.quickCode == "123456" }
            c.back(); c.await { it.route == DesktopRoute.SignIn && !it.busy }
            c.quickConnect(); c.await { it.route == DesktopRoute.Home && !it.busy }
            assertEquals(1, f.requests.count { it.first == "/QuickConnect/Initiate" })
            assertEquals(1, f.requests.count { it.first == "/Users/AuthenticateWithQuickConnect" })
            c.back(); c.await { it.route == DesktopRoute.Home && !it.busy }
        } }
    }
    @Test fun mediaRedirectOutsideOriginNeverReceivesCredentials() {
        val unauthorizedRequests = java.util.concurrent.atomic.AtomicInteger()
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        destination.createContext("/") { e -> unauthorizedRequests.incrementAndGet(); e.sendResponseHeaders(200, -1); e.close() }
        destination.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        origin.createContext("/") { e -> e.responseHeaders.set("Location", "http://127.0.0.1:${destination.address.port}/steal"); e.sendResponseHeaders(302, -1); e.close() }
        origin.start()
        try {
            val base = "http://127.0.0.1:${origin.address.port}"
            val a = Account(Server("s", "Demo", base, "12.4.0"), User("u", "Viewer"), "secret")
            val decision = PlayDecision("movie", MediaSourceInfo(id = "source"), PlayMethod.DirectPlay, "$base/media", mapOf("Authorization" to "private"), "session")
            DesktopMediaProxy(a, decision).use { proxy ->
                val request = java.net.http.HttpRequest.newBuilder(java.net.URI(proxy.url())).build()
                java.net.http.HttpClient.newHttpClient().use { client ->
                    val response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString())
                    assertEquals(502, response.statusCode())
                    assertEquals(0, unauthorizedRequests.get())
                }
            }
        } finally { origin.stop(0); destination.stop(0) }
    }
    @Test fun deviceProfileKeepsAvDecoderEnvelopeSeparateFromSubtitleDeliveryCapabilities() {
        val profile = desktopMpvProfile(DesktopSettings(), setOf("h264", "hevc", "aac"))
        assertEquals(setOf("h264", "hevc", "aac"), profile.directPlayProfiles.single().videoCodec!!.split(',').toSet())
        assertFalse(profile.directPlayProfiles.single().videoCodec!!.contains("av1"))
        assertEquals(setOf("h264", "hevc", "aac"), profile.directPlayProfiles.single().audioCodec!!.split(',').toSet())
        val rich = desktopMpvProfile(DesktopSettings(), setOf("h264", "aac", "ffv1", "prores"))
        assertTrue("ffv1" in rich.directPlayProfiles.single().videoCodec!!.split(','))
        assertTrue("prores" in rich.directPlayProfiles.single().videoCodec!!.split(','))
        assertEquals(
            setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt"),
            profile.subtitleProfiles.filter { it.method == SubtitleDeliveryMethod.External }.mapNotNull { it.format }.toSet()
        )
        assertTrue(profile.subtitleProfiles.none { it.method == SubtitleDeliveryMethod.Embed }, "Baseline text negotiation must not let Jellyfin convert text into bitmap Embed output")
        val pgs = profile.forDesktopSubtitle("hdmv_pgs_subtitle")
        assertEquals(listOf(SubtitleProfile("pgssub", SubtitleDeliveryMethod.Embed)), pgs.subtitleProfiles)
        assertEquals("pgssub", desktopBitmapSubtitleFormat("pgssub"))
        assertEquals("dvdsub", desktopBitmapSubtitleFormat("dvd_subtitle"))
        assertEquals("dvbsub", desktopBitmapSubtitleFormat("dvb_subtitle"))
        assertEquals(listOf(SubtitleProfile("dvdsub", SubtitleDeliveryMethod.Embed)), profile.forDesktopSubtitle("dvd_subtitle").subtitleProfiles)
        assertEquals(listOf(SubtitleProfile("dvbsub", SubtitleDeliveryMethod.Embed)), profile.forDesktopSubtitle("dvb_subtitle").subtitleProfiles)
        assertEquals(profile.subtitleProfiles, profile.forDesktopSubtitle("subrip").subtitleProfiles)
        assertEquals("aac", profile.transcodingProfiles.single().audioCodec)
    }

    @Test fun externalDeliveryMethodMarksSubtitleAsUrlDeliveredWithoutExternalFileFlag() {
        val stream = MediaStream(type = MediaStreamType.Subtitle, deliveryMethod = SubtitleDeliveryMethod.External,
            deliveryUrl = "/Videos/movie/Subtitles/1/Stream.srt")
        assertTrue(stream.isDeliveredExternally())
        assertTrue(MediaStream(type = MediaStreamType.Subtitle, isExternal = true).isDeliveredExternally())
        assertFalse(MediaStream(type = MediaStreamType.Subtitle, deliveryMethod = SubtitleDeliveryMethod.Embed).isDeliveredExternally())
    }

    @Test fun knownTextCodecRequiresExternalDeliveryBeforePlaybackInfoProvidesAUrl() {
        val stream = MediaStream(type = MediaStreamType.Subtitle, index = 3, codec = "SubRip")
        assertFalse(stream.isDeliveredExternally(), "The raw Jellyfin delivery metadata is still absent")
        assertTrue(stream.requiresExternalSubtitleDelivery(), "Desktop text delivery policy is based on the same profile codec allowlist")
        assertNull(nativeSubtitleOrdinal(listOf(stream), 3), "Do not silently select the Matroska copy while requesting URL negotiation")
        assertFalse(MediaStream(type = MediaStreamType.Subtitle, index = 9, codec = "hdmv_pgs_subtitle").requiresExternalSubtitleDelivery())
    }

    @Test fun externalDeliverySkipsSelectionButStillCountsItsPhysicalEmbeddedTrackOrdinal() {
        val streams = listOf(
            MediaStream(type = MediaStreamType.Subtitle, index = 3, codec = "subrip",
                deliveryMethod = SubtitleDeliveryMethod.External, deliveryUrl = "/Videos/movie/Subtitles/3/Stream.srt"),
            MediaStream(type = MediaStreamType.Subtitle, index = 6, codec = "hdmv_pgs_subtitle",
                deliveryMethod = SubtitleDeliveryMethod.Embed)
        )
        assertNull(nativeSubtitleOrdinal(streams, 3), "URL-delivered SRT must wait for sub-add")
        assertEquals(2, nativeSubtitleOrdinal(streams, 6), "SRT remains in Matroska track ordering for the later embedded PGS track")
    }
    @Test fun settingsPersistAndInvalidBitrateCannotCommit() {
        val dir = directory(); val store = DesktopSettingsStore(File(dir, "settings.properties"))
        val settings = DesktopSettings(40, false, false, 42, true)
        store.write(settings); assertEquals(settings, store.read())
        assertFailsWith<IllegalArgumentException> { store.write(settings.copy(bitrateMbps = 17)) }
        assertEquals(settings, store.read())
    }
    @Test fun failedNativeCleanupStillClosesTheRealLoopbackMediaCapability() {
        val a = Account(Server("s", "Demo", "http://127.0.0.1:8096", "12.4.0"), User("u", "Viewer"), "secret")
        val decision = PlayDecision("movie", MediaSourceInfo(id = "source"), PlayMethod.DirectPlay,
            "http://127.0.0.1:8096/Videos/movie/stream", mapOf("Authorization" to "private"), "session")
        val proxy = DesktopMediaProxy(a, decision)
        val url = proxy.url()
        var restoredWindow = false
        val failures = runDesktopCleanupActions(
            { error("native GPU drain failed") },
            { restoredWindow = true },
            { proxy.close() }
        )
        assertEquals(1, failures.size); assertTrue(restoredWindow)
        java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(1)).build().use { client ->
            assertFailsWith<java.io.IOException> {
                client.send(java.net.http.HttpRequest.newBuilder(java.net.URI(url)).timeout(java.time.Duration.ofSeconds(1)).build(), java.net.http.HttpResponse.BodyHandlers.discarding())
            }
        }
    }
    @Test fun mediaProxyRejectsExternalReferencesBeforeForwardingAuthorization() {
        val a = Account(Server("s", "Demo", "http://127.0.0.1:8096", "12.4.0"), User("u", "Viewer"), "secret")
        val decision = PlayDecision("movie", MediaSourceInfo(id="source"), PlayMethod.DirectPlay, "http://127.0.0.1:8096/Videos/movie/stream", mapOf("Authorization" to "private"), "session")
        DesktopMediaProxy(a, decision).use { proxy ->
            assertFailsWith<IllegalArgumentException> { proxy.url("https://elsewhere.invalid/segment") }
            assertFailsWith<IllegalArgumentException> { proxy.rewriteManifest("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://elsewhere.invalid/key\"", decision.url) }
            val rewritten = proxy.rewriteManifest("#EXTM3U\nsegment.ts\n#EXT-X-MAP:URI=\"init.mp4\"", decision.url)
            assertTrue(rewritten.contains("http://127.0.0.1:")); assertFalse(rewritten.contains("private")); assertFalse(rewritten.contains("secret"))
        }
    }
}
