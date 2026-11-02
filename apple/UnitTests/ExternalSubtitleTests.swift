// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Foundation
import AVFoundation
import UIKit
import SwiftUI
import Combine
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// Real URLSession transport, isolated to an in-process fixture. Held responses are
/// cancelled by URLSession; no public host or actual network connection is used.
private final class ExternalSubtitleHTTP: URLProtocol {
    enum Delivery { case valid, http500, malformed, held, declaredOversize, streamedOversize }
    struct Recorded { let url: URL; let body: [String: Any]; var path: String { url.path } }
    private static let lock = NSLock()
    private static var delivery: Delivery = .valid
    private static var recorded: [Recorded] = []
    private static var cancelled: [String] = []
    private static var held: [ObjectIdentifier: ExternalSubtitleHTTP] = [:]
    private static var captionReference: String? = "/captions/selected.vtt"
    private static var encodedExternalSubrip = false
    private static var embeddedSelectedTrack = false
    private static var expandedTracks = false
    static let caption = Data("WEBVTT\n\n00:02:00.000 --> 00:02:05.000\nSelected caption\n".utf8)
    static var source: [String: Any] {
        lock.lock(); let reference = captionReference, encoded = encodedExternalSubrip, embedded = embeddedSelectedTrack, expanded = expandedTracks; lock.unlock()
        var selected: [String: Any] = ["Index": encoded ? 0 : 4, "Type": "Subtitle", "Codec": embedded ? "mov_text" : encoded ? "subrip" : "vtt", "IsExternal": !embedded,
                                      "DeliveryMethod": embedded ? "Embed" : encoded ? "Encode" : "External"]
        if !embedded, let reference { selected["DeliveryUrl"] = reference }
        var streams: [[String: Any]] = [
            ["Index": encoded ? 1 : 0, "Type": "Video", "Codec": "h264"],
            ["Index": encoded ? 2 : 1, "Type": "Audio", "Codec": "aac"], selected,
            ["Index": encoded ? 3 : 5, "Type": "Subtitle", "Codec": "vtt", "IsExternal": true,
             "DeliveryMethod": "External", "DeliveryUrl": "/captions/unselected.vtt"]
        ]
        if expanded {
            for index in 6...47 {
                let audio = index % 2 == 0
                var stream: [String: Any] = ["Index": index, "Type": audio ? "Audio" : "Subtitle", "Codec": audio ? "aac" : "mov_text"]
                stream["Language"] = ["eng", "fra", "jpn"][index % 3]
                stream["Title"] = "Fixture track \(index)"
                stream["IsForced"] = index % 7 == 0
                stream["IsHearingImpaired"] = index % 5 == 0
                streams.append(stream)
            }
        }
        return ["Id": "source", "Container": "mp4", "SupportsDirectPlay": true,
                "DefaultAudioStreamIndex": encoded ? 2 : 1, "DefaultSubtitleStreamIndex": encoded ? 0 : 4,
                "MediaStreams": streams]
    }

    static func reset(_ value: Delivery, captionReference: String? = "/captions/selected.vtt", encodedExternalSubrip: Bool = false, embeddedSelectedTrack: Bool = false, expandedTracks: Bool = false) {
        lock.lock(); defer { lock.unlock() }
        delivery = value; self.captionReference = captionReference; self.encodedExternalSubrip = encodedExternalSubrip; self.embeddedSelectedTrack = embeddedSelectedTrack; self.expandedTracks = expandedTracks
        recorded = []; cancelled = []; held = [:]
    }
    // Shared resolve-24/25 append even slash-prefixed references to the configured
    // server base. Absolute DeliveryUrl values retain their own same-origin path.
    static func isCaptionPath(_ path: String) -> Bool {
        path.hasPrefix("/captions/") || path.hasPrefix("/jellyfin/captions/")
            || (path.hasPrefix("/jellyfin/Videos/movie/source/Subtitles/") && path.hasSuffix("/Stream.vtt"))
    }
    static func completeHeldCaptions() {
        lock.lock(); let responses = Array(held.values); held = [:]; lock.unlock()
        responses.forEach { $0.respond(status: 200, data: caption) }
    }
    static var requests: [Recorded] { lock.lock(); defer { lock.unlock() }; return recorded }
    static var stoppedPaths: [String] { lock.lock(); defer { lock.unlock() }; return cancelled }
    static func configuration() -> URLSessionConfiguration {
        let value = URLSessionConfiguration.ephemeral
        value.protocolClasses = [ExternalSubtitleHTTP.self]
        return value
    }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "media.example.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    private static func body(_ request: URLRequest) -> [String: Any] {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&bytes, maxLength: bytes.count)
                if count <= 0 { break }; data.append(bytes, count: count)
            }
        }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    }
    override func startLoading() {
        let path = request.url!.path
        let body = Self.body(request)
        Self.lock.lock()
        Self.recorded.append(Recorded(url: request.url!, body: body))
        let delivery = Self.delivery
        if Self.isCaptionPath(path), case .held = delivery { Self.held[ObjectIdentifier(self)] = self; Self.lock.unlock(); return }
        Self.lock.unlock()
        if Self.isCaptionPath(path) {
            switch delivery {
            case .http500: respond(status: 500, data: Data("Unavailable".utf8))
            case .malformed: respond(status: 200, data: Data("WEBVTT\n\n00:broken --> invalid\nMalformed".utf8))
            case .declaredOversize: respond(status: 200, data: Data(), headers: ["Content-Length": String(TextSubtitles.maximumBytes + 1)])
            case .streamedOversize: respond(status: 200, data: Data(repeating: 32, count: TextSubtitles.maximumBytes + 1))
            case .valid: respond(status: 200, data: Self.caption)
            case .held: break
            }
            return
        }
        let value: [String: Any]
        if path.hasSuffix("PlaybackInfo") { value = ["MediaSources": [Self.source], "PlaySessionId": "subtitle-viewing"] }
        else if path.hasSuffix("/Items/movie") { value = ["Id": "movie", "Type": "Movie", "Chapters": []] }
        else if path.contains("MediaSegments") { value = ["Items": []] }
        else { value = [:] }
        respond(status: 200, data: try! JSONSerialization.data(withJSONObject: value))
    }
    private func respond(status: Int, data: Data, headers: [String: String] = [:]) {
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        if !data.isEmpty { client?.urlProtocol(self, didLoad: data) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.cancelled.append(request.url!.path)
        Self.held.removeValue(forKey: ObjectIdentifier(self))
    }
}

