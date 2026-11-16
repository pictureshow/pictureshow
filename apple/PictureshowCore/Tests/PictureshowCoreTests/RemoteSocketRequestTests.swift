// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

/// The push socket's handshake: Jellyfin 12 answers 403 to a token in the query, and URLs end up in logs.
final class RemoteSocketRequestTests: XCTestCase {
    private func account(_ server: String) -> Account {
        Account(server: Server(url: URL(string: server)!, id: "srv"), userId: "user", username: "Viewer", accessToken: "secret-token", deviceID: "dev-1")
    }

    func testTheTokenTravelsInTheHeaderNeverTheURL() throws {
        let request = try XCTUnwrap(JellyfinClient(deviceID: "dev-1").socketRequest(account: account("https://media.example.test/jf/")))
        XCTAssertEqual(request.url?.absoluteString, "wss://media.example.test/jf/socket")
        XCTAssertFalse(request.url!.absoluteString.contains("secret-token"))
        let header = try XCTUnwrap(request.value(forHTTPHeaderField: "Authorization"))
        XCTAssertTrue(header.contains(#"Token="secret-token""#), header)
        XCTAssertTrue(header.contains(#"DeviceId="dev-1""#), header)
    }

    func testPlainHTTPServersGetAPlainSocket() throws {
        let request = try XCTUnwrap(JellyfinClient(deviceID: "dev-1").socketRequest(account: account("http://127.0.0.1:8096")))
        XCTAssertEqual(request.url?.absoluteString, "ws://127.0.0.1:8096/socket")
    }
}
