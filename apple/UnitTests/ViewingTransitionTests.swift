// SPDX-License-Identifier: MPL-2.0
import XCTest
import PictureshowCore
@testable import Pictureshow_iOS

private final class ViewingProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var failPlayed = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let path = request.url!.path
        var status = 200
        var body: Any = [:]
        if path.hasSuffix("PlaybackInfo") {
            let source = path.contains("/first/") ? "first-source" : "next-source"
            body = ["MediaSources": [["Id": source, "SupportsDirectPlay": true, "MediaStreams": [["Index":0,"Type":"Video","Codec":"h264"],["Index":1,"Type":"Audio","Codec":"aac"]]]], "PlaySessionId": source + "-session"]
        } else if path.hasSuffix("/Items/first") || path.hasSuffix("/Items/next") {
            body = ["Id":path.hasSuffix("first") ? "first" : "next", "Type":"Episode", "Chapters":[]]
        } else if path.contains("MediaSegments") { body = ["Items":[]] }
        else if path.hasSuffix("/Episodes") {
            body = ["Items":[["Id":"first","Name":"First","Type":"Episode","SeriesId":"series"],["Id":"next","Name":"Next","Type":"Episode","SeriesId":"series"]]]
        } else if path.hasSuffix("UserPlayedItems/first"), Self.failPlayed { status = 503 }
        let data = try! JSONSerialization.data(withJSONObject: body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class ViewingTransitionTests: XCTestCase {
    private var session: PlaybackSession!
    override func tearDown() { ViewingProtocol.requests = []; ViewingProtocol.failPlayed = false }
    private func setupViewing() async throws {
        ViewingProtocol.requests = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ViewingProtocol.self]
        let client = JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: configuration))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
        var item = MediaItem(id: "first", name: "First", type: "Episode")
        item.seriesId = "series"
        item.mediaSources = [MediaSource(id: "first-source", supportsDirectPlay: true)]
        session = PlaybackSession(client: client, account: account, item: item)
        try await session.prepare(startTicks: 0, audio: nil, subtitle: -1)
        for _ in 0..<100 {
            if session.nextItem != nil { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The real next-episode HTTP lookup did not publish its result")
    }
    /// The next episode has no embedded sources in the list DTO. Its negotiation must discover
    /// its own source rather than binding the prior episode's source ID or caption metadata.
    func testContinuationNegotiatesTheNextEpisodesOwnSource() async throws {
        try await setupViewing()
        await session.advance()
        XCTAssertEqual(session.item.id, "next")
        XCTAssertEqual(session.decision?.source.id, "next-source")
        XCTAssertNil(session.error)
        let requests = ViewingProtocol.requests.filter { $0.url!.path.contains("/next/PlaybackInfo") }
        XCTAssertEqual(requests.count, 1)
        let body = try requestBody(XCTUnwrap(requests.first))
        XCTAssertNil(body["MediaSourceId"], "A previous source ID would bind negotiation to the wrong item")
        await session.stop()
    }
    /// A failed watched-state write cannot be erased by a successful Stop or silently open the
    /// next episode. The final-detail Retry repeats the same desired Played=true intent.
    func testFailedCompletionBlocksNextAndRemainsRetryableAfterStop() async throws {
        try await setupViewing()
        ViewingProtocol.failPlayed = true
        await session.advance()
        XCTAssertEqual(session.item.id, "first")
        XCTAssertNotNil(session.reportError)
        XCTAssertNotNil(session.error)
        XCTAssertFalse(ViewingProtocol.requests.contains { $0.url!.path.contains("/next/PlaybackInfo") })
        await session.stop()
        XCTAssertNotNil(session.reportError)
        ViewingProtocol.failPlayed = false
        await session.retrySaving()
        XCTAssertNil(session.reportError)
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("UserPlayedItems/first") }.count, 2)
    }
    private func requestBody(_ request: URLRequest) throws -> [String:Any] {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; data.append(buffer, count: count) }
        }
        return try JSONSerialization.jsonObject(with: data) as! [String:Any]
    }
}
