// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Combine
import PictureshowCore

/// Optional text delivery has no reference to AVPlayer or its readiness deadline.
/// A selection owns its request/retries; the media period owns parsed cue reuse.
@MainActor final class ExternalTextSubtitles: ObservableObject {
    struct Asset: Equatable {
        let id: String
        let url: URL
        let format: String
        let originMs: Int64
    }
    @Published private(set) var cues: [TextSubtitleCue] = []
    @Published private(set) var loading = false
    @Published private(set) var unavailable = false
    private(set) var selected: Asset?
    private var task: Task<Void, Never>?
    private var revision = 0
    private var cache: [String: [TextSubtitleCue]] = [:]
    private var cacheOrder: [String] = []
    private var cachedTextBytes = 0
    private let fetch: (URL) async throws -> Data
    private let sleep: (Int64) async throws -> Void

    init(fetch: @escaping (URL) async throws -> Data = { try await ExternalTextSubtitles.fetchBounded($0) },
         sleep: @escaping (Int64) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) }) {
        self.fetch = fetch; self.sleep = sleep
    }
    func select(_ asset: Asset?) {
        guard selected != asset else { return }
        revision += 1; let version = revision
        task?.cancel(); task = nil; selected = asset; cues = []; unavailable = false; loading = false
        guard let asset else { return }
        if let cached = cache[asset.id] { cues = cached; return }
        loading = true
        task = Task { [weak self] in
            guard let self else { return }
            var failures = 0
            while !Task.isCancelled, self.revision == version {
                do {
                    let data = try await self.fetch(asset.url)
                    try Task.checkCancellation()
                    let parsed = try await Task.detached(priority: .utility) { try TextSubtitles.parse(data: data, format: asset.format) }.value
                    guard self.revision == version else { return }
                    self.cues = parsed; self.loading = false; self.unavailable = false
                    // Bound optional cue storage to one period and 16 MiB of text.
                    let size = parsed.reduce(0) { $0 + $1.text.utf8.count }
                    while self.cachedTextBytes + size > TextSubtitles.maximumBytes || self.cacheOrder.count >= 16 {
                        guard let first = self.cacheOrder.first else { break }
                        self.cacheOrder.removeFirst()
                        self.cachedTextBytes -= self.cache.removeValue(forKey: first)?.reduce(0) { $0 + $1.text.utf8.count } ?? 0
                    }
                    if size <= TextSubtitles.maximumBytes {
                        self.cache[asset.id] = parsed; self.cacheOrder.append(asset.id); self.cachedTextBytes += size
                    }
                    return
                } catch {
                    guard !Task.isCancelled, self.revision == version else { return }
                    self.unavailable = true
                    failures = min(10, failures + 1)
                    do { try await self.sleep(ExternalSubtitleLoading.retryDelayMs(errorCount: failures)) }
                    catch { return }
                }
            }
        }
    }
    func text(atFullMediaMs position: Int64) -> String? {
        guard let selected else { return nil }
        let local = position.subtractingReportingOverflow(selected.originMs)
        guard !local.overflow else { return nil }
        return TextSubtitles.text(cues: cues, positionMs: local.partialValue)
    }
    func release() {
        select(nil); cache = [:]; cacheOrder = []; cachedTextBytes = 0
    }
    nonisolated static func fetchBounded(_ url: URL, configuration: URLSessionConfiguration = .ephemeral) async throws -> Data {
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 35
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        let redirects = SubtitleOrigin(origin: url)
        let (bytes, response) = try await session.bytes(for: URLRequest(url: url), delegate: redirects)
        guard let response = response as? HTTPURLResponse, (200..<300).contains(response.statusCode),
              let finalURL = response.url, ServerURLs.isSameOrigin(url, finalURL),
              response.expectedContentLength <= Int64(TextSubtitles.maximumBytes) else { throw SubtitleFailure.delivery }
        var data = Data()
        for try await byte in bytes {
            try Task.checkCancellation()
            guard data.count < TextSubtitles.maximumBytes else { throw SubtitleFailure.delivery }
            data.append(byte)
        }
        return data
    }
}

private enum SubtitleFailure: Error { case delivery }
private final class SubtitleOrigin: NSObject, URLSessionTaskDelegate {
    let origin: URL
    init(origin: URL) { self.origin = origin }
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(request.url.map { ServerURLs.isSameOrigin(origin, $0) } == true ? request : nil)
    }
}
