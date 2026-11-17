// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

// The SyncPlay session adapter (spec/features/syncplay.md SYN-01..11): one engine per signed-in viewer that owns
// socket registration, membership intents, clock calibration, command scheduling, readiness, drift correction and
// the stay-in-group rule. It is synchronous and single-threaded by contract (the app drives it from the main actor),
// so tests and the shared vectors run the same code with fake transport, clock, scheduler and renderer.

/// What a POST to SyncPlay returned. Only a definite 4xx refusal proves the server did not apply it.
public enum SyncPostOutcome: Equatable, Sendable {
    case ok, http(Int), failed
    public var definitelyRefused: Bool {
        if case let .http(code) = self { return (400..<500).contains(code) && code != 408 && code != 429 }
        return false
    }
}

/// Viewer-scoped HTTP for one account. Completions must arrive on the engine's thread.
public protocol SyncEngineTransport: AnyObject {
    func post(_ request: SyncRequest, completion: @escaping (SyncPostOutcome) -> Void)
    /// One GetUtcTime exchange: the server's reception and transmission times in UTC milliseconds, or nil.
    func utcTime(completion: @escaping ((receivedMillis: Int64, transmittedMillis: Int64)?) -> Void)
}

/// Wall time for server mapping and a monotonic clock for elapsed time, both in milliseconds.
public protocol SyncEngineClock: AnyObject {
    var wallMillis: Int64 { get }
    var monotonicMillis: Int64 { get }
}

public protocol SyncScheduled: AnyObject { func cancel() }
public protocol SyncEngineScheduler: AnyObject {
    /// Runs `work` once on the engine's thread after `millis`.
    func after(_ millis: Int64, _ work: @escaping () -> Void) -> SyncScheduled
}

/// The renderer's actual native facts, read when the engine needs them.
public struct SyncRendererFacts: Equatable, Sendable {
    /// The playlist entry and membership generation this renderer was opened for.
    public var entryId: String?
    public var generation: Int64
    /// The native source is loaded and can take seeks and start.
    public var prepared: Bool
    public var buffering: Bool
    /// Native play intent (Jellyfin's IsPlaying), distinct from advancing output.
    public var unpaused: Bool
    /// Output suppressed for another reason (audio interruption) while unpaused.
    public var suppressed: Bool
    public var positionTicks: Int64
    public var durationTicks: Int64
    /// Finite, seekable server VOD (`SyncTiming.sourceEligible`).
    public var eligible: Bool
    public init(entryId: String?, generation: Int64, prepared: Bool, buffering: Bool, unpaused: Bool, suppressed: Bool = false,
                positionTicks: Int64, durationTicks: Int64, eligible: Bool) {
        self.entryId = entryId; self.generation = generation; self.prepared = prepared; self.buffering = buffering
        self.unpaused = unpaused; self.suppressed = suppressed; self.positionTicks = positionTicks
        self.durationTicks = durationTicks; self.eligible = eligible
    }
    /// Output is actually advancing: unpaused, loaded, not buffering and not suppressed.
    public var rendering: Bool { unpaused && prepared && !buffering && !suppressed }
}

public protocol SyncRenderer: AnyObject {
    var syncFacts: SyncRendererFacts { get }
    /// Server-origin application. Moves only the native player (nil position keeps it where it is) and never
    /// produces a group request.
    func syncApply(positionTicks: Int64?, playing: Bool)
}

/// The engine asks the app to open the group's current entry at the server's position.
public struct SyncOpenRequest: Equatable, Sendable {
    public let generation: Int64, itemId: String, playlistItemId: String, startTicks: Int64
}

public protocol SyncEngineHost: AnyObject {
    func syncOpen(_ request: SyncOpenRequest)
    /// Something the UI shows changed.
    func syncChanged()
}

/// Whether the server may hold this device in a group. A sent but unanswered Join is `uncertain`.
public enum SyncServerMembership: String, Sendable { case none, uncertain, confirmed }

public enum SyncMembershipIntent: Equatable, Sendable { case create(name: String), join(groupId: String) }

/// SYN-08 registration boundary: a Create/Join waits for the server to attach the current socket to its session.
/// On Jellyfin a valid ForceKeepAlive marks that; a retired connection or an invalid heartbeat cannot.
public struct SyncSocketRegistration: Equatable, Sendable {
    public private(set) var connection: Int64 = 0
    public private(set) var open = false
    public private(set) var registered = false
    public init() {}
    public mutating func opened(_ connection: Int64) { self.connection = connection; open = true; registered = false }
    /// A heartbeat from the current connection proves it is open; only a valid one registers it.
    public mutating func keepAlive(_ connection: Int64, valid: Bool) {
        guard connection == self.connection else { return }
        open = true
        if valid { registered = true }
    }
    public mutating func closed(_ connection: Int64) {
        guard connection == self.connection else { return }
        open = false; registered = false
    }
}

