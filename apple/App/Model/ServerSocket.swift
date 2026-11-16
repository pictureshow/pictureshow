// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Combine
import Foundation
import os
import PictureshowCore

/// The signed-in viewer's push socket to their server, shared by everything that listens to it.
///
/// One per selected account. Subscribers ask for message types (`"Play"`, `"Playstate"`, `"GeneralCommand"` for the
/// remote target; `"SyncPlayCommand"`, `"SyncPlayGroupUpdate"` for SyncPlay) and get the raw text and the decoded
/// object of each matching message, in arrival order, on the main actor. `state` says whether the socket is live, so a
/// listener can pause what depends on it (SYN-08 "network loss pauses") and rebuild after `.open` returns.
/// Keep-alives follow the server's `ForceKeepAlive` (half its interval); a dropped socket reconnects with backoff
/// (1, 2, 4 … 30 s) until the account changes or `stop()`.
@MainActor
final class ServerSocket: ObservableObject {
    enum State: Equatable { case closed, connecting, open }
    struct Subscription: Hashable { fileprivate let id = UUID() }
    typealias Handler = @MainActor (_ type: String, _ text: String, _ object: [String: Any]) -> Void

    @Published private(set) var state: State = .closed
    private(set) var account: Account?
    /// Runs after each successful connect, before messages are delivered (e.g. re-posting capabilities).
    var onOpen: [@MainActor (Account) async -> Void] = []

    private var handlers: [Subscription: (Set<String>, Handler)] = [:]
    private var task: URLSessionWebSocketTask?
    private var loop: Task<Void, Never>?
    private var keepAlive: Task<Void, Never>?
    private let session = URLSession(configuration: .default)
    private let log = Logger(subsystem: "app.pictureshow", category: "socket")
    private let requestFor: (Account) -> URLRequest?

    init(requestFor: @escaping (Account) -> URLRequest?) { self.requestFor = requestFor }

    func subscribe(_ types: Set<String>, _ handler: @escaping Handler) -> Subscription {
        let token = Subscription(); handlers[token] = (types, handler); return token
    }
    func unsubscribe(_ token: Subscription) { handlers[token] = nil }

    /// Follows the selected account: a different sign-in closes the old socket and opens one for the new account.
    func follow(_ next: Account?) {
        guard next?.key != account?.key || next?.accessToken != account?.accessToken else { return }
        stop()
        account = next
        guard let next, next.isSignedIn else { return }
        loop = Task { [weak self] in await self?.run(next) }
    }

    func stop() {
        loop?.cancel(); loop = nil
        keepAlive?.cancel(); keepAlive = nil
        task?.cancel(with: .goingAway, reason: nil); task = nil
        state = .closed
    }

    /// Sends a message of the server's socket protocol (`{"MessageType": …, "Data": …}`).
    func send(type: String, data: Any? = nil) {
        var message: [String: Any] = ["MessageType": type]
        if let data { message["Data"] = data }
        guard let task, let bytes = try? JSONSerialization.data(withJSONObject: message), let text = String(data: bytes, encoding: .utf8) else { return }
        task.send(.string(text)) { _ in }
    }

    private func run(_ account: Account) async {
        var delay: UInt64 = 1
        while !Task.isCancelled, self.account?.key == account.key {
            guard let request = requestFor(account) else { return }
            state = .connecting
            let socket = session.webSocketTask(with: request)
            task = socket
            socket.resume()
            do {
                let first = try await socket.receive()
                state = .open; delay = 1
                log.info("socket open")
                for hook in onOpen { await hook(account) }
                deliver(first)
                while !Task.isCancelled { deliver(try await socket.receive()) }
            } catch {
                log.info("socket closed: \(error.localizedDescription, privacy: .public)")
            }
            keepAlive?.cancel(); keepAlive = nil
            if task === socket { task = nil }
            guard !Task.isCancelled else { return }
            state = .connecting
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            delay = min(delay * 2, 30)
        }
    }

    private func deliver(_ message: URLSessionWebSocketTask.Message) {
        let text: String
        switch message {
        case .string(let s): text = s
        case .data(let d): text = String(decoding: d, as: UTF8.self)
        @unknown default: return
        }
        guard let data = text.data(using: .utf8), let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let type = object["MessageType"] as? String else { return }
        if type == "ForceKeepAlive" {
            let seconds = max(5, (object["Data"] as? NSNumber)?.intValue ?? 60)
            keepAlive?.cancel()
            keepAlive = Task { [weak self] in
                while !Task.isCancelled {
                    self?.send(type: "KeepAlive")
                    try? await Task.sleep(nanoseconds: UInt64(seconds / 2) * 1_000_000_000)
                }
            }
            return
        }
        for (types, handler) in handlers.values where types.contains(type) { handler(type, text, object) }
    }

    /// For tests: delivers a message as if it had arrived on the socket.
    func receiveForTesting(_ text: String) { deliver(.string(text)) }
}