/// The test chooses when optional retry time advances. At the limit, the retry
/// stays suspended until cleanup; no wall-clock sleep or AV clock is advanced.
@MainActor private final class SubtitleRetryClock {
    var delays: [Int64] = []
    var elapsedMs: Int64 = 0
    let stopAtMs: Int64
    private var waiters: [CheckedContinuation<Void, Error>] = []
    init(stopAtMs: Int64 = 242_000) { self.stopAtMs = stopAtMs }
    func sleep(_ milliseconds: Int64) async throws {
        delays.append(milliseconds); elapsedMs += milliseconds
        if elapsedMs < stopAtMs { await Task.yield(); try Task.checkCancellation(); return }
        try await withCheckedThrowingContinuation { waiters.append($0) }
    }
    func finish() { let pending = waiters; waiters = []; pending.forEach { $0.resume(throwing: CancellationError()) } }
}

/// Deliberately ignores cancellation, so a late completion exercises the loader's
/// generation/cancellation checks instead of relying on a cooperative transport.
@MainActor private final class LateSubtitleFetch {
    private(set) var pending: [String: CheckedContinuation<Data, Error>] = [:]
    func fetch(_ url: URL) async throws -> Data { try await withCheckedThrowingContinuation { pending[url.path] = $0 } }
    func complete(_ path: String, text: String) { pending.removeValue(forKey: path)?.resume(returning: Data(text.utf8)) }
    func finish() { let values = pending.values; pending = [:]; values.forEach { $0.resume(throwing: CancellationError()) } }
}

private final class SubtitleInertPlayer: AVPlayer {
    private(set) var replacements = 0
    private(set) var attachedItems: [AVPlayerItem] = []
    private(set) var automaticCriteriaAtAttachment: [Bool] = []
    private(set) var seekTargets: [CMTime] = []
    override func replaceCurrentItem(with item: AVPlayerItem?) {
        if item != nil {
            replacements += 1
            attachedItems.append(item!)
            automaticCriteriaAtAttachment.append(appliesMediaSelectionCriteriaAutomatically)
        }
    }
    override func currentTime() -> CMTime { .invalid }
    override func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime, completionHandler: @escaping (Bool) -> Void) {
        seekTargets.append(time); completionHandler(true)
    }
}

#if os(tvOS)
/// A fixed presentation size makes the real controller's resize-aspect layout
/// testable without decoding video or starting a media clock.
private final class SubtitleSizedPlayerItem: AVPlayerItem {
    override var presentationSize: CGSize { CGSize(width: 1920, height: 1080) }
}
#endif

@MainActor final class ExternalSubtitleTests: XCTestCase {
    #if os(tvOS)
    func testExternalSubtitleLayoutClearsNativeTVHUDAndKeepsHiddenControlsPlacement() throws {
        let controller = PictureshowPlayerViewController()
        controller.player = AVPlayer(playerItem: SubtitleSizedPlayerItem(asset: AVMutableComposition()))
        defer { controller.player = nil }
        controller.loadViewIfNeeded()
        let video = CGRect(x: 0, y: 0, width: 1920, height: 1080)
        controller.view.frame = video
        let overlay = try XCTUnwrap(controller.contentOverlayView)
        overlay.frame = video
        let cue = "Fixture external subtitle"
        let preferences = PlaybackPreferences(subtitleSize: .normal, subtitleBackground: .box)

        controller.showSubtitle(cue, controlsVisible: true, preferences: preferences)
        controller.viewDidLayoutSubviews()
        XCTAssertEqual(overlay.bounds, video, "The test must exercise the full 1920×1080 video viewport")
        let caption = try XCTUnwrap(overlay.subviews.compactMap { $0 as? UILabel }.first { $0.text == cue })
        XCTAssertFalse(caption.isHidden)
        XCTAssertTrue(caption.frame.width > 0 && caption.frame.height > 0)
        XCTAssertTrue(video.contains(caption.frame))
        XCTAssertLessThanOrEqual(caption.frame.maxY, 648,
                                 "External subtitle overlaps native TV HUD: the bottom 40% must remain clear while controls are visible")

        controller.showSubtitle(cue, controlsVisible: false, preferences: preferences)
        controller.viewDidLayoutSubviews()
        XCTAssertEqual(caption.text, cue)
        XCTAssertFalse(caption.isHidden)
        XCTAssertTrue(caption.frame.width > 0 && caption.frame.height > 0)
        XCTAssertTrue(video.contains(caption.frame))
        XCTAssertEqual(caption.frame.maxY, 1032, accuracy: 1,
                       "Hiding native controls must restore captions near the ordinary bottom margin")

        controller.setInformationVisible(true)
        controller.viewDidLayoutSubviews()
        XCTAssertTrue(controller.informationVisible)
        XCTAssertFalse(caption.isHidden)
        XCTAssertTrue(caption.frame.width > 0 && caption.frame.height > 0)
        XCTAssertTrue(video.contains(caption.frame))
        XCTAssertLessThanOrEqual(caption.frame.maxY, 486,
                                 "External subtitle overlaps expanded TV Info: the bottom 55% must remain clear while Info is visible")

        controller.setInformationVisible(false)
        controller.viewDidLayoutSubviews()
        XCTAssertFalse(controller.informationVisible)
        XCTAssertEqual(caption.frame.maxY, 1032, accuracy: 1,
                       "Closing Info with native controls hidden must restore the ordinary bottom margin")

        let client = JellyfinClient(deviceID: "info-lifecycle-test", session: URLSession(configuration: ExternalSubtitleHTTP.configuration()))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "info-lifecycle-test")
        let session = PlaybackSession(client: client, account: account, item: MediaItem(id: "movie", name: "Fixture movie", type: "Movie"), player: try XCTUnwrap(controller.player))
        controller.installInformation(session: session)
        let info = try XCTUnwrap(controller.customInfoViewControllers.first)
        XCTAssertFalse(session.panelOpen)
        info.loadViewIfNeeded()
        info.viewWillAppear(false)
        XCTAssertTrue(controller.informationVisible, "Info must reserve caption space before its appearance animation begins")
        XCTAssertTrue(session.panelOpen, "Appearing Info must gate the early continuation countdown before didAppear")
        controller.viewDidLayoutSubviews()
        XCTAssertLessThanOrEqual(caption.frame.maxY, 486, "Info clearance must apply during appearance, before didAppear")
        info.viewDidAppear(false)
        XCTAssertTrue(controller.informationVisible)
        XCTAssertTrue(session.panelOpen, "Visible Info must gate the early continuation countdown")
        controller.showSubtitle(cue, controlsVisible: true, preferences: preferences)
        controller.viewDidLayoutSubviews()
        XCTAssertLessThanOrEqual(caption.frame.maxY, 486, "Info clearance must take priority over the ordinary HUD clearance")

