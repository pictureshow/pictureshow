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
import app.pictureshow.core.control.CastReattachInput
import app.pictureshow.core.control.CastSenderVpn
import app.pictureshow.core.control.CastSessionEvent
import app.pictureshow.core.control.CastState
import app.pictureshow.core.control.CastStoredSessions
import app.pictureshow.core.control.CastTrack
import app.pictureshow.core.control.CastViewedItem
import app.pictureshow.core.control.CastViewingRecord
import app.pictureshow.core.control.ControlIntent
import app.pictureshow.core.control.OwnerState
import app.pictureshow.core.control.TemporarySession
import app.pictureshow.core.control.ViewingIdentity
import app.pictureshow.core.control.castCapability
import app.pictureshow.core.control.castContentType
import app.pictureshow.core.control.castControls
import app.pictureshow.core.control.castMediaUrl
import app.pictureshow.core.control.castReach
import app.pictureshow.core.control.castReattach
import app.pictureshow.core.control.castReattachState
import app.pictureshow.core.control.castSubtitles
import app.pictureshow.core.control.castUrlBase
import app.pictureshow.core.control.reduceCast
import app.pictureshow.core.control.reduceCastSession
import app.pictureshow.core.playback.PlayDecision
import app.pictureshow.core.playback.PlayMethod
import app.pictureshow.core.playback.PlayOutcome
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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

internal const val MS_TO_TICKS = 10_000L

/** The reducer only compares this for sameness; it is never sent anywhere. */
private const val LOCAL_DEVICE = "this-device"

/** The reducer compares this for sameness. There is one sender per install, and it is never sent anywhere. */
private const val SESSION_SENDER = "this-sender"
internal const val CAST_LOAD_TIMEOUT_MS = 30_000L
internal const val CAST_STOP_TIMEOUT_MS = 5_000L
internal const val CAST_COMMAND_TIMEOUT_MS = 5_000L
internal const val CAST_SEARCH_MS = 8_000L
internal const val CAST_REPORT_MS = 10_000L

/** How long a rejoin to stop a detached viewing may take once its route is selected. */
internal const val CAST_RETIRE_MS = 15_000L

/** How long moving to another device waits for the old session to end before selecting anyway. */
internal const val CAST_SWITCH_MS = 3_000L

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
    val local: CastLocalOutput? = null,
    /** What "This device" does in the chooser; null when this device is already playing it. */
    val onThisDevice: (() -> Unit)? = null,
    /** Runs after a Pictureshow TV is attached from this same chooser. */
    val onJellyfinPick: (() -> Unit)? = null
)

internal data class CastPlayItem(
    val id: String,
    val title: String,
    val imageUrl: String? = null,
    val durationMs: Long? = null,
    val live: Boolean = false
)

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
    val localStart: CastLocalStart? = null,
    /** Why the last pick didn't start anything, as a fixed category. */
    val pickRefusal: String? = null,
    /** The device a pick still starting was dropped from, until the viewer has been told. */
    val droppedFrom: String? = null
)

private data class IssuedCastSession(val account: Account, val binding: TemporarySession)

internal sealed interface CastSessionIssue {
    data class Issued(val session: Account) : CastSessionIssue

    /** The server has Quick Connect off, so there's no session to give the receiver. */
    data object QuickConnectOff : CastSessionIssue
    data object Failed : CastSessionIssue
}

/**
 * The server access a sender needs: a session of the receiver's own, negotiating for the receiver, and reporting on its
 * behalf. Everything but [provisionSession] is called with that session, never with the viewer's.
 */
internal interface CastMediaGateway {
    /** Stable for one receiver, server and viewer, and never the viewer's own: logging in under that one logs the viewer out. */
    suspend fun sessionDevice(account: Account, route: String): String
    suspend fun provisionSession(account: Account, deviceId: String): CastSessionIssue

    /** True once the session is gone, including when the server says it already was. */
    suspend fun revokeSession(session: Account): Boolean
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

    /** Saved before the session is used or the load is sent, so a process death can't lose what has to be revoked. */
    val sessions: CastStoredSessions
    suspend fun saveSessions(value: CastStoredSessions)
    val viewing: CastViewingRecord?
    suspend fun saveViewing(record: CastViewingRecord)
    fun clearViewing()
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

    /** What the open chooser would send. */
    private var request: CastPlayRequest? = null

    /** What the viewer picked a device for, and what the handoff under way works from. */
    private var handoff: CastPlayRequest? = null
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

    /** A failed process death mid-issue leaves a record with nothing to revoke; the stable device id replaces whatever it got. */
    private var sessions = environment.sessions.let { stored ->
        stored.copy(state = stored.state.copy(records = stored.state.records.filter { it.secretRef != null }))
    }
    private var session: Account? = null
    private var sessionBinding: TemporarySession? = null
    private var viewing: CastViewingRecord? = null
    private var retiring: CastViewingRecord? = null
    private var restoreJob: Job? = null
    private var retireJob: Job? = null

    /** What the receiver said on rejoining to stop, if it was ours: a sign-out's last report waits for this. Null is no answer. */
    private var retireAnswer: CompletableDeferred<CastStatus?>? = null
    private var retireEmptySeen = false
    private var reattachEmptySeen = false

