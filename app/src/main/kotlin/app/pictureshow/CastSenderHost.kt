/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.Server
import app.pictureshow.core.control.CastCapability
import app.pictureshow.core.control.CastCapabilityInput
import app.pictureshow.core.control.CastControls
import app.pictureshow.core.control.CastControlsInput
import app.pictureshow.core.control.CastEvent
import app.pictureshow.core.control.CastObservation
import app.pictureshow.core.control.CastReach
import app.pictureshow.core.control.CastReachInput
import app.pictureshow.core.control.CastSenderVpn
import app.pictureshow.core.control.CastState
import app.pictureshow.core.control.CastTrack
import app.pictureshow.core.control.ControlIntent
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.ViewingIdentity
import app.pictureshow.core.control.castCapability
import app.pictureshow.core.control.castContentType
import app.pictureshow.core.control.castControls
import app.pictureshow.core.control.castMediaUrl
import app.pictureshow.core.control.castReach
import app.pictureshow.core.control.castReattachState
import app.pictureshow.core.control.castSubtitles
import app.pictureshow.core.control.reduceCast
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MS_TO_TICKS = 10_000L

/** The reducer only compares this for sameness; it is never sent anywhere. */
private const val LOCAL_DEVICE = "this-device"
internal const val CAST_LOAD_TIMEOUT_MS = 30_000L
internal const val CAST_STOP_TIMEOUT_MS = 5_000L
internal const val CAST_COMMAND_TIMEOUT_MS = 5_000L
internal const val CAST_SEARCH_MS = 8_000L
internal const val CAST_REPORT_MS = 10_000L

/** What the viewer asked to send. Nothing here plays until a device is picked. */
internal class CastPlayRequest(
    val items: List<CastPlayItem>,
    val startIndex: Int = 0,
    /** Where to start when there is no local player to ask. */
    val startMs: Long = 0,
    val paused: Boolean = false,
    val mediaSourceId: String? = null,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
    val local: CastLocalOutput? = null
)

internal data class CastPlayItem(val id: String, val title: String, val imageUrl: String? = null, val durationMs: Long? = null, val live: Boolean = false)

internal data class CastLocalStart(val itemId: String, val positionMs: Long, val paused: Boolean, val serial: Long)

internal data class CastUi(
    val capability: CastCapability = CastCapability("idle", visible = true, canDiscover = true),
    val routes: List<CastRoute> = emptyList(),
    val chooserOpen: Boolean = false,
    val blockedReach: CastReach? = null,
    val cast: CastState? = null,
    val deviceName: String? = null,
    val title: String? = null,
    val controls: CastControls? = null,
    val localStart: CastLocalStart? = null
)

/** The server access a sender needs: negotiate for the receiver, and report on the receiver's behalf. */
internal interface CastMediaGateway {
    suspend fun prepare(account: Account, itemId: String, request: CastPlayRequest, startMs: Long): ClientResult<PlayOutcome>
    suspend fun reportStart(account: Account, report: CastReport)
    suspend fun reportProgress(account: Account, report: CastReport)
    suspend fun reportStopped(account: Account, report: CastReport)
    suspend fun stopEncoding(account: Account, playSessionId: String)
}

internal data class CastReport(
    val itemId: String,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val method: PlayMethod,
    val positionTicks: Long,
    val paused: Boolean
)

/** Everything device-specific. The link is built on first need, so nothing before that touches the Cast SDK. */
internal interface CastEnvironment {
    val tvInterface: Boolean
    val osSupported: Boolean
    fun services(): String
    fun localNetworkAllowed(): Boolean
    fun senderVpn(): CastSenderVpn
    fun link(): CastLink
    var enabled: Boolean

    /** Whether a receiver may still be playing for us, so that returning to the app is worth a reattach. */
    var mayBeLive: Boolean
}

/**
 * One Cast sender for the signed-in account: the Android side of [reduceCast]. It runs effects and timers; every
 * decision about who owns the viewing stays in the reducer. Reports go out on the receiver's behalf only while attached:
 * a temporary arrangement until a receiver of ours reports for itself (CAST-06), and an accepted gap while detached.
 */
