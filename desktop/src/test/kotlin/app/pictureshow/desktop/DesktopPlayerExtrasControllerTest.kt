/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import kotlin.test.*

/** Stream A through the controller: extras load, skip, speed, timing, rewind, countdown and still-watching. */
class DesktopPlayerExtrasControllerTest {
    private class Engine : DesktopPlaybackEngine {
        @Volatile var position = 0.0; @Volatile var ended = false; var openedAt: Double? = null; var closed = false
        var passthrough = false
        val speeds = CopyOnWriteArrayList<Float>(); val delays = CopyOnWriteArrayList<Int>(); val seeks = CopyOnWriteArrayList<Double>()
        var prefs: PlaybackPreferences? = null
        override fun attach(window: java.awt.Window) {}
        override fun open(account: Account, decision: PlayDecision, position: Double, audio: Int, subtitle: Int, volume: Int) { openedAt = position; this.position = position }
        override fun snapshot() = EngineSnapshot(position, 60.0, false, !closed, ended = ended)
        override fun mute(muted: Boolean) {}
        override fun togglePause() {}
        override fun seek(seconds: Double) { seeks += seconds; position = seconds }
        override fun volume(value: Int) {}
        override fun selectAudio(index: Int) = true
        override fun selectSubtitle(index: Int) = true
        override fun fullscreen(enabled: Boolean) {}
        override fun close() { closed = true }
        override fun preferences(prefs: PlaybackPreferences) { this.prefs = prefs }
        override fun speed(value: Float): Boolean { speeds += value; return true }
        override fun subtitleDelay(ms: Int) { delays += ms }
        override fun passthroughActive() = passthrough
    }

    private class Fixture : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url get() = "http://127.0.0.1:${server.address.port}"
        @Volatile var segments = """{"Items":[]}"""
        private fun episode(id: String, index: Int, extra: String = "") =
            """{"Id":"$id","Name":"Episode $index","Type":"Episode","SeriesId":"show","SeasonId":"season","ParentIndexNumber":1,"IndexNumber":$index,"RunTimeTicks":600000000,"UserData":{}$extra}"""
        private val chapters = ""","Chapters":[{"StartPositionTicks":0,"Name":"Opening"},{"StartPositionTicks":300000000,"Name":"Middle"}]"""
        init {
            server.executor = executor
            server.createContext("/") { e ->
                val path = e.requestURI.path
                val request = e.requestBody.bufferedReader().use { it.readText() }
                requests.add(path)
                val query = e.requestURI.rawQuery.orEmpty().split("&")
                var status = 200
                val body = when {
                    path == "/System/Info/Public" -> """{"Id":"server","ServerName":"Demo","ProductName":"Jellyfin Server","Version":"12.4.0"}"""
                    path == "/Users/Public" -> "[]"
                    path == "/Users/AuthenticateByName" -> """{"ServerId":"server","User":{"Id":"viewer","Name":"Viewer"},"AccessToken":"test-token"}"""
                    path == "/Users/Me" -> """{"Id":"viewer","Name":"Viewer"}"""
                    path == "/UserViews" -> """{"Items":[],"TotalRecordCount":0}"""
                    path == "/UserItems/Resume" || path == "/Shows/NextUp" -> """{"Items":[],"TotalRecordCount":0}"""
                    path == "/Items" && query.any { it.startsWith("ids=") } -> {
                        val id = query.first { it.startsWith("ids=") }.removePrefix("ids=")
                        """{"Items":[${if (id == "movie") movie else episode(id, if (id == "e1") 1 else 2)}],"TotalRecordCount":1}"""
                    }
                    path.endsWith("/Items/movie") -> movie
                    path.endsWith("/Items/e1") -> episode("e1", 1, if ("fields=Chapters%2CTrickplay" in query || "fields=Chapters,Trickplay" in query) chapters else "")
                    path.endsWith("/Items/e2") -> episode("e2", 2)
                    path.startsWith("/MediaSegments/") -> segments
                    path == "/Shows/show/Episodes" -> """{"Items":[${episode("e1", 1)},${episode("e2", 2)}],"TotalRecordCount":2}"""
                    path.endsWith("/PlaybackInfo") -> """{"PlaySessionId":"session","MediaSources":[{"Id":"source","Container":"mkv","SupportsDirectPlay":true,"RunTimeTicks":600000000,"DefaultAudioStreamIndex":1,"MediaStreams":[{"Type":"Video","Index":0,"Codec":"h264"},{"Type":"Audio","Index":1,"Codec":"aac","Language":"eng"},{"Type":"Subtitle","Index":2,"Codec":"subrip","Language":"eng"${if (Regex("\"SubtitleStreamIndex\"\\s*:\\s*2").containsMatchIn(request)) ",\"DeliveryMethod\":\"External\",\"DeliveryUrl\":\"/Videos/e1/Subtitles/2/Stream.srt\"" else ""}}]}]}"""
                    path.startsWith("/UserPlayedItems/") -> """{"Played":true}"""
                    path.startsWith("/Sessions/") || path.startsWith("/Videos/ActiveEncodings") -> ""
                    path == "/Items" || path == "/Users/viewer/Items" -> """{"Items":[],"TotalRecordCount":0}"""
                    else -> { status = 404; "" }
                }
                e.responseHeaders.set("Content-Type", "application/json")
                if (body.isEmpty()) e.sendResponseHeaders(status, -1) else { val bytes = body.toByteArray(); e.sendResponseHeaders(status, bytes.size.toLong()); e.responseBody.write(bytes) }
                e.close()
            }
            server.start()
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
        companion object { const val movie = """{"Id":"movie","Name":"A picture","Type":"Movie","RunTimeTicks":600000000,"UserData":{"PlaybackPositionTicks":300000000}}""" }
    }

