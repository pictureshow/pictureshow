/* SPDX-License-Identifier: MPL-2.0
 * Copyright (c) Legitimate LLC.
 */
package app.pictureshow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.pictureshow.core.Account
import app.pictureshow.core.ClientResult
import app.pictureshow.core.sync.SyncClockAnchor
import app.pictureshow.core.sync.SyncClockCalibration
import app.pictureshow.core.sync.SyncClockSample
import app.pictureshow.core.sync.SyncCommand
import app.pictureshow.core.sync.SyncDriftAction
import app.pictureshow.core.sync.SyncDriftState
import app.pictureshow.core.sync.SyncEvent
import app.pictureshow.core.sync.SyncGroup
import app.pictureshow.core.sync.SyncMembership
import app.pictureshow.core.sync.SyncReadinessState
import app.pictureshow.core.sync.SyncRequest
import app.pictureshow.core.sync.SyncUtcTime
import app.pictureshow.core.sync.beginSyncJoin
import app.pictureshow.core.sync.leaveSync
import app.pictureshow.core.sync.millisecondsToSyncTicks
import app.pictureshow.core.sync.parseSyncMessage
import app.pictureshow.core.sync.recoverSync
import app.pictureshow.core.sync.reduceSyncEvent
import app.pictureshow.core.sync.stopSyncDrift
import app.pictureshow.core.sync.syncCreate
import app.pictureshow.core.sync.syncDrift
import app.pictureshow.core.sync.syncIgnoreWait
import app.pictureshow.core.sync.syncJoin
import app.pictureshow.core.sync.syncNewQueue
import app.pictureshow.core.sync.syncPing
import app.pictureshow.core.sync.syncReadiness
import app.pictureshow.core.sync.syncRemove
import app.pictureshow.core.sync.syncRepeat
import app.pictureshow.core.sync.syncScheduledPlaying
import app.pictureshow.core.sync.syncScheduledPosition
import app.pictureshow.core.sync.syncSelect
import app.pictureshow.core.sync.syncShuffle
import app.pictureshow.core.sync.syncTarget
import app.pictureshow.core.sync.syncTimelinePlaying
import app.pictureshow.core.sync.syncTransport
import app.pictureshow.core.sync.syncTransportApplied
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal interface SyncPlayGateway {
    suspend fun groups(account: Account): ClientResult<List<SyncGroup>>
    suspend fun access(account: Account): ClientResult<String>
    suspend fun time(account: Account): ClientResult<SyncUtcTime>
    suspend fun send(account: Account, request: SyncRequest): Int
}

/** Actual underlying renderer facts, independent of exposed player wrappers and paused intent. */
internal data class SyncNativeRendererState(
    val generation: Long,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val suppressionReason: Int,
    val isLoading: Boolean,
    val positionMs: Long,
    val bufferedPositionMs: Long,
    val bufferedDurationMs: Long
)

internal data class SyncRendererState(
    val itemId: String,
    val positionTicks: Long,
    val durationTicks: Long,
    val ready: Boolean,
    val buffering: Boolean,
    val playing: Boolean,
    val seekable: Boolean,
    val rateSupported: Boolean = false,
    val playlistItemId: String? = null,
    val native: SyncNativeRendererState? = null
)

internal interface SyncRenderer {
    /** A stable bridge can outlive the native decoder it currently controls. */
    val sourceIdentity: Any get() = this
    val positionApplicationPending: Boolean get() = false
    fun snapshot(): SyncRendererState

    /** Bypasses outgoing transport interception; returns whether a position seek was actually issued. */
    fun apply(positionTicks: Long? = null, playing: Boolean? = null, rate: Double = 1.0): Boolean
}

internal data class SyncLocalSelection(val itemId: String, val startTicks: Long)

internal data class SyncOpen(val generation: Long, val entryId: String, val itemId: String, val ticks: Long)

