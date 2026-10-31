// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Network
import OSLog

public enum RelayError: Error { case invalidURL, unavailable, invalidManifest }

/// AVPlayer receives only a short-lived loopback capability. Authentication is attached to
/// upstream requests after checking their origin, including every HLS URI and redirect.
public final class PlaybackRelay: @unchecked Sendable {
    private let server: URL
    private let headers: [String: String]
    private let capability = UUID().uuidString
    private let queue = DispatchQueue(label: "app.pictureshow.playback-relay")
    private var listener: NWListener?
    private var port: UInt16 = 0
    private var transfers: [UUID: RelayTransfer] = [:]

    public init(server: URL, headers: [String: String]) {
        self.server = server
        self.headers = headers
    }

    public func start() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            queue.async {
                do {
                    let parameters = NWParameters.tcp
                    parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
                    let listener = try NWListener(using: parameters)
                    self.listener = listener
                    var completed = false
                    listener.stateUpdateHandler = { state in
                        switch state {
                        case .ready where !completed:
                            completed = true
                            self.port = listener.port!.rawValue
                            continuation.resume()
                        case .failed(let error) where !completed:
                            completed = true
                            continuation.resume(throwing: error)
                        default: break
                        }
                    }
                    listener.newConnectionHandler = { self.receive($0) }
                    listener.start(queue: self.queue)
                } catch { continuation.resume(throwing: error) }
            }
        }
    }

    public func url(for upstream: URL) throws -> URL {
        guard Self.sameOrigin(server, upstream), port > 0 else { throw RelayError.invalidURL }
        let clean = Self.withoutCredentials(upstream)
        let encoded = Data(clean.absoluteString.utf8).base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        // Extension lets AVFoundation identify HLS before the first response.
        let suffix = upstream.pathExtension.lowercased() == "m3u8" ? ".m3u8" : ".asset"
        return URL(string: "http://127.0.0.1:\(port)/\(capability)/\(encoded)\(suffix)")!
    }

    public func stop() {
        queue.async {
            self.listener?.cancel()
            self.listener = nil
            self.transfers.values.forEach { $0.cancel() }
            self.transfers.removeAll()
        }
    }

    private func receive(_ connection: NWConnection) {
        connection.start(queue: queue)
        receiveHeaders(connection, buffer: Data())
    }

    private func receiveHeaders(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { data, _, ended, error in
            var buffer = buffer
            if let data { buffer.append(data) }
            guard error == nil, buffer.count <= 65_536 else { connection.cancel(); return }
            guard let raw = String(data: buffer, encoding: .utf8), raw.contains("\r\n\r\n") else {
                if ended { connection.cancel() } else { self.receiveHeaders(connection, buffer: buffer) }
                return
            }
            let lines = raw.components(separatedBy: "\r\n")
            let first = (lines.first ?? "").split(separator: " ")
            guard first.count == 3, ["GET", "HEAD"].contains(String(first[0])) else { self.reject(connection); return }
            let path = String(first[1]).components(separatedBy: "?")[0]
            let pieces = path.split(separator: "/")
            guard pieces.count == 2, String(pieces[0]) == self.capability else { self.reject(connection); return }
            var encoded = String(pieces[1]).replacingOccurrences(of: ".m3u8", with: "").replacingOccurrences(of: ".asset", with: "").replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
            encoded += String(repeating: "=", count: (4 - encoded.count % 4) % 4)
            guard let decoded = Data(base64Encoded: encoded), let text = String(data: decoded, encoding: .utf8), let upstream = URL(string: text), Self.sameOrigin(self.server, upstream) else { self.reject(connection); return }
            var request = URLRequest(url: upstream, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 45)
            request.httpMethod = String(first[0])
            for (key, value) in self.headers { request.setValue(value, forHTTPHeaderField: key) }
            for line in lines.dropFirst() {
                guard let colon = line.firstIndex(of: ":") else { continue }
                let key = String(line[..<colon])
                if upstream.pathExtension.lowercased() != "m3u8", ["range", "if-range"].contains(key.lowercased()) {
                    request.setValue(String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces), forHTTPHeaderField: key)
                }
            }
            request.setValue("identity", forHTTPHeaderField: "Accept-Encoding")
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Relay").notice("Request method=\(request.httpMethod ?? "", privacy: .public), range=\(request.value(forHTTPHeaderField: "Range") ?? "none", privacy: .public)")
            #endif
            let id = UUID()
            let transfer = RelayTransfer(connection: connection, server: self.server, relay: self, queue: self.queue) { self.transfers.removeValue(forKey: id) }
            self.transfers[id] = transfer
            transfer.start(request)
        }
    }

    private func reject(_ connection: NWConnection) {
        connection.send(content: Data("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8), completion: .contentProcessed { _ in connection.cancel() })
    }

    public static func sameOrigin(_ a: URL, _ b: URL) -> Bool {
        func valid(_ url: URL) -> Bool {
            let s = url.absoluteString
            return ["http", "https"].contains(url.scheme?.lowercased() ?? "") && url.host != nil && url.user == nil && url.password == nil && !s.contains("\\") && !s.contains(where: { $0.isWhitespace || $0.asciiValue == 0 }) && (url.port.map { (1...65535).contains($0) } ?? true)
        }
        return valid(a) && valid(b) && a.scheme?.lowercased() == b.scheme?.lowercased() && a.host?.lowercased() == b.host?.lowercased() && (a.port ?? (a.scheme == "https" ? 443 : 80)) == (b.port ?? (b.scheme == "https" ? 443 : 80))
    }

    public static func withoutCredentials(_ url: URL) -> URL {
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: true)!
        parts.queryItems = parts.queryItems?.filter { !["apikey", "api_key", "x-emby-token", "x-mediabrowser-token"].contains($0.name.lowercased()) }
        if parts.queryItems?.isEmpty == true { parts.queryItems = nil }
        return parts.url!
    }

    /// Resolve against the manifest URL, not the server root. Rewrites variants, segments,
    /// encryption keys, initialization maps and subtitle renditions through the same origin gate.
    public static func rewriteManifest(_ text: String, base: URL, map: (URL) throws -> URL) throws -> String {
        let regex = try NSRegularExpression(pattern: "URI=\"([^\"]+)\"")
        return try text.components(separatedBy: .newlines).map { line in
            if line.hasPrefix("#") {
                var result = line
                for match in regex.matches(in: line, range: NSRange(line.startIndex..., in: line)).reversed() {
                    guard let range = Range(match.range(at: 1), in: result), let target = URL(string: String(result[range]), relativeTo: base)?.absoluteURL else { throw RelayError.invalidManifest }
                    result.replaceSubrange(range, with: try map(target).absoluteString)
                }
                return result
            }
            guard !line.trimmingCharacters(in: .whitespaces).isEmpty else { return line }
            guard let target = URL(string: line, relativeTo: base)?.absoluteURL else { throw RelayError.invalidManifest }
            return try map(target).absoluteString
        }.joined(separator: "\n")
    }
}

