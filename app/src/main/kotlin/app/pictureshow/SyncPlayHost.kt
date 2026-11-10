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
import app.pictureshow.core.sync.syncSelect
import app.pictureshow.core.sync.syncShuffle
import app.pictureshow.core.sync.syncTarget
import app.pictureshow.core.sync.syncTransport
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

internal data class SyncRendererState(
    val itemId: String,
    val positionTicks: Long,
    val durationTicks: Long,
    val ready: Boolean,
    val buffering: Boolean,
    val playing: Boolean,
    val seekable: Boolean,
    val rateSupported: Boolean = false
)

internal interface SyncRenderer {
    fun snapshot(): SyncRendererState

    /** Bypasses outgoing transport interception: applying a server command never echoes it. */
    fun apply(positionTicks: Long? = null, playing: Boolean? = null, rate: Double = 1.0)
}

internal data class SyncOpen(val generation: Long, val entryId: String, val itemId: String, val ticks: Long)

/** One signed-in viewing scope. All methods and socket callbacks run on the main dispatcher. */
internal class SyncPlayHost(
    private val scope: CoroutineScope,
    private val gateway: SyncPlayGateway,
    private val wall: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = android.os.SystemClock::elapsedRealtime
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
    private var calibration = SyncClockCalibration()
    private var anchor: SyncClockAnchor? = null
    private var clockJob: Job? = null
    private var ticker: Job? = null
    private var scheduled: Job? = null
    private var timeline: SyncCommand? = null
    private var drift = SyncDriftState()
    private var readyEntry: String? = null
    private var wasReady: Boolean? = null
    private var requestedOpen: String? = null
    private var createQueue = false
    private var ignoreWait = false
    private var waitRequest: Job? = null
    private var operation: Job? = null
    private var sequence = 0L

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
        resetClock()
        mutable.value = SyncPlayPresentation(server = usable?.server?.name.orEmpty(), viewer = usable?.user?.name.orEmpty())
    }

    fun show() {
        panelOpen = true
        refresh()
    }
    fun refresh() {
        val target = account ?: return
        val version = sequence
        mutable.value = mutable.value.copy(busy = true)
        scope.launch {
            val policy = gateway.access(target)
            if (!current(target, version)) return@launch
            mutable.value = mutable.value.copy(policy = (policy as? ClientResult.Success)?.value ?: "None")
            mutable.value = mutable.value.copy(busy = false)
            if (policy is ClientResult.Failure) {
                note(policy.error.message)
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

                is ClientResult.Failure -> if (current(target, version)) note(groups.error.message)
            }
        }
    }

    fun socket(value: Boolean) {
        if (connected == value) return
        connected = value
        val waiting = joiningSocket
        if (waiting != null) {
            if (value) waiting.complete(Unit)
            return
        }
        if (!value && participating) {
            scheduled?.cancel()
            renderer?.apply(playing = false)
            membership = recoverSync(membership, "Connection lost. Playback is paused; rejoin to synchronize again.")
            resetClock()
            publish()
        } else if (value && participating) {
            // Rejoin obtains membership and fresh queue events; stale local state is never replayed.
            val group = membership.groupId
            if (group != null) join(group) else leave()
        }
    }

    fun receive(text: String, generation: Long = membership.generation) {
        val event = parseSyncMessage(text) ?: return
        val before = membership
        val after = reduceSyncEvent(before, event, generation)
        if (after === before || after == before) return
        membership = after
        if (after.generation != before.generation || !after.active) {
            renderer?.apply(playing = false)
            resetClock()
            publish()
            return
        }
        if (after.phase == "Reconnecting") {
            scheduled?.cancel()
            renderer?.apply(playing = false)
            timeline = null
            publish()
            // A fresh deliberate rejoin is visible; avoid loops on unknown future event types.
            note(after.notice ?: "Synchronization needs a fresh group snapshot. Leave and join again.")
            return
        }
        if (event is SyncEvent.Joined && createQueue) {
            createQueue = false
            renderer?.snapshot()?.takeIf { it.durationTicks > 0 }?.let {
                send(syncNewQueue(listOf(it.itemId), startPositionTicks = it.positionTicks))
            }
        }
        val entry = after.queue?.current
        if (event is SyncEvent.Queue && entry?.playlistItemId != before.queue?.current?.playlistItemId) {
            scheduled?.cancel()
            renderer?.apply(playing = false)
            timeline = null
            drift = stopSyncDrift()
            readyEntry = null
            wasReady = null
            requestedOpen = null
        }
        val entryChanged = entry?.playlistItemId != before.queue?.current?.playlistItemId
        val needsOpen = entry != null && (renderer?.snapshot()?.itemId != entry.itemId || entryChanged)
        if (entry != null && needsOpen && requestedOpen != entry.playlistItemId) {
            renderer?.apply(playing = false)
            requestedOpen = entry.playlistItemId
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
            renderer?.apply(playing = false)
            membership = membership.copy(phase = "Error", notice = "Library access denied or media unavailable. Leave to recover.")
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
        val policy = state.value.policy
        if (policy == "None" || (creating && policy != "CreateAndJoinGroups")) {
            note("This viewer is not allowed to ${if (creating) "create" else "join"} a group.")
            return
        }
        sequence++
        operation?.cancel()
        membership = beginSyncJoin(membership, group)
        createQueue = creating
        renderer?.apply(playing = false)
        resetClock()
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
            val status = gateway.send(target, request)
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
                renderer?.apply(playing = false)
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
        renderer?.apply(playing = false)
        resetClock()
        createQueue = false
        ignoreWait = false
        publish()
        note("Left Watch together. This device is paused; the group can keep playing.")
        val version = sequence
        if (target != null && hadMembership) {
            scope.launch {
                val status = gateway.send(target, SyncRequest("Leave"))
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
        if (participating) leave()
    }
    fun bindRenderer(token: Any, next: SyncRenderer) {
        rendererToken = token
        renderer = next
        if (participating) {
            next.apply(playing = false)
            readyEntry = null
            wasReady = null
        }
    }
    fun clearRenderer(token: Any) {
        if (rendererToken !== token) return
        renderer = null
        rendererToken = null
        readyEntry = null
        wasReady = null
    }

    /** True consumes every timeline intent while grouped, even during reconnect or preparation. */
    fun transport(command: String, positionMs: Long? = null): Boolean {
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
        val target = account ?: return null
        if (!connected || membership.group == null || membership.phase in setOf("Error", "Reconnecting")) {
            note("Group controls are unavailable until membership and synchronization are refreshed.")
            return null
        }
        val version = sequence
        return scope.launch {
            val status = gateway.send(target, request)
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
        var next = SyncClockCalibration()
        repeat(5) {
            val t0 = wall()
            val m0 = monotonic()
            val result = gateway.time(target)
            val t3 = wall()
            if (!current(target, version)) return
            if (kotlin.math.abs((t3 - t0) - (monotonic() - m0)) <= 100) {
                (result as? ClientResult.Success)?.value?.let {
                    next = next.add(SyncClockSample(t0, it.receptionMs, it.transmissionMs, t3))
                }
            }
        }
        calibration = next
        anchor = next.anchor(wall(), monotonic())
        next.best?.let { send(syncPing(it.networkRttMillis)) }
        if (anchor == null) {
            renderer?.apply(playing = false)
            note("Synchronization unavailable: no valid server clock samples. Leave or try joining again.")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (participating && isActive) {
                if (anchor?.needsRefresh(monotonic()) == true && clockJob?.isActive != true) {
                    clockJob = scope.launch { calibrate() }
                }
                tick()
                delay(250)
            }
        }
    }

    internal fun tick() {
        val output = renderer ?: return
        val r = output.snapshot()
        val current = membership.queue?.current ?: return
        if (membership.phase == "Idle") return
        if (!membership.hasSnapshot || r.itemId != current.itemId) return
        val now = anchor?.serverNowTicks(wall(), monotonic())
        if (now == null) {
            output.apply(playing = false)
            note("Synchronization unavailable: refresh the server clock by joining again.")
            return
        }
        val ready = r.ready && !r.buffering && r.seekable && r.durationTicks > 0
        if (readyEntry != current.playlistItemId || wasReady != ready) {
            readyEntry = current.playlistItemId
            wasReady = ready
            if (!ready) output.apply(playing = false)
            send(syncReadiness(ready, now / 10_000, r.positionTicks, r.playing, current.playlistItemId))
        }
        val command = timeline ?: return
        if (!ready || command.playlistItemId != current.playlistItemId || membership.phase !in setOf("Playing", "Paused")) return
        val playing = command.command == "Unpause" || (command.command == "Seek" && membership.phase == "Playing")
        val target = syncTarget(command.positionTicks ?: r.positionTicks, command.whenMs, now, playing, r.durationTicks) ?: return
        if (target.delayMillis > 0) return
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
            output.apply(decision.targetTicks, playing, decision.rate)
        } else {
            output.apply(playing = playing, rate = decision.rate)
        }
        val errorMs = (r.positionTicks - target.positionTicks) / 10_000
        val uncertainty = (anchor?.uncertaintyTicks ?: Long.MAX_VALUE) / 10_000
        note(
            "${if (uncertainty <= 50) "Renderer timing — not yet qualified" else "Not qualified"} · drift $errorMs ms · clock uncertainty ±$uncertainty ms"
        )
    }

    private fun schedule(command: SyncCommand) {
        scheduled?.cancel()
        scheduled = scope.launch {
            val generation = membership.generation
            val now = anchor?.serverNowTicks(wall(), monotonic()) ?: run {
                // Join's clock exchange may still be in flight. Retain command, but never output on an uncalibrated clock.
                timeline = command
                return@launch
            }
            val duration = renderer?.snapshot()?.durationTicks ?: return@launch
            val target = syncTarget(
                command.positionTicks ?: renderer?.snapshot()?.positionTicks ?: 0,
                command.whenMs,
                now,
                command.command == "Unpause",
                duration
            ) ?: return@launch
            if (target.delayMillis > 0) delay(target.delayMillis)
            if (generation != membership.generation || command.playlistItemId != membership.queue?.current?.playlistItemId) return@launch
            timeline = command
            val r = renderer?.snapshot() ?: return@launch
            if (!r.ready || r.buffering || !r.seekable || r.itemId != membership.queue?.current?.itemId) return@launch
            val at = anchor?.serverNowTicks(wall(), monotonic()) ?: return@launch
            val playing = command.command == "Unpause" || (command.command == "Seek" && membership.phase == "Playing")
            val execution =
                syncTarget(command.positionTicks ?: r.positionTicks, command.whenMs, at, playing, r.durationTicks) ?: return@launch
            renderer?.apply(execution.positionTicks, playing)
            drift = stopSyncDrift()
        }
    }

    private fun resetClock() {
        waitRequest = null
        ticker?.cancel()
        ticker = null
        clockJob?.cancel()
        clockJob = null
        scheduled?.cancel()
        scheduled = null
        anchor = null
        calibration = SyncClockCalibration()
        timeline = null
        drift = stopSyncDrift()
        readyEntry = null
        wasReady = null
        requestedOpen = null
    }
    private fun current(target: Account, version: Long) =
        version == sequence && target.key == account?.key && target.accessToken == account?.accessToken
    private fun note(text: String) {
        mutable.value = mutable.value.copy(status = text)
    }
    private fun publish() {
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
