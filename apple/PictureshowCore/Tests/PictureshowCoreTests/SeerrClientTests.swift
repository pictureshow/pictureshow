// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

/// Replays answers a Seerr v3.5.2 gave (leg 163) to the client: sign-in, renewal, the 403 ambiguity, 202, and the
/// fail-closed API-key link.
final class SeerrClientTests: XCTestCase {
    /// A scripted Seerr: each request takes the next answer whose path prefix matches, in order.
    final class Script: @unchecked Sendable {
        private let lock = NSLock()
        private var answers: [(String, SeerrHTTPResponse)]
        private(set) var requests: [URLRequest] = []
        init(_ answers: [(String, SeerrHTTPResponse)]) { self.answers = answers }

        var transport: SeerrTransport {
            { request in
                try self.lock.withLock {
                    self.requests.append(request)
                    let path = request.url!.absoluteString.components(separatedBy: "/api/v1/").last ?? ""
                    guard let index = self.answers.firstIndex(where: { path.hasPrefix($0.0) }) else { throw URLError(.cannotConnectToHost) }
                    return self.answers.remove(at: index).1
                }
            }
        }
        var paths: [String] { lock.withLock { requests.map { $0.url!.absoluteString.components(separatedBy: "/api/v1/").last ?? "" } } }
        var remaining: Int { lock.withLock { answers.count } }
    }

    static func json(_ value: Any, _ status: Int = 200, cookies: [String] = []) -> SeerrHTTPResponse {
        SeerrHTTPResponse(status: status, setCookies: cookies, body: try! JSONSerialization.data(withJSONObject: value))
    }

    static let pilot: [String: Any] = ["id": 2, "displayName": "pilot", "jellyfinUserId": "db7d5bcbc3cf4c0d88a2ff7e124a2c4b", "permissions": 32]
    static let base = "https://requests.example.com"

    final class Saved: @unchecked Sendable {
        let lock = NSLock()
        var pairs: [(SeerrSecret, SeerrSecret)] = []
    }

    func testJellyfinSignInKeepsOnlyTheSessionCookieAndVerifiesWhoItIs() async throws {
        let script = Script([
            ("auth/jellyfin", Self.json(Self.pilot, cookies: ["connect.sid=s%3Aold"])),
            ("auth/me", Self.json(Self.pilot))
        ])
        let result = try await SeerrClient.connect(url: "requests.example.com/api/v1/", apiKey: nil, username: " pilot ", password: "pw",
                                                   jellyfinUserId: "x", transport: script.transport)
        XCTAssertEqual(result.config, SeerrConfig(url: Self.base, mode: .jellyfin, user: try JSONDecoder().decode(SeerrUser.self, from: JSONSerialization.data(withJSONObject: Self.pilot))))
        XCTAssertEqual(result.secret, SeerrSecret(cookie: "connect.sid=s%3Aold", username: "pilot", password: "pw"))
        XCTAssertEqual(script.requests[1].value(forHTTPHeaderField: "Cookie"), "connect.sid=s%3Aold")
        // Seerr's sign-in body, and no Jellyfin token anywhere.
        let body = try JSONSerialization.jsonObject(with: script.requests[0].httpBody!) as! [String: String]
        XCTAssertEqual(body, ["username": "pilot", "password": "pw"])
        XCTAssertTrue(script.requests.allSatisfy { $0.value(forHTTPHeaderField: "Authorization") == nil && $0.value(forHTTPHeaderField: "X-Emby-Token") == nil })
    }

    func testSignInWithoutASessionCookieIsAServerError() async {
        let script = Script([("auth/jellyfin", Self.json(Self.pilot))])
        await XCTAssertThrowsSeerr(.server) { _ = try await SeerrClient.login(url: Self.base, username: "a", password: "b", transport: script.transport) }
    }

    func testWrongPasswordAndNotSeerr() async {
        let wrong = Script([("auth/jellyfin", Self.json(["message": "INVALID_CREDENTIALS"], 401))])
        await XCTAssertThrowsSeerr(.password) { _ = try await SeerrClient.login(url: Self.base, username: "a", password: "b", transport: wrong.transport) }
        let other = Script([("auth/jellyfin", SeerrHTTPResponse(status: 404, body: Data("Not Found".utf8)))])
        await XCTAssertThrowsSeerr(.address) { _ = try await SeerrClient.login(url: Self.base, username: "a", password: "b", transport: other.transport) }
        await XCTAssertThrowsSeerr(.address) { _ = try await SeerrClient.login(url: "ftp://x", username: "a", password: "b", transport: other.transport) }
        XCTAssertEqual(other.requests.count, 1, "a refused address makes no call")
    }