private final class RelayTransfer: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let connection: NWConnection
    private let server: URL
    private unowned let relay: PlaybackRelay
    private let queue: DispatchQueue
    private let finished: () -> Void
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var response: HTTPURLResponse?
    private var manifest: Data?
    private var completed = false
    private var pendingSends = 0
    private var upstreamDone = false
    private var closingWrite = false

    init(connection: NWConnection, server: URL, relay: PlaybackRelay, queue: DispatchQueue, finished: @escaping () -> Void) {
        self.connection = connection; self.server = server; self.relay = relay; self.queue = queue; self.finished = finished
    }
    func start(_ request: URLRequest) {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        let delegates = OperationQueue()
        delegates.maxConcurrentOperationCount = 1
        delegates.underlyingQueue = queue
        session = URLSession(configuration: config, delegate: self, delegateQueue: delegates)
        task = session!.dataTask(with: request)
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.cancel() }
            if case .cancelled = state { self?.cancel() }
        }
        task!.resume()
    }
    func cancel() {
        guard !completed else { return }
        completed = true
        task?.cancel(); session?.invalidateAndCancel(); connection.cancel(); finished()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        guard let url = request.url, PlaybackRelay.sameOrigin(server, url) else { completionHandler(nil); return }
        var safe = request
        for (key, value) in task.originalRequest?.allHTTPHeaderFields ?? [:] { safe.setValue(value, forHTTPHeaderField: key) }
        completionHandler(safe)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse, completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            connection.send(content: Data("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".utf8), completion: .contentProcessed { _ in self.cancel() })
            completionHandler(.cancel); return
        }
        self.response = http
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Relay").notice("Response status=\(http.statusCode), length=\(http.value(forHTTPHeaderField: "Content-Length") ?? "none", privacy: .public), range=\(http.value(forHTTPHeaderField: "Content-Range") ?? "none", privacy: .public), type=\(http.mimeType ?? "none", privacy: .public)")
        #endif
        if dataTask.originalRequest?.httpMethod != "HEAD", http.url?.pathExtension.lowercased() == "m3u8" || (dataTask.originalRequest?.httpMethod != "HEAD" && (http.mimeType?.lowercased().contains("mpegurl") ?? false)) { manifest = Data() }
        else { send(responseHeaders(http)) }
        completionHandler(.allow)
    }
    private func responseHeaders(_ http: HTTPURLResponse, length: Int? = nil) -> Data {
        var value = "HTTP/1.1 \(http.statusCode) OK\r\nConnection: close\r\n"
        for name in ["Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"] {
            if name == "Content-Length", let length { value += "Content-Length: \(length)\r\n" }
            else if let field = http.value(forHTTPHeaderField: name) { value += "\(name): \(field)\r\n" }
        }
        if length != nil && http.value(forHTTPHeaderField: "Content-Length") == nil { value += "Content-Length: \(length!)\r\n" }
        return Data((value + "\r\n").utf8)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        if manifest != nil {
            guard manifest!.count + data.count < 2_000_000 else { cancel(); return }
            manifest!.append(data)
        } else {
            dataTask.suspend()
            send(data) { dataTask.resume() }
        }
    }
    private func send(_ data: Data, then: (() -> Void)? = nil) {
        pendingSends += 1
        connection.send(content: data, completion: .contentProcessed { error in
            self.pendingSends -= 1
            if error != nil { self.cancel(); return }
            then?()
            self.finishIfDrained()
        })
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !completed else { return }
        guard error == nil else { cancel(); return }
        if let manifest, let response, let base = response.url {
            do {
                let text = try PlaybackRelay.rewriteManifest(String(decoding: manifest, as: UTF8.self), base: base) { try relay.url(for: $0) }
                let data = Data(text.utf8)
                send(responseHeaders(response, length: data.count) + data)
            } catch { cancel(); return }
        }
        upstreamDone = true
        finishIfDrained()
    }
    private func finishIfDrained() {
        guard upstreamDone, pendingSends == 0, !closingWrite, !completed else { return }
        closingWrite = true
        // Complete the HTTP byte stream with a TCP FIN before disposing the connection.
        // A send callback means Network processed the buffer, not that the peer read it.
        connection.send(content: nil, contentContext: .finalMessage, isComplete: true, completion: .contentProcessed { error in
            guard !self.completed else { return }
            if error != nil { self.cancel(); return }
            self.connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { _, _, _, _ in self.cancel() }
        })
        queue.asyncAfter(deadline: .now() + 15) { [weak self] in self?.cancel() }
    }
}
