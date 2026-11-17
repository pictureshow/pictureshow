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

/// Watch together in the app: socket registration, group routing of the player's controls, stay-on-close, sign-out.
@MainActor
final class SyncPlayAppTests: XCTestCase {
    /// Recorded SyncPlay requests, a virtual clock and timers that fire on `advance`.
    final class Fake: SyncEngineTransport, SyncEngineClock, SyncEngineScheduler {
        final class Timer: SyncScheduled {
            let due: Int64, work: () -> Void
            var cancelled = false
            init(due: Int64, work: @escaping () -> Void) { self.due = due; self.work = work }
            func cancel() { cancelled = true }
        }
        var posted: [SyncRequest] = []
        var wallMillis: Int64 = 1_800_000_000_000
        var monotonicMillis: Int64 = 1_000
        var timers: [Timer] = []
        var operations: [String] { posted.map(\.operation) }
        func post(_ request: SyncRequest, completion: @escaping (SyncPostOutcome) -> Void) { posted.append(request); completion(.ok) }
        func utcTime(completion: @escaping ((receivedMillis: Int64, transmittedMillis: Int64)?) -> Void) { completion((wallMillis, wallMillis)) }
        func after(_ millis: Int64, _ work: @escaping () -> Void) -> SyncScheduled {
            let timer = Timer(due: monotonicMillis + millis, work: work); timers.append(timer); return timer
        }
        func advance(_ millis: Int64) {
            let end = monotonicMillis + millis
            while let next = timers.filter({ !$0.cancelled && $0.due <= end }).min(by: { $0.due < $1.due }) {
                wallMillis += next.due - monotonicMillis; monotonicMillis = next.due; next.cancelled = true; next.work()
            }
            wallMillis += end - monotonicMillis; monotonicMillis = end
        }
    }

    private let viewer = "0123456789abcdef0123456789abcdef"

