// SPDX-License-Identifier: MPL-2.0
import XCTest
import Foundation
import AVFoundation
import PictureshowCore
import PictureshowTransport
@testable import Pictureshow_iOS

/// Delivers AVPlayer seek completions only when a test asks, making callback order
/// deterministic without decoding media or depending on a simulator clock.
private final class DeferredSeekPlayer: AVPlayer {
    private(set) var seekTargets: [CMTime] = []
    private var completions: [(Bool) -> Void] = []

    override func replaceCurrentItem(with item: AVPlayerItem?) { }
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

    static func reset() {
        playbackInfoCount = 0
        playbackInfoBodies = []
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
        var body: Any = [:]
        if path.hasSuffix("PlaybackInfo") {
            Self.playbackInfoCount += 1
            if let value = Self.requestBody(request) { Self.playbackInfoBodies.append(value) }
            let source = Self.playbackInfoCount == 1 ? "first-source" : "replacement-source"
            body = [
                "MediaSources": [[
                    "Id": source,
                    "SupportsDirectPlay": true,
                    "MediaStreams": [
                        ["Index": 0, "Type": "Video", "Codec": "h264"],
                        ["Index": 1, "Type": "Audio", "Codec": "aac"]
                    ]
                ]],
                "PlaySessionId": source + "-session"
            ]
        } else if path.hasSuffix("/Items/first") || path.hasSuffix("/Items/next") {
            body = ["Id": path.hasSuffix("/Items/first") ? "first" : "next", "Type": "Episode", "Chapters": []]
        } else if path.contains("MediaSegments") || path.hasSuffix("/Episodes") {
            body = ["Items": []]
        }

        do {
            let data = try JSONSerialization.data(withJSONObject: body)
            let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
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

    private func makeReadySession() async throws {
        SeekRaceProtocol.reset()
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
        try await session.prepare(startTicks: 0, audio: nil, subtitle: -1)
        session.ready(version: 0)
        for _ in 0..<100 {
            if !session.waitingForVideo { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The test session did not become timeline-ready")
    }

    private func waitForSeekCount(_ count: Int) async throws {
        for _ in 0..<100 {
            if player.seekTargets.count >= count { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected " + String(count) + " controlled AVPlayer seek requests, observed " + String(player.seekTargets.count))
    }

    private func drainMainActor() async throws {
        // AVPlayer invokes its callback synchronously here, but PlaybackSession intentionally
        // hops to MainActor before committing; let that queued task run deterministically.
        try await Task.sleep(for: .milliseconds(30))
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
        XCTAssertEqual(session.positionTicks, 0, "The earlier callback must not publish its stale position")

        let latest = session.seek(by: 5)
        XCTAssertEqual(latest, second + Ticks.from(seconds: 5), "Relative seek must build on the newest pending target")
        let latestAVTarget = try XCTUnwrap(player.seekTargets.last)
        XCTAssertEqual(latestAVTarget.seconds, Ticks.seconds(latest), accuracy: 0.001)

        player.completeSeek(at: 1, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, 0, "A second stale callback must not replace the newest pending seek")
        player.completeSeek(at: 2, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest)
        await session.stop()
    }

    /// A callback from the old decoder must not mutate playback after track replacement
    /// has installed a fresh decision and pending target in the same viewing.
    func testOldStreamSeekCannotCommitIntoReplacementDecision() async throws {
        try await makeReadySession()
        let oldTarget = Ticks.from(seconds: 50)
        session.seek(toTicks: oldTarget)
        XCTAssertEqual(player.seekTargets.count, 1)

        await session.changeTracks(audio: 1, subtitle: -1)
        XCTAssertEqual(session.decision?.source.id, "replacement-source")
        let replacementBody = try XCTUnwrap(SeekRaceProtocol.playbackInfoBodies.last)
        XCTAssertEqual((replacementBody["StartTimeTicks"] as? NSNumber)?.int64Value, oldTarget,
                       "Track replacement must negotiate from the newest pending seek, not the old decoder clock")
        XCTAssertEqual(session.decision?.startTicks, oldTarget)
        session.ready(version: 1)
        try await waitForSeekCount(2) // readiness seeks to the replacement's requested start
        player.completeSeek(at: 1, success: true)
        for _ in 0..<100 {
            if !session.waitingForVideo { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertFalse(session.waitingForVideo)
        XCTAssertEqual(session.positionTicks, oldTarget, "The replacement stream should publish its negotiated start position")
        let replacementTarget = Ticks.from(seconds: 20)
        session.seek(toTicks: replacementTarget)
        XCTAssertEqual(player.seekTargets.count, 3)

        player.completeSeek(at: 0, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, oldTarget, "A stale callback must not replace the negotiated start position")
        let latest = session.seek(by: 5)
        XCTAssertEqual(latest, replacementTarget + Ticks.from(seconds: 5), "The old callback must not erase the replacement stream's pending seek")
        XCTAssertEqual(player.seekTargets.count, 4)

        player.completeSeek(at: 3, success: true)
        try await drainMainActor()
        XCTAssertEqual(session.positionTicks, latest)
        await session.stop()
    }
}
