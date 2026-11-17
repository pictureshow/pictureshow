// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

/// The SyncPlay session engine against fake transport, clock, scheduler and renderer.
final class SyncSessionEngineTests: XCTestCase {
    private func joinedEngine(_ world: SyncFakeWorld, entries: [(String, String)] = [("item", "entry-0")], state: String = "Paused") -> SyncSessionEngine {
        world.serverOffsetMillis = 0
        let engine = world.engine()
        engine.socketOpened(connection: 1)
        engine.socketKeepAlive(connection: 1, valid: true)
        engine.join(groupId: "g")
        engine.receive(SyncFakeWorld.joined(state: state))
        engine.receive(SyncFakeWorld.queue(entries: entries))
        return engine
    }

    private func renderer(entry: String = "entry-0", generation: Int64, prepared: Bool = true, unpaused: Bool = false) -> SyncFakeRenderer {
        SyncFakeRenderer(SyncRendererFacts(entryId: entry, generation: generation, prepared: prepared, buffering: false, unpaused: unpaused,
                                           positionTicks: 0, durationTicks: 36_000_000_000, eligible: true))
    }

    func testJoinWaitsForRegistrationThenOpensTheGroupEntryAtTheServerPosition() {
        let world = SyncFakeWorld()
        world.serverOffsetMillis = 0
        let engine = world.engine()
        engine.socketOpened(connection: 1)
        engine.join(groupId: "g")
        XCTAssertTrue(world.posted.isEmpty)
        engine.socketKeepAlive(connection: 1, valid: true)
        XCTAssertEqual(world.operations, ["Join", "Ping"], "Join, then the measured round trip")
        engine.receive(SyncFakeWorld.joined())
        engine.receive(SyncFakeWorld.queue(entries: [("item", "entry-0")], start: 900_000_000))
        XCTAssertEqual(engine.server, .confirmed)
        XCTAssertEqual(world.opens, [SyncOpenRequest(generation: engine.membership.generation, itemId: "item", playlistItemId: "entry-0", startTicks: 900_000_000)])
        // A repeated snapshot doesn't reopen the same entry.
        engine.receive(SyncFakeWorld.queue(entries: [("item", "entry-0")], start: 900_000_000, at: "2026-11-10T12:00:01.0000000Z"))
        XCTAssertEqual(world.opens.count, 1)
    }