    /** The route to join once the session on the old device has ended. */
    private var pendingSelect: String? = null
    private var switchJob: Job? = null

    /** The old session's end hasn't come yet, though the new route has been selected. */
    private var oldEndOwed = false

    /** What the viewer picked for a receiver this app first has to rejoin. */
    private var pendingPick: Pair<CastRoute, CastPlayRequest>? = null

    /** The last report, the encoder and the revocation of a viewing that just ended. The next session waits for it. */
    private var cleanupJob: Job? = null
    private var foreground = false
    private var loadedUrls: Map<String, String> = emptyMap()
    private var reported: CastReport? = null
    private var localAlive = false
    private var reconnectJob: Job? = null

    /** The viewer chose this device over a rejoin the device may still answer. */
    private var rejoinDropped = false
    private var lastStatus: CastStatus? = null
    private var awaitingReattach = false

    private var startPausedItem: String? = null

    private val cast get() = mutable.value.cast

    /** What the open chooser was opened to play, if anything. */
    val chooserRequest: CastPlayRequest? get() = request

    fun bind(next: Account?) {
        val usable = next?.takeUnless { it.expired }
        if (usable?.key == account?.key) {
            account = usable
            if (usable != null) settleStored()
            return
        }
        // A receiver doesn't follow an account change, and its media belongs to the viewer who left: it stops here.
        val trigger = if (usable == null) "sign-out" else "viewer-change"
        stopForAccountChange(usable, trigger)
        account = usable
        clear()
        if (usable == null) return
        settleStored()
        // On a cold start the foreground signal can arrive before the account does.
        if (foreground) reattachIfLive()
    }

    private fun settleStored() {
        retryRevocations()
        sweepOrphans()
    }

    /**
     * A process death between the session being saved and its viewing being recorded leaves a token nothing refers to, and
     * the server gives it no expiry. Nothing was ever sent to a receiver with it, so it just goes.
     */
    private fun sweepOrphans() {
        if (cast != null || sessionBinding != null || cleanupJob?.isActive == true) return
        val current = environment.viewing?.device
        val orphans = sessions.state.records.filter { it.phase == "active" && it.device != current }
        if (orphans.isEmpty()) return
        cleanupJob = scope.launch { orphans.forEach { endRecord(it, "forget") } }
    }

    private fun stopForAccountChange(next: Account?, trigger: String) {
        readPosition()
        val state = cast
        val reporter = session
        val active = state?.owner?.let { it.candidate?.kind == "cast" || it.owner.kind == "cast" } == true
        val last = reportFor().takeIf { active && state?.owner?.owner?.kind == "cast" }
        val stored = viewing ?: environment.viewing
        val leaving = stored?.takeIf { next == null || it.server != next.server.id || it.viewer != next.user.id }
        // With Google Cast turned off this app stays away from Cast devices: the session still goes, the receiver is left alone.
        val mayJoin = environment.enabled && environment.services() == "ok"
        val rejoined = when {
            active && state?.link == "connected" -> {
                if (loadStarted || state.owner.owner.kind == "cast") link?.stop { }
                link?.endSession(stopReceiver = true)
                null
            }

            active && stored != null -> stored.takeIf { mayJoin }

            leaving != null && mayJoin -> leaving

            else -> null
        }
        rejoined?.let(::rejoinToStop)
        val answer = retireAnswer.takeIf { rejoined != null }
        val lastReporter = reporter ?: rejoined?.let(::storedSession)
        if (active || leaving != null) {
            environment.mayBeLive = false
            environment.clearViewing()
            viewing = null
        }
        session = null
        sessionBinding = null
        val records = sessions.state.records.filter { active || next == null || it.server != next.server.id || it.viewer != next.user.id }
        if (records.isEmpty() && last == null && rejoined == null) return
        val earlier = cleanupJob
        cleanupJob = scope.launch {
            // A change of account during an earlier wait must not revoke the session that wait still has to report with.
            earlier?.join()
            // Detached, only the receiver knows how far it got, so its answer comes before the session goes.
            val final = answer?.await()?.let { lastReportOf(requireNotNull(rejoined), it) } ?: last
            if (lastReporter != null && final != null) {
                gateway.reportStopped(lastReporter, final)
                final.playSessionId?.let { gateway.stopEncoding(lastReporter, it) }
            }
            records.forEach { endRecord(it, trigger) }
        }
    }

    private fun storedSession(record: CastViewingRecord): Account? = sessions.state.records
        .firstOrNull { it.device == record.device }?.secretRef?.let(sessions.secrets::get)

    private fun lastReportOf(record: CastViewingRecord, status: CastStatus): CastReport? {
        if (status.positionMs <= 0) return null
        val item = record.items.firstOrNull { it.entry == status.customData["entry"] } ?: record.items.firstOrNull() ?: return null
        return CastReport(
            item.itemId,
            item.mediaSourceId,
            item.playSessionId,
            playMethod(item.method),
            (status.durationMs?.takeIf { it > 0 }?.let { minOf(status.positionMs, it) } ?: status.positionMs) * MS_TO_TICKS,
            status.playerState == "paused"
        )
    }

