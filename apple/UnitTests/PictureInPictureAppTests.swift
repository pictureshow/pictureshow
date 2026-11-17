// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// TOUCH-15 in the app: the player's real state feeds the shared gate, and the window's close stops the viewing.
@MainActor
final class PictureInPictureAppTests: XCTestCase {
    private final class MemoryKeychain: KeychainBackend {
        var stored: Data?
        func read(service: String) -> (Data?, OSStatus) { (stored, stored == nil ? errSecItemNotFound : errSecSuccess) }
        func write(_ data: Data, service: String) -> OSStatus { stored = data; return errSecSuccess }
        func delete(service: String) { stored = nil }
    }

    private func model() throws -> AppModel {
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: "0123456789abcdef0123456789abcdef",
                              username: "v", accessToken: "t", deviceID: "dev-pip")
        let store = AccountStore(service: suite, fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json"),
                                 keychain: MemoryKeychain(), defaults: UserDefaults(suiteName: suite)!, isSimulator: true)
        try store.save(AccountSnapshot(servers: [account.server], accounts: [account], selected: account.key))
        let app = AppModel(store: store)
        app.socket.stop()
        return app
    }

    /// A download playing from this device: no server negotiation, so the only thing missing is a picture.
    private func localViewing(_ app: AppModel) -> PlaybackController {
        let entry = OfflineEntry(id: "i1:original", itemId: "i1", kind: .movie, preset: .original, runtimeTicks: 600_000_000,
                                 labels: OfflineLabels(title: "A Spring Crossing"),
                                 resources: [OfflineResource(id: "media", role: .media, path: "media.mp4", bytes: 2_000_000, sha256: "x", complete: true)],
                                 tracks: [], job: OfflineJob(phase: .ready, failure: nil))
        let media = LocalMedia(accountKey: app.account!.key, entry: entry, url: URL(fileURLWithPath: "/tmp/pip-test.mp4"), subtitles: [])
        let request = PlayerRequest(account: app.account!, item: MediaItem(id: "i1", name: "A Spring Crossing", runTimeTicks: 600_000_000),
                                    fromStart: false, local: media)
        return PlaybackController(app: app, request: request)
    }

    private func allowed(_ controller: PlaybackController, tv: Bool = false, supports: Bool = true) -> Bool {
        PictureInPicture.allowed(controller.pictureInPictureState(tvInterface: tv, deviceSupports: supports))
    }

    func testGateFollowsThePlayersPictureAndItsEnd() throws {
        let app = try model()
        let controller = localViewing(app)
        // Nothing on screen yet: no item, no size.
        XCTAssertFalse(allowed(controller))
        controller.player.replaceCurrentItem(with: AVPlayerItem(url: URL(fileURLWithPath: "/tmp/pip-test.mp4")))
        XCTAssertFalse(allowed(controller), "an item without a known picture size has nothing to show")
        controller.videoSize = CGSize(width: 1920, height: 1080)
        XCTAssertTrue(allowed(controller))
        #if os(iOS)
        XCTAssertTrue(PictureInPictureHost.settings(for: controller).allowed == PictureInPictureHost.deviceSupports)
        XCTAssertFalse(PictureInPictureHost.settings(for: controller).linear, "a film can skip both ways")
        #endif
        XCTAssertFalse(allowed(controller, tv: true), "never on a TV interface")
        XCTAssertFalse(allowed(controller, supports: false))
        XCTAssertEqual(controller.pictureInPictureActions.map(\.action), ["back10", "play", "forward10"])
        controller.close()
        XCTAssertFalse(allowed(controller), "a viewing that has ended is not kept in a small window")
    }

    #if os(iOS)
    func testClosingTheWindowStopsTheViewingButExpandingKeepsIt() throws {
        let app = try model()
        let expanded = localViewing(app)
        let host = PictureInPictureHost()
        host.controller = expanded
        var entered = 0
        host.onEnter = { entered += 1 }
        host.willStart()
        XCTAssertTrue(host.active)
        XCTAssertEqual(entered, 1, "sheets and menus close as the window opens")
        host.restore()
        host.didStop()
        XCTAssertFalse(host.active)
        XCTAssertFalse(expanded.finished, "expanding returns to the same viewing")

        let closed = localViewing(app)
        host.controller = closed
        host.willStart()
        host.didStop()
        XCTAssertTrue(closed.finished, "closing the window stops the viewing through the normal stop")
        XCTAssertNil(app.player)
    }

    /// AUTH-18: the host reports the window as in use only while its viewing plays, and reports it gone on close or a
    /// failed start, so the relock timer never waits on a window that isn't there.
    func testWindowReportsInUseOnlyWhilePlaying() {
        let host = PictureInPictureHost()
        var reports: [String] = []
        host.onUse = { active, playing in reports.append("\(active)/\(playing)") }
        host.rateChanged(1)
        XCTAssertEqual(reports, [], "no window, nothing to report")
        host.willStart()
        host.rateChanged(0)
        host.rateChanged(1.5)
        host.didStop()
        host.failedToStart()
        XCTAssertEqual(reports, ["true/true", "true/false", "true/true", "false/false", "false/false"])
    }

    func testAutomaticStartFollowsTheGate() throws {
        guard PictureInPictureHost.deviceSupports else { throw XCTSkip("This simulator has no picture-in-picture") }
        let host = PictureInPictureHost()
        host.attach(AVPlayerLayer(player: AVPlayer()))
        let system = try XCTUnwrap(host.system)
        host.update(.init(allowed: true, linear: false))
        XCTAssertTrue(system.canStartPictureInPictureAutomaticallyFromInline)
        host.update(.init(allowed: false, linear: true))
        XCTAssertFalse(system.canStartPictureInPictureAutomaticallyFromInline, "going Home must not shrink a closed or failed viewing")
        XCTAssertTrue(system.requiresLinearPlayback)
        XCTAssertFalse(host.start(), "More can't start a window the gate refuses")
    }
    #endif
}