/// Where a transport change came from. Only a server-origin application bypasses the group.
public enum SyncControlOrigin: String, Sendable { case native, viewer, server, remote }

public enum SyncPauseFeedback {
    /// In a group, a pause the server or the wait barrier applied is not the viewer's action: the controls stay as they
    /// are. A viewer's or a remote's own pause is revealed as in solo playback.
    public static func reveals(participating: Bool, origin: SyncControlOrigin) -> Bool {
        !participating || origin == .viewer || origin == .remote
    }
}

public enum SyncSeekEffect: Equatable, Sendable { case native(Int64), group(Int64) }

/// A seek requested before the player can take it waits here with its origin, so a deferred server alignment stays a
/// native bypass and a deferred viewer seek becomes one group request (never both, never an echo).
public struct SyncSeekGate: Equatable, Sendable {
    public private(set) var pendingMillis: Int64?
    private var pendingOrigin: SyncControlOrigin = .viewer
    public init() {}

    public static func effect(millis: Int64, origin: SyncControlOrigin, grouped: Bool) -> SyncSeekEffect {
        origin == .server || !grouped ? .native(millis) : .group(millis)
    }

    public mutating func seek(millis: Int64, origin: SyncControlOrigin, grouped: Bool, ready: Bool) -> SyncSeekEffect? {
        guard ready else { pendingMillis = max(0, millis); pendingOrigin = origin; return nil }
        pendingMillis = nil
        return Self.effect(millis: max(0, millis), origin: origin, grouped: grouped)
    }

    public mutating func becameReady(grouped: Bool) -> SyncSeekEffect? {
        guard let millis = pendingMillis else { return nil }
        pendingMillis = nil
        return Self.effect(millis: millis, origin: pendingOrigin, grouped: grouped)
    }

    public mutating func clear() { pendingMillis = nil }
}

public final class SyncSessionEngine {
    public static let registrationTimeoutMillis: Int64 = 10_000

    private let transport: SyncEngineTransport
    private let clock: SyncEngineClock
    private let scheduler: SyncEngineScheduler
    public weak var host: SyncEngineHost?

    public private(set) var membership = SyncMembership()
    public private(set) var socket = SyncSocketRegistration()
    public private(set) var server: SyncServerMembership = .none
    public private(set) var intent: SyncMembershipIntent?
    /// This device renders, or is opening, the group's current entry. A stayed device is not following.
    public private(set) var following = false
    /// The last IgnoreWait value this device sent.
    public private(set) var ignoreWait = false
    /// The viewer's Wait for me choice, restored when a stayed device rejoins playback.
    public private(set) var waitForMe = true
    /// A Leave was sent and not confirmed; the reminder offers to retry it.
    public private(set) var leavePending = false
    public private(set) var anchor: SyncClockAnchor?
    public private(set) var calibration = SyncClockCalibration()
    public private(set) var lastNotice: String?
    public private(set) var departure: SyncDepartureCause?
    /// Debug counts for SYN-06 evidence; no identities.
    public private(set) var driftSeeks = 0
    public private(set) var appliedCommands = 0
    /// Renderer minus group timeline at the last drift check, in ticks; nil when not measured.
    public private(set) var lastDriftTicks: Int64?

    private weak var renderer: SyncRenderer?
    private struct Pending {
        let command: SyncCommand; let intendedPlaying: Bool; let generation: Int64
        /// A future Unpause accepted while this renderer sat paused, ready and aligned with its base position: timer
        /// lateness at When alone must not seek it (syncplay.md, prepared Unpause).
        var preparedUnpause = false
    }
    private struct Applied { let command: SyncCommand; let playing: Bool }
    private var pending: Pending?
    private var applied: Applied?
    private var timer: SyncScheduled?
    private var registrationTimer: SyncScheduled?
    private var readiness = SyncReadinessState()
    private var drift = SyncDriftState()
    /// A seek this engine made (a server command's position or an autonomous drift correction) is in flight until this
    /// monotonic deadline: the seek and the buffer refill it causes aren't a buffer wait, and the landing reports Ready
    /// once. Reporting Buffering for a commanded seek makes a Waiting server answer with the same Pause, whose seek
    /// reports Buffering again: a loop (OBSERVED live 2026-11-16 after a paused group Seek).
    private var correctionSeekUntil: Int64?
    private var correctionSeekInFlight: Bool {
        get { correctionSeekUntil != nil }
        set {
            correctionTimer?.cancel(); correctionTimer = nil
            correctionSeekUntil = newValue ? clock.monotonicMillis + Self.correctionGraceMillis : nil
        }
    }
    /// How long an engine seek may take (seek plus refill) before a still-unready renderer reports Buffering.
    static let correctionGraceMillis: Int64 = 3_000
    /// A paused renderer within 1 ms of a commanded position is already there.
    static let alignedTicks: Int64 = 10_000
    private var correctionTimer: SyncScheduled?
    /// Learned per renderer: how far ahead a correction seek aims so playback resumes on the group's timeline, bounded.
    public private(set) var seekLeadTicks: Int64 = 0
    private var measureSeekLead = false
    static let maxSeekLeadTicks: Int64 = 5_000_000
    private var stoppedEntryId: String?
    private var openedEntryId: String?
    private var calibrating = false
    private var clockEpoch: Int64 = 0
    private var lastCalibrationFinished: Int64?
    private var resnapshotRequested = false
    private var stagedQueue: (itemId: String, startTicks: Int64)?
    private var terminated = false
    /// A Leave request is on the wire and hasn't been answered.
    private var leaveInFlight = false

