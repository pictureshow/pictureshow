/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.offline.OfflineEntry
import app.pictureshow.core.offline.OfflineLabels
import app.pictureshow.core.offline.OfflineMediaKind
import app.pictureshow.core.offline.OfflinePreset
import app.pictureshow.core.offline.OfflineReconnectFacts
import app.pictureshow.core.offline.OfflineTrack
import app.pictureshow.core.playback.MediaStreamType
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineReconnectTest {
    private val entry = OfflineEntry(
        "i:original",
        "i",
        OfflineMediaKind.movie,
        preset = OfflinePreset.original,
        labels = OfflineLabels("Film"),
        tracks = listOf(
            OfflineTrack(1, "audio", "English"),
            OfflineTrack(6, "audio", "Japanese", available = false),
            OfflineTrack(4, "subtitle", "Spanish", available = false)
        )
    )
    private val log = mutableListOf<String>()
    private val allowed = OfflineReconnectFacts("ok", true, true)

    private inner class Player(override var paused: Boolean = false) : OfflineReconnectPlayer {
        override val positionMs = 123_000L
        override val audioIndex = 1
        override val subtitleIndex = -1
        override fun pause() {
            log += "pause"
            paused = true
        }
        override fun resume() {
            log += "resume"
            paused = false
        }
        override fun close(next: () -> Unit) {
            log += "close"
            next()
        }
    }

    private inner class Handoff : OfflineReconnectHandoff {
        var result: OfflineSettleResult = OfflineSettleResult.Settled
        var thrown: Exception? = null
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun settle(positionTicks: Long): OfflineSettleResult {
            log += "settle:$positionTicks"
            hold?.await()
            thrown?.let { throw it }
            return result
        }
        override fun reopen() {
            log += "reopen"
        }
    }

    private var network = "unmetered"
    private var answer: suspend () -> OfflineReconnectFacts = { allowed }
    private var asked = 0
    private val handoff = Handoff()
    private val streams = mutableListOf<OfflineStreamStart>()

    private fun TestScope.reconnect() = OfflineReconnect(entry, backgroundScope, { network }, {
        asked++
        answer()
    }, handoff) {
        log += "stream"
        streams += it
    }

    private fun TestScope.offered(): OfflineReconnect = reconnect().also {
        it.pick(MediaStreamType.Audio, 6)
        runCurrent()
        assertIs<OfflineReconnectState.Offer>(it.state)
    }

    @Test
    fun `no connection is said at once, without asking anyone, and playback is untouched`() = runTest {
        network = "none"
        val reconnect = reconnect()
        reconnect.pick(MediaStreamType.Audio, 6)
        runCurrent()
        assertEquals(OfflineReconnectState.Refused(entry.tracks[1], "no-connection"), reconnect.state)
        assertEquals(0, asked)
        assertTrue(log.isEmpty())
        assertTrue("no connection" in offlineReconnectMessage(reconnect.state))
    }

    @Test
    fun `an answer that settles nothing is a server not answering, never an offer`() = runTest {
        listOf(OfflineReconnectFacts(), OfflineReconnectFacts("ok"), OfflineReconnectFacts("ok", true, null)).forEach { facts ->
            answer = { facts }
            val reconnect = reconnect()
            reconnect.pick(MediaStreamType.Subtitle, 4)
            runCurrent()
            assertEquals("server-unreachable", assertIs<OfflineReconnectState.Refused>(reconnect.state).outcome)
        }
    }

    @Test
    fun `each server answer becomes its own plain refusal and leaves playback alone`() = runTest {
        val cases = listOf(
            OfflineReconnectFacts("unreachable") to ("server-unreachable" to "isn't answering"),
            OfflineReconnectFacts("signed-out") to ("signed-out" to "Sign in"),
            OfflineReconnectFacts("ok", playbackAllowed = false) to ("not-allowed" to "isn't allowed"),
            OfflineReconnectFacts("ok", true, false) to ("track-gone" to "no longer on the server")
        )
        cases.forEach { (facts, expected) ->
            answer = { facts }
            val reconnect = reconnect()
            reconnect.pick(MediaStreamType.Subtitle, 4)
            assertIs<OfflineReconnectState.Checking>(reconnect.state)
            runCurrent()
            val refused = assertIs<OfflineReconnectState.Refused>(reconnect.state)
            assertEquals(expected.first, refused.outcome)
            assertTrue(expected.second in offlineReconnectMessage(refused), offlineReconnectMessage(refused))
        }
        assertTrue(log.isEmpty())
    }

    @Test
    fun `a probe that blows up is a server not answering`() = runTest {
        answer = { throw IOException("gone") }
        val reconnect = reconnect()
        reconnect.pick(MediaStreamType.Audio, 6)
        runCurrent()
        assertEquals("server-unreachable", assertIs<OfflineReconnectState.Refused>(reconnect.state).outcome)
    }

    @Test
    fun `a track this download has can't start an offer`() = runTest {
        val reconnect = reconnect()
        reconnect.pick(MediaStreamType.Audio, 1)
        reconnect.pick(MediaStreamType.Subtitle, 6)
        runCurrent()
        assertEquals(OfflineReconnectState.Idle, reconnect.state)
        assertEquals(0, asked)
    }

    @Test
    fun `a metered network is offered too and told what it costs`() = runTest {
        val plain = offered()
        assertFalse("mobile data" in offlineReconnectMessage(plain.state))
        network = "metered"
        val metered = offered()
        assertEquals(true, assertIs<OfflineReconnectState.Offer>(metered.state).metered)
        assertTrue("mobile data" in offlineReconnectMessage(metered.state))
        assertTrue("for downloads" in offlineReconnectMessage(metered.state))
        assertTrue(log.isEmpty())
    }

    @Test
    fun `no while checking means no late offer ever appears`() = runTest {
        val answerGate = CompletableDeferred<Unit>()
        answer = {
            answerGate.await()
            allowed
        }
        val reconnect = reconnect()
        reconnect.pick(MediaStreamType.Audio, 6)
        runCurrent()
        reconnect.stay()
        answerGate.complete(Unit)
        runCurrent()
        assertEquals(OfflineReconnectState.Idle, reconnect.state)
        assertTrue(log.isEmpty())
    }

    @Test
    fun `declining an offer changes nothing and the next pick starts again`() = runTest {
        val reconnect = offered()
        reconnect.stay()
        assertEquals(OfflineReconnectState.Idle, reconnect.state)
        assertTrue(log.isEmpty())
        assertEquals(1, asked)
        reconnect.pick(MediaStreamType.Audio, 6)
        runCurrent()
        assertIs<OfflineReconnectState.Offer>(reconnect.state)
    }

    @Test
    fun `accepting holds playback, settles the exact position, closes, and only then streams`() = runTest {
        val reconnect = offered()
        reconnect.accept(Player())
        assertIs<OfflineReconnectState.Connecting>(reconnect.state)
        runCurrent()
        assertEquals(listOf("pause", "settle:1230000000", "close", "stream"), log)
        assertIs<OfflineReconnectState.Leaving>(reconnect.state)
        // The picked audio, and the subtitle state the viewer already had, explicit Off included.
        assertEquals(listOf(OfflineStreamStart(123_000, 6, -1)), streams)
    }

    /** The store's lock can be held by a background sync, and each call inside has its own 6 s: one limit for all of it. */
    @Test
    fun `saving the place has one overall limit, then local viewing carries on`() = runTest {
        handoff.hold = CompletableDeferred()
        val reconnect = offered()
        val player = Player()
        reconnect.accept(player)
        advanceTimeBy(14_000)
        assertTrue(reconnect.state is OfflineReconnectState.Connecting)
        advanceTimeBy(2_000)
        assertEquals("server-unreachable", (reconnect.state as OfflineReconnectState.Refused).outcome)
        assertTrue("reopen" in log)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun `a server-kept conflict still moves on`() = runTest {
        handoff.result = OfflineSettleResult.Conflict
        val reconnect = offered()
        reconnect.accept(Player())
        runCurrent()
        assertEquals(1, streams.size)
    }

    @Test
    fun `a failed settle reopens local viewing, resumes it and says so`() = runTest {
        for (fail in listOf<() -> Unit>({ handoff.result = OfflineSettleResult.Failed }, { handoff.thrown = IOException("gone") })) {
            log.clear()
            fail()
            val reconnect = offered()
            val player = Player()
            reconnect.accept(player)
            runCurrent()
            assertEquals(listOf("pause", "settle:1230000000", "reopen", "resume"), log)
            assertFalse(player.paused)
            assertEquals("server-unreachable", assertIs<OfflineReconnectState.Refused>(reconnect.state).outcome)
            assertTrue(streams.isEmpty())
        }
    }

    @Test
    fun `a viewer who had paused stays paused when the handoff fails`() = runTest {
        handoff.result = OfflineSettleResult.Failed
        val reconnect = offered()
        val player = Player(paused = true)
        reconnect.accept(player)
        runCurrent()
        assertFalse("resume" in log)
        assertTrue("reopen" in log)
        assertTrue(player.paused)
    }

    @Test
    fun `changing your mind while connecting goes back to the download and never moves`() = runTest {
        val gate = CompletableDeferred<Unit>()
        handoff.hold = gate
        val reconnect = offered()
        val player = Player()
        reconnect.accept(player)
        runCurrent()
        reconnect.stay()
        assertEquals(OfflineReconnectState.Idle, reconnect.state)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("pause", "settle:1230000000", "reopen", "resume"), log)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun `the app going away while connecting reopens without restarting playback`() = runTest {
        handoff.hold = CompletableDeferred()
        val reconnect = offered()
        reconnect.accept(Player())
        runCurrent()
        reconnect.stay(resume = false)
        assertEquals(listOf("pause", "settle:1230000000", "reopen"), log)
    }

    @Test
    fun `nothing undoes a move already made`() = runTest {
        val reconnect = offered()
        reconnect.accept(Player())
        runCurrent()
        reconnect.stay()
        assertIs<OfflineReconnectState.Leaving>(reconnect.state)
        assertFalse("resume" in log)
        assertFalse("reopen" in log)
    }

    @Test
    fun `accept only means something on an offer`() = runTest {
        network = "none"
        val reconnect = reconnect()
        reconnect.pick(MediaStreamType.Audio, 6)
        reconnect.accept(Player())
        runCurrent()
        assertTrue(log.isEmpty())
    }
}
