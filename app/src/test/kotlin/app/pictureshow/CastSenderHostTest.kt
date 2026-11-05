/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.User
import app.pictureshow.core.control.CastSenderVpn
import app.pictureshow.core.playback.MediaSourceInfo
import app.pictureshow.core.playback.MediaStream
import app.pictureshow.core.playback.MediaStreamType
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

private val ACCOUNT = Account(Server("server", "Server", "http://server.example/jf", "12.4"), User("me", "Ana"), "secret-token")
private val ROUTE = CastRoute("provider:route-abcd", "Living room")
private const val ITEM = "item-1"
private const val ENTRY = "0:item-1"

private class FakeLink(private val log: MutableList<String>) : CastLink {
    override val routes = MutableStateFlow(listOf(ROUTE))
    var sink: CastLinkListener? = null
    var request: CastLoadRequest? = null
    var loadResult: Boolean? = true
    var discovering = false
    override fun setListener(listener: CastLinkListener?) {
        sink = listener
    }

    override fun startDiscovery() {
        discovering = true
        log += "discover"
    }

    override fun stopDiscovery() {
        discovering = false
        log += "stop-discover"
    }

    override fun select(routeId: String) {
        log += "select"
    }

    override fun endSession(stopReceiver: Boolean) {
        log += "end:$stopReceiver"
    }

    override fun load(request: CastLoadRequest, onResult: (Boolean) -> Unit) {
        this.request = request
        log += "load"
        loadResult?.let(onResult)
    }

    override fun play(onResult: (Boolean) -> Unit) {
        log += "play"
        onResult(true)
    }

    override fun pause(onResult: (Boolean) -> Unit) {
        log += "pause"
        onResult(true)
    }

    override fun seek(positionMs: Long, onResult: (Boolean) -> Unit) {
        log += "seek:$positionMs"
        onResult(true)
    }

    override fun stop(onResult: (Boolean) -> Unit) {
        log += "stop"
        onResult(true)
    }

    override fun setActiveTracks(ids: List<Long>, onResult: (Boolean) -> Unit) {
        log += "tracks:$ids"
        onResult(true)
    }

    override fun requestStatus() {
        log += "status"
    }
}

private class FakeEnvironment(private val log: MutableList<String>) : CastEnvironment {
    val built = FakeLink(log)
    var linkBuilds = 0
    var servicesState = "ok"
    var vpn = CastSenderVpn()
    override var tvInterface = false
    override val osSupported = true
    override fun services() = servicesState
    override fun localNetworkAllowed() = true
    override fun senderVpn() = vpn
    override fun link(): CastLink {
        linkBuilds += 1
        return built
    }

    override var enabled = true
    override var mayBeLive = false
}

private class FakeGateway(private val log: MutableList<String>) : CastMediaGateway {
    var outcome: ClientResult<PlayOutcome>? = null
    val reports = mutableListOf<String>()
    override suspend fun prepare(account: Account, itemId: String, request: CastPlayRequest, startMs: Long): ClientResult<PlayOutcome> {
        log += "prepare:$startMs"
        return outcome ?: ClientResult.Success(PlayOutcome.Play(decision(itemId)))
    }

    override suspend fun reportStart(account: Account, report: CastReport) {
        reports += "start:${report.positionTicks}"
    }

    override suspend fun reportProgress(account: Account, report: CastReport) {
        reports += "progress:${report.positionTicks}:${report.paused}"
    }

    override suspend fun reportStopped(account: Account, report: CastReport) {
        reports += "stopped:${report.positionTicks}"
    }

    override suspend fun stopEncoding(account: Account, playSessionId: String) {
        reports += "encoding:$playSessionId"
    }
}

private fun decision(itemId: String) = PlayDecision(
    itemId,
    MediaSourceInfo(
        id = "source",
        container = "mp4",
        mediaStreams = listOf(
            MediaStream(type = MediaStreamType.Subtitle, index = 3, isTextSubtitleStream = true, displayTitle = "English")
        )
    ),
    PlayMethod.DirectPlay,
    "http://server.example/jf/Videos/$itemId/stream?static=true",
    emptyMap(),
    "play-session"
)