/** One signed-in viewing scope. All methods and socket callbacks run on the main dispatcher. */
internal class SyncPlayHost(
    private val scope: CoroutineScope,
    private val gateway: SyncPlayGateway,
    private val wall: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val diagnosticSink: (String) -> Unit = {}
) {
    private val mutable = MutableStateFlow(SyncPlayPresentation())
    val state = mutable.asStateFlow()
    private val opening = MutableSharedFlow<SyncOpen>(extraBufferCapacity = 8)
    val opens = opening.asSharedFlow()
    var panelOpen by mutableStateOf(false)
    private var account: Account? = null
    internal var membership = SyncMembership()
        private set
    private var leavePending = false
    val participating: Boolean get() = membership.active || membership.groupId != null || leavePending
    var pendingExternalRoute by mutableStateOf<(() -> Unit)?>(null)
        private set
    fun confirmExternalRoute(then: () -> Unit): Boolean {
        if (!participating) return false
        pendingExternalRoute = then
        return true
    }
    fun cancelExternalRoute() {
        pendingExternalRoute = null
    }
    fun leaveForExternalRoute() {
        val then = pendingExternalRoute ?: return
        pendingExternalRoute = null
        leaveConfirmed(then)
    }
    private var connected = false
    private var renderer: SyncRenderer? = null
    private var rendererToken: Any? = null
    private data class PendingSource(val token: Any, val generation: Long, val selection: SyncLocalSelection)
    private var pendingSource: PendingSource? = null
    private var awaitingSource: PendingSource? = null
    private var calibration = SyncClockCalibration()
    private var anchor: SyncClockAnchor? = null
    private var clockJob: Job? = null
    private var ticker: Job? = null
    private var scheduled: Job? = null
    private var timeline: SyncCommand? = null
    private var timelinePlaying = false
    private var timelineApplied = false
    private data class AppliedUnpause(
        val token: Any?,
        val renderer: SyncRenderer,
        val sourceIdentity: Any,
        val generation: Long,
        val command: app.pictureshow.core.sync.SyncCommand
    )
    private var appliedUnpause: AppliedUnpause? = null
    private var drift = SyncDriftState()
    private var readiness = SyncReadinessState()
    private var stoppedEntry: String? = null
    private var requestedOpen: String? = null
    private var openingEntry: String? = null
    private var createQueue = false
    private var ignoreWait = false
    private var waitRequest: Job? = null
    private var operation: Job? = null
    private var sequence = 0L
    private val traceIds = linkedMapOf<String, Long>()
    private val traceTokens = java.util.IdentityHashMap<Any, Long>()
    private var traceOrdinal = 0L
    private var traceSequence = 0L
    private var clockAttempt = 0L
    private var anchorGeneration = 0L
    private var commandSequence = 0L
    private var requestSequence = 0L
    private var lastTickTrace: Long? = null
    private var lastTickGate: String? = null
    private var lastPauseReason: String? = null
    private var lastPauseTrace: Long? = null
    private var traceWindow = 0L
    private var traceEmitted = 0
    private var traceSuppressed = 0L

    /** Rotate before Join so frames queued by the old membership cannot enter the new one. */
    var membershipSocket: ((Long, Boolean) -> Unit)? = null
    private var joiningSocket: CompletableDeferred<Unit>? = null

    fun bind(next: Account?) {
        val usable = next?.takeUnless { it.expired }
        if (usable?.key == account?.key && usable?.accessToken == account?.accessToken) return
        val before = account
        if (participating && before != null) leave()
        account = usable
        sequence++
        operation?.cancel()
        membership = SyncMembership(generation = membership.generation + 1)
        leavePending = false
        resetClock("account")
        trace("membership", stateFields())
        mutable.value = SyncPlayPresentation(server = usable?.server?.name.orEmpty(), viewer = usable?.user?.name.orEmpty())
    }

    fun show() {
        panelOpen = true
        refresh()
    }
    fun refresh() {
        val target = account ?: return
        val version = sequence
        mutable.value =
            mutable.value.copy(busy = true, policy = "None", policyKnown = false, policyFailed = false, groups = emptyList(), status = "")
        scope.launch {
            when (val policy = gateway.access(target)) {
                is ClientResult.Failure -> {
                    if (!current(target, version)) return@launch
                    mutable.value = mutable.value.copy(busy = false, policyFailed = true)
                    note(policy.error.message)
                    return@launch
                }
                is ClientResult.Success -> {
                    if (!current(target, version)) return@launch
                    mutable.value = mutable.value.copy(policy = policy.value, policyKnown = true, status = "")
                }
            }
            if (!mutable.value.accessAllowed) {
                mutable.value = mutable.value.copy(busy = false)
                return@launch
            }
            when (val groups = gateway.groups(target)) {
                is ClientResult.Success -> if (current(target, version)) {
                    mutable.value =
                        mutable.value.copy(
                            busy = false,
                            groups = groups.value.map {
                                SyncPlayGroupRow(it.id, it.name, it.participants.size)
                            }
                        )
                }

                is ClientResult.Failure -> if (current(target, version)) {
                    mutable.value = mutable.value.copy(busy = false)
                    note(groups.error.message)
                }
            }
        }
    }

    fun socket(value: Boolean) {
        if (connected == value) return
        connected = value
        trace("socket", "connected=$value joining=${joiningSocket != null}")
        val waiting = joiningSocket
        if (waiting != null) {
            if (value) waiting.complete(Unit)
            return
        }
        if (!value && participating) {
            scheduled?.cancel()
            pause("socket-disconnect")
            membership = recoverSync(membership, "Connection lost. Playback is paused; rejoin to synchronize again.")
            resetClock("socket-disconnect")
            trace("membership", stateFields())
            publish()
        } else if (value && participating) {
            // Rejoin obtains membership and fresh queue events; stale local state is never replayed.
            val group = membership.groupId
            if (group != null) join(group) else leave()
        }
    }

    fun receive(text: String, generation: Long = membership.generation) {
        val event = parseSyncMessage(text) ?: run {
            trace("receive", "event=unparsed accepted=false")
            return
        }
        val before = membership
        val after = reduceSyncEvent(before, event, generation)
        trace(
            "receive",
            "event=${eventKind(event)} accepted=${after !== before && after != before} suppliedGeneration=$generation " +
                "eventGroup=${traceId(event.groupId)} message=${traceId(event.messageId)} " +
                "beforeGeneration=${before.generation} afterGeneration=${after.generation} " +
                "afterPhase=${after.phase} afterEntry=${traceId(after.queue?.current?.playlistItemId)} " +
                "afterItem=${traceId(after.queue?.current?.itemId)} queueUpdateTicks=${after.queue?.lastUpdateTicks ?: -1} " +
                ((event as? SyncEvent.Command)?.command?.let(::commandFields) ?: "")
        )
        if (after === before || after == before) return
        membership = after
        trace("membership", stateFields())
        if (after.phase != before.phase) diagnostic("phase", "phase=${after.phase}")
        after.command?.takeIf { it.command == "Stop" }?.let { stoppedEntry = it.playlistItemId }
        if (after.generation != before.generation || !after.active) {
            pause("membership-invalidated")
            resetClock("membership-invalidated")
            publish()
            return
        }
        if (after.phase == "Reconnecting") {
            scheduled?.cancel()
            pause("membership-reconnecting")
            timeline = null
            appliedUnpause = null
            publish()
            // A fresh deliberate rejoin is visible; avoid loops on unknown future event types.
            note(after.notice ?: "Synchronization needs a fresh group snapshot. Leave and join again.")
            return
        }
        if (event is SyncEvent.Joined && createQueue) {
            createQueue = false
            renderer?.snapshot()?.takeIf { it.durationTicks > 0 && it.seekable }?.let {
                send(syncNewQueue(listOf(it.itemId), startPositionTicks = it.positionTicks))
            }
        }
        val entry = after.queue?.current
        if (event is SyncEvent.Queue && entry?.playlistItemId != before.queue?.current?.playlistItemId) {
            scheduled?.cancel()
            pause("queue-entry")
            timeline = null
            appliedUnpause = null
            drift = stopSyncDrift()
            readiness = SyncReadinessState()
            requestedOpen = null
        }
        val entryChanged = entry?.playlistItemId != before.queue?.current?.playlistItemId
        val awaiting = awaitingSource
        val reusePrepared = event is SyncEvent.Queue &&
            entry != null &&
            awaiting != null &&
            awaiting.token === rendererToken &&
            awaiting.generation == after.generation &&
            awaiting.selection.itemId == entry.itemId &&
            renderer?.snapshot()?.itemId == entry.itemId
        if (reusePrepared && entry != null) {
            awaitingSource = null
            openingEntry = null
            note("Group queue updated. Waiting for renderer readiness.")
            requestedOpen = entry.playlistItemId
            val prepared = renderer?.snapshot()
            val start = prepared?.takeIf { it.seekable && it.durationTicks > 0 }?.let {
                requireNotNull(after.queue).startPositionTicks.coerceIn(0, it.durationTicks)
            }
            // This decoder already owns the paused selection. Keep its single viewing/report session.
            trace("apply-before", "source=prepared-queue playing=false seekTicks=${start ?: -1} " + stateFields(prepared))
            val positionChanged = renderer?.apply(start, playing = false) == true
            readiness = readiness.afterPositionApplied(positionChanged)
            trace("apply-after", "source=prepared-queue " + stateFields())
        }
        val needsOpen = entry != null && !reusePrepared && (renderer?.snapshot()?.itemId != entry.itemId || entryChanged)
        if (entry != null && needsOpen && requestedOpen != entry.playlistItemId) {
            pause("queue-opening")
            requestedOpen = entry.playlistItemId
            openingEntry = entry.playlistItemId
            opening.tryEmit(SyncOpen(after.generation, entry.playlistItemId, entry.itemId, requireNotNull(after.queue).startPositionTicks))
        }
        after.command?.let { command ->
            if (command !== before.command) schedule(command)
        }
        publish()
    }

    fun resolved(open: SyncOpen, ok: Boolean): Boolean {
        if (open.generation != membership.generation || open.entryId != membership.queue?.current?.playlistItemId) return false
        if (!ok) {
            pause("resolve-failed")
            membership = membership.copy(phase = "Error", notice = "Library access denied or media unavailable. Leave to recover.")
            trace("membership", stateFields())
            note(requireNotNull(membership.notice))
        }
        return ok
    }

    fun join(group: String) = begin(syncJoin(group), group, false)
    fun create(name: String) = begin(syncCreate(name), null, true)
    private fun begin(request: SyncRequest, group: String?, creating: Boolean) {
        val target = account ?: return
        if (!connected) {
            note("Waiting for the server connection. Try again when connected.")
            return
        }
        val local = renderer?.snapshot()
        if (local != null && (!local.seekable || local.durationTicks <= 0)) {
            note("Watch together is unavailable for live, infinite or offline sources. Open an online movie, episode or song first.")
            return
        }
        val presentation = state.value
        if (!presentation.policyKnown) {
            note("Refresh to confirm watch-together access before joining a group.")
            return
        }
        val policy = presentation.policy
        if (!presentation.accessAllowed || (creating && policy != "CreateAndJoinGroups")) {
            note("This viewer is not allowed to ${if (creating) "create" else "join"} a group.")
            return
        }
        sequence++
        operation?.cancel()
        membership = beginSyncJoin(membership, group)
        createQueue = creating
        pause("join")
        resetClock("join")
        trace("membership", stateFields())
        publish()
        val version = sequence
        val waitForSocket = membershipSocket?.let { reset ->
            CompletableDeferred<Unit>().also {
                joiningSocket = it
                connected = false
                reset(membership.generation, true)
            }
        }
        operation = scope.launch {
            if (waitForSocket != null) {
                val opened = withTimeoutOrNull(10_000) {
                    waitForSocket.await()
                    true
                } == true
                if (joiningSocket === waitForSocket) joiningSocket = null
                if (!current(target, version)) return@launch
                if (!opened) {
                    membership = membership.copy(phase = "Reconnecting")
                    publish()
                    note("Could not refresh the group connection. Leave and try joining again.")
                    return@launch
                }
            }
            val status = tracedSend(target, request)
            if (!current(target, version)) return@launch
            if (status !in 200..299) {
                membership = if (status in setOf(400, 401, 403, 404)) {
                    leaveSync(membership)
                } else {
                    membership.copy(phase = "Reconnecting")
                }
                membershipSocket?.invoke(membership.generation, false)
                publish()
                note(
                    if (status == 401 ||
                        status == 403
                    ) {
                        "The server denied this group request."
                    } else {
                        "Group request not confirmed. Refresh before trying again."
                    }
                )
                return@launch
            }
            calibrate()
            if (!current(target, version)) return@launch
            startTicker()
            delay(10_000)
            if (membership.group == null || (membership.queue == null && membership.group?.state != "Idle")) {
                pause("join-snapshot-timeout")
                note("The server has not supplied a complete group snapshot. Leave and join again.")
            }
        }
    }

    /** Leave invalidates events immediately, retains local paused resume and sends no group Stop. */
    fun leave() = leaveConfirmed(null)
    private fun leaveConfirmed(after: (() -> Unit)?) {
        val target = account
        val hadMembership = participating || membership.groupId != null
        sequence++
        operation?.cancel()
        membership = leaveSync(membership)
        leavePending = false
        joiningSocket?.cancel()
        joiningSocket = null
        membershipSocket?.invoke(membership.generation, false)
        pause("leave")
        resetClock("leave")
        trace("membership", stateFields())
        createQueue = false
        ignoreWait = false
        publish()
        note("Left Watch together. This device is paused; the group can keep playing.")
        val version = sequence
        if (target != null && hadMembership) {
            scope.launch {
                val status = tracedSend(target, SyncRequest("Leave"))
                if (!current(target, version)) return@launch
                if (status in 200..299 && !participating) after?.invoke()
                if (status !in 200..299 && !participating) {
                    leavePending = true
                    mutable.value = mutable.value.copy(phase = "Reconnecting")
                    note("This device is paused. The server has not confirmed Leave; reconnect and leave again.")
                }
            }
        }
    }

    fun background() {
        trace("lifecycle", "source=background participating=$participating")
        if (participating) leave()
    }
    fun bindRenderer(token: Any, next: SyncRenderer, localSelection: SyncLocalSelection? = null, opening: SyncOpen? = null): Boolean {
        val current = membership.queue?.current
        if (opening != null &&
            (
                next.snapshot().itemId != opening.itemId ||
                    !app.pictureshow.core.sync.syncOpenMatches(
                        opening.generation,
                        membership.generation,
                        opening.entryId,
                        current?.playlistItemId,
                        opening.itemId,
                        current?.itemId
                    )
                )
        ) {
            trace(
                "bind",
                "accepted=false candidateToken=${traceToken(token)} openGeneration=${opening.generation} " +
                    "openEntry=${traceId(opening.entryId)} openItem=${traceId(opening.itemId)} " + stateFields(next.snapshot())
            )
            pause("bind-rejected", next)
            return false
        }
        trace(
            "bind",
            "accepted=true candidateToken=${traceToken(token)} replacedToken=${traceToken(rendererToken)} " +
                "openGeneration=${opening?.generation ?: -1} openEntry=${traceId(opening?.entryId)} " + stateFields(next.snapshot())
        )
        if (rendererToken !== token || renderer !== next) appliedUnpause = null
        rendererToken = token
        renderer = next
        if (opening != null && opening.entryId == openingEntry) openingEntry = null
        awaitingSource = null
        pendingSource = localSelection?.takeIf {
            participating && membership.group != null && it.itemId == next.snapshot().itemId
        }?.let { PendingSource(token, membership.generation, it) }
        if (participating) {
            pause("bind", next)
            readiness = SyncReadinessState()
        }
        return true
    }
    fun clearRenderer(token: Any) {
        trace("clear", "clearToken=${traceToken(token)} accepted=${rendererToken === token} " + stateFields())
        if (rendererToken !== token) return
        renderer = null
        appliedUnpause = null
        rendererToken = null
        pendingSource = null
        awaitingSource = null
        readiness = SyncReadinessState()
    }

    /** True consumes every timeline intent while grouped, even during reconnect or preparation. */
    fun transport(command: String, positionMs: Long? = null): Boolean {
        trace(
            "transport",
            "command=${if (command in setOf("play", "pause", "stop", "seek", "next", "previous", "speed")) command else "unknown"} " +
                "positionMs=${positionMs ?: -1} consumed=$participating"
        )
        if (!participating) return false
        val entry = membership.queue?.current
        when (command) {
            "play", "pause", "stop" -> send(
                syncTransport(
                    when (command) {
                        "play" -> "Unpause"
                        "pause" -> "Pause"
                        else -> "Stop"
                    }
                )
            )

            "seek" -> if (positionMs != null && positionMs >= 0) {
                val duration = renderer?.snapshot()?.durationTicks?.takeIf { it > 0 }
                if (duration != null) {
                    millisecondsToSyncTicks(positionMs)?.coerceAtMost(duration)?.let { send(syncTransport("Seek", it)) }
                }
            }

            "next", "previous" -> if (entry !=
                null
            ) {
                send(syncTransport(if (command == "next") "NextItem" else "PreviousItem", playlistItemId = entry.playlistItemId))
            }

            "speed" -> note("Watch together uses the shared 1× timeline; synchronization adjusts this device when needed.")
        }
        return true
    }

    fun action(action: String, value: String?) {
        when (action) {
            "refresh" -> refresh()

            "create" -> value?.let(::create)

            "join" -> value?.let(::join)

            "leave" -> leave()

            "play", "pause", "next", "previous" -> transport(action)

            "ignore-wait" -> {
                val requested = value == "true"
                val previous = waitRequest
                val version = sequence
                waitRequest = scope.launch {
                    previous?.join()
                    if (version != sequence) return@launch
                    send(syncIgnoreWait(requested)) {
                        ignoreWait = requested
                        publish()
                    }?.join()
                }
            }

            "repeat" -> value?.let { send(syncRepeat(it)) }

            "shuffle" -> send(syncShuffle(value == "Shuffle"))

            "select-entry" -> value?.let { send(syncSelect(it)) }

            "remove-entry" -> value?.let { send(syncRemove(listOf(it))) }
        }
    }

    fun queueRequest(request: SyncRequest): Boolean {
        if (!participating) return false
        send(request)
        return true
    }

    private fun send(request: SyncRequest, confirmed: (() -> Unit)? = null): Job? {
        val target = account ?: run {
            trace("request-rejected", "operation=${request.operation} gate=no-account")
            return null
        }
        if (!connected || membership.group == null || membership.phase in setOf("Error", "Reconnecting")) {
            trace("request-rejected", "operation=${request.operation} gate=membership connected=$connected phase=${membership.phase}")
            note("Group controls are unavailable until membership and synchronization are refreshed.")
            return null
        }
        val version = sequence
        return scope.launch {
            val status = tracedSend(target, request)
            if (current(target, version) && status in 200..299) confirmed?.invoke()
            if (current(target, version) && status !in 200..299) {
                note(
                    if (status == 401 || status == 403) {
                        "The server denied this group request."
                    } else {
                        "Group request not confirmed. Refresh before repeating it."
                    }
                )
            }
        }
    }

    private suspend fun calibrate() {
        val target = account ?: return
        val version = sequence
        val attempt = ++clockAttempt
        trace("clock-begin", "attempt=$attempt " + stateFields())
        var completed = false
        try {
            var next = SyncClockCalibration()
            repeat(5) { index ->
                val t0 = wall()
                val m0 = monotonic()
                val result = gateway.time(target)
                val t3 = wall()
                if (!current(target, version)) {
                    trace("clock-abort", "attempt=$attempt reason=operation-changed")
                    return
                }
                val m3 = monotonic()
                val wallDelta = (t3 - t0) - (m3 - m0)
                var sample: SyncClockSample? = null
                if (kotlin.math.abs(wallDelta) <= 100) {
                    (result as? ClientResult.Success)?.value?.let {
                        sample = SyncClockSample(t0, it.receptionMs, it.transmissionMs, t3)
                        next = next.add(requireNotNull(sample))
                    }
                }
                val measured = sample?.measurement()
                val outcome = when {
                    kotlin.math.abs(wallDelta) > 100 -> "wall-jump"
                    result !is ClientResult.Success -> "request-failed"
                    measured == null -> "invalid-sample"
                    else -> "valid"
                }
                trace(
                    "clock-sample",
                    "attempt=$attempt index=$index outcome=$outcome elapsedDeltaMs=${m3 - m0} " +
                        "wallDeltaMs=$wallDelta rttMs=${measured?.networkRttMillis ?: -1} " +
                        "offsetTicks=${measured?.offsetTicks ?: 0} uncertaintyTicks=${measured?.uncertaintyTicks ?: -1}"
                )
            }
            calibration = next
            val oldAnchor = anchor
            anchor = next.anchor(wall(), monotonic())
            anchorGeneration++
            trace(
                "clock-end",
                "attempt=$attempt validSamples=${next.samples.size} oldAnchor=${oldAnchor != null} " +
                    "oldAnchorElapsedMs=${oldAnchor?.localMonotonicMillis ?: -1} oldAnchorGeneration=${anchorGeneration - 1} " +
                    "newAnchor=${anchor != null} " + stateFields()
            )
            completed = true
            next.best?.let { send(syncPing(it.networkRttMillis)) }
            if (anchor == null) {
                appliedUnpause = null
                pause("clock-calibration")
                note("Synchronization unavailable: no valid server clock samples. Leave or try joining again.")
            }
        } finally {
            if (!completed) trace("clock-unfinished", "attempt=$attempt current=${current(target, version)}")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            trace("ticker-start", stateFields())
            try {
                while (participating && isActive) {
                    if (anchor?.needsRefresh(monotonic()) == true && clockJob?.isActive != true) {
                        clockJob = scope.launch { calibrate() }
                    }
                    tick()
                    delay(250)
                }
            } finally {
                trace("ticker-end", "active=$isActive " + stateFields())
            }
        }
    }

    internal fun tick() {
        val output = renderer ?: run {
            tickTrace("no-renderer")
            return
        }
        val r = output.snapshot()
        if (participating && r.ready && !r.seekable) {
            appliedUnpause = null
            pause("source-ineligible", output)
            note("Watch together is unavailable for this source. Leave the group to play it separately.")
        }
        pendingSource?.let { pending ->
            if (pending.token !== rendererToken || pending.generation != membership.generation || !participating) {
                pendingSource = null
            } else if (membership.queue?.current?.itemId == pending.selection.itemId) {
                // Another participant selected it first; the server's position and entry identity win.
                pendingSource = null
            } else if (r.itemId == pending.selection.itemId && r.ready && r.seekable && r.durationTicks > 0) {
                val request = syncNewQueue(
                    listOf(pending.selection.itemId),
                    startPositionTicks = pending.selection.startTicks.coerceIn(0, r.durationTicks)
                )
                if (send(request) != null) {
                    awaitingSource = pending
                    pendingSource = null
                    note("Waiting for the server to update the group queue.")
                }
            }
        }
        val current = membership.queue?.current ?: run {
            tickTrace("no-entry", r)
            return
        }
        // A first queue can arrive without StateUpdate while our cached phase is still Idle.
        // Report actual readiness for that new entry, but do not restart an explicitly stopped entry.
        if (!app.pictureshow.core.sync.syncReportsReadiness(membership.phase, current.playlistItemId, stoppedEntry)) {
            tickTrace("stopped-entry", r)
            return
        }
        if (!membership.hasSnapshot || r.itemId != current.itemId || !rendererOwnsEntry(r, current.playlistItemId)) {
            tickTrace(
                if (!membership.hasSnapshot) {
                    "no-snapshot"
                } else if (r.itemId != current.itemId) {
                    "item-mismatch"
                } else {
                    "entry-fence"
                },
                r
            )
            return
        }
        val now = anchor?.serverNowTicks(wall(), monotonic())
        if (now == null) {
            appliedUnpause = null
            pause("clock-unavailable", output)
            note("Synchronization unavailable: refresh the server clock by joining again.")
            tickTrace("clock-unavailable", r)
            return
        }
        val ready = r.ready && !r.buffering && r.seekable && r.durationTicks > 0
        val readinessObservation = readiness.observe(current.playlistItemId, ready)
        if (readinessObservation.report) {
            readiness = readinessObservation.state
            if (!ready) pause("not-ready", output)
            diagnostic("readiness", "ready=$ready buffering=${r.buffering} playing=${r.playing}")
            trace(
                "ready-request",
                "ready=$ready whenMs=${now / 10_000} positionTicks=${r.positionTicks} playing=${r.playing} " + stateFields(r)
            )
            send(syncReadiness(ready, now / 10_000, r.positionTicks, r.playing, current.playlistItemId))
        }
        val command = timeline ?: run {
            tickTrace("no-timeline", r)
            return
        }
        if (!ready || command.playlistItemId != current.playlistItemId || membership.phase !in setOf("Playing", "Paused")) {
            tickTrace(
                if (!ready) {
                    "not-ready"
                } else if (command.playlistItemId != current.playlistItemId) {
                    "command-entry"
                } else {
                    "phase"
                },
                r
            )
            return
        }
        if (!timelineApplied) {
            if (timelinePlaying && membership.phase != "Playing") {
                tickTrace("pending-playing-phase", r)
                return
            }
            // A scheduled command may have reached When before this decoder was ready.
            // Re-run its scheduled application, including play/pause even when position is aligned.
            tickTrace("pending-application", r)
            if (scheduled?.isActive != true) schedule(command, timelinePlaying)
            return
        }
        val playing = syncTimelinePlaying(command.command, timelinePlaying)
        val target = syncTarget(command.positionTicks ?: r.positionTicks, command.whenMs, now, playing, r.durationTicks) ?: run {
            tickTrace("invalid-target", r)
            return
        }
        if (target.delayMillis > 0) {
            tickTrace("future-when", r)
            return
        }
        tickTrace("drift", r)
        val decision = syncDrift(
            drift,
            r.positionTicks,
            target.positionTicks,
            playing,
            monotonic(),
            r.durationTicks,
            r.rateSupported,
            if (r.rateSupported) setOf(0.97, 1.03) else emptySet()
        )
        drift = decision.state
        if (decision.action == SyncDriftAction.SEEK) {
            diagnostic("correction-seek", "errorTicks=${r.positionTicks - target.positionTicks} targetTicks=${decision.targetTicks}")
            trace(
                "apply-before",
                "source=correction action=${decision.action} targetTicks=${decision.targetTicks} playing=$playing " + stateFields(r)
            )
            val changed = output.apply(decision.targetTicks, playing, decision.rate)
            readiness = readiness.afterPositionApplied(changed)
            trace("apply-after", "source=correction positionChanged=$changed " + stateFields())
        } else {
            output.apply(playing = playing, rate = decision.rate)
        }
        val errorMs = (r.positionTicks - target.positionTicks) / 10_000
        val uncertainty = (anchor?.uncertaintyTicks ?: Long.MAX_VALUE) / 10_000
        note(
            "${if (uncertainty <= 50) "Renderer timing — not yet qualified" else "Not qualified"} · drift $errorMs ms · clock uncertainty ±$uncertainty ms"
        )
    }

    private fun rendererOwnsEntry(snapshot: SyncRendererState, entryId: String): Boolean {
        if (openingEntry == null) return true
        // Persistent music bridges expose Media3's actual current queue slot; matching ItemId alone is insufficient.
        if (!app.pictureshow.core.sync.syncRendererOwnsEntry(openingEntry, snapshot.playlistItemId, entryId)) return false
        trace("entry-ack", "entry=${traceId(entryId)} " + stateFields(snapshot))
        openingEntry = null
        return true
    }

    private fun alreadyAppliedUnpause(command: SyncCommand): Boolean = appliedUnpause?.let {
        it.token === rendererToken &&
            it.renderer === renderer &&
            it.sourceIdentity === renderer?.sourceIdentity &&
            it.generation == membership.generation &&
            app.pictureshow.core.sync.syncSameUnpauseTimeline(it.command, command)
    } == true
    private fun schedule(command: SyncCommand, retainedPlaying: Boolean? = null) {
        if (!alreadyAppliedUnpause(command)) appliedUnpause = null
        scheduled?.cancel()
        // Acceptance can publish Playing before When. The ticker must wait on this command,
        // rather than extrapolating a previous paused Seek through the wait interval.
        timeline = command
        timelinePlaying = retainedPlaying ?: syncTimelinePlaying(command.command, membership.phase == "Playing")
        val intendedPlaying = timelinePlaying
        timelineApplied = false
        val attempt = ++commandSequence
        trace("schedule-pending", "attempt=$attempt " + commandFields(command) + " " + stateFields())
        scheduled = scope.launch {
            var outcome = "unfinished"
            fun abort(reason: String) {
                if (reason == "clock-before-wait" || reason == "clock-after-wait") appliedUnpause = null
                outcome = reason
                trace("schedule-abort", "attempt=$attempt reason=$reason " + stateFields())
            }
            try {
                val generation = membership.generation
                val now = anchor?.serverNowTicks(wall(), monotonic()) ?: run {
                    // Join's clock exchange may still be in flight. Keep the pending timeline, but never output without calibration.
                    abort("clock-before-wait")
                    return@launch
                }
                val duration = renderer?.snapshot()?.durationTicks ?: run {
                    abort("no-renderer-before-wait")
                    return@launch
                }
                val target = syncTarget(
                    command.positionTicks ?: renderer?.snapshot()?.positionTicks ?: 0,
                    command.whenMs,
                    now,
                    command.command == "Unpause",
                    duration
                ) ?: run {
                    abort("invalid-target-before-wait")
                    return@launch
                }
                trace("schedule-wait", "attempt=$attempt delayMs=${target.delayMillis} nowTicks=$now targetTicks=${target.positionTicks}")
                if (target.delayMillis > 0) delay(target.delayMillis)
                trace("schedule-wake", "attempt=$attempt")
                if (generation != membership.generation || command.playlistItemId != membership.queue?.current?.playlistItemId) {
                    abort(if (generation != membership.generation) "generation" else "entry")
                    return@launch
                }
                val r = renderer?.snapshot() ?: run {
                    abort("no-renderer-after-wait")
                    return@launch
                }
                if (!r.ready ||
                    r.buffering ||
                    !r.seekable ||
                    r.itemId != membership.queue?.current?.itemId ||
                    !rendererOwnsEntry(r, command.playlistItemId)
                ) {
                    abort(
                        when {
                            !r.ready -> "not-ready"
                            r.buffering -> "buffering"
                            !r.seekable -> "not-seekable"
                            r.itemId != membership.queue?.current?.itemId -> "item"
                            else -> "entry-fence"
                        }
                    )
                    return@launch
                }
                val at = anchor?.serverNowTicks(wall(), monotonic()) ?: run {
                    abort("clock-after-wait")
                    return@launch
                }
                // Waiting may temporarily hold a playing command; it does not erase that command's intent.
                val playing = syncScheduledPlaying(intendedPlaying, membership.phase)
                val execution =
                    syncTarget(command.positionTicks ?: r.positionTicks, command.whenMs, at, playing, r.durationTicks) ?: run {
                        abort("invalid-target-after-wait")
                        return@launch
                    }
                diagnostic("scheduled-apply", "command=${command.command} playing=$playing targetTicks=${execution.positionTicks}")
                // A Ready response can repeat an already-applied timeline with a new
                // message/emission identity. Resume its mode without bypassing the
                // autonomous correction cooldown with another positional seek.
                val alreadyPositioned = alreadyAppliedUnpause(command)
                val position = if (alreadyPositioned) {
                    null
                } else {
                    syncScheduledPosition(
                        command.command,
                        r.positionTicks,
                        execution.positionTicks
                    )
                }
                trace(
                    "apply-before",
                    "source=scheduled attempt=$attempt playing=$playing seekTicks=${position ?: -1} nowTicks=$at " + stateFields(r)
                )
                val applyingRenderer = renderer
                val applyingToken = rendererToken
                val applyingSource = applyingRenderer?.sourceIdentity
                val positionChanged = applyingRenderer?.apply(position, playing) == true
                // A BUFFERING -> READY cycle can finish between 250 ms samples. The old
                // acknowledgement belongs to the pre-seek position, even if both samples are READY.
                readiness = readiness.afterPositionApplied(positionChanged)
                timelineApplied = !intendedPlaying || playing
                if (!alreadyPositioned &&
                    command.command == "Unpause" &&
                    playing &&
                    timelineApplied &&
                    applyingRenderer != null &&
                    !applyingRenderer.positionApplicationPending &&
                    renderer === applyingRenderer &&
                    rendererToken === applyingToken &&
                    applyingSource === applyingRenderer.sourceIdentity
                ) {
                    appliedUnpause = AppliedUnpause(applyingToken, applyingRenderer, requireNotNull(applyingSource), generation, command)
                }
                // Ready/Unpause after a correction-induced buffer must not re-arm an immediate seek.
                drift = syncTransportApplied(drift, monotonic(), positionChanged)
                outcome = "applied"
                trace("apply-after", "source=scheduled attempt=$attempt positionChanged=$positionChanged " + stateFields())
            } finally {
                if (outcome == "unfinished") abort(if (isActive) "failed" else "cancelled")
                trace("schedule-end", "attempt=$attempt outcome=$outcome active=$isActive")
            }
        }
    }

    private fun resetClock(reason: String) {
        appliedUnpause = null
        trace("clock-reset", "reason=$reason nextAnchorGeneration=${anchorGeneration + 1} " + stateFields())
        pendingSource = null
        awaitingSource = null
        waitRequest = null
        ticker?.cancel()
        ticker = null
        clockJob?.cancel()
        clockJob = null
        scheduled?.cancel()
        scheduled = null
        anchor = null
        anchorGeneration++
        calibration = SyncClockCalibration()
        timeline = null
        timelinePlaying = false
        timelineApplied = false
        drift = stopSyncDrift()
        readiness = SyncReadinessState()
        stoppedEntry = null
        requestedOpen = null
        openingEntry = null
    }
    private fun current(target: Account, version: Long) =
        version == sequence && target.key == account?.key && target.accessToken == account?.accessToken

    private suspend fun tracedSend(target: Account, request: SyncRequest): Int {
        val id = ++requestSequence
        val generation = membership.generation
        val version = sequence
        trace("request-begin", "request=$id operation=${request.operation} requestGeneration=$generation " + stateFields())
        var status = -1
        try {
            status = gateway.send(target, request)
            return status
        } finally {
            trace(
                "request-end",
                "request=$id operation=${request.operation} requestGeneration=$generation " +
                    "status=$status current=${current(target, version)} " + stateFields()
            )
        }
    }

    private fun pause(reason: String, output: SyncRenderer? = renderer) {
        if (BuildConfig.DEBUG) {
            val now = monotonic()
            if (reason != lastPauseReason || lastPauseTrace == null || now - requireNotNull(lastPauseTrace) >= 1_000) {
                lastPauseReason = reason
                lastPauseTrace = now
                trace("pause", "reason=$reason " + stateFields(output?.snapshot()))
            }
        }
        output?.apply(playing = false)
    }

    private fun tickTrace(gate: String, snapshot: SyncRendererState? = null) {
        if (!BuildConfig.DEBUG) return
        val now = monotonic()
        if (gate == lastTickGate && lastTickTrace != null && now - requireNotNull(lastTickTrace) in 0..999) return
        lastTickGate = gate
        lastTickTrace = now
        trace("snapshot", "gate=$gate " + stateFields(snapshot))
    }

    /** Ordinals retain equality without emitting private ids, token text, participant names or locations. */
    private fun traceId(value: String?): Long {
        if (!BuildConfig.DEBUG || value == null) return 0
        traceIds[value]?.let { return it }
        if (traceIds.size >= 256) traceIds.remove(traceIds.keys.first())
        return (++traceOrdinal).also { traceIds[value] = it }
    }
    private fun traceToken(value: Any?): Long {
        if (!BuildConfig.DEBUG || value == null) return 0
        traceTokens[value]?.let { return it }
        if (traceTokens.size >= 128) traceTokens.clear()
        return (++traceOrdinal).also { traceTokens[value] = it }
    }
    private fun eventKind(event: SyncEvent): String = when (event) {
        is SyncEvent.Joined -> "joined"
        is SyncEvent.Queue -> "queue"
        is SyncEvent.State -> "state"
        is SyncEvent.Participant -> "participant"
        is SyncEvent.Left -> "left"
        is SyncEvent.Denied -> "denied"
        is SyncEvent.Command -> "command"
        is SyncEvent.Invalid -> "invalid"
    }
    private fun commandFields(command: SyncCommand): String =
        "commandId=${traceToken(command)} command=${command.command} whenMs=${command.whenMs} " +
            "positionTicks=${command.positionTicks ?: -1} commandEntry=${traceId(command.playlistItemId)} " +
            "emittedTicks=${command.emittedAtTicks}"

    private fun stateFields(snapshot: SyncRendererState? = null): String {
        if (!BuildConfig.DEBUG) return ""
        val r = snapshot ?: renderer?.snapshot()
        val n = r?.native
        val a = anchor
        val elapsed = monotonic()
        val now = a?.serverNowTicks(wall(), elapsed)
        val current = membership.queue?.current
        return "generation=${membership.generation} operationGeneration=$sequence group=${traceId(membership.groupId)} " +
            "phase=${membership.phase} participating=$participating active=${membership.active} connected=$connected " +
            "snapshot=${membership.hasSnapshot} " +
            "entry=${traceId(current?.playlistItemId)} item=${traceId(current?.itemId)} openingEntry=${traceId(openingEntry)} " +
            "requestedOpen=${traceId(requestedOpen)} stoppedEntry=${traceId(stoppedEntry)} token=${traceToken(rendererToken)} " +
            "pendingSource=${pendingSource != null} awaitingSource=${awaitingSource != null} renderer=${r != null} " +
            "rendererItem=${traceId(r?.itemId)} rendererEntry=${traceId(r?.playlistItemId)} " +
            "ready=${r?.ready} buffering=${r?.buffering} playing=${r?.playing} seekable=${r?.seekable} " +
            "positionTicks=${r?.positionTicks ?: -1} durationTicks=${r?.durationTicks ?: -1} " +
            "anchorGeneration=$anchorGeneration anchor=${a != null} clockUsable=${now != null} " +
            "anchorAgeMs=${a?.let { elapsed - it.localMonotonicMillis } ?: -1} " +
            "wallDeltaMs=${a?.let { (wall() - it.localWallMillis) - (elapsed - it.localMonotonicMillis) } ?: 0} " +
            "uncertaintyTicks=${a?.uncertaintyTicks ?: -1} serverNowTicks=${now ?: -1} " +
            "tickerActive=${ticker?.isActive == true} clockActive=${clockJob?.isActive == true} " +
            "scheduledActive=${scheduled?.isActive == true} " +
            "pending=${timeline != null && !timelineApplied} intendedPlaying=$timelinePlaying applied=$timelineApplied " +
            "timelineId=${traceToken(timeline)} whenMs=${timeline?.whenMs ?: -1} lastSeekMs=${drift.lastSeekMonotonicMillis ?: -1} " +
            "native=${n != null} nativeGeneration=${n?.generation ?: -1} nativeState=${n?.playbackState ?: -1} " +
            "nativePwr=${n?.playWhenReady} nativeSuppression=${n?.suppressionReason ?: -1} nativeLoading=${n?.isLoading} " +
            "nativePositionMs=${n?.positionMs ?: -1} nativeBufferedPositionMs=${n?.bufferedPositionMs ?: -1} " +
            "nativeBufferedDurationMs=${n?.bufferedDurationMs ?: -1}"
    }

    /** At most 64 transition records per elapsed second; loss is explicit on the next emitted record. */
    private fun trace(event: String, fields: String = "") {
        if (!BuildConfig.DEBUG) return
        val now = monotonic()
        if (now - traceWindow !in 0..999) {
            traceWindow = now
            traceEmitted = 0
        }
        if (traceEmitted >= 64) {
            traceSuppressed++
            return
        }
        traceEmitted++
        diagnosticSink("trace-$event elapsedMs=$now traceSeq=${++traceSequence} suppressed=$traceSuppressed $fields")
        traceSuppressed = 0
    }

    /** Debug evidence counts transitions and corrections without viewer names, ids or server locations. */
    private fun diagnostic(event: String, fields: String) {
        if (BuildConfig.DEBUG) {
            diagnosticSink("$event uptimeMs=${monotonic()} $fields")
        }
    }
    private fun note(text: String) {
        mutable.value = mutable.value.copy(status = text)
    }
    private fun publish() {
        trace("published", stateFields())
        val q = membership.queue
        mutable.value = mutable.value.copy(
            phase = membership.phase, groupId = membership.groupId, groupName = membership.group?.name,
            participants = membership.group?.participants.orEmpty(), ignoreWait = ignoreWait,
            repeat = q?.repeatMode ?: "RepeatNone", shuffle = q?.shuffleMode ?: "Sorted",
            queue = q?.entries.orEmpty().map {
                SyncPlayQueueRow(
                    it.playlistItemId,
                    "Item ${q?.entries?.indexOf(it)?.plus(1)}",
                    it == q?.current
                )
            },
            status = membership.notice ?: mutable.value.status
        )
    }
}
