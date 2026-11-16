// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// Fakes that drive the real SyncSessionEngine: recorded POSTs with a configurable outcome, a virtual clock and a
/// virtual-time scheduler. Shared by the host vectors and SyncSessionEngineTests.
final class SyncFakeWorld: SyncEngineTransport, SyncEngineClock, SyncEngineScheduler, SyncEngineHost {
    final class Timer: SyncScheduled {
        let due: Int64, work: () -> Void
        var cancelled = false
        init(due: Int64, work: @escaping () -> Void) { self.due = due; self.work = work }
        func cancel() { cancelled = true }
    }

    var posted: [SyncRequest] = []
    var outcome: SyncPostOutcome = .ok
    /// GetUtcTime replies: nil means the server never answers with a usable time.
    var serverOffsetMillis: Int64?
    var wallMillis: Int64 = 1_800_000_000_000
    var monotonicMillis: Int64 = 1_000
    var timers: [Timer] = []
    var opens: [SyncOpenRequest] = []
    var changes = 0

    var operations: [String] { posted.map { String($0.path.dropFirst("SyncPlay/".count)) } }

    /// When set, answers wait in `held` until `release()`, as a slow server would.
    var holdAnswers = false
    var held: [() -> Void] = []

    func post(_ request: SyncRequest, completion: @escaping (SyncPostOutcome) -> Void) {
        posted.append(request)
        if holdAnswers { held.append { [unowned self] in completion(self.outcome) } } else { completion(outcome) }
    }

    func release() {
        let answers = held
        held.removeAll(); holdAnswers = false
        answers.forEach { $0() }
    }

    func engine(scope: Int64) -> SyncSessionEngine {
        let engine = SyncSessionEngine(transport: self, clock: self, scheduler: self, scope: scope)
        engine.host = self
        return engine
    }

    func utcTime(completion: @escaping ((receivedMillis: Int64, transmittedMillis: Int64)?) -> Void) {
        guard let offset = serverOffsetMillis else { completion(nil); return }
        let server = wallMillis + offset
        completion((server, server))
    }

    func after(_ millis: Int64, _ work: @escaping () -> Void) -> SyncScheduled {
        let timer = Timer(due: monotonicMillis + max(0, millis), work: work)
        timers.append(timer)
        return timer
    }

    /// Moves both clocks forward, firing due timers in order.
    func advance(_ millis: Int64) {
        let end = monotonicMillis + millis
        while let next = timers.filter({ !$0.cancelled && $0.due <= end }).min(by: { $0.due < $1.due }) {
            wallMillis += next.due - monotonicMillis
            monotonicMillis = next.due
            next.cancelled = true
            next.work()
        }
        wallMillis += end - monotonicMillis
        monotonicMillis = end
    }

    func syncOpen(_ request: SyncOpenRequest) { opens.append(request) }
    func syncChanged() { changes += 1 }

    func engine() -> SyncSessionEngine {
        let engine = SyncSessionEngine(transport: self, clock: self, scheduler: self)
        engine.host = self
        return engine
    }

    // Wire messages in the server's shapes.
    static func update(_ type: String, group: String = "g", data: String = "null", id: String? = nil) -> String {
        let message = id.map { ",\"MessageId\":\"\($0)\"" } ?? ""
        return "{\"MessageType\":\"SyncPlayGroupUpdate\"\(message),\"Data\":{\"GroupId\":\"\(group)\",\"Type\":\"\(type)\",\"Data\":\(data)}}"
    }

    static func joined(group: String = "g", name: String = "Evening", state: String = "Paused", at: String = "2026-11-10T12:00:00.0000000Z") -> String {
        update("GroupJoined", group: group, data: "{\"GroupId\":\"\(group)\",\"GroupName\":\"\(name)\",\"State\":\"\(state)\",\"Participants\":[\"a\"],\"LastUpdatedAt\":\"\(at)\"}")
    }

    static func queue(group: String = "g", entries: [(String, String)] = [("item", "entry-0")], index: Int = 0, start: Int64 = 0,
                      repeatMode: String = "RepeatNone", at: String = "2026-11-10T12:00:00.0000000Z") -> String {
        let rows = entries.map { "{\"ItemId\":\"\($0.0)\",\"PlaylistItemId\":\"\($0.1)\"}" }.joined(separator: ",")
        return update("PlayQueue", group: group, data: "{\"Reason\":\"NewPlaylist\",\"LastUpdate\":\"\(at)\",\"Playlist\":[\(rows)],\"PlayingItemIndex\":\(index),\"StartPositionTicks\":\(start),\"IsPlaying\":false,\"ShuffleMode\":\"Sorted\",\"RepeatMode\":\"\(repeatMode)\"}")
    }

