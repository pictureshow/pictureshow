// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// The app's socket and remote-target adapters: what reaches the shared reducer and what it lets through.
@MainActor
final class RemoteAppTests: XCTestCase {
    private let viewer = "0123456789abcdef0123456789abcdef"
    private let stranger = "fedcba9876543210fedcba9876543210"

    private func model() throws -> AppModel {
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: viewer, username: "v", accessToken: "t", deviceID: "dev-remote")
        let store = AccountStore(service: suite, fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json"),
                                 keychain: MemoryKeychain(), defaults: UserDefaults(suiteName: suite)!, isSimulator: true)
        try store.save(AccountSnapshot(servers: [account.server], accounts: [account], selected: account.key))
        let app = AppModel(store: store)
        app.socket.stop()
        app.remoteTarget.socketChanged(open: true)
        return app
    }

    private func message(_ type: String, id: String = UUID().uuidString, from user: String, _ data: [String: Any]) -> String {
        var body = data; body["ControllingUserId"] = user
        let object: [String: Any] = ["MessageType": type, "MessageId": id, "Data": body]
        return String(data: try! JSONSerialization.data(withJSONObject: object), encoding: .utf8)!
    }

    func testSocketHandsEachSubscriberOnlyItsTypesAndKeepsKeepAlivesToItself() throws {
        let app = try model()
        var seen: [String] = []
        let token = app.socket.subscribe(["SyncPlayCommand", "ForceKeepAlive"]) { type, _, _ in seen.append(type) }
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":60}"#)
        app.socket.receiveForTesting(#"{"MessageType":"SyncPlayCommand","Data":{}}"#)
        app.socket.receiveForTesting(#"{"MessageType":"Playstate","Data":{}}"#)
        app.socket.receiveForTesting("not json")
        XCTAssertEqual(seen, ["SyncPlayCommand"])
        app.socket.unsubscribe(token)
        app.socket.receiveForTesting(#"{"MessageType":"SyncPlayCommand","Data":{}}"#)
        XCTAssertEqual(seen, ["SyncPlayCommand"])
    }

    func testAServersKeepAlivePeriodIsClampedSoItCannotCrashTheApp() throws {
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds(NSNumber(value: 60)), 30_000_000_000)
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds(NSNumber(value: Int64.max)), 1_800_000_000_000)
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds(NSNumber(value: 1e300)), 1_800_000_000_000)
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds(NSNumber(value: -5)), 2_500_000_000)
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds(NSNumber(value: Double.nan)), 30_000_000_000)
        XCTAssertEqual(ServerSocket.keepAliveNanoseconds("sixty"), 30_000_000_000)
        let app = try model()
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":1e300}"#)
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":99999999999999999999}"#)
    }

    #if os(iOS)
    func testSigningOutClosesAChooserOpenedUnderTheOldAccount() throws {
        let app = try model()
        app.remote.choose()
        XCTAssertTrue(app.remote.choosing)
        app.remote.accountChanged()
        XCTAssertTrue(app.remote.choosing, "the same account keeps its chooser")
        app.signOut(try XCTUnwrap(app.account))
        XCTAssertFalse(app.remote.choosing, "a chooser from the old account must not attach a TV under the new one")
        XCTAssertNil(app.remote.pendingPlay)
    }
    #endif

    func testAnotherViewersCommandIsRefusedAndOpensNothing() throws {
        let app = try model()
        app.socket.receiveForTesting(message("Play", from: stranger, ["PlayCommand": "PlayNow", "ItemIds": [viewer]]))
        XCTAssertEqual(app.remoteTarget.lastRejection, "other-viewer")
        XCTAssertEqual(app.remoteTarget.lastEffects, [])
        XCTAssertNil(app.player)
    }

    func testPauseWithNothingOnScreenIsRefusedNotHeld() throws {
        let app = try model()
        app.socket.receiveForTesting(message("Playstate", from: viewer, ["Command": "Pause"]))
        XCTAssertEqual(app.remoteTarget.lastRejection, "no-active-viewing")
    }

    func testTheSameMessageTwiceIsObeyedOnce() throws {
        let app = try model()
        let text = message("Play", id: "m-1", from: viewer, ["PlayCommand": "PlayNow", "ItemIds": [stranger]])
        app.socket.receiveForTesting(text)
        XCTAssertEqual(app.remoteTarget.lastEffects, ["open"])
        app.socket.receiveForTesting(text)
        XCTAssertEqual(app.remoteTarget.lastRejection, "duplicate")
        XCTAssertEqual(app.remoteTarget.lastEffects, [])
    }

    func testOnlyCommandsTheAppCarriesOutAreAdvertisedAndObeyed() throws {
        XCTAssertFalse(RemoteTarget.commands.contains("MoveUp"), "no D-pad relay on Apple yet (REM-PD03)")
        XCTAssertFalse(RemoteTarget.commands.contains("SendString"))
        let app = try model()
        app.socket.receiveForTesting(message("GeneralCommand", from: viewer, ["Name": "MoveUp", "Arguments": [String: String]()]))
        XCTAssertEqual(app.remoteTarget.lastRejection, "unsupported")
    }

    func testCommandsWhileTheSocketIsDownAreRefused() throws {
        let app = try model()
        app.remoteTarget.socketChanged(open: false)
        app.socket.receiveForTesting(message("Playstate", from: viewer, ["Command": "Pause"]))
        XCTAssertEqual(app.remoteTarget.lastRejection, "disconnected")
    }

    private final class MemoryKeychain: KeychainBackend {
        var stored: Data?
        func read(service: String) -> (Data?, OSStatus) { (stored, stored == nil ? errSecItemNotFound : errSecSuccess) }
        func write(_ data: Data, service: String) -> OSStatus { stored = data; return errSecSuccess }
        func delete(service: String) { stored = nil }
    }
}