    /// The account/token epoch this engine serves. The app builds a new engine, with a new scope, for a different
    /// viewer, a rotated token or a sign-out; consent from an older scope can never act on this one (SYN-10).
    public let scope: Int64
    /// The viewer's pending "leave the group to use AirPlay" consent, if one is open (SYN-10).
    public private(set) var externalRouteConsent: SyncRouteConsent?
    private var externalRoute: (() -> Void)?
    private var consentCounter: Int64 = 0
    /// Bumped by each new consent, cancellation and termination, so an answered Leave starts only its own route.
    private var routeEpoch: Int64 = 0

    public init(transport: SyncEngineTransport, clock: SyncEngineClock, scheduler: SyncEngineScheduler, scope: Int64 = 0) {
        self.transport = transport; self.clock = clock; self.scheduler = scheduler; self.scope = scope
    }

    // MARK: Facts for the UI

    /// In a group, or possibly in one on the server.
    public var participating: Bool { intent != nil || membership.active }
    public var groupName: String? { membership.group?.name }
    public var pendingExternalRoute: SyncRouteConsent? { externalRouteConsent }
    /// The authoritative queue bounds for Previous/Next, only from a current server snapshot.
    public var navigation: SyncNavigationAuthority? {
        guard membership.hasSnapshot, let queue = membership.queue else { return nil }
        return SyncNavigationAuthority(repeatMode: queue.repeatMode, entryIds: queue.entries.map(\.playlistItemId), currentEntryId: queue.current?.playlistItemId)
    }
    public var queueEntries: Int { membership.queue?.entries.count ?? 0 }
    /// Group transport can be requested: a confirmed membership whose queue the server has described.
    public var transportUsable: Bool { participating && intent == nil && membership.hasSnapshot }
    public var rendererBound: Bool { renderer != nil }
    public func isBound(_ candidate: SyncRenderer?) -> Bool { candidate != nil && renderer === candidate }
    public var clockUsable: Bool { anchor != nil }
    public var stayReminder: SyncStayReminder? {
        SyncStay.reminder(SyncStayFacts(groupId: participating ? (membership.groupId ?? membership.group?.id) : nil, groupName: groupName,
                                        phase: membership.phase, queueEntries: membership.queue?.entries.count ?? 0,
                                        playerOpen: renderer != nil, connected: socket.registered, leavePending: leavePending))
    }
    /// A local estimate of this device's sync, never a qualification of other renderers.
    public var quality: SyncQuality {
        guard let renderer, let current = membership.queue?.current else { return .UNKNOWN }
        let facts = renderer.syncFacts
        return SyncPresentation.quality(usableClock: anchor != nil, ready: facts.entryId == current.playlistItemId && facts.prepared && !facts.buffering,
                                        applied: applied != nil && pending == nil, errorTicks: lastDriftTicks, uncertaintyTicks: anchor?.uncertaintyTicks)
    }

    // MARK: Socket

    public func socketOpened(connection: Int64) { socket.opened(connection); host?.syncChanged() }

    public func socketKeepAlive(connection: Int64, valid: Bool) {
        let was = socket.registered
        socket.keepAlive(connection, valid: valid)
        guard !was, socket.registered, !terminated else { return }
        registered()
    }

    /// Network loss pauses this participant; membership, queue and clock are reacquired after registration.
    public func socketClosed(connection: Int64) {
        let was = socket.open || socket.registered
        socket.closed(connection)
        guard was, !socket.open else { return }
        clockEpoch += 1; calibrating = false; anchor = nil
        if membership.groupId != nil && membership.active {
            holdOutput()
            membership = membership.recovering(notice: "Lost connection to the server. Reconnecting…")
            resnapshotRequested = false
        }
        host?.syncChanged()
    }

    private func registered() {
        if intent != nil { sendIntent(); return }
        if participating, let group = membership.groupId ?? membership.group?.id, !resnapshotRequested {
            // Reacquire membership, queue and clock before any output.
            resnapshotRequested = true
            membership = membership.beginningJoin(groupId: group)
            send(try? SyncRequest.join(groupId: group), membershipRequest: true)
        }
        if participating { calibrate() }
        host?.syncChanged()
    }

    // MARK: Membership

    public func create(name: String) { begin(.create(name: name), groupId: nil) }
    public func join(groupId: String) { begin(.join(groupId: groupId), groupId: groupId) }

