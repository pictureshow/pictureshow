// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Security
import SwiftUI
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// AUTH-16..19 in the app: PINs only in the Keychain, failing closed, and the five-minute relock on the scene phase.
@MainActor final class ProfilePinAppTests: XCTestCase {
    final class FakeKeychain: KeychainBackend {
        var items: [String: Data] = [:]
        var readStatus: OSStatus?
        var writeStatus: OSStatus = errSecSuccess
        var deleteWorks = true
        func read(service: String) -> (Data?, OSStatus) {
            if let readStatus { return (nil, readStatus) }
            return items[service].map { ($0, errSecSuccess) } ?? (nil, errSecItemNotFound)
        }
        func write(_ data: Data, service: String) -> OSStatus {
            if writeStatus == errSecSuccess { items[service] = data }
            return writeStatus
        }
        func delete(service: String) { if deleteWorks { items[service] = nil } }
    }

    private let viewer = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: "u1", username: "kid", accessToken: "t1", deviceID: "dev-1")
    private let parent = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: "u2", username: "parent", accessToken: "t2", deviceID: "dev-2")

    private func savedLock(_ pin: String, askOnStart: Bool = true) throws -> Data {
        try JSONEncoder().encode(ProfileLock(hash: try PBKDF2ProfilePinHasher(rounds: 1).create(pin), askOnStart: askOnStart))
    }

    private func model(pins: FakeKeychain) throws -> AppModel {
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let store = AccountStore(service: suite, fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json"),
                                 keychain: FakeKeychain(), defaults: UserDefaults(suiteName: suite)!, isSimulator: true)
        try store.save(AccountSnapshot(servers: [viewer.server], accounts: [viewer, parent], selected: viewer.key))
        let app = AppModel(store: store, pinKeychain: pins)
        app.socket.stop()
        return app
    }

    // MARK: Keychain storage

    func testPinIsSavedOnlyAsAHashInItsOwnKeychainItem() throws {
        let keychain = FakeKeychain(), store = ProfileLockKeychain(keychain: keychain)
        let lock = ProfileLock(hash: try PBKDF2ProfilePinHasher(rounds: 1).create("2468"))
        try store.write(lock, key: viewer.key)
        let data = try XCTUnwrap(keychain.items[ProfileLockKeychain.service(viewer.key)])
        XCTAssertFalse(String(decoding: data, as: UTF8.self).contains("2468"))
        XCTAssertEqual(store.read(viewer.key), .lock(lock))
        XCTAssertEqual(store.read(parent.key), .none)
    }

    /// Fail closed: a Keychain that refuses to answer, or an entry that doesn't decode, locks the viewer.
    func testUnreadableKeychainEntryReadsAsLocked() throws {
        let keychain = FakeKeychain(), store = ProfileLockKeychain(keychain: keychain)
        keychain.items[ProfileLockKeychain.service(viewer.key)] = Data("not a lock".utf8)
        XCTAssertEqual(store.read(viewer.key), .unreadable)
        keychain.readStatus = errSecInteractionNotAllowed
        XCTAssertEqual(store.read(parent.key), .unreadable, "a refusal is not 'no PIN'")
        keychain.readStatus = errSecMissingEntitlement
        XCTAssertEqual(store.read(parent.key), .none, "an unsigned build has no Keychain at all")
    }

    func testFailedWriteOrDeleteThrows() throws {
        let keychain = FakeKeychain(), store = ProfileLockKeychain(keychain: keychain)
        keychain.writeStatus = errSecNotAvailable
        XCTAssertThrowsError(try store.write(ProfileLock(hash: "x"), key: viewer.key))
        keychain.writeStatus = errSecSuccess
        try store.write(ProfileLock(hash: "x"), key: viewer.key)
        keychain.deleteWorks = false
        XCTAssertThrowsError(try store.delete(viewer.key), "a PIN still in the Keychain isn't reported off")
    }

    /// Without the entitlement (unsigned simulator builds) PINs live in memory only, never in defaults or files.
    func testMissingEntitlementKeepsPinsInMemoryOnly() throws {
        let keychain = FakeKeychain(), store = ProfileLockKeychain(keychain: keychain)
        keychain.writeStatus = errSecMissingEntitlement
        keychain.readStatus = errSecMissingEntitlement
        let lock = ProfileLock(hash: "h")
        try store.write(lock, key: viewer.key)
        XCTAssertEqual(store.read(viewer.key), .lock(lock))
        XCTAssertTrue(keychain.items.isEmpty)
        XCTAssertFalse(UserDefaults.standard.dictionaryRepresentation().keys.contains { $0.contains("app.pictureshow.pin") })
    }

    // MARK: The gate

    func testOpeningOnAViewerWithAPinShowsTheLock() throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(viewer.key)] = try savedLock("1234")
        let app = try model(pins: pins)
        XCTAssertTrue(app.pinGateNeeded(viewer))
        app.signedIn(viewer)
        XCTAssertFalse(app.pinGateNeeded(viewer), "the password route opens it")
        XCTAssertFalse(app.pins.changeNeedsPin(viewer.key), "and allows a new PIN without the old one")
    }

    /// Fail closed at the app level: an unreadable PIN keeps the viewer behind the lock and no PIN opens it.
    func testKeychainErrorKeepsTheViewerLocked() async throws {
        let pins = FakeKeychain()
        pins.readStatus = errSecInteractionNotAllowed
        let app = try model(pins: pins)
        XCTAssertTrue(app.pinGateNeeded(viewer))
        let result = await app.pins.verify(viewer.key, pin: "1234", now: 1)
        XCTAssertEqual(result, .unavailable)
        XCTAssertTrue(app.pinGateNeeded(viewer))
        XCTAssertTrue(app.pins.passwordOnly(viewer.key))
    }

    func testSwitchingToAViewerWithAPinAsksFirstAndKeepsTheOpenOne() throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(parent.key)] = try savedLock("9999")
        let app = try model(pins: pins)
        XCTAssertFalse(app.pinGateNeeded(viewer))
        app.select(parent)
        XCTAssertEqual(app.pinSwitch?.key, parent.key)
        XCTAssertEqual(app.account?.key, viewer.key, "the open viewer stays until the PIN is right")
        app.choose(parent)
        XCTAssertNil(app.pinSwitch)
        XCTAssertEqual(app.account?.key, parent.key)
    }

    func testRemovingAViewerDropsItsPin() throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(parent.key)] = try savedLock("9999")
        let app = try model(pins: pins)
        app.remove(parent)
        XCTAssertNil(pins.items[ProfileLockKeychain.service(parent.key)])
        XCTAssertFalse(app.pins.hasPin(parent.key))
    }

    // MARK: AUTH-18 relock on the scene phase

    func testFiveMinutesInTheBackgroundLocksAgainAndClosesTheViewing() async throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(viewer.key)] = try savedLock("1234")
        let app = try model(pins: pins)
        let unlocked = await app.pins.verify(viewer.key, pin: "1234", now: 10)
        XCTAssertEqual(unlocked, .unlocked)
        XCTAssertFalse(app.pinGateNeeded(viewer))
        app.player = PlayerRequest(account: viewer, item: MediaItem(id: "m1", type: "Movie"), fromStart: false)

        XCTAssertFalse(app.sceneMoved(to: .background, now: 1_000_000))
        XCTAssertFalse(app.sceneMoved(to: .active, now: 1_299_999), "4 min 59.999 s away stays open")
        XCTAssertFalse(app.pinGateNeeded(viewer))
        XCTAssertNotNil(app.player)

        XCTAssertFalse(app.sceneMoved(to: .inactive, now: 1_400_000), "a dialog or the shade over the app is not time away")
        XCTAssertFalse(app.sceneMoved(to: .active, now: 1_800_000))

        app.sceneMoved(to: .background, now: 2_000_000)
        XCTAssertTrue(app.sceneMoved(to: .inactive, now: 2_300_000), "exactly 5 min away locks, from the first visible phase")
        XCTAssertTrue(app.pinGateNeeded(viewer))
        XCTAssertNil(app.player, "nothing plays behind the lock")
        XCTAssertFalse(app.sceneMoved(to: .active, now: 2_300_001), "the same return isn't counted twice")
    }

    func testARestartWhileAwayLocks() async throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(viewer.key)] = try savedLock("1234")
        let app = try model(pins: pins)
        _ = await app.pins.verify(viewer.key, pin: "1234", now: 10)
        app.sceneMoved(to: .background, now: 5_000_000)
        XCTAssertTrue(app.sceneMoved(to: .active, now: 20_000), "a monotonic clock below the saved time is a restart")
        XCTAssertTrue(app.pinGateNeeded(viewer))
    }
}
