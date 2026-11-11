// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Combine
import OSLog
import PictureshowCore

/// Optional text delivery has no reference to AVPlayer or its readiness deadline.
/// A selection owns its request/retries; the media period owns parsed cue reuse.
@MainActor final class ExternalTextSubtitles: ObservableObject {
    struct Asset: Equatable {
        let id: String
        let url: URL
        let format: String
        let originMs: Int64
        let streamIndex: Int?
        let sourceID: String?
        let selectionCount: Int?
        init(id: String, url: URL, format: String, originMs: Int64, streamIndex: Int? = nil, sourceID: String? = nil, selectionCount: Int? = nil) {
            self.id = id; self.url = url; self.format = format; self.originMs = originMs
            self.streamIndex = streamIndex; self.sourceID = sourceID; self.selectionCount = selectionCount
        }
    }
    @Published private(set) var cues: [TextSubtitleCue] = []
    @Published private(set) var loading = false
    @Published private(set) var unavailable = false
    private(set) var selected: Asset?
    // Internal read-only task receipt lets tests join real fetch/parse/publication
    // with a bounded deadline instead of guessing scheduler progress by polling.
    private(set) var task: Task<Void, Never>?
    private(set) var revision = 0
    struct DiagnosticEvent: Equatable {
        let kind: String
        /// Loader revision, used only to reject stale callbacks.
        let selection: Int
        /// Logical subtitle-select receipt captured when the asset was selected.
        let selectionCount: Int
        let attempts: Int
        let originMs: Int64
        let streamIndex: Int?
        let sourceID: String?
        /// Authored start of the first visibly delivered cue, on full-media time.
        let firstCueAtMs: Int64?
        let visibleAtMs: Int64?
        let itemID: String
        let viewingID: String
    }
    private let diagnosticSink: (DiagnosticEvent) -> Void
    private(set) var attemptCount = 0
    private(set) var cacheHits = 0
    private var visibleTextRevision: Int?
    private var visibleTextAsset: Asset?
    private var visibleCueAtMs: Int64?
    private var visibleAtMs: Int64?
    private var deliveredRevision: Int?
    private var context = (itemID: "", viewingID: "")
    private var selectionContext = (itemID: "", viewingID: "")
    func setContext(itemID: String, viewingID: String) { context = (itemID, viewingID) }
    private func emit(_ kind: String, asset: Asset, firstCueAtMs: Int64? = nil, visibleAtMs: Int64? = nil) {
        diagnosticSink(.init(kind: kind, selection: revision, selectionCount: asset.selectionCount ?? revision, attempts: attemptCount, originMs: asset.originMs,
                             streamIndex: asset.streamIndex, sourceID: asset.sourceID,
                             firstCueAtMs: firstCueAtMs, visibleAtMs: visibleAtMs,
                             itemID: selectionContext.itemID, viewingID: selectionContext.viewingID))
    }
    /// Called only after the selected text has reached the visible overlay.
    /// A stale layout callback cannot attribute old text to a newer request.
    func didDisplayVisibleText(selection: Int) {
        guard selection == revision, visibleTextRevision == revision, deliveredRevision != revision,
              let asset = visibleTextAsset else { return }
        deliveredRevision = revision
        emit("subtitle-cues", asset: asset, firstCueAtMs: visibleCueAtMs, visibleAtMs: visibleAtMs)
    }
    private var cache: [String: [TextSubtitleCue]] = [:]
    private var cacheOrder: [String] = []
    private var cachedTextBytes = 0
    private let fetch: (URL) async throws -> Data
    private let sleep: (Int64) async throws -> Void