    /// After creating from a viewing, its title becomes the new group's queue once.
    public func stageQueue(itemId: String, startTicks: Int64) { stagedQueue = (itemId, max(0, startTicks)) }

    private func begin(_ next: SyncMembershipIntent, groupId: String?) {
        guard !terminated else { return }
        registrationTimer?.cancel()
        cancelTimeline()
        membership = membership.beginningJoin(groupId: groupId)
        intent = next
        following = true
        departure = nil
        lastNotice = nil
        resnapshotRequested = true
        if socket.registered { sendIntent() } else {
            let generation = membership.generation
            registrationTimer = scheduler.after(Self.registrationTimeoutMillis) { [weak self] in self?.registrationTimedOut(generation) }
        }
        host?.syncChanged()
    }

    private func sendIntent() {
        registrationTimer?.cancel(); registrationTimer = nil
        guard let intent else { return }
        self.intent = nil
        let request: SyncRequest?
        switch intent {
        case let .create(name): request = try? SyncRequest.create(name: name)
        case let .join(id): request = try? SyncRequest.join(groupId: id)
        }
        send(request, membershipRequest: true)
        calibrate()
    }

    private func registrationTimedOut(_ generation: Int64) {
        guard generation == membership.generation, intent != nil else { return }
        intent = nil
        membership = membership.recovering(notice: "The server didn't accept this device's connection in time. Try again.")
        host?.syncChanged()
    }

    /// Explicit Leave. Cancelling an unsent intent sends nothing unless the server may still hold an earlier group.
    public func leave() { depart(.MANUAL_LEAVE) }

    /// Before an external route takes playback (SYN-10): acts on the open consent if there is one, otherwise leaves.
    public func leaveForExternalRoute() {
        if let consent = externalRouteConsent { leaveForExternalRoute(scope: consent.scope, id: consent.id) } else { depart(.EXTERNAL_ROUTE) }
    }

    /// SYN-10: while grouped, an external route first asks the viewer. Returns false when no consent is needed (not in
    /// a group), so the caller routes at once. A newer request replaces an older open one.
    @discardableResult
    public func confirmExternalRoute(_ route: @escaping () -> Void) -> Bool {
        guard participating, !terminated else { return false }
        consentCounter += 1
        routeEpoch += 1
        externalRouteConsent = SyncRouteConsent(scope: scope, id: consentCounter)
        externalRoute = route
        host?.syncChanged()
        return true
    }

    /// The consent dialog's Cancel, for the consent it rendered. An older dialog can't cancel a newer consent.
    public func cancelExternalRoute(scope: Int64, id: Int64) {
        guard externalRouteConsent == SyncRouteConsent(scope: scope, id: id) else { return }
        externalRouteConsent = nil; externalRoute = nil; routeEpoch += 1
        host?.syncChanged()
    }

    /// The consent dialog's Leave, for the consent it rendered: consumed at once (a repeat press does nothing), then the
    /// ordinary Leave; the route starts only after the server accepted the Leave and this device is out of the group.
    public func leaveForExternalRoute(scope: Int64, id: Int64) {
        guard !terminated, let consent = externalRouteConsent, consent == SyncRouteConsent(scope: scope, id: id), let route = externalRoute else { return }
        externalRouteConsent = nil; externalRoute = nil
        routeEpoch += 1
        let epoch = routeEpoch
        let needsLeave = server != .none
        depart(.EXTERNAL_ROUTE) { [weak self] accepted in
            guard let self, accepted, !self.terminated, epoch == self.routeEpoch, !self.participating else { return }
            route()
        }
        // The viewing stays on screen, paused and no longer the group's renderer.
        renderer = nil
        if !needsLeave, !participating, !terminated, epoch == routeEpoch { route() }
    }

    private func depart(_ cause: SyncDepartureCause?, left: ((Bool) -> Void)? = nil) {
        let wasIn = participating
        registrationTimer?.cancel(); registrationTimer = nil
        intent = nil
        stagedQueue = nil
        holdOutput()
        cancelTimeline()
        following = false; openedEntryId = nil; resnapshotRequested = false
        let leave = server != .none
        if wasIn { departure = cause }
        membership = membership.leaving()
        clockEpoch += 1; calibrating = false
        // After the local state is out of the group, so an answer that arrives at once sees this device as left.
        if leave { sendLeave(left) }
        host?.syncChanged()
    }

    private func sendLeave(_ left: ((Bool) -> Void)? = nil) {
        leavePending = true
        leaveInFlight = true
        let epoch = clockEpoch
        transport.post(SyncRequest.leave()) { [weak self] outcome in
            guard let self else { return }
            self.leaveInFlight = false
            defer { left?(outcome == .ok) }
            if outcome == .ok || outcome.definitelyRefused {
                self.leavePending = false
                if self.membership.groupId == nil, self.intent == nil { self.server = .none }
            }
            _ = epoch
            self.host?.syncChanged()
        }
    }

