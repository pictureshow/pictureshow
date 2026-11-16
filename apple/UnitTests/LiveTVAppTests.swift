// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// The live player's timeline comes only from the player's seekable range (LIVE-05).
final class LivePlayerStateTests: XCTestCase {
    private func sample(_ state: inout LivePlayerState, start: Double? = 100, length: Double? = 60, at current: Double, offset: Double? = 3, paused: Bool = false) {
        state.observe(rangeStart: start, rangeDuration: length, currentSeconds: current, liveOffsetSeconds: offset, paused: paused)
    }

    func testPlayingAtTheLivePointIsLiveWithNoGoLive() {
        var state = LivePlayerState()
        sample(&state, at: 157)
        XCTAssertTrue(state.canTimeShift)
        XCTAssertEqual(state.window.defaultPositionMs, 57_000, "the live point sits the player's live offset before the range end")
        XCTAssertEqual(state.positionMs, 57_000)
        XCTAssertTrue(state.atEdge)
        XCTAssertFalse(state.canGoLive)
        XCTAssertEqual(state.liveEdgeSeconds, 157)
    }

    func testBehindLiveOffersGoLiveAndMeasuresFromTheLivePoint() {
        var state = LivePlayerState()
        sample(&state, at: 120)
        XCTAssertEqual(state.behindMs, 37_000)
        XCTAssertFalse(state.atEdge)
        XCTAssertTrue(state.canGoLive)
    }

    func testAPausedBufferThatSlidOutOfTheWindowExpires() {
        var state = LivePlayerState()
        sample(&state, at: 130, paused: true)
        XCTAssertFalse(state.expired)
        XCTAssertEqual(state.pausedAbsoluteMs, 130_000)
        // The window slides on while paused; the paused point is no longer held.
        sample(&state, start: 200, at: 130, paused: true)
        XCTAssertTrue(state.expired)
        XCTAssertNil(state.behindMs, "an expired buffer has no behind-live clock")
        XCTAssertTrue(state.canGoLive)
        // Playing again clears the paused point.
        sample(&state, start: 200, at: 257)
        XCTAssertFalse(state.expired)
        XCTAssertNil(state.pausedAbsoluteMs)
    }

    func testSeeksStayInsideTheWindowAndNeverPassTheLivePoint() {
        var state = LivePlayerState()
        sample(&state, at: 150)
        XCTAssertEqual(state.clampedSeek(500), 157, "a forward seek stops at the live point")
        XCTAssertEqual(state.clampedSeek(10), 100, "no rewind before the oldest held time")
        XCTAssertEqual(state.clampedSeek(140), 140)
    }

    func testNoSeekableRangeMeansNoTimeShiftAndNoFabricatedRewind() {
        var state = LivePlayerState()
        sample(&state, start: nil, length: nil, at: 42)
        XCTAssertFalse(state.canTimeShift)
        XCTAssertNil(state.clampedSeek(10))
        XCTAssertFalse(state.canGoLive)
        sample(&state, start: 100, length: 0, at: 100)
        XCTAssertFalse(state.canTimeShift, "an empty range is not a time-shift window")
    }

    func testAnOffsetLargerThanTheWindowPutsTheLivePointAtItsStart() {
        var state = LivePlayerState()
        sample(&state, length: 4, at: 100, offset: 30)
        XCTAssertEqual(state.window.defaultPositionMs, 0)
    }
}

/// A Live TV service the tests control: per-call channel answers, optional delays and failures.
private final class FakeLive: LiveTVService {
    var policyValue = LivePolicy(access: true, manage: true, deleteContent: false, userData: true)
    var channelAnswers: [(delayMs: UInt64, names: [String])] = []
    var channelCalls = 0
    var policyCalls = 0
    var failNext = false

