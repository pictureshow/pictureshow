/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow.desktop

import androidx.compose.ui.input.key.Key
import app.pictureshow.core.*
import app.pictureshow.core.playback.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class DesktopMusicTest {
    /** The output as the host sees it: what it holds and what it says it's doing. The test moves it along. */
    private class FakeOutput : DesktopMusicOutput {
        val held = CopyOnWriteArrayList<DesktopMusicSource>()
        @Volatile var index = -1
        @Volatile var position = 0.0
        @Volatile var duration = 0.0
        @Volatile var paused = true
        @Volatile var ended = false
        @Volatile var closed = false
        @Volatile var replacedAt: Double? = null
        override fun decoders() = setOf(AudioCodec.FLAC, AudioCodec.MP3, AudioCodec.AAC)
        override fun replace(account: Account, sources: List<DesktopMusicSource>, startSeconds: Double, paused: Boolean) {
            held.clear(); held += sources; index = if (sources.isEmpty()) -1 else 0; position = startSeconds; replacedAt = startSeconds; this.paused = paused
        }
        override fun append(account: Account, sources: List<DesktopMusicSource>) { held += sources }
        // As mpv does: removing entries before the playing one moves playlist-pos down with them.
        override fun remove(from: Int, to: Int) { repeat(to - from) { held.removeAt(from); if (from < index) index-- } }
        override fun paused(value: Boolean) { paused = value }
        override fun seek(seconds: Double) { position = seconds }
        override fun volume(value: Int) {}
        override fun loopCurrent(value: Boolean) {}
        override fun state() = DesktopMusicOutputState(index, position, duration, paused, ended, false)
        override fun close() { closed = true }
    }

    private class FakeGateway : DesktopMusicGateway {
        val negotiated = CopyOnWriteArrayList<Pair<String, Int>>()
        val started = CopyOnWriteArrayList<String>()
        val progressed = CopyOnWriteArrayList<PlaybackProgressInfo>()
        val stopped = CopyOnWriteArrayList<PlaybackStopInfo>()
        @Volatile var gone = emptySet<String>()
        override suspend fun negotiate(account: Account, media: String, ceiling: Int, decoders: Set<AudioCodec>): ClientResult<PlayOutcome> {
            negotiated += media to ceiling
            return ClientResult.Success(PlayOutcome.Play(decision(media, "${account.server.url}/Audio/$media/stream")))
        }
        override suspend fun start(account: Account, info: PlaybackProgressInfo) { started += info.itemId }
        override suspend fun progress(account: Account, info: PlaybackProgressInfo) { progressed += info }
        override suspend fun stopped(account: Account, info: PlaybackStopInfo) { stopped += info }
        override suspend fun check(account: Account, media: List<String>): ClientResult<List<String>> = ClientResult.Success(media.filter { it !in gone })
    }

    private class Host(val dir: File, quality: String = "auto") : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val accounts = AccountStore(object : TextPersistence {
            var text: String? = null
            override suspend fun read() = text
            override suspend fun write(value: String) { text = value }
        })
        val gateway = FakeGateway()
        val outputs = CopyOnWriteArrayList<FakeOutput>()
        val logs = CopyOnWriteArrayList<String>()
        val music = DesktopMusic(scope, accounts, DesktopMusicStore(dir), gateway, { FakeOutput().also(outputs::add) }, { quality }, log = { logs += it }, tickMs = 10)
        val output get() = outputs.last()
        override fun close() { music.closeNow(); scope.cancel() }
    }

    private val account = Account(Server("server", "Demo", "http://127.0.0.1:9", "10.11.0"), User("viewer", "Viewer"), "token")
    private fun track(id: String, seconds: Long = 200) = DesktopMusicTrack(id, "Track $id", "Artist", "Album", "album", null, seconds * 10_000_000)
    private val album = listOf(track("a"), track("b"), track("c"))
    private fun albumPlay(start: Int? = null, context: String = "MusicAlbum:album") = DesktopMusicPlay(account.key, album, start, context, "Album")

    private suspend fun Host.signIn() {
        accounts.remember(account)
        withTimeout(5_000) { music.state.first { it.account?.key == account.key } }
        music.idle()
    }

    private suspend fun Host.until(what: String, check: () -> Boolean) {
        // A step can be seen half done from here, so the road is drained once more after the condition holds.
        try { withTimeout(5_000) { while (!check()) { music.idle(); delay(10) }; music.idle() } } catch (e: TimeoutCancellationException) { fail("Timed out waiting for $what; state=${music.state.value}; log=$logs; files=${dir.list()?.toList()}") }
    }

    private suspend fun Host.playing(output: FakeOutput = this.output, at: Double = 5.0) {
        output.duration = 200.0; output.position = at; output.paused = false
        // The first poll may already have reported it playing at 0, so wait for the host to have read this position.
        until("a start report at the position") { gateway.started.isNotEmpty() && music.state.value.positionMs >= (at * 1000).toLong() }
    }

    @Test
    fun `a play opens the audio output with the queue's window and reports the track once it plays`() = runBlocking {
        Host(dir()).use { h ->
            h.signIn()
            h.music.request(albumPlay(start = 1))
            h.until("the window") { h.outputs.isNotEmpty() && h.output.held.size >= 2 }
            assertEquals(listOf("b", "c"), h.output.held.map { h.music.state.value.queue!!.entries.first { e -> e.id == it.entry }.media })
            assertFalse(h.output.paused)
            assertEquals("b", h.music.state.value.current?.track?.id)
            h.playing()
            assertEquals(listOf("b"), h.gateway.started)
            // Auto on an unmetered computer asks for no cap (MUS-06).
            assertTrue(h.gateway.negotiated.all { it.second == app.pictureshow.core.music.musicBitrateCeiling("auto", false) })
        }
    }

    @Test
    fun `the output moving on by itself ends the track, reports it stopped and starts the next`() = runBlocking {
        Host(dir()).use { h ->
            h.signIn()
            h.music.request(albumPlay())
            h.until("the window") { h.outputs.isNotEmpty() && h.output.held.size >= 2 }
            h.playing(at = 199.5)
            h.output.index = 1; h.output.position = 0.2
            h.until("the queue to follow") { h.music.state.value.current?.track?.id == "b" }
            h.until("the stop and the next start") { h.gateway.stopped.size == 1 && h.gateway.started.size == 2 }
            assertEquals("a", h.gateway.stopped.single().itemId)
            assertFalse(h.gateway.stopped.single().failed)
            assertEquals(listOf("a", "b"), h.gateway.started)
            assertEquals(listOf("a"), h.music.state.value.played.map { it.track.id })
        }
    }

    @Test
    fun `a video start stops the music and leaves the queue paused where it was`() = runBlocking {
        Host(dir()).use { h ->
            h.signIn()
            h.music.request(albumPlay())
            h.until("the window") { h.outputs.isNotEmpty() && h.output.held.isNotEmpty() }
            h.playing(at = 42.0)
            h.music.beforeVideo()
            h.until("the output to close") { !h.music.state.value.outputActive }
            val ui = h.music.state.value
            assertTrue(h.output.closed)
            assertEquals("a", ui.current?.track?.id)
            assertTrue(ui.queue!!.paused)
            assertEquals(42_000, ui.positionMs)
            h.until("the stop report") { h.gateway.stopped.isNotEmpty() }
            // Nothing restarts it by itself (MUS-04): no new output opens until the viewer presses Play.
            delay(100); h.music.idle()
            assertEquals(1, h.outputs.size)
            h.music.play()
            h.until("a new output") { h.outputs.size == 2 && h.output.held.isNotEmpty() }
            assertEquals(42.0, h.output.replacedAt)
        }
    }

    @Test
    fun `the queue comes back paused after a restart, at its saved place, and drops what the server no longer has`() = runBlocking {
        val dir = dir()
        Host(dir).use { h ->
            h.signIn()
            h.music.request(albumPlay(start = 1))
            h.until("the window") { h.outputs.isNotEmpty() && h.output.held.isNotEmpty() }
            h.playing(at = 30.0)
            h.music.pause()
            h.until("the pause") { h.music.state.value.queue?.paused == true }
        }
        Host(dir).use { h ->
            h.gateway.gone = setOf("c")
            h.signIn()
            h.until("the restore") { h.music.state.value.current != null }
            val ui = h.music.state.value
            assertEquals("b", ui.current?.track?.id)
            assertEquals("Track b", ui.current?.track?.title)
            assertTrue(ui.queue!!.paused)
            assertFalse(ui.outputActive)
            assertEquals(30_000, ui.positionMs)
            assertEquals(listOf("a", "b"), ui.queue.entries.map { it.media })
            assertEquals("1 saved track is no longer available.", ui.message)
            assertTrue(h.outputs.isEmpty(), "a restored queue makes no sound until Play")
        }
    }

    @Test
    fun `signing a viewer out of this computer removes their saved queue, and other viewers keep theirs`() = runBlocking {
        val dir = dir()
        Host(dir).use { h ->
            val other = Account(Server("server", "Demo", "http://127.0.0.1:9", "10.11.0"), User("second", "Second"), "token2")
            h.accounts.remember(other)
            h.signIn()
            h.music.request(albumPlay())
            h.until("the save") { (dir.list()?.count { it.endsWith(".json") } ?: 0) == 1 }
            val mine = dir.list()!!.single { it.endsWith(".json") }
            h.accounts.select(other.key)
            h.until("the switch") { h.music.state.value.account?.key == other.key }
            assertTrue(mine in dir.list()!!, "a viewer still saved here keeps their queue")
            h.accounts.removeAccount(account.key)
            h.until("the removal") { mine !in dir.list()!! }
        }
    }

    @Test
    fun `playing another album asks before replacing the queue, and the same album jumps inside it`() = runBlocking {
        Host(dir()).use { h ->
            h.signIn()
            h.music.request(albumPlay())
            h.until("the queue") { h.music.state.value.current != null }
            val first = h.music.state.value.queue!!.entries.map { it.id }
            h.music.request(albumPlay(start = 2))
            h.until("the jump") { h.music.state.value.current?.track?.id == "c" }
            assertEquals(first, h.music.state.value.queue!!.entries.map { it.id })
            val other = DesktopMusicPlay(account.key, listOf(track("x"), track("y")), null, "MusicAlbum:other", "Other")
            h.music.request(other)
            h.until("the prompt") { h.music.state.value.prompt != null }
            assertEquals(first, h.music.state.value.queue!!.entries.map { it.id })
            h.music.confirm()
            h.until("the replacement") { h.music.state.value.queue!!.entries.map { it.media } == listOf("x", "y") }
            assertNull(h.music.state.value.prompt)
            assertEquals("Other", h.music.state.value.contextName)
        }
    }

    @Test
    fun `adding to an empty queue readies it without playing, and queue edits reach the output`() = runBlocking {
        Host(dir()).use { h ->
            h.signIn()
            h.music.append(account, listOf(track("a"), track("b")))
            h.until("the queue") { h.music.state.value.current != null }
            assertTrue(h.music.state.value.queue!!.paused)
            assertTrue(h.outputs.isEmpty())
            assertEquals("Added 2 tracks to the queue.", h.music.state.value.message)
            h.music.playNext(account, listOf(track("n")))
            h.until("play next") { h.music.state.value.upNext.map { it.track.id } == listOf("n", "b") }
            val b = h.music.state.value.upNext.last().entry
            h.music.move(b, 1)
            h.until("the move") { h.music.state.value.upNext.map { it.track.id } == listOf("b", "n") }
            h.music.remove(b)
            h.until("the removal") { h.music.state.value.upNext.map { it.track.id } == listOf("n") }
            h.music.clear()
            h.until("the clear") { h.music.state.value.current == null }
            assertFalse(h.music.state.value.visible)
        }
    }

    @Test
    fun `a downloaded track plays from its file without asking the server`() = runBlocking {
        Host(dir()).use { h ->
            val file = File(dir(), "a.flac").apply { writeText("") }
            h.music.local = object : DesktopMusicLocal {
                override fun decision(account: Account, media: String) = if (media == "a") decision("a", file.toURI().toString()) else null
                override fun downloaded(account: Account) = setOf("a")
                override suspend fun report(account: Account, media: String, serial: Long, report: String, ticks: Long, runtimeTicks: Long?) {}
            }
            h.signIn()
            h.music.request(albumPlay())
            h.until("the window") { h.outputs.isNotEmpty() && h.output.held.size >= 2 }
            assertTrue(h.output.held.first().local)
            assertEquals(file.path, desktopLocalMusicPath(h.output.held.first().decision.url))
            assertFalse(h.gateway.negotiated.any { it.first == "a" })
        }
    }

    @Test
    fun `mpv decoder names become the music profile's codecs`() {
        val codecs = desktopMusicDecoders(setOf("flac", "mp3", "pcm_s24le", "h264", "opus"))
        assertEquals(setOf(AudioCodec.FLAC, AudioCodec.MP3, AudioCodec.PCM, AudioCodec.OPUS), codecs)
        assertFalse(AudioCodec.AAC in desktopMusicDecoders(setOf("mp3")))
    }

    @Test
    fun `only file URLs and paths are local`() {
        assertEquals(File("/music/a.flac").path, desktopLocalMusicPath("file:/music/a.flac"))
        assertEquals("/music/a.flac", desktopLocalMusicPath("/music/a.flac"))
        assertEquals("C:\\Music\\a.flac", desktopLocalMusicPath("C:\\Music\\a.flac"))
        assertNull(desktopLocalMusicPath("http://127.0.0.1:9/Audio/a/stream"))
    }

    @Test
    fun `the saved queue file never names the viewer or the server`() = runBlocking {
        val dir = dir()
        Host(dir).use { h ->
            h.signIn()
            h.music.request(albumPlay())
            h.until("the save") { dir.listFiles()!!.any { it.name.endsWith(".json") } }
        }
        val names = dir.listFiles()!!.map { it.name }
        assertTrue(names.none { "viewer" in it || "server" in it || "127.0.0.1" in it }, names.toString())
        assertFalse(dir.listFiles()!!.filter { it.name.endsWith(".json") }.any { "token" in it.readText() })
    }

    @Test
    fun `music quality persists with the other settings`() {
        val file = File(dir(), "settings.properties")
        val store = DesktopSettingsStore(file)
        assertEquals("auto", store.read().musicQuality)
        store.write(DesktopSettings(musicQuality = "128"))
        assertEquals("128", DesktopSettingsStore(file).read().musicQuality)
        file.writeText(file.readText().replace("musicQuality=128", "musicQuality=999"))
        assertEquals("auto", DesktopSettingsStore(file).read().musicQuality)
    }

    @Test
    fun `K, Space and the media key toggle music only when music is queued and nothing else wants the key`() {
        fun action(key: Key, route: DesktopRoute = DesktopRoute.Home, music: Boolean = true, focused: Boolean = false) =
            shellFallbackAction(ShellKeyPress(key), route, focused, fullscreen = false, mac = false, signedIn = true, music = music)
        assertEquals(ShellAction.ToggleMusic, action(Key.K))
        assertEquals(ShellAction.ToggleMusic, action(Key.Spacebar))
        assertEquals(ShellAction.ToggleMusic, action(Key.MediaPlayPause, focused = true))
        assertEquals(ShellAction.ToggleMusic, action(Key.K, DesktopRoute.MusicNow))
        assertNotEquals(ShellAction.ToggleMusic, action(Key.Spacebar, focused = true))
        assertNotEquals(ShellAction.ToggleMusic, action(Key.K, music = false))
        assertNotEquals(ShellAction.ToggleMusic, action(Key.K, DesktopRoute.Player))
        assertNotEquals(ShellAction.ToggleMusic, action(Key.K, DesktopRoute.Search))
    }

    private companion object {
        fun dir(): File = Files.createTempDirectory("pictureshow-music-").toFile()
        fun decision(media: String, url: String) = PlayDecision(media, MediaSourceInfo(id = "source-$media", container = "flac"), PlayMethod.DirectPlay, url, emptyMap(), "session-$media")
    }
}