    /// Sign-out or account change: Leave once, keep nothing.
    public func terminate() {
        guard !terminated else { return }
        // A Leave already on the wire covers this one; a failed one is retried here.
        if server != .none, !leaveInFlight { transport.post(SyncRequest.leave()) { _ in } }
        externalRouteConsent = nil; externalRoute = nil; routeEpoch += 1
        registrationTimer?.cancel(); timer?.cancel()
        holdOutput()
        terminated = true
        intent = nil; server = .none; following = false; renderer = nil; pending = nil; applied = nil
        membership = membership.leaving()
        clockEpoch += 1
    }

    // MARK: Server messages

    public func receive(_ text: String) {
        guard !terminated, let event = SyncPlay.parseMessage(text) else { return }
        let before = membership
        if case let .denied(_, _, reason) = event, before.active, before.groupId == nil || event.groupId == nil || event.groupId == before.groupId {
            if ["NotInGroup", "GroupDoesNotExist", "CreateGroupDenied", "JoinGroupDenied"].contains(reason) { server = .none }
            if SyncStay.endsOnDenial(reason: reason, playerOpen: renderer != nil) || (["CreateGroupDenied", "JoinGroupDenied"].contains(reason) && before.groupId == nil) {
                let name = before.group?.name
                holdOutput(); cancelTimeline()
                following = false; openedEntryId = nil
                membership = before.leaving()
                membership.notice = reason.hasSuffix("Denied") ? "This viewer is not allowed to create or join this group." : nil
                lastNotice = membership.notice
                departure = reason.hasSuffix("Denied") ? nil : .MEMBERSHIP_LOST
                _ = name
                host?.syncChanged()
                return
            }
        }
        let next = before.reduce(event, generation: before.generation)
        guard next != before else { return }
        membership = next
        lastNotice = next.notice
        switch event {
        case .joined:
            server = .confirmed
            resnapshotRequested = false
            if !following && !ignoreWait {
                // A stayed device that rejoined has no player: it must not hold the group's wait barrier.
                ignoreWait = true
                send(SyncRequest.ignoreWait(true))
            }
        case .left:
            server = .none
            holdOutput(); cancelTimeline()
            following = false; openedEntryId = nil
            departure = .MEMBERSHIP_LOST
        default: break
        }
        if next.phase == "Reconnecting" && before.phase != "Reconnecting" {
            holdOutput(); cancelTimeline(); resnapshotRequested = false
            if socket.registered, let group = next.groupId { resnapshotRequested = true; send(try? SyncRequest.join(groupId: group), membershipRequest: true) }
        }
        if next.queue?.current?.playlistItemId != before.queue?.current?.playlistItemId {
            readiness = SyncReadinessState()
            if next.queue?.current?.playlistItemId != stoppedEntryId { stoppedEntryId = nil }
        }
        if let command = next.command { accept(command, phaseBefore: before.phase) }
        else if pending != nil && next.phase != before.phase { schedule() }
        if let staged = stagedQueue, server == .confirmed, next.hasSnapshot, next.queue?.entries.isEmpty == true {
            stagedQueue = nil
            send(try? SyncRequest.newQueue(items: [staged.itemId], startPositionTicks: staged.startTicks))
        }
        openCurrentIfFollowing()
        evaluateReadiness()
        host?.syncChanged()
    }

    private func openCurrentIfFollowing() {
        guard following, SyncStay.opensGroupEntry(following: following), membership.hasSnapshot, membership.phase != "Reconnecting",
              let queue = membership.queue, let current = queue.current, current.playlistItemId != openedEntryId else { return }
        if let facts = renderer?.syncFacts, facts.entryId == current.playlistItemId, facts.generation == membership.generation { openedEntryId = current.playlistItemId; return }
        openedEntryId = current.playlistItemId
        host?.syncOpen(SyncOpenRequest(generation: membership.generation, itemId: current.itemId, playlistItemId: current.playlistItemId, startTicks: queue.startPositionTicks))
    }

    // MARK: Commands

    private func accept(_ command: SyncCommand, phaseBefore: String) {
        if command.command == "Stop" {
            cancelTimeline()
            stoppedEntryId = command.playlistItemId
            if let renderer, renderer.syncFacts.entryId == command.playlistItemId { renderer.syncApply(positionTicks: nil, playing: false) }
            return
        }
        stoppedEntryId = nil
        if let applied, applied.playing, SyncTiming.sameUnpauseTimeline(applied: applied.command, received: command) {
            // An equivalent fresh emission of an applied Unpause restores play mode without a new seek.
            pending = nil; timer?.cancel()
            if let renderer, membership.phase == "Playing", !renderer.syncFacts.unpaused { renderer.syncApply(positionTicks: nil, playing: true) }
            return
        }
        let intended = command.command == "Unpause" || (command.command == "Seek" && phaseBefore == "Playing")
        pending = Pending(command: command, intendedPlaying: intended, generation: membership.generation)
        if command.command == "Unpause", let renderer, let now = serverNow() {
            let facts = renderer.syncFacts
            pending?.preparedUnpause = facts.entryId == command.playlistItemId && SyncTiming.preparedUnpause(
                command: command.command, acceptedFuture: command.whenMs * SyncMath.ticksPerMillisecond > now, ready: facts.prepared,
                buffering: facts.buffering, unpaused: facts.unpaused, positionApplicationPending: false,
                currentTicks: facts.positionTicks, baseTicks: command.positionTicks ?? -1)
        }
        applied = nil
        schedule()
    }

