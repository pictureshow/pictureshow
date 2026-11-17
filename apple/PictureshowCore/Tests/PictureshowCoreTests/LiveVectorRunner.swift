// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
@testable import PictureshowCore

/// Mock server for the Live TV traces: each request is answered by the current trace's handler, so the real LiveTVClient
/// and JellyfinClient transport run unchanged.
final class LiveTraceProtocol: URLProtocol {
    struct Disconnect: Error {}
    nonisolated(unsafe) static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            guard let handler = Self.handler else { throw URLError(.cannotConnectToHost) }
            let (status, data) = try handler(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
                                                                  headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error is Disconnect ? URLError(.networkConnectionLost) : error)
        }
    }
    override func stopLoading() {}
}

/// Executes spec/vectors/live-guide.json, live-dvr.json, live-source.json and the logic operations of
/// live-presentation.json against the Live TV core (spec/features/live-tv.md). Each case yields a JSON value shaped like
/// its `expected`; numbers compare within 0.001 (Android compares its Float results the same way).
enum LiveVectorRunner {
    static let suites: Set<String> = ["live-guide", "live-dvr", "live-source", "live-presentation"]

    struct Mismatch: Error, CustomStringConvertible { let description: String }
    struct Malformed: Error, CustomStringConvertible { let description: String }

    // MARK: Entry points

    static func check(suite: String, vector: [String: Any]) async throws {
        let actual: Any
        if suite == "live-source" {
            actual = try observedWindow(vector)
        } else {
            let input = try XCTInput(vector)
            let operation = vector["operation"] as? String ?? ""
            switch suite {
            case "live-presentation": actual = try presentation(operation, input)
            default: actual = try await guideOrDVR(operation, input)
            }
        }
        try compare(actual, vector["expected"] ?? NSNull(), id: vector["id"] as? String ?? "?")
    }

    /// live-source `metadataCases`: whether the player shows the channel's current programme. Returns the count executed.
    static func checkMetadata(_ document: [String: Any]) throws -> Int {
        guard let cases = document["metadataCases"] as? [[String: Any]], !cases.isEmpty else { throw Malformed(description: "live-source metadataCases missing") }
        var failures: [String] = []
        for c in cases {
            let id = try string(c, "id")
            let channel = LiveChannel(id: try string(c, "channelId"), currentProgram: LiveProgram(id: "program", channelId: try string(c, "programChannelId"),
                                                                                              startDate: try string(c, "start"), endDate: try string(c, "end")))
            guard let now = LiveRules.instant(try string(c, "now")), let show = c["show"] as? Bool else { throw Malformed(description: "\(id): bad now/show") }
            let actual = LiveRules.playerProgram(channel: channel, nowMs: now) != nil
            if actual != show { failures.append("live-source.\(id): expected show=\(show), actual \(actual)") }
        }
        if !failures.isEmpty { throw Mismatch(description: failures.joined(separator: "\n")) }
        return cases.count
    }

    // MARK: live-source

    private static func observedWindow(_ vector: [String: Any]) throws -> Any {
        guard let w = vector["window"] as? [String: Any] else { throw Malformed(description: "window missing") }
        let window = ObservedLiveWindow(offsetMs: try long(w, "offsetMs"), durationMs: try long(w, "durationMs"),
                                        defaultPositionMs: try long(w, "defaultPositionMs"), seekable: try flag(w, "seekable"))
        let position = try long(vector, "positionMs")
        let paused = (vector["pausedAbsoluteMs"] as? NSNumber)?.int64Value
        let expired = window.pausedPositionExpired(paused)
        return ["canTimeShift": window.canTimeShift, "contains": window.contains(position), "expired": expired,
                "goLiveMs": nullable(window.goLivePosition()), "behindLiveMs": nullable(window.behindLiveMs(position)),
                "atLiveEdge": window.atLiveEdge(position, expired: expired)]
    }

    // MARK: live-guide and live-dvr