    func policy(account: Account) async throws -> LivePolicy {
        policyCalls += 1
        if failNext { failNext = false; throw LiveTVError(kind: .network, message: "offline") }
        return policyValue
    }
    func info(account: Account) async throws -> LiveInfo { LiveInfo(enabled: true) }
    func channels(account: Account, startIndex: Int, limit: Int, search: String?, favorites: Bool) async throws -> LivePage<LiveChannel> {
        let answer: (delayMs: UInt64, names: [String]) = channelCalls < channelAnswers.count ? channelAnswers[channelCalls] : (0, ["A"])
        channelCalls += 1
        if answer.delayMs > 0 { try await Task.sleep(nanoseconds: answer.delayMs * 1_000_000) }
        let items = answer.names.map { LiveChannel(id: "c-\($0)", name: $0) }
        return LivePage(items: items, totalRecordCount: items.count)
    }
    func channel(account: Account, id: String) async throws -> LiveChannel { LiveChannel(id: id) }
    func programs(account: Account, channelIDs: [String], startUTC: String, endUTC: String, startIndex: Int, limit: Int) async throws -> LivePage<LiveProgram> { LivePage() }
    func program(account: Account, id: String) async throws -> LiveProgram { LiveProgram(id: id) }
    func recordings(account: Account, startIndex: Int, limit: Int) async throws -> ItemPage { try JSONDecoder().decode(ItemPage.self, from: Data(#"{"Items":[],"TotalRecordCount":0}"#.utf8)) }
    func timers(account: Account, series: Bool) async throws -> [LiveSchedule] { [] }
    func defaults(account: Account, programID: String) async throws -> LiveSchedule { LiveSchedule(fields: [:]) }
    func createTimer(account: Account, draft: LiveSchedule, series: Bool) async -> LiveWrite { LiveWrite("ok") }
    func updateTimer(account: Account, schedule: LiveSchedule, series: Bool) async -> LiveWrite { LiveWrite("ok") }
    func cancelTimer(account: Account, id: String, series: Bool) async -> LiveWrite { LiveWrite("ok") }
    func deleteRecording(account: Account, id: String) async -> LiveWrite { LiveWrite("ok") }
    func favorite(account: Account, id: String, favorite: Bool) async throws -> LiveChannel { LiveChannel(id: id) }
}

@MainActor
final class LiveTVModelTests: XCTestCase {
    private let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "s", name: "Home"),
                                  userId: "u1", username: "viewer", accessToken: "t", deviceID: "d")
    private var clock: Int64 = 1_800_000_000_000

    private func model(_ service: FakeLive) -> LiveTVModel {
        LiveTVModel(account: account, service: service, now: { [unowned self] in self.clock })
    }

    /// LIVE-11: a slow answer to an older request never lands over the newer one.
    func testOnlyTheNewestGenerationLands() async throws {
        let service = FakeLive()
        service.channelAnswers = [(400, ["Old"]), (0, ["New"])]
        let live = model(service)
        live.refresh()
        try await Task.sleep(nanoseconds: 50_000_000)
        live.refresh()
        await live.waitForLoad()
        try await Task.sleep(nanoseconds: 600_000_000)
        XCTAssertEqual(live.state.channels.map(\.name), ["New"])
        XCTAssertFalse(live.state.loading)
    }

    /// LIVE-11: the guide refreshes itself at four minutes, keeps its actions while that runs, and a failed quiet
    /// attempt changes nothing and waits a minute before trying again.
    func testQuietRefreshAtFourMinutesKeepsActions() async throws {
        let service = FakeLive()
        let live = model(service)
        live.refresh()
        await live.waitForLoad()
        let fetched = try XCTUnwrap(live.state.fetchedAtMs)
        XCTAssertEqual(service.policyCalls, 1)

        live.quietRefreshIfDue(at: fetched + 239_999)
        XCTAssertEqual(service.policyCalls, 1, "not due before four minutes")

        service.channelAnswers = [(0, ["A"]), (200, ["A", "B"])]
        clock = fetched + 240_000
        live.quietRefreshIfDue(at: clock)
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertTrue(live.state.actionsAvailable(nowMs: clock), "a quiet refresh keeps tune and schedule available")
        XCTAssertFalse(live.state.loading)
        await live.waitForLoad()
        XCTAssertEqual(live.state.channels.map(\.name), ["A", "B"])
        XCTAssertEqual(live.state.fetchedAtMs, clock)

        // A failed quiet refresh leaves the snapshot alone and backs off for a minute.
        let second = clock
        clock = second + 240_000
        service.failNext = true
        live.quietRefreshIfDue(at: clock)
        await live.waitForLoad()
        XCTAssertFalse(live.state.offline)
        XCTAssertEqual(live.state.fetchedAtMs, second)
        let calls = service.policyCalls
        live.quietRefreshIfDue(at: clock + 30_000)
        XCTAssertEqual(service.policyCalls, calls, "waits a minute after a failed attempt")
        live.quietRefreshIfDue(at: clock + 60_000)
        await live.waitForLoad()
        XCTAssertEqual(service.policyCalls, calls + 1)
    }

    /// A stale snapshot can't act, and a visible (not quiet) refresh holds actions until it lands.
    func testStaleOrLoadingSnapshotDisablesActions() async {
        let service = FakeLive()
        let live = model(service)
        XCTAssertFalse(live.state.actionsAvailable(nowMs: clock), "nothing loaded yet")
        live.refresh()
        await live.waitForLoad()
        XCTAssertTrue(live.state.actionsAvailable(nowMs: clock))
        XCTAssertFalse(live.state.actionsAvailable(nowMs: clock + 300_001), "older than five minutes")
    }

    /// LIVE-10: a denied policy keeps nothing from the previous read.
    func testLosingAccessDropsEverything() async {
        let service = FakeLive()
        let live = model(service)
        live.refresh()
        await live.waitForLoad()
        XCTAssertFalse(live.state.channels.isEmpty)
        service.policyValue = LivePolicy(access: false)
        live.refresh()
        await live.waitForLoad()
        XCTAssertTrue(live.state.channels.isEmpty)
        XCTAssertEqual(live.state.policy?.access, false)
    }
}