private class FakeOutput(private val log: MutableList<String>) : CastLocalOutput {
    val position = CompletableDeferred<Long>()
    var released = 0
    var resumed = 0
    override suspend fun pauseAndReadPositionMs(): Long {
        log += "pause-local"
        val value = position.await()
        log += "paused-local"
        return value
    }

    override fun release() {
        released += 1
        log += "release"
    }

    override fun resume() {
        resumed += 1
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CastSenderHostTest {
    private val log = mutableListOf<String>()
    private val environment = FakeEnvironment(log)
    private val gateway = FakeGateway(log)
    private val link get() = environment.built
    private val output = FakeOutput(log)

    private fun TestScope.host(account: Account? = ACCOUNT) =
        CastSenderHost(backgroundScope, environment, gateway) { currentTime }.also { it.bind(account) }

    private fun request(local: CastLocalOutput? = null, startMs: Long = 5_000, paused: Boolean = false) =
        CastPlayRequest(listOf(CastPlayItem(ITEM, "A film")), startMs = startMs, paused = paused, local = local)

    private fun status(
        state: String,
        positionMs: Long = 5_000,
        generation: Long? = null,
        reason: String? = null,
        session: Long = 7,
        extra: Map<String, String> = emptyMap()
    ) = CastStatus(
        mediaSession = session,
        playerState = state,
        idleReason = reason,
        positionMs = positionMs,
        durationMs = 600_000,
        customData = buildMap {
            generation?.let { put("generation", it.toString()) }
            put("entry", ENTRY)
            putAll(extra)
        },
        commands = listOf("play", "pause", "seek", "stop")
    )

    private fun CastSenderHost.generation() = state.value.cast?.let { it.owner.candidate ?: it.owner.owner }?.generation ?: -1

    /** Up to the point where the receiver has been asked to load. */
    private fun TestScope.handoff(host: CastSenderHost, local: CastLocalOutput? = null, startMs: Long = 5_000, paused: Boolean = false) {
        host.openChooser(request(local, startMs, paused))
        runCurrent()
        host.pick(ROUTE)
        link.sink!!.onConnecting()
        link.sink!!.onConnected("Living room")
        runCurrent()
    }

    private fun TestScope.casting(host: CastSenderHost, paused: Boolean = false) {
        handoff(host, paused = paused)
        link.sink!!.onStatus(status(if (paused) "paused" else "playing", generation = host.generation()))
        runCurrent()
    }

    @Test fun localPauseIsAcknowledgedBeforePrepareAndLoad() = runTest {
        val host = host()
        handoff(host, output)
        assertEquals(1, log.count { it == "pause-local" })
        assertFalse(log.any { it.startsWith("prepare") || it == "load" }, log.toString())
        output.position.complete(62_000)
        runCurrent()
        val order = log.filter { it == "paused-local" || it.startsWith("prepare") || it == "load" }
        assertEquals(listOf("paused-local", "prepare:62000", "load"), order)
        assertEquals(62_000, link.request!!.startMs)
    }

    @Test fun nothingLoadsUntilTheViewerPicks() = runTest {
        val host = host()
        host.openChooser(request())
        runCurrent()
        link.routes.value = listOf(ROUTE, ROUTE.copy(id = "other"))
        runCurrent()
        assertFalse(log.any { it == "select" || it == "load" || it == "pause-local" }, log.toString())
        assertNull(host.state.value.cast)
    }

    @Test fun localIsReleasedOnlyOnceTheReceiverReportsPlaying() = runTest {
        val host = host()
        handoff(host, output)
        output.position.complete(5_000)
        runCurrent()
        assertEquals(0, output.released, "accepting a LOAD isn't playing")
        link.sink!!.onStatus(status("buffering", generation = host.generation()))
        runCurrent()
        assertEquals(0, output.released)
        link.sink!!.onStatus(status("playing", positionMs = 6_000, generation = host.generation()))
        runCurrent()
        assertEquals(1, output.released)
        assertEquals("cast", host.state.value.cast!!.owner.owner.kind)
        assertEquals(listOf("start:60000000"), gateway.reports)
    }

    @Test fun aStatusFromAnOlderViewingCommitsNothing() = runTest {
        val host = host()
        handoff(host, output)
        output.position.complete(5_000)
        runCurrent()
        link.sink!!.onStatus(status("playing", generation = 1))
        runCurrent()
        assertEquals(0, output.released)
        assertEquals("awaiting-ready", host.state.value.cast!!.owner.phase)
    }

    @Test fun refusedLoadKeepsLocalPausedWithResumeHere() = runTest {
        link.loadResult = false
        val host = host()
        handoff(host, output)
        output.position.complete(5_000)
        runCurrent()
        val cast = host.state.value.cast!!
        assertEquals("failed", cast.owner.phase)
        assertEquals("unsupported-media", cast.recovery)
        assertEquals(0, output.released)
        assertEquals(0, output.resumed, "resuming is the viewer's choice")
        assertTrue("end:true" in log)
        host.resumeHere()
        assertEquals(1, output.resumed)
        assertNull(host.state.value.localStart, "the player is still open")
        assertNull(host.state.value.cast)
    }

    @Test fun serverRefusalIsAFailureNotAStall() = runTest {
        gateway.outcome =
            ClientResult.Success(PlayOutcome.Unplayable(app.pictureshow.core.playback.UnplayableReason.NO_COMPATIBLE_SOURCE, "no"))
        val host = host()
        handoff(host)
        assertEquals("unsupported-media", host.state.value.cast!!.recovery)
        assertFalse("load" in log)
    }

    @Test fun receiverThatNeverStartsTimesOutAndStopsWhatItLoaded() = runTest {
        link.loadResult = null
        val host = host()
        handoff(host)
        assertEquals("awaiting-ready", host.state.value.cast!!.owner.phase)
        advanceTimeBy(CAST_LOAD_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals("receiver-timeout", host.state.value.cast!!.recovery)
        assertTrue("stop" in log)
        assertTrue("end:true" in log)
    }

    @Test fun failedHandoffFromADetailScreenOpensAPlayer() = runTest {
        link.loadResult = false
        val host = host()
        handoff(host, startMs = 5_000)
        host.resumeHere()
        assertEquals(CastLocalStart(ITEM, 5_000, false, 1), host.state.value.localStart)
        host.localStartHandled(1)
        assertNull(host.state.value.localStart)
    }

    @Test fun closedPlayerMakesResumeOpenANewOne() = runTest {
        link.loadResult = false
        val host = host()
        handoff(host, output)
        output.position.complete(5_000)
        runCurrent()
        host.localClosed(output)
        host.resumeHere()
        assertEquals(0, output.resumed)
        assertNotNull(host.state.value.localStart)
    }

    @Test fun disconnectLeavesTheReceiverAndStopsReporting() = runTest {
        val host = host()
        casting(host)
        gateway.reports.clear()
        log.clear()
        host.disconnect()
        runCurrent()
        assertEquals(listOf("end:false"), log.filter { it == "stop" || it.startsWith("end") })
        assertEquals("disconnected", host.state.value.cast!!.owner.phase)
        assertTrue(environment.mayBeLive, "a viewing may still be running")
        advanceTimeBy(CAST_REPORT_MS * 3)
        runCurrent()
        assertEquals(emptyList(), gateway.reports)
    }

    @Test fun reattachTakesTheReceiversClockNotAnOldOne() = runTest {
        val host = host()
        casting(host)
        val generation = host.generation()
        host.disconnect()
        advanceTimeBy(60_000)
        link.sink!!.onConnected("Living room")
        assertNull(host.state.value.controls, "no controls until the receiver answers")
        link.sink!!.onStatus(status("paused", positionMs = 90_000, generation = generation))
        runCurrent()
        val cast = host.state.value.cast!!
        assertEquals("active", cast.owner.phase)
        assertEquals(900_000_000, cast.observed!!.ticks)
        assertEquals(true, cast.commands!!.fresh)
    }

    @Test fun reportsGoOutOnlyWhileAttached() = runTest {
        val host = host()
        casting(host)
        gateway.reports.clear()
        advanceTimeBy(CAST_REPORT_MS + 1)
        runCurrent()
        assertEquals(1, gateway.reports.count { it.startsWith("progress") })
        link.sink!!.onSuspended()
        advanceTimeBy(CAST_REPORT_MS * 2)
        runCurrent()
        assertEquals(1, gateway.reports.count { it.startsWith("progress") })
    }

    @Test fun stateChangeReportsAtOnce() = runTest {
        val host = host()
        casting(host)
        gateway.reports.clear()
        link.sink!!.onStatus(status("paused", positionMs = 7_000, generation = host.generation()))
        runCurrent()
        assertEquals(listOf("progress:70000000:true"), gateway.reports)
    }

    @Test fun commandsGoOnlyThroughTheReducer() = runTest {
        val host = host()
        casting(host)
        log.clear()
        host.pause()
        host.seekTo(300_000_000)
        runCurrent()
        assertEquals(listOf("pause", "seek:30000"), log.filter { it == "pause" || it.startsWith("seek") })
        link.sink!!.onSuspended()
        log.clear()
        host.play()
        assertEquals(emptyList(), log.filter { it == "play" })
    }

    @Test fun stopCastingWaitsForTheReceiverThenCleansUp() = runTest {
        val host = host()
        casting(host)
        gateway.reports.clear()
        log.clear()
        host.stop()
        runCurrent()
        assertTrue("stop" in log)
        assertFalse(log.any { it.startsWith("end") })
        assertFalse(gateway.reports.any { it.startsWith("stopped") }, "not yet")
        link.sink!!.onStatus(status("idle", generation = host.generation(), reason = "cancelled"))
        runCurrent()
        assertTrue("end:true" in log)
        assertEquals(listOf("stopped:50000000", "encoding:play-session"), gateway.reports)
        assertNull(host.state.value.cast)
        assertFalse(environment.mayBeLive)
    }

    @Test fun unconfirmedStopNeedsAChoice() = runTest {
        val host = host()
        casting(host)
        host.stop()
        advanceTimeBy(CAST_STOP_TIMEOUT_MS + 1)
        runCurrent()
        val cast = host.state.value.cast!!
        assertEquals("return-uncertain", cast.owner.phase)
        assertEquals("unknown", cast.stop)
        assertEquals(emptyList(), gateway.reports.filter { it.startsWith("stopped") }, "it may still be playing")
        host.resumeHere()
        assertNull(host.state.value.localStart, "not without the explicit choice")
        host.resumeHere(confirmed = true)
        runCurrent()
        assertEquals(ITEM, host.state.value.localStart?.itemId)
        assertTrue(gateway.reports.any { it.startsWith("stopped") })
    }

    @Test fun playHereKeepsThePauseAndThePosition() = runTest {
        val host = host()
        casting(host)
        val generation = host.generation()
        link.sink!!.onStatus(status("paused", positionMs = 120_000, generation = generation))
        runCurrent()
        host.playHere()
        runCurrent()
        assertNull(host.state.value.localStart, "waits for the receiver")
        link.sink!!.onStatus(status("idle", positionMs = 0, generation = generation, reason = "cancelled"))
        runCurrent()
        val start = host.state.value.localStart!!
        assertEquals(120_000, start.positionMs)
        assertTrue(start.paused)
        assertTrue(gateway.reports.contains("stopped:1200000000"))
    }

    @Test fun playHereWhilePlayingKeepsPlaying() = runTest {
        val host = host()
        casting(host)
        host.playHere()
        link.sink!!.onStatus(status("idle", generation = host.generation(), reason = "cancelled"))
        runCurrent()
        assertFalse(host.state.value.localStart!!.paused)
    }

    @Test fun receiverFinishingTellsTheViewerAndStartsNothing() = runTest {
        val host = host()
        casting(host)
        link.sink!!.onStatus(status("idle", positionMs = 600_000, generation = host.generation(), reason = "finished"))
        runCurrent()
        val cast = host.state.value.cast!!
        assertEquals("receiver-finished", cast.notice)
        assertNull(host.state.value.localStart)
        assertTrue(gateway.reports.any { it == "stopped:6000000000" })
        assertTrue("end:true" in log)
    }

    @Test fun someoneElsesMediaIsLeftAlone() = runTest {
        val host = host()
        casting(host)
        log.clear()
        link.sink!!.onStatus(status("playing", generation = null, session = 30).copy(customData = emptyMap()))
        runCurrent()
        assertEquals("receiver-taken", host.state.value.cast!!.notice)
        assertEquals(listOf("end:false"), log.filter { it == "stop" || it.startsWith("end") })
    }

    @Test fun droppedCustomDataStillMatchesByTheUrlWeLoaded() = runTest {
        val host = host()
        handoff(host)
        val url = link.request!!.items.single().url
        host.state.value.cast!!.let { assertEquals("awaiting-ready", it.owner.phase) }
        link.sink!!.onStatus(status("playing").copy(customData = emptyMap(), contentUrl = url))
        runCurrent()
        assertEquals("active", host.state.value.cast!!.owner.phase)
    }

    @Test fun lostLinkDuringAHandoffFailsItAndWhileCastingKeepsTheOwner() = runTest {
        val host = host()
        handoff(host, output)
        link.sink!!.onEnded()
        assertEquals("link-lost", host.state.value.cast!!.recovery)

        val second = host()
        casting(second)
        link.sink = second
        link.sink!!.onEnded()
        assertEquals("disconnected", second.state.value.cast!!.owner.phase)
        assertEquals("link-lost", second.state.value.cast!!.notice)
    }

    @Test fun afterProcessDeathOnlyOurOwnMediaReattaches() = runTest {
        environment.mayBeLive = true
        val host = host()
        host.setForeground(true)
        runCurrent()
        assertEquals(1, environment.linkBuilds)
        link.sink!!.onConnected("Living room")
        assertTrue("status" in log)
        link.sink!!.onStatus(
            status(
                "playing",
                positionMs = 30_000,
                generation = 40,
                extra = mapOf(
                    "server" to "server",
                    "viewer" to "me",
                    "device" to ROUTE.id,
                    "session" to "cast-39",
                    "item" to ITEM,
                    "method" to "Transcode",
                    "source" to "source",
                    "playSession" to "ps"
                )
            )
        )
        runCurrent()
        val cast = host.state.value.cast!!
        assertEquals("cast", cast.owner.owner.kind)
        assertEquals(40, cast.owner.owner.generation)
        assertEquals("active", cast.owner.phase)
        assertEquals(300_000_000, cast.observed!!.ticks)
        host.stop()
        link.sink!!.onStatus(status("idle", generation = 40, reason = "cancelled"))
        runCurrent()
        assertTrue(gateway.reports.contains("encoding:ps"), "cleanup still has what it needs")
    }

    @Test fun afterProcessDeathSomeoneElsesViewingIsNotTaken() = runTest {
        environment.mayBeLive = true
        val host = host()
        host.setForeground(true)
        runCurrent()
        link.sink!!.onConnected(null)
        link.sink!!.onStatus(
            status("playing", generation = 40, extra = mapOf("server" to "server", "viewer" to "someone-else"))
        )
        runCurrent()
        assertNull(host.state.value.cast)
        assertFalse(environment.mayBeLive)
        assertEquals(listOf("end:false"), log.filter { it == "stop" || it.startsWith("end") })
    }

    @Test fun nothingReachesTheLinkBeforeTheChooserOrAReasonToReattach() = runTest {
        val host = host()
        host.setForeground(true)
        host.openChooser(null)
        runCurrent()
        assertEquals(0, environment.linkBuilds)
    }

    @Test fun theTvInterfaceNeverBuildsALink() = runTest {
        environment.tvInterface = true
        environment.mayBeLive = true
        val host = host()
        host.setForeground(true)
        host.openChooser(request())
        host.pick(ROUTE)
        runCurrent()
        assertEquals(0, environment.linkBuilds)
        assertEquals("tv-interface", host.state.value.capability.state)
    }

    @Test fun discoveryRunsOnlyWhileTheChooserIsOpen() = runTest {
        val host = host()
        host.openChooser(request())
        runCurrent()
        assertTrue(link.discovering)
        assertEquals("ready", host.state.value.capability.state)
        host.closeChooser()
        assertFalse(link.discovering)
        assertEquals(emptyList(), host.state.value.routes)
    }

    @Test fun searchEndsInASayableState() = runTest {
        link.routes.value = emptyList()
        val host = host()
        host.openChooser(request())
        runCurrent()
        assertEquals("searching", host.state.value.capability.state)
        advanceTimeBy(CAST_SEARCH_MS + 1)
        runCurrent()
        assertEquals("no-targets", host.state.value.capability.state)
    }

    @Test fun missingPlayServicesIsAStateNotACrashAndSearchesNothing() = runTest {
        environment.servicesState = "missing"
        val host = host()
        host.openChooser(request())
        runCurrent()
        assertEquals("services-missing", host.state.value.capability.state)
        assertEquals(true, host.state.value.capability.jellyfinRemote)
        assertEquals(0, environment.linkBuilds)
    }

    @Test fun turnedOffMeansNoSearchAndNoReattachButLeavesTheReceiverPlaying() = runTest {
        val host = host()
        casting(host)
        log.clear()
        host.setEnabled(false)
        runCurrent()
        assertEquals("viewer-disabled", host.state.value.capability.state)
        assertEquals(listOf("end:false"), log.filter { it == "stop" || it.startsWith("end") })
        host.openChooser(request())
        runCurrent()
        assertFalse(link.discovering)
    }

    @Test fun anAddressTheReceiverCanNotReachStopsBeforeAnythingHappens() = runTest {
        val host = host(ACCOUNT.copy(server = ACCOUNT.server.copy(url = "http://127.0.0.1:8096")))
        host.openChooser(request())
        runCurrent()
        host.pick(ROUTE)
        runCurrent()
        assertEquals("loopback", host.state.value.blockedReach?.category)
        assertNull(host.state.value.cast)
        assertFalse("select" in log)
    }

    @Test fun theTokenStaysOutOfEverythingLoggableAndOutOfCustomData() = runTest {
        val host = host()
        handoff(host)
        val item = link.request!!.items.single()
        assertTrue("ApiKey=secret-token" in item.url, "the receiver can't send a header")
        assertTrue("ApiKey=secret-token" in item.tracks.single().url)
        assertFalse("secret-token" in item.customData.values.joinToString())
        assertFalse("secret-token" in item.toString())
        assertFalse("secret-token" in item.tracks.single().toString())
        assertFalse("secret-token" in CastStatus(contentUrl = item.url).toString())
        assertFalse("secret-token" in host.state.value.toString())
    }

    @Test fun queueEntriesStayDistinctWhenTheSameItemRepeats() = runTest {
        val host = host()
        host.openChooser(
            CastPlayRequest(listOf(CastPlayItem(ITEM, "A"), CastPlayItem(ITEM, "A"), CastPlayItem("item-2", "B")), startIndex = 1)
        )
        runCurrent()
        host.pick(ROUTE)
        link.sink!!.onConnected("Living room")
        runCurrent()
        val entries = link.request!!.items.map { it.customData["entry"] }
        assertEquals(listOf("0:item-1", "1:item-1", "2:item-2"), entries)
        assertEquals(1, link.request!!.startIndex)
    }

    @Test fun seamLabelsTellSameNamedDevicesApartWithoutAddresses() {
        val routes = listOf(CastRoute("id-aaaa", "TV"), CastRoute("id-bbbb", "TV"), CastRoute("id-cccc", "Den"))
        assertEquals("TV (aaaa)", castRouteLabel(routes[0], routes))
        assertEquals("Den", castRouteLabel(routes[2], routes))
    }

    @Test fun everyStateHasPlainWordsAndNoCodes() {
        listOf(
            "os-too-old",
            "browser-unsupported",
            "services-missing",
            "services-update-required",
            "viewer-disabled",
            "permission-denied"
        ).forEach {
            assertNotNull(castCapabilityMessage(it), it)
        }
        listOf("loopback", "link-local", "sender-vpn").forEach { assertTrue(castReachMessage(it).contains("TV")) }
        assertEquals("Finished playing on Den.", castNoticeMessage("receiver-finished", "Den"))
    }
}