    private func model() throws -> (AppModel, Fake) {
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: viewer, username: "v", accessToken: "t", deviceID: "dev-sync")
        let store = AccountStore(service: suite, fileURL: FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json"),
                                 keychain: MemoryKeychain(), defaults: UserDefaults(suiteName: suite)!, isSimulator: true)
        try store.save(AccountSnapshot(servers: [account.server], accounts: [account], selected: account.key))
        let app = AppModel(store: store)
        app.socket.stop()
        let fake = Fake()
        app.syncPlay.installForTesting(fake)
        return (app, fake)
    }

    private static func update(_ type: String, _ data: String) -> String {
        "{\"MessageType\":\"SyncPlayGroupUpdate\",\"Data\":{\"GroupId\":\"g\",\"Type\":\"\(type)\",\"Data\":\(data)}}"
    }
    private static let joined = update("GroupJoined", "{\"GroupId\":\"g\",\"GroupName\":\"Evening\",\"State\":\"Paused\",\"Participants\":[\"v\",\"w\"],\"LastUpdatedAt\":\"2026-11-10T12:00:00Z\"}")
    private static let queue = update("PlayQueue", "{\"Reason\":\"NewPlaylist\",\"LastUpdate\":\"2026-11-10T12:00:00Z\",\"Playlist\":[{\"ItemId\":\"item\",\"PlaylistItemId\":\"entry-0\"}],\"PlayingItemIndex\":0,\"StartPositionTicks\":0,\"IsPlaying\":false,\"ShuffleMode\":\"Sorted\",\"RepeatMode\":\"RepeatNone\"}")

    private static let twoEntries = update("PlayQueue", "{\"Reason\":\"NewPlaylist\",\"LastUpdate\":\"2026-11-10T12:00:01Z\",\"Playlist\":[{\"ItemId\":\"item\",\"PlaylistItemId\":\"entry-0\"},{\"ItemId\":\"next\",\"PlaylistItemId\":\"entry-1\"}],\"PlayingItemIndex\":0,\"StartPositionTicks\":0,\"IsPlaying\":false,\"ShuffleMode\":\"Sorted\",\"RepeatMode\":\"RepeatNone\"}")

    /// Registers the socket and joins group "g" through the real socket subscription.
    private func join(_ app: AppModel, _ fake: Fake) {
        app.socket.openForTesting()
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":60}"#)
        app.syncPlay.join(SyncGroup(id: "g", name: "Evening", state: "Paused", participants: [], lastUpdatedTicks: 0))
        app.socket.receiveForTesting(Self.joined)
        app.socket.receiveForTesting(Self.queue)
    }

    private func groupViewing(_ app: AppModel) -> PlaybackController {
        let request = PlayerRequest(account: app.account!, item: MediaItem(id: "item", name: "Film", runTimeTicks: 36_000_000_000), fromStart: false,
                                    startTicks: 0, sync: SyncStamp(generation: app.syncPlay.engine.membership.generation, playlistItemId: "entry-0"))
        return PlaybackController(app: app, request: request)
    }

    func testSocketRegistrationNeedsAValidHeartbeatOnTheCurrentConnection() throws {
        let (app, fake) = try model()
        app.socket.openForTesting()
        XCTAssertFalse(app.socket.registration.registered, "An open transport isn't a registered session")
        app.syncPlay.join(SyncGroup(id: "g", name: "Evening", state: "Paused", participants: [], lastUpdatedTicks: 0))
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":"sixty"}"#)
        XCTAssertFalse(app.socket.registration.registered)
        XCTAssertTrue(fake.posted.isEmpty, "No membership request before registration")
        app.socket.receiveForTesting(#"{"MessageType":"ForceKeepAlive","Data":60}"#)
        XCTAssertTrue(app.socket.registration.registered)
        XCTAssertEqual(fake.operations.first, "Join")
        app.socket.closeForTesting()
        XCTAssertFalse(app.socket.registration.registered)
        app.socket.openForTesting()
        XCTAssertFalse(app.socket.registration.registered, "A renewed connection registers again")
    }

    func testPlayersControlsInAGroupAreGroupRequestsAndItStartsPaused() throws {
        let (app, fake) = try model()
        join(app, fake)
        app.socket.receiveForTesting(Self.twoEntries)
        let controller = groupViewing(app)
        defer { controller.close(reason: .MANUAL_LEAVE) }
        XCTAssertTrue(controller.grouped)
        XCTAssertFalse(controller.player.allowsExternalPlayback, "AirPlay is off while in a group (SYN-10)")
        fake.posted.removeAll()
        controller.userPlay()
        controller.userPause(origin: .remote)
        controller.seek(to: 60)
        controller.playNext()
        // The viewer's seek waits for the source to be ready (sync-application delayed viewer-origin seek); this
        // source never loads here, so no Seek goes out and nothing moves locally.
        XCTAssertEqual(fake.operations, ["Unpause", "Pause", "NextItem"])
        XCTAssertEqual(controller.player.rate, 0, "The local player waits for the server's command")
    }

    func testServerApplicationMovesOnlyThisPlayerAndIsNeverEchoed() throws {
        let (app, fake) = try model()
        join(app, fake)
        let controller = groupViewing(app)
        defer { controller.close(reason: .MANUAL_LEAVE) }
        fake.posted.removeAll()
        controller.syncApply(positionTicks: nil, playing: true)
        let settled = expectation(description: "rate notifications delivered")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { settled.fulfill() }
        wait(for: [settled], timeout: 2)
        XCTAssertFalse(fake.operations.contains("Unpause"))
        XCTAssertFalse(fake.operations.contains("Pause"))
        XCTAssertEqual(controller.lastControlOrigin, .server)
    }

    func testClosingAGroupViewingStaysInTheGroupAndShowsTheReminder() throws {
        let (app, fake) = try model()
        join(app, fake)
        let controller = groupViewing(app)
        fake.posted.removeAll()
        controller.close()
        XCTAssertEqual(fake.operations, ["SetIgnoreWait"])
        XCTAssertTrue(app.syncPlay.participating)
        XCTAssertEqual(app.syncPlay.stayReminder?.groupName, "Evening")
        // A title chosen while staying becomes the group's queue; nothing opens locally.
        app.play(MediaItem(id: "other", name: "Other", type: "Movie"))
        XCTAssertEqual(fake.operations.last, "SetNewQueue")
        XCTAssertNil(app.player)
        app.syncPlay.leave()
        XCTAssertEqual(fake.operations.last, "Leave")
        XCTAssertNil(app.syncPlay.stayReminder)
    }

    func testSignOutLeavesTheGroupAndAnAccountSwitchStartsClean() throws {
        let (app, fake) = try model()
        join(app, fake)
        fake.posted.removeAll()
        let account = try XCTUnwrap(app.account)
        _ = app.syncPlay.signingOut(account)
        XCTAssertEqual(fake.operations, ["Leave"])
        XCTAssertFalse(app.syncPlay.participating)
        _ = app.syncPlay.signingOut(account)
        XCTAssertEqual(fake.operations, ["Leave"], "Leave is sent once")
    }

    func testAViewingForAnOlderMembershipIsNotGrouped() throws {
        let (app, fake) = try model()
        join(app, fake)
        let generation = app.syncPlay.engine.membership.generation
        app.syncPlay.leave()
        join(app, fake)
        XCTAssertGreaterThan(app.syncPlay.engine.membership.generation, generation)
        let stale = PlaybackController(app: app, request: PlayerRequest(account: app.account!, item: MediaItem(id: "item", name: "Film"), fromStart: false,
                                                                         sync: SyncStamp(generation: generation, playlistItemId: "entry-0")))
        defer { stale.close() }
        XCTAssertEqual(stale.syncFacts.generation, generation)
        XCTAssertEqual(app.syncPlay.engine.naturalCompletion(stale.syncRenderer!, current: true, unpaused: true), .HOLD,
                       "A renderer from an older membership can't drive continuation")
    }

    func testGroupTransportFollowsTheServersQueueAndOnlyAsks() throws {
        let (app, fake) = try model()
        join(app, fake)
        fake.posted.removeAll()
        var transport = app.syncPlay.transport
        XCTAssertTrue(transport.visible)
        XCTAssertFalse(transport.previousEnabled || transport.nextEnabled, "One Repeat Off entry has nowhere to go")
        app.syncPlay.requestNext(); app.syncPlay.requestPrevious()
        XCTAssertFalse(fake.operations.contains("NextItem") || fake.operations.contains("PreviousItem"))
        app.socket.receiveForTesting(Self.twoEntries)
        transport = app.syncPlay.transport
        XCTAssertTrue(transport.nextEnabled)
        XCTAssertFalse(transport.previousEnabled)
        XCTAssertEqual(transport.primary, "play")
        app.syncPlay.requestNext()
        app.syncPlay.requestPlayPause()
        XCTAssertEqual(fake.operations.filter { $0 == "NextItem" || $0 == "Unpause" }, ["NextItem", "Unpause"])
        XCTAssertEqual(app.syncPlay.engine.membership.queue?.current?.playlistItemId, "entry-0", "Nothing moves before the server says so")
        XCTAssertEqual(SyncPresentation.watchingCount(app.syncPlay.participants.count), "2 people watching")
    }

    func testAirPlayInAGroupAsksAndRoutesOnlyAfterTheLeave() throws {
        let (app, fake) = try model()
        join(app, fake)
        let controller = groupViewing(app)
        defer { controller.close(reason: .MANUAL_LEAVE) }
        fake.posted.removeAll()
        var routes = 0
        XCTAssertTrue(app.syncPlay.askToLeaveForAirPlay { routes += 1 })
        let first = try XCTUnwrap(app.syncPlay.airPlayConsent)
        app.syncPlay.cancelAirPlay(first)
        XCTAssertNil(app.syncPlay.airPlayConsent)
        XCTAssertTrue(fake.posted.isEmpty, "Cancel keeps the group")
        XCTAssertTrue(app.syncPlay.askToLeaveForAirPlay { routes += 1; controller.airPlayAfterLeaving() })
        let second = try XCTUnwrap(app.syncPlay.airPlayConsent)
        app.syncPlay.leaveForAirPlay(first)
        XCTAssertEqual(routes, 0, "An older dialog's answer does nothing")
        app.syncPlay.leaveForAirPlay(second)
        XCTAssertEqual(fake.operations.filter { $0 == "Leave" }, ["Leave"])
        XCTAssertEqual(routes, 1)
        XCTAssertFalse(app.syncPlay.participating)
        XCTAssertTrue(controller.player.allowsExternalPlayback)
        XCTAssertTrue(controller.routePickerPending)
    }

    func testAnAccountChangeDropsAnOpenAirPlayQuestion() throws {
        let (app, fake) = try model()
        join(app, fake)
        var routes = 0
        XCTAssertTrue(app.syncPlay.askToLeaveForAirPlay { routes += 1 })
        let consent = try XCTUnwrap(app.syncPlay.airPlayConsent)
        _ = app.syncPlay.signingOut(try XCTUnwrap(app.account))
        app.syncPlay.leaveForAirPlay(consent)
        XCTAssertEqual(routes, 0)
        XCTAssertNil(app.syncPlay.airPlayConsent)
    }

    private final class MemoryKeychain: KeychainBackend {
        var stored: Data?
        func read(service: String) -> (Data?, OSStatus) { (stored, stored == nil ? errSecItemNotFound : errSecSuccess) }
        func write(_ data: Data, service: String) -> OSStatus { stored = data; return errSecSuccess }
        func delete(service: String) { stored = nil }
    }
}
