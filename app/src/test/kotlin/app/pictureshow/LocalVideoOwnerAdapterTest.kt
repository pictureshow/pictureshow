/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.ClientResult
import app.pictureshow.core.control.CommandEvent
import app.pictureshow.core.control.OwnerEvent
import app.pictureshow.core.control.ViewingIdentity
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import app.pictureshow.core.playback.PlaybackProgressInfo
import app.pictureshow.core.playback.PlaybackReports
import app.pictureshow.core.playback.PlaybackRequest
import app.pictureshow.core.playback.PlaybackStopInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LocalVideoOwnerAdapterTest {
    private val identity = ViewingIdentity("server", "viewer", "install", "viewing", 1)
    private val decision =
        PlayDecision("film", MediaSourceInfo(id = "source"), PlayMethod.DirectPlay, "https://example.invalid/movie", emptyMap(), "encode")
    private val events = mutableListOf<String>()
    private val gateway = object : PlaybackGateway {
        override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
            events += "prepare:${request.startPosition.inWholeMilliseconds}"
            return ClientResult.Success(PlayOutcome.Play(decision))
        }
        override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "start:${info.positionTicks}"
            return ClientResult.Success(Unit)
        }
        override suspend fun progress(info: PlaybackProgressInfo): ClientResult<Unit> {
            events += "progress:${info.positionTicks}"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopped(info: PlaybackStopInfo): ClientResult<Unit> {
            events += "stop:${info.positionTicks}"
            return ClientResult.Success(Unit)
        }
        override suspend fun stopEncoding(sessionId: String): ClientResult<Unit> {
            events += "cleanup:$sessionId"
            return ClientResult.Success(Unit)
        }
    }
    private val output = object : PlaybackOutput {
        override fun position() = PlaybackPosition(7, true)
        override fun open(decision: PlayDecision, positionMs: Long, audioIndex: Int, subtitleIndex: Int, paused: Boolean) = Unit
        override fun select(type: MediaStreamType, index: Int) = false
        override fun pause() = Unit
        override fun release() = Unit
    }

    @Test fun acceptedGatewayAndOutputKeepTheirOrderAndScopedCleanupEvenAfterRetirement() = runTest {
        val adapter = LocalVideoOwnerAdapter(identity, gateway, output, "film")
        assertSame(output, adapter.playbackOutput)
        val report = PlaybackReports(decision).progress(7.milliseconds, true, 2, -1)
        adapter.playbackGateway.prepare(PlaybackRequest(startPosition = 7.milliseconds))
        adapter.playbackGateway.start(report) {}
        adapter.playbackGateway.progress(report) {}
        adapter.cleanupDone()
        adapter.playbackGateway.stopped(PlaybackReports(decision).stopped(7.milliseconds))
        adapter.playbackGateway.stopEncoding("encode")
        assertEquals(listOf("prepare:7", "start:70000", "progress:70000", "stop:70000", "cleanup:encode"), events)
        assertEquals(identity, adapter.ownership.owner)
    }

    @Test fun ownerFreshnessAndDuplicateReportsCannotCrossViewings() {
        val adapter = LocalVideoOwnerAdapter(identity, gateway, output, "film")
        assertTrue(adapter.reportAllowed(identity, 2))
        assertFalse(adapter.reportAllowed(identity, 2))
        assertFalse(adapter.reportAllowed(identity.copy(viewer = "other"), 3))
        assertFalse(adapter.reportAllowed(identity.copy(session = "replaced"), 4))
        assertTrue(adapter.reportAllowed(identity, 5))
        val stale = adapter.submit(CommandEvent("intent", identity.copy(generation = 0), id = 1, value = "pause"))
        assertEquals("stale-owner", stale.rejection)
        assertEquals("none", stale.effect)
        val pause = adapter.submit(CommandEvent("intent", identity, id = 1, value = "pause"))
        assertEquals(true, pause.pending?.paused)
        assertEquals("dispatch", pause.effect)
        adapter.cleanupDone()
        assertFalse(adapter.reportAllowed(identity, 6))
        assertEquals("retired-owner", adapter.submit(CommandEvent("intent", identity, id = 2, value = "play")).rejection)
        assertEquals(1L, adapter.commands.sequence)
    }

    @Test fun retiredReportsAreSuppressedButInFlightAndScopedCleanupRemainBoundToTheirViewing() = runTest {
        val sent = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val delayedGateway = object : PlaybackGateway by gateway {
            override suspend fun start(info: PlaybackProgressInfo): ClientResult<Unit> {
                events += "in-flight"
                sent.complete(Unit)
                response.await()
                return ClientResult.Success(Unit)
            }
        }
        val old = LocalVideoOwnerAdapter(identity, delayedGateway, output, "film")
        val report = PlaybackReports(decision).progress(7.milliseconds, true, 2, -1)
        val request = async { old.playbackGateway.start(report) }
        sent.await()
        val next = LocalVideoOwnerAdapter(identity.copy(session = "next", generation = 2), gateway, output, "film")
        old.cleanupDone()
        old.playbackGateway.progress(report) {}
        old.playbackGateway.start(report)
        next.playbackGateway.start(report) {}
        val nextOwnership = next.ownership
        response.complete(Unit)
        request.await()
        assertEquals(nextOwnership, next.ownership)
        assertEquals(listOf("in-flight", "start:70000"), events)
        old.playbackGateway.stopEncoding("old-encode")
        assertEquals("cleanup:old-encode", events.last())
    }

    @Test fun handoffStopsFutureLocalReportsBeforeGatewayDispatch() = runTest {
        val adapter = LocalVideoOwnerAdapter(identity, gateway, output, "film")
        val remote = identity.copy(device = "tv", session = "remote", generation = 2, kind = "jellyfin")
        listOf("select", "connect", "prepare", "local-paused").forEach { adapter.ownerEvent(OwnerEvent(it, remote)) }
        adapter.ownerEvent(OwnerEvent("ready", remote, ticks = 0))
        val report = PlaybackReports(decision).progress(7.milliseconds, true, 2, -1)
        adapter.playbackGateway.start(report) {}
        adapter.playbackGateway.progress(report)
        assertTrue(events.isEmpty())
        assertEquals(remote, adapter.ownership.owner)
        assertEquals("none", adapter.submit(CommandEvent("intent", identity, id = 1, value = "play")).effect)
    }

    @Test fun aReplacedOrSwitchedLocalViewingKeepsReportingAsTheCurrentOwner() = runTest {
        val suppressed = mutableListOf<String>()
        val adapter = LocalVideoOwnerAdapter(identity, gateway, output, "film", suppressed::add)
        val report = PlaybackReports(decision).progress(7.milliseconds, true, 2, -1)
        adapter.playbackGateway.start(report) {}
        val resumed = identity.copy(session = "resumed", generation = 2)
        adapter.ownerEvent(OwnerEvent("replace", resumed))
        adapter.playbackGateway.progress(report) {}
        val switched = identity.copy(viewer = "next", session = "switched", generation = 3)
        adapter.ownerEvent(OwnerEvent("viewer-switch", switched))
        adapter.playbackGateway.start(report)
        adapter.playbackGateway.progress(report)
        assertEquals(listOf("start:70000", "progress:70000", "start:70000", "progress:70000"), events)
        assertEquals(switched, adapter.ownership.owner)
        assertTrue(suppressed.isEmpty())
        assertEquals("dispatch", adapter.submit(CommandEvent("intent", switched, id = 1, value = "pause")).effect)
        assertEquals("stale-owner", adapter.submit(CommandEvent("intent", identity, id = 2, value = "play")).rejection)
    }

    @Test fun suppressedReportsLeaveAReasonAndCommandsNameTheRightOne() = runTest {
        val suppressed = mutableListOf<String>()
        val adapter = LocalVideoOwnerAdapter(identity, gateway, output, "film", suppressed::add)
        val remote = identity.copy(device = "tv", session = "remote", generation = 2, kind = "jellyfin")
        listOf("select", "connect", "prepare", "local-paused").forEach { adapter.ownerEvent(OwnerEvent(it, remote)) }
        adapter.ownerEvent(OwnerEvent("ready", remote, ticks = 0))
        val report = PlaybackReports(decision).progress(7.milliseconds, true, 2, -1)
        adapter.playbackGateway.progress(report)
        assertEquals("handed-off", adapter.submit(CommandEvent("intent", identity, id = 1, value = "play")).rejection)
        adapter.cleanupDone()
        adapter.playbackGateway.progress(report)
        assertEquals(listOf("handed-off", "retired"), suppressed)
        assertEquals("retired-owner", adapter.submit(CommandEvent("intent", identity, id = 2, value = "play")).rejection)
    }

    @Test fun realPlaybackSessionKeepsItsExactReportOrderThroughTheAdapter() = runTest {
        val first = PlayDecision("film", MediaSourceInfo(id = "source"), PlayMethod.Transcode, "https://example.invalid/one", emptyMap(), "encode-one")
        val second = PlayDecision("film", MediaSourceInfo(id = "source"), PlayMethod.Transcode, "https://example.invalid/two", emptyMap(), "encode-two")
        val outcomes = ArrayDeque(listOf(first, second))
        val positions = object : PlaybackOutput by output {
            override fun position() = PlaybackPosition(7, false)
            override fun select(type: MediaStreamType, index: Int) = false
        }
        val trace = object : PlaybackGateway by gateway {
            override suspend fun prepare(request: PlaybackRequest): ClientResult<PlayOutcome> {
                events += "prepare"
                return ClientResult.Success(PlayOutcome.Play(outcomes.removeFirst()))
            }
        }
        val adapter = LocalVideoOwnerAdapter(identity, trace, positions, "film")
        val session = PlaybackSession(adapter.playbackGateway, adapter.playbackOutput, backgroundScope, "en")
        session.start(0)
        runCurrent()
        session.ready()
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        session.select(MediaStreamType.Audio, 1)
        runCurrent()
        session.ready()
        runCurrent()
        session.close()
        runCurrent()
        adapter.cleanupDone()
        assertEquals(
            listOf(
                "prepare",
                "start:70000",
                "progress:70000", // ticker
                "cleanup:encode-one", // the transcode replacement stops only the old encode
                "prepare",
                "progress:70000", // the replacement reports without a second Start
                "progress:70000", // close's final position
                "stop:70000",
                "cleanup:encode-two"
            ),
            events
        )
    }
}
