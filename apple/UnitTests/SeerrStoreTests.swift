// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Security
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// SEERR-01/02: each account's Seerr connection lives only in the Keychain, fails closed, and can't be revived by a
/// sign-in or renewal that finishes after the account disconnected or signed out.
@MainActor final class SeerrStoreTests: XCTestCase {
    final class FakeKeychain: KeychainBackend {
        var items: [String: Data] = [:]
        var writeStatus: OSStatus = errSecSuccess
        var readStatus: OSStatus?
        func read(service: String) -> (Data?, OSStatus) {
            if let readStatus { return (nil, readStatus) }
            return items[service].map { ($0, errSecSuccess) } ?? (nil, errSecItemNotFound)
        }
        func write(_ data: Data, service: String) -> OSStatus {
            if writeStatus == errSecSuccess { items[service] = data }
            return writeStatus
        }
        func delete(service: String) { items[service] = nil }
    }

    /// A Seerr that answers by path: sign-in sets a fresh cookie, `/auth/me` answers per `sessionAlive`.
    final class FakeSeerr: @unchecked Sendable {
        let lock = NSLock()
        var sessionAlive = true
        var signIns = 0
        var paths: [String] = []
        static let user: [String: Any] = ["id": 7, "displayName": "viewer", "jellyfinUserId": "u1", "permissions": 32]

        var transport: SeerrTransport {
            { request in
                let path = request.url!.path.replacingOccurrences(of: "/api/v1/", with: "")
                return self.lock.withLock {
                    self.paths.append(path)
                    func json(_ value: Any, _ status: Int = 200, cookies: [String] = []) -> SeerrHTTPResponse {
                        SeerrHTTPResponse(status: status, setCookies: cookies, body: try! JSONSerialization.data(withJSONObject: value))
                    }
                    switch path {
                    case "auth/jellyfin":
                        self.signIns += 1
                        self.sessionAlive = true
                        return json(Self.user, cookies: ["connect.sid=s%3Asession\(self.signIns)"])
                    case "auth/me":
                        return self.sessionAlive ? json(Self.user) : json(["message": "Unauthorized"], 403)
                    default:
                        return self.sessionAlive ? json(["page": 1, "totalPages": 1, "results": []]) : json(["message": "Unauthorized"], 403)
                    }
                }
            }
        }
    }

    private let server = Server(url: URL(string: "https://media.example.test")!)
    private lazy var viewer = Account(server: server, userId: "u1", username: "viewer", accessToken: "t1", deviceID: "d1")
    private lazy var other = Account(server: server, userId: "u2", username: "other", accessToken: "t2", deviceID: "d2")
    private let address = "https://requests.example.test"
    private let password = "correct horse battery"

    private func connect(_ store: SeerrConnections, _ account: Account, stillSignedIn: @escaping () -> Bool = { true }) async throws -> SeerrConfig {
        try await store.connect(account: account, url: address, apiKey: nil, username: account.username, password: password, stillSignedIn: stillSignedIn)
    }

    func testConnectionIsKeptOnlyInTheKeychainAndReloads() async throws {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        let config = try await connect(store, viewer)
        XCTAssertEqual(config.user.id, 7)
        XCTAssertEqual(Array(keychain.items.keys), [SeerrConnections.service(viewer.key)])
        let stored = String(decoding: keychain.items.values.first!, as: UTF8.self)
        XCTAssertTrue(stored.contains(password), "the password for renewal is in the Keychain item")
        // Nothing of it in defaults.
        let defaults = String(describing: UserDefaults.standard.dictionaryRepresentation())
        XCTAssertFalse(defaults.contains(password))
        XCTAssertFalse(defaults.contains("connect.sid"))

        let reopened = SeerrConnections(keychain: keychain, transport: seerr.transport)
        reopened.load(keys: [viewer.key, other.key])
        XCTAssertEqual(reopened.config(viewer.key), config)
        XCTAssertNil(reopened.config(other.key))
        XCTAssertNotNil(reopened.client(viewer.key))
        XCTAssertNil(reopened.client(other.key))
    }

    func testKeychainFailureFailsClosed() async {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        keychain.writeStatus = errSecInteractionNotAllowed
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        do {
            _ = try await connect(store, viewer)
            XCTFail("connect must fail when the Keychain refuses")
        } catch {
            XCTAssertEqual(error as? SeerrError, .storage)
        }
        XCTAssertNil(store.config(viewer.key))
        XCTAssertNil(store.client(viewer.key))
        XCTAssertTrue(keychain.items.isEmpty)
    }

    func testMissingEntitlementKeepsTheConnectionInMemoryOnly() async throws {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        keychain.writeStatus = errSecMissingEntitlement
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        _ = try await connect(store, viewer)
        XCTAssertNotNil(store.config(viewer.key))
        XCTAssertTrue(keychain.items.isEmpty)
        // A new process starts without it.
        let reopened = SeerrConnections(keychain: keychain, transport: seerr.transport)
        reopened.load(keys: [viewer.key])
        XCTAssertNil(reopened.config(viewer.key))
    }

    func testSignInThatFinishesAfterSignOutIsDropped() async {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        do {
            _ = try await connect(store, viewer, stillSignedIn: { false })
            XCTFail("a connect that outlives the sign-in must not be saved")
        } catch {
            XCTAssertEqual(error as? SeerrError, .expired)
        }
        XCTAssertNil(store.config(viewer.key))
        XCTAssertTrue(keychain.items.isEmpty)
    }

    func testRenewalAfterDisconnectIsNotSaved() async throws {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        _ = try await connect(store, viewer)
        let client = try XCTUnwrap(store.client(viewer.key))
        store.remove(viewer.key)
        seerr.lock.withLock { seerr.sessionAlive = false }
        _ = try await client.trending()
        await Task.yield()
        XCTAssertEqual(seerr.signIns, 2, "the client renewed its session")
        XCTAssertTrue(keychain.items.isEmpty, "but the renewal didn't bring the connection back")
        XCTAssertNil(store.config(viewer.key))
    }

    func testRenewalIsSavedWhileStillConnected() async throws {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        _ = try await connect(store, viewer)
        let client = try XCTUnwrap(store.client(viewer.key))
        seerr.lock.withLock { seerr.sessionAlive = false }
        _ = try await client.trending()
        for _ in 0..<20 where !String(decoding: keychain.items.values.first ?? Data(), as: UTF8.self).contains("session2") { await Task.yield() }
        XCTAssertTrue(String(decoding: keychain.items.values.first ?? Data(), as: UTF8.self).contains("session2"))
    }

    func testRemoveDropsOnlyThatAccount() async throws {
        let keychain = FakeKeychain(), seerr = FakeSeerr()
        let store = SeerrConnections(keychain: keychain, transport: seerr.transport)
        _ = try await connect(store, viewer)
        _ = try await connect(store, other)
        store.remove(viewer.key)
        XCTAssertNil(store.config(viewer.key))
        XCTAssertNotNil(store.config(other.key))
        XCTAssertEqual(Array(keychain.items.keys), [SeerrConnections.service(other.key)])
    }

    func testCorruptOrUnreadableEntryReadsAsNotConnected() {
        let keychain = FakeKeychain()
        keychain.items[SeerrConnections.service(viewer.key)] = Data("{not json".utf8)
        let store = SeerrConnections(keychain: keychain, transport: FakeSeerr().transport)
        store.load(keys: [viewer.key])
        XCTAssertNil(store.config(viewer.key))
        keychain.readStatus = errSecInteractionNotAllowed
        store.load(keys: [viewer.key])
        XCTAssertNil(store.config(viewer.key))
    }
}