        let normalFontSize = caption.font.pointSize
        let longCue = (1...40).map { "Fixture external subtitle line \($0)" }.joined(separator: "\n")
        controller.showSubtitle(longCue, controlsVisible: false, preferences: preferences)
        let fullCueHeight = caption.sizeThatFits(CGSize(width: video.width * 0.9, height: .greatestFiniteMagnitude)).height
        XCTAssertGreaterThan(fullCueHeight, 486, "The multiline fixture must exceed the entire caption region above Info")
        controller.viewDidLayoutSubviews()
        XCTAssertEqual(caption.text, longCue)
        XCTAssertFalse(caption.isHidden)
        XCTAssertTrue(caption.frame.width > 0 && caption.frame.height > 0)
        XCTAssertGreaterThanOrEqual(caption.frame.minY, 0, "An oversized cue must stay within the video instead of moving above it")
        XCTAssertTrue(video.contains(caption.frame))
        XCTAssertLessThanOrEqual(caption.frame.maxY, 486, "An oversized multiline cue must be clipped above Info rather than extend beneath it")
        XCTAssertEqual(caption.font.pointSize, normalFontSize, "Info clearance must retain the selected subtitle font size")

        controller.showSubtitle(cue, controlsVisible: false, preferences: preferences)
        controller.viewDidLayoutSubviews()
        XCTAssertEqual(caption.text, cue)
        info.viewWillDisappear(false)
        XCTAssertTrue(controller.informationVisible, "Info must retain caption clearance throughout its dismissal animation")
        XCTAssertTrue(session.panelOpen, "Disappearing Info must keep the early continuation countdown gated until didDisappear")
        controller.viewDidLayoutSubviews()
        XCTAssertLessThanOrEqual(caption.frame.maxY, 486, "Info clearance must remain active until dismissal finishes")
        info.viewDidDisappear(false)
        controller.viewDidLayoutSubviews()
        XCTAssertFalse(controller.informationVisible)
        XCTAssertFalse(session.panelOpen)
        XCTAssertEqual(caption.frame.maxY, 1032, accuracy: 1)
    }
    #endif

    private func asset(_ name: String = "selected", originMs: Int64 = 0) -> ExternalTextSubtitles.Asset {
        .init(id: name, url: URL(string: "https://media.example.test/captions/\(name).vtt")!, format: "vtt", originMs: originMs)
    }
    private func makeLoader(clock: SubtitleRetryClock) -> ExternalTextSubtitles {
        ExternalTextSubtitles(fetch: { try await ExternalTextSubtitles.fetchBounded($0, configuration: ExternalSubtitleHTTP.configuration()) }, sleep: { try await clock.sleep($0) })
    }
    private func eventually(_ message: String, _ predicate: () -> Bool, file: StaticString = #filePath, line: UInt = #line) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while ContinuousClock.now < deadline {
            if predicate() { return }
            try await Task.sleep(for: .milliseconds(5))
        }
        XCTFail(message, file: file, line: line)
    }
    private var captionRequests: [ExternalSubtitleHTTP.Recorded] { ExternalSubtitleHTTP.requests.filter { ExternalSubtitleHTTP.isCaptionPath($0.path) } }

    func testOnlySelectedTrackLoadsAndOffTrackChangeReleaseCancelHTTP() async throws {
        ExternalSubtitleHTTP.reset(.held)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        loader.select(nil)
        await Task.yield()
        XCTAssertTrue(captionRequests.isEmpty, "Off must not prefetch optional tracks")
        loader.select(asset())
        try await eventually("Selected request did not reach URLSession") { self.captionRequests.count == 1 }
        XCTAssertEqual(captionRequests.map(\.path), ["/captions/selected.vtt"])
        loader.select(asset("other"))
        try await eventually("Track change must cancel the old HTTP request and request only the new selection") {
            ExternalSubtitleHTTP.stoppedPaths.contains("/captions/selected.vtt") && self.captionRequests.count == 2
        }
        XCTAssertFalse(captionRequests.contains { $0.path.contains("unselected") })
        loader.select(nil)
        try await eventually("Off must cancel the active HTTP request") { ExternalSubtitleHTTP.stoppedPaths.contains("/captions/other.vtt") }
        XCTAssertTrue(loader.cues.isEmpty); XCTAssertNil(loader.text(atFullMediaMs: 121_000))
        loader.select(asset("third"))
        try await eventually("Third selected request did not begin") { self.captionRequests.count == 3 }
        loader.release()
        try await eventually("Release must cancel the selected request") { ExternalSubtitleHTTP.stoppedPaths.contains("/captions/third.vtt") }
        XCTAssertNil(loader.selected); XCTAssertFalse(loader.loading); XCTAssertTrue(loader.cues.isEmpty)
    }

    func testLateResponsesCannotPublishAfterTrackOffOrRelease() async throws {
        let transport = LateSubtitleFetch()
        let loader = ExternalTextSubtitles(fetch: { try await transport.fetch($0) })
        defer { loader.release(); transport.finish() }
        for action in ["track", "off", "release"] {
            loader.select(asset("old-" + action))
            try await eventually("Old response was not held") { transport.pending["/captions/old-\(action).vtt"] != nil }
            if action == "track" {
                loader.select(asset("new"))
                try await eventually("New response was not held") { transport.pending["/captions/new.vtt"] != nil }
                transport.complete("/captions/new.vtt", text: "WEBVTT\n\n00:02:00.000 --> 00:02:05.000\nNew selection")
                try await eventually("New selected cues did not publish") { loader.text(atFullMediaMs: 121_000) == "New selection" }
            } else if action == "off" { loader.select(nil) } else { loader.release() }
            transport.complete("/captions/old-\(action).vtt", text: "WEBVTT\n\n00:02:00.000 --> 00:02:05.000\nStale selection")
            // A queued uncooperative completion must get an opportunity to execute.
            try await Task.sleep(for: .milliseconds(20))
            XCTAssertEqual(loader.text(atFullMediaMs: 121_000), action == "track" ? "New selection" : nil)
            if action != "track" { XCTAssertTrue(loader.cues.isEmpty); XCTAssertNil(loader.selected) }
        }
    }

    func testOffOnAndLocalSeekReuseParsedFullFileButReleaseEvictsIt() async throws {
        ExternalSubtitleHTTP.reset(.valid)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        loader.select(asset())
        try await eventually("Full-file cues did not load") { !loader.loading && !loader.cues.isEmpty }
        XCTAssertEqual(loader.text(atFullMediaMs: 120_000), "Selected caption")
        XCTAssertNil(loader.text(atFullMediaMs: 125_000))
        loader.select(nil); loader.select(asset())
        XCTAssertEqual(loader.text(atFullMediaMs: 121_000), "Selected caption")
        XCTAssertNil(loader.text(atFullMediaMs: 0)); XCTAssertEqual(loader.text(atFullMediaMs: 124_999), "Selected caption")
        XCTAssertEqual(captionRequests.count, 1, "Off/on and local timeline queries must reuse parsed cues")
        loader.release(); loader.select(asset())
        try await eventually("A released media period must not retain its cache") { self.captionRequests.count == 2 && !loader.loading }
    }

    func testRebasedTextUsesExtractionOriginWhileFullFileRetainsAuthoredTime() async throws {
        let loader = ExternalTextSubtitles(fetch: { _ in Data("WEBVTT\n\n00:00:02.000 --> 00:00:04.000\nRebased".utf8) })
        defer { loader.release() }
        loader.select(asset("rebased", originMs: 120_000))
        try await eventually("Rebased cues did not load") { !loader.loading }
        XCTAssertNil(loader.text(atFullMediaMs: 2_000))
        XCTAssertEqual(loader.text(atFullMediaMs: 122_000), "Rebased")
        XCTAssertNil(loader.text(atFullMediaMs: 124_000))
        loader.select(asset("full", originMs: 0))
        try await eventually("Full-file cues did not load") { !loader.loading }
        XCTAssertEqual(loader.text(atFullMediaMs: 2_000), "Rebased")
        XCTAssertNil(loader.text(atFullMediaMs: 122_000))
    }

    private func readySession(loader: ExternalTextSubtitles, expectedCaptionPath: String? = "/jellyfin/captions/selected.vtt", subtitleIndex: Int = 4) async throws -> (PlaybackSession, SubtitleInertPlayer) {
        let client = JellyfinClient(deviceID: "external-subtitle-test", session: URLSession(configuration: ExternalSubtitleHTTP.configuration()))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "external-subtitle-test")
        var item = MediaItem(id: "movie", name: "Fixture movie", type: "Movie")
        item.mediaSources = [try JSONDecoder().decode(MediaSource.self, from: JSONSerialization.data(withJSONObject: ExternalSubtitleHTTP.source))]
        let player = SubtitleInertPlayer()
        let session = PlaybackSession(client: client, account: account, item: item, player: player, externalSubtitles: loader)
        try await session.prepare(startTicks: 0, audio: nil, subtitle: subtitleIndex)
        XCTAssertEqual(loader.selected?.url.path, expectedCaptionPath,
                       "Selected caption resolution must honor the configured server base or absolute DeliveryUrl")
        session.ready(version: 0)
        try await eventually("Optional selected subtitles must not gate real session readiness or Start") {
            !session.waitingForVideo && ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Sessions/Playing") }
        }
        return (session, player)
    }

    func testSameEmbeddedTrackPickPreservesItemAndViewing() async throws {
        ExternalSubtitleHTTP.reset(.valid, embeddedSelectedTrack: true)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader, expectedCaptionPath: nil)
        let first = try XCTUnwrap(player.attachedItems.first)
        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: session.decision?.subtitleIndex)
        XCTAssertEqual(player.replacements, 1, "Picking the active embedded track must not construct or attach another item")
        XCTAssertTrue(player.attachedItems.last === first)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("/Sessions/Playing") }.count, 1)
        XCTAssertFalse(ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Sessions/Playing/Stopped") })
        await session.stop()
    }

    func testAutomaticTrackIntentDoesNotBecomeRememberedReportSelection() async throws {
        ExternalSubtitleHTTP.reset(.valid)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, _) = try await readySession(loader: loader)
        XCTAssertEqual(session.decision?.audioIndex, 1, "Playback still uses the negotiated audio")
        XCTAssertEqual(session.decision?.subtitleIndex, 4, "Playback still uses the automatic caption intent")
        session.backgrounded()
        await session.stop()
        let reports = ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("/Sessions/Playing") || $0.path.hasSuffix("/Sessions/Playing/Progress") }
        XCTAssertTrue(reports.contains { $0.path.hasSuffix("/Sessions/Playing") })
        XCTAssertTrue(reports.contains { $0.path.hasSuffix("/Sessions/Playing/Progress") })
        for report in reports {
            XCTAssertNil(report.body["AudioStreamIndex"], "Automatic audio must not overwrite the viewer's remembered track")
            XCTAssertNil(report.body["SubtitleStreamIndex"], "Automatic captions must not overwrite the viewer's remembered track")
        }
    }

    func testViewerSameActivePickReportsOnlyChosenTypeAndPreservesOptionalRetry() async throws {
        ExternalSubtitleHTTP.reset(.held)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader)
        try await eventually("Selected optional request did not begin") { self.captionRequests.count == 1 }
        let item = try XCTUnwrap(player.attachedItems.first)
        let revision = loader.revision, attempts = loader.attemptCount
        XCTAssertEqual(session.diagnostics.counts["subtitle-select"], 1, "Opening the automatic choice emits one selection receipt")
        XCTAssertEqual(loader.selected?.selectionCount, 1)
        session.backgrounded()
        await session.selectTrack(type: "Subtitle", index: 4)
        try await eventually("A ready same-active subtitle pick must report its real index") {
            ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Progress") && ($0.body["SubtitleStreamIndex"] as? NSNumber)?.intValue == 4 }
        }
        let subtitleReport = try XCTUnwrap(ExternalSubtitleHTTP.requests.last { $0.path.hasSuffix("/Progress") })
        XCTAssertNil(subtitleReport.body["AudioStreamIndex"], "Choosing captions cannot make automatic audio a remembered choice")
        XCTAssertEqual(subtitleReport.body["IsPaused"] as? Bool, true)
        XCTAssertEqual(loader.revision, revision); XCTAssertEqual(loader.attemptCount, attempts)
        XCTAssertEqual(session.diagnostics.counts["subtitle-select"], 2, "An explicit same-active choice emits its own receipt")
        XCTAssertEqual(loader.selected?.selectionCount, 1, "The held load keeps its captured selection identity")
        XCTAssertEqual(captionRequests.count, 1); XCTAssertTrue(loader.loading)
        XCTAssertTrue(ExternalSubtitleHTTP.stoppedPaths.filter(ExternalSubtitleHTTP.isCaptionPath).isEmpty, "Same-active choice must preserve the held caption request; stopped caption paths: \(ExternalSubtitleHTTP.stoppedPaths.filter(ExternalSubtitleHTTP.isCaptionPath))")
        XCTAssertEqual(player.replacements, 1); XCTAssertTrue(player.attachedItems.last === item)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("/Sessions/Playing") }.count, 1)
        await session.selectTrack(type: "Audio", index: 1)
        await session.stop()
        let both = try XCTUnwrap(ExternalSubtitleHTTP.requests.last { $0.path.hasSuffix("/Progress") })
        XCTAssertEqual((both.body["AudioStreamIndex"] as? NSNumber)?.intValue, 1)
        XCTAssertEqual((both.body["SubtitleStreamIndex"] as? NSNumber)?.intValue, 4)
        XCTAssertEqual(player.replacements, 1)
    }

    func testViewerPickBeforeReadyReportsAtStartWithoutReplacingItem() async throws {
        ExternalSubtitleHTTP.reset(.valid, embeddedSelectedTrack: true)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let client = JellyfinClient(deviceID: "pending-track-test", session: URLSession(configuration: ExternalSubtitleHTTP.configuration()))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "pending-track-test")
        let player = SubtitleInertPlayer()
        let session = PlaybackSession(client: client, account: account, item: MediaItem(id: "movie", name: "Movie", type: "Movie"), player: player, externalSubtitles: loader)
        try await session.prepare(startTicks: 0, audio: nil, subtitle: 4)
        await session.selectTrack(type: "Subtitle", index: 4)
        XCTAssertEqual(player.replacements, 1)
        XCTAssertFalse(ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Sessions/Playing") || $0.path.hasSuffix("/Progress") })
        session.ready(version: 0)
        try await eventually("Pending viewer choice must report when Ready") { ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Sessions/Playing") } }
        let start = try XCTUnwrap(ExternalSubtitleHTTP.requests.first { $0.path.hasSuffix("/Sessions/Playing") })
        XCTAssertEqual((start.body["SubtitleStreamIndex"] as? NSNumber)?.intValue, 4)
        XCTAssertNil(start.body["AudioStreamIndex"])
        await session.stop()
    }

    func testSubtitleDiagnosticsCaptureVisibleOriginAndRejectStaleCallbacks() async throws {
        var events: [ExternalTextSubtitles.DiagnosticEvent] = []
        let loader = ExternalTextSubtitles(fetch: { _ in ExternalSubtitleHTTP.caption }, diagnostics: { events.append($0) })
        defer { loader.release() }
        loader.setContext(itemID: "first-item", viewingID: "first-viewing")
        let first = ExternalTextSubtitles.Asset(id: "opaque-first", url: URL(string: "https://media.example.test/first.vtt")!, format: "vtt", originMs: 30_000, streamIndex: 4, sourceID: "source-a", selectionCount: 7)
        loader.select(first)
        try await eventually("Selected text must finish parsing") { !loader.loading && !loader.cues.isEmpty }
        let revision = loader.revision
        XCTAssertEqual(events.map(\.kind), ["subtitle-loaded"])
        loader.didDisplayVisibleText(selection: revision)
        XCTAssertEqual(events.count, 1, "Loading alone cannot claim a visible cue")
        XCTAssertEqual(loader.text(atFullMediaMs: 151_000), "Selected caption")
        loader.didDisplayVisibleText(selection: revision)
        loader.didDisplayVisibleText(selection: revision)
        let visible = try XCTUnwrap(events.last)
        XCTAssertEqual(events.map(\.kind), ["subtitle-loaded", "subtitle-cues"])
        XCTAssertEqual(visible.selectionCount, 7); XCTAssertEqual(visible.streamIndex, 4)
        XCTAssertEqual(visible.sourceID, "source-a"); XCTAssertEqual(visible.originMs, 30_000)
        XCTAssertEqual(visible.firstCueAtMs, 150_000); XCTAssertEqual(visible.visibleAtMs, 151_000)
        XCTAssertEqual(visible.itemID, "first-item"); XCTAssertEqual(visible.viewingID, "first-viewing")
        loader.setContext(itemID: "second-item", viewingID: "second-viewing")
        loader.select(.init(id: "opaque-second", url: URL(string: "https://media.example.test/second.vtt")!, format: "vtt", originMs: 0, streamIndex: 9, sourceID: "source-b", selectionCount: 8))
        try await eventually("New selected text must finish parsing") { !loader.loading && !loader.cues.isEmpty }
        let before = events.count
        loader.didDisplayVisibleText(selection: revision)
        XCTAssertEqual(events.count, before, "Old layout cannot attribute a cue to a newly selected stream")
        XCTAssertEqual(loader.text(atFullMediaMs: 121_000), "Selected caption")
        loader.didDisplayVisibleText(selection: loader.revision)
        XCTAssertEqual(events.last?.selectionCount, 8); XCTAssertEqual(events.last?.streamIndex, 9)
        XCTAssertEqual(events.last?.sourceID, "source-b"); XCTAssertEqual(events.last?.itemID, "second-item")
    }

    func testCaptionAppearanceObserverRegistersOnceAcrossOverlayVisibility() {
        let controller = PictureshowPlayerViewController()
        controller.loadViewIfNeeded()
        XCTAssertEqual(controller.captionObserverRegistrations, 1)
        for visible in [true, false, true, false] {
            controller.showSubtitle("Fixture cue", controlsVisible: visible, preferences: .init())
            controller.loadViewIfNeeded()
            #if os(tvOS)
            controller.setInformationVisible(visible)
            #endif
            controller.viewDidLayoutSubviews()
        }
        XCTAssertEqual(controller.captionObserverRegistrations, 1, "HUD and Info transitions cannot re-register system caption listeners")
    }

    #if os(iOS)
    func testPhonePaused45TrackPanelKeepsLabelsCached() async throws {
        try await measurePausedTrackPanels()
    }
    #else
    func testTVPausedExpandedInfo45TracksKeepsLabelsCached() async throws {
        try await measurePausedTrackPanels()
    }
    #endif
    private func measurePausedTrackPanels() async throws {
        ExternalSubtitleHTTP.reset(.valid, embeddedSelectedTrack: true, expandedTracks: true)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, _) = try await readySession(loader: loader, expectedCaptionPath: nil)
        session.backgrounded()
        try await eventually("Both menus must prepare independently of opening a panel") { session.trackLabels.prepared != nil }
        XCTAssertEqual(session.trackLabels.audio.count + session.trackLabels.subtitles.count, 45)
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let window = UIWindow(windowScene: scene)
        let root = UIViewController()
        window.rootViewController = root; window.makeKeyAndVisible()
        defer { window.isHidden = true; window.rootViewController = nil }
        func attach(_ child: UIViewController, frame: CGRect) {
            root.addChild(child); child.view.frame = frame
            root.view.addSubview(child.view); child.didMove(toParent: root)
        }
        let frame = window.bounds
        let native = UIHostingController(rootView: NativePlayer(session: session, close: {}))
        attach(native, frame: CGRect(x: 0, y: 0, width: frame.width, height: frame.height / 2))
        #if os(iOS)
        let panel = UIHostingController(rootView: PlayerOptions(session: session, kind: "tracks", done: {}))
        attach(panel, frame: CGRect(x: 0, y: frame.height / 2, width: frame.width, height: frame.height / 2))
        let event = "phonePlayerOptions.body"
        #else
        func playerController(_ parent: UIViewController) -> PictureshowPlayerViewController? {
            if let found = parent as? PictureshowPlayerViewController { return found }
            return parent.children.compactMap { playerController($0) }.first
        }
        try await eventually("Hosted NativePlayer did not construct its controller") { playerController(native) != nil }
        let controller = try XCTUnwrap(playerController(native))
        let panel = try XCTUnwrap(controller.customInfoViewControllers.first)
        // This measures real Info content invalidation. Native expansion/focus is
        // a separate XCUI case; manually hosting it makes no transport claim.
        attach(panel, frame: CGRect(x: 0, y: frame.height / 2, width: frame.width, height: frame.height / 2))
        let event = "expandedInfo.body"
        try await eventually("Info lifecycle must gate continuation while visible") { session.panelOpen && controller.informationVisible }
        #endif
        try await eventually("The actual hosted panel body did not render") { session.diagnostics.counts[event, default: 0] > 0 }
        root.view.layoutIfNeeded()
        let labels = session.trackLabels.labelsBuilt, revision = session.trackLabels.cacheRevision
        let body = session.diagnostics.counts[event, default: 0]
        let snapshots = session.diagnostics.counts["nativePlayer.snapshot", default: 0]
        var ticks = 0
        let observation = session.$positionTicks.dropFirst().sink { _ in ticks += 1 }
        defer { observation.cancel() }
        let deadline = ContinuousClock.now.advanced(by: .seconds(8))
        while ticks < 8 && ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(20)) }
        XCTAssertGreaterThanOrEqual(ticks, 8, "Measure real 500 ms periodic publications while paused")
        let bodyDelta = session.diagnostics.counts[event, default: 0] - body
        let snapshotDelta = session.diagnostics.counts["nativePlayer.snapshot", default: 0] - snapshots
        XCTAssertGreaterThan(bodyDelta, 0, "The measurement must include actual panel reevaluations")
        XCTAssertGreaterThan(snapshotDelta, 0, "The native wrapper must receive the periodic updates")
        XCTAssertEqual(session.trackLabels.labelsBuilt - labels, 0)
        XCTAssertEqual(session.trackLabels.cacheRevision - revision, 0)
        XCTAssertTrue(session.isPaused)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        print("F3_PANEL_MEASUREMENT event=\(event) tracks=45 ticks=\(ticks) bodies=\(bodyDelta) nativeSnapshots=\(snapshotDelta) labelsBuiltDelta=\(session.trackLabels.labelsBuilt - labels) cacheRevisionDelta=\(session.trackLabels.cacheRevision - revision)")
        // Sensitivity control: relevant metadata must invalidate the cached labels.
        var streams = try XCTUnwrap(session.decision?.source.mediaStreams)
        let index = try XCTUnwrap(streams.firstIndex { $0.type == "Audio" })
        streams[index].title = "Changed relevant track title"
        session.trackLabels.update(streams)
        try await eventually("Changed metadata must prepare a new complete menu") { session.trackLabels.prepared != nil }
        XCTAssertEqual(session.trackLabels.cacheRevision, revision + 1)
        XCTAssertEqual(session.trackLabels.labelsBuilt - labels, 45)
        await session.stop()
    }

    func testRelativeAndAbsoluteDeliveryURLsReachTheirExactResolvedCaptionPaths() async throws {
        for (reference, expectedPath) in [
            ("/captions/selected.vtt", "/jellyfin/captions/selected.vtt"),
            ("captions/selected.vtt", "/jellyfin/captions/selected.vtt"),
            ("https://media.example.test/captions/selected.vtt", "/captions/selected.vtt")
        ] {
            ExternalSubtitleHTTP.reset(.valid, captionReference: reference)
            let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
            defer { loader.release(); clock.finish() }
            let (session, _) = try await readySession(loader: loader, expectedCaptionPath: expectedPath)
            try await eventually("Resolved selected caption did not load from \(expectedPath)") { !loader.loading && !loader.cues.isEmpty }
            XCTAssertEqual(captionRequests.map(\.path), [expectedPath], "No other caption path may be prefetched")
            XCTAssertEqual(loader.text(atFullMediaMs: 121_000), "Selected caption")
            await session.stop(); clock.finish()
        }
    }

    func testHTTP500RetriesForVirtual242SecondsWithoutGatingAVReadyOrStart() async throws {
        try await assertOptionalFailureDoesNotGateAV(.http500)
    }

    /// Exercise app ordering around real prepare/Ready/track-replacement calls.
    /// The spy never attaches an item, so these assertions inspect the app's
    /// automatic-selection contract without decoder or AVKit startup assumptions.
    func testEveryAssetAttachesWithAutomaticCriteriaBeforeItsOwnReady() async throws {
        ExternalSubtitleHTTP.reset(.valid)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader, expectedCaptionPath: nil, subtitleIndex: -1)
        XCTAssertEqual(player.automaticCriteriaAtAttachment, [true], "The first asset must not inherit disabled automatic selection")
        XCTAssertFalse(player.appliesMediaSelectionCriteriaAutomatically, "Explicit track ownership begins after current Ready")

        for (audioIndex, version) in [(-1, 1), (1, 2)] {
            await session.changeTracks(audio: audioIndex, subtitle: -1)
            XCTAssertEqual(player.automaticCriteriaAtAttachment, Array(repeating: true, count: version + 1),
                           "A track replacement must re-enable startup criteria after the prior Ready disabled them")
            XCTAssertTrue(session.waitingForVideo)
            XCTAssertTrue(player.appliesMediaSelectionCriteriaAutomatically, "The replacement has not received its own Ready")

            session.ready(version: version - 1)
            await Task.yield()
            XCTAssertTrue(player.appliesMediaSelectionCriteriaAutomatically, "A stale Ready must not disable criteria for the replacement generation")
            XCTAssertTrue(session.waitingForVideo)

            session.ready(version: version)
            try await eventually("The current replacement Ready did not settle") { !session.waitingForVideo }
            XCTAssertFalse(player.appliesMediaSelectionCriteriaAutomatically, "Current Ready can take explicit track ownership")
            XCTAssertNil(session.error)
        }
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 3)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("/Sessions/Playing") }.count, 1,
                       "Track replacements remain inside one viewing")
        await session.stop()
    }
    /// DeliveryMethod describes the server's proposed playback delivery, not the
    /// external source's extractability. A known text sidecar remains optional
    /// when video is separately negotiated with captions Off.
    func testExternalSubripProposedAsEncodeStillLoadsOptionalFallbackAndReportsIndexZero() async throws {
        ExternalSubtitleHTTP.reset(.valid, captionReference: nil, encodedExternalSubrip: true)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let expectedPath = "/jellyfin/Videos/movie/source/Subtitles/0/Stream.vtt"
        let (session, player) = try await readySession(loader: loader, expectedCaptionPath: expectedPath, subtitleIndex: 0)
        try await eventually("An external SubRip source proposed as Encode must still load through optional VTT extraction") {
            self.captionRequests.count == 1 && !loader.loading && !loader.cues.isEmpty
        }
        XCTAssertEqual(captionRequests.map(\.path), [expectedPath])
        if let captionRequest = captionRequests.first {
            let query = URLComponents(url: captionRequest.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertEqual(query.first { $0.name == "StartPositionTicks" }?.value, "0")
            XCTAssertEqual(query.first { $0.name == "CopyTimestamps" }?.value, "true")
        }
        XCTAssertEqual(loader.text(atFullMediaMs: 121_000), "Selected caption", "Fallback Stream.vtt must be parsed as VTT despite the source SubRip codec")
        let negotiations = ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }
        XCTAssertEqual(negotiations.count, 1)
        XCTAssertEqual((negotiations.first?.body["SubtitleStreamIndex"] as? NSNumber)?.intValue, -1)
        XCTAssertEqual(session.decision?.subtitleIndex, 0, "Zero is a selected stream, not Off")
        let start = try XCTUnwrap(ExternalSubtitleHTTP.requests.first { $0.path.hasSuffix("/Sessions/Playing") })
        XCTAssertNil(start.body["SubtitleStreamIndex"], "The automatic external index zero is playback intent, not a remembered viewer choice")
        await session.selectTrack(type: "Subtitle", index: 0)
        try await eventually("An explicit same-active index zero must be reported") {
            ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Progress") && ($0.body["SubtitleStreamIndex"] as? NSNumber)?.intValue == 0 }
        }
        XCTAssertEqual(player.replacements, 1); XCTAssertNil(session.error)
        let explanation = session.subtitleTimingExplanation.lowercased()
        XCTAssertTrue(explanation.contains("pictureshow") && explanation.contains("external text") && explanation.contains("overlay"),
                      "Info must identify the actual app-rendered external text path, even when delivery was proposed as Encode")
        XCTAssertFalse(explanation.contains("avkit uses the system caption renderer"),
                       "Selected external text is not being rendered by AVKit")
        XCTAssertTrue(explanation.contains("timing") && explanation.contains("not available"))
        XCTAssertFalse(session.subtitleTimingAvailable)

        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: -1)
        XCTAssertEqual(session.decision?.subtitleIndex, -1)
        XCTAssertNil(loader.selected, "Off must leave the real external renderer selection empty")
        XCTAssertFalse(session.subtitleTimingExplanation.lowercased().contains("overlay"), "Off cannot claim the external overlay is rendering")
        XCTAssertFalse(session.subtitleTimingAvailable)
        XCTAssertEqual(player.replacements, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        await session.stop()

        // Exercise an actual embedded selection through prepare, rather than
        // assigning a decision or testing an unselected source's metadata.
        ExternalSubtitleHTTP.reset(.valid, embeddedSelectedTrack: true)
        let (embeddedSession, _) = try await readySession(loader: loader, expectedCaptionPath: nil, subtitleIndex: 4)
        XCTAssertEqual(embeddedSession.decision?.subtitleIndex, 4)
        XCTAssertFalse(try XCTUnwrap(embeddedSession.decision?.source.mediaStreams.first { $0.index == 4 }).isExternal)
        XCTAssertNil(loader.selected)
        XCTAssertTrue(captionRequests.isEmpty, "A nonexternal selection must not start the app's optional text loader")
        XCTAssertFalse(embeddedSession.subtitleTimingExplanation.lowercased().contains("overlay"),
                       "A nonexternal selection cannot claim the external overlay is rendering")
        XCTAssertFalse(embeddedSession.subtitleTimingAvailable)
        await embeddedSession.stop()
    }

    func testColdSelectedCuesPublishWhilePausedWithoutClockMovementOrResume() async throws {
        ExternalSubtitleHTTP.reset(.held)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader, expectedCaptionPath: nil, subtitleIndex: -1)
        XCTAssertTrue(captionRequests.isEmpty)
        session.togglePlayback()
        XCTAssertTrue(session.isPaused)
        session.seek(toTicks: 121_000 * 10_000)
        try await eventually("Paused seek did not publish its intended position") { session.positionTicks == 121_000 * 10_000 }
        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: 4)
        try await eventually("Cold selected caption request was not held") { self.captionRequests.count == 1 }
        XCTAssertNil(session.subtitleCue); XCTAssertTrue(loader.cues.isEmpty)
        let seeksAtLoad = player.seekTargets.count
        ExternalSubtitleHTTP.completeHeldCaptions()
        try await eventually("Cold cues must reach the session overlay while paused, without a resume or moving A/V clock") {
            session.subtitleCue == "Selected caption"
        }
        XCTAssertEqual(loader.text(atFullMediaMs: 121_000), "Selected caption")
        XCTAssertTrue(session.isPaused)
        XCTAssertEqual(session.positionTicks, 121_000 * 10_000)
        XCTAssertEqual(player.seekTargets.count, seeksAtLoad)
        XCTAssertEqual(player.replacements, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        XCTAssertEqual(captionRequests.count, 1)
        XCTAssertNil(session.error)
        await session.stop()
    }
    func testUnresolvedSelectedHTTPDoesNotGateSessionReadyStartAndStopCancelsIt() async throws {
        ExternalSubtitleHTTP.reset(.held)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader)
        try await eventually("Selected HTTP request was not held") { self.captionRequests.count == 1 }
        XCTAssertTrue(loader.loading); XCTAssertTrue(loader.cues.isEmpty)
        XCTAssertTrue(clock.delays.isEmpty, "An unresolved request is distinct from a failed retry")
        XCTAssertFalse(session.waitingForVideo); XCTAssertNil(session.error); XCTAssertFalse(session.terminal)
        XCTAssertEqual(player.replacements, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("/Sessions/Playing") }.count, 1)
        await session.stop()
        try await eventually("Stopping the viewing must cancel its unresolved optional request") {
            ExternalSubtitleHTTP.stoppedPaths.contains("/jellyfin/captions/selected.vtt")
        }
        XCTAssertNil(loader.selected); XCTAssertTrue(loader.cues.isEmpty)
    }
    func testMalformedSelectedTextRetriesWithoutConsumingAVFailureBudget() async throws {
        try await assertOptionalFailureDoesNotGateAV(.malformed)
    }
    private func assertOptionalFailureDoesNotGateAV(_ failure: ExternalSubtitleHTTP.Delivery) async throws {
        ExternalSubtitleHTTP.reset(failure)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader)
        try await eventually("Optional retry clock did not cross the 242-second held-failure interval") { clock.elapsedMs >= 242_000 }
        XCTAssertEqual(Array(clock.delays.prefix(12)), [1_000,2_000,3_000,4_000,5_000,6_000,7_000,8_000,9_000,10_000,10_000,10_000])
        XCTAssertTrue(loader.unavailable); XCTAssertTrue(loader.loading)
        XCTAssertGreaterThanOrEqual(captionRequests.count, 29)
        XCTAssertFalse(captionRequests.contains { $0.path.contains("unselected") })
        XCTAssertFalse(session.waitingForVideo); XCTAssertNil(session.error); XCTAssertNil(session.reportError)
        XCTAssertFalse(session.terminal); XCTAssertEqual(session.status, "Direct play")
        XCTAssertEqual(player.replacements, 1, "Optional text failure must not replace or fallback the A/V asset")
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, 1)
        XCTAssertFalse(ExternalSubtitleHTTP.requests.contains { $0.path.hasSuffix("/Sessions/Playing/Stopped") || $0.path.contains("UserPlayedItems") })
        let negotiation = try XCTUnwrap(ExternalSubtitleHTTP.requests.first { $0.path.hasSuffix("PlaybackInfo") })
        XCTAssertEqual((negotiation.body["SubtitleStreamIndex"] as? NSNumber)?.intValue, -1, "A/V must negotiate captions Off")
        XCTAssertEqual(session.decision?.subtitleIndex, 4, "The selected optional track remains the automatic user-facing choice")
        let start = try XCTUnwrap(ExternalSubtitleHTTP.requests.first { $0.path.hasSuffix("/Sessions/Playing") })
        XCTAssertNil(start.body["SubtitleStreamIndex"], "An automatic optional-caption choice must not become a remembered server choice under PLAY-20")
        XCTAssertFalse(session.showStartupWaitHint(version: 0), "Ready A/V must reject a late startup deadline")
        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: -1)
        let countAtOff = captionRequests.count
        await Task.yield()
        XCTAssertNil(loader.selected); XCTAssertNil(session.subtitleCue)
        XCTAssertEqual(captionRequests.count, countAtOff)
        XCTAssertEqual(player.replacements, 1, "Off must be a local text change")
        await session.stop()
    }

    func testSessionTextOffOnAndSeekDoNotRenegotiateOrReplaceVideo() async throws {
        ExternalSubtitleHTTP.reset(.valid)
        let clock = SubtitleRetryClock(), loader = makeLoader(clock: clock)
        defer { loader.release(); clock.finish() }
        let (session, player) = try await readySession(loader: loader)
        try await eventually("Selected cues did not load") { !loader.loading }
        let playbackRequests = ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count
        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: -1)
        await session.changeTracks(audio: session.decision?.audioIndex, subtitle: 4)
        session.seek(toTicks: 121_000 * 10_000)
        try await eventually("Local seek did not publish") { session.positionTicks == 121_000 * 10_000 }
        XCTAssertEqual(loader.text(atFullMediaMs: session.positionTicks / 10_000), "Selected caption")
        XCTAssertEqual(captionRequests.count, 1)
        XCTAssertEqual(player.replacements, 1)
        XCTAssertEqual(ExternalSubtitleHTTP.requests.filter { $0.path.hasSuffix("PlaybackInfo") }.count, playbackRequests)
        XCTAssertNil(session.error)
        await session.stop()
        XCTAssertTrue(loader.cues.isEmpty); XCTAssertNil(loader.selected)
    }

    func testDeclared16MiBOverflowIsRejectedBeforeCaptionParsing() async throws {
        ExternalSubtitleHTTP.reset(.declaredOversize)
        do {
            _ = try await ExternalTextSubtitles.fetchBounded(asset().url, configuration: ExternalSubtitleHTTP.configuration())
            XCTFail("An advertised optional caption larger than 16 MiB must fail")
        } catch { XCTAssertEqual(captionRequests.count, 1) }
    }

    func testUnannounced16MiBStreamOverflowIsIsolatedToOptionalText() async throws {
        ExternalSubtitleHTTP.reset(.streamedOversize)
        let completed = expectation(description: "Bounded optional subtitle stream rejects overflow")
        let task = Task {
            do {
                _ = try await ExternalTextSubtitles.fetchBounded(asset().url, configuration: ExternalSubtitleHTTP.configuration())
                XCTFail("A streamed optional caption larger than 16 MiB must fail")
            } catch { }
            completed.fulfill()
        }
        await fulfillment(of: [completed], timeout: 2)
        task.cancel()
        await task.value
        XCTAssertEqual(captionRequests.count, 1)
    }
}