    private func serverNow() -> Int64? {
        guard let anchor else { return nil }
        guard let now = anchor.serverNowTicks(wallMillis: clock.wallMillis, monotonicMillis: clock.monotonicMillis) else {
            // A wall-clock jump invalidates calibration; output stays held until a fresh anchor.
            self.anchor = nil; calibrate(); return nil
        }
        return now
    }

    private var durationBound: Int64 {
        if let facts = renderer?.syncFacts, facts.durationTicks > 0 { return facts.durationTicks }
        return Int64.max / 4
    }

    private func schedule() {
        timer?.cancel(); timer = nil
        guard let pending, let now = serverNow() else { return }
        let playing = SyncTiming.scheduledPlaying(intendedPlaying: pending.intendedPlaying, phase: membership.phase)
        guard let target = SyncTiming.target(positionTicks: pending.command.positionTicks ?? 0, whenUtcMillis: pending.command.whenMs,
                                             serverNowTicks: now, playing: playing, durationTicks: durationBound) else { return }
        if target.delayMillis > 0 {
            timer = scheduler.after(target.delayMillis) { [weak self] in self?.execute() }
        } else {
            execute()
        }
    }

    /// Applies the pending command at its When if the renderer for its entry is prepared; otherwise it stays pending
    /// and `rendererChanged` retries it.
    private func execute() {
        guard let pending, pending.generation == membership.generation, following, let renderer else { return }
        let facts = renderer.syncFacts
        guard facts.entryId == pending.command.playlistItemId, facts.prepared, let now = serverNow() else { return }
        let playing = SyncTiming.scheduledPlaying(intendedPlaying: pending.intendedPlaying, phase: membership.phase)
        guard let target = SyncTiming.target(positionTicks: pending.command.positionTicks ?? 0, whenUtcMillis: pending.command.whenMs,
                                             serverNowTicks: now, playing: playing, durationTicks: durationBound) else { return }
        if target.delayMillis > 0 { schedule(); return }
        let stillPrepared = pending.preparedUnpause && playing && !facts.buffering && !facts.unpaused
        let scheduled = stillPrepared ? nil
            : SyncTiming.scheduledPosition(command: playing ? pending.command.command : "Pause", currentTicks: facts.positionTicks, targetTicks: target.positionTicks)
        // A paused player already holding the target issues no seek (syncplay.md: an aligned scheduled command that
        // issues no renderer seek). A Paused server answers each Ready with a Pause at the group position; seeking for
        // it would report Ready again and loop (OBSERVED live 2026-11-16).
        let aligned = !playing && !facts.unpaused && !facts.buffering && scheduled.map { abs($0 - facts.positionTicks) <= Self.alignedTicks } == true
        let position = aligned ? nil : scheduled
        // State first: the renderer reports its changes back synchronously, and those reports must see this command as
        // applied rather than still due.
        appliedCommands += 1
        applied = Applied(command: pending.command, playing: playing)
        // A playing intent held by Waiting stays pending until the barrier lifts.
        self.pending = pending.intendedPlaying && !playing ? pending : nil
        drift = SyncTiming.transportApplied(drift, nowMonotonicMillis: clock.monotonicMillis, positionChanged: position != nil)
        readiness = readiness.afterPositionApplied(changed: position != nil)
        correctionSeekInFlight = position != nil
        lastDriftTicks = nil
        renderer.syncApply(positionTicks: position, playing: playing)
        host?.syncChanged()
    }

    private func cancelTimeline() {
        timer?.cancel(); timer = nil
        pending = nil; applied = nil
        correctionSeekInFlight = false
        drift = SyncTiming.stoppedDrift()
        readiness = SyncReadinessState()
        lastDriftTicks = nil
    }

    private func holdOutput() {
        if let renderer, renderer.syncFacts.unpaused { renderer.syncApply(positionTicks: nil, playing: false) }
    }

    // MARK: Renderer

    /// A renderer opened for the group's entry binds here; binding resumes following.
    public func bind(_ next: SyncRenderer) {
        guard participating, !terminated else { return }
        renderer = next
        following = true
        seekLeadTicks = 0; measureSeekLead = false
        readiness = SyncReadinessState()
        if let entry = next.syncFacts.entryId { openedEntryId = entry }
        if ignoreWait && waitForMe { ignoreWait = false; send(SyncRequest.ignoreWait(false)) }
        rendererChanged()
    }

