// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Combine
import Foundation
import os
import PictureshowCore

/// This device as a target of the viewer's other apps (REM-02, REM-09; Apple TV first, CAST-PD03).
///
/// Socket messages go through the shared `remote-receive` reducer, which owns the rules: the same viewer only, no
/// duplicates, commands that arrive while a title is opening wait for it (up to 5 s), and only advertised commands are
/// obeyed. This type only turns the reducer's effects into player actions and keeps it told about the viewing.
@MainActor
final class RemoteTarget {
    private weak var appModel: AppModel?
    private var app: AppModel! { appModel }
    private var state: [String: Any] = [:]
    private var subscription: ServerSocket.Subscription?
    private var ticker: Task<Void, Never>?
    private var socketWatch: Any?
    private let log = Logger(subsystem: "app.pictureshow", category: "remote")

    /// What this device obeys. Apple TV's volume belongs to the TV or receiver, so it isn't offered there.
    static var commands: [String] {
        #if os(tvOS)
        return ["SetAudioStreamIndex", "SetSubtitleStreamIndex"]
        #else
        return ["SetAudioStreamIndex", "SetSubtitleStreamIndex", "SetVolume", "VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute"]
        #endif
    }

    init(app: AppModel) {
        self.appModel = app
        subscription = app.socket.subscribe(["Play", "Playstate", "GeneralCommand"]) { [weak self] _, text, _ in self?.message(text) }
        app.socket.onOpen.append { [weak self] account in await self?.opened(account) }
        socketWatch = app.socket.$state.removeDuplicates().sink { [weak self] state in
            MainActor.assumeIsolated { self?.socketChanged(open: state == .open) }
        }
        accountChanged()
        ticker = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                self.reduce(["op": "tick", "nowMs": Self.nowMs()])
            }
        }
    }

    func accountChanged() {
        guard let account = app.account, account.isSignedIn else { state = [:]; return }
        let server = account.server.url.absoluteString
        if state.isEmpty {
            state = ["server": server, "viewer": account.userId, "connected": app.socket.state == .open]
            reduce(["op": "policy", "policy": "same-viewer", "advertised": Self.commands])
        } else if state["server"] as? String != server || state["viewer"] as? String != account.userId {
            reduce(["op": "account", "server": server, "viewer": account.userId])
        }
    }

    /// A reconnect starts a new generation; nothing sent before it is replayed (REM-06).
    func socketChanged(open: Bool) { reduce(["op": open ? "reconnect" : "disconnect"]) }

    /// What the last message or event led to, for tests and the log.
    private(set) var lastRejection: String?
    private(set) var lastEffects: [String] = []

    private func opened(_ account: Account) async {
        try? await app.client(for: account).postCapabilities(account: account, remoteEnabled: true, commands: Self.commands)
    }

    /// The viewing changed (opened, ready, paused, sought, closed): the reducer may release held commands.
    func viewingChanged() {
        reduce(["op": "viewing", "viewing": Self.viewing(app.activeController) as Any? ?? NSNull(), "nowMs": Self.nowMs()])
    }

    static func viewing(_ c: PlaybackController?) -> [String: Any]? {
        guard let c, !c.finished else { return nil }
        return ["item": Self.compact(c.item.id), "ready": c.duration > 0, "durationTicks": String(Ticks.from(seconds: c.duration)),
                "ticks": String(Ticks.from(seconds: c.currentSeconds)), "paused": !c.isPlaying, "canSeek": c.duration > 0]
    }

    private func message(_ text: String) {
        guard let parsed = try? RemoteReducers.reduce(suite: "remote-wire", operation: "parse", state: [:], event: ["text": text]) else { return }
        // The reducer judges against the latest viewing, so refresh it first.
        if let v = Self.viewing(app.activeController) { state["viewing"] = v }
        reduce(["op": "message", "message": parsed, "nowMs": Self.nowMs()])
    }

    private func reduce(_ event: [String: Any]) {
        guard !state.isEmpty else { return }
        do {
            state = try RemoteReducers.reduce(suite: "remote-receive", operation: "receive", state: state, event: event)
        } catch {
            log.error("remote reducer: \(error.localizedDescription, privacy: .public)"); return
        }
        lastRejection = state["rejection"] as? String
        if let why = lastRejection { log.info("remote command not obeyed: \(why, privacy: .public)") }
        let effects = state["effects"] as? [[String: Any]] ?? []
        lastEffects = effects.compactMap { $0["op"] as? String }
        for effect in state["effects"] as? [[String: Any]] ?? [] { apply(effect) }
    }

    private func apply(_ effect: [String: Any]) {
        let op = effect["op"] as? String ?? ""
        let controller = app.activeController
        let generation = effect["generation"] as? String ?? "0"
        switch op {
        case "open":
            let items = effect["items"] as? [String] ?? [], index = effect["index"] as? Int ?? 0
            guard items.indices.contains(index), let account = app.account else { return }
            let ticks = (effect["ticks"] as? String).flatMap(Int64.init)
            Task {
                do {
                    let item = try await app.client(for: account).item(account: account, id: items[index])
                    app.activeController?.close()
                    app.player = PlayerRequest(account: account, item: item, fromStart: ticks == 0, startTicks: ticks)
                } catch {
                    reduce(["op": "resolved", "generation": generation, "ok": false])
                }
            }
        case "pause": controller?.player.pause(); controller?.reportNow()
        case "unpause": controller?.player.play(); controller?.reportNow()
        case "report": controller?.reportNow()
        case "stop": controller?.close(); app.player = nil
        case "next": controller?.playNext()
        case "previous": controller?.seek(to: 0)
        case "seek":
            if let ticks = (effect["ticks"] as? String).flatMap(Int64.init) { controller?.seek(to: Double(ticks) / 10_000_000) }
        case "audio": if let index = effect["index"] as? Int { controller?.selectAudio(index) }
        case "subtitle": if let index = effect["index"] as? Int { controller?.selectSubtitle(index) }
        case "volume": if let level = effect["level"] as? Int { controller?.player.volume = Float(level) / 100 }
        case "volume-key":
            guard let player = controller?.player else { return }
            switch effect["name"] as? String {
            case "Mute": player.isMuted = true
            case "Unmute": player.isMuted = false
            case "ToggleMute": player.isMuted.toggle()
            case "VolumeUp": player.volume = min(1, player.volume + 0.1)
            case "VolumeDown": player.volume = max(0, player.volume - 0.1)
            default: break
            }
        default:
            // queue-next / queue-add: the Apple player has no play queue yet; nav and text aren't advertised.
            log.info("remote effect not carried out on Apple: \(op, privacy: .public)")
        }
    }

    /// Item ids as the socket parser gives them (lowercase, no dashes), so an opened title matches the command's.
    static func compact(_ id: String) -> String { id.lowercased().replacingOccurrences(of: "-", with: "") }

    static func nowMs() -> String { String(Int64(Date().timeIntervalSince1970 * 1000)) }
}