    /**
     * Revoking doesn't stop a receiver that plays the file directly: the server serves that route without a token. Joining
     * is the only way to reach it, and only media still carrying this viewing's nonce gets stopped.
     */
    private fun rejoinToStop(record: CastViewingRecord) {
        retiring = record
        retireEmptySeen = false
        val current = ensureLink()
        current.startDiscovery()
        retireJob?.cancel()
        retireAnswer?.complete(null)
        val answer = CompletableDeferred<CastStatus?>()
        retireAnswer = answer
        retireJob = scope.launch {
            val found = withTimeoutOrNull(CAST_SEARCH_MS) { current.routes.first { routes -> routes.any { it.id == record.route } } }
            val selected = found != null && current.select(record.route)
            current.stopDiscovery()
            if (selected) delay(CAST_RETIRE_MS)
            // Connected or not, this attempt is over; left set, it would take the next cast's link for its own.
            if (retiring === record) {
                retiring = null
                if (selected) current.endSession(stopReceiver = false)
            }
            answer.complete(null)
        }
    }

    private fun stopRetiring() {
        retiring = null
        retireAnswer?.complete(null)
        retireAnswer = null
        retireJob?.cancel()
        retireJob = null
    }

    /** The answer is durable before the caller acts on it. The state itself moves at once, so reducers never see an old one. */
    private suspend fun applySession(event: CastSessionEvent): String {
        val next = reduceCastSession(sessions.state, event)
        sessions = sessions.copy(state = next)
        environment.saveSessions(sessions)
        return next.effect
    }

    /** Ends one session: durably marked first, then logged out, and kept as pending if the server can't be reached. */
    private suspend fun endRecord(record: TemporarySession, trigger: String) {
        if (applySession(CastSessionEvent(trigger, record)) != "persist-revocation") return
        if (applySession(CastSessionEvent("persisted", record)) == "logout") logout(record)
    }

    private suspend fun logout(record: TemporarySession) {
        val ref = sessions.state.records.firstOrNull { it.device == record.device && it.server == record.server }?.secretRef
        val secret = ref?.let(sessions.secrets::get)
        val gone = secret == null || gateway.revokeSession(secret)
        applySession(CastSessionEvent(if (gone) "logout-ok" else "logout-failed", record))
        if (gone && ref != null) {
            sessions = sessions.copy(secrets = sessions.secrets - ref)
            environment.saveSessions(sessions)
        }
    }

    /** Pending revocations go first whenever the server may be back, and before any new session under that identity. */
    private fun retryRevocations() {
        val pending = sessions.state.records.filter { it.phase in listOf("pending-persist", "revoking", "pending-revoke") }
        if (pending.isEmpty()) return
        scope.launch { pending.forEach { resumeRevocation(it) } }
    }

    private suspend fun resumeRevocation(record: TemporarySession) {
        val op = if (record.phase == "pending-persist") "persisted" else "reconnect"
        if (applySession(CastSessionEvent(op, record)) == "logout") logout(record)
    }

    /** A session for this viewing, or null with the failure already dispatched. The old viewing's sessions end first. */
    private suspend fun provision(active: Account, target: ViewingIdentity): IssuedCastSession? {
        // The old viewing's last report goes out under its own session, so it finishes before anything revokes that.
        cleanupJob?.join()
        sessions.state.records.toList().forEach { old ->
            if (old.phase == "active") endRecord(old, "viewer-change") else resumeRevocation(old)
        }
        if (cast?.owner?.candidate?.generation != target.generation) return null
        val deviceId = gateway.sessionDevice(active, target.device)
        val binding = TemporarySession(active.server.id, active.user.id, deviceId, SESSION_SENDER, target.generation)
        // Set before the save can suspend, so a cancel in that gap still finds this session to end.
        sessionBinding = binding
        if (applySession(CastSessionEvent("provision", binding)) != "provision") {
            // Offline revocation still owed, or otherwise refused: never a second session and never the viewer's own token.
            if (sessionBinding === binding) sessionBinding = null
            if (cast?.owner?.candidate?.generation == target.generation) dispatch(CastEvent("load-failed", recovery = "server-unreachable"))
            return null
        }
        when (val issue = gateway.provisionSession(active, deviceId)) {
            is CastSessionIssue.Issued -> {
                val ref = UUID.randomUUID().toString()
                sessions = sessions.copy(secrets = sessions.secrets + (ref to issue.session))
                environment.saveSessions(sessions)
                val effect = applySession(CastSessionEvent("issued", binding, issue.session.user.id, ref))
                if (effect == "use") return IssuedCastSession(issue.session, binding)
                if (effect == "persist-revocation" && applySession(CastSessionEvent("persisted", binding)) == "logout") logout(binding)
                return null
            }

            CastSessionIssue.QuickConnectOff -> settleUnissued(binding, "quick-connect-off")

            CastSessionIssue.Failed -> settleUnissued(binding, "server-unreachable")
        }
        return null
    }

    private suspend fun settleUnissued(binding: TemporarySession, recovery: String) {
        sessions = sessions.copy(
            state = sessions.state.copy(
                records = sessions.state.records.filterNot { it.device == binding.device && it.server == binding.server }
            )
        )
        environment.saveSessions(sessions)
        if (sessionBinding === binding) sessionBinding = null
        if (cast?.owner?.candidate?.generation == binding.generation) dispatch(CastEvent("load-failed", recovery = recovery))
    }

