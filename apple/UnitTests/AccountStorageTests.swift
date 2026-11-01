// SPDX-License-Identifier: MPL-2.0
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class AccountStorageTests: XCTestCase {
    /// Run the production simulator loader against an isolated directory. A fresh
    /// install must not fall through to the unavailable unsigned-app Keychain;
    /// a corrupt existing snapshot must still fail instead of losing sign-ins.
    func testFreshInstallLegacyMigrationAndCorruption() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let empty = try AccountVault.loadSnapshot(simulatorDirectory: directory)
        XCTAssertTrue(empty.accounts.isEmpty)
        XCTAssertTrue(empty.servers.isEmpty)
        XCTAssertNil(empty.selected)

        let viewer = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!),
                             userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
        try JSONEncoder().encode(viewer).write(to: directory.appendingPathComponent("simulator-account.json"))
        let migrated = try AccountVault.loadSnapshot(simulatorDirectory: directory)
        XCTAssertEqual(migrated.accounts, [viewer])
        XCTAssertEqual(migrated.selected, viewer.localKey)

        let snapshotFile = directory.appendingPathComponent("simulator-accounts-v1.json")
        try Data("corrupt fixture".utf8).write(to: snapshotFile)
        XCTAssertThrowsError(try AccountVault.loadSnapshot(simulatorDirectory: directory))
        try JSONEncoder().encode(migrated).write(to: snapshotFile)
        XCTAssertEqual(try AccountVault.loadSnapshot(simulatorDirectory: directory).accounts, [viewer])
    }
}
