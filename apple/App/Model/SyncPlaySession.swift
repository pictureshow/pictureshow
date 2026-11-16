// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Combine
import Foundation
import os
import PictureshowCore

/// Watch together (SyncPlay, spec/features/syncplay.md) for the selected viewer.
///
/// The shared `SyncSessionEngine` owns the rules: registration before membership requests, server-scheduled commands,
/// readiness, drift correction and the stay-in-group rule. This type binds it to the app: the viewer's socket and
/// HTTP client, the main-actor clock and timers, the player it opens, and what the screens show.
@MainActor
final class SyncPlaySession: ObservableObject {
    /// Groups the server listed for this viewer; empty until a refresh.
    @Published private(set) var groups: [SyncGroup] = []
    @Published private(set) var listing = false
    @Published private(set) var listFailed = false
    /// SyncPlayAccess for this viewer; nil until the server answered.
    @Published private(set) var policy: String?
    @Published private(set) var policyFailed = false
    /// Bumped whenever the engine reports a change, so views reading `engine` redraw.
    @Published private(set) var revision = 0

    private weak var appModel: AppModel?
    private(set) var engine: SyncSessionEngine
    private var bridge: SyncAppBridge
    private var accountKey: String?
    private var subscription: ServerSocket.Subscription?
    private var refresher: Task<Void, Never>?
    private let log = Logger(subsystem: "app.pictureshow", category: "syncplay")

    init(app: AppModel) {
        appModel = app
        bridge = SyncAppBridge(account: nil, client: nil)
        engine = SyncSessionEngine(transport: bridge, clock: bridge, scheduler: bridge)
        subscription = app.socket.subscribe(["SyncPlayCommand", "SyncPlayGroupUpdate"]) { [weak self] _, text, _ in self?.engine.receive(text) }
        app.socket.onLifecycle.append { [weak self] event in self?.socketEvent(event) }
        accountChanged()
        refresher = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                if self.appModel?.activeController == nil { self.engine.tick() }
            }
        }
    }

    deinit { refresher?.cancel() }

    // MARK: Account

    /// A different viewer (or a sign-out) leaves the old group once with the old credentials and starts clean.
    func accountChanged() {
        guard let app = appModel else { return }
        let next = app.account.flatMap { $0.isSignedIn ? $0 : nil }
        guard next?.key != accountKey || next?.accessToken != bridge.account?.accessToken else { return }
        engine.terminate()
        accountKey = next?.key
        bridge = SyncAppBridge(account: next, client: next.map { app.client(for: $0) })
        engine = SyncSessionEngine(transport: bridge, clock: bridge, scheduler: bridge)
        engine.host = bridge
        bridge.session = self
        groups = []; policy = nil; policyFailed = false; listFailed = false
        if next != nil {
            let socket = app.socket
            if socket.state == .open {
                engine.socketOpened(connection: socket.connection)
                if socket.registration.registered { engine.socketKeepAlive(connection: socket.connection, valid: true) }
            }
        }
        changed()
    }

    /// For tests: the same engine wiring with a fake transport, clock and scheduler in place of the server.
    func installForTesting(_ platform: SyncEngineTransport & SyncEngineClock & SyncEngineScheduler) {
        engine.terminate()
        engine = SyncSessionEngine(transport: platform, clock: platform, scheduler: platform)
        engine.host = bridge
        bridge.session = self
        changed()
    }

    /// Sign-out of `account`: Leave goes out before the session's token is revoked. Returns the request chain to await
    /// before logging out.
    func signingOut(_ account: Account) -> Task<Void, Never>? {
        guard account.key == accountKey else { return nil }
        if let controller = appModel?.activeController { engine.closeViewing(controller.syncRenderer, reason: .SIGN_OUT) }
        engine.terminate()
        return bridge.pending
    }

    private func socketEvent(_ event: ServerSocket.Lifecycle) {
        guard accountKey != nil else { return }
        switch event {
        case let .opened(id): engine.socketOpened(connection: id)
        case let .keepAlive(id, valid): engine.socketKeepAlive(connection: id, valid: valid)
        case let .closed(id): engine.socketClosed(connection: id)
        }
    }

    // MARK: What the screens read

    var participating: Bool { engine.participating }
    var groupName: String? { engine.groupName }
    var phase: SyncPhase { SyncPresentation.phase(engine.membership.phase, reason: nil) }
    var participants: [String] { engine.membership.group?.participants ?? [] }
    var notice: String? { engine.membership.notice ?? engine.lastNotice }
    var stayReminder: SyncStayReminder? { engine.stayReminder }
    var quality: SyncQuality { engine.quality }
    var access: SyncAccessPresentation {
        SyncAccessPresentation(policy: policy ?? "None", known: policy != nil, busy: listing, failed: policyFailed)
    }
    var waitForMe: Bool { engine.waitForMe }

    // MARK: Viewer actions

    /// Policy and groups for the chooser. A failed policy check keeps Create and Join closed.
    func refresh() async {
        guard let app = appModel, let account = bridge.account else { return }
        let key = account.key
        listing = true
        defer { if accountKey == key { listing = false } }
        let client = app.client(for: account)
        do {
            let access = try await client.syncPlayAccess(account: account)
            guard accountKey == key else { return }
            policy = access; policyFailed = false
        } catch {
            guard accountKey == key else { return }
            policy = nil; policyFailed = true; groups = []
            return
        }
        do {
            let listed = try await client.syncPlayGroups(account: account)
            guard accountKey == key else { return }
            groups = listed; listFailed = false
        } catch {
            guard accountKey == key else { return }
            groups = []; listFailed = true
        }
    }

    /// Creates a group; when started from a title, that title becomes the group's queue at `startTicks`.
    func create(name: String, item: MediaItem? = nil, startTicks: Int64 = 0) {
        engine.create(name: name)
        if let item { engine.stageQueue(itemId: item.id, startTicks: startTicks) }
        changed()
    }

    func join(_ group: SyncGroup) { engine.join(groupId: group.id); changed() }

    func leave() {
        if let controller = appModel?.activeController, controller.syncRenderer != nil {
            engine.closeViewing(controller.syncRenderer, reason: .MANUAL_LEAVE)
        } else {
            engine.leave()
        }
        changed()
    }

    /// The reminder's Watch: open the group's current entry again.
    func watch() { engine.watch(); changed() }

    func setWaitForMe(_ wait: Bool) { engine.setWaitForMe(wait); changed() }

    /// A title chosen while in a group becomes the group's queue (SYN-03); nothing plays here until the server says so.
    func queue(_ item: MediaItem, fromStart: Bool, startTicks: Int64? = nil) {
        engine.requestQueue(itemId: item.id, startTicks: startTicks ?? (fromStart ? 0 : item.userData.playbackPositionTicks))
    }

    func changed() { revision &+= 1 }

    /// Debug-log summary of the bound renderer: prepared/buffering/unpaused flags and drift-correction count.
    func debugFacts() -> String {
        guard let controller = appModel?.activeController, controller.syncRenderer != nil else { return "no-renderer" }
        let f = controller.syncFacts
        return "prepared=\(f.prepared) buffering=\(f.buffering) unpaused=\(f.unpaused) pos=\(f.positionTicks / 10_000)ms driftSeeks=\(engine.driftSeeks) drift=\((engine.lastDriftTicks ?? 0) / 10_000)ms"
    }

    // MARK: Opening the group's entry

    fileprivate func open(_ request: SyncOpenRequest) {
        guard let app = appModel, let account = bridge.account, let client = bridge.client else { return }
        Task { [weak self, weak app] in
            do {
                let item = try await client.item(account: account, id: request.itemId)
                guard let self, let app, self.engine.membership.generation == request.generation, self.accountKey == account.key,
                      self.engine.following, self.engine.membership.queue?.current?.playlistItemId == request.playlistItemId else { return }
                // AUTH-16: the group's entry never opens behind the PIN lock.
                guard app.pastPinGate(account) else { self.log.info("group entry held: viewer locked"); return }
                if let current = app.activeController {
                    if current.sync?.playlistItemId == request.playlistItemId && current.sync?.generation == request.generation { return }
                    current.closeReplacedBySync()
                }
                app.player = PlayerRequest(account: account, item: item, fromStart: request.startTicks == 0, startTicks: request.startTicks,
                                           sync: SyncStamp(generation: request.generation, playlistItemId: request.playlistItemId))
            } catch {
                self?.log.error("group entry could not be opened")
            }
        }
    }
}