    /** [encodings] are stopped under the session first, while it can still ask. */
    private fun endCurrentSession(trigger: String, encodings: List<String> = emptyList()) {
        val record = sessionBinding
        val reporter = session
        session = null
        sessionBinding = null
        viewing = null
        environment.clearViewing()
        if (record == null) return
        cleanupJob = scope.launch {
            if (reporter != null) encodings.forEach { gateway.stopEncoding(reporter, it) }
            endRecord(record, trigger)
        }
    }

    /** Returning to the app reattaches only if a viewing may still be running; otherwise nothing wakes the SDK. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (!value || environment.tvInterface || account == null) return
        settleStored()
        reattachIfLive()
    }

    private fun reattachIfLive() {
        if (environment.tvInterface || !environment.mayBeLive || cast != null) return
        if (environment.services() != "ok" || !environment.enabled) return
        ensureLink()
        // Without a session to resume there's nothing to wait for. The viewing is still offered, so it can still be ended.
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(CAST_SEARCH_MS)
            if (cast == null && !awaitingReattach) restoreDetached()
        }
    }

    private fun restoreDetached() {
        val active = account ?: return
        val record = environment.viewing ?: return
        if (record.server != active.server.id || record.viewer != active.user.id || !adopt(record)) return
        val entry = record.items.firstOrNull()?.entry
        val state = castReattachState(ownerOf(record), localIdentity(active, record.generation), ControlIntent(entry = entry))
        mutable.update { it.copy(cast = state, title = record.title, deviceName = record.deviceName) }
    }

    private fun ownerOf(record: CastViewingRecord) =
        ViewingIdentity(record.server, record.viewer, record.route, record.session, record.generation, "cast")

    /** Takes this viewing up again only if its own session is still there to report with. */
    private fun adopt(record: CastViewingRecord): Boolean {
        val binding = sessions.state.records.firstOrNull { it.device == record.device && it.phase == "active" } ?: return false
        val secret = binding.secretRef?.let(sessions.secrets::get) ?: return false
        session = secret
        sessionBinding = binding
        viewing = record
        generation = maxOf(generation, record.generation)
        return true
    }

    fun openChooser(playRequest: CastPlayRequest?) {
        request = playRequest
        mutable.update { it.copy(chooserOpen = true, blockedReach = null, pickRefusal = null) }
        if (playRequest == null) return
        refreshCapability()
        startSearch()
    }

    fun closeChooser() {
        request = null
        stopSearch()
        mutable.update { it.copy(chooserOpen = false, blockedReach = null, pickRefusal = null) }
    }

    private val mutableCastEnabled = kotlinx.coroutines.flow.MutableStateFlow(environment.enabled)

    /** The viewer's setting. The capability can't say: a device that needs a Play services update reads the same either way. */
    val castEnabled = mutableCastEnabled.asStateFlow()

    fun droppedHandled() = mutable.update { it.copy(droppedFrom = null) }

    fun setEnabled(value: Boolean) {
        environment.enabled = value
        mutableCastEnabled.value = value
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
        readPosition()
        val reach = castReach(CastReachInput(active.server.url, environment.senderVpn()))
        if (!reach.allowed) {
            mutable.update { it.copy(blockedReach = reach, pickRefusal = null) }
            return
        }
        val item = playRequest.items.getOrNull(playRequest.startIndex) ?: return
        val local = localIdentity(active, nextGeneration())
        val target = ViewingIdentity(
            active.server.id,
            active.user.id,
            route.id,
            "cast-" + local.generation,
            nextGeneration(),
            "cast"
        )
        val intent = ControlIntent(
            entry = entryId(playRequest.startIndex, item.id),
            ticks = playRequest.startMs * MS_TO_TICKS,
            paused = playRequest.paused,
            queue = playRequest.items.mapIndexed { index, it -> entryId(index, it.id) }
        )
        val before = cast
        val next = reduceCast(
            before ?: CastState(OwnerState(local, intent = intent)),
            CastEvent("pick", identity = target, intent = intent)
        )
        when {
            next.effect == "show-controls" || next.effect == "reconnect" -> {
                pendingPick = (route to playRequest).takeIf { next.reason == "rejoin-to-replace" }
                request = null
                stopSearch()
                mutable.update { it.copy(chooserOpen = false, pickRefusal = null) }
                // After the chooser's search stops, because this starts its own.
                if (next.effect == "reconnect") reconnect()
                return
            }

            next.reason != null -> {
                mutable.update { it.copy(pickRefusal = next.reason, blockedReach = null) }
                return
            }
        }
        // The viewing this replaces, while its status can still say where it got to.
        if (next.effect.startsWith("replace-")) endReplacedViewing(before)
        stopRetiring()
        restoreJob?.cancel()
        awaitingReattach = false
        handoff = playRequest
        request = null
        prepared = emptyMap()
        loadedUrls = emptyMap()
        reported = null
        viewing = null
        lastStatus = null
        loadStarted = false
        localAlive = playRequest.local != null
        mutable.update {
            it.copy(cast = next, deviceName = route.name, title = item.title, controls = null, chooserOpen = false, pickRefusal = null)
        }
        environment.mayBeLive = true
        loadTimer?.cancel()
        // The same limit covers joining the device: a route that never connects must not leave local playback waiting.
        loadTimer = scope.launch {
            delay(CAST_LOAD_TIMEOUT_MS)
            dispatch(CastEvent("load-timeout"))
        }
        val joining = ensureLink()
        when (next.effect) {
            "replace-on-link" -> {
                // Already joined: the SDK would say nothing about selecting this route again.
                stopSearch()
                dispatch(CastEvent("link-connecting"))
                dispatch(CastEvent("link-connected"))
            }

            "replace-elsewhere" -> {
                // Selecting while the old session ends would race its own unselect, so this waits for it. Search stays on until then.
                dispatch(CastEvent("link-connecting"))
                pendingSelect = route.id
                joining.endSession(stopReceiver = true)
                switchJob?.cancel()
                switchJob = scope.launch {
                    delay(CAST_SWITCH_MS)
                    selectPending(oldEnded = false)
                }
            }

            else -> {
                dispatch(CastEvent("link-connecting"))
                joinRoute(joining, route.id)
            }
        }
    }