    private val e1 = MediaItem("e1", "Episode 1", "Episode", seriesId = "show")
    private val movie = MediaItem("movie", "A picture", "Movie")
    private val intro = """{"Id":"intro","Type":"Intro","StartTicks":50000000,"EndTicks":200000000}"""
    private val outro = """{"Id":"outro","Type":"Outro","StartTicks":500000000,"EndTicks":580000000}"""

    private suspend fun DesktopController.await(predicate: (DesktopState) -> Boolean): DesktopState = withTimeout(10000) { state.first(predicate) }
    private suspend fun poll(condition: () -> Boolean) = withTimeout(10000) { while (!condition()) delay(20) }
    private suspend fun DesktopController.login(f: Fixture) {
        start(); await { it.route == DesktopRoute.Servers && !it.busy }
        connect(f.url); await { it.route == DesktopRoute.SignIn && !it.busy }
        signIn("Viewer", "correct"); await { it.route == DesktopRoute.Home && !it.busy }
    }
    private suspend fun DesktopController.playback(change: (PlaybackPreferences) -> PlaybackPreferences) {
        val s = state.value.settings
        saveSettings(s.copy(playback = change(s.playback)))
        await { it.route == DesktopRoute.Settings && !it.busy && it.settings.playback == change(s.playback) }
    }
    private fun directory() = Files.createTempDirectory("pictureshow-desktop-extras-").toFile()

