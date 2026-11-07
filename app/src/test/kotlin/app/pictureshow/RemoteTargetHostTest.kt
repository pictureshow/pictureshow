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
import kotlinx.coroutines.CompletableDeferred
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
        var ownQueue = false
        override fun enqueue(items: List<String>, next: Boolean): Boolean {
            if (ownQueue) calls += "enqueue:$items:$next"
            return ownQueue
        }
        override fun previous(): Boolean {
            if (ownQueue) calls += "previous"
            return ownQueue
        }
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
        var gate: CompletableDeferred<Unit>? = null
        var status = 204
        val opens = mutableListOf<RemoteEffect>()
        val navigation = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val host = RemoteTargetHost(
            scope.backgroundScope,
            { url, authorization, events -> FakeSocket(url, authorization, events).also { sockets += it } },
            register = { _, commands ->
                gate?.await()
                registered += commands
                status
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

    @Test fun offWithdrawsWhatWasAdvertisedAndStopsCommandsAndOnAdvertisesAgain() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        val first = rig.sockets.single()
        assertTrue(rig.registered.single().isNotEmpty())
        rig.host.bind(account, "off")
        // The press that was already on its way is refused before the withdrawal has even been answered.
        first.events.message(state("late", "Pause"))
        runCurrent()
        assertTrue(first.closed)
        assertEquals(listOf(emptyList()), rig.registered.drop(1), "the picker must not keep listing a TV that said no")
        assertTrue(rig.player.calls.isEmpty())
        rig.host.setForeground(false)
        rig.host.setForeground(true)
        runCurrent()
        assertEquals(1, rig.sockets.size, "off stays off across a return to the foreground")
        assertEquals(2, rig.registered.size, "a withdrawal the server accepted is not repeated")
        rig.host.bind(account, "same-viewer")
        runCurrent()
        val second = rig.sockets.last()
        assertEquals(2, rig.sockets.size)
        second.events.opened()
        runCurrent()
        assertEquals(rig.registered.first(), rig.registered.last())
        rig.message(this, state("back", "Pause"))
        assertEquals(listOf("pause"), rig.player.calls)
    }

    @Test fun aWithdrawalAndARegistrationReachTheServerInThePressOrder() = runTest {
        val rig = Rig(this)
        val slow = CompletableDeferred<Unit>()
        rig.gate = slow
        rig.online(this)
        rig.host.bind(account, "off")
        rig.host.bind(account, "same-viewer")
        rig.host.bind(account, "off")
        runCurrent()
        assertTrue(rig.registered.isEmpty(), "the withdrawal waits for the registration on the wire")
        slow.complete(Unit)
        runCurrent()
        assertTrue(rig.registered.first().isNotEmpty())
        assertEquals(emptyList(), rig.registered.last())
    }

    @Test fun anAppStartedWithOffWithdrawsOnceAndAFailedWithdrawalTriesAgain() = runTest {
        val rig = Rig(this)
        rig.status = 0
        rig.host.bind(account, "off")
        runCurrent()
        assertTrue(rig.registered.isEmpty(), "nothing is sent while the activity is stopped")
        rig.host.setForeground(true)
        runCurrent()
        assertEquals(listOf(emptyList()), rig.registered)
        rig.status = 204
        rig.host.setForeground(false)
        rig.host.setForeground(true)
        runCurrent()
        assertEquals(2, rig.registered.size)
        rig.host.setForeground(false)
        rig.host.setForeground(true)
        runCurrent()
        assertEquals(2, rig.registered.size)
        assertTrue(rig.sockets.isEmpty())
    }

    @Test fun aSocketDropWithACommandInFlightActsNothingAfterTheReconnect() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        val old = rig.sockets.last()
        // Read before the drop: it may act now, while this connection is still the live one.
        old.events.message(state("before", "Pause"))
        old.events.closed()
        runCurrent()
        assertEquals(listOf("pause"), rig.player.calls)
        advanceTimeBy(1_001)
        runCurrent()
        val fresh = rig.sockets.last()
        assertTrue(fresh !== old)
        // A callback delivered late, racing the new connection before and after it opens.
        old.events.message(state("late-1", "Stop"))
        runCurrent()
        fresh.events.opened()
        old.events.message(state("late-2", "Stop"))
        old.events.message(general("late-3", "MoveUp"))
        runCurrent()
        rig.watching()
        old.events.message(state("late-4", "Stop"))
        runCurrent()
        assertEquals(listOf("pause"), rig.player.calls)
        assertTrue(rig.navigation.isEmpty())
    }

    @Test fun aCommandInFlightWhenTheActivityStopsOrTheViewerChangesDoesNothing() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching()
        rig.sockets.last().events.message(state("stopping", "Pause"))
        rig.host.setForeground(false)
        runCurrent()
        assertTrue(rig.player.calls.isEmpty())
        rig.host.setForeground(true)
        runCurrent()
        rig.sockets.last().events.opened()
        runCurrent()
        rig.watching()
        rig.sockets.last().events.message(state("switching", "Pause"))
        rig.host.bind(account.copy(user = User(other, "Bo")), "same-viewer")
        runCurrent()
        assertTrue(rig.player.calls.isEmpty())
    }

    @Test fun textInFlightWhenTheScreenLeavesSearchNeverReachesTheNextScreen() = runTest {
        val rig = Rig(this)
        rig.online(this)
        val typed = mutableListOf<String>()
        rig.host.setSearchField(typed::add)
        // The screen moves on, to a credential field say, with the phone's text already on its way.
        rig.sockets.last().events.message(general("gone", "SendString", """{"String":"secret"}"""))
        rig.host.setSearchField(null)
        runCurrent()
        assertTrue(typed.isEmpty())
        assertTrue(rig.trace.any { it.startsWith("remote refused") }, rig.trace.toString())
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

    @Test fun whatTheViewerAddsPlaysAheadOfTheSeriesTheServerExpandedOnto() = runTest {
        val rig = Rig(this)
        rig.online(this)
        val fourth = "f".repeat(32)
        // Jellyfin relays one episode as that episode and the rest of its series, for every play command.
        rig.message(this, play("open", listOf(film, second, third, fourth)))
        rig.watching(film)
        assertEquals(second, rig.host.upcoming)
        rig.message(this, play("add", listOf(third, fourth), "PlayLast"))
        assertEquals(third, rig.host.upcoming)
        rig.message(this, state("n", "NextTrack"))
        assertEquals("next:$third", rig.player.calls.last())
        rig.host.report(RemoteViewing(third, true, 10_000_000, 0, false, true))
        assertEquals(second, rig.host.upcoming)
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        assertEquals(fourth, rig.host.upcoming, "the added episode played once, at the added place")
        rig.host.report(RemoteViewing(fourth, true, 10_000_000, 0, false, true))
        assertNull(rig.host.upcoming)
    }

    @Test fun anAdditionWhileTheNextTitleOpensJoinsItsQueueNotTheTitleBeingReplaced() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching(film)
        val fourth = "f".repeat(32)
        rig.message(this, play("open", listOf(second, third)))
        rig.message(this, play("add", listOf(fourth), "PlayLast"))
        assertEquals(fourth, rig.host.upcoming)
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        assertEquals(fourth, rig.host.upcoming)
        rig.host.report(RemoteViewing(fourth, true, 10_000_000, 0, false, true))
        assertEquals(third, rig.host.upcoming)
    }

    @Test fun addingTheTitleOnScreenDoesNotReplayItForever() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.message(this, play("open", listOf(film)))
        rig.watching(film)
        rig.message(this, play("add", listOf(film), "PlayLast"))
        assertNull(rig.host.upcoming, "a copy next to itself could never advance: a target only sees media IDs")
    }

    @Test fun aPlayerWithItsOwnQueueTakesAdditionsAndPreviousInstead() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching(film)
        rig.player.ownQueue = true
        rig.message(this, play("a", listOf(second), "PlayNext"))
        rig.message(this, play("b", listOf(third), "PlayLast"))
        rig.message(this, state("c", "PreviousTrack"))
        assertEquals(listOf("enqueue:[$second]:true", "enqueue:[$third]:false", "previous"), rig.player.calls.takeLast(3))
        assertEquals(null, rig.host.upcoming, "the target's own queue stays out of it")
    }

    @Test fun anAdditionRightAfterPreviousKeepsTheQueue() = runTest {
        val rig = Rig(this)
        rig.online(this)
        rig.watching(film)
        rig.message(this, play("a", listOf(second), "PlayNext"))
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        rig.message(this, state("b", "PreviousTrack"))
        assertEquals("next:$film", rig.player.calls.last())
        rig.message(this, play("c", listOf(third), "PlayLast"))
        assertEquals(second, rig.host.upcoming, "the player is still on the later title; the queue must not adopt it")
        rig.host.report(RemoteViewing(film, true, 10_000_000, 0, false, true))
        assertEquals(second, rig.host.upcoming)
        rig.host.report(RemoteViewing(second, true, 10_000_000, 0, false, true))
        assertEquals(third, rig.host.upcoming)
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

    @Test fun theEndOfATitleWaitsOnlyForAQueuedTitleThatCanOpen() {
        val queued = app.pictureshow.core.MediaItem("q", "Queued", "Movie")
        assertEquals(false, remoteQueueHoldsEnd(null, null, false))
        assertEquals(true, remoteQueueHoldsEnd("q", null, true))
        assertEquals(true, remoteQueueHoldsEnd("q", queued, false))
        assertEquals(false, remoteQueueHoldsEnd("q", null, false))
    }

    @Test fun backoffIsBounded() {
        assertEquals(1_000, RemoteTargetHost.backoffMs(0))
        assertEquals(30_000, RemoteTargetHost.backoffMs(40))
    }
}
