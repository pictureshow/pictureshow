// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// This phone or tablet as a remote for one of the viewer's TVs (REM-01, REM-03, REM-04, REM-06).
///
/// The phone stays a controller: nothing plays here. What the screen shows comes from what the server says the TV is
/// doing (polled while attached), never from what was sent, so a command only shows as done when the TV reports it.
/// A TV whose session leaves the list, or comes back under a new session, is `lost`: it must be chosen again on
/// purpose, and nothing is queued for it meanwhile. Disconnect leaves the TV playing; Stop stops it.
@MainActor
final class RemoteControl: ObservableObject {
    enum Link: Equatable { case none, attached, lost }

    @Published private(set) var link: Link = .none
    /// The attached TV as last observed; its `now` is what the remote shows.
    @Published private(set) var target: RemotePlaybackTarget?
    /// One line about the last command ("Sending…", "Couldn't reach …"); nil when there is nothing to say.
    @Published private(set) var status: String?
    @Published var choosing = false
    @Published var showingRemote = false
    /// A title to start on whichever TV the viewer picks next.
    private(set) var pendingPlay: (item: MediaItem, ticks: Int64?)?

    private weak var appModel: AppModel?
    private var app: AppModel! { appModel }
    private var accountKey: String?
    private var session: String?
    private var device: String?
    private var poller: Task<Void, Never>?
    private var awaiting: (item: String, since: Date)?

    init(app: AppModel) { self.appModel = app }

    var now: [String: Any]? { target?.reducerValue["now"] as? [String: Any] }
    var commands: [String] { target?.reducerValue["commands"] as? [String] ?? [] }
    var targetName: String { target?.name ?? "TV" }

    /// The TV's account, shown as supporting context only when it isn't this viewer.
    var otherViewerName: String? { target?.sameViewer == false ? target?.viewerName : nil }

    // MARK: Choosing a TV

    func choose(playing item: MediaItem? = nil, ticks: Int64? = nil) {
        pendingPlay = item.map { ($0, ticks) }
        guard showingRemote else { choosing = true; return }
        showingRemote = false
        Task { try? await Task.sleep(nanoseconds: 450_000_000); choosing = true }
    }

    /// Detail's Play on: straight to the attached TV, otherwise pick one first.
    func playOn(_ item: MediaItem, ticks: Int64? = nil) {
        if link == .attached { play(item, ticks: ticks); showingRemote = true } else { choose(playing: item, ticks: ticks) }
    }

    func targets() async throws -> [RemotePlaybackTarget] {
        guard let account = app.account else { return [] }
        return try await app.client(for: account).remotePlaybackTargets(account: account)
    }

    func attach(_ chosen: RemotePlaybackTarget) {
        guard let account = app.account else { return }
        accountKey = account.key
        session = chosen.id
        device = chosen.reducerValue["device"] as? String
        target = chosen
        link = .attached
        status = nil
        choosing = false
        startPolling()
        if let pending = pendingPlay {
            pendingPlay = nil
            play(pending.item, ticks: pending.ticks)
        }
        showingRemote = true
    }

    /// Leaves the TV playing and stops controlling it (REM-06).
    func disconnect() {
        poller?.cancel(); poller = nil
        link = .none; target = nil; session = nil; device = nil; status = nil; awaiting = nil
        showingRemote = false
    }

    /// Account switch or sign-out detaches at once (REM-07).
    func accountChanged() {
        if link != .none, app.account?.key != accountKey || app.account?.isSignedIn != true { disconnect() }
    }

    // MARK: Commands

    /// Starts a title on the TV (REM-03). The TV opens it at `ticks`, or at the viewer's place when nil.
    func play(_ item: MediaItem, ticks: Int64?) {
        let ticks = ticks ?? (item.userData.playbackPositionTicks > 0 ? item.userData.playbackPositionTicks : nil)
        awaiting = (RemoteTarget.compact(item.id), Date())
        send("play-now", itemID: item.id, ticks: ticks, sending: "Starting \(item.name) on \(targetName)…")
    }

    func togglePause() { send(paused ? "play" : "pause") }
    func stop() { send("stop") }
    func next() { send("next") }
    func previous() { send("previous") }
    func seek(toSeconds seconds: Double) {
        let bounded = max(0, durationSeconds > 0 ? min(seconds, durationSeconds - 1) : seconds)
        send("seek", ticks: Ticks.from(seconds: bounded))
    }
    func skip(by seconds: Double) { seek(toSeconds: positionSeconds + seconds) }
    func audio(_ index: Int) { send("audio", index: index) }
    func subtitle(_ index: Int) { send("subtitle", index: index) }
    func volume(_ level: Int) { send("volume", level: level) }

    var paused: Bool { now?["paused"] as? Bool ?? true }
    var canSeek: Bool { now?["canSeek"] as? Bool ?? false }
    var positionSeconds: Double { Double(Int64(now?["positionTicks"] as? String ?? "0") ?? 0) / 10_000_000 }
    var durationSeconds: Double { Double(Int64(now?["durationTicks"] as? String ?? "0") ?? 0) / 10_000_000 }
    var tracks: [[String: Any]] { now?["tracks"] as? [[String: Any]] ?? [] }
    var supportsVolume: Bool { commands.contains("SetVolume") && !(now?["volume"] is NSNull) }

    private func send(_ op: String, itemID: String? = nil, ticks: Int64? = nil, index: Int? = nil, level: Int? = nil, sending: String? = nil) {
        guard link == .attached, let target, let account = app.account else { return }
        if let sending { status = sending }
        let client = app.client(for: account), name = target.name
        Task {
            do {
                let outcome = try await client.sendRemotePlayback(account: account, target: target, operation: op, itemID: itemID, ticks: ticks, index: index, level: level)
                if !outcome.succeeded { status = "\(name) didn't take that\(outcome.status.map { " (\($0))" } ?? "")."; awaiting = nil }
                else if awaiting == nil { status = nil }
            } catch {
                status = "Couldn't reach \(name). Check that Pictureshow is open on it."
                awaiting = nil
            }
            try? await Task.sleep(nanoseconds: 600_000_000)
            await refresh()
        }
    }

    // MARK: Observing the TV

    private func startPolling() {
        poller?.cancel()
        poller = Task { [weak self] in
            while !Task.isCancelled {
                await self?.refresh()
                try? await Task.sleep(nanoseconds: 2_000_000_000)
            }
        }
    }

    func refresh() async {
        guard link == .attached, let session else { return }
        guard let list = try? await targets(), link == .attached, self.session == session else { return }
        guard let current = list.first(where: { $0.id == session }),
              device == nil || current.reducerValue["device"] as? String == device else {
            // Gone, or back under a new session: choose it again on purpose (REM-06).
            link = .lost; status = nil; awaiting = nil
            poller?.cancel(); poller = nil
            return
        }
        target = current
        if let wait = awaiting {
            if (now?["item"] as? String) == wait.item { awaiting = nil; status = nil }
            else if Date().timeIntervalSince(wait.since) > 20 { awaiting = nil; status = "\(current.name) didn't start it. Check the TV." }
        }
    }
}