/// Which group entry a viewing was opened for.
struct SyncStamp: Equatable {
    let generation: Int64
    let playlistItemId: String
}

/// The engine's platform: the viewer's HTTP client, the wall and monotonic clocks, main-queue timers, and the host
/// callbacks. Everything runs on the main actor; requests are sent one at a time, in the order the engine made them.
final class SyncAppBridge: SyncEngineTransport, SyncEngineClock, SyncEngineScheduler, SyncEngineHost {
    let account: Account?
    let client: JellyfinClient?
    weak var session: SyncPlaySession?
    private var chain: Task<Void, Never>?
    /// The last request in flight; sign-out waits for it so Leave reaches the server before the token is revoked.
    var pending: Task<Void, Never>? { chain }

    init(account: Account?, client: JellyfinClient?) { self.account = account; self.client = client }
    private static let log = Logger(subsystem: "app.pictureshow", category: "syncplay")

    func post(_ request: SyncRequest, completion: @escaping (SyncPostOutcome) -> Void) {
        guard let account, let client else { completion(.failed); return }
        // Fixed fields only (no identities): which request, and the renderer facts that led to it.
        let facts = MainActor.assumeIsolated { session?.debugFacts() } ?? "-"
        Self.log.debug("request \(request.operation, privacy: .public) \(facts, privacy: .public)")
        let previous = chain
        chain = Task { @MainActor in
            await previous?.value
            let outcome = await client.syncPlay(account: account, request)
            completion(outcome)
        }
    }

    func utcTime(completion: @escaping ((receivedMillis: Int64, transmittedMillis: Int64)?) -> Void) {
        guard let account, let client else { completion(nil); return }
        Task { @MainActor in
            let reply = try? await client.serverUtcTime(account: account)
            completion(reply)
        }
    }

    var wallMillis: Int64 { Int64((Date().timeIntervalSince1970 * 1000).rounded()) }
    var monotonicMillis: Int64 { Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000) }

    final class Timer: SyncScheduled {
        var item: DispatchWorkItem?
        func cancel() { item?.cancel(); item = nil }
    }

    func after(_ millis: Int64, _ work: @escaping () -> Void) -> SyncScheduled {
        let timer = Timer()
        let item = DispatchWorkItem(block: work)
        timer.item = item
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(max(0, min(millis, 86_400_000)))), execute: item)
        return timer
    }

    func syncOpen(_ request: SyncOpenRequest) {
        MainActor.assumeIsolated { session?.open(request) }
    }

    func syncChanged() {
        MainActor.assumeIsolated { session?.changed() }
    }
}
