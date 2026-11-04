/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.RemoteNowPlaying
import app.pictureshow.core.control.RemoteRequest
import app.pictureshow.core.control.RemoteTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControllerHostTest {
    private class Gateway : RemoteControllerGateway {
        var lists = 0
        var targets: ClientResult<List<RemoteTarget>> = ClientResult.Success(emptyList())
        val sent = mutableListOf<RemoteRequest>()
        var answer = 204
        var hold: CompletableDeferred<Unit>? = null

        override suspend fun targets(account: Account): ClientResult<List<RemoteTarget>> {
            lists += 1
            val reply = targets
            hold?.await()
            return reply
        }

        override suspend fun send(account: Account, request: RemoteRequest): Int {
            sent += request
            return answer
        }
    }

    private val tv = RemoteTarget(
        session = "s1",
        device = "tv",
        name = "Living room",
        viewer = "me",
        sameViewer = true,
        commands = listOf("MoveUp", "SendString"),
        now = RemoteNowPlaying(item = "i1", canSeek = true, durationTicks = 36_000_000_000, positionTicks = 600_000_000)
    )

    private fun TestScope.host(gateway: Gateway) = RemoteControllerHost(backgroundScope, gateway) { currentTime }.also {
        it.bind(Account(Server("server", "Server", "http://server.example/jf", "12.4"), User("me", "Ana"), "token"))
        it.setForeground(true)
    }

    @Test
    fun pollsOnlyWhileAttachedOrChoosingAndOnlyInForeground() = runTest {
        val gateway = Gateway()
        val host = host(gateway)
        advanceTimeBy(10_000)
        assertEquals(0, gateway.lists)
        host.setPicker(true)
        runCurrent()
        assertEquals(1, gateway.lists)
        host.setPicker(false)
        advanceTimeBy(10_000)
        assertEquals(1, gateway.lists)
        gateway.targets = ClientResult.Success(listOf(tv))
        host.attach(tv)
        runCurrent()
        val attachedLists = gateway.lists
        host.setForeground(false)
        advanceTimeBy(60_000)
        assertEquals(attachedLists, gateway.lists)
    }

    @Test
    fun returningFromTheLockScreenHoldsControlsUntilAFreshList() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.setForeground(false)
        runCurrent()
        val hold = CompletableDeferred<Unit>()
        gateway.hold = hold
        host.setForeground(true)
        runCurrent()
        assertFalse(host.state.value.controller!!.command!!.fresh)
        host.pause()
        runCurrent()
        assertTrue("A press before the fresh list must not reach the TV", gateway.sent.isEmpty())
        gateway.hold = null
        hold.complete(Unit)
        runCurrent()
        assertTrue(host.state.value.controller!!.command!!.fresh)
    }

    @Test
    fun aListThatStartedBeforeDetachCannotReattach() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        val hold = CompletableDeferred<Unit>()
        gateway.hold = hold
        runCurrent()
        host.detach()
        hold.complete(Unit)
        runCurrent()
        assertEquals("detached", host.state.value.controller!!.phase)
        assertNull(host.state.value.controller!!.target)
    }

    @Test
    fun twoQuickSkipsAddUpFromTheFirstTarget() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.seekBy(300_000_000)
        host.seekBy(300_000_000)
        runCurrent()
        assertEquals(
            listOf("900000000", "1200000000"),
            gateway.sent.map { it.query["seekPositionTicks"] ?: it.query["SeekPositionTicks"] }
        )
    }

    @Test
    fun aFailedSendIsNeverRetried() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        gateway.answer = 0
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.pause()
        advanceTimeBy(30_000)
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun navigationDeniedStopsTheRemote() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        gateway.answer = 403
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.navigate("MoveUp")
        runCurrent()
        assertEquals("denied", host.state.value.controller!!.phase)
        host.navigate("MoveUp")
        runCurrent()
        assertEquals(1, gateway.sent.size)
    }

    @Test
    fun signingOutForgetsTheTarget() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.bind(null)
        assertNull(host.state.value.controller)
    }

    @Test
    fun aNeverConfirmedCommandSaysSoThenLeavesTheScreen() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.pause()
        runCurrent()
        assertEquals("Sending to the TV…", remoteCommandStatus(host.state.value.controller, currentTime))
        advanceTimeBy(6_000)
        assertEquals(
            "The TV didn't confirm that. Showing what it reports now.",
            remoteCommandStatus(host.state.value.controller, currentTime)
        )
        advanceTimeBy(10_000)
        assertNull(remoteCommandStatus(host.state.value.controller, currentTime))
    }

    @Test
    fun aConfirmedCommandShowsNothingAfterTheLockScreen() = runTest {
        val gateway = Gateway()
        gateway.targets = ClientResult.Success(listOf(tv))
        val host = host(gateway)
        host.attach(tv)
        runCurrent()
        host.pause()
        gateway.targets = ClientResult.Success(listOf(tv.copy(now = tv.now!!.copy(paused = true))))
        advanceTimeBy(2_500)
        assertEquals("confirmed", host.state.value.controller!!.command!!.pending!!.result)
        host.setForeground(false)
        host.setForeground(true)
        runCurrent()
        assertNull(remoteCommandStatus(host.state.value.controller, currentTime))
    }

    @Test
    fun problemCopyNamesNoMediaOrUser() {
        listOf("target-gone", "target-restarted", "denied", "signed-out").forEach {
            assertTrue(remoteProblem(it)!!.isNotBlank())
        }
        assertNull(remoteProblem("attached"))
    }
}

/** A controller that is never attached lists and sends nothing, so it changes nothing a shell test observes. */
internal fun inertRemoteController() = RemoteControllerHost(
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
    object : RemoteControllerGateway {
        override suspend fun targets(account: Account): ClientResult<List<RemoteTarget>> = ClientResult.Success(emptyList())
        override suspend fun send(account: Account, request: RemoteRequest) = 0
    }
) { 0L }