    private static func guideOrDVR(_ operation: String, _ c: [String: Any]) async throws -> Any {
        switch operation {
        case "guide":
            return try LiveRules.guidePrograms(try decode([LiveProgram].self, c["programs"]), allowedChannels: Set(try strings(c, "channels")),
                                               startUTC: try string(c, "start"), endUTC: try string(c, "end")).map(\.id)
        case "guide-metadata":
            return try LiveRules.guidePrograms(try decode([LiveProgram].self, c["programs"]), allowedChannels: Set(try strings(c, "channels")),
                                               startUTC: try string(c, "start"), endUTC: try string(c, "end"))
                .map { ["id": $0.id, "name": $0.name, "start": nullable($0.startDate), "end": nullable($0.endDate)] as [String: Any] }
        case "sort": return LiveRules.sortChannels(try decode([LiveChannel].self, c["channels"])).map(\.id)
        case "tune": return LiveRules.canTune(try decode(LiveProgram.self, c["program"]), nowMs: try long(c, "now"))
        case "draft":
            return LiveRules.timerDraft(defaults: try schedule(c["defaults"]), program: try decode(LiveProgram.self, c["program"]), series: try flag(c, "series"))?
                .fields.mapValues(\.foundation) ?? NSNull()
        case "edit":
            do {
                return try LiveRules.editSchedule(try schedule(c["original"]), edits: try decode([String: JSONValue].self, c["edits"]), series: try flag(c, "series"))
                    .fields.mapValues(\.foundation)
            } catch is LiveRules.RuleError { return ["error": true] }
        case "statuses": return try strings(c, "statuses").map(LiveRules.recordingStatus)
        case "status-tones": return try strings(c, "statuses").map { LiveRules.recordingTone($0).rawValue }
        case "cache":
            let original = LiveGuideCache(accountKey: try string(c, "account"), generation: try long(c, "generation"), channelIds: Set(try strings(c, "channels")))
            var after = original.page(account: try string(c, "responseAccount"), generation: try long(c, "responseGeneration"),
                                      incoming: try decode([LiveProgram].self, c["programs"]), fetchedAt: try long(c, "fetchedAt"))
            after.offline = try flag(c, "offline")
            after.refreshing = c["refreshing"] as? Bool ?? false
            return ["ids": after.programs.map(\.id),
                    "actionAllowed": after.actionAllowed(try decode(LiveProgram.self, c["candidate"]), nowMs: try long(c, "now"))] as [String: Any]
        case "preview-line":
            return LiveRules.previewLine(program: try decode(LiveProgram.self, c["program"]), channelName: content(c["channel"]), nowMs: try long(c, "now"))
        case "quiet-refresh":
            return LiveGuideCache(accountKey: "viewer", generation: 1, channelIds: [], fetchedAtMs: (c["fetchedAt"] as? NSNumber)?.int64Value,
                                  offline: try flag(c, "offline"), refreshing: try flag(c, "refreshing")).quietRefreshDue(nowMs: try long(c, "now"))
        case "program-query": return try await programQuery(c)
        case "create-trace": return try await createTrace(c)
        case "delete-trace": return try await deleteTrace(c)
        default: throw Malformed(description: "Unhandled Live TV operation \(operation)")
        }
    }

