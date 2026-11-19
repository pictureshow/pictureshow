/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow.desktop

import app.pictureshow.core.Account
import app.pictureshow.core.ClientIdentity
import app.pictureshow.core.MediaItem
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.RemoteEffect
import app.pictureshow.core.playback.PlaybackPreferences
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

/** Stream E: the desktop glue between the ported hosts, the mpv player and the server socket. */
class DesktopTogetherTest {
    /**
     * Every rig, so a failed assertion still cancels its hosts. Their poll loops run on the test scheduler, and runTest's
     * closing advanceUntilIdle never returns while one is alive (a failing test hung a worker for a day on 2026-11-17).
     */
    private val rigs = CopyOnWriteArrayList<Rig>()
    private fun together(body: suspend TestScope.() -> Unit) = runTest(timeout = 30.seconds) {
        try { body() } finally { rigs.forEach { it.close() }; rigs.clear() }
    }
    private val viewer = "a".repeat(32)
    private val film = "c".repeat(32)
    private val account = Account(Server("server", "Server", "http://server.example/jf", "10.11.0"), User(viewer, "Viewer"), "token")

    private class Socket(val url: String, val events: RemoteSocketEvents) : RemoteSocket {
        var closed = false
        override fun send(text: String) = true
        override fun close() { closed = true; events.closed() }
    }

    private class Playback : DesktopTogetherPlayback {
        val calls = CopyOnWriteArrayList<String>()
        var video: DesktopTogetherVideo? = null
        var admit: (suspend (Boolean) -> Boolean)? = null
        override fun video() = video
        override fun music(): DesktopMusic? = null
        /** True in the music cases; starts are recorded rather than played. */
        var musicHere = false
        override fun hasMusic() = musicHere
        override fun playMusic(play: DesktopMusicPlay) { calls += "music-play:${play.tracks.map { it.id }}" }
        override fun queueMusic(owner: Account, tracks: List<DesktopMusicTrack>, next: Boolean) { calls += "music-queue:$next" }
        override fun open(itemId: String, startMs: Long?, admit: suspend (Boolean) -> Boolean) { calls += "open:$itemId:$startMs"; this.admit = admit }
        override fun toggle() { calls += "toggle" }
        override fun seek(positionMs: Long) { calls += "seek:$positionMs" }
        override fun stop() { calls += "stop" }
        override fun nextEpisode() { calls += "next-episode" }
        override fun audio(index: Int) { calls += "audio:$index" }
        override fun subtitle(index: Int) { calls += "subtitle:$index" }
        override fun engineSeek(positionMs: Long) { calls += "engine-seek:$positionMs" }
        /** mpv before its core is initialized: commands throw "core not initialized" (macOS recheck log). */
        @Volatile var engineUp = true
        override fun enginePlaying(playing: Boolean) {
            check(engineUp) { "core not initialized" }
            calls += "engine-playing:$playing"
        }
        override fun engineSpeed(rate: Double): Boolean { calls += "engine-speed:$rate"; return true }
    }

