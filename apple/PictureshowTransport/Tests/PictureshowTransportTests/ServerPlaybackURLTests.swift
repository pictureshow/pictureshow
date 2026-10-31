// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowTransport
final class ServerPlaybackURLTests: XCTestCase {
    func testQueryCredentialBoundaryPreservesServerTimelineAndSource() throws {
        let server = URL(string: "https://media.example.test/jellyfin")!
        let source = URL(string: "https://media.example.test/jellyfin/Videos/item/master.m3u8?MediaSourceId=source&StartTimeTicks=650000000&api_key=obsolete&ApiKey=stale&SubtitleStreamIndex=2#secret")!
        let result = try ServerPlaybackURL.make(decisionURL: source, server: server, token: "viewer +/?&token", requiredHeaders: ["Authorization":"MediaBrowser"])
        let parts = URLComponents(url: result, resolvingAgainstBaseURL: false)!
        XCTAssertEqual(parts.path, "/jellyfin/Videos/item/master.m3u8")
        XCTAssertEqual(parts.queryItems?.filter { $0.name == "ApiKey" }.map(\.value), ["viewer +/?&token"])
        XCTAssertFalse(parts.queryItems!.contains { $0.name == "api_key" })
        XCTAssertEqual(parts.queryItems?.first { $0.name == "StartTimeTicks" }?.value, "650000000")
        XCTAssertNil(parts.fragment)
    }
    func testCrossOriginAndUnsupportedRequiredHeadersFailBeforeAssetCreation() {
        let server = URL(string: "https://media.example.test/base")!
        for value in ["http://media.example.test/base/a", "https://other.example.test/a", "https://media.example.test:444/a", "https://user:pass@media.example.test/a"] {
            XCTAssertThrowsError(try ServerPlaybackURL.make(decisionURL: URL(string: value)!, server: server, token: "fixture"))
        }
        XCTAssertThrowsError(try ServerPlaybackURL.make(decisionURL: server, server: server, token: "fixture", requiredHeaders: ["X-Custom-Upstream":"required"]))
        XCTAssertNoThrow(try ServerPlaybackURL.make(decisionURL: URL(string: "https://media.example.test:443/base/a")!, server: server, token: "fixture"))
    }
}