internal class CastSenderHost(
    private val scope: CoroutineScope,
    private val environment: CastEnvironment,
    private val gateway: CastMediaGateway,
    private val nowMs: () -> Long
) : CastLinkListener {
    private val mutable = MutableStateFlow(CastUi())
    val state = mutable.asStateFlow()
    private var account: Account? = null
    private var link: CastLink? = null
    private var routesJob: Job? = null
    private var searchJob: Job? = null
    private var request: CastPlayRequest? = null
    private var loadTimer: Job? = null
    private var stopTimer: Job? = null
    private var commandTimer: Job? = null
    private var reportJob: Job? = null
    private var loadStarted = false
    private var generation = 1L
    private var commandId = 0L
    private var reportSequence = 0L
    private var localSerial = 0L
    private var discovery = "idle"
    private var prepared: Map<String, PlayDecision> = emptyMap()
    private var loadedUrls: Map<String, String> = emptyMap()
    private var reported: CastReport? = null
    private var localAlive = false
    private var lastStatus: CastStatus? = null
    private var awaitingReattach = false

    private val cast get() = mutable.value.cast

    fun bind(next: Account?) {
        val usable = next?.takeUnless { it.expired }
        if (usable?.key == account?.key) {
            account = usable
            return
        }
        // A receiver doesn't follow an account change, so the old viewing is left there, and left alone.
        if (cast?.owner?.owner?.kind == "cast") link?.endSession(false)
        account = usable
        clear()
    }

    /** Returning to the app reattaches only if a viewing may still be running; otherwise nothing wakes the SDK. */
    fun setForeground(value: Boolean) {
        if (!value || environment.tvInterface || !environment.mayBeLive || account == null || cast != null) return
        if (environment.services() != "ok" || !environment.enabled) return
        ensureLink()
    }

    fun openChooser(playRequest: CastPlayRequest?) {
        request = playRequest
        mutable.update { it.copy(chooserOpen = true, blockedReach = null) }
        if (playRequest == null) return
        refreshCapability()
        startSearch()
    }

    fun closeChooser() {
        // Picked and on its way: the request has to outlive the dialog.
        if (cast?.owner?.candidate == null) request = null
        stopSearch()
        mutable.update { it.copy(chooserOpen = false, blockedReach = null) }
    }

    fun setEnabled(value: Boolean) {
        environment.enabled = value
        if (!value) {
            stopSearch()
            // Turning it off keeps this app away from Cast devices. What is already playing there is left alone.
            if (cast?.owner?.owner?.kind == "cast") disconnect()
        } else if (mutable.value.chooserOpen && request != null) {
            startSearch()
        }
        refreshCapability()
    }

    fun refreshCapability() {
        mutable.update { it.copy(capability = capability(it.routes.size)) }
    }

    private fun capability(targets: Int): CastCapability {
        // The TV interface answers before anything else is asked of the device.
        if (environment.tvInterface) return castCapability(CastCapabilityInput(surface = "tv"))
        return castCapability(
            CastCapabilityInput(
                surface = "touch",
                osSupported = environment.osSupported,
                services = environment.services(),
                viewerEnabled = environment.enabled,
                localNetworkAllowed = environment.localNetworkAllowed(),
                discovery = discovery,
                targets = targets
            )
        )
    }

    private fun ensureLink(): CastLink {
        link?.let { return it }
        val built = environment.link()
        link = built
        built.setListener(this)
        routesJob = scope.launch {
            built.routes.collect { routes ->
                mutable.update { it.copy(routes = routes, capability = capability(routes.size)) }
            }
        }
        return built
    }

    private fun startSearch() {
        discovery = "idle"
        if (!capability(0).canDiscover) return refreshCapability()
        discovery = "searching"
        ensureLink().startDiscovery()
        refreshCapability()
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(CAST_SEARCH_MS)
            discovery = "finished"
            refreshCapability()
        }
    }

    private fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        discovery = "idle"
        link?.stopDiscovery()
        mutable.update { it.copy(routes = emptyList()) }
    }

    /** The one call that starts a handoff, and only a viewer's pick reaches it. */
    fun pick(route: CastRoute) {
        val active = account ?: return
        val playRequest = request ?: return
        if (!capability(0).canDiscover) return
        val reach = castReach(CastReachInput(active.server.url, environment.senderVpn()))
        if (!reach.allowed) {
            mutable.update { it.copy(blockedReach = reach) }
            return
        }
        val local = localIdentity(active, nextGeneration())
        val target = ViewingIdentity(
            active.server.id,
            active.user.id,
            route.id,
            "cast-" + local.generation,
            nextGeneration(),
            "cast"
        )
        val item = playRequest.items.getOrNull(playRequest.startIndex) ?: return
        val intent = ControlIntent(
            entry = entryId(playRequest.startIndex, item.id),
            ticks = playRequest.startMs * MS_TO_TICKS,
            paused = playRequest.paused,
            queue = playRequest.items.mapIndexed { index, it -> entryId(index, it.id) }
        )
        prepared = emptyMap()
        loadedUrls = emptyMap()
        reported = null
        lastStatus = null
        loadStarted = false
        localAlive = playRequest.local != null
        mutable.update {
            it.copy(cast = CastState(OwnerState(local, intent = intent)), deviceName = route.name, title = item.title, controls = null)
        }
        environment.mayBeLive = true
        dispatch(CastEvent("select", identity = target, intent = intent))
        dispatch(CastEvent("link-connecting"))
        stopSearch()
        mutable.update { it.copy(chooserOpen = false) }
        val joining = ensureLink()
        loadTimer?.cancel()
        // The same limit covers joining the device: a route that never connects must not leave local playback waiting.
        loadTimer = scope.launch {
            delay(CAST_LOAD_TIMEOUT_MS)
            dispatch(CastEvent("load-timeout"))
        }
        joining.select(route.id)
    }

    private fun nextGeneration() = ++generation

    private fun localIdentity(active: Account, value: Long) =
        ViewingIdentity(active.server.id, active.user.id, LOCAL_DEVICE, "local-$value", value, "local")

    private fun entryId(index: Int, itemId: String) = "$index:$itemId"

    private fun dispatch(event: CastEvent) {
        val current = cast ?: return
        val next = reduceCast(current, event)
        mutable.update { ui ->
            ui.copy(cast = next, controls = next.observed?.let(::controlsFor))
        }
        runEffect(next)
    }

    private fun controlsFor(observed: CastObservation): CastControls = castControls(
        CastControlsInput(
            commands = observed.commands,
            durationTicks = observed.durationTicks,
            live = lastStatus?.live == true,
            seekableStartTicks = lastStatus?.seekableStartMs?.let { it * MS_TO_TICKS },
            seekableEndTicks = lastStatus?.seekableEndMs?.let { it * MS_TO_TICKS },
            tracks = (prepared[observed.entry] ?: prepared.values.firstOrNull())?.let { decision ->
                account?.let { castTracks(it.server, decision) }
            }.orEmpty(),
            activeTracks = observed.activeTracks
        )
    )

    private fun castTracks(server: Server, decision: PlayDecision) = castSubtitles(server, decision, "").map {
        CastTrack(it.trackId, "subtitle", it.label)
    }

    private fun runEffect(state: CastState) {
        when (state.effect) {
            "pause-local" -> pauseLocal()
            "request-remote" -> loadRemote()
            "release-local" -> released()
            "request-status" -> link?.requestStatus()
            "send-command" -> sendCommand()
            "stop-remote" -> sendStop()
            "resume-here" -> if (state.owner.phase == "failed") failed() else stopped(state)
            "cleanup" -> stopped(state)
            "detach" -> detached()
            "play-local" -> playLocal()
            else -> Unit
        }
    }

    private fun pauseLocal() {
        val playRequest = request
        val output = playRequest?.local
        if (output == null) {
            dispatch(CastEvent("local-paused", ticks = (playRequest?.startMs ?: 0) * MS_TO_TICKS))
            return
        }
        scope.launch {
            val position = output.pauseAndReadPositionMs()
            dispatch(CastEvent("local-paused", ticks = position * MS_TO_TICKS))
        }
    }

    private fun loadRemote() {
        val active = account ?: return dispatch(CastEvent("load-failed"))
        val playRequest = request ?: return dispatch(CastEvent("load-failed"))
        val state = cast ?: return
        val target = state.owner.candidate ?: return
        val startMs = state.owner.intent.ticks / MS_TO_TICKS
        val serial = target.generation
        scope.launch {
            val items = mutableListOf<CastLoadItem>()
            val decisions = mutableMapOf<String, PlayDecision>()
            playRequest.items.forEachIndexed { index, item ->
                val outcome = gateway.prepare(active, item.id, playRequest, if (index == playRequest.startIndex) startMs else 0)
                if (cast?.owner?.candidate?.generation != serial) return@launch
                val decision = ((outcome as? ClientResult.Success)?.value as? PlayOutcome.Play)?.decision
                if (decision == null) {
                    val refused = outcome is ClientResult.Success
                    dispatch(CastEvent("load-failed", recovery = if (refused) "unsupported-media" else "server-unreachable"))
                    return@launch
                }
                val entry = entryId(index, item.id)
                decisions[entry] = decision
                items += loadItem(active, target, item, entry, decision)
            }
            prepared = decisions
            loadedUrls = items.associate { it.url to (it.customData["entry"] ?: "") }
            loadStarted = true
            val current = link ?: return@launch dispatch(CastEvent("load-failed"))
            current.load(CastLoadRequest(items, playRequest.startIndex, startMs, autoplay = !state.owner.intent.paused)) { accepted ->
                if (!accepted && cast?.owner?.candidate?.generation == serial) {
                    dispatch(CastEvent("load-failed", recovery = "unsupported-media"))
                } else if (accepted && cast?.owner?.candidate?.generation == serial) {
                    current.requestStatus()
                }
            }
        }
    }

    private fun loadItem(active: Account, target: ViewingIdentity, item: CastPlayItem, entry: String, decision: PlayDecision): CastLoadItem {
        val tracks = castSubtitles(active.server, decision, active.accessToken).map {
            CastLoadTrack(it.trackId.toLong(), it.label, it.language, it.url)
        }
        return CastLoadItem(
            url = castMediaUrl(decision, active.accessToken),
            contentType = castContentType(decision),
            title = item.title,
            imageUrl = item.imageUrl,
            durationMs = item.durationMs,
            live = item.live,
            tracks = tracks,
            customData = buildMap {
                put("app", "pictureshow")
                put("generation", target.generation.toString())
                put("server", target.server)
                put("viewer", target.viewer)
                put("device", target.device)
                put("session", target.session)
                put("entry", entry)
                put("item", item.id)
                put("method", decision.method.name)
                decision.mediaSourceId?.let { put("source", it) }
                decision.playSessionId?.let { put("playSession", it) }
            }
        )
    }

    /** A failed handoff: the local player stays paused where it was, and Resume here is the viewer's call. */
    private fun failed() {
        loadTimer?.cancel()
        val sent = loadStarted
        loadStarted = false
        val current = link ?: return
        if (sent) current.stop { }
        if (cast?.link != "lost") current.endSession(stopReceiver = true)
        environment.mayBeLive = false
    }

    private fun released() {
        loadTimer?.cancel()
        request?.local?.release()
        localAlive = false
        request = null
        val active = account
        val report = reportFor()
        reported = report
        if (active != null && report != null) scope.launch { gateway.reportStart(active, report) }
        startReporting()
    }

    private fun startReporting() {
        reportJob?.cancel()
        reportJob = scope.launch {
            while (isActive) {
                delay(CAST_REPORT_MS)
                report()
            }
        }
    }

    private fun report() {
        val before = cast ?: return
        val after = reduceCast(before, CastEvent("report", sequence = ++reportSequence))
        mutable.update { it.copy(cast = after) }
        if (after.effect != "report") return
        val active = account ?: return
        val value = reportFor() ?: return
        reported = value
        scope.launch { gateway.reportProgress(active, value) }
    }

    /** The current entry's report, read from the receiver's own status. */
    private fun reportFor(): CastReport? {
        val observed = cast?.observed ?: return reported
        val entry = observed.entry ?: cast?.owner?.intent?.entry
        val decision = prepared[entry] ?: prepared.values.firstOrNull()
        return if (decision != null) {
            CastReport(decision.itemId, decision.mediaSourceId, decision.playSessionId, decision.method, observed.ticks, observed.playerState == "paused")
        } else {
            reported?.copy(positionTicks = observed.ticks, paused = observed.playerState == "paused")
        }
    }

    private fun sendCommand() {
        val state = cast ?: return
        val pending = state.commands?.pending ?: return
        val current = link ?: return
        val answer: (Boolean) -> Unit = { accepted ->
            if (!accepted) dispatch(CastEvent("command-rejected", id = pending.id))
        }
        when (pending.operation) {
            "play" -> current.play(answer)
            "pause" -> current.pause(answer)
            "seek-absolute", "seek-relative" -> current.seek((pending.ticks ?: 0) / MS_TO_TICKS, answer)
            else -> Unit
        }
        commandTimer?.cancel()
        commandTimer = scope.launch {
            delay(CAST_COMMAND_TIMEOUT_MS)
            dispatch(CastEvent("command-timeout", id = pending.id, nowMs = nowMs()))
        }
    }

    private fun sendStop() {
        val current = link
        stopTimer?.cancel()
        stopTimer = scope.launch {
            delay(CAST_STOP_TIMEOUT_MS)
            dispatch(CastEvent("stop-timeout"))
        }
        // A refused Stop isn't proof either way; the timeout turns silence into the explicit choice.
        current?.stop { current.requestStatus() }
    }

    /** The receiver confirmed: the viewing there is over. */
    private fun stopped(state: CastState) {
        stopTimer?.cancel()
        reportJob?.cancel()
        commandTimer?.cancel()
        environment.mayBeLive = false
        cleanup(state.returnTicks)
        link?.endSession(stopReceiver = state.notice != "receiver-taken")
        if (state.stopPurpose == "here" && state.owner.phase == "return-ready") {
            account?.let { dispatch(CastEvent("resume-here", identity = localIdentity(it, nextGeneration()))) }
        } else if (state.stopPurpose == "end") {
            clear()
        }
    }

    private fun cleanup(ticks: Long?) {
        val active = account ?: return
        val last = reportFor()?.let { it.copy(positionTicks = ticks ?: it.positionTicks) }
        val serial = cast?.owner?.generation
        scope.launch {
            if (last != null) {
                gateway.reportStopped(active, last)
                last.playSessionId?.let { gateway.stopEncoding(active, it) }
            }
            if (cast?.owner?.generation == serial) dispatch(CastEvent("cleanup-done"))
        }
    }

    private fun detached() {
        reportJob?.cancel()
        commandTimer?.cancel()
        link?.endSession(stopReceiver = false)
        // Reattaching needs nothing but the receiver's own media; the owner state stays for the bar.
    }

    private fun playLocal() {
        val state = cast ?: return
        val entry = state.owner.intent.entry
        val itemId = entry?.substringAfter(':') ?: return
        localSerial += 1
        val start = CastLocalStart(itemId, state.owner.intent.ticks / MS_TO_TICKS, state.owner.intent.paused, localSerial)
        // The choice to play here despite a Stop nobody confirmed: the viewer took responsibility, so tidy up now.
        if (state.stop == "unknown") {
            environment.mayBeLive = false
            cleanup(state.returnTicks)
            link?.endSession(stopReceiver = true)
        }
        val output = request?.local.takeIf { localAlive }
        mutable.update { CastUi(capability = it.capability, localStart = if (output == null) start else null) }
        // After a failed handoff the player is still open, paused where it was.
        output?.resume()
        localAlive = false
        request = null
    }

    fun localStartHandled(serial: Long) {
        mutable.update { if (it.localStart?.serial == serial) it.copy(localStart = null) else it }
    }

    /** The player screen went away, so Resume here has to open a new one. */
    fun localClosed(output: CastLocalOutput) {
        if (request?.local === output) localAlive = false
    }

    private fun clear() {
        loadTimer?.cancel()
        stopTimer?.cancel()
        commandTimer?.cancel()
        reportJob?.cancel()
        request = null
        prepared = emptyMap()
        reported = null
        mutable.update { CastUi(capability = it.capability, routes = it.routes, chooserOpen = it.chooserOpen, localStart = it.localStart) }
    }

    private fun command(value: String, entry: String? = null) {
        val state = cast ?: return
        val id = (state.commands?.sequence ?: 0).coerceAtLeast(commandId) + 1
        commandId = id
        dispatch(CastEvent("command", value = value, entry = entry, id = id, nowMs = nowMs()))
    }

    fun pause() = command("pause")

    fun play() = command("play")

    fun seekTo(ticks: Long) = command("seek-absolute", ticks.toString())

    fun seekBy(ticks: Long) = command("seek-relative", ticks.toString())

    fun setSubtitle(id: Int?) {
        link?.setActiveTracks(listOfNotNull(id?.toLong())) { link?.requestStatus() }
    }

    fun stop() = dispatch(CastEvent("stop"))

    fun playHere() = dispatch(CastEvent("play-here"))

    fun retryStop() = dispatch(CastEvent("retry-stop"))

    fun disconnect() = dispatch(CastEvent("disconnect"))

    fun cancel() = dispatch(CastEvent("cancel"))

    /** Back to a receiver the viewer left playing. This is the viewer's ask, so a short search is fine here. */
    fun reconnect() {
        val device = cast?.owner?.owner?.device ?: return
        if (cast?.owner?.phase != "disconnected") return
        val current = ensureLink()
        current.startDiscovery()
        scope.launch {
            val found = withTimeoutOrNull(CAST_SEARCH_MS) { current.routes.first { routes -> routes.any { it.id == device } } }
            if (found != null) current.select(device)
            current.stopDiscovery()
        }
    }

    /** Resume here after a failure or an ending; [confirmed] is the viewer accepting a Stop nobody confirmed. */
    fun resumeHere(confirmed: Boolean = false) {
        val active = account ?: return
        dispatch(CastEvent("resume-here", identity = localIdentity(active, nextGeneration()), confirmed = confirmed))
    }

    /** The viewer read the message and wants nothing more from this handoff. */
    fun dismiss() {
        environment.mayBeLive = false
        clear()
    }

    override fun onConnecting() = dispatch(CastEvent("link-connecting"))

    override fun onConnected(deviceName: String?) {
        if (deviceName != null) mutable.update { it.copy(deviceName = deviceName) }
        if (cast == null) {
            awaitingReattach = true
            link?.requestStatus()
            return
        }
        dispatch(CastEvent("link-connected"))
    }

    override fun onSuspended() {
        dispatch(CastEvent("link-suspended"))
    }

    override fun onEnded() {
        awaitingReattach = false
        if (cast == null) {
            environment.mayBeLive = false
            return
        }
        // Our own endSession also lands here, after the viewing has already moved on.
        val state = cast ?: return
        if (state.owner.candidate != null || state.owner.phase == "active" || state.stop == "sent") dispatch(CastEvent("link-lost"))
    }

    override fun onStatus(status: CastStatus) {
        lastStatus = status
        if (cast == null) {
            if (awaitingReattach) reattach(status)
            return
        }
        val before = cast?.observed?.playerState?.takeIf { cast?.owner?.phase == "active" && cast?.owner?.owner?.kind == "cast" }
        dispatch(CastEvent("observe", observation = observation(status), nowMs = nowMs()))
        val after = cast?.observed?.playerState?.takeIf { cast?.owner?.phase == "active" }
        // A change of state goes out at once. The first status is the start report's, not a change.
        if (before != null && after != null && before != after) report()
    }

    /** After a process death: this is ours only if the receiver's media says it is, and only for this viewer. */
    private fun reattach(status: CastStatus) {
        awaitingReattach = false
        val active = account ?: return
        val data = status.customData
        val value = data["generation"]?.toLongOrNull()
        if (status.mediaSession < 0 ||
            status.playerState == "idle" ||
            value == null ||
            data["server"] != active.server.id ||
            data["viewer"] != active.user.id
        ) {
            environment.mayBeLive = false
            link?.endSession(stopReceiver = false)
            return
        }
        generation = maxOf(generation, value)
        val owner = ViewingIdentity(active.server.id, active.user.id, data["device"].orEmpty(), data["session"].orEmpty(), value, "cast")
        val local = localIdentity(active, value)
        val entry = data["entry"]
        val itemId = data["item"].orEmpty()
        reported = CastReport(
            itemId,
            data["source"],
            data["playSession"],
            runCatching { PlayMethod.valueOf(data["method"].orEmpty()) }.getOrDefault(PlayMethod.DirectPlay),
            status.positionMs * MS_TO_TICKS,
            status.playerState == "paused"
        )
        val state = castReattachState(owner, local, ControlIntent(entry = entry, ticks = status.positionMs * MS_TO_TICKS, queue = status.queue))
        mutable.update { it.copy(cast = state, title = status.title) }
        dispatch(CastEvent("link-connected"))
        dispatch(CastEvent("observe", observation = observation(status), nowMs = nowMs()))
        startReporting()
    }

    /** The generation comes from the media itself. If a receiver drops custom data, the URL we loaded still identifies it. */
    private fun observation(status: CastStatus): CastObservation {
        val target = cast?.owner?.candidate ?: cast?.owner?.owner
        val entryFromUrl = status.contentUrl?.let(loadedUrls::get)
        val stamped = status.customData["generation"]?.toLongOrNull()
            ?: target?.generation?.takeIf { entryFromUrl != null }
            ?: -1
        return CastObservation(
            mediaSession = status.mediaSession,
            generation = stamped,
            playerState = status.playerState,
            idleReason = status.idleReason,
            ticks = status.positionMs * MS_TO_TICKS,
            durationTicks = status.durationMs?.let { it * MS_TO_TICKS },
            entry = status.customData["entry"] ?: entryFromUrl,
            activeTracks = status.activeTracks,
            queue = status.queue,
            commands = status.commands
        )
    }
}