    /** Selected before the search stops, because stopping is what lets the router forget a route. */
    private fun joinRoute(joining: CastLink, routeId: String) {
        val selected = joining.select(routeId)
        stopSearch()
        if (!selected) dispatch(CastEvent("load-failed", recovery = "route-gone"))
    }

    private fun selectPending(oldEnded: Boolean = true) {
        val routeId = pendingSelect ?: return
        pendingSelect = null
        oldEndOwed = !oldEnded
        switchJob?.cancel()
        switchJob = null
        joinRoute(ensureLink(), routeId)
    }

    /** Once the receiver has answered, the rejoin is done and this is a replace on the link. If the viewing has gone, so is the point of it. */
    private fun settlePendingPick() {
        val (route, playRequest) = pendingPick ?: return
        val state = cast
        if (state == null || state.owner.owner.kind != "cast") {
            pendingPick = null
        } else if (state.owner.phase == "active" && state.link == "connected" && state.observed != null) {
            pendingPick = null
            request = playRequest
            pick(route)
            request = null
        }
    }

    private fun cancelPendingSelect() {
        oldEndOwed = false
        pendingSelect = null
        switchJob?.cancel()
        switchJob = null
    }

    /** Stop now if this link holds it, then its last report and its session. A failed start won't leave it unowned on the receiver. */
    private fun endReplacedViewing(before: CastState?) {
        reportJob?.cancel()
        commandTimer?.cancel()
        stopTimer?.cancel()
        if (before?.link == "connected") link?.stop { }
        cleanup(before?.observed?.ticks, "replaced")
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
        val playRequest = handoff
        val output = playRequest?.local
        if (output == null) {
            dispatch(CastEvent("local-paused", ticks = (playRequest?.startMs ?: 0) * MS_TO_TICKS))
            return
        }
        scope.launch {
            val moment = output.pauseAndRead()
            dispatch(CastEvent("local-paused", ticks = moment.positionMs * MS_TO_TICKS, paused = moment.paused))
        }
    }