    /// The renderer was replaced by a newer group entry; this is not the viewer closing playback.
    public func unbind(_ old: SyncRenderer) {
        guard renderer === old else { return }
        renderer = nil
        readiness = SyncReadinessState()
    }

    /// The viewer closed the player (SYN-08): stay, or leave for an external route or sign-out.
    @discardableResult
    public func closeViewing(_ old: SyncRenderer?, reason: SyncCloseReason) -> SyncCloseDecision {
        if let old, renderer !== old, reason != .MANUAL_LEAVE, reason != .EXTERNAL_ROUTE { return SyncCloseDecision(.NONE) }
        let decision = SyncStay.closeDecision(reason, participating: participating, following: following)
        switch decision.outcome {
        case .NONE: break
        case .STAY:
            holdOutput()
            renderer = nil
            cancelTimeline()
            following = false; openedEntryId = nil
            if decision.ignoreWait && !ignoreWait && server != .none { ignoreWait = true; send(SyncRequest.ignoreWait(true)) }
        case .LEAVE:
            if reason == .SIGN_OUT { terminate() } else { depart(decision.departure) }
            renderer = nil
        }
        host?.syncChanged()
        return decision
    }

    /// The reminder's Watch: open the group's current entry again.
    public func watch() {
        guard participating, !following else { return }
        following = true
        openedEntryId = nil
        openCurrentIfFollowing()
        host?.syncChanged()
    }

    /// The renderer's facts changed (loaded, buffering, paused, sought): retry a held command and report readiness.
    public func rendererChanged() {
        guard renderer != nil else { return }
        if pending != nil, timer == nil { schedule() }
        evaluateReadiness()
    }

    private func evaluateReadiness() {
        guard following, server != .none, let renderer, let entry = membership.queue?.current?.playlistItemId,
              !["Joining", "Reconnecting", "Leaving", "Error"].contains(membership.phase),
              SyncReadiness.reports(phase: membership.phase, currentEntryId: entry, stoppedEntryId: stoppedEntryId) else { return }
        let facts = renderer.syncFacts
        guard facts.entryId == entry, facts.eligible, let now = serverNow() else { return }
        let due = pending.map { $0.command.whenMs * SyncMath.ticksPerMillisecond <= now && $0.command.playlistItemId == entry } ?? false
        if let until = correctionSeekUntil {
            // A correction seek that hasn't landed isn't a buffer wait (SYN-06 counts them apart); reporting Buffering
            // here would pause the whole group after every correction. A stall that outlasts the grace still reports.
            if !(facts.prepared && !facts.buffering) && clock.monotonicMillis < until {
                if correctionTimer == nil {
                    correctionTimer = scheduler.after(until - clock.monotonicMillis) { [weak self] in
                        self?.correctionTimer = nil; self?.rendererChanged()
                    }
                }
                return
            }
            correctionSeekInFlight = false
        }
        let ready = facts.prepared && !facts.buffering && !due
        let (state, report) = readiness.observe(entryId: entry, ready: ready)
        readiness = state
        guard report else { return }
        let millis = now >= 0 ? now / SyncMath.ticksPerMillisecond : (now - 9_999) / SyncMath.ticksPerMillisecond
        send(try? SyncRequest.readiness(ready: ready, whenMs: millis, positionTicks: max(0, facts.positionTicks), isPlaying: facts.unpaused, playlistItemId: entry))
    }

    /// Periodic work while a viewing is bound: clock refresh and SYN-05 drift correction (seek mode).
    public func tick() {
        guard !terminated else { return }
        if participating, socket.registered, !calibrating,
           SyncTiming.clockRefreshDue(anchor: anchor, lastAttemptFinishedMillis: lastCalibrationFinished, monotonicMillis: clock.monotonicMillis) {
            calibrate()
        }
        guard following, membership.phase == "Playing", pending == nil, let applied, applied.playing, let renderer else { return }
        let facts = renderer.syncFacts
        guard facts.entryId == applied.command.playlistItemId, facts.rendering, let now = serverNow() else { return }
        guard let target = SyncTiming.target(positionTicks: applied.command.positionTicks ?? 0, whenUtcMillis: applied.command.whenMs,
                                             serverNowTicks: now, playing: true, durationTicks: durationBound) else { return }
        lastDriftTicks = facts.positionTicks - target.positionTicks
        if measureSeekLead { // `rendering` above: the correction seek has landed
            // What the last correction left behind is this renderer's seek-to-output latency: lead the next one by it.
            measureSeekLead = false
            seekLeadTicks = min(max(seekLeadTicks - (lastDriftTicks ?? 0) / 2, 0), Self.maxSeekLeadTicks)
        }
        // Apple: seek correction. AVPlayer exposes a rate setter but not proof that 0.97/1.03 reach the output exactly.
        let decision = SyncTiming.drift(state: drift, currentTicks: facts.positionTicks, targetTicks: target.positionTicks, playing: true,
                                        nowMonotonicMillis: clock.monotonicMillis, durationTicks: durationBound, sourceAllowsRate: false, engineRates: [])
        drift = decision.state
        if decision.action == .SEEK {
            driftSeeks += 1
            readiness = readiness.afterPositionApplied(changed: true)
            correctionSeekInFlight = true
            measureSeekLead = true
            renderer.syncApply(positionTicks: min(decision.targetTicks + seekLeadTicks, durationBound), playing: true)
        }
    }