    static func command(_ command: String, group: String = "g", entry: String = "entry-0", whenMillis: Int64, positionTicks: Int64? = 0, emittedMillis: Int64? = nil) -> String {
        let position = positionTicks.map { ",\"PositionTicks\":\($0)" } ?? ""
        return "{\"MessageType\":\"SyncPlayCommand\",\"Data\":{\"GroupId\":\"\(group)\",\"PlaylistItemId\":\"\(entry)\",\"When\":\"\(SyncPlay.utcTimestamp(whenMillis))\",\"EmittedAt\":\"\(SyncPlay.utcTimestamp(emittedMillis ?? whenMillis))\",\"Command\":\"\(command)\"\(position)}}"
    }
}

extension SyncVectorRunner {
    static let hostSuites: Set<String> = ["sync-socket-readiness", "sync-membership-registration", "sync-application"]

    static func host(suite: String, _ c: SyncJSON, _ id: String) throws -> Int {
        switch suite {
        case "sync-socket-readiness": return try socketReadiness(c, id)
        case "sync-membership-registration": return try membershipRegistration(c, id)
        case "sync-application": return try application(c, id)
        default: throw fail("unknown host suite \(suite)")
        }
    }

    /// The registration boundary the engine waits on (SyncSocketRegistration, fed by ServerSocket).
    static func socketReadiness(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "steps"])
        var registration = SyncSocketRegistration()
        var connection: Int64 = 0
        var assertions = 0
        for step in c["steps"]?.items ?? [] {
            try fields(step, ["action", "ready"])
            switch try text(step, "action") {
            case "open": connection += 1; registration.opened(connection)
            case "ready": registration.keepAlive(connection, valid: true)
            case "invalid": registration.keepAlive(connection, valid: false)
            case "close": registration.closed(connection)
            case "renew": connection += 1; registration.opened(connection)
            case "old-ready": registration.keepAlive(connection - 1, valid: true)
            default: throw fail("\(id): unknown socket action")
            }
            try check(registration.registered == (try strictBool(step, "ready")), "\(id) step \(assertions)")
            assertions += 1
        }
        try check(assertions > 0, "\(id): no steps")
        return assertions
    }

    /// A Create/Join waits up to the bounded registration window on the current socket; cancelling an unsent intent posts
    /// nothing unless an earlier membership may still exist on the server.
    static func membershipRegistration(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "intent", "terminal", "posted", "phase", "priorMembership", "priorLeft", "priorHttp"])
        let world = SyncFakeWorld()
        let engine = world.engine()
        engine.socketOpened(connection: 1)
        engine.socketKeepAlive(connection: 1, valid: true)
        if c["priorMembership"] != nil, try strictBool(c, "priorMembership") {
            let status = c["priorHttp"] == nil ? 204 : try int(c, "priorHttp")
            world.outcome = status == 204 ? .ok : .http(status)
            engine.join(groupId: "g")
            if status == 204 {
                engine.receive(SyncFakeWorld.joined())
                engine.receive(SyncFakeWorld.queue())
                try check(engine.server == .confirmed, "\(id): prior membership confirmed")
            }
            world.outcome = .ok
            if c["priorLeft"] != nil, try strictBool(c, "priorLeft") { engine.receive(SyncFakeWorld.update("GroupLeft")) }
            world.posted.removeAll()
        }
        // The membership socket rotates: the new connection is open but not yet registered.
        engine.socketClosed(connection: 1)
        engine.socketOpened(connection: 2)
        if try text(c, "intent") == "create" { engine.create(name: "Evening") } else { engine.join(groupId: "g") }
        try check(engine.membership.phase == "Joining", "\(id): joining")
        try check(world.posted.isEmpty, "\(id): nothing before registration")
        world.advance(9_999)
        try check(world.posted.isEmpty, "\(id): nothing inside the window")
        switch try text(c, "terminal") {
        case "ready": engine.socketKeepAlive(connection: 2, valid: true)
        case "leave": engine.leave(); engine.socketKeepAlive(connection: 2, valid: true); world.advance(10_001)
        case "route":
            engine.leaveForExternalRoute(); engine.socketKeepAlive(connection: 2, valid: true); world.advance(10_001)
            try check(engine.departure == nil || engine.departure == .EXTERNAL_ROUTE, "\(id): route departure")
        case "timeout": world.advance(2)
        default: throw fail("\(id): unknown terminal")
        }
        let expected = try strings(c, "posted")
        try check(world.operations == expected, "\(id): posted \(world.operations)")
        try check(engine.membership.phase == (try text(c, "phase")), "\(id): phase \(engine.membership.phase)")
        return 4
    }

    static func application(_ c: SyncJSON, _ id: String) throws -> Int {
        switch try text(c, "operation") {
        case "deferred-seek":
            try fields(c, ["id", "operation", "source", "targetMs", "expectedNativeSeeks", "expectedGroupSeekAfterCompletion", "nextViewerSeekMs"])
            let origin: SyncControlOrigin
            switch try text(c, "source") { case "server": origin = .server; case "viewer": origin = .viewer; default: throw fail("\(id) source") }
            let target = try long(c, "targetMs")
            var gate = SyncSeekGate()
            var native: [Int64] = [], group: [Int64] = []
            func apply(_ effect: SyncSeekEffect?) {
                switch effect { case let .native(ms)?: native.append(ms); case let .group(ms)?: group.append(ms); case nil: break }
            }
            apply(gate.seek(millis: target, origin: origin, grouped: true, ready: false))
            try check(gate.pendingMillis == target && native.isEmpty && group.isEmpty, "\(id): deferred")
            apply(gate.becameReady(grouped: true))
            let expectedNative = try (c["expectedNativeSeeks"]?.items ?? []).map { v -> Int64 in guard let n = v.int64 else { throw fail("\(id) seeks") }; return n }
            try check(native == expectedNative, "\(id): native \(native)")
            let groupAfter = try strictBool(c, "expectedGroupSeekAfterCompletion")
            try check(group == (groupAfter ? [target] : []), "\(id): group \(group)")
            try check(gate.pendingMillis == nil, "\(id): cleared")
            let next = try long(c, "nextViewerSeekMs")
            apply(gate.seek(millis: next, origin: .viewer, grouped: true, ready: true))
            try check(group == (groupAfter ? [target, next] : [next]) && native == expectedNative, "\(id): next viewer seek")
            return 4
        case "readiness-unpaused":
            try fields(c, ["id", "operation", "playWhenReady", "buffering", "suppressed", "expectedRendering", "expectedIsPlaying"])
            let facts = SyncRendererFacts(entryId: "e", generation: 1, prepared: true, buffering: try strictBool(c, "buffering"),
                                          unpaused: try strictBool(c, "playWhenReady"), suppressed: try strictBool(c, "suppressed"),
                                          positionTicks: 12_340_000, durationTicks: 100_000_000, eligible: true)
            try check(facts.rendering == (try strictBool(c, "expectedRendering")), "\(id): rendering")
            try check(facts.unpaused == (try strictBool(c, "expectedIsPlaying")), "\(id): IsPlaying")
            // The engine's readiness report carries the native play intent, not advancing output.
            let world = SyncFakeWorld()
            world.serverOffsetMillis = 0
            let engine = world.engine()
            engine.socketOpened(connection: 1); engine.socketKeepAlive(connection: 1, valid: true)
            engine.join(groupId: "g")
            engine.receive(SyncFakeWorld.joined(state: "Waiting"))
            engine.receive(SyncFakeWorld.queue(entries: [("item", "e")]))
            let renderer = SyncFakeRenderer(facts)
            engine.bind(renderer)
            guard let report = world.posted.last(where: { $0.path == "SyncPlay/Ready" || $0.path == "SyncPlay/Buffering" }),
                  let body = report.body else { throw fail("\(id): no readiness report") }
            try check(body["IsPlaying"]?.bool == (try strictBool(c, "expectedIsPlaying")), "\(id): reported IsPlaying \(body.rendered)")
            try check(report.path == (facts.buffering ? "SyncPlay/Buffering" : "SyncPlay/Ready"), "\(id): report kind \(report.path)")
            return 4
        case "pause-feedback":
            try fields(c, ["id", "operation", "participating", "origin", "expectedReveal"])
            guard let origin = SyncControlOrigin(rawValue: try text(c, "origin")) else { throw fail("\(id) origin") }
            let actual = SyncPauseFeedback.reveals(participating: try strictBool(c, "participating"), origin: origin)
            try check(actual == (try strictBool(c, "expectedReveal")), "\(id): reveal \(actual)")
            return 1
        default: throw fail("\(id): no executor for operation")
        }
    }
}

final class SyncFakeRenderer: SyncRenderer {
    var syncFacts: SyncRendererFacts
    var applied: [(positionTicks: Int64?, playing: Bool)] = []
    /// Runs after each application, as a native player's synchronous state callbacks would.
    var onApply: ((SyncFakeRenderer, Int64?) -> Void)?
    init(_ facts: SyncRendererFacts) { syncFacts = facts }
    func syncApply(positionTicks: Int64?, playing: Bool) {
        applied.append((positionTicks, playing))
        if let positionTicks { syncFacts.positionTicks = positionTicks }
        syncFacts.unpaused = playing
        onApply?(self, positionTicks)
    }
}
