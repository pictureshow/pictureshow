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
import app.pictureshow.core.sync.SyncCompletionAction
import app.pictureshow.core.sync.SyncDepartureCause
import app.pictureshow.core.sync.SyncDepartureEvent
import app.pictureshow.core.sync.SyncDepartureState
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
import app.pictureshow.core.sync.reduceSyncDeparture
import app.pictureshow.core.sync.reduceSyncEvent
import app.pictureshow.core.sync.stopSyncDrift
import app.pictureshow.core.sync.syncClockRefreshDue
import app.pictureshow.core.sync.syncCompletionAction
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

internal interface SyncPlayGateway {
    suspend fun groups(account: Account): ClientResult<List<SyncGroup>>
    suspend fun title(account: Account, itemId: String): ClientResult<String?> = ClientResult.Success(null)
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
    val bufferedDurationMs: Long,
    val playbackRate: Float = 1f,
    val isPlaying: Boolean? = null
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
    val native: SyncNativeRendererState? = null,
    /** Native play intent, including while buffering or suppressed; never inferred from a future server command. */
    val playWhenReady: Boolean? = null
) {
    // Simple renderers can omit the separate native intent; both Media3 adapters always provide it in release builds too.
    val unpaused: Boolean get() = playWhenReady ?: native?.playWhenReady ?: playing
}

internal interface SyncRenderer {
    /** A stable bridge can outlive the native decoder it currently controls. */
    val sourceIdentity: Any get() = this
    val positionApplicationPending: Boolean get() = false
    fun snapshot(): SyncRendererState

    /** Bypasses outgoing transport interception; returns whether a position seek was actually issued. */
    fun apply(positionTicks: Long? = null, playing: Boolean? = null, rate: Double = 1.0): Boolean

    /** Autonomous correction may become unnecessary between the sampled decision and native execution. */
    fun correct(positionTicks: Long, playing: Boolean, rate: Double, sampledAtMillis: Long): Boolean = apply(positionTicks, playing, rate)
}

internal data class SyncLocalSelection(val itemId: String, val startTicks: Long)

internal data class SyncOpen(val generation: Long, val entryId: String, val itemId: String, val ticks: Long)

