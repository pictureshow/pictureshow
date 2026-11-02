// SPDX-License-Identifier: MPL-2.0
import XCTest
import Foundation
import AVFoundation
import PictureshowCore
import PictureshowTransport
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// Delivers AVPlayer seek completions only when a test asks, making callback order
/// deterministic without decoding media or depending on a simulator clock.
private final class DeferredSeekPlayer: AVPlayer {
    private(set) var seekTargets: [CMTime] = []
    private(set) var itemReplacementCount = 0
    private(set) var transportCommandCount = 0
    private(set) var playCount = 0
    private var completions: [(Bool) -> Void] = []

    override func replaceCurrentItem(with item: AVPlayerItem?) {
        if item != nil { itemReplacementCount += 1 }
    }
    override func play() { transportCommandCount += 1; playCount += 1; super.play() }
    override func pause() { transportCommandCount += 1; super.pause() }
    /// Keep wall-clock sampling from the production tick loop from injecting a decoder time
    /// into this callback-order test; PlaybackSession's published position is authoritative.
    override func currentTime() -> CMTime { .invalid }

    override func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime,
                       completionHandler: @escaping (Bool) -> Void) {
        seekTargets.append(time)
        completions.append(completionHandler)
    }

    func completeSeek(at index: Int, success: Bool) {
        completions[index](success)
    }
}

private final class SeekRaceProtocol: URLProtocol {
    static var playbackInfoCount = 0
    static var playbackInfoBodies: [[String: Any]] = []
    static var startReportBodies: [[String: Any]] = []
    static var streamTitle = "Original"
    static var reportStatuses: [Int] = []
    static var reportBodies: [(String, [String:Any])] = []
    static var includeNextEpisode = false
    static var holdNegotiation = false
    static var heldNegotiations: [SeekRaceProtocol] = []

    static func reset(includeNext: Bool = false) {
        playbackInfoCount = 0; streamTitle = "Original"; holdNegotiation = false; heldNegotiations = []
        playbackInfoBodies = []
        startReportBodies = []; reportBodies = []; reportStatuses = []
        includeNextEpisode = includeNext
    }

    private static func requestBody(_ request: URLRequest) -> [String: Any]? {
        var data = request.httpBody ?? Data()
        if data.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
        }
        guard let decoded = try? JSONSerialization.jsonObject(with: data) else { return nil }
        return decoded as? [String: Any]
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let path = request.url!.path
        if path.hasSuffix("PlaybackInfo"), Self.holdNegotiation { Self.heldNegotiations.append(self); return }
        if path.contains("/Sessions/Playing"), let report = Self.requestBody(request) {
            let kind = path.hasSuffix("Stopped") ? "stop" : path.hasSuffix("Progress") ? "progress" : "start"
            Self.reportBodies.append((kind, report))
        }
        var body: Any = [:]
        if path.hasSuffix("PlaybackInfo") {
            Self.playbackInfoCount += 1
            let playbackRequest = Self.requestBody(request)
            if let playbackRequest { Self.playbackInfoBodies.append(playbackRequest) }
            let itemID = path.split(separator: "/").dropLast().last.map(String.init) ?? "first"
            let source = playbackRequest?["MediaSourceId"] as? String
                ?? (itemID == "next" ? "next-source" : "first-source")
            var mediaSource: [String: Any] = [
                "Id": source,
                "DefaultAudioStreamIndex": 1,
                "MediaStreams": [
                    ["Index": 0, "Type": "Video", "Codec": "h264"],
                    ["Index": 1, "Type": "Audio", "Codec": "aac", "Title": Self.streamTitle],
                    ["Index": 2, "Type": "Audio", "Codec": "aac"]
                ]
            ]
            if playbackRequest?["EnableDirectPlay"] as? Bool == true {
                mediaSource["SupportsDirectPlay"] = true
            } else {
                mediaSource["TranscodingUrl"] = "/Videos/\(itemID)/master.m3u8"
            }
            body = [
                "MediaSources": [mediaSource],
                "PlaySessionId": "fixture-session-\(Self.playbackInfoCount)"
            ]
        } else if path.hasSuffix("/Sessions/Playing") {
            if let report = Self.requestBody(request) { Self.startReportBodies.append(report) }
        } else if path.hasSuffix("/Items/first") || path.hasSuffix("/Items/next") {
            body = ["Id": path.hasSuffix("/Items/first") ? "first" : "next", "Type": "Episode", "Chapters": []]
        } else if path.contains("MediaSegments") {
            body = ["Items": []]
        } else if path.hasSuffix("/Episodes") {
            let episodes: [[String: Any]] = Self.includeNextEpisode ? [
                ["Id": "first", "Name": "First", "Type": "Episode", "SeriesId": "series"],
                ["Id": "next", "Name": "Next", "Type": "Episode", "SeriesId": "series"]
            ] : []
            body = ["Items": episodes]
        }

