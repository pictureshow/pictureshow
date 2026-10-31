// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Network
@testable import PictureshowTransport

final class PlaybackRelayTests: XCTestCase {
    func testManifestRewritesVariantSegmentsKeysMapsAndSubtitlesAgainstManifestDirectory() throws {
        let base = URL(string: "https://media.test/jellyfin/video/master.m3u8?ApiKey=secret")!
        let manifest = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,URI="../subs/eng.m3u8?ApiKey=secret"
        #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
        #EXT-X-MAP:URI="init.mp4"
        variant/main.m3u8
        segment.ts?offset=6&api_key=secret
        """
        var mapped: [URL] = []
        let output = try PlaybackRelay.rewriteManifest(manifest, base: base) { url in
            mapped.append(PlaybackRelay.withoutCredentials(url))
            return URL(string: "http://127.0.0.1:5555/\(mapped.count)")!
        }
        XCTAssertEqual(mapped.map(\.absoluteString), ["https://media.test/jellyfin/subs/eng.m3u8", "https://media.test/jellyfin/video/key.bin", "https://media.test/jellyfin/video/init.mp4", "https://media.test/jellyfin/video/variant/main.m3u8", "https://media.test/jellyfin/video/segment.ts?offset=6"])
        XCTAssertFalse(output.contains("secret"))
        XCTAssertTrue(output.contains("URI=\"http://127.0.0.1:5555/2\""))
        XCTAssertTrue(output.contains("\nhttp://127.0.0.1:5555/4\n"))
    }
    func testOriginGateRejectsCredentialsOtherHostsDowngradesAndPorts() {
        let base = URL(string: "https://media.test/base")!
        for target in ["http://media.test/a", "https://other.test/a", "https://user:password@media.test/a", "https://media.test:444/a", "file:///a", "https://media.test:0/a"] {
            XCTAssertFalse(PlaybackRelay.sameOrigin(base, URL(string: target)!), target)
        }
        XCTAssertTrue(PlaybackRelay.sameOrigin(base, URL(string: "https://MEDIA.test:443/else")!))
    }
    func testTokenNamesAreDecodedBeforeStripping() {
        let url = URL(string: "https://media.test/a?%41piKey=secret&api_key=secret&X-Emby-Token=secret&X-MediaBrowser-Token=secret&tag=ok")!
        XCTAssertEqual(PlaybackRelay.withoutCredentials(url).absoluteString, "https://media.test/a?tag=ok")
    }
    func testRelayForwardsRangeAndAuthenticationAndPreservesPartialResponse() async throws {
        let upstream = try await HTTPFixture.start(body: Data("abcd".utf8), status: "206 Partial Content", headers: ["Content-Range": "bytes 2-5/8", "Accept-Ranges": "bytes", "Content-Type": "video/mp4"])
        defer { upstream.stop() }
        let relay = PlaybackRelay(server: upstream.url, headers: ["Authorization": "MediaBrowser Token=\"fixture-secret\""])
        try await relay.start()
        defer { relay.stop() }
        var request = URLRequest(url: try relay.url(for: upstream.url.appendingPathComponent("video.mp4")))
        request.setValue("bytes=2-5", forHTTPHeaderField: "Range")
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual(data, Data("abcd".utf8))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 206)
        XCTAssertEqual((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Range"), "bytes 2-5/8")
        XCTAssertTrue(upstream.request.contains("Range: bytes=2-5"))
        XCTAssertTrue(upstream.request.contains("Authorization: MediaBrowser Token=\"fixture-secret\""))
    }
    func testLiveRelayRewritesHLSAndRejectsUnknownCapability() async throws {
        let upstream = try await HTTPFixture.start(body: Data("#EXTM3U\n#EXTINF:6,\nsegment.ts?ApiKey=server-secret\n".utf8), headers: ["Content-Type": "application/vnd.apple.mpegurl"])
        defer { upstream.stop() }
        let relay = PlaybackRelay(server: upstream.url, headers: [:])
        try await relay.start()
        defer { relay.stop() }
        let url = try relay.url(for: upstream.url.appendingPathComponent("master.m3u8"))
        let (data, _) = try await URLSession.shared.data(from: url)
        let text = String(decoding: data, as: UTF8.self)
        XCTAssertTrue(text.contains("http://127.0.0.1:"))
        XCTAssertFalse(text.contains("server-secret"))
        var invalid = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        invalid.path = "/wrong/anything.asset"
        let (_, response) = try await URLSession.shared.data(from: invalid.url!)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 403)
        XCTAssertThrowsError(try relay.url(for: URL(string: "https://other.test/a")!))
    }
    func testManifestHEADPreservesRepresentationLengthAndDoesNotRewriteEmptyBody() async throws {
        let upstream = try await HTTPFixture.start(body: Data("#EXTM3U\nsegment.ts\n".utf8), headers: ["Content-Type":"application/vnd.apple.mpegurl"])
        defer { upstream.stop() }
        let relay = PlaybackRelay(server: upstream.url, headers: [:])
        try await relay.start()
        defer { relay.stop() }
        var request = URLRequest(url: try relay.url(for: upstream.url.appendingPathComponent("master.m3u8")))
        request.httpMethod = "HEAD"
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertTrue(data.isEmpty)
        XCTAssertEqual((response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Length"), String(upstream.body.count))
    }
    func testManifestRangeIsNotForwardedBecauseRewritingNeedsFullRepresentation() async throws {
        let upstream = try await HTTPFixture.start(body: Data("#EXTM3U\nsegment.ts\n".utf8), headers: ["Content-Type":"application/vnd.apple.mpegurl"])
        defer { upstream.stop() }
        let relay = PlaybackRelay(server: upstream.url, headers: [:])
        try await relay.start()
        defer { relay.stop() }
        var request = URLRequest(url: try relay.url(for: upstream.url.appendingPathComponent("master.m3u8")))
        request.setValue("bytes=0-6", forHTTPHeaderField: "Range")
        let (data, _) = try await URLSession.shared.data(for: request)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains("#EXTM3U"))
        XCTAssertFalse(upstream.request.lowercased().contains("range:"))
    }
    func testRedirectOutsideOriginNeverReceivesAuthentication() async throws {
        let destination = try await HTTPFixture.start(body: Data("leaked".utf8))
        defer { destination.stop() }
        let upstream = try await HTTPFixture.start(body: Data(), status: "302 Found", headers: ["Location": destination.url.absoluteString])
        defer { upstream.stop() }
        let relay = PlaybackRelay(server: upstream.url, headers: ["Authorization": "private-token"])
        try await relay.start()
        defer { relay.stop() }
        let (_, response) = try await URLSession.shared.data(from: relay.url(for: upstream.url))
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 502)
        XCTAssertEqual(destination.request, "")
    }
}

private final class HTTPFixture: @unchecked Sendable {
    let listener: NWListener
    let body: Data
    let status: String
    let headers: [String:String]
    let queue = DispatchQueue(label: "relay-fixture")
    private(set) var request = ""
    var url: URL { URL(string: "http://127.0.0.1:\(listener.port!.rawValue)")! }
    private init(body: Data, status: String, headers: [String:String]) throws {
        self.body = body; self.status = status; self.headers = headers
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
    }
    static func start(body: Data, status: String = "200 OK", headers: [String:String] = [:]) async throws -> HTTPFixture {
        let fixture = try HTTPFixture(body: body, status: status, headers: headers)
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            fixture.listener.stateUpdateHandler = { state in
                if case .ready = state { c.resume() }
                else if case .failed(let error) = state { c.resume(throwing: error) }
            }
            fixture.listener.newConnectionHandler = { fixture.serve($0) }
            fixture.listener.start(queue: fixture.queue)
        }
        return fixture
    }
    func serve(_ connection: NWConnection) {
        connection.start(queue: queue)
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65_536) { data, _, _, _ in
            self.request = String(decoding: data ?? Data(), as: UTF8.self)
            let headers = self.headers.map { "\($0): \($1)\r\n" }.joined()
            let response = Data("HTTP/1.1 \(self.status)\r\nContent-Length: \(self.body.count)\r\nConnection: close\r\n\(headers)\r\n".utf8) + self.body
            connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
        }
    }
    func stop() { listener.cancel() }
}