    private inner class Rig(val test: TestScope, receiver: String = "same-viewer") {
        val sockets = mutableListOf<Socket>()
        val posted = CopyOnWriteArrayList<String>()
        val playback = Playback()
        val logs = CopyOnWriteArrayList<String>()
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            posted += path
            when {
                path.endsWith("/Users/Me") -> respond("""{"Policy":{"SyncPlayAccess":"CreateAndJoinGroups"}}""", HttpStatusCode.OK, json)
                path.endsWith("/SyncPlay/List") -> respond("""[{"GroupId":"g","GroupName":"Evening","State":"Idle","Participants":["Viewer"],"LastUpdatedAt":"2026-11-18T20:00:00.0000000Z"}]""", HttpStatusCode.OK, json)
                else -> respond("", HttpStatusCode.NoContent)
            }
        })
        val receiver = MutableStateFlow(receiver)
        val accounts = MutableStateFlow<Account?>(account)
        @Volatile var allowed = true
        val together = DesktopTogether(playback, http, ClientIdentity("Pictureshow Desktop", "install"), StandardTestDispatcher(test.testScheduler),
            onHost = { true }, sockets = { url, _, events -> Socket(url, events).also { sockets += it } }, log = { logs += it }, allowed = { allowed }, nowMs = { test.testScheduler.currentTime })
        /** The mock server answers on ktor's threads, outside the test scheduler: wait (bounded) until [done]. */
        fun until(what: String, done: () -> Boolean) {
            repeat(300) {
                test.runCurrent()
                if (done()) return
                Thread.sleep(10)
            }
            kotlin.test.fail("timed out waiting for $what")
        }
        fun online() {
            together.start(accounts, receiver)
            until("the socket") { sockets.isNotEmpty() }
            sockets.last().events.opened()
            test.runCurrent()
        }
        init { rigs += this }
        fun message(text: String) { sockets.last().events.message(text); test.runCurrent() }
        /** Joins group "g" the way the page does: refresh, Join, then the server's GroupJoined on the renewed socket. */
        fun joinGroup() {
            together.sync.show()
            until("access and groups") { together.sync.state.value.let { it.policyKnown && it.groups.isNotEmpty() && !it.busy } }
            val before = sockets.size
            together.sync.join("g")
            // Joining renews the socket so frames from the old membership can't enter the new one.
            until("the renewed socket") { sockets.size > before }
            sockets.last().events.opened()
            // Jellyfin sends this once the socket belongs to the session; only then may Join go out.
            message("""{"MessageType":"ForceKeepAlive","Data":60}""")
            until("the Join request") { posted.any { it.endsWith("SyncPlay/Join") } }
            message(groupJoined())
            assertTrue(together.participating, "joined: ${together.sync.state.value}")
        }
        var closed = false
        fun close() { if (closed) return; closed = true; together.close(); http.close() }
    }

    private fun play(items: List<String>) =
        """{"MessageId":"p1","MessageType":"Play","Data":{"ItemIds":[${items.joinToString(",") { "\"$it\"" }}],"PlayCommand":"PlayNow","StartPositionTicks":30000000,"ControllingUserId":"$viewer"}}"""

    private fun state(id: String, command: String, extra: String = "") =
        """{"MessageId":"$id","MessageType":"Playstate","Data":{"Command":"$command","ControllingUserId":"$viewer"$extra}}"""

    private fun general(id: String, name: String, arguments: String) =
        """{"MessageId":"$id","MessageType":"GeneralCommand","Data":{"Name":"$name","ControllingUserId":"$viewer","Arguments":$arguments}}"""

    private fun groupJoined() =
        """{"MessageType":"SyncPlayGroupUpdate","Data":{"GroupId":"g","Type":"GroupJoined","Data":{"GroupId":"g","GroupName":"Evening","State":"Idle","Participants":["Viewer"],"LastUpdatedAt":"2026-11-18T20:00:00.0000000Z"}}}"""

    @Test fun theDesktopIsAControllableSessionOnTheSignedInServer() = together {
        val rig = Rig(this)
        rig.online()
        assertEquals("ws://server.example/jf/socket", rig.sockets.single().url)
        rig.until("capabilities to be reported") { rig.posted.any { it.endsWith("Sessions/Capabilities/Full") } }
        rig.close()
    }

    @Test fun offInSettingsKeepsTheGroupSocketButTakesNoRemotePlayAndSigningOutClosesIt() = together {
        // As on Android: the socket also carries Watch together, so it stays; "off" withdraws the remote commands.
        val off = Rig(this, receiver = "off")
        off.online()
        off.message(play(listOf(film)))
        assertEquals(emptyList(), off.playback.calls, "a remote Play while Off opens nothing")
        off.close()
        val rig = Rig(this)
        rig.online()
        rig.accounts.value = null
        runCurrent()
        assertTrue(rig.sockets.single().closed)
        rig.close()
    }

    @Test fun aRemotePlayOpensTheTitleInThePlayerAtItsStartAndReportsTheOutcome() = together {
        val rig = Rig(this)
        rig.online()
        rig.message(play(listOf(film)))
        assertEquals(listOf("open:$film:3000"), rig.playback.calls)
        var accepted: Boolean? = null
        backgroundScope.launch { accepted = rig.playback.admit!!(true) }
        runCurrent()
        assertEquals(true, accepted, "the open is still current, so the player may change")
        rig.close()
    }

    @Test fun remoteTransportDrivesTheBoundVideoAndAStoppedViewingTakesNothing() = together {
        val rig = Rig(this)
        rig.online()
        val token = Any()
        rig.playback.video = DesktopTogetherVideo(token, film, "Movie", 1_000, 10_000, ready = true, paused = false, live = false)
        rig.together.observe()
        rig.message(state("a", "Pause"))
        rig.message(state("b", "Seek", ""","SeekPositionTicks":50000000"""))
        rig.message(state("c", "Unpause"))
        rig.message(general("d", "SetAudioStreamIndex", """{"Index":"2"}"""))
        rig.message(general("e", "SetSubtitleStreamIndex", """{"Index":"-1"}"""))
        rig.message(state("f", "Stop"))
        // Unpause while already playing is no toggle: the bridge reads the player's state first.
        assertEquals(listOf("toggle", "seek:5000", "audio:2", "subtitle:-1", "stop"), rig.playback.calls.filter { !it.startsWith("engine") })
        rig.playback.calls.clear()
        rig.playback.video = null
        rig.together.observe()
        rig.message(state("g", "Pause"))
        assertEquals(emptyList(), rig.playback.calls.filter { !it.startsWith("engine") })
        rig.close()
    }

    @Test fun outsideAGroupTheViewersOwnControlsStayLocal() = together {
        val rig = Rig(this)
        rig.online()
        assertFalse(rig.together.transport("pause"))
        assertFalse(rig.together.transport("seek", 5_000))
        assertFalse(rig.together.ended(), "a genuine end outside a group keeps local autoplay")
        assertFalse(groupQueueRequest(rig.together.sync, RemoteEffect("open", listOf(film), 0, null, generation = 1)), "a remote Play outside a group opens here")
        rig.close()
    }

    @Test fun theReceiverSettingIsSavedAndAnUnknownValueFallsBack() {
        val p = Properties()
        writeDesktopPlayback(p, desktopDefaultPlayback().copy(remoteReceiver = "server-allowed"))
        assertEquals("server-allowed", readDesktopPlayback(p).remoteReceiver)
        writeDesktopPlayback(p, desktopDefaultPlayback().copy(remoteReceiver = "off"))
        assertEquals("off", readDesktopPlayback(p).remoteReceiver)
        p.setProperty("remoteReceiver", "everyone")
        assertEquals("same-viewer", readDesktopPlayback(p).remoteReceiver)
        assertEquals("same-viewer", readDesktopPlayback(Properties()).remoteReceiver)
        assertEquals(PlaybackPreferences().remoteReceiver, DESKTOP_REMOTE_RECEIVER_CHOICES.first().first)
    }

    @Test fun desktopRemoteOpensOnlyVideoTypes() {
        assertTrue("Movie" in DESKTOP_REMOTE_VIDEO_TYPES && "Episode" in DESKTOP_REMOTE_VIDEO_TYPES)
        assertFalse("Audio" in DESKTOP_REMOTE_VIDEO_TYPES || "Series" in DESKTOP_REMOTE_VIDEO_TYPES)
    }

    @Test fun inAGroupTheViewersTransportGoesToTheGroupAndEndingLeavesNextToTheGroup() = together {
        val rig = Rig(this)
        rig.online()
        rig.playback.video = DesktopTogetherVideo(Any(), film, "Movie", 1_000, 600_000, ready = true, paused = true, live = false)
        rig.together.observe()
        rig.joinGroup()
        rig.posted.clear()
        assertTrue(rig.together.transport("play"), "the group takes the viewer's Play")
        rig.until("Play to reach the group: ${rig.posted}") { rig.posted.any { it.endsWith("SyncPlay/Unpause") } }
        assertFalse(rig.playback.calls.any { it == "toggle" }, "and not straight to this player")
        assertTrue(rig.together.ended(), "a genuine end in a group: the group decides what plays next, no local autoplay")
        assertTrue(groupQueueRequest(rig.together.sync, RemoteEffect("open", listOf(film), 0, null, generation = 1)), "a remote Play in a group goes to the group queue")
    }

    @Test fun aPlayerThatRefusesThePauseBeforeItIsUpStillGetsItAndBackShowsTheStayBar() = together {
        val rig = Rig(this)
        rig.online()
        rig.joinGroup()
        rig.playback.engineUp = false
        val viewing = Any()
        rig.playback.video = DesktopTogetherVideo(viewing, film, "Movie", 0, 600_000, ready = false, paused = false, live = false)
        testScheduler.advanceTimeBy(DesktopTogether.OBSERVE_MS * 2)
        runCurrent()
        assertTrue(rig.logs.any { "engine not ready" in it }, "the refused pause is held: ${rig.logs}")
        rig.playback.engineUp = true
        testScheduler.advanceTimeBy(DesktopTogether.OBSERVE_MS * 2)
        runCurrent()
        assertTrue("engine-playing:false" in rig.playback.calls, "the group's pause reaches the engine once it is up: ${rig.playback.calls}")
        // Back: the viewing closes, and the hosts must still be watching to see it go.
        rig.together.closing(app.pictureshow.core.sync.SyncCloseReason.STOP_BUTTON)
        rig.playback.video = null
        testScheduler.advanceTimeBy(DesktopTogether.OBSERVE_MS * 2)
        runCurrent()
        assertTrue(rig.together.participating)
        assertTrue(rig.together.sync.stayReminder != null, "Still in Evening · Leave shows under the page")
    }

    @Test fun leavingTheGroupHandsTheControlsBack() = together {
        val rig = Rig(this)
        rig.online()
        rig.playback.video = DesktopTogetherVideo(Any(), film, "Movie", 1_000, 600_000, ready = true, paused = true, live = false)
        rig.together.observe()
        rig.joinGroup()
        rig.together.sync.action("leave", null)
        rig.until("the Leave request") { rig.posted.any { it.endsWith("SyncPlay/Leave") } }
        assertFalse(rig.together.participating)
        assertFalse(rig.together.transport("play"), "after leaving, Play is this device's own again")
    }

    @Test fun aDownloadedCopyCantBeTheGroupsSource() = together {
        val rig = Rig(this)
        rig.online()
        rig.playback.video = DesktopTogetherVideo(Any(), film, "Movie", 1_000, 600_000, ready = true, paused = true, live = false, offline = true)
        rig.together.observe()
        rig.together.sync.show()
        rig.until("access and groups") { rig.together.sync.state.value.let { it.policyKnown && it.groups.isNotEmpty() && !it.busy } }
        rig.together.sync.join("g")
        runCurrent()
        assertEquals(SyncNoticeKind.SOURCE_UNAVAILABLE, rig.together.sync.state.value.notice?.kind, "a local copy can't join: ${rig.together.sync.state.value}")
        assertFalse(rig.together.participating)
        assertFalse(rig.posted.any { it.endsWith("SyncPlay/Join") })
    }

    private fun musicRig(test: TestScope, lockDuringLookup: Boolean): Rig {
        val rig = Rig(test)
        val track = "d".repeat(32)
        rig.playback.musicHere = true
        rig.together.lookups(item = { _, id -> app.pictureshow.core.ClientResult.Success(MediaItem(id)) }, items = { _, ids ->
            // The profile locks while this lookup is on the wire.
            if (lockDuringLookup) rig.allowed = false
            ids.map { MediaItem(it, type = "Audio") }
        })
        rig.online()
        rig.message(play(listOf(track)))
        rig.until("the remote's music open to settle") { rig.playback.calls.isNotEmpty() || rig.logs.any { "music open refused" in it } }
        return rig
    }

    @Test fun aRemoteMusicPlayStartsTheTracks() = together {
        val rig = musicRig(this, lockDuringLookup = false)
        assertEquals(listOf("music-play:[${"d".repeat(32)}]"), rig.playback.calls)
    }

    @Test fun aProfileThatLocksDuringTheLookupStartsNoMusic() = together {
        val rig = musicRig(this, lockDuringLookup = true)
        assertEquals(emptyList(), rig.playback.calls.filter { it.startsWith("music") }, "a locked profile starts nothing")
        assertEquals(emptyList(), rig.playback.calls.filter { it.startsWith("open") }, "nor falls back to the video player")
    }
}