        do {
            let data = try JSONSerialization.data(withJSONObject: body)
            let response = HTTPURLResponse(url: request.url!, statusCode: path.contains("/Sessions/Playing") && !Self.reportStatuses.isEmpty ? Self.reportStatuses.removeFirst() : 200, httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() { }
}

@MainActor final class SeekCompletionTests: XCTestCase {
    private var session: PlaybackSession!
    private var player: DeferredSeekPlayer!

    override func tearDown() {
        SeekRaceProtocol.reset()
        session = nil
        player = nil
    }

    private func makePreparedSession(startTicks: Int64, includeNextEpisode: Bool = false) async throws {
        SeekRaceProtocol.reset(includeNext: includeNextEpisode)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SeekRaceProtocol.self]
        let client = JellyfinClient(deviceID: "seek-race-device", session: URLSession(configuration: configuration))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!),
                             userId: "viewer", username: "Viewer", accessToken: "fixture-token",
                             deviceID: "seek-race-device")
        var item = MediaItem(id: "first", name: "First", type: "Episode")
        item.seriesId = "series"
        item.mediaSources = [MediaSource(id: "first-source", supportsDirectPlay: true)]
        player = DeferredSeekPlayer()
        session = PlaybackSession(client: client, account: account, item: item, player: player)
        try await session.prepare(startTicks: startTicks, audio: nil, subtitle: -1)
    }

    private func makeReadySession(includeNextEpisode: Bool = false) async throws {
        try await makePreparedSession(startTicks: 0, includeNextEpisode: includeNextEpisode)
        session.ready(version: 0)
        for _ in 0..<100 {
            if !session.waitingForVideo,
               (!includeNextEpisode || session.nextItem != nil) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The test session did not become timeline-ready and load its requested next item")
    }

    private func waitForSeekCount(_ count: Int) async throws {
        for _ in 0..<100 {
            if player.seekTargets.count >= count { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected " + String(count) + " controlled AVPlayer seek requests, observed " + String(player.seekTargets.count))
    }

    private func waitForStartReport() async -> [String: Any]? {
        for _ in 0..<100 {
            if let report = SeekRaceProtocol.startReportBodies.first { return report }
            try? await Task.sleep(for: .milliseconds(10))
        }
        return nil
    }

    private func drainMainActor() async throws {
        // AVPlayer invokes its callback synchronously here, but PlaybackSession intentionally
        // hops to MainActor before committing; let that queued task run deterministically.
        try await Task.sleep(for: .milliseconds(30))
    }

    func testF5CoveredBeforeReadyAndReturnRemainPausedWithPausedStart() async throws {
        try await makePreparedSession(startTicks: 0)
        session.backgrounded()
        XCTAssertFalse(session.isForeground)
        XCTAssertFalse(session.nativePlayRequested(), "Covered native Play cannot clear retained pause")
        session.ready(version: 0)
        let pendingStart = await waitForStartReport()
        let start = try XCTUnwrap(pendingStart)
        XCTAssertEqual(start["IsPaused"] as? Bool, true)
        XCTAssertTrue(session.isPaused)
        XCTAssertEqual(player.playCount, 0)
        session.foregrounded()
        XCTAssertTrue(session.isPaused, "Visibility restores no Play intent")
        XCTAssertEqual(player.playCount, 0)
        XCTAssertTrue(session.nativePlayRequested())
        XCTAssertFalse(session.isPaused, "Only deliberate foreground Play releases the latch")
        await session.stop()
    }
    func testF5CoveredPendingSeekCannotResumeOrAutomaticallyAdvance() async throws {
        try await makeReadySession(includeNextEpisode: true)
        let plays = player.playCount
        session.seek(by: 10)
        try await waitForSeekCount(1)
        session.backgrounded()
        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertTrue(session.isPaused)
        XCTAssertEqual(player.playCount, plays)
        let item = session.item.id
        await session.advance(automatic: true)
        XCTAssertEqual(session.item.id, item)
        session.foregrounded()
        await session.advance(automatic: true)
        XCTAssertEqual(session.item.id, item, "Returning with a latched pause cannot trigger Next")
        await session.stop()
        let reports = session.diagnostics.reportLines.filter { !$0.contains("kind=stop") }
        XCTAssertTrue(reports.contains { $0.contains("paused=true") })
    }
    func testFailedStartIsNotReplayedAndStopRetryHasAnotherReceipt() async throws {
        try await makePreparedSession(startTicks: 0)
        SeekRaceProtocol.reportStatuses = [503]
        session.ready(version: 0)
        for _ in 0..<100 {
            if session.diagnostics.reportLines.count == 1 { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(session.diagnostics.reportLines.first?.contains("kind=start") == true)
        XCTAssertTrue(session.diagnostics.reportLines.first?.contains("status=503 failure=http") == true)
        await session.selectTrack(type: "Audio", index: 1)
        for _ in 0..<100 {
            if session.diagnostics.reportLines.contains(where: { $0.contains("kind=progress") }) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(SeekRaceProtocol.reportBodies.filter { $0.0 == "start" }.count, 1)
        XCTAssertNil(session.reportError)
        SeekRaceProtocol.reportStatuses = [503, 204]
        await session.stop()
        let lines = session.diagnostics.reportLines.filter { $0.contains("kind=stop") }
        XCTAssertEqual(lines.count, 2)
        XCTAssertTrue(lines.first?.contains("status=503 failure=http") == true)
        XCTAssertTrue(lines.last?.contains("status=204 failure=none") == true)
        XCTAssertNil(session.reportError)
        XCTAssertEqual(SeekRaceProtocol.reportBodies.filter { $0.0 == "stop" }.count, 2)
    }

    func testQueuedMenuPickCannotSelectAgainstReplacedMetadata() async throws {
        try await makeReadySession()
        await session.trackLabels.worker?.value
        let oldGeneration = try XCTUnwrap(session.trackLabels.prepared?.generation)
        SeekRaceProtocol.holdNegotiation = true
        let replacement = Task { await session.changeTracks(audio: 2, subtitle: -1) }
        for _ in 0..<100 {
            if !SeekRaceProtocol.heldNegotiations.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(SeekRaceProtocol.heldNegotiations.count, 1)
        let stale = Task { await session.selectTrack(type: "Audio", index: 1, menuGeneration: oldGeneration) }
        await Task.yield()
        SeekRaceProtocol.streamTitle = "Replacement"
        SeekRaceProtocol.holdNegotiation = false
        let held = SeekRaceProtocol.heldNegotiations; SeekRaceProtocol.heldNegotiations = []
        held.forEach { $0.startLoading() }
        await replacement.value; await stale.value
        XCTAssertEqual(session.decision?.audioIndex, 2)
        XCTAssertFalse(session.audioChosenByViewer)
        XCTAssertEqual(SeekRaceProtocol.playbackInfoCount, 2)
        XCTAssertNotEqual(session.trackLabels.cacheRevision, oldGeneration)
        await session.stop()
    }

    func testEveryReportReceiptMatchesWireBodyAndIndependentChoiceOrigins() async throws {
        try await makeReadySession()
        _ = await waitForStartReport()
        await session.selectTrack(type: "Audio", index: 1)
        await session.stop()
        let lines = session.diagnostics.reportLines
        let wire = SeekRaceProtocol.reportBodies
        XCTAssertEqual(lines.count, wire.count)
        XCTAssertGreaterThanOrEqual(wire.count, 3)
        for (offset, record) in wire.enumerated() {
            let line = lines[offset], kind = record.0, body = record.1
            XCTAssertTrue(line.contains("seq=\(offset + 1) kind=\(kind) "), line)
            let fields = PlaybackReportFields.describe(kind: kind,
                positionTicks: (body["PositionTicks"] as? NSNumber)?.int64Value ?? -1,
                audio: body["AudioStreamIndex"] as? Int, subtitle: body["SubtitleStreamIndex"] as? Int,
                audioViewer: kind != "start", subtitleViewer: false)
            XCTAssertTrue(line.contains(fields), line)
            XCTAssertTrue(line.contains("status=200 failure=none elapsedMs="), line)
            XCTAssertFalse(line.contains("fixture-token")); XCTAssertFalse(line.contains("https:"))
        }
        XCTAssertEqual(wire.first?.0, "start"); XCTAssertEqual(wire.last?.0, "stop")
        XCTAssertNil(wire.first?.1["AudioStreamIndex"])
        XCTAssertNil(wire.last?.1["AudioStreamIndex"])
        XCTAssertTrue(wire.contains { $0.0 == "progress" && $0.1["AudioStreamIndex"] as? Int == 1 })
    }

    func testRelativeStepsDuringReplacementNegotiationSurviveUntilMatchingReady() async throws {
        try await makeReadySession()
        session.togglePlayback() // Paused intent survives every handoff.
        session.seek(toTicks: Ticks.from(seconds: 80))
        SeekRaceProtocol.holdNegotiation = true
        let change = Task { await session.changeTracks(audio: 2, subtitle: -1) }
        for _ in 0..<100 {
            if !SeekRaceProtocol.heldNegotiations.isEmpty { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(SeekRaceProtocol.heldNegotiations.count, 1)
        for expected in [90.0, 100.0, 110.0] {
            XCTAssertEqual(Ticks.seconds(session.seek(by: 10)), expected, accuracy: 0.001)
            XCTAssertEqual(Ticks.seconds(session.positionTicks), expected, accuracy: 0.001)
        }
        XCTAssertEqual(Ticks.seconds(session.seek(by: -10)), 100, accuracy: 0.001)
        XCTAssertEqual(Ticks.seconds(session.seek(by: 10)), 110, accuracy: 0.001)
        SeekRaceProtocol.holdNegotiation = false
        let held = SeekRaceProtocol.heldNegotiations; SeekRaceProtocol.heldNegotiations = []
        held.forEach { $0.startLoading() }
        await change.value
        XCTAssertEqual(Ticks.seconds(session.positionTicks), 110, accuracy: 0.001,
                       "A late PlaybackInfo response must not rewind the accumulated target")
        session.ready(version: 1)
        try await waitForSeekCount(2)
        XCTAssertEqual(player.seekTargets.last?.seconds ?? -1, 110, accuracy: 0.001)
        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertTrue(session.waitingForVideo)
        player.completeSeek(at: 1, success: true)
        try await drainMainActor()
        XCTAssertFalse(session.waitingForVideo)
        XCTAssertTrue(session.isPaused)
        XCTAssertEqual(Ticks.seconds(session.positionTicks), 110, accuracy: 0.001)
        await session.stop()
    }

    func testNativeSeekCompletionRequiresNewestRevisionAndDecoder() async throws {
        try await makeReadySession()
        XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 90), 90)
        XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 90), 100)
        session.nativeSeekCompleted(.init(generation: 0, revision: 1, target: Ticks.from(seconds: 90)), success: true)
        session.nativeSeekCompleted(.init(generation: -1, revision: 2, target: Ticks.from(seconds: 100)), success: true)
        session.nativeSeekCompleted(.init(generation: 0, revision: 2, target: Ticks.from(seconds: 100)), success: false)
        XCTAssertEqual(session.diagnostics.counts["seek.nativeCompleted", default: 0], 0)
        XCTAssertEqual(Ticks.seconds(session.positionTicks), 80)
        XCTAssertEqual(Ticks.seconds(session.seek(by: 10)), 90)
        // Ordinary seek invalidates every outstanding native receipt as well.
        session.nativeSeekCompleted(.init(generation: 0, revision: 2, target: Ticks.from(seconds: 100)), success: true)
        XCTAssertEqual(Ticks.seconds(session.positionTicks), 90)
        XCTAssertEqual(session.diagnostics.counts["seek.nativeCompleted", default: 0], 0)
        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        await session.stop()
    }

    func testFailedMatchingNativeSeekReturnsToObservedClockForReportAndNextStep() async throws {
        try await makeReadySession()
        XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 90), 90)
        session.nativeSeekCompleted(.init(generation: 0, revision: 1, target: Ticks.from(seconds: 90)), success: false)
        XCTAssertEqual(Ticks.seconds(session.positionTicks), 80)
        XCTAssertEqual(session.diagnostics.counts["seek.nativeFailed", default: 0], 1)
        for _ in 0..<100 {
            if SeekRaceProtocol.reportBodies.contains(where: { $0.0 == "progress" && ($0.1["PositionTicks"] as? NSNumber)?.int64Value == Ticks.from(seconds: 80) }) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(SeekRaceProtocol.reportBodies.contains(where: { $0.0 == "progress" && ($0.1["PositionTicks"] as? NSNumber)?.int64Value == Ticks.from(seconds: 80) }),
                      "A failed native seek must report the observed clock, never the unvisited target")
        XCTAssertEqual(Ticks.seconds(session.seek(by: 10)), 90)
        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        await session.stop()
    }

    func testFailedProgrammaticSeekAlsoReconcilesBeforeNextRelativeStep() async throws {
        try await makeReadySession()
        session.seek(toTicks: Ticks.from(seconds: 30))
        player.completeSeek(at: 0, success: false)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, 0)
        XCTAssertEqual(Ticks.seconds(session.seek(by: 10)), 10)
        player.completeSeek(at: 1, success: true)
        try await drainMainActor()
        await session.stop()
    }

    func testNativeTransportStepsAccumulateBeforeClockAcknowledges() async throws {
        try await makeReadySession()
        for expected in [90.0, 100.0, 110.0] {
            XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 90), expected, accuracy: 0.001)
            XCTAssertEqual(Ticks.seconds(session.positionTicks), expected, accuracy: 0.001)
        }
        XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 70), 100, accuracy: 0.001)
        XCTAssertEqual(session.nativeSeekTarget(from: 80, to: 90), 110, accuracy: 0.001)
        XCTAssertEqual(player.seekTargets.count, 0, "AVKit performs the seek using the returned target")
        await session.stop()
    }

    /// A successful completion from the first of two queued seeks must not clear the newer
    /// pending target. A follow-up relative seek proves which target remained authoritative.
    func testEarlierSuccessfulSeekCannotCommitOverNewerPendingSeek() async throws {
        try await makeReadySession()
        let first = Ticks.from(seconds: 10)
        let second = Ticks.from(seconds: 30)
        session.seek(toTicks: first)
        session.seek(toTicks: second)
        XCTAssertEqual(player.seekTargets.count, 2)

        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, second, "The earlier callback must not replace the pending UI target")

        let latest = session.seek(by: 5)
        XCTAssertEqual(latest, second + Ticks.from(seconds: 5), "Relative seek must build on the newest pending target")
        let latestAVTarget = try XCTUnwrap(player.seekTargets.last)
        XCTAssertEqual(latestAVTarget.seconds, Ticks.seconds(latest), accuracy: 0.001)

        player.completeSeek(at: 1, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest, "A second stale callback must not replace the newest pending seek")
        player.completeSeek(at: 2, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest)
        await session.stop()
    }

    /// A target accepted while the resume seek is in flight must replace that target even
    /// when it is zero, and a superseded AVPlayer completion may legitimately return false.
    func testSeekDuringInitialReadySeekUsesNewestTarget() async throws {
        try await assertInitialReadySeekScenario(viewerSeconds: 47, oldSeekSuccess: true,
                                                 scenario: "newer nonzero target")
        try await assertInitialReadySeekScenario(viewerSeconds: 0, oldSeekSuccess: true,
                                                 scenario: "newer zero target")
        try await assertInitialReadySeekScenario(viewerSeconds: 47, oldSeekSuccess: false,
                                                 scenario: "interrupted old completion")
    }

    private func assertInitialReadySeekScenario(viewerSeconds: Double, oldSeekSuccess: Bool,
                                                 scenario: String) async throws {
        let startupTarget = Ticks.from(seconds: 12)
        let viewerTarget = Ticks.from(seconds: viewerSeconds)
        try await makePreparedSession(startTicks: startupTarget)
        session.ready(version: 0)
        try await waitForSeekCount(1)
        guard !player.seekTargets.isEmpty else { await session.stop(); return }
        XCTAssertEqual(player.seekTargets[0].seconds, Ticks.seconds(startupTarget), accuracy: 0.001)

        session.seek(toTicks: viewerTarget)
        XCTAssertEqual(session.positionTicks, viewerTarget,
                       "A viewer seek during startup should become the authoritative pending position (\(scenario))")

        player.completeSeek(at: 0, success: oldSeekSuccess)
        try await drainMainActor()
        if !oldSeekSuccess {
            XCTAssertNil(session.error,
                         "An interrupted startup seek must not reject the newer viewer target (\(scenario))")
        }
        try await waitForSeekCount(2)
        guard player.seekTargets.count >= 2 else {
            // The current implementation can finish readiness using only the captured
            // stale target. Keep this regression as a reported test failure, not an index trap.
            await session.stop()
            return
        }
        let newestAVTarget = player.seekTargets[1]
        XCTAssertEqual(newestAVTarget.seconds, Ticks.seconds(viewerTarget), accuracy: 0.001,
                       "Readiness must seek to the newer viewer target after the stale startup target completes (\(scenario))")
        player.completeSeek(at: 1, success: true)
        for _ in 0..<100 {
            if !session.waitingForVideo { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(session.waitingForVideo, "Readiness should finish after the newest target lands (\(scenario))")
        XCTAssertNil(session.error, "The newest target should complete without a startup error (\(scenario))")
        XCTAssertEqual(session.positionTicks, viewerTarget, "The newest viewer target should remain published (\(scenario))")

        let startReport = await waitForStartReport()
        XCTAssertNotNil(startReport, "Readiness should send its initial start report (\(scenario))")
        if let startReport {
            XCTAssertEqual((startReport["PositionTicks"] as? NSNumber)?.int64Value, viewerTarget,
                           "The server start report must use the newest viewer seek, not the stale resume target (\(scenario))")
        }
        await session.stop()
    }

    /// A callback from the old decoder must not mutate playback after track replacement
    /// has installed a fresh decision and pending target in the same viewing.
    func testOldStreamSeekCannotCommitIntoReplacementDecision() async throws {
        try await makeReadySession()
        let oldTarget = Ticks.from(seconds: 50)
        session.seek(toTicks: oldTarget)
        session.seek(toTicks: oldTarget)
        XCTAssertEqual(player.seekTargets.count, 2)
        guard player.seekTargets.count == 2 else { await session.stop(); return }

        let originalPlaySessionID = try XCTUnwrap(session.decision?.playSessionId)
        XCTAssertEqual(session.decision?.audioIndex, 1)
        XCTAssertEqual(session.decision?.subtitleIndex, -1)
        let originalRequestCount = SeekRaceProtocol.playbackInfoCount
        let originalReplacementCount = player.itemReplacementCount
        let committedPosition = session.positionTicks
        await session.changeTracks(audio: 1, subtitle: -1)
        XCTAssertEqual(SeekRaceProtocol.playbackInfoCount, originalRequestCount,
                       "Same-audio Off to Off must not renegotiate the A/V stream")
        XCTAssertEqual(player.itemReplacementCount, originalReplacementCount,
                       "Same-audio Off to Off must retain the current A/V asset")
        XCTAssertEqual(session.decision?.playSessionId, originalPlaySessionID)
        XCTAssertEqual(player.seekTargets.count, 2, "A no-op track choice must not issue another seek")
        XCTAssertEqual(session.positionTicks, committedPosition,
                       "A no-op track choice must not commit the pending seek early")

        await session.changeTracks(audio: 2, subtitle: -1)
        XCTAssertEqual(SeekRaceProtocol.playbackInfoCount, originalRequestCount + 1,
                       "A real alternate audio track must negotiate a replacement decision")
        XCTAssertEqual(player.itemReplacementCount, originalReplacementCount + 1)
        XCTAssertEqual(session.decision?.audioIndex, 2,
                       "The replacement decision must select the actual alternate audio stream")
        XCTAssertEqual(session.decision?.source.id, "first-source",
                       "A same-item track replacement must honor the requested MediaSourceId")
        XCTAssertNotEqual(session.decision?.playSessionId, originalPlaySessionID,
                          "The replacement decoder should own a fresh play-session identifier")
        let replacementBody = try XCTUnwrap(SeekRaceProtocol.playbackInfoBodies.last)
        XCTAssertEqual((replacementBody["AudioStreamIndex"] as? NSNumber)?.intValue, 2,
                       "The replacement request must ask the server for alternate audio index 2")
        XCTAssertEqual(replacementBody["MediaSourceId"] as? String, "first-source",
                       "The same-item track request must remain bound to the original media source")
        XCTAssertEqual((replacementBody["StartTimeTicks"] as? NSNumber)?.int64Value, oldTarget,
                       "Track replacement must negotiate from the newest pending seek, not the old decoder clock")
        XCTAssertEqual(session.decision?.startTicks, oldTarget)
        session.ready(version: 1)
        try await waitForSeekCount(3) // readiness seeks to the replacement's requested start
        XCTAssertEqual(player.seekTargets.count, 3)
        guard player.seekTargets.count == 3 else { await session.stop(); return }
        let replacementTransportCount = player.transportCommandCount
        // This old callback has the same seek revision and pending target as the
        // replacement's startup. Only its decoder generation makes it stale.
        player.completeSeek(at: 1, success: true)
        try await drainMainActor()
        XCTAssertTrue(session.waitingForVideo, "An old decoder callback must not finish replacement readiness")
        XCTAssertEqual(player.transportCommandCount, replacementTransportCount,
                       "An old decoder must not issue Play/Pause into the waiting replacement")
        player.completeSeek(at: 2, success: true)
        for _ in 0..<100 {
            if !session.waitingForVideo { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(session.waitingForVideo)
        XCTAssertEqual(session.positionTicks, oldTarget, "The replacement stream should publish its negotiated start position")
        let replacementTarget = Ticks.from(seconds: 20)
        session.seek(toTicks: replacementTarget)
        XCTAssertEqual(player.seekTargets.count, 4)
        guard player.seekTargets.count == 4 else { await session.stop(); return }

        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, replacementTarget, "A stale callback must not replace the new pending position")
        let latest = session.seek(by: 5)
        XCTAssertEqual(latest, replacementTarget + Ticks.from(seconds: 5), "The old callback must not erase the replacement stream's pending seek")
        XCTAssertEqual(player.seekTargets.count, 5)
        guard player.seekTargets.count == 5 else { await session.stop(); return }

        player.completeSeek(at: 4, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest)
        await session.stop()
    }

    /// Advancing creates a new viewing at zero. A late callback from the previous episode
    /// must neither trigger a startup seek on the next item nor erase its new pending target.
    func testLateOldSeekCannotLeakIntoNextViewing() async throws {
        try await makeReadySession(includeNextEpisode: true)
        XCTAssertEqual(session.nextItem?.id, "next")
        let oldTarget = Ticks.from(seconds: 50)
        session.seek(toTicks: oldTarget)
        XCTAssertEqual(player.seekTargets.count, 1)

        await session.advance()
        XCTAssertEqual(session.item.id, "next")
        XCTAssertEqual(session.decision?.itemID, "next")
        XCTAssertEqual(session.decision?.source.id, "next-source",
                       "The adjacent item must negotiate its own distinct media source")
        XCTAssertEqual(session.decision?.startTicks, 0)
        let nextPlaybackBody = try XCTUnwrap(SeekRaceProtocol.playbackInfoBodies.last)
        XCTAssertNotEqual(nextPlaybackBody["MediaSourceId"] as? String, "first-source",
                          "The adjacent item must not reuse the previous item's source binding")
        XCTAssertEqual((nextPlaybackBody["StartTimeTicks"] as? NSNumber)?.int64Value, 0,
                       "A new episode must negotiate from its own beginning")
        session.ready(version: 1)
        var resolvedUnexpectedStartupSeek = false
        for _ in 0..<100 {
            if player.seekTargets.count > 1, !resolvedUnexpectedStartupSeek {
                // Let a pre-fix inherited startup seek finish so the test fails by assertion,
                // not by leaving ready() suspended on the controlled player.
                player.completeSeek(at: 1, success: true)
                resolvedUnexpectedStartupSeek = true
            }
            if !session.waitingForVideo { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(session.waitingForVideo)
        XCTAssertEqual(player.seekTargets.count, 1, "The next episode must start at zero without seeking to the old episode's pending target")
        XCTAssertEqual(session.positionTicks, 0)

        let newTarget = Ticks.from(seconds: 20)
        session.seek(toTicks: newTarget)
        XCTAssertEqual(player.seekTargets.count, 2)
        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, newTarget, "The old viewing callback must not publish into the next episode")
        let latest = session.seek(by: 5)
        XCTAssertEqual(latest, newTarget + Ticks.from(seconds: 5), "The old viewing callback must not clear the next episode's pending seek")
        player.completeSeek(at: player.seekTargets.count - 1, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest)
        await session.stop()
    }
}
