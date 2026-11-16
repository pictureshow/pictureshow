// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// A download must never hand the viewer's token, or take a file, from a host other than their server.
final class DownloadRedirectTests: XCTestCase {
    /// Answers the viewer's server with a redirect to another host, and records every request it sees.
    final class Stub: URLProtocol {
        nonisolated(unsafe) static var seen: [(host: String, authorization: String?)] = []
        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
        override func startLoading() {
            let url = request.url!
            Self.seen.append((url.host ?? "", request.value(forHTTPHeaderField: "Authorization")))
            if url.host == "media.example.test" {
                let elsewhere = URL(string: "https://elsewhere.example.net/capture")!
                let redirect = HTTPURLResponse(url: url, statusCode: 302, httpVersion: "HTTP/1.1", headerFields: ["Location": elsewhere.absoluteString])!
                client?.urlProtocol(self, wasRedirectedTo: URLRequest(url: elsewhere), redirectResponse: redirect)
                // A refused redirect leaves the 302 as the answer; a followed one cancels this load, and these are ignored.
                client?.urlProtocol(self, didReceive: redirect, cacheStoragePolicy: .notAllowed)
                client?.urlProtocolDidFinishLoading(self)
            } else {
                client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: Data("captured".utf8))
                client?.urlProtocolDidFinishLoading(self)
            }
        }
        override func stopLoading() {}
    }

    func testSidecarRequestsDoNotFollowARedirectToAnotherHost() async throws {
        Stub.seen = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [Stub.self]
        let session = Downloads.sidecarSession(configuration)
        var request = URLRequest(url: URL(string: "https://media.example.test/Items/1/Images/Primary")!)
        request.setValue("MediaBrowser Token=\"secret\"", forHTTPHeaderField: "Authorization")
        let (_, response) = try await session.data(for: request)
        XCTAssertEqual(Stub.seen.map(\.host), ["media.example.test"], "the other host must never be asked")
        let http = try XCTUnwrap(response as? HTTPURLResponse)
        XCTAssertFalse((200..<300).contains(http.statusCode))
    }

    func testOnlyAnAnswerFromTheViewersServerCounts() {
        let server = URL(string: "https://media.example.test")!
        func answer(_ url: String?) -> HTTPURLResponse {
            HTTPURLResponse(url: URL(string: url ?? "about:blank")!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        }
        XCTAssertTrue(Downloads.servedBy(server, answer("https://media.example.test/Items/1/Download")))
        XCTAssertFalse(Downloads.servedBy(server, answer("https://elsewhere.example.net/Items/1/Download")))
        XCTAssertFalse(Downloads.servedBy(server, answer("https://media.example.test:8443/Items/1/Download")))
        XCTAssertFalse(Downloads.servedBy(server, answer("http://media.example.test/Items/1/Download")))
    }
}