    func testScheduledUnpauseAppliesAtWhenWithoutEchoingAGroupRequest() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        world.posted.removeAll()
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis + 500, positionTicks: 100_000_000, emittedMillis: world.wallMillis))
        XCTAssertTrue(player.applied.isEmpty, "Nothing before When")
        world.advance(499)
        XCTAssertTrue(player.applied.isEmpty)
        world.advance(1)
        XCTAssertEqual(player.applied.count, 1)
        XCTAssertEqual(player.applied.first?.playing, true)
        XCTAssertEqual(player.applied.first?.positionTicks, 100_000_000)
        XCTAssertFalse(world.operations.contains("Unpause"), "An applied server command is never echoed")
        XCTAssertFalse(world.operations.contains("Seek"))
    }

    func testCommandIsHeldUntilTheRendererForItsEntryIsPreparedThenCatchesUp() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation, prepared: false)
        engine.bind(player)
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis, positionTicks: 0, emittedMillis: world.wallMillis))
        world.advance(2_000)
        XCTAssertTrue(player.applied.isEmpty, "An unprepared renderer never starts")
        player.syncFacts.prepared = true
        engine.rendererChanged()
        XCTAssertEqual(player.applied.count, 1)
        XCTAssertEqual(player.applied.first?.positionTicks, 20_000_000, "Late application lands where the group is now")
    }

    func testCommandForAnotherEntryOrGroupNeverMovesTheRenderer() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(entry: "entry-other", generation: engine.membership.generation)
        engine.bind(player)
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis, positionTicks: 0))
        XCTAssertTrue(player.applied.isEmpty)
        let other = renderer(generation: engine.membership.generation)
        engine.bind(other)
        engine.receive(SyncFakeWorld.command("Pause", group: "someone-else", whenMillis: world.wallMillis + 10, positionTicks: 0, emittedMillis: world.wallMillis + 10))
        world.advance(100)
        XCTAssertTrue(other.applied.allSatisfy { $0.playing }, "Another group's Pause is ignored")
    }

    func testViewerControlsAreGroupRequestsAndLeaveTheLocalPlayerAlone() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world, entries: [("item", "entry-0"), ("next", "entry-1")])
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        world.posted.removeAll()
        engine.requestUnpause(); engine.requestPause(); engine.requestSeek(ticks: 50_000_000); engine.requestNext()
        // Previous from the first Repeat Off entry has no target in the authoritative queue, so nothing is asked.
        engine.requestPrevious()
        XCTAssertEqual(world.operations, ["Unpause", "Pause", "Seek", "NextItem"])
        XCTAssertEqual(world.posted[2].body?.rendered, "{\"PositionTicks\":50000000}")
        XCTAssertTrue(player.applied.isEmpty)
    }

    func testReadinessReportsActualEntryPositionAndIntentOncePerChange() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world, state: "Waiting")
        let player = renderer(generation: engine.membership.generation, prepared: false)
        engine.bind(player)
        XCTAssertEqual(world.operations.filter { $0 == "Ready" || $0 == "Buffering" }, ["Buffering"])
        engine.rendererChanged()
        XCTAssertEqual(world.operations.filter { $0 == "Ready" || $0 == "Buffering" }, ["Buffering"], "No repeat without a change")
        player.syncFacts.prepared = true
        player.syncFacts.positionTicks = 70_000_000
        engine.rendererChanged()
        let ready = world.posted.last { $0.path == "SyncPlay/Ready" }
        XCTAssertEqual(ready?.body?["PositionTicks"]?.int64, 70_000_000)
        XCTAssertEqual(ready?.body?["PlaylistItemId"]?.content, "entry-0")
        XCTAssertEqual(ready?.body?["IsPlaying"]?.bool, false)
    }

    func testClosingTheViewingStaysOnceAndWatchRestoresWaitForMe() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation, unpaused: true)
        engine.bind(player)
        world.posted.removeAll()
        let decision = engine.closeViewing(player, reason: .BACK)
        XCTAssertEqual(decision.outcome, .STAY)
        XCTAssertEqual(player.applied.last?.playing, false, "The device pauses")
        XCTAssertEqual(world.operations, ["SetIgnoreWait"])
        XCTAssertEqual(world.posted.first?.body?.rendered, "{\"IgnoreWait\":true}")
        XCTAssertTrue(engine.participating)
        XCTAssertEqual(engine.stayReminder, SyncStayReminder(groupName: "Evening", rejoin: true, leaveUnconfirmed: false))
        // A stayed device never opens a new group entry by itself.
        let opens = world.opens.count
        engine.receive(SyncFakeWorld.queue(entries: [("item", "entry-0"), ("next", "entry-1")], index: 1, at: "2026-11-10T12:00:05.0000000Z"))
        XCTAssertEqual(world.opens.count, opens)
        engine.closeViewing(nil, reason: .CLOSE)
        XCTAssertEqual(world.operations, ["SetIgnoreWait"], "IgnoreWait is sent once")
        engine.watch()
        XCTAssertEqual(world.opens.last?.playlistItemId, "entry-1")
        engine.bind(renderer(entry: "entry-1", generation: engine.membership.generation))
        XCTAssertEqual(world.posted.filter { $0.path == "SyncPlay/SetIgnoreWait" }.last?.body?.rendered, "{\"IgnoreWait\":false}")
    }

    func testLeaveRetainsLocalPauseNeverStopsTheGroupAndIgnoresLateEvents() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation, unpaused: true)
        engine.bind(player)
        world.posted.removeAll()
        engine.leave()
        XCTAssertEqual(world.operations, ["Leave"])
        XCTAssertEqual(player.applied.last?.playing, false)
        XCTAssertFalse(engine.participating)
        XCTAssertEqual(engine.departure, .MANUAL_LEAVE)
        engine.receive(SyncFakeWorld.joined(at: "2026-11-10T12:10:00.0000000Z"))
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis, positionTicks: 0))
        XCTAssertEqual(engine.membership.phase, "Idle", "Late events cannot undo Leave")
        XCTAssertEqual(player.applied.count, 1)
    }

    func testRenderingBackgroundLeavesOnceAndSignOutTerminates() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation, unpaused: true)
        engine.bind(player)
        world.posted.removeAll()
        XCTAssertEqual(engine.closeViewing(player, reason: .BACKGROUND_RENDERING).outcome, .LEAVE)
        XCTAssertEqual(world.operations, ["Leave"])
        engine.closeViewing(player, reason: .BACKGROUND_RENDERING)
        XCTAssertEqual(world.operations, ["Leave"])

        let other = SyncFakeWorld()
        let second = joinedEngine(other)
        other.posted.removeAll()
        second.terminate()
        second.terminate()
        XCTAssertEqual(other.operations, ["Leave"])
        second.join(groupId: "g")
        XCTAssertEqual(other.operations, ["Leave"], "A terminated engine sends nothing")
    }

    func testDriftBeyondTheSeekThresholdCorrectsBySeekingWithCooldown() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis, positionTicks: 0, emittedMillis: world.wallMillis))
        XCTAssertEqual(player.applied.count, 1)
        // The fake renderer doesn't advance: after 3 s it is 3 s behind the group.
        world.advance(3_000)
        engine.tick()
        XCTAssertEqual(engine.driftSeeks, 1)
        XCTAssertEqual(player.applied.last?.positionTicks, 30_000_000)
        world.advance(500)
        engine.tick()
        XCTAssertEqual(engine.driftSeeks, 1, "Seek cooldown")
        XCTAssertFalse(world.operations.contains("Seek"), "Correction is local")
    }

    func testSocketLossHoldsOutputAndRejoinsAfterRegistration() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation, unpaused: true)
        engine.bind(player)
        world.posted.removeAll()
        engine.socketClosed(connection: 1)
        XCTAssertEqual(engine.membership.phase, "Reconnecting")
        XCTAssertEqual(player.applied.last?.playing, false)
        XCTAssertFalse(engine.clockUsable)
        engine.socketOpened(connection: 2)
        engine.socketKeepAlive(connection: 1, valid: true)
        XCTAssertTrue(world.posted.isEmpty, "A retired connection's heartbeat doesn't register")
        engine.socketKeepAlive(connection: 2, valid: true)
        XCTAssertEqual(world.operations.first, "Join")
        XCTAssertTrue(engine.clockUsable)
    }

    func testNaturalCompletionLetsTheServerOwnContinuation() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world, entries: [("a", "entry-0"), ("b", "entry-1")])
        let player = renderer(generation: engine.membership.generation, unpaused: true)
        engine.bind(player)
        world.posted.removeAll()
        XCTAssertEqual(engine.naturalCompletion(player, current: false, unpaused: true), .HOLD)
        XCTAssertTrue(world.posted.isEmpty, "An obsolete decision sends nothing")
        XCTAssertEqual(engine.naturalCompletion(player, current: true, unpaused: true), .NEXT)
        XCTAssertEqual(world.operations, ["NextItem"])

        let single = SyncFakeWorld()
        let last = joinedEngine(single)
        let ended = renderer(generation: last.membership.generation, unpaused: true)
        last.bind(ended)
        single.posted.removeAll()
        XCTAssertEqual(last.naturalCompletion(ended, current: true, unpaused: true), .FINISH)
        last.closeViewing(ended, reason: .NATURAL_END)
        XCTAssertEqual(single.operations, ["SetIgnoreWait"])
        XCTAssertTrue(last.participating)
    }

    func testDefiniteRefusalClearsServerMembershipButUncertainFailureKeepsCleanup() {
        for (outcome, expected) in [(SyncPostOutcome.http(403), SyncServerMembership.none), (.http(503), .uncertain), (.failed, .uncertain), (.http(429), .uncertain)] {
            let world = SyncFakeWorld()
            let engine = world.engine()
            engine.socketOpened(connection: 1); engine.socketKeepAlive(connection: 1, valid: true)
            world.outcome = outcome
            engine.join(groupId: "g")
            XCTAssertEqual(engine.server, expected, "\(outcome)")
        }
    }

    // MARK: Live-check regressions (AVPlayer reports its state synchronously while a command is applied)

    /// Applies a playing Unpause at position 0 in a Playing group and returns the bound renderer.
    private func playingGroup(_ world: SyncFakeWorld) -> (SyncSessionEngine, SyncFakeRenderer) {
        let engine = joinedEngine(world, state: "Playing")
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis + 200, positionTicks: 0, emittedMillis: world.wallMillis))
        world.advance(200)
        XCTAssertEqual(player.applied.last?.playing, true)
        return (engine, player)
    }

    func testRendererCallbacksDuringAnUnpauseDoNotReportBuffering() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world, state: "Playing")
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        player.onApply = { _, _ in engine.rendererChanged() }
        world.posted.removeAll()
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis + 200, positionTicks: 0, emittedMillis: world.wallMillis))
        world.advance(200)
        XCTAssertEqual(player.applied.count, 1)
        XCTAssertFalse(world.operations.contains("Buffering"), "The applied command is no longer due when the player reports back")
    }

    func testACorrectionSeekIsNotABufferWaitButALongStallStillReports() {
        let world = SyncFakeWorld()
        let (engine, player) = playingGroup(world)
        world.posted.removeAll()
        // The player lags 400 ms behind the group's timeline: one seek correction.
        world.advance(400)
        player.onApply = { renderer, position in if position != nil { renderer.syncFacts.prepared = false; renderer.syncFacts.buffering = true } }
        engine.tick()
        XCTAssertEqual(engine.driftSeeks, 1)
        engine.rendererChanged()
        XCTAssertFalse(world.operations.contains("Buffering"), "The seek and its refill don't pause the group")
        player.syncFacts.prepared = true; player.syncFacts.buffering = false
        engine.rendererChanged()
        XCTAssertEqual(world.operations.filter { $0 == "Ready" }.count, 1, "The landing reports the new position once")
        // A second correction whose refill outlasts the grace reports the stall.
        world.posted.removeAll()
        world.advance(2_500)
        engine.tick()
        XCTAssertEqual(engine.driftSeeks, 2, "The frozen player is now 2.5 s behind")
        engine.rendererChanged()
        XCTAssertFalse(world.operations.contains("Buffering"))
        world.advance(SyncSessionEngine.correctionGraceMillis)
        XCTAssertEqual(world.operations.filter { $0 == "Buffering" }.count, 1)
    }

    func testACommandedSeekReportsOnlyItsLandingSoAWaitingServerDoesNotLoop() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation)
        engine.bind(player)
        engine.rendererChanged()
        world.posted.removeAll()
        player.onApply = { renderer, position in if position != nil { renderer.syncFacts.prepared = false } }
        engine.receive(SyncFakeWorld.update("StateUpdate", data: "{\"State\":\"Waiting\",\"Reason\":\"Seek\"}"))
        engine.receive(SyncFakeWorld.command("Pause", whenMillis: world.wallMillis, positionTicks: 600_000_000, emittedMillis: world.wallMillis))
        XCTAssertEqual(player.applied.last?.positionTicks, 600_000_000)
        engine.rendererChanged()
        XCTAssertFalse(world.operations.contains("Buffering"), "The engine's own seek isn't a buffer wait")
        player.syncFacts.prepared = true; player.syncFacts.positionTicks = 600_000_000
        engine.rendererChanged()
        XCTAssertEqual(world.operations, ["Ready"], "The landing reports Ready once")
    }

    func testAPauseAtThePositionAPausedPlayerHoldsNeitherSeeksNorReportsAgain() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation)
        player.syncFacts.positionTicks = 667_580_000
        engine.bind(player)
        engine.rendererChanged()
        world.posted.removeAll()
        player.onApply = { renderer, position in if position != nil { renderer.syncFacts.prepared = false } }
        for _ in 0..<3 { // A Paused server answers each Ready with a Pause at the group position.
            engine.receive(SyncFakeWorld.command("Pause", whenMillis: world.wallMillis, positionTicks: 667_580_000, emittedMillis: world.wallMillis))
            engine.rendererChanged()
        }
        XCTAssertTrue(player.applied.allSatisfy { $0.positionTicks == nil && !$0.playing }, "No seek to where the player already is")
        XCTAssertTrue(world.posted.isEmpty, "Readiness was already reported, so nothing repeats")
        engine.receive(SyncFakeWorld.command("Pause", whenMillis: world.wallMillis, positionTicks: 700_000_000, emittedMillis: world.wallMillis))
        XCTAssertEqual(player.applied.last?.positionTicks, 700_000_000, "A Pause elsewhere still seeks")
    }

    func testAFutureUnpauseForAPreparedAlignedPlayerNeverSeeksForTimerLateness() {
        let world = SyncFakeWorld()
        let engine = joinedEngine(world)
        let player = renderer(generation: engine.membership.generation)
        player.syncFacts.positionTicks = 100_000_000
        engine.bind(player)
        engine.receive(SyncFakeWorld.update("StateUpdate", data: "{\"State\":\"Playing\",\"Reason\":\"Ready\"}"))
        engine.receive(SyncFakeWorld.command("Unpause", whenMillis: world.wallMillis + 500, positionTicks: 100_000_000, emittedMillis: world.wallMillis))
        world.advance(650) // the timer fires 150 ms late
        XCTAssertEqual(player.applied.count, 1)
        XCTAssertNil(player.applied.first?.positionTicks, "Lateness alone doesn't seek a prepared source")
        XCTAssertEqual(player.applied.first?.playing, true)
    }

    func testCorrectionSeeksLearnToLeadByTheRenderersResumeLatency() {
        let world = SyncFakeWorld()
        let (engine, player) = playingGroup(world)
        world.advance(400)
        engine.tick()
        XCTAssertEqual(engine.driftSeeks, 1)
        XCTAssertEqual(engine.seekLeadTicks, 0, "Nothing learned before a correction lands")
        let firstTarget = player.applied.last?.positionTicks ?? 0
        // Playback resumes 300 ms late: the next tick after landing still measures that lag and learns half of it.
        world.advance(2_100)
        player.syncFacts.positionTicks = firstTarget + 18_000_000
        engine.tick()
        XCTAssertEqual(engine.seekLeadTicks, 1_500_000)
        XCTAssertEqual(engine.driftSeeks, 2)
        XCTAssertEqual(player.applied.last?.positionTicks, firstTarget + 21_000_000 + 1_500_000, "The second seek aims ahead by the learned lead")
    }
}
