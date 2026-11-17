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

    // MARK: Nothing plays behind the lock

    /// The player cover shows nothing for a locked viewer, whatever route set the request.
    func testPlayerIsNotShownBehindTheLock() async throws {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(viewer.key)] = try savedLock("1234")
        let app = try model(pins: pins)
        app.player = PlayerRequest(account: viewer, item: MediaItem(id: "m1", type: "Movie"), fromStart: false)
        XCTAssertFalse(app.pastPinGate(viewer))
        XCTAssertNil(app.shownPlayer, "a request set while locked is not presented")
        let r = await app.pins.verify(viewer.key, pin: "1234", now: 10)
        XCTAssertEqual(r, .unlocked)
        XCTAssertNotNil(app.shownPlayer)
        XCTAssertFalse(app.pastPinGate(parent), "only the current viewer plays")
    }

    /// A remote's Play on a locked viewer is refused before anything is fetched or opened.
    func testRemoteOpenIsRefusedWhileLocked() throws {
        let hex = "0123456789abcdef0123456789abcdef"
        let kid = Account(server: viewer.server, userId: hex, username: "kid", accessToken: "t3", deviceID: "dev-3")
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(kid.key)] = try savedLock("1234")
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let store = AccountStore(service: suite, fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json"),
                                 keychain: FakeKeychain(), defaults: UserDefaults(suiteName: suite)!, isSimulator: true)
        try store.save(AccountSnapshot(servers: [kid.server], accounts: [kid], selected: kid.key))
        let app = AppModel(store: store, pinKeychain: pins)
        app.socket.stop()
        app.remoteTarget.socketChanged(open: true)
        XCTAssertTrue(app.pinGateNeeded(kid))
        let body: [String: Any] = ["MessageType": "Play", "MessageId": "m-1",
                                   "Data": ["ControllingUserId": hex, "PlayCommand": "PlayNow", "ItemIds": ["fedcba9876543210fedcba9876543210"]]]
        app.socket.receiveForTesting(String(data: try JSONSerialization.data(withJSONObject: body), encoding: .utf8)!)
        XCTAssertEqual(app.remoteTarget.lastEffects, ["open"])
        XCTAssertEqual(app.remoteTarget.refusedWhileLocked, 1)
        XCTAssertNil(app.player)
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

    private func unlockedModel() async throws -> AppModel {
        let pins = FakeKeychain()
        pins.items[ProfileLockKeychain.service(viewer.key)] = try savedLock("1234")
        let app = try model(pins: pins)
        _ = await app.pins.verify(viewer.key, pin: "1234", now: 10)
        app.player = PlayerRequest(account: viewer, item: MediaItem(id: "m1", type: "Movie"), fromStart: false)
        return app
    }

    /// TOUCH-15 with AUTH-18: a playing picture-in-picture window is Pictureshow in use, whichever comes first (the
    /// window or the scene going to the background); the time starts when it closes in the background.
    func testPlayingPictureInPictureIsNotTimeAwayAndClosingItStartsTheTimer() async throws {
        let app = try await unlockedModel()
        app.pictureInPictureMoved(active: true, playing: true, now: 1_000_000)
        app.sceneMoved(to: .background, now: 1_000_001)
        XCTAssertFalse(app.sceneMoved(to: .active, now: 1_000_001 + 3_600_000), "an hour of the window playing is not away")
        XCTAssertFalse(app.pinGateNeeded(viewer))

        app.pictureInPictureMoved(active: false, playing: false, now: 5_000_000)
        app.sceneMoved(to: .background, now: 5_000_001)
        app.pictureInPictureMoved(active: true, playing: true, now: 5_000_002)
        XCTAssertFalse(app.sceneMoved(to: .active, now: 5_000_002 + 3_600_000), "the window opening just after the scene left stops the time away")

        app.sceneMoved(to: .background, now: 9_000_000)
        app.pictureInPictureMoved(active: false, playing: false, now: 9_600_000)
        XCTAssertFalse(app.sceneMoved(to: .active, now: 9_600_000 + 299_999), "the time away starts when the window closes")
        app.sceneMoved(to: .background, now: 9_900_000)
        XCTAssertTrue(app.sceneMoved(to: .active, now: 9_900_000 + 300_000), "and five minutes from then locks")
        XCTAssertTrue(app.pinGateNeeded(viewer))
        XCTAssertNil(app.player, "the viewing closed at the relock")
        XCTAssertNil(app.shownPlayer, "no player or window past the gate")
    }

    /// A paused (or finished) window left open is time away: five minutes from the pause lock, and resuming after that
    /// locks and closes the window instead of playing behind the lock.
    func testPausedPictureInPictureDoesNotHoldOffTheRelock() async throws {
        let app = try await unlockedModel()
        app.sceneMoved(to: .background, now: 1_000_000)
        app.pictureInPictureMoved(active: true, playing: true, now: 1_000_001)
        app.pictureInPictureMoved(active: true, playing: false, now: 2_000_000)
        XCTAssertFalse(app.pictureInPictureMoved(active: true, playing: true, now: 2_000_000 + 299_999), "playing again within five minutes restarts nothing")
        XCTAssertFalse(app.pinGateNeeded(viewer))
        app.pictureInPictureMoved(active: true, playing: false, now: 3_000_000)
        XCTAssertTrue(app.pictureInPictureMoved(active: true, playing: true, now: 3_000_000 + 300_000), "resuming after five minutes paused locks")
        XCTAssertTrue(app.pinGateNeeded(viewer))
        XCTAssertNil(app.shownPlayer, "the window's viewing closed at the relock")

        let left = try await unlockedModel()
        left.sceneMoved(to: .background, now: 1_000_000)
        left.pictureInPictureMoved(active: true, playing: true, now: 1_000_001)
        left.pictureInPictureMoved(active: true, playing: false, now: 2_000_000)
        XCTAssertTrue(left.sceneMoved(to: .active, now: 2_000_000 + 3_600_000), "an hour with the window paused is away")
    }

    /// A torn-down viewing takes the window's in-use state with it, whatever ends it, so the time away runs.
    func testTearingDownThePlayerClearsPictureInPicture() async throws {
        for teardown in ["close", "signOut", "leaveLockedViewer"] {
            let app = try await unlockedModel()
            app.sceneMoved(to: .background, now: 1_000_000)
            app.pictureInPictureMoved(active: true, playing: true, now: 1_000_001)
            switch teardown {
            case "close": app.player = nil
            case "signOut": app.signOut(viewer)
            default: app.leaveLockedViewer()
            }
            XCTAssertFalse(app.pictureInPictureActive, teardown)
            XCTAssertFalse(app.pictureInPicturePlaying, teardown)
            XCTAssertEqual(app.pins.backgroundedAt != nil, true, "\(teardown): the time away runs again")
        }
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