    @Test fun chaptersLoadAndChoosingOneSeeksOnce(): Unit = runBlocking {
        Fixture().use { f ->
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(e1, fromStart = true)
                val p = c.await { it.playback?.ready == true && it.playback.extras.chapters.size == 2 }.playback!!
                assertEquals(listOf("Opening", "Middle"), p.extras.chapters.map { it.name })
                c.seekChapter(1)
                assertEquals(listOf(30.0), engine.seeks)
                c.seekChapter(7)
                assertEquals(1, engine.seeks.size, "a chapter that isn't there does nothing")
            }
        }
    }

    @Test fun automaticIntroSkipsOnceAndAskWaitsForTheButton(): Unit = runBlocking {
        Fixture().use { f ->
            f.segments = """{"Items":[$intro]}"""
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.playback { it.copy(segmentModes = it.segmentModes + (SegmentType.Intro to SegmentMode.Automatic)) }
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.extras.segments.isNotEmpty() }
                engine.position = 6.0
                poll { engine.seeks.isNotEmpty() }
                assertEquals(listOf(20.0), engine.seeks)
                c.await { "intro" in it.playback!!.extras.autoSkipped }
                engine.position = 7.0
                delay(1200)
                assertEquals(listOf(20.0), engine.seeks, "Automatic skips each segment once per viewing")
            }
            val asked = Engine()
            DesktopController(directory(), playbackFactory = { asked }).use { c ->
                c.login(f)
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.extras.segments.isNotEmpty() }
                asked.position = 6.0
                c.await { (it.playback?.positionSeconds ?: 0.0) >= 6.0 }
                delay(800)
                assertTrue(asked.seeks.isEmpty(), "Ask never skips by itself")
                c.skipSegment()
                assertEquals(listOf(20.0), asked.seeks)
            }
        }
    }

    @Test fun speedAndTimingReachTheEngineAndSurviveTheDownloadedSubtitleReopen(): Unit = runBlocking {
        Fixture().use { f ->
            val engines = CopyOnWriteArrayList<Engine>()
            DesktopController(directory(), playbackFactory = { Engine().also { engines += it } }).use { c ->
                c.login(f)
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true }
                c.setSpeed(1.5f)
                c.stepSubtitleDelay(1); c.stepSubtitleDelay(1); c.stepSubtitleDelay(-1); c.stepSubtitleDelay(1)
                val extras = c.state.value.playback!!.extras
                assertEquals(1.5f, extras.speed); assertEquals(200, extras.subtitleDelayMs)
                assertEquals(listOf(1.5f), engines.first().speeds); assertEquals(listOf(100, 200, 100, 200), engines.first().delays)
                engines.first().position = 25.0
                c.await { (it.playback?.positionSeconds ?: 0.0) >= 25.0 }
                c.adoptDownloadedSubtitle(2, 1)
                c.await { engines.size == 2 && it.playback?.ready == true && it.playback.subtitleIndex == 2 }
                assertEquals(25.0, engines.last().openedAt)
                assertEquals(listOf(1.5f), engines.last().speeds, "speed carries into the reopened viewing")
                assertEquals(listOf(200), engines.last().delays, "so does subtitle timing")
                assertEquals(1.5f, c.state.value.playback!!.extras.speed)
                c.stepSubtitleDelay(0)
                assertEquals(0, c.state.value.playback!!.extras.subtitleDelayMs); assertEquals(0, engines.last().delays.last())
            }
        }
    }

    @Test fun speedStaysAtOneWhileEncodedAudioPassesThrough(): Unit = runBlocking {
        Fixture().use { f ->
            val engine = Engine().apply { passthrough = true }
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true }
                c.setSpeed(2f)
                val extras = c.state.value.playback!!.extras
                assertEquals(1f, extras.speed); assertEquals(PlaybackSpeeds.ENCODED_AUDIO, extras.speedMessage)
                assertTrue(engine.speeds.isEmpty(), "the engine is never asked for a rate passthrough can't follow")
            }
        }
    }

    @Test fun resumeIsExactUnlessRewindIsChosenAndSettingsReachTheEngine(): Unit = runBlocking {
        Fixture().use { f ->
            val first = Engine()
            DesktopController(directory(), playbackFactory = { first }).use { c ->
                c.login(f)
                c.play(movie)
                c.await { it.playback?.ready == true }
                assertEquals(30.0, first.openedAt)
                assertFalse(first.prefs!!.passthrough)
            }
            val second = Engine()
            DesktopController(directory(), playbackFactory = { second }).use { c ->
                c.login(f)
                c.playback { it.copy(subtitleSize = SubtitleSize.Large, tuning = it.tuning.copy(rewindSeconds = 10)) }
                c.play(movie)
                c.await { it.playback?.ready == true }
                assertEquals(20.0, second.openedAt)
                assertEquals(SubtitleSize.Large, second.prefs!!.subtitleSize)
            }
        }
    }

    @Test fun countdownComesFromSettingsAndStillWatchingAsksInstead(): Unit = runBlocking {
        Fixture().use { f ->
            val engines = CopyOnWriteArrayList<Engine>()
            DesktopController(directory(), playbackFactory = { Engine().also { engines += it } }).use { c ->
                c.login(f)
                c.playback { it.copy(tuning = it.tuning.copy(nextEpisodeCountdownSeconds = 30)) }
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.nextEpisode != null }
                engines.last().ended = true
                val card = c.await { it.playback?.end != null }.playback!!.end!!
                assertEquals(30, card.secondsLeft); assertFalse(card.stillWatching)
            }
            engines.clear()
            DesktopController(directory(), playbackFactory = { Engine().also { engines += it } }).use { c ->
                c.login(f)
                c.playback { it.copy(tuning = it.tuning.copy(stillWatching = StillWatching.TwoEpisodes)) }
                c.viewerInput.automaticallyAdvanced()
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.nextEpisode != null }
                engines.last().ended = true
                val card = c.await { it.playback?.end != null }.playback!!.end!!
                assertTrue(card.stillWatching); assertNull(card.secondsLeft, "nothing counts down while the question is up")
                delay(1200)
                assertEquals("e1", c.state.value.playback?.item?.id)
                c.continueWatching()
                c.await { it.playback?.item?.id == "e2" && it.playback.ready }
                assertFalse(c.viewerInput.due(StillWatching.TwoEpisodes), "answering counts as the viewer being there")
            }
        }
    }

    @Test fun creditsCountdownShowsOverTheCreditsAndCancelHoldsIt(): Unit = runBlocking {
        Fixture().use { f ->
            f.segments = """{"Items":[$outro]}"""
            val engine = Engine()
            DesktopController(directory(), playbackFactory = { engine }).use { c ->
                c.login(f)
                c.playback { it.copy(tuning = it.tuning.copy(postCredits = PostCredits.NextEpisode, nextEpisodeCountdownSeconds = 5)) }
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.nextEpisode != null && it.playback.extras.segments.isNotEmpty() }
                delay(700)
                assertNull(c.state.value.playback!!.extras.creditsSecondsLeft, "no card before the credits")
                engine.position = 51.0
                val left = c.await { it.playback?.extras?.creditsSecondsLeft != null }.playback!!.extras.creditsSecondsLeft!!
                assertTrue(left in 1..5, "counts from the setting: $left")
                c.cancelCreditsCountdown()
                assertNull(c.state.value.playback!!.extras.creditsSecondsLeft)
                delay(6000)
                assertEquals("e1", c.state.value.playback?.item?.id, "Cancel lets the credits play on")
                assertNull(c.state.value.playback!!.extras.creditsSecondsLeft)
            }
        }
    }

    @Test fun creditsCountdownStartsTheNextEpisodeOnce(): Unit = runBlocking {
        Fixture().use { f ->
            f.segments = """{"Items":[$outro]}"""
            val engines = CopyOnWriteArrayList<Engine>()
            DesktopController(directory(), playbackFactory = { Engine().also { engines += it } }).use { c ->
                c.login(f)
                c.playback { it.copy(tuning = it.tuning.copy(postCredits = PostCredits.NextEpisode, nextEpisodeCountdownSeconds = 5)) }
                c.play(e1, fromStart = true)
                c.await { it.playback?.ready == true && it.playback.nextEpisode != null && it.playback.extras.segments.isNotEmpty() }
                engines.last().position = 51.0
                c.await { it.playback?.item?.id == "e2" && it.playback.ready }
                assertEquals(2, engines.size, "one next episode")
                assertEquals(1, c.viewerInput.automaticAdvances)
            }
        }
    }

    @Test fun serverPreferencesKeepUnknownFieldsAndRevertAFailedSave(): Unit = runBlocking {
        val account = Account(Server("s", "Demo", "http://127.0.0.1:1", "12.4.0"), User("u", "Viewer"), "token")
        val saved = CopyOnWriteArrayList<UserConfiguration>()
        var fail = false
        val service = object : UserConfigurationService {
            override suspend fun configuration(account: Account) = ClientResult.Success(UserConfiguration(buildJsonObject {
                put("AudioLanguagePreference", "eng"); put("SubtitleMode", "Default"); put("HidePlayedInLatest", true)
            }))
            override suspend fun saveConfiguration(account: Account, configuration: UserConfiguration): ClientResult<Unit> {
                saved += configuration
                return if (fail) ClientResult.Failure(ClientError(ErrorKind.SERVER, "Server error.")) else ClientResult.Success(Unit)
            }
        }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val prefs = DesktopServerPreferences(service, scope) { account }
            prefs.load(); poll { prefs.state.value.configuration != null }
            prefs.save("SubtitleMode", "Always"); poll { !prefs.state.value.saving }
            assertEquals("Always", prefs.state.value.configuration!!.subtitleMode)
            assertEquals(JsonPrimitive(true), saved.single().fields["HidePlayedInLatest"], "fields Pictureshow doesn't show survive the save")
            fail = true
            prefs.save("AudioLanguagePreference", "fra"); poll { !prefs.state.value.saving }
            assertEquals("eng", prefs.state.value.configuration!!.audioLanguage, "a failed save shows the server's value again")
            assertNotNull(prefs.state.value.error)
        } finally { scope.cancel() }
    }
}