    // MARK: Viewer requests (SYN-03): the group moves first; the local player waits for the server's command.

    public func requestPause() { send(try? SyncRequest.transport("Pause")) }
    public func requestUnpause() { send(try? SyncRequest.transport("Unpause")) }
    public func requestSeek(ticks: Int64) { send(try? SyncRequest.transport("Seek", positionTicks: max(0, ticks))) }
    public func requestNext() {
        guard let entry = membership.queue?.current?.playlistItemId,
              SyncPresentation.directionAvailable(usable: transportUsable, queueEntries: queueEntries, previous: false, navigation: navigation) else { return }
        send(try? SyncRequest.transport("NextItem", playlistItemId: entry))
    }
    public func requestPrevious() {
        guard let entry = membership.queue?.current?.playlistItemId,
              SyncPresentation.directionAvailable(usable: transportUsable, queueEntries: queueEntries, previous: true, navigation: navigation) else { return }
        send(try? SyncRequest.transport("PreviousItem", playlistItemId: entry))
    }
    /// A title chosen while in a group becomes the group's queue.
    public func requestQueue(itemId: String, startTicks: Int64) { send(try? SyncRequest.newQueue(items: [itemId], startPositionTicks: max(0, startTicks))) }
    public func setWaitForMe(_ wait: Bool) {
        waitForMe = wait
        guard server != .none, ignoreWait == wait else { return }
        ignoreWait = !wait
        send(SyncRequest.ignoreWait(!wait))
        host?.syncChanged()
    }

    /// Natural end of the bound renderer (SYN-09): the server owns continuation. `current` is false for an obsolete
    /// playback decision, which can send nothing. Returns FINISH when the caller should close with NATURAL_END.
    public func naturalCompletion(_ ended: SyncRenderer, current: Bool, unpaused: Bool) -> SyncCompletionAction {
        guard current, renderer === ended, let queue = membership.queue, let entry = queue.current else { return .HOLD }
        let facts = ended.syncFacts
        let action = SyncCompletion.action(queue: queue, hasSnapshot: membership.hasSnapshot,
                                           rendererMatches: facts.entryId == entry.playlistItemId && facts.generation == membership.generation, unpaused: unpaused)
        switch action {
        case .NEXT: send(try? SyncRequest.transport("NextItem", playlistItemId: entry.playlistItemId))
        case .REPEAT: send(try? SyncRequest.select(playlistItemId: entry.playlistItemId))
        case .FINISH, .HOLD: break
        }
        return action
    }

    // MARK: Clock

    private func calibrate() {
        guard !calibrating, !terminated else { return }
        calibrating = true
        let epoch = clockEpoch
        var remaining = 5
        var window = calibration
        func sample() {
            let t0 = clock.wallMillis
            transport.utcTime { [weak self] reply in
                guard let self, epoch == self.clockEpoch else { return }
                let t3 = self.clock.wallMillis
                if let reply { window = window.adding(SyncClockSample(t0Millis: t0, t1Millis: reply.receivedMillis, t2Millis: reply.transmittedMillis, t3Millis: t3)) }
                remaining -= 1
                if remaining > 0 { sample() } else { self.calibrated(window) }
            }
        }
        sample()
    }

    private func calibrated(_ window: SyncClockCalibration) {
        calibrating = false
        calibration = window
        lastCalibrationFinished = clock.monotonicMillis
        anchor = window.anchor(wallMillis: clock.wallMillis, monotonicMillis: clock.monotonicMillis)
        if let best = window.best { send(try? SyncRequest.ping(roundTripMs: best.networkRttMillis)) }
        if anchor == nil { lastNotice = "Can't measure the server's clock. Playback stays paused." }
        if pending != nil { schedule() }
        evaluateReadiness()
        host?.syncChanged()
    }

    // MARK: Requests

    private func send(_ request: SyncRequest?, membershipRequest: Bool = false) {
        guard let request, !terminated else { return }
        let generation = membership.generation
        if membershipRequest && server == .none { server = .uncertain }
        let prior = server
        transport.post(request) { [weak self] outcome in
            guard let self, generation == self.membership.generation, !self.terminated else { return }
            guard membershipRequest else { return }
            if outcome.definitelyRefused {
                // A definite refusal created no membership.
                if prior != .confirmed { self.server = .none }
                self.membership.phase = "Error"
                self.membership.notice = outcome == .http(403) ? "This viewer is not allowed to create or join this group." : "The server refused the group request."
                self.lastNotice = self.membership.notice
                self.host?.syncChanged()
            }
        }
    }
}
