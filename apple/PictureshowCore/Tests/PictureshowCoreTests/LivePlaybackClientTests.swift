// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

private final class LivePlaybackProtocol: URLProtocol {
    nonisolated(unsafe) static var respond: ((URLRequest, [String: Any]?) throws -> (Int, Any))?
    nonisolated(unsafe) static var seen: [(request: URLRequest, body: [String: Any]?)] = []
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var chunk = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&chunk, maxLength: chunk.count); if n <= 0 { break }; data.append(chunk, count: n) }
        }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        Self.seen.append((request, body))
        do {
            let (status, value) = try Self.respond!(request, body)
            let payload = value is Data ? value as! Data : try JSONSerialization.data(withJSONObject: value)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: payload); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

/// LIVE-04: a live channel's source is opened exactly once with the server's OpenToken, the opened stream is the one played
/// and reported, and closing a stream the server already released (404) is not an error.
final class LivePlaybackClientTests: XCTestCase {
    private let account = Account(server: Server(url: URL(string: "https://media.example.test")!), userId: "viewer", username: "Viewer",
                                  accessToken: "fixture-token", deviceID: "fixture-device")

    override func setUp() { LivePlaybackProtocol.seen = []; LivePlaybackProtocol.respond = nil }
    override func tearDown() { LivePlaybackProtocol.respond = nil; LivePlaybackProtocol.seen = [] }