/** Identity captured by the rendered consent, independent of membership/request generations. */
internal data class SyncExternalRouteConsent(val scope: Long, val id: Long)

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
    internal var membership by mutableStateOf(SyncMembership())
        private set
    private var titleScope: app.pictureshow.core.sync.SyncTitleScope? = null
    private var titleQueue: List<Pair<String, String>> = emptyList()
    private val titles = mutableMapOf<String, String?>()
    private val titleRequests = mutableSetOf<String>()
    private val titleLookupSlots = Semaphore(4)
    private var noticeRevision = 0L
    private var titleRevision = 0L
    private var waitReason: String? = null
    private var leavePending = false

    // Local Joining intent alone cannot have changed server membership. Preserve this through recovery/rejoin.
    private var serverMembershipPossible = false
    val participating: Boolean get() = membership.active || membership.groupId != null || leavePending
    private data class PendingExternalRoute(val consent: SyncExternalRouteConsent, val route: () -> Unit)
    private var pendingRoute by mutableStateOf<PendingExternalRoute?>(null)
    private var externalRouteId = 0L
    val pendingExternalRoute: (() -> Unit)? get() = pendingRoute?.route
    val externalRouteConsent: SyncExternalRouteConsent? get() = pendingRoute?.consent

    fun confirmExternalRoute(then: () -> Unit): Boolean {
        if (!participating) return false
        val consent = SyncExternalRouteConsent(departureState.scope, ++externalRouteId)
        pendingRoute = PendingExternalRoute(consent, then)
        return true
    }
    fun cancelExternalRoute() {
        val consent = externalRouteConsent ?: return
        cancelExternalRoute(consent.scope, consent.id)
    }
    fun cancelExternalRoute(scope: Long, id: Long) {
        val pending = pendingRoute ?: return
        if (scope != departureState.scope || pending.consent != SyncExternalRouteConsent(scope, id)) return
        pendingRoute = null
    }
    fun leaveForExternalRoute() {
        val consent = externalRouteConsent ?: return
        leaveForExternalRoute(consent.scope, consent.id)
    }
    fun leaveForExternalRoute(scope: Long, id: Long) {
        val pending = pendingRoute ?: return
        if (scope != departureState.scope || pending.consent != SyncExternalRouteConsent(scope, id)) return
        // Consume before Leave so repeated confirmation cannot send or route twice.
        pendingRoute = null
        leaveConfirmed({
            // HTTP confirmation may outlive its account or be superseded by another consent.
            if (departureState.scope == scope && externalRouteId == id) pending.route()
        }, SyncDepartureCause.EXTERNAL_ROUTE)
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
    private var lastClockAttemptFinishedMillis: Long? = null
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
    private data class PreparedUnpause(val binding: AppliedUnpause, val clockGeneration: Long)
    private var preparedUnpause: PreparedUnpause? = null
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
        pendingRoute = null
        val before = account
        if (participating && before != null) leave()
        account = usable
        departure(SyncDepartureEvent.ScopeChanged(departureState.scope + 1))
        sequence++
        operation?.cancel()
        membership = SyncMembership(generation = membership.generation + 1)
        leavePending = false
        serverMembershipPossible = false
        resetClock("account")
        trace("membership", stateFields())
        mutable.value = SyncPlayPresentation(server = usable?.server?.name.orEmpty(), viewer = usable?.user?.name.orEmpty())
    }

    internal var departureState = SyncDepartureState()
        private set
    internal var chooserSession = 0L
        private set

    private fun departure(event: SyncDepartureEvent) {
        departureState = reduceSyncDeparture(departureState, event)
        mutable.value = mutable.value.copy(departure = departureState.pending)
    }

    fun leave(cause: SyncDepartureCause) = leaveConfirmed(null, cause)

    /** Layout acknowledgement is accepted only for the currently selected, visible chooser banner. */
    fun departurePresented(scope: Long, id: Long, chooserSession: Long) {
        if (!panelOpen ||
            chooserSession != this.chooserSession ||
            effectiveSyncNotice(mutable.value)?.kind != SyncNoticeKind.DEPARTURE
        ) {
            return
        }
        departure(SyncDepartureEvent.Presented(scope, id, chooserSession))
    }

    /** Configuration disposal is not a dismissal; callers capture this chooser's identity. */
    fun dismissChooser(scope: Long, id: Long?, chooserSession: Long) {
        if (scope != departureState.scope || chooserSession != this.chooserSession || id != departureState.pending?.id) return
        if (panelOpen && id != null && effectiveSyncNotice(mutable.value)?.kind == SyncNoticeKind.DEPARTURE) {
            departure(SyncDepartureEvent.Dismissed(scope, id, chooserSession))
        }
        panelOpen = false
    }

    fun show() {
        if (!panelOpen) chooserSession++
        panelOpen = true
        refresh()
    }
    fun refresh() {
        val target = account ?: return
        val version = sequence
        mutable.value =
            mutable.value.copy(
                busy = true,
                policy = "None",
                policyKnown = false,
                policyFailed = false,
                groups = emptyList(),
                status = "",
                notice = null
            )
        scope.launch {
            when (val policy = gateway.access(target)) {
                is ClientResult.Failure -> {
                    if (!current(target, version)) return@launch
                    mutable.value = mutable.value.copy(busy = false, policyFailed = true)
                    note(policy.error.message, SyncNoticeKind.ACCESS_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.REFRESH)
                    return@launch
                }

                is ClientResult.Success -> {
                    if (!current(target, version)) return@launch
                    mutable.value = mutable.value.copy(policy = policy.value, policyKnown = true, status = "", notice = null)
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
                    note(groups.error.message, SyncNoticeKind.GROUPS_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.REFRESH)
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
        if (event is SyncEvent.Joined) departure(SyncDepartureEvent.MembershipStarted(departureState.scope))
        if (event is SyncEvent.Left) {
            departure(
                SyncDepartureEvent.Recorded(departureState.scope, before.group?.name, SyncDepartureCause.MEMBERSHIP_LOST)
            )
        }
        if (event is SyncEvent.Joined || after.generation != before.generation || !after.active) waitReason = null
        if (event is SyncEvent.State) waitReason = event.reason
        if (after.phase !in setOf("Error", "Reconnecting") && event is SyncEvent.State) {
            mutable.value = mutable.value.copy(notice = null)
        }
        if (event is SyncEvent.Denied) {
            val kind = when (event.reason) {
                "LibraryAccessDenied" -> SyncNoticeKind.LIBRARY_DENIED
                "GroupDoesNotExist", "NotInGroup" -> SyncNoticeKind.GROUP_UNAVAILABLE
                else -> SyncNoticeKind.ACCESS_DENIED
            }
            note(after.notice.orEmpty(), kind, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
        }
        if (event is SyncEvent.Joined) serverMembershipPossible = true
        if (event is SyncEvent.Left) serverMembershipPossible = false
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
            pendingSource = null
            awaitingSource = null
            scheduled?.cancel()
            pause("membership-reconnecting")
            timeline = null
            appliedUnpause = null
            publish()
            // A fresh deliberate rejoin is visible; avoid loops on unknown future event types.
            note(
                after.notice ?: "Synchronization needs a fresh group snapshot. Leave and join again.",
                SyncNoticeKind.REJOIN,
                SyncNoticeTone.ERROR,
                SyncNoticeAction.REJOIN
            )
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
            note("Group queue updated. Waiting for renderer readiness.", SyncNoticeKind.PREPARING)
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
            note(requireNotNull(membership.notice), SyncNoticeKind.LIBRARY_DENIED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
            publish()
        }
        return ok
    }

    fun join(group: String) = begin(syncJoin(group), group, false)
    fun create(name: String) = begin(syncCreate(name), null, true)
    private fun begin(request: SyncRequest, group: String?, creating: Boolean) {
        val target = account ?: return
        if (!connected && membershipSocket == null) {
            note(
                "Waiting for the server connection. Try again when connected.",
                SyncNoticeKind.CONNECTION,
                SyncNoticeTone.WARNING,
                SyncNoticeAction.REFRESH
            )
            return
        }
        val local = renderer?.snapshot()
        if (local != null && (!local.seekable || local.durationTicks <= 0)) {
            note(
                "Watch together is unavailable for live, infinite or offline sources. Open an online movie, episode or song first.",
                SyncNoticeKind.SOURCE_UNAVAILABLE,
                SyncNoticeTone.WARNING
            )
            return
        }
        val presentation = state.value
        if (!presentation.policyKnown) {
            note(
                "Refresh to confirm watch-together access before joining a group.",
                SyncNoticeKind.CHECK_ACCESS,
                SyncNoticeTone.WARNING,
                SyncNoticeAction.REFRESH
            )
            return
        }
        val policy = presentation.policy
        if (!presentation.accessAllowed || (creating && policy != "CreateAndJoinGroups")) {
            note(
                "This viewer is not allowed to ${if (creating) "create" else "join"} a group.",
                SyncNoticeKind.ACCESS_DENIED,
                SyncNoticeTone.ERROR,
                SyncNoticeAction.REFRESH
            )
            return
        }
        sequence++
        operation?.cancel()
        mutable.value = mutable.value.copy(notice = null)
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
                    note(
                        "Could not refresh the group connection. Leave and try joining again.",
                        SyncNoticeKind.REJOIN,
                        SyncNoticeTone.ERROR,
                        SyncNoticeAction.REJOIN
                    )
                    return@launch
                }
            }
            val earlierMembershipPossible = serverMembershipPossible
            serverMembershipPossible = true
            val status = tracedSend(target, request)
            if (!current(target, version)) return@launch
            if (status !in 200..299) {
                membership = if (status in setOf(400, 401, 403, 404)) {
                    if (!earlierMembershipPossible) serverMembershipPossible = false
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
                    },
                    SyncNoticeKind.REQUEST_FAILED,
                    SyncNoticeTone.ERROR,
                    SyncNoticeAction.REFRESH
                )
                return@launch
            }
            calibrate()
            if (!current(target, version)) return@launch
            startTicker()
            delay(10_000)
            if (membership.group == null || (membership.queue == null && membership.group?.state != "Idle")) {
                pause("join-snapshot-timeout")
                note(
                    "The server has not supplied a complete group snapshot. Leave and join again.",
                    SyncNoticeKind.SNAPSHOT,
                    SyncNoticeTone.ERROR,
                    SyncNoticeAction.REJOIN
                )
            }
        }
    }

    /** Leave invalidates events immediately, retains local paused resume and sends no group Stop. */
    fun leave() = leave(SyncDepartureCause.MANUAL_LEAVE)
    private fun leaveConfirmed(after: (() -> Unit)?, cause: SyncDepartureCause) {
        if (membership.active || membership.groupId != null) {
            departure(SyncDepartureEvent.Recorded(departureState.scope, membership.group?.name, cause))
        }
        val target = account
        val hadMembership = serverMembershipPossible || leavePending
        serverMembershipPossible = false
        sequence++
        operation?.cancel()
        mutable.value = mutable.value.copy(notice = null)
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
        note(
            if (departureState.pending?.cause == SyncDepartureCause.MANUAL_LEAVE) {
                "Left Watch together. This device is paused; the group can keep playing."
            } else {
                "Watch-together membership ended: ${departureState.pending?.cause ?: cause}."
            },
            SyncNoticeKind.LEFT
        )
        val version = sequence
        if (target != null && hadMembership) {
            scope.launch {
                val status = tracedSend(target, SyncRequest("Leave"))
                if (!current(target, version)) return@launch
                if (status in 200..299 && !participating) after?.invoke()
                if (status !in 200..299 && !participating) {
                    leavePending = true
                    mutable.value = mutable.value.copy(phase = "Reconnecting")
                    note(
                        "This device is paused. The server has not confirmed Leave; reconnect and leave again.",
                        SyncNoticeKind.LEAVE_UNCONFIRMED,
                        SyncNoticeTone.ERROR,
                        SyncNoticeAction.LEAVE
                    )
                }
            }
        } else {
            // Cancelling an unsent intent needs no server mutation or network confirmation.
            after?.invoke()
        }
    }

    fun background() {
        trace("lifecycle", "source=background participating=$participating")
        if (participating) leave(SyncDepartureCause.MEMBERSHIP_LOST)
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
        if (rendererToken !== token || renderer !== next) {
            appliedUnpause = null
            preparedUnpause = null
        }
        rendererToken = token
        renderer = next
        if (opening != null && opening.entryId == openingEntry) openingEntry = null
        awaitingSource = null
        pendingSource = localSelection?.takeIf {
            participating && membership.canSelectSource && it.itemId == next.snapshot().itemId
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
        preparedUnpause = null
        rendererToken = null
        pendingSource = null
        awaitingSource = null
        readiness = SyncReadinessState()
    }

    /** Actual native seek callbacks also cover deferred seeks and sub-tick buffer cycles. */
    fun rendererPositionApplied(token: Any, output: SyncRenderer, source: Any, generation: Long, entryId: String?) {
        if (entryId == null || !participating || rendererToken !== token || renderer !== output || output.sourceIdentity !== source) return
        val current = membership.queue?.current ?: return
        val value = output.snapshot()
        if (!app.pictureshow.core.sync.syncOpenMatches(
                generation,
                membership.generation,
                entryId,
                current.playlistItemId,
                value.itemId,
                current.itemId
            )
        ) {
            return
        }
        if (!membership.hasSnapshot || value.itemId != current.itemId || openingEntry != null) return
        preparedUnpause = null
        readiness = readiness.afterPositionApplied(true)
        drift = syncTransportApplied(drift, monotonic(), true)
        trace("position-applied", "source=native-callback " + stateFields(value))
    }

    /** A retired native completion cannot finish or advance a replacement viewing, even for the same media. */
    fun rendererEnded(
        token: Any,
        output: SyncRenderer,
        source: Any,
        generation: Long,
        entryId: String?,
        complete: () -> Boolean = { true }
    ): Boolean {
        if (!participating) return false
        if (rendererToken !== token ||
            renderer !== output ||
            output.sourceIdentity !== source ||
            generation != membership.generation ||
            entryId == null ||
            entryId != membership.queue?.current?.playlistItemId
        ) {
            return true
        }
        return completeNaturalEnd(complete)
    }

    private fun completeNaturalEnd(complete: () -> Boolean): Boolean {
        val queue = membership.queue
        val entry = queue?.current
        val value = renderer?.snapshot()
        val matches = entry != null &&
            value != null &&
            value.itemId == entry.itemId &&
            (value.playlistItemId?.let { it == entry.playlistItemId } ?: (openingEntry == null))
        when (syncCompletionAction(queue, membership.hasSnapshot, matches, value?.unpaused == true)) {
            SyncCompletionAction.HOLD -> Unit
            SyncCompletionAction.NEXT -> send(syncTransport("NextItem", playlistItemId = entry!!.playlistItemId))
            SyncCompletionAction.REPEAT -> send(syncSelect(entry!!.playlistItemId))
            SyncCompletionAction.FINISH -> {
                // Validate and queue this viewing's normal completion before membership/navigation teardown.
                if (!complete()) return true
                leave(SyncDepartureCause.PLAYBACK_ENDED)
                return false
            }
        }
        return true
    }

    /** True consumes grouped intent; terminal natural completion returns false for the caller's local cleanup. */
    fun transport(command: String, positionMs: Long? = null): Boolean {
        val name = command.takeIf { it in setOf("play", "pause", "stop", "seek", "next", "previous", "speed", "ended") } ?: "unknown"
        trace(
            "transport",
            "command=$name positionMs=${positionMs ?: -1} consumed=$participating"
        )
        if (!participating) return false
        val queue = membership.queue
        val entry = queue?.current
        when (command) {
            "ended" -> return completeNaturalEnd { true }

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

            "speed" -> note(
                "Watch together uses the shared 1× timeline; synchronization adjusts this device when needed.",
                SyncNoticeKind.SPEED
            )
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
            note(
                "Group controls are unavailable until membership and synchronization are refreshed.",
                SyncNoticeKind.REJOIN,
                SyncNoticeTone.ERROR,
                SyncNoticeAction.REJOIN
            )
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
                    },
                    SyncNoticeKind.REQUEST_FAILED,
                    SyncNoticeTone.ERROR,
                    SyncNoticeAction.REJOIN
                )
            }
        }
    }

    private suspend fun calibrate() {
        val target = account ?: return
        val version = sequence
        val generation = membership.generation
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
            if (anchor != null && mutable.value.notice?.kind == SyncNoticeKind.CLOCK) mutable.value = mutable.value.copy(notice = null)
            if (anchor == null) {
                appliedUnpause = null
                pause("clock-calibration")
                note(
                    "Synchronization unavailable: no valid server clock samples. Leave or try joining again.",
                    SyncNoticeKind.CLOCK,
                    SyncNoticeTone.ERROR,
                    SyncNoticeAction.REJOIN
                )
            }
        } finally {
            if (completed && current(target, version) && generation == membership.generation && connected && participating) {
                lastClockAttemptFinishedMillis = monotonic()
            }
            if (!completed) trace("clock-unfinished", "attempt=$attempt current=${current(target, version)}")
        }
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            trace("ticker-start", stateFields())
            try {
                while (participating && isActive) {
                    if (connected &&
                        (anchor != null || membership.hasSnapshot) &&
                        syncClockRefreshDue(anchor, lastClockAttemptFinishedMillis, monotonic()) &&
                        clockJob?.isActive != true
                    ) {
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
        try {
            tickRenderer()
        } finally {
            publish()
        }
    }

    private fun tickRenderer() {
        val output = renderer ?: run {
            tickTrace("no-renderer")
            return
        }
        val r = output.snapshot()
        if (participating && r.ready && !r.seekable) {
            appliedUnpause = null
            pause("source-ineligible", output)
            note(
                "Watch together is unavailable for this source. Leave the group to play it separately.",
                SyncNoticeKind.SOURCE_UNAVAILABLE,
                SyncNoticeTone.ERROR,
                SyncNoticeAction.LEAVE
            )
        }
        pendingSource?.let { pending ->
            if (pending.token !== rendererToken ||
                pending.generation != membership.generation ||
                !participating ||
                !membership.canSelectSource
            ) {
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
                    note("Waiting for the server to update the group queue.", SyncNoticeKind.QUEUE_PENDING)
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
        val targetSampleMillis = monotonic()
        val now = anchor?.serverNowTicks(wall(), targetSampleMillis)
        if (now == null) {
            appliedUnpause = null
            pause("clock-unavailable", output)
            note(
                "Synchronization unavailable: refresh the server clock by joining again.",
                SyncNoticeKind.CLOCK,
                SyncNoticeTone.ERROR,
                SyncNoticeAction.REJOIN
            )
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
                "ready=$ready whenMs=${now / 10_000} positionTicks=${r.positionTicks} playing=${r.playing} unpaused=${r.unpaused} " +
                    stateFields(r)
            )
            send(syncReadiness(ready, now / 10_000, r.positionTicks, r.unpaused, current.playlistItemId))
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
        val previousDrift = drift
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
            val changed = output.correct(decision.targetTicks, playing, decision.rate, targetSampleMillis)
            // A decision is not an actual native seek. Skipped execution preserves
            // the last real seek's cooldown rather than starting a new one.
            drift = syncTransportApplied(previousDrift, monotonic(), changed)
            readiness = readiness.afterPositionApplied(changed)
            trace("apply-after", "source=correction positionChanged=$changed " + stateFields())
        } else {
            output.apply(playing = playing, rate = decision.rate)
        }
        val errorMs = (r.positionTicks - target.positionTicks) / 10_000
        val uncertainty = (anchor?.uncertaintyTicks ?: Long.MAX_VALUE) / 10_000
        diagnostic("ui-estimate", "driftMs=$errorMs uncertaintyMs=$uncertainty")
    }

    private fun rendererOwnsEntry(snapshot: SyncRendererState, entryId: String): Boolean {
        if (openingEntry == null) return true
        // Persistent music bridges expose Media3's actual current queue slot; matching ItemId alone is insufficient.
        if (!app.pictureshow.core.sync.syncRendererOwnsEntry(openingEntry, snapshot.playlistItemId, entryId)) return false
        trace("entry-ack", "entry=${traceId(entryId)} " + stateFields(snapshot))
        openingEntry = null
        return true
    }

    private fun sameBoundUnpause(binding: AppliedUnpause?, command: SyncCommand): Boolean = binding?.let {
        it.token === rendererToken &&
            it.renderer === renderer &&
            it.sourceIdentity === renderer?.sourceIdentity &&
            it.generation == membership.generation &&
            app.pictureshow.core.sync.syncSameUnpauseTimeline(it.command, command)
    } == true
    private fun alreadyAppliedUnpause(command: SyncCommand): Boolean = sameBoundUnpause(appliedUnpause, command)

    private fun sourcePreparedForUnpause(command: SyncCommand, acceptedFuture: Boolean, output: SyncRenderer): Boolean {
        val r = output.snapshot()
        return membership.hasSnapshot &&
            command.playlistItemId == membership.queue?.current?.playlistItemId &&
            r.itemId == membership.queue?.current?.itemId &&
            rendererOwnsEntry(r, command.playlistItemId) &&
            r.seekable &&
            r.durationTicks > 0 &&
            app.pictureshow.core.sync.syncPreparedUnpause(
                command.command,
                acceptedFuture,
                r.ready,
                r.buffering,
                r.unpaused,
                output.positionApplicationPending,
                r.positionTicks,
                command.positionTicks ?: -1
            )
    }

    private fun schedule(command: SyncCommand, retainedPlaying: Boolean? = null) {
        preparedUnpause = preparedUnpause?.takeIf {
            it.clockGeneration == anchorGeneration && sameBoundUnpause(it.binding, command)
        }
        // Preparation is admitted while the command is future, never manufactured by a late retry.
        val receiptNow = anchor?.serverNowTicks(wall(), monotonic())
        val commandWhen = millisecondsToSyncTicks(command.whenMs)
        val preparingOutput = renderer
        if (preparedUnpause == null &&
            receiptNow != null &&
            commandWhen != null &&
            commandWhen > receiptNow &&
            preparingOutput != null &&
            sourcePreparedForUnpause(command, true, preparingOutput)
        ) {
            preparedUnpause = PreparedUnpause(
                AppliedUnpause(rendererToken, preparingOutput, preparingOutput.sourceIdentity, membership.generation, command),
                anchorGeneration
            )
        }
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
                val prepared = preparedUnpause?.let {
                    it.clockGeneration == anchorGeneration &&
                        sameBoundUnpause(it.binding, command) &&
                        renderer?.let { output -> sourcePreparedForUnpause(command, true, output) } == true
                } == true
                val position = if (alreadyPositioned || prepared) {
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
                val replayRate = app.pictureshow.core.sync.syncUnpauseReplayRate(
                    drift,
                    alreadyPositioned,
                    playing,
                    r.rateSupported,
                    monotonic()
                )
                val positionChanged = applyingRenderer?.apply(position, playing, replayRate) == true
                // A BUFFERING -> READY cycle can finish between 250 ms samples. The old
                // acknowledgement belongs to the pre-seek position, even if both samples are READY.
                readiness = readiness.afterPositionApplied(positionChanged)
                timelineApplied = !intendedPlaying || playing
                if (timelineApplied) preparedUnpause = null
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
                // An equivalent timeline must not interrupt or extend the existing correction window.
                if (!alreadyPositioned || positionChanged) drift = syncTransportApplied(drift, monotonic(), positionChanged)
                outcome = "applied"
                trace("apply-after", "source=scheduled attempt=$attempt positionChanged=$positionChanged " + stateFields())
            } finally {
                if (outcome == "unfinished") abort(if (isActive) "failed" else "cancelled")
                trace("schedule-end", "attempt=$attempt outcome=$outcome active=$isActive")
            }
        }
    }

    private fun resetClock(reason: String) {
        waitReason = null
        mutable.value =
            mutable.value.copy(quality = app.pictureshow.core.sync.SyncQuality.UNKNOWN, localPlayback = SyncLocalPlayback.UNAVAILABLE)
        appliedUnpause = null
        preparedUnpause = null
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
        lastClockAttemptFinishedMillis = null
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
            "nativeRate=${n?.playbackRate ?: 1f} nativePwr=${n?.playWhenReady} " +
            "nativeSuppression=${n?.suppressionReason ?: -1} nativeLoading=${n?.isLoading} " +
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
    private fun note(text: String, kind: SyncNoticeKind, tone: SyncNoticeTone = SyncNoticeTone.INFO, action: SyncNoticeAction? = null) {
        // Raw server/legacy strings remain diagnostic only. A transient notice cannot hide an actionable error.
        val existing = mutable.value.notice
        val next = SyncNotice(kind, tone, action)
        val revision = ++noticeRevision
        mutable.value = mutable.value.copy(
            status = text,
            notice = if (existing?.tone == SyncNoticeTone.ERROR && tone == SyncNoticeTone.INFO) existing else next
        )
        if (tone == SyncNoticeTone.INFO) {
            scope.launch {
                delay(4_000)
                if (noticeRevision == revision && mutable.value.notice == next) mutable.value = mutable.value.copy(notice = null)
            }
        }
    }

    private fun publish() {
        trace("published", stateFields())
        val q = membership.queue
        val entries = q?.entries.orEmpty()
        val target = account
        val signature = entries.map { it.playlistItemId to it.itemId }
        val oldScope = titleScope
        if (oldScope?.account != target?.key || oldScope?.membership != membership.generation) {
            titles.clear()
            titleRequests.clear()
            titleQueue = emptyList()
        }
        if (signature != titleQueue || oldScope?.account != target?.key || oldScope?.membership != membership.generation) {
            titleQueue = signature
            titleRevision++
            titleRequests.clear()
        }
        val lookupScope = app.pictureshow.core.sync.SyncTitleScope(target?.key.orEmpty(), membership.generation, titleRevision)
        titleScope = lookupScope
        val observedFromMs = if (BuildConfig.DEBUG) monotonic() else 0L
        val snapshot = renderer?.snapshot()
        val current = q?.current
        val ownsEntry = current != null &&
            snapshot?.itemId == current.itemId &&
            (openingEntry == null || snapshot.playlistItemId == current.playlistItemId)
        val local = when {
            !participating || !ownsEntry || snapshot == null -> SyncLocalPlayback.UNAVAILABLE
            snapshot.buffering -> SyncLocalPlayback.BUFFERING
            snapshot.ready && snapshot.seekable && snapshot.durationTicks > 0 -> SyncLocalPlayback.READY
            else -> SyncLocalPlayback.PREPARING
        }
        val now = monotonic()
        val serverNow = anchor?.takeUnless { it.needsRefresh(now) }?.serverNowTicks(wall(), now)
        val command = timeline?.takeIf { it.playlistItemId == current?.playlistItemId }
        val estimate = if (serverNow != null && command != null && snapshot != null) {
            syncTarget(
                command.positionTicks ?: snapshot.positionTicks,
                command.whenMs,
                serverNow,
                syncTimelinePlaying(command.command, timelinePlaying),
                snapshot.durationTicks
            )?.takeIf { it.delayMillis == 0L }
        } else {
            null
        }
        val quality = app.pictureshow.core.sync.syncQuality(
            connected && membership.hasSnapshot && serverNow != null && membership.phase in setOf("Playing", "Paused"),
            local == SyncLocalPlayback.READY,
            timelineApplied && renderer?.positionApplicationPending == false,
            estimate?.let { snapshot?.positionTicks?.minus(it.positionTicks) },
            anchor?.uncertaintyTicks
        )
        val recovery = when {
            membership.phase == "Error" -> mutable.value.notice?.takeIf { it.tone == SyncNoticeTone.ERROR }
                ?: SyncNotice(SyncNoticeKind.PLAYBACK_FAILED, SyncNoticeTone.ERROR, SyncNoticeAction.LEAVE)
            membership.phase == "Reconnecting" -> SyncNotice(
                SyncNoticeKind.REJOIN,
                SyncNoticeTone.ERROR,
                if (membership.groupId != null) SyncNoticeAction.REJOIN else SyncNoticeAction.LEAVE
            )
            else -> mutable.value.notice
        }
        val published = mutable.value.copy(
            phase = membership.phase, groupId = membership.groupId, groupName = membership.group?.name,
            participants = membership.group?.participants.orEmpty(), ignoreWait = ignoreWait,
            repeat = q?.repeatMode ?: "RepeatNone", shuffle = q?.shuffleMode ?: "Sorted",
            navigation = q?.takeIf { connected && membership.hasSnapshot }?.let {
                app.pictureshow.core.sync.SyncNavigationAuthority(
                    it.repeatMode,
                    it.entries.map { entry -> entry.playlistItemId },
                    it.current?.playlistItemId
                )
            },
            queue = entries.map { entry ->
                SyncPlayQueueRow(
                    entry.playlistItemId,
                    titles[entry.itemId],
                    entry == q?.current,
                    entry.itemId,
                    titles.containsKey(entry.itemId) && titles[entry.itemId] == null
                )
            },
            quality = quality, localPlayback = local, waitReason = waitReason,
            notice = recovery, status = membership.notice ?: mutable.value.status
        )
        mutable.value = published
        if (BuildConfig.DEBUG) {
            // This brackets sequential observations, not an atomic UI draw. Null must not resample a renderer.
            val snapshotFields = if (snapshot != null) stateFields(snapshot) else "renderer=false native=false"
            val phaseFact = when (published.phase) {
                "Idle", "Joining", "Waiting", "Playing", "Paused", "Reconnecting", "Leaving", "Error" -> published.phase
                else -> "other"
            }
            val waitFact = when (published.waitReason) {
                null -> "none"
                "Buffer", "Seek" -> published.waitReason
                else -> "other"
            }
            val positionPending = renderer?.positionApplicationPending
            val observedThroughMs = monotonic()
            trace(
                "presentation-v1",
                "observationStartMs=$observedFromMs observationEndMs=$observedThroughMs " +
                    "presentationPhase=$phaseFact presentationWait=$waitFact " +
                    "presentationQuality=${published.quality.name} presentationLocal=${published.localPlayback.name} " +
                    "positionPendingObservedAfter=$positionPending nativePlaying=${snapshot?.native?.isPlaying} " + snapshotFields
            )
        }
        if (target != null) {
            entries.map { it.itemId }.distinct().filter { it !in titles && titleRequests.add(it) }.forEach { item ->
                val requestedEntries = signature.filter { it.second == item }.map { it.first }.toSet()
                scope.launch {
                    val result = titleLookupSlots.withPermit {
                        // A queued lookup must not start using credentials after its viewing scope has changed.
                        if (account?.key != target.key || account?.accessToken != target.accessToken || titleScope != lookupScope) {
                            return@launch
                        }
                        gateway.title(target, item)
                    }
                    if (account?.key != target.key ||
                        account?.accessToken != target.accessToken ||
                        !app.pictureshow.core.sync.syncAcceptTitle(
                            lookupScope,
                            titleScope ?: return@launch,
                            titleQueue.map { it.first }.toSet(),
                            requestedEntries
                        )
                    ) {
                        return@launch
                    }
                    titles[item] = (result as? ClientResult.Success)?.value?.takeUnless { it.isBlank() }
                    publish()
                }
            }
        }
    }
}