    private static let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "fixture", name: "Fixture", version: "12.0.0"),
                                         userId: "viewer", username: "Viewer", accessToken: "fixture", deviceID: "fixture-device")

    private static func liveClient(now: @escaping @Sendable () -> Int64 = { 1_893_456_000_000 }) -> LiveTVClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [LiveTraceProtocol.self]
        return LiveTVClient(client: JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: config)), now: now)
    }

    private static func body(_ value: Any) -> Data { (try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])) ?? Data() }

    private static func withHandler<T>(_ handler: @escaping (URLRequest) throws -> (Int, Data), _ run: () async throws -> T) async rethrows -> T {
        LiveTraceProtocol.handler = handler
        defer { LiveTraceProtocol.handler = nil }
        return try await run()
    }

    /// Mutable trace state shared with the mock handler.
    private final class Trace: @unchecked Sendable {
        var query: [String: Any] = [:]
        var methods: [String] = []
        var posts = 0
        var stored: [String: Any]?
        var unlisted = false
        var fileDeleted = false
        var unexpected: [String] = []
    }

    private static func programQuery(_ c: [String: Any]) async throws -> Any {
        let trace = Trace()
        let overview = try string(c, "overview")
        let page = try await withHandler({ request in
            let url = request.url!
            var query: [String: Any] = ["path": url.path]
            for item in URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? [] { query[item.name] = item.value ?? "" }
            trace.query = query
            var metadata: [String: Any] = ["Id": "p", "Name": "Generated programme"]
            if query["fields"] as? String == "Overview" { metadata["Overview"] = overview }
            return (200, body(["Items": [metadata], "TotalRecordCount": 1]))
        }) {
            try await liveClient().programs(account: account, channelIDs: try strings(c, "channels"), startUTC: try string(c, "start"), endUTC: try string(c, "end"),
                                            startIndex: Int(try long(c, "startIndex")), limit: Int(try long(c, "limit")))
        }
        guard page.items.count == 1 else { throw Mismatch(description: "program query returned \(page.items.count) items") }
        return ["query": trace.query, "overview": nullable(page.items[0].overview)] as [String: Any]
    }

    private static func createTrace(_ c: [String: Any]) async throws -> Any {
        let trace = Trace()
        trace.stored = c["stored"] as? [String: Any]
        let series = try flag(c, "series"), applied = try flag(c, "applied"), disconnect = try flag(c, "disconnect")
        let path = series ? "/LiveTv/SeriesTimers" : "/LiveTv/Timers"
        guard let draft = c["draft"] as? [String: Any], let policy = c["policy"] else { throw Malformed(description: "draft/policy missing") }
        let programOverrides = c["program"] as? [String: Any] ?? [:]
        let client = liveClient()
        let schedule = LiveSchedule(fields: try decode([String: JSONValue].self, draft))
        let (first, second) = try await withHandler({ request in
            let route = request.url!.path, method = request.httpMethod ?? "GET"
            trace.methods.append("\(method) \(route)")
            if route == "/Users/viewer" { return (200, body(["Policy": policy])) }
            if method == "GET" && route == path { return (200, body(["Items": trace.stored.map { [$0] } ?? []])) }
            if route == "/LiveTv/Programs/p" {
                return (200, body(draft.merging(["Id": "p", "IsSeries": true]) { $1 }.merging(programOverrides) { $1 }))
            }
            if route == "/LiveTv/Channels/c" { return (200, body(["Id": "c", "Name": "Channel"])) }
            if method == "POST" && route == path {
                trace.posts += 1
                if applied { trace.stored = draft.merging(["Id": "saved", "Status": "New"]) { $1 } }
                if disconnect { throw LiveTraceProtocol.Disconnect() }
                return (204, Data())
            }
            trace.unexpected.append("\(method) \(route)")
            return (500, Data())
        }) {
            (try await client.createTimer(account: account, draft: schedule, series: series),
             try await client.createTimer(account: account, draft: schedule, series: series))
        }
        guard trace.unexpected.isEmpty else { throw Mismatch(description: "unexpected fixture requests \(trace.unexpected)") }
        var result: [String: Any] = ["first": first.outcome, "second": second.outcome, "postCount": trace.posts,
                                     "posts": trace.methods.filter { $0.hasPrefix("POST") }]
        if c["reportSchedule"] as? Bool == true {
            result["firstRuleId"] = nullable(first.schedule?.id)
            result["secondRuleId"] = nullable(second.schedule?.id)
        }
        return result
    }

    private static func deleteTrace(_ c: [String: Any]) async throws -> Any {
        let trace = Trace()
        guard let policy = c["policy"], let canDelete = c["canDelete"] else { throw Malformed(description: "policy/canDelete missing") }
        let retainFile = try flag(c, "retainFile")
        let result = try await withHandler({ request in
            let route = request.url!.path, method = request.httpMethod ?? "GET"
            trace.methods.append("\(method) \(route)")
            if route == "/Users/viewer" { return (200, body(["Policy": policy])) }
            if method == "DELETE" {
                trace.unlisted = true
                if route == "/Items/recording" && !retainFile { trace.fileDeleted = true }
                return (204, Data())
            }
            if route == "/LiveTv/Recordings/recording" || route == "/Items/recording" {
                let gone = (route.hasPrefix("/LiveTv/") && trace.unlisted) || (route.hasPrefix("/Items/") && trace.fileDeleted)
                return (gone ? 404 : 200, body(["Id": "recording", "CanDelete": canDelete]))
            }
            trace.unexpected.append("\(method) \(route)")
            return (500, Data())
        }) { try await liveClient().deleteRecording(account: account, id: "recording") }
        guard trace.unexpected.isEmpty else { throw Mismatch(description: "unexpected fixture requests \(trace.unexpected)") }
        return ["outcome": result.outcome, "fileDeleted": trace.fileDeleted, "requests": trace.methods] as [String: Any]
    }

    // MARK: live-presentation

    /// Android-only operations: Compose dp geometry, constants of Android surfaces, or Android navigation routing. They have no
    /// Apple core rule; the native Apple views own their own measurements (reason recorded in PendingSpecCaseManifest).
    static let androidOnlyPresentation: Set<String> = [
        "tick", "lane-height", "ruler-height", "guide-channel-column", "focus-shift", "guide-scroll", "recording-shelf-pivot", "recording-shelf-tail",
        "recording-caption-height", "compact-height", "disclosure-rotation", "program-content-inset", "quality-scroll-cues", "quality-overflow-affordance",
        "live-seek-presentation", "recording-label-body", "recording-mutation-slot", "tv-review-surface", "review-header-navigation", "touch-parent",
    ]

    private static func liveWindow(_ value: Any?) throws -> ObservedLiveWindow? {
        guard let w = value as? [String: Any] else { return nil }
        return ObservedLiveWindow(offsetMs: 100_000, durationMs: try long(w, "duration"), defaultPositionMs: try long(w, "default"), seekable: try flag(w, "seekable"))
    }

    private static func time(_ c: [String: Any]) throws -> LiveTime {
        guard let zone = TimeZone(identifier: try string(c, "zone")), let now = LiveRules.instant(try string(c, "now")) else { throw Malformed(description: "bad zone/now") }
        return LiveTime(twentyFour: try flag(c, "twentyFour"), locale: Locale(identifier: try string(c, "locale")), zone: zone, nowMs: now)
    }

    /// Android normalizes the narrow and ordinary no-break spaces some locales put before AM/PM.
    private static func spaced(_ text: String) -> String { text.replacingOccurrences(of: "\u{202F}", with: " ").replacingOccurrences(of: "\u{00A0}", with: " ") }

    /// An epoch instant as java.time.Instant.toString writes it.
    static func iso(_ ms: Int64) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = ms % 1000 == 0 ? [.withInternetDateTime] : [.withInternetDateTime, .withFractionalSeconds]
        return f.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    private static func timedPrograms(_ c: [String: Any], channel: Bool) throws -> [LiveProgram] {
        guard let programs = c["programs"] as? [[String: Any]] else { throw Malformed(description: "programs missing") }
        return try programs.map {
            LiveProgram(id: try string($0, "id"), channelId: channel ? try string($0, "channel") : "", startDate: iso(try long($0, "start")), endDate: iso(try long($0, "end")))
        }
    }

    private static func presentation(_ operation: String, _ c: [String: Any]) throws -> Any {
        typealias P = LivePresentation
        switch operation {
        case "text": return P.guideShowsText(width: try double(c, "width"))
        case "clock": return spaced(try time(c).clock(try string(c, "instant")))
        case "range": return spaced(try time(c).range(try string(c, "start"), try string(c, "end")))
        case "recording-dot": return LiveProgram(id: "p", timerId: content(c["timerId"]), seriesTimerId: content(c["seriesTimerId"])).hasRecordingTimer
        case "window-start":
            guard let zone = TimeZone(identifier: try string(c, "zone")), let now = LiveRules.instant(try string(c, "now")) else { throw Malformed(description: "bad zone/now") }
            return iso(P.guideWindowStart(nowMs: now, zone: zone))
        case "initial-focus":
            let cells = P.guideCells(try timedPrograms(c, channel: false), startMs: try long(c, "start"), endMs: try long(c, "end"))
            return nullable(P.initialFocus(cells, nowMs: try long(c, "now")))
        case "schedule-status": return P.scheduleStatus(try schedule(c["fields"]), series: try flag(c, "series"))
        case "recording-metadata":
            let item = try decode(MediaItem.self, c["item"])
            return ["title": P.recordingTitle(item), "metadata": spaced(P.recordingMetadata(item, time: try time(c))),
                    "progress": nullable(P.recordingProgress(item))] as [String: Any]
        case "channel-idle-label": return P.channelIdleLabel(hasPrograms: try flag(c, "hasPrograms"), unavailable: c["unavailable"] as? Bool ?? false)
        case "scroll-owner": return P.scrollOwner(tab: try string(c, "tab"), linear: try flag(c, "linear"))
        case "recording-priority": return P.recordingPriorityLabel(content(c["value"]))
        case "recording-days": return P.recordingDaysLabel(Set(try strings(c, "days")))
        case "live-readout":
            let r = P.timelineReadout(window: try liveWindow(c["window"]), position: try long(c, "position"), paused: try flag(c, "paused"),
                                      expired: try flag(c, "expired"), canTimeShift: try flag(c, "canTimeShift"))
            return ["status": r.status, "clock": r.clock]
        case "live-forward":
            return P.forwardSeekAllowed(live: try flag(c, "live"), window: try liveWindow(c["window"]), position: try long(c, "position"), expired: try flag(c, "expired"))
        case "live-reader-sequence":
            guard let steps = c["steps"] as? [Any] else { throw Malformed(description: "steps missing") }
            let position = try long(c, "position"), paused = try flag(c, "paused")
            return try steps.map { step -> String in
                guard let w = try liveWindow(step) else { throw Malformed(description: "null step window") }
                return P.timelineLabel(paused: paused, canTimeShift: w.canTimeShift, expired: false, behindMs: w.behindLiveMs(position))
            }
        case "schedule-group":
            guard let now = LiveRules.instant(try string(c, "now")) else { throw Malformed(description: "bad now") }
            return P.scheduleGroup(try schedule(c["fields"]), nowMs: now)
        case "schedule-focus": return P.scheduleFocusKey(try schedule(c["fields"]), series: try flag(c, "series"))
        case "refresh":
            let r = P.refreshPresentation(loading: try flag(c, "loading"), writing: try flag(c, "writing"), tv: try flag(c, "tv"), pulled: try flag(c, "pulled"))
            return ["pullRefreshing": r.pullRefreshing, "headerLoading": r.headerLoading]
        case "guide-empty-message": return P.guideEmptyMessage(unavailable: try flag(c, "unavailable"))
        case "guide-read-status": return LiveTVState(offline: try flag(c, "readFailed"), error: content(c["error"])).guideUnavailable
        case "guide-reference-notice":
            let fetched = try content(c["fetchedAt"]).map { value -> Int64 in
                guard let ms = Int64(value) else { throw Malformed(description: "bad fetchedAt") }
                return ms
            }
            return nullable(LiveTVState(loading: try flag(c, "loading"), offline: try flag(c, "offline"), fetchedAtMs: fetched).guideReferenceNotice(nowMs: try long(c, "now")))
        case "guide-freshness-label":
            let fetched = try content(c["fetchedAt"]).map { value -> Int64 in
                guard let ms = LiveRules.instant(value) else { throw Malformed(description: "bad fetchedAt") }
                return ms
            }
            return nullable(P.guideFreshness(fetchedAtMs: fetched, offline: try flag(c, "offline"), time: try time(c)).map(spaced))
        case "guide-text-window":
            let w = P.textWindow(cellX: try double(c, "cellX"), cellWidth: try double(c, "cellWidth"), viewportStart: try double(c, "viewportStart"),
                                 viewportWidth: try double(c, "viewportWidth"))
            return ["inset": w.inset, "width": w.width]
        case "recording-action-order": return P.recordingActionOrder(hasFromStart: try flag(c, "hasFromStart"), hasDownload: try flag(c, "hasDownload"))
        case "program-record-entry-focus": return P.programRecordInitialFocus(tv: try flag(c, "tv"), primary: try flag(c, "primary"))
        case "program-recording-label": return P.programRecordingLabel(status: try string(c, "status"))
        case "live-pause-focus": return P.pauseRequestsControlFocus(live: try flag(c, "live"), surfaceFocused: try flag(c, "surfaceFocused"))
        case "touch-live-slider": return P.touchLiveSliderAvailable(window: try liveWindow(c["window"]), expired: try flag(c, "expired"))
        case "live-buffer-fill":
            return nullable(P.trackPositionFraction(window: try liveWindow(c["window"]), position: try long(c, "position"), expired: try flag(c, "expired")))
        case "live-buffer-thumb":
            guard let w = try liveWindow(c["window"]) else { throw Malformed(description: "window missing") }
            return P.bufferSliderPosition(window: w, position: try long(c, "position"), expired: try flag(c, "expired"))
        case "live-buffer-scrub-end":
            guard let w = try liveWindow(c["window"]) else { throw Malformed(description: "window missing") }
            return P.bufferScrubEnd(window: w)
        case "live-buffer-endpoints":
            let labels = P.bufferEndpointLabels(window: try liveWindow(c["window"]), position: try long(c, "position"), expired: try flag(c, "expired"))
            return [labels.0, labels.1]
        case "live-buffer-status":
            return P.timelineVisualStatus(LiveTimelineReadout(status: try string(c, "status"), clock: try string(c, "clock")), behindMs: (c["behind"] as? NSNumber)?.int64Value)
        case "guide-ruler-label-fit":
            return P.guideRulerLabelFits(x: try double(c, "x"), width: try double(c, "width"), viewportStart: try double(c, "viewportStart"), viewportWidth: try double(c, "viewportWidth"))
        case "guide-viewport-focus":
            guard let raw = c["cells"] as? [[String: Any]] else { throw Malformed(description: "cells missing") }
            let cells = try raw.map {
                LiveGuideCell(program: LiveProgram(id: try string($0, "id")), startFraction: try double($0, "x") / P.guideWidth,
                              widthFraction: try double($0, "width") / P.guideWidth, lane: Int(try long($0, "lane")))
            }
            return nullable(P.guideViewportFocus(cells, currentID: content(c["current"]), viewportStart: try double(c, "viewportStart"), viewportWidth: try double(c, "viewportWidth")))
        case "schedule-effective-changes": return P.scheduleHasChanges(try schedule(c["fields"]), edits: try decode([String: JSONValue].self, c["edits"]))
        case "schedule-review-label": return P.scheduleReviewLabel(existing: try flag(c, "existing"), changed: try flag(c, "changed"))
        case "schedule-confirm-guard":
            return P.scheduleCanConfirm(existing: try flag(c, "existing"), changed: try flag(c, "changed"), busy: try flag(c, "busy"), canSubmit: c["canSubmit"] as? Bool ?? true)
        case "guide-now-focus":
            return nullable(P.guideNowFocus(channels: try strings(c, "channels").map { LiveChannel(id: $0) }, programs: try timedPrograms(c, channel: true),
                                            selectedKey: content(c["selectedKey"]), startMs: try long(c, "start"), nowMs: try long(c, "now")))
        case "guide-now-enabled": return P.guideNowEnabled(tv: try flag(c, "tv"), enabled: try flag(c, "enabled"), atNow: try flag(c, "atNow"))
        case "program-metadata-lines":
            return P.programMetadataLines(channel: content(c["channel"]), range: try string(c, "range"), episode: content(c["episode"]), title: content(c["title"]), tv: try flag(c, "tv"))
        case "review-summary-focus": return P.reviewSummaryFocusable(tv: try flag(c, "tv"))
        case "review-header-reveal": return P.editorShouldRevealHeader(tv: try flag(c, "tv"), firstRowFocused: try flag(c, "firstActionRowFocused"))
        case "program-recording-time-state":
            let end = content(c["endDate"]), now = try long(c, "now")
            return ["ended": P.programEnded(endISO: end, nowMs: now), "creationAvailable": P.programCreationAvailable(endISO: end, nowMs: now)]
        case "schedule-order": return P.scheduleGroups(timers: [], series: [], nowMs: 0).map(\.title)
        case "empty-state": return try emptyState(c)
        case "program-episode":
            func number(_ key: String) throws -> Int? {
                try content(c[key]).map { value in
                    guard let n = Int(value) else { throw Malformed(description: "bad \(key)") }
                    return n
                }
            }
            return nullable(P.programEpisodeLabel(LiveProgram(id: "p", name: "Programme", channelId: "c", episodeNumber: try number("episode"), seasonNumber: try number("season"))))
        case "program-relative-time":
            guard let now = LiveRules.instant(try string(c, "now")) else { throw Malformed(description: "bad now") }
            return P.programTimeLabel(range: try string(c, "range"), start: content(c["start"]), end: content(c["end"]), nowMs: now)
        case "visible-error": return P.visibleError(try string(c, "message"))
        case "schedule-review-summary":
            let time = LiveTime(twentyFour: true, locale: Locale(identifier: "en-US"), zone: TimeZone(identifier: "UTC")!, nowMs: LiveRules.instant("2026-11-13T12:00:00Z")!)
            return P.scheduleReviewSummary(try schedule(c["fields"]), series: try flag(c, "series"), existing: try flag(c, "existing"), time: time,
                                           touch: c["touch"] as? Bool ?? false)
        case "library-destination":
            var item = MediaItem(id: "view", name: try string(c, "name"), type: try string(c, "type"))
            item.collectionType = content(c["collectionType"])
            return item.isLiveTvLibrary ? "live-tv" : "catalog"
        case "library-cards": return ["destination:live-tv"] + P.ordinaryLibraries(try decode([MediaItem].self, c["items"])).map(\.id)
        case "channel-monogram": return P.channelMonogramVisible(imageLoaded: try string(c, "imageState") == "success")
        default: throw Malformed(description: "Unhandled live-presentation operation \(operation)")
        }
    }

    private static func emptyState(_ c: [String: Any]) throws -> Any {
        func number(_ key: String, _ fallback: Int = 0) -> Int { (c[key] as? NSNumber)?.intValue ?? fallback }
        let channels = number("channels")
        let policy: LivePolicy? = c["policyKnown"] as? Bool == false ? nil
            : LivePolicy(access: c["access"] as? Bool ?? true, manage: c["manage"] as? Bool ?? true, deleteContent: true, userData: true)
        let state = LiveTVState(loading: c["loading"] as? Bool ?? false, policy: policy, tunerEnabled: c["tuner"] as? Bool, offline: c["offline"] as? Bool ?? false,
                                channels: (0..<channels).map { LiveChannel(id: "c\($0)", name: "Channel \($0)", number: "\($0)") },
                                programs: (0..<number("programs")).map { LiveProgram(id: "p\($0)", name: "Programme", channelId: "c0") })
        guard let result = LivePresentation.emptyPresentation(state: state, tab: c["tab"] as? String ?? "Channels", filtered: number("filtered", channels),
                                                              favorites: c["favorites"] as? Bool ?? false, search: c["search"] as? String ?? "") else { return NSNull() }
        var parts = [result.title, result.action.label]
        if c["includeAction"] as? Bool == true { parts.append(result.action.name) }
        if c["includeBody"] as? Bool == true { parts.append(result.body) }
        return parts
    }

    // MARK: Input helpers

    private static func XCTInput(_ vector: [String: Any]) throws -> [String: Any] {
        guard let input = vector["input"] as? [String: Any] else { throw Malformed(description: "input missing") }
        return input
    }
    private static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T {
        guard let value, !(value is NSNull) else { throw Malformed(description: "missing \(T.self)") }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }
    private static func schedule(_ value: Any?) throws -> LiveSchedule { LiveSchedule(fields: try decode([String: JSONValue].self, value)) }
    private static func isBool(_ n: NSNumber) -> Bool { CFGetTypeID(n) == CFBooleanGetTypeID() }
    private static func string(_ d: [String: Any], _ key: String) throws -> String {
        guard let v = d[key] as? String else { throw Malformed(description: "missing string \(key)") }
        return v
    }
    private static func strings(_ d: [String: Any], _ key: String) throws -> [String] {
        guard let v = d[key] as? [String] else { throw Malformed(description: "missing strings \(key)") }
        return v
    }
    private static func long(_ d: [String: Any], _ key: String) throws -> Int64 {
        guard let v = d[key] as? NSNumber, !isBool(v) else { throw Malformed(description: "missing integer \(key)") }
        return v.int64Value
    }
    private static func double(_ d: [String: Any], _ key: String) throws -> Double {
        guard let v = d[key] as? NSNumber, !isBool(v) else { throw Malformed(description: "missing number \(key)") }
        return v.doubleValue
    }
    private static func flag(_ d: [String: Any], _ key: String) throws -> Bool {
        guard let v = d[key] as? NSNumber, isBool(v) else { throw Malformed(description: "missing boolean \(key)") }
        return v.boolValue
    }
    /// kotlinx `jsonPrimitive.contentOrNull`: a primitive's text, nil for JSON null or absence.
    private static func content(_ value: Any?) -> String? {
        switch value {
        case let s as String: return s
        case let n as NSNumber: return isBool(n) ? (n.boolValue ? "true" : "false") : n.stringValue
        default: return nil
        }
    }
    private static func nullable(_ value: Any?) -> Any { value ?? NSNull() }

    // MARK: Comparison

    private static func compare(_ actual: Any, _ expected: Any, id: String) throws {
        let a = try normalized(actual), e = try normalized(expected)
        guard equal(a, e) else { throw Mismatch(description: "\(id)\n expected: \(e)\n actual: \(a)") }
    }
    private static func normalized(_ value: Any) throws -> JSONValue {
        try JSONDecoder().decode(JSONValue.self, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]))
    }
    private static func numeric(_ v: JSONValue) -> Double? {
        switch v {
        case .integer(let n): return Double(n)
        case .number(let n): return n
        default: return nil
        }
    }
    private static func equal(_ a: JSONValue, _ e: JSONValue) -> Bool {
        if let x = numeric(a), let y = numeric(e) { return abs(x - y) <= 0.001 }
        switch (a, e) {
        case (.array(let x), .array(let y)): return x.count == y.count && zip(x, y).allSatisfy(equal)
        case (.object(let x), .object(let y)): return Set(x.keys) == Set(y.keys) && x.allSatisfy { equal($0.value, y[$0.key]!) }
        default: return a == e
        }
    }
}