    init(fetch: @escaping (URL) async throws -> Data = { try await ExternalTextSubtitles.fetchBounded($0) },
         sleep: @escaping (Int64) async throws -> Void = { try await Task.sleep(for: .milliseconds($0)) },
         diagnostics: @escaping (DiagnosticEvent) -> Void = { event in
             let stream = event.streamIndex.map(String.init) ?? "unknown"
             let source = event.sourceID ?? "unknown"
             Logger(subsystem: "app.pictureshow", category: "Playback").notice("\(event.kind, privacy: .public) stream=\(stream, privacy: .public) source=\(source, privacy: .public) selectionCount=\(event.selectionCount) loaderRevision=\(event.selection) attempts=\(event.attempts) firstCueAtMs=\(event.firstCueAtMs ?? -1) visibleAtMs=\(event.visibleAtMs ?? -1) originMs=\(event.originMs) item=\(event.itemID, privacy: .public) viewing=\(event.viewingID, privacy: .public)")
         }) {
        self.fetch = fetch; self.sleep = sleep; self.diagnosticSink = diagnostics
    }
    func select(_ asset: Asset?) {
        guard selected != asset else { return }
        revision += 1; let version = revision
        attemptCount = 0; visibleTextRevision = nil; visibleTextAsset = nil; visibleCueAtMs = nil; visibleAtMs = nil; deliveredRevision = nil; selectionContext = context
        task?.cancel(); task = nil; selected = asset; cues = []; unavailable = false; loading = false
        guard let asset else { return }
        if let cached = cache[asset.id] { cacheHits += 1; cues = cached; emit("subtitle-cache", asset: asset); return }
        loading = true
        task = Task { [weak self] in
            guard let self else { return }
            var failures = 0
            while !Task.isCancelled, self.revision == version {
                do {
                    self.attemptCount += 1
                    let data = try await self.fetch(asset.url)
                    try Task.checkCancellation()
                    // This is the selected track's visible caption work, not a
                    // speculative preload. Keep parsing off the UI actor without
                    // demoting delivery behind unrelated utility work.
                    let parsed = try await Task.detached(priority: .userInitiated) { try TextSubtitles.parse(data: data, format: asset.format) }.value
                    guard self.revision == version else { return }
                    self.cues = parsed; self.loading = false; self.unavailable = false
                    self.emit("subtitle-loaded", asset: asset)
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
        let text = TextSubtitles.text(cues: cues, positionMs: local.partialValue)
        if text?.isEmpty == false {
            visibleTextRevision = revision; visibleTextAsset = selected; visibleAtMs = position
            // Attribute only a cue actually active in the text handed to UIKit.
            // Neither an opaque cache id nor a later requested track is an origin.
            let start = cues.first { local.partialValue >= $0.startMs && local.partialValue < $0.endMs }?.startMs
            visibleCueAtMs = start.flatMap {
                let full = $0.addingReportingOverflow(selected.originMs)
                return full.overflow ? nil : full.partialValue
            }
        } else {
            visibleTextRevision = nil; visibleTextAsset = nil; visibleCueAtMs = nil; visibleAtMs = nil
        }
        return text
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
              let finalURL = response.url, ServerURLs.isSameOrigin(url, finalURL) else { throw SubtitleFailure.delivery }
        guard response.expectedContentLength <= Int64(TextSubtitles.maximumBytes) else { throw TextSubtitleError.tooLarge }
        var data = Data()
        // AsyncBytes still accounts for every byte, but buffer appends and explicit
        // cancellation checks have bounded chunk granularity instead of per-byte cost.
        let chunkSize = 16 * 1024
        var chunk: [UInt8] = []
        chunk.reserveCapacity(chunkSize)
        var received = 0
        try Task.checkCancellation()
        for try await byte in bytes {
            guard received < TextSubtitles.maximumBytes else { throw TextSubtitleError.tooLarge }
            received += 1
            chunk.append(byte)
            if chunk.count == chunkSize {
                try Task.checkCancellation()
                data.append(contentsOf: chunk)
                chunk.removeAll(keepingCapacity: true)
            }
        }
        try Task.checkCancellation()
        data.append(contentsOf: chunk)
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
