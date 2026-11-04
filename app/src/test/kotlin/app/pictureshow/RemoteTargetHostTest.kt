/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.RemoteEffect
import app.pictureshow.core.control.RemoteViewing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RemoteTargetHostTest {
    private val viewer = "a".repeat(32)
    private val other = "b".repeat(32)
    private val film = "c".repeat(32)
    private val second = "d".repeat(32)
    private val third = "e".repeat(32)
    private val account = Account(Server("server", "Server", "http://server.example/jf", "12.4"), User(viewer, "Ana"), "token")

    private class FakeSocket(val url: String, val authorization: String, val events: RemoteSocketEvents) : RemoteSocket {
        val sent = mutableListOf<String>()
        var closed = false
        override fun send(text: String) = sent.add(text)
        override fun close() {
            closed = true
            events.closed()
        }
    }

    private class Player : RemotePlayerBridge {
        val calls = mutableListOf<String>()
        override fun pause() {
            calls += "pause"
        }
        override fun unpause() {
            calls += "unpause"
        }
        override fun report() {
            calls += "report"
        }
        override fun seekTo(positionMs: Long) {
            calls += "seek:$positionMs"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun next(item: String?) {
            calls += "next:$item"
        }
        override fun audio(index: Int) {
            calls += "audio:$index"
        }
        override fun subtitle(index: Int) {
            calls += "subtitle:$index"
        }
    }

    private inner class Rig(scope: TestScope) {
        val sockets = mutableListOf<FakeSocket>()
        val registered = mutableListOf<List<String>>()
        val opens = mutableListOf<RemoteEffect>()
        val navigation = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = RemoteTargetHost(
            scope.backgroundScope,
            { url, authorization, events -> FakeSocket(url, authorization, events).also { sockets += it } },
            register = { _, commands ->
                registered += commands
                204
            },
            authorization = { "auth:${it.user.id}" },
            nowMs = { scope.testScheduler.currentTime },
            trace = { trace += it }
        )
        val player = Player()
        val token = Any()
        init {
            scope.backgroundScope.launch { host.opens.collect { opens += it } }
            scope.backgroundScope.launch { host.navigation.collect { navigation += it } }
        }
        fun online(scope: TestScope) {
            host.bind(account, "same-viewer")
            host.setForeground(true)
            scope.runCurrent()
            sockets.last().events.opened()
            scope.runCurrent()
        }
        fun message(scope: TestScope, text: String) {
            sockets.last().events.message(text)
            scope.runCurrent()
        }
        fun watching(item: String = film, paused: Boolean = false) {
            host.setPlayer(token, player)
            host.report(RemoteViewing(item, true, 10_000_000, 1_000, paused, true))
        }
    }

    private fun play(id: String, items: List<String>, command: String = "PlayNow", controller: String = viewer) =
        """{"MessageId":"$id","MessageType":"Play","Data":{"ItemIds":[${items.joinToString(",") {
            "\"$it\""
        }}],"PlayCommand":"$command","ControllingUserId":"$controller"}}"""

    private fun state(id: String, command: String, controller: String = viewer, extra: String = "") =
        """{"MessageId":"$id","MessageType":"Playstate","Data":{"Command":"$command","ControllingUserId":"$controller"$extra}}"""

    private fun general(id: String, name: String, arguments: String = "{}") =
        """{"MessageId":"$id","MessageType":"GeneralCommand","Data":{"Name":"$name","ControllingUserId":"$viewer","Arguments":$arguments}}"""

    @Test fun socketFollowsForegroundAccountAndPolicyAndRegistersOnlyWhatIsImplemented() = runTest {
        val rig = Rig(this)
        rig.host.bind(account, "same-viewer")
        runCurrent()
        assertTrue(rig.sockets.isEmpty(), "a stopped activity is not a target")
        rig.host.setForeground(true)
        runCurrent()
        val socket = rig.sockets.single()
        assertEquals("ws://server.example/jf/socket", socket.url)
        assertEquals("auth:$viewer", socket.authorization)
        assertFalse(socket.url.contains("token"))
        socket.events.opened()
        runCurrent()
        val commands = rig.registered.single()
        assertTrue("SendString" in commands && "MoveUp" in commands && "SetAudioStreamIndex" in commands)
        assertFalse(commands.any { it.contains("Volume") || it.contains("Mute") }, "device volume is not implemented")
        rig.host.setForeground(false)
        runCurrent()
        assertTrue(socket.closed)
        rig.host.setForeground(true)
        runCurrent()
        assertEquals(2, rig.sockets.size)
        rig.host.bind(account, "off")
        runCurrent()
        assertTrue(rig.sockets.last().closed, "off means no socket and nothing advertised")
        rig.host.bind(null, "same-viewer")
        runCurrent()
        assertEquals(2, rig.sockets.size)
    }

    @Test fun capabilitiesFollowEveryReconnectAndKeepAliveAnswersTheServerInterval() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.message(this, """{"MessageId":"k","MessageType":"ForceKeepAlive","Data":10}""")
        advanceTimeBy(4_001)
        runCurrent()
        assertEquals(listOf("""{"MessageType":"KeepAlive"}"""), rig.sockets.last().sent)
        rig.sockets.last().events.closed()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, rig.sockets.size)
        rig.sockets.last().events.opened()
        runCurrent()
        assertEquals(2, rig.registered.size)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(1, rig.sockets.first().sent.size, "the old socket's heartbeat stops with it")
    }

    @Test fun inputHeldDuringPreparationIsLostOnReconnectAndNeverReachesTheNextConnection() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.message(this, play("p", listOf(film)))
        assertEquals(listOf(film), rig.opens.single().items)
        rig.message(this, state("a", "Pause"))
        rig.sockets.last().events.closed()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        rig.sockets.last().events.opened()
        runCurrent()
        rig.watching()
        runCurrent()
        assertTrue(rig.player.calls.isEmpty())
    }

    @Test fun lateMessageFromAClosedSocketIsIgnored() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        val old = rig.sockets.last()
        old.events.closed()
        runCurrent()
        old.events.message(state("late", "Stop"))
        runCurrent()
        assertTrue(rig.player.calls.isEmpty())
    }

    @Test fun titleStillResolvingForThePreviousViewerCannotOpen() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.message(this, play("p", listOf(film)))
        val generation = rig.opens.single().generation
        rig.host.bind(account.copy(user = User(other, "Ben")), "same-viewer")
        runCurrent()
        assertFalse(rig.host.resolved(generation, true))
        rig.sockets.last().events.opened()
        runCurrent()
        assertFalse(rig.host.resolved(generation, true))
    }

    @Test fun otherViewersCommandIsRefusedAndTracedWithoutAnyIdentifier() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        rig.message(this, state("a", "Stop", controller = other))
        assertTrue(rig.player.calls.isEmpty())
        val refused = rig.trace.last()
        assertEquals("remote refused reason=other-viewer", refused)
    }

    @Test fun transportReachesTheMountedPlayerOnlyAndAStaleTokenCannotClearANewOne() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        rig.message(this, state("a", "Pause"))
        rig.message(this, state("b", "Seek", extra = ""","SeekPositionTicks":5000000"""))
        rig.message(this, state("c", "Stop"))
        rig.message(this, general("d", "SetAudioStreamIndex", """{"Index":"2"}"""))
        assertEquals(listOf("pause", "seek:500", "stop", "audio:2"), rig.player.calls)
        val next = Player()
        val token = Any()
        rig.host.setPlayer(token, next)
        rig.host.clearPlayer(rig.token)
        rig.message(this, state("e", "Unpause"))
        assertEquals(listOf("report"), next.calls)
        rig.host.clearPlayer(token)
        rig.message(this, state("f", "Unpause"))
        assertEquals(listOf("report"), next.calls)
    }

    @Test fun queueGoesFirstThenFollowsWhatReallyStartedAndNothingQueuedWhenIdle() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.message(this, play("idle", listOf(second), "PlayNext"))
        assertNull(rig.host.upcoming)
        rig.watching(film)
        val before = rig.host.queueRevision
        rig.message(this, play("a", listOf(third), "PlayLast"))
        rig.message(this, play("b", listOf(second), "PlayNext"))
        assertEquals(second, rig.host.upcoming)
        assertTrue(rig.host.queueRevision > before)
        rig.message(this, state("n", "NextTrack"))
        assertEquals("next:$second", rig.player.calls.last())
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        assertEquals(third, rig.host.upcoming)
        rig.host.report(RemoteViewing(film, true, 10_000_000, 0, false, true))
        assertNull(rig.host.upcoming, "a title opened by hand ends the queue")
    }

    @Test fun previousRestartsPastThreeSecondsAndGoesBackBeforeThat() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching(film)
        rig.message(this, play("a", listOf(second), "PlayNext"))
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        rig.host.report(RemoteViewing(second, true, 10_000_000, 40_000_000, false, true))
        rig.message(this, state("b", "PreviousTrack"))
        assertEquals("seek:0", rig.player.calls.last())
        rig.host.report(RemoteViewing(second, true, 10_000_000, 10_000_000, false, true))
        rig.message(this, state("c", "PreviousTrack"))
        assertEquals("next:$film", rig.player.calls.last())
    }

    @Test fun searchIsTheOnlyTextDestinationAndNavigationStaysInTheApp() = runTest {
        val rig = Rig(this)
        rig.online(this)
        val typed = mutableListOf<String>()
        rig.message(this, general("a", "SendString", """{"String":"alien"}"""))
        assertTrue(typed.isEmpty())
        rig.host.setSearchField(typed::add)
        rig.message(this, general("b", "SendString", """{"String":"alien"}"""))
        assertEquals(listOf("alien"), typed)
        rig.host.setSearchField(null)
        rig.message(this, general("c", "SendString", """{"String":"more"}"""))
        assertEquals(listOf("alien"), typed)
        rig.message(this, general("d", "MoveUp"))
        rig.message(this, general("e", "DisplayMessage"))
        assertEquals(listOf("MoveUp"), rig.navigation)
        assertEquals(android.view.KeyEvent.KEYCODE_DPAD_UP, remoteKeyCode("MoveUp"))
        assertNull(remoteKeyCode("GoHome"))
        assertNull(remoteKeyCode("Power"))
    }

    @Test fun backoffIsBounded() {
        assertEquals(1_000, RemoteTargetHost.backoffMs(0))
        assertEquals(30_000, RemoteTargetHost.backoffMs(40))
    }
}