    private func client() -> JellyfinClient {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [LivePlaybackProtocol.self]
        return JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: config))
    }

    private func query(_ request: URLRequest) -> [String: String] {
        Dictionary((URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name, $0.value ?? "") }) { $1 }
    }

    private let listed: [String: Any] = ["Id": "tuner-source", "RequiresOpening": true, "OpenToken": "open-token-1", "SupportsDirectPlay": false,
                                         "SupportsDirectStream": true, "IsInfiniteStream": true, "MediaStreams": []]

    private func opened(defaultAudio: Int = 1) -> [String: Any] {
        ["Id": "opened-source", "LiveStreamId": "live-1", "RequiresClosing": true, "IsInfiniteStream": true, "SupportsDirectPlay": false,
         "SupportsDirectStream": false, "TranscodingUrl": "/videos/channel/master.m3u8?LiveStreamId=live-1&PlaySessionId=session-1",
         "TranscodingSubProtocol": "hls", "TranscodingContainer": "ts", "DefaultAudioStreamIndex": defaultAudio, "DefaultSubtitleStreamIndex": -1,
         "MediaStreams": [["Index": 0, "Type": "Video", "Codec": "h264"], ["Index": 1, "Type": "Audio", "Codec": "aac", "IsDefault": true]]]
    }

    func testLiveSourceOpensOnceWithTheNegotiatedFieldsAndIsOwned() async throws {
        LivePlaybackProtocol.respond = { request, _ in
            switch request.url!.path {
            case "/Items/channel/PlaybackInfo": return (200, ["PlaySessionId": "session-1", "MediaSources": [self.listed]])
            case "/LiveStreams/Open": return (200, ["MediaSource": self.opened()])
            default: return (500, Data())
            }
        }
        let decision = try await client().prepare(account: account, itemID: "channel")
        let paths = LivePlaybackProtocol.seen.map { "\($0.request.httpMethod ?? "") \($0.request.url!.path)" }
        XCTAssertEqual(paths, ["POST /Items/channel/PlaybackInfo", "POST /LiveStreams/Open"], "One discovery and one Open; no second tuner")

        let info = try XCTUnwrap(LivePlaybackProtocol.seen[0].body)
        XCTAssertEqual(info["AutoOpenLiveStream"] as? Bool, false, "The server must not open a tuner during discovery")

        let open = try XCTUnwrap(LivePlaybackProtocol.seen[1].body)
        XCTAssertEqual(open["OpenToken"] as? String, "open-token-1")
        XCTAssertEqual(open["ItemId"] as? String, "channel")
        XCTAssertEqual(open["UserId"] as? String, "viewer")
        XCTAssertEqual(open["PlaySessionId"] as? String, "session-1")
        XCTAssertEqual(open["DirectPlayProtocols"] as? [String], ["Http"])
        for key in ["EnableDirectPlay", "EnableDirectStream", "AlwaysBurnInSubtitleWhenTranscoding"] {
            XCTAssertNotNil(open[key] as? Bool, key)
            XCTAssertEqual(open[key] as? Bool, info[key] as? Bool, "Open repeats the negotiated \(key)")
        }
        XCTAssertNotNil(open["DeviceProfile"] as? [String: Any], "The opened stream is negotiated against this device's profile")
        XCTAssertEqual((open["MaxStreamingBitrate"] as? NSNumber)?.intValue, (info["MaxStreamingBitrate"] as? NSNumber)?.intValue)
        XCTAssertEqual((open["StartTimeTicks"] as? NSNumber)?.int64Value, 0)
        XCTAssertNil(query(LivePlaybackProtocol.seen[1].request)["userId"], "Open carries the user in its body only")

        XCTAssertEqual(decision.liveStreamId, "live-1")
        XCTAssertEqual(decision.ownedLiveStreamId, "live-1", "RequiresClosing makes this client the owner")
        XCTAssertTrue(decision.isLive)
        XCTAssertEqual(decision.source.id, "opened-source", "The opened source replaces the listed one")
    }

    func testOpenedSourceWithoutDefaultAudioIsAskedAgainForTheSameStream() async throws {
        LivePlaybackProtocol.respond = { request, body in
            switch request.url!.path {
            case "/Items/channel/PlaybackInfo":
                if body?["LiveStreamId"] != nil { return (200, ["PlaySessionId": "session-2", "MediaSources": [self.opened(defaultAudio: -1)]]) }
                return (200, ["PlaySessionId": "session-1", "MediaSources": [self.listed]])
            case "/LiveStreams/Open": return (200, ["MediaSource": self.opened(defaultAudio: -1)])
            default: return (500, Data())
            }
        }
        let decision = try await client().prepare(account: account, itemID: "channel")
        let paths = LivePlaybackProtocol.seen.map { $0.request.url!.path }
        XCTAssertEqual(paths, ["/Items/channel/PlaybackInfo", "/LiveStreams/Open", "/Items/channel/PlaybackInfo"])
        XCTAssertEqual(paths.filter { $0 == "/LiveStreams/Open" }.count, 1, "The repair must not open a second tuner")
        let repair = try XCTUnwrap(LivePlaybackProtocol.seen[2].body)
        XCTAssertEqual(repair["LiveStreamId"] as? String, "live-1")
        XCTAssertEqual(repair["MediaSourceId"] as? String, "opened-source")
        XCTAssertEqual((repair["AudioStreamIndex"] as? NSNumber)?.intValue, 1)
        XCTAssertEqual(decision.ownedLiveStreamId, "live-1")
        XCTAssertEqual(decision.audioIndex, 1)
    }

    func testSourceThatRequiresOpeningWithoutATokenIsRefusedWithoutOpening() async throws {
        var tokenless = listed
        tokenless["OpenToken"] = NSNull()
        let discovery: [String: Any] = ["MediaSources": [tokenless]]
        LivePlaybackProtocol.respond = { request, _ -> (Int, Any) in
            if request.url!.path == "/Items/channel/PlaybackInfo" { return (200, discovery) }
            return (500, Data())
        }
        do {
            _ = try await client().prepare(account: account, itemID: "channel")
            XCTFail("A source that requires opening cannot play without its token")
        } catch ClientError.invalidResponse {}
        XCTAssertFalse(LivePlaybackProtocol.seen.contains { $0.request.url!.path == "/LiveStreams/Open" })
    }

    func testCloseTreatsAnAlreadyReleasedStreamAsClosed() async throws {
        LivePlaybackProtocol.respond = { _, _ in (404, Data()) }
        try await client().closeLiveStream(account: account, liveStreamID: "live-1")
        let close = try XCTUnwrap(LivePlaybackProtocol.seen.first?.request)
        XCTAssertEqual(close.httpMethod, "POST")
        XCTAssertEqual(close.url!.path, "/LiveStreams/Close")
        XCTAssertEqual(query(close)["liveStreamId"], "live-1")
        XCTAssertNil(query(close)["userId"])
    }

    func testCloseStillReportsOtherServerFailures() async throws {
        LivePlaybackProtocol.respond = { _, _ in (500, Data()) }
        do {
            try await client().closeLiveStream(account: account, liveStreamID: "live-1")
            XCTFail("Only 404 counts as already closed")
        } catch ClientError.server(let code) { XCTAssertEqual(code, 500) }
    }
}