    func testLapsedSessionRenewsOnceWithTheSavedPasswordAndRetries() async throws {
        let script = Script([
            ("discover/trending", Self.json(["message": "Unauthorized"], 403)),
            ("auth/me", Self.json(["message": "Unauthorized"], 403)),
            ("auth/jellyfin", Self.json(Self.pilot, cookies: ["connect.sid=s%3Anew"])),
            ("discover/trending", Self.json(["results": [["id": 603, "mediaType": "movie", "title": "The Matrix"]]]))
        ])
        let saved = Saved()
        let old = SeerrSecret(cookie: "connect.sid=s%3Aold", username: "pilot", password: "pw")
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: old, transport: script.transport) { used, renewed in
            saved.lock.withLock { saved.pairs.append((used, renewed)) }
        }
        let page = try await client.trending()
        XCTAssertEqual(page.results.map(\.id), [603])
        XCTAssertEqual(script.requests.last?.value(forHTTPHeaderField: "Cookie"), "connect.sid=s%3Anew")
        XCTAssertEqual(saved.pairs.count, 1)
        XCTAssertEqual(saved.pairs.first?.0, old)
        XCTAssertEqual(saved.pairs.first?.1.cookie, "connect.sid=s%3Anew")
        XCTAssertEqual(script.remaining, 0)
    }

    func testMissingPermissionIsNeverMistakenForAnEndedSession() async {
        let script = Script([
            ("request", Self.json(["message": "You do not have permission to make movie requests."], 403)),
            ("auth/me", Self.json(Self.pilot))
        ])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)),
                                 secret: SeerrSecret(cookie: "connect.sid=a", username: "pilot", password: "pw"), transport: script.transport)
        await XCTAssertThrowsSeerr(.permission) { _ = try await client.request(SeerrTitle(id: 603, mediaType: "movie")) }
        XCTAssertFalse(script.paths.contains("auth/jellyfin"))
    }

    func testChangedJellyfinPasswordReadsAsSignInAgain() async {
        let script = Script([
            ("auth/me", Self.json([:], 401)),
            ("auth/jellyfin", Self.json(["message": "INVALID_CREDENTIALS"], 401))
        ])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)),
                                 secret: SeerrSecret(cookie: "connect.sid=a", username: "pilot", password: "changed"), transport: script.transport)
        await XCTAssertThrowsSeerr(.expired) { _ = try await client.me() }
    }

    func testNoSavedPasswordMeansExpiredWithoutASignInAttempt() async {
        let script = Script([("auth/me", Self.json([:], 401))])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: script.transport)
        await XCTAssertThrowsSeerr(.expired) { _ = try await client.me() }
        XCTAssertEqual(script.paths, ["auth/me"])
    }

    func testNoSeasonsLeft202IsAFailureNotARequest() async throws {
        let script = Script([("request", Self.json(["message": "No seasons available to request"], 202))])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: script.transport)
        let title = SeerrTitle(id: 95396, mediaType: "tv", seasons: [SeerrSeason(seasonNumber: 1, episodeCount: 9)])
        await XCTAssertThrowsSeerr(.alreadyRequested) { _ = try await client.request(title, seasons: [1]) }
        let body = try JSONSerialization.jsonObject(with: script.requests[0].httpBody!) as! [String: Any]
        XCTAssertEqual(body["seasons"] as? [Int], [1])
        XCTAssertEqual(body["mediaId"] as? Int, 95396)
        // A season that can't be requested never leaves the device.
        await XCTAssertThrowsSeerr(.alreadyRequested) { _ = try await client.request(title, seasons: [2]) }
        await XCTAssertThrowsSeerr(.alreadyRequested) { _ = try await client.request(title, seasons: []) }
        XCTAssertEqual(script.requests.count, 1)
    }

    func testAPIKeyLinksTheViewerAcrossPagesAndAlwaysNamesThem() async throws {
        let admin: [String: Any] = ["id": 1, "displayName": "admin", "jellyfinUserId": "ab3c4f85f313450683238943da5e73b9", "permissions": 2]
        let script = Script([
            ("user?take=100&skip=0", Self.json(["results": [admin], "pageInfo": ["results": 2, "pages": 2]])),
            ("user?take=100&skip=1", Self.json(["results": [Self.pilot], "pageInfo": ["results": 2, "pages": 2]])),
            ("auth/me", Self.json(Self.pilot)),
            ("request?", Self.json(["results": [], "pageInfo": ["results": 0, "pages": 1]]))
        ])
        let result = try await SeerrClient.connect(url: Self.base, apiKey: " key ", username: "", password: "",
                                                   jellyfinUserId: "DB7D5BCB-C3CF-4C0D-88A2-FF7E124A2C4B", transport: script.transport)
        XCTAssertEqual(result.config.user.id, 2)
        XCTAssertEqual(result.secret, SeerrSecret(apiKey: "key"))
        _ = try await SeerrClient(config: result.config, secret: result.secret, transport: script.transport).requests()
        XCTAssertTrue(script.requests[3].url!.absoluteString.contains("requestedBy=2"))
        for (index, request) in script.requests.enumerated() {
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Api-Key"), "key")
            // Only the user lookup goes without X-API-User.
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-API-User"), index < 2 ? nil : "2", script.paths[index])
            XCTAssertNil(request.value(forHTTPHeaderField: "Cookie"))
        }
    }

    func testAPIKeyWithNoLinkedUserFailsClosed() async {
        let script = Script([("user?take=100&skip=0", Self.json(["results": [["id": 1, "displayName": "admin", "permissions": 2], ["id": 3, "displayName": "local"]], "pageInfo": ["results": 2]]))])
        await XCTAssertThrowsSeerr(.notLinked) {
            _ = try await SeerrClient.connect(url: Self.base, apiKey: "key", username: "", password: "", jellyfinUserId: "db7d5bcbc3cf4c0d88a2ff7e124a2c4b", transport: script.transport)
        }
        XCTAssertEqual(script.requests.count, 1, "no call is made as the admin after the lookup fails")
        // A config without a linked user can't call Seerr at all.
        let unlinked = SeerrClient(config: SeerrConfig(url: Self.base, mode: .apiKey, user: SeerrUser(id: 0)), secret: SeerrSecret(apiKey: "key"), transport: script.transport)
        await XCTAssertThrowsSeerr(.notLinked) { _ = try await unlinked.trending() }
        XCTAssertEqual(script.requests.count, 1)
    }

    func testAPIKeyAnsweredAsSomeoneElseFailsClosed() async {
        let script = Script([
            ("user?", Self.json(["results": [Self.pilot], "pageInfo": ["results": 1]])),
            ("auth/me", Self.json(["id": 1, "displayName": "admin", "permissions": 2]))
        ])
        await XCTAssertThrowsSeerr(.notLinked) {
            _ = try await SeerrClient.connect(url: Self.base, apiKey: "key", username: "", password: "", jellyfinUserId: "db7d5bcbc3cf4c0d88a2ff7e124a2c4b", transport: script.transport)
        }
    }

    func testWrongKeyRedirectAndUnreachable() async {
        let wrong = Script([("user?", Self.json(["message": "You do not have permission to access this endpoint."], 403))])
        await XCTAssertThrowsSeerr(.apiKey) { _ = try await SeerrClient.apiKey(url: Self.base, key: "bad", jellyfinUserId: "x", transport: wrong.transport) }
        let moved = Script([("auth/me", SeerrHTTPResponse(status: 307))])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: moved.transport)
        await XCTAssertThrowsSeerr(.address) { _ = try await client.me() }
        let down = Script([])
        let offline = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: down.transport)
        await XCTAssertThrowsSeerr(.unreachable) { _ = try await offline.trending() }
        let garbage = Script([("discover", SeerrHTTPResponse(status: 200, body: Data("<html>".utf8)))])
        let html = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: garbage.transport)
        await XCTAssertThrowsSeerr(.server) { _ = try await html.trending() }
    }

    func testSearchWordsAreEncodedInFull() async throws {
        let script = Script([("search?", Self.json(["results": []]))])
        let client = SeerrClient(config: SeerrConfig(url: Self.base, mode: .jellyfin, user: SeerrUser(id: 2)), secret: SeerrSecret(cookie: "connect.sid=a"), transport: script.transport)
        _ = try await client.search("  Ocean's Eleven (2001)! ")
        XCTAssertEqual(script.paths, ["search?query=Ocean%27s%20Eleven%20%282001%29%21&page=1"])
    }

    func testSetCookieHeadersJoinedByFoundationAreSplitProperly() {
        let url = URL(string: Self.base + "/api/v1/auth/jellyfin")!
        let joined = "connect.sid=s%3Aabc.def; Path=/; Expires=Wed, 16 Dec 2026 08:00:00 GMT; HttpOnly; SameSite=Lax, theme=dark; Path=/"
        let response = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Set-Cookie": joined])!
        let cookies = SeerrHTTP.setCookies(response)
        XCTAssertEqual(seerrSessionCookie(cookies), "connect.sid=s%3Aabc.def")
    }

    func testSecretsNeverPrint() {
        let secret = SeerrSecret(apiKey: "KEY123", cookie: "connect.sid=COOKIE", username: "u", password: "PW456")
        var dumped = ""
        dump(secret, to: &dumped)
        for text in [String(describing: secret), String(reflecting: secret), "\(secret)", dumped] {
            for value in ["KEY123", "COOKIE", "PW456"] { XCTAssertFalse(text.contains(value), text) }
        }
    }
}

func XCTAssertThrowsSeerr(_ expected: SeerrError, file: StaticString = #filePath, line: UInt = #line, _ work: () async throws -> Void) async {
    do {
        try await work()
        XCTFail("expected \(expected)", file: file, line: line)
    } catch let error as SeerrError {
        XCTAssertEqual(error, expected, file: file, line: line)
    } catch {
        XCTFail("expected \(expected), got \(error)", file: file, line: line)
    }
}