    private fun loadRemote() {
        val active = account ?: return dispatch(CastEvent("load-failed"))
        val playRequest = handoff ?: return dispatch(CastEvent("load-failed"))
        val state = cast ?: return
        val target = state.owner.candidate ?: return
        val startMs = state.owner.intent.ticks / MS_TO_TICKS
        val serial = target.generation
        scope.launch {
            val (temporary, binding) = provision(active, target) ?: return@launch
            if (cast?.owner?.candidate?.generation != serial) {
                // Overtaken: this ends this session and nobody else's.
                if (sessionBinding === binding) sessionBinding = null
                endRecord(binding, "cancel")
                return@launch
            }
            session = temporary
            val nonce = UUID.randomUUID().toString()
            val items = mutableListOf<CastLoadItem>()
            val decisions = mutableMapOf<String, PlayDecision>()
            playRequest.items.forEachIndexed { index, item ->
                val outcome = gateway.prepare(temporary, item.id, playRequest, if (index == playRequest.startIndex) startMs else 0)
                if (cast?.owner?.candidate?.generation != serial) return@launch
                val decision = ((outcome as? ClientResult.Success)?.value as? PlayOutcome.Play)?.decision
                if (decision == null) {
                    val refused = outcome is ClientResult.Success
                    dispatch(CastEvent("load-failed", recovery = if (refused) "unsupported-media" else "server-unreachable"))
                    return@launch
                }
                val entry = entryId(index, item.id)
                decisions[entry] = decision
                items += loadItem(temporary, target, item, entry, decision, nonce)
            }
            prepared = decisions
            val record = CastViewingRecord(
                nonce = nonce,
                server = active.server.id,
                viewer = active.user.id,
                route = target.device,
                device = temporary.deviceId.orEmpty(),
                session = target.session,
                generation = target.generation,
                items = playRequest.items.mapIndexed { index, item ->
                    val entry = entryId(index, item.id)
                    val decision = decisions.getValue(entry)
                    CastViewedItem(
                        entry,
                        item.id,
                        decision.mediaSourceId,
                        decision.playSessionId,
                        decision.method.name,
                        castUrlBase(castMediaUrl(decision, temporary.accessToken))
                    )
                },
                title = playRequest.items.getOrNull(playRequest.startIndex)?.title,
                deviceName = mutable.value.deviceName
            )
            viewing = record
            environment.saveViewing(record)
            if (cast?.owner?.candidate?.generation != serial) return@launch
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

    /**
     * Only what a reattach compares goes in custom data: anything on the receiver's network can read it, and can write it
     * too, so nothing is ever taken from it.
     */
    private fun loadItem(
        temporary: Account,
        target: ViewingIdentity,
        item: CastPlayItem,
        entry: String,
        decision: PlayDecision,
        nonce: String
    ): CastLoadItem {
        val tracks = castSubtitles(temporary.server, decision, temporary.accessToken).map {
            CastLoadTrack(it.trackId.toLong(), it.label, it.language, it.url)
        }
        return CastLoadItem(
            url = castMediaUrl(decision, temporary.accessToken),
            contentType = castContentType(decision),
            title = item.title,
            imageUrl = item.imageUrl,
            durationMs = item.durationMs,
            live = item.live,
            tracks = tracks,
            customData = mapOf(
                "app" to "pictureshow",
                "nonce" to nonce,
                "generation" to target.generation.toString(),
                "entry" to entry
            )
        )
    }

    /** A failed handoff: the local player stays paused where it was, and Resume here is the viewer's call. */
    private fun failed() {
        loadTimer?.cancel()
        cancelPendingSelect()
        val sent = loadStarted
        loadStarted = false
        environment.mayBeLive = false
        link?.let { current ->
            if (sent) current.stop { }
            // Selecting a device can join a receiver someone else is using. Only a load, or having started it, makes it this viewing's to stop.
            if (cast?.link != "lost") current.endSession(stopReceiver = sent || current.launchedReceiver())
        }
        // The receiver may have started a transcode by now, and only this session can ask to stop it.
        val encodings = if (sent) prepared.values.mapNotNull { it.playSessionId }.distinct() else emptyList()
        endCurrentSession("load-failed", encodings)
    }

    private fun released() {
        loadTimer?.cancel()
        // A player that has gone may have left another on this same route, and releasing is what exits the route.
        handoff?.local?.takeIf { localAlive }?.release()
        localAlive = false
        handoff = null
        val active = session
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

    /** Takes the receiver's clock now, before something reads the position or takes the status away. */
    private fun readPosition() {
        if (retiring != null) return
        link?.positionMs()?.let { dispatch(CastEvent("progress", ticks = it * MS_TO_TICKS)) }
    }

    private fun report() {
        readPosition()
        val before = cast ?: return
        val after = reduceCast(before, CastEvent("report", sequence = ++reportSequence))
        mutable.update { it.copy(cast = after) }
        if (after.effect != "report") return
        val active = session ?: return
        val value = reportFor() ?: return
        reported = value
        scope.launch { gateway.reportProgress(active, value) }
    }

    /** The current entry's report: position and pause from the receiver's status, and what it's about from our own record. */
    private fun reportFor(): CastReport? {
        val observed = cast?.observed ?: return reported
        val entry = observed.entry ?: cast?.owner?.intent?.entry
        val items = viewing?.items.orEmpty()
        val item = items.firstOrNull { it.entry == entry } ?: items.firstOrNull()
        val paused = observed.playerState == "paused"
        return if (item != null) {
            CastReport(item.itemId, item.mediaSourceId, item.playSessionId, playMethod(item.method), observed.ticks, paused)
        } else {
            reported?.copy(positionTicks = observed.ticks, paused = paused)
        }
    }

    private fun playMethod(name: String) = runCatching { PlayMethod.valueOf(name) }.getOrDefault(PlayMethod.DirectPlay)

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
        cleanup(state.returnTicks, if (state.stopPurpose == "ended") "receiver-ended" else "stop-confirmed")
        link?.endSession(stopReceiver = state.notice != "receiver-taken")
        if (state.stopPurpose == "here" && state.owner.phase == "return-ready") {
            account?.let { dispatch(CastEvent("resume-here", identity = localIdentity(it, nextGeneration()))) }
        } else if (state.stopPurpose == "end") {
            clear()
        }
    }

    /** The last report and the encoder go out under the session, which is only revoked after them. */
    private fun cleanup(ticks: Long?, trigger: String) {
        val reporter = session
        val record = sessionBinding
        val last = reportFor()?.let { it.copy(positionTicks = ticks ?: it.positionTicks) }
        val serial = cast?.owner?.generation
        session = null
        sessionBinding = null
        viewing = null
        environment.clearViewing()
        cleanupJob = scope.launch {
            if (reporter != null && last != null) {
                gateway.reportStopped(reporter, last)
                last.playSessionId?.let { gateway.stopEncoding(reporter, it) }
            }
            if (cast?.owner?.generation == serial) dispatch(CastEvent("cleanup-done"))
            record?.let { endRecord(it, trigger) }
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
            cleanup(state.returnTicks, "play-here-unconfirmed")
            link?.endSession(stopReceiver = true)
        }
        val output = handoff?.local.takeIf { localAlive }
        startPausedItem = itemId.takeIf { output == null && state.owner.intent.paused }
        mutable.update { CastUi(capability = it.capability, localStart = if (output == null) start else null) }
        // After a failed handoff the player is still open, paused where it was.
        output?.resume()
        localAlive = false
        handoff = null
    }

    fun localStartHandled(serial: Long) {
        mutable.update { if (it.localStart?.serial == serial) it.copy(localStart = null) else it }
    }

    /** True once, for the player that Play here opened on a viewing that was paused. */
    fun consumeStartPaused(itemId: String): Boolean = (startPausedItem == itemId).also { if (it) startPausedItem = null }

    /** The player screen went away, so Resume here has to open a new one. */
    fun localClosed(output: CastLocalOutput) {
        if (handoff?.local === output) localAlive = false
        localLeaving(output)
    }

    /**
     * Once a player starts to leave, or has played to the end, a chooser opened from it and a pick waiting on a rejoin have
     * nothing left to act on. One opened elsewhere is not its business.
     */
    fun localLeaving(output: CastLocalOutput) {
        if (request?.local === output) closeChooser()
        if (pendingPick?.second?.local === output) pendingPick = null
    }

    /**
     * This player is out of title, or going on to the next one. A handoff still starting from it would read its last moment
     * and send the finished title, so it ends here, with nothing to resume: the next step is already under way.
     */
    fun localAdvancing(output: CastLocalOutput) {
        localLeaving(output)
        if (handoff?.local !== output || cast?.owner?.candidate == null) return
        localAlive = false
        val device = state.value.deviceName
        dispatch(CastEvent("cancel"))
        dismiss()
        mutable.update { it.copy(droppedFrom = device) }
    }

    /** The viewer's later choice stands over one still waiting on a rejoin. */
    fun chooseThisDevice(shown: CastPlayRequest) {
        if (pendingPick != null) dropRejoin()
        shown.onThisDevice?.invoke()
    }

    /** The rejoin was only for the pick. What's playing on the device is left alone, and the viewing stays detached and offered. */
    private fun dropRejoin() {
        pendingPick = null
        rejoinDropped = true
        reconnectJob?.cancel()
        reconnectJob = null
        link?.stopDiscovery()
        link?.endSession(stopReceiver = false)
        dispatch(CastEvent("link-lost"))
    }

    /** The viewer is choosing where this player's viewing goes, or has chosen and it hasn't got there yet: what's next waits too. */
    fun holdsNextStep(output: CastLocalOutput) = (state.value.chooserOpen && request?.local === output) ||
        pendingPick?.second?.local === output ||
        (handoff?.local === output && state.value.cast?.owner?.candidate != null)

    /**
     * A Jellyfin pick from the dialog on screen. The dialog holds the request it composed with, so a tap that lands after the
     * player has gone must not run it. [attach] runs first, as the pick depends on the attached TV.
     */
    fun pickJellyfin(shown: CastPlayRequest?, attach: () -> Unit): Boolean {
        if (shown != null && shown !== request) return false
        attach()
        shown?.onJellyfinPick?.invoke()
        return true
    }

    private fun clear() {
        loadTimer?.cancel()
        stopTimer?.cancel()
        commandTimer?.cancel()
        reportJob?.cancel()
        cancelPendingSelect()
        pendingPick = null
        reconnectJob?.cancel()
        rejoinDropped = false
        request = null
        handoff = null
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

    fun stop() {
        readPosition()
        dispatch(CastEvent("stop"))
    }

    fun playHere() {
        readPosition()
        dispatch(CastEvent("play-here"))
    }

    fun retryStop() = dispatch(CastEvent("retry-stop"))

    fun disconnect() {
        pendingPick = null
        // Nothing reports while detached, so this is the position the server keeps until someone comes back.
        report()
        dispatch(CastEvent("disconnect"))
    }

    fun cancel() = dispatch(CastEvent("cancel"))

    /** Back to a receiver the viewer left playing. This is the viewer's ask, so a short search is fine here. */
    fun reconnect() {
        val device = cast?.owner?.owner?.device ?: return
        if (cast?.owner?.phase != "disconnected" || !environment.enabled) return
        rejoinDropped = false
        val current = ensureLink()
        current.startDiscovery()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val found = withTimeoutOrNull(CAST_SEARCH_MS) { current.routes.first { routes -> routes.any { it.id == device } } }
            val selected = found != null && current.select(device)
            current.stopDiscovery()
            if (!selected) {
                pendingPick = null
                dispatch(CastEvent("link-failed"))
            }
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
        endCurrentSession("forget")
        clear()
    }

    override fun onConnecting() {
        if (!rejoinDropped) dispatch(CastEvent("link-connecting"))
    }

    override fun onConnected(deviceName: String?) {
        restoreJob?.cancel()
        reattachEmptySeen = false
        oldEndOwed = false
        if (rejoinDropped) {
            rejoinDropped = false
            link?.endSession(stopReceiver = false)
            return
        }
        if (retiring != null) {
            link?.requestStatus()
            return
        }
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
        rejoinDropped = false
        stopRetiring()
        // The old device's session, which a move to another device was waiting for. It isn't the new one's failure.
        if (pendingSelect != null) return selectPending()
        if (oldEndOwed) {
            oldEndOwed = false
            return
        }
        pendingPick = null
        if (cast == null) {
            // A viewing still on record may still be playing, and is still offered so it can be ended.
            if (environment.viewing == null) environment.mayBeLive = false
            return
        }
        // Our own endSession also lands here, after the viewing has already moved on.
        val state = cast ?: return
        if (state.owner.candidate != null || state.owner.phase == "active" || state.stop == "sent") dispatch(CastEvent("link-lost"))
    }

    override fun onConnectFailed() {
        awaitingReattach = false
        pendingPick = null
        if (rejoinDropped) {
            rejoinDropped = false
            return
        }
        if (retiring != null) return stopRetiring()
        dispatch(CastEvent("link-failed"))
    }

    override fun onStatus(status: CastStatus) {
        retiring?.let { record ->
            val answer = castReattach(reattachInput(record, record.server, record.viewer, status, retireEmptySeen))
            if (answer.decision == "recheck") {
                retireEmptySeen = true
                link?.requestStatus()
                return
            }
            val ours = answer.decision == "adopt"
            if (ours) retireAnswer?.complete(status)
            stopRetiring()
            if (ours) link?.stop { }
            link?.endSession(stopReceiver = ours)
            return
        }
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
        settlePendingPick()
    }

    override fun onProgress(positionMs: Long) {
        if (retiring == null) dispatch(CastEvent("progress", ticks = positionMs * MS_TO_TICKS))
    }

    /** After a process death: ours only if what the receiver shows is what we stored, and what we report comes from that. */
    private fun reattach(status: CastStatus) {
        val active = account ?: return
        val record = environment.viewing
        val answer = castReattach(reattachInput(record, active.server.id, active.user.id, status, reattachEmptySeen))
        if (answer.decision == "recheck") {
            reattachEmptySeen = true
            link?.requestStatus()
            // If nothing more is said, the viewing is offered with a way to end it, and nothing is stopped on a guess.
            restoreJob?.cancel()
            restoreJob = scope.launch {
                delay(CAST_SEARCH_MS)
                if (cast == null && awaitingReattach) {
                    awaitingReattach = false
                    restoreDetached()
                }
            }
            return
        }
        restoreJob?.cancel()
        awaitingReattach = false
        val entry = answer.entry
        if (answer.decision != "adopt" || record == null || entry == null || !adopt(record)) {
            leaveReceiver(record, if (answer.decision == "ended") "receiver-ended" else "foreign-media")
            return
        }
        val item = record.items.first { it.entry == entry }
        reported = CastReport(
            item.itemId,
            item.mediaSourceId,
            item.playSessionId,
            playMethod(item.method),
            status.positionMs * MS_TO_TICKS,
            status.playerState == "paused"
        )
        val intent = ControlIntent(entry = entry, ticks = status.positionMs * MS_TO_TICKS, queue = record.items.map { it.entry })
        val state = castReattachState(ownerOf(record), localIdentity(active, record.generation), intent)
        mutable.update { it.copy(cast = state, title = record.title ?: status.title) }
        dispatch(CastEvent("link-connected"))
        dispatch(CastEvent("observe", observation = observation(status), nowMs = nowMs()))
        startReporting()
    }

    private fun reattachInput(record: CastViewingRecord?, server: String, viewer: String, status: CastStatus, emptyBefore: Boolean) =
        CastReattachInput(
            record,
            server,
            viewer,
            status.mediaSession,
            status.playerState,
            status.customData,
            status.contentUrl,
            link?.joinedRoute(),
            status.idleReason,
            emptyBefore
        )

    /** Not ours: nothing is controlled or reported, and a session we still hold for a viewing that's gone is revoked. */
    private fun leaveReceiver(record: CastViewingRecord?, trigger: String) {
        environment.mayBeLive = false
        environment.clearViewing()
        link?.endSession(stopReceiver = false)
        val stored = sessions.state.records.filter { record != null && it.device == record.device }
        scope.launch { stored.forEach { endRecord(it, trigger) } }
    }

    /**
     * Whether media is ours is decided by this viewing's nonce, or by the URL we loaded if a receiver drops custom data.
     * Other media can claim any generation it likes: without those it reads as not ours. The rest of a claim is ignored.
     */
    private fun observation(status: CastStatus): CastObservation {
        val target = cast?.owner?.candidate ?: cast?.owner?.owner
        val ours = viewing?.let { record ->
            status.customData["nonce"] == record.nonce &&
                (status.contentUrl == null || record.items.any { it.url == castUrlBase(status.contentUrl) })
        } == true
        val entryFromUrl = status.contentUrl?.let(loadedUrls::get)
        val stamped = when {
            ours -> status.customData["generation"]?.toLongOrNull() ?: target?.generation ?: -1
            entryFromUrl != null -> target?.generation ?: -1
            else -> -1
        }
        val claimedEntry = status.customData["entry"].takeIf { entry -> ours && viewing?.items?.any { it.entry == entry } == true }
        return CastObservation(
            mediaSession = status.mediaSession,
            generation = stamped,
            playerState = status.playerState,
            idleReason = status.idleReason,
            ticks = status.positionMs * MS_TO_TICKS,
            durationTicks = status.durationMs?.let { it * MS_TO_TICKS },
            entry = claimedEntry ?: entryFromUrl,
            activeTracks = status.activeTracks,
            queue = status.queue,
            commands = status.commands
        )
    }
}
