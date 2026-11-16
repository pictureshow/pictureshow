// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// Executes the shared SyncPlay vectors against the Swift core. Files are read with the exact-lexeme
/// SyncJSON reader so 64-bit ticks and quoted wire numbers reach the parser as written.
/// Expected values are read only from spec/vectors; unknown operations and fields fail closed.
enum SyncVectorRunner {
    static let suites: Set<String> = Set([
        "sync-state", "sync-queue", "sync-clock", "sync-drift", "sync-presentation", "sync-departure", "sync-stay",
        "sync-local-display", "sync-chip-name", "sync-source", "sync-completion", "sync-player-intent-presentation"
    ] as [String]).union(hostSuites).union(adapterSuites)

    struct Failure: Error, CustomStringConvertible { let description: String }
    static func fail(_ message: String) -> Failure { Failure(description: message) }
    static func check(_ condition: Bool, _ message: @autoclosure () -> String) throws { if !condition { throw fail(message()) } }

    private static var cache: [String: SyncJSON] = [:]
    private static let lock = NSLock()

    static func document(_ suite: String) throws -> SyncJSON {
        guard suites.contains(suite) else { throw fail("unknown sync suite \(suite)") }
        lock.lock(); defer { lock.unlock() }
        if let cached = cache[suite] { return cached }
        let doc = try SyncJSON.parse(Data(contentsOf: RemoteVectorRunner.specRoot().appendingPathComponent("vectors/\(suite).json")))
        try check(doc["schemaVersion"]?.int64 == 1 || doc["schema"]?.int64 == 1, "\(suite) schema")
        try check(doc["suite"]?.content == suite, "\(suite) suite name")
        cache[suite] = doc
        return doc
    }

    /// Runs one case by ID; returns the number of assertions it made.
    static func run(suite: String, id: String) throws -> Int {
        let doc = try document(suite)
        guard let row = doc["cases"]?.items?.first(where: { $0["id"]?.content == id }) else { throw fail("unknown \(suite) case \(id)") }
        return try run(suite: suite, row: row)
    }

    static func run(suite: String, row c: SyncJSON) throws -> Int {
        let id = c["id"]?.content ?? "?"
        if hostSuites.contains(suite) { return try host(suite: suite, c, id) }
        if adapterSuites.contains(suite) { return try adapter(suite: suite, c, id) }
        switch suite {
        case "sync-state", "sync-queue": return try membershipSuite(c, id)
        case "sync-clock": return try clock(c, id)
        case "sync-drift": return try drift(c, id)
        case "sync-presentation": return try presentation(c, id)
        case "sync-departure": return try departure(c, id)
        case "sync-stay": return try stay(c, id)
        case "sync-local-display":
            try fields(c, ["id", "description", "localState", "queueEntries", "expectedVisible"])
            try check(SyncPresentation.showsLocalPlayback(queueEntries: try int(c, "queueEntries")) == (try bool(c, "expectedVisible")), id)
            return 1
        case "sync-chip-name":
            try fields(c, ["id", "description", "name", "graphemeEnds", "fittingCandidates", "expected"])
            let fits = Set(try strings(c, "fittingCandidates"))
            let ends = try (c["graphemeEnds"]?.items ?? []).map { v -> Int in guard let n = v.int32 else { throw fail("\(id) ends") }; return n }
            let actual = SyncPresentation.chipName(try text(c, "name"), graphemeEnds: ends, fits: fits.contains)
            try check(actual == (try optText(c, "expected")), "\(id): \(actual ?? "nil")")
            return 1
        case "sync-source":
            try fields(c, ["id", "description", "op", "input", "expected"])
            try check(try text(c, "op") == "sourceEligible", "\(id) op")
            let i = try obj(c, "input")
            try fields(i, ["durationTicks", "seekable", "live", "infinite", "offline"])
            try check(i["durationTicks"]?.isString == true, "\(id) ticks are decimal strings")
            let actual = SyncTiming.sourceEligible(durationTicks: try long(i, "durationTicks"), seekable: try strictBool(i, "seekable"), live: try strictBool(i, "live"), infinite: try strictBool(i, "infinite"), offline: try strictBool(i, "offline"))
            try check(actual == (try strictBool(c, "expected")), id)
            return 1
        case "sync-completion":
            try fields(c, ["id", "description", "operation", "input", "expected"])
            try check(try text(c, "operation") == "natural-completion", "\(id) op")
            let i = try obj(c, "input")
            try fields(i, ["queueCount", "currentIndex", "repeatMode", "hasSnapshot", "rendererMatches", "unpaused"])
            let queue = SyncQueue(entries: (0..<(try int(i, "queueCount"))).map { SyncQueueEntry(itemId: "same-media", playlistItemId: "entry-\($0)") }, currentIndex: try int(i, "currentIndex"), startPositionTicks: 0, isPlaying: true, repeatMode: try text(i, "repeatMode"), shuffleMode: "Sorted", lastUpdateTicks: 0)
            let actual = SyncCompletion.action(queue: queue, hasSnapshot: try strictBool(i, "hasSnapshot"), rendererMatches: try strictBool(i, "rendererMatches"), unpaused: try strictBool(i, "unpaused"))
            try check(actual.rawValue == (try text(c, "expected")), "\(id): \(actual)")
            return 1
        case "sync-player-intent-presentation":
            try fields(c, ["id", "description", "profile", "input", "expected"])
            let i = try obj(c, "input"), e = try obj(c, "expected")
            switch try text(c, "profile") {
            case "primary-transport":
                try fields(i, ["participating", "phase", "usable", "queueEntries", "localShowPlayButton"]); try fields(e, ["action"])
                let actual = SyncPresentation.playerPrimaryAction(participating: try bool(i, "participating"), usable: try bool(i, "usable"), queueEntries: try int(i, "queueEntries"), phase: try text(i, "phase"), localShowPlayButton: try bool(i, "localShowPlayButton"))
                try check(actual == (try optText(e, "action")), "\(id): \(actual ?? "nil")")
            case "speed-selection":
                try fields(i, ["participating"]); try fields(e, ["selectionAllowed", "explanationRequired"])
                let actual = SyncPresentation.playerSpeedSelection(participating: try bool(i, "participating"))
                try check(actual.selectionAllowed == (try bool(e, "selectionAllowed")) && actual.explanationRequired == (try bool(e, "explanationRequired")), id)
            default: throw fail("\(id): no executor for profile")
            }
            return 1
        default: throw fail("unknown sync suite \(suite)")
        }
    }

    // MARK: sync-state and sync-queue

    static func membershipSuite(_ c: SyncJSON, _ id: String) throws -> Int {
        let operation = try text(c, "operation")
        switch operation {
        case "readiness":
            try fields(c, ["id", "description", "operation", "phase", "currentEntryId", "stoppedEntryId", "expected"])
            try check(SyncReadiness.reports(phase: try text(c, "phase"), currentEntryId: try text(c, "currentEntryId"), stoppedEntryId: try optText(c, "stoppedEntryId")) == (try bool(c, "expected")), id)
            return 1
        case "renderer-readiness":
            try fields(c, ["id", "description", "operation", "pendingEntryId", "renderedEntryId", "currentEntryId", "expected"])
            try check(SyncReadiness.rendererOwnsEntry(pendingEntryId: try optText(c, "pendingEntryId"), renderedEntryId: try optText(c, "renderedEntryId"), currentEntryId: try text(c, "currentEntryId")) == (try bool(c, "expected")), id)
            return 1
        case "renderer-bind":
            try fields(c, ["id", "description", "operation", "openGeneration", "currentGeneration", "openEntryId", "currentEntryId", "openItemId", "currentItemId", "expected"])
            let actual = SyncReadiness.openMatches(openGeneration: try long(c, "openGeneration"), currentGeneration: try long(c, "currentGeneration"), openEntryId: try text(c, "openEntryId"), currentEntryId: try optText(c, "currentEntryId"), openItemId: try text(c, "openItemId"), currentItemId: try optText(c, "currentItemId"))
            try check(actual == (try bool(c, "expected")), id)
            return 1
        case "access-presentation":
            try fields(c, ["id", "description", "operation", "policy", "known", "busy", "failed", "expected"])
            let e = try obj(c, "expected")
            try check(Set(e.keys) == ["notice", "canJoin", "canCreate", "canRefresh"], "\(id) expected keys")
            let a = SyncAccessPresentation(policy: try text(c, "policy"), known: try bool(c, "known"), busy: try bool(c, "busy"), failed: try bool(c, "failed"))
            try check(a.notice.rawValue == (try text(e, "notice")) && a.canJoin == (try bool(e, "canJoin")) && a.canCreate == (try bool(e, "canCreate")) && a.canRefresh == (try bool(e, "canRefresh")), "\(id): \(a)")
            return 1
        case "readiness-cache":
            try fields(c, ["id", "description", "operation", "steps"])
            var state = SyncReadinessState(), count = 0
            for (index, step) in (c["steps"]?.items ?? []).enumerated() {
                switch try text(step, "op") {
                case "observe":
                    try fields(step, ["op", "entryId", "ready", "report"])
                    let observation = state.observe(entryId: try text(step, "entryId"), ready: try bool(step, "ready"))
                    try check(observation.report == (try bool(step, "report")), "\(id) step \(index)")
                    state = observation.state; count += 1
                case "position-applied":
                    try fields(step, ["op", "changed"]); state = state.afterPositionApplied(changed: try bool(step, "changed"))
                case "reset":
                    try fields(step, ["op"]); state = SyncReadinessState()
                default: throw fail("\(id): unknown readiness step")
                }
            }
            try check(count > 0, "\(id) has no observations")
            return count
        case "membership":
            try fields(c, ["id", "description", "operation", "state", "steps"])
            let initial = try obj(c, "state")
            try fields(initial, ["generation", "groupId", "phase"])
            var state = SyncMembership(generation: try long(initial, "generation"), groupId: try optText(initial, "groupId"), phase: try text(initial, "phase"))
            guard let steps = c["steps"]?.items, !steps.isEmpty else { throw fail("\(id) has no steps") }
            for (index, step) in steps.enumerated() {
                try fields(step, ["event", "expected"])
                state = try apply(state, try obj(step, "event"))
                try project(try obj(step, "expected"), state, "\(id)/\(index)")
            }
            return steps.count
        default: throw fail("\(id): unknown membership operation \(operation)")
        }
    }

    static func apply(_ state: SyncMembership, _ event: SyncJSON) throws -> SyncMembership {
        switch try text(event, "op") {
        case "join": try fields(event, ["op", "groupId"]); return state.beginningJoin(groupId: try optText(event, "groupId"))
        case "leave": try fields(event, ["op"]); return state.leaving()
        case "message":
            try fields(event, ["op", "envelope", "generation"])
            guard let envelope = event["envelope"] else { throw fail("missing envelope") }
            guard let parsed = SyncPlay.parseMessage(envelope.rendered) else { return state }
            let generation = event["generation"].map { $0.int64 } ?? state.generation
            guard let generation else { throw fail("bad generation") }
            return state.reduce(parsed, generation: generation)
        default: throw fail("unknown SyncPlay vector operation")
        }
    }

    static func project(_ expected: SyncJSON, _ s: SyncMembership, _ path: String) throws {
        try check(!expected.keys.isEmpty, "\(path) has no assertions")
        func nullable(_ v: String?) -> SyncJSON { v.map(SyncJSON.string) ?? .null }
        let actual: [String: SyncJSON] = [
            "generation": .literal(String(s.generation)),
            "groupId": nullable(s.groupId),
            "phase": .string(s.phase),
            "hasSnapshot": .literal(String(s.hasSnapshot)),
            "canSelectSource": .literal(String(s.canSelectSource)),
            "currentEntry": nullable(s.queue?.current?.playlistItemId),
            "entries": .array((s.queue?.entries ?? []).map { .string($0.playlistItemId) }),
            "items": .array((s.queue?.entries ?? []).map { .string($0.itemId) }),
            "participants": .array((s.group?.participants ?? []).map { .string($0) }),
            "command": nullable(s.command?.command),
            "commandTicks": nullable(s.command?.positionTicks.map { String($0) }),
            "startTicks": nullable(s.queue.map { String($0.startPositionTicks) }),
            "repeat": nullable(s.queue?.repeatMode),
            "shuffle": nullable(s.queue?.shuffleMode),
        ]
        for key in expected.keys {
            let value = expected[key]!
            if key == "noticeContains" {
                try check(s.notice?.contains(try text(expected, key)) == true, "\(path).\(key): \(s.notice ?? "nil")")
                continue
            }
            guard let mine = actual[key] else { throw fail("Unknown SyncPlay assertion: \(key)") }
            try check(mine == value, "\(path).\(key): expected \(value.rendered), actual \(mine.rendered)")
        }
    }

    // MARK: sync-clock and sync-drift

    static func clock(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "description", "op", "input", "expected"])
        guard let expected = c["expected"] else { throw fail("\(id) expected") }
        let input = try value(c, "input")
        func ok(_ condition: Bool) throws { try check(condition, id) }
        switch try text(c, "op") {
        case "clock-refresh-due":
            let anchor = try input["anchor"].flatMap { a -> SyncClockAnchor? in
                if a.isNull { return nil }
                return SyncClockAnchor(serverTicksAtAnchor: try long(a, "serverTicksAtAnchor"), localWallMillis: try long(a, "localWallMillis"), localMonotonicMillis: try long(a, "localMonotonicMillis"), uncertaintyTicks: try long(a, "uncertaintyTicks"))
            }
            try ok(SyncTiming.clockRefreshDue(anchor: anchor, lastAttemptFinishedMillis: try optLong(input, "lastAttemptFinishedMillis"), monotonicMillis: try long(input, "monotonicMillis")) == (try boolValue(expected)))
        case "prepared-future-unpause":
            try ok(SyncTiming.preparedUnpause(command: try text(input, "command"), acceptedFuture: try bool(input, "future"), ready: try bool(input, "ready"), buffering: try bool(input, "buffering"), unpaused: try bool(input, "unpaused"), positionApplicationPending: try bool(input, "pending"), currentTicks: try long(input, "current"), baseTicks: try long(input, "base")) == (try boolValue(expected)))
        case "unpause-replay-rate":
            let state = SyncDriftState(rateStartedMonotonicMillis: try optLong(input, "started"), rate: try dbl(input, "rate"))
            try ok(SyncTiming.unpauseReplayRate(state, sameAppliedTimeline: try bool(input, "same"), playing: try bool(input, "playing"), sourceAllowsRate: try bool(input, "allowsRate"), nowMonotonicMillis: try long(input, "now")) == expected.double)
        case "pcm-rate-capability":
            let known = input["knownPcm"].flatMap { $0.isNull ? nil : $0.bool }
            try ok(SyncTiming.pcmRateSupported(knownPcm: known, softwareRate: try bool(input, "softwareRate"), commandAvailable: try bool(input, "commandAvailable")) == (try boolValue(expected)))
        case "sample":
            guard let t = input.items?.compactMap(\.int64), t.count == 4 else { throw fail("\(id) sample input") }
            let m = SyncClockSample(t0Millis: t[0], t1Millis: t[1], t2Millis: t[2], t3Millis: t[3]).measurement()
            if expected.isNull { try ok(m == nil) }
            else {
                guard let m else { throw fail("\(id): no measurement") }
                try ok(m.networkRttMillis == (try long(expected, "rtt")) && m.offsetTicks == (try long(expected, "offsetTicks")) && m.uncertaintyTicks == (try long(expected, "uncertaintyTicks")))
            }
        case "target":
            guard let v = SyncTiming.target(positionTicks: try long(input, "position"), whenUtcMillis: try long(input, "when"), serverNowTicks: try long(input, "now"), playing: try bool(input, "playing"), durationTicks: try long(input, "duration")) else { throw fail("\(id): no target") }
            try ok(v.positionTicks == (try long(expected, "position")) && v.delayMillis == (try long(expected, "delay")))
        case "scheduled-position":
            try ok(SyncTiming.scheduledPosition(command: try text(input, "command"), currentTicks: try long(input, "current"), targetTicks: try long(input, "target")) == (expected.isNull ? nil : expected.int64))
        case "correction-position":
            let v = SyncTiming.correctionPosition(currentTicks: try long(input, "current"), targetTicks: try long(input, "target"), playing: try bool(input, "playing"), sampledAtMillis: try long(input, "sampledAt"), executionAtMillis: try long(input, "executionAt"), durationTicks: try long(input, "duration"), resolutionTicks: try long(input, "resolution"))
            try ok(v == (expected.isNull ? nil : expected.int64))
        case "same-unpause-timeline":
            func command(_ v: SyncJSON?) throws -> SyncCommand? {
                guard let v, !v.isNull else { return nil }
                return SyncCommand(groupId: try text(v, "group"), playlistItemId: try text(v, "entry"), command: try text(v, "command"), whenMs: try long(v, "when"), positionTicks: try optLong(v, "position"), emittedAtTicks: try long(v, "emitted"), whenTicks: v["whenTicks"].flatMap(\.int64))
            }
            guard let received = try command(input["received"]) else { throw fail("\(id) received") }
            try ok(SyncTiming.sameUnpauseTimeline(applied: try command(input["applied"]), received: received) == (try boolValue(expected)))
        case "seek-feedback":
            try ok(SeekSteps.preview(previousMs: try optLong(input, "previous"), targetMs: try long(input, "target"), showFeedback: try bool(input, "showFeedback")) == (expected.isNull ? nil : expected.int64))
        case "timeline-playing":
            try ok(SyncTiming.timelinePlaying(command: try text(input, "command"), appliedPlaying: try bool(input, "appliedPlaying")) == (try boolValue(expected)))
        case "scheduled-playing":
            try ok(SyncTiming.scheduledPlaying(intendedPlaying: try bool(input, "intended"), phase: try text(input, "phase")) == (try boolValue(expected)))
        default: throw fail("\(id): unknown clock operation")
        }
        return 1
    }

    static func drift(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "description", "input", "expected"])
        let i = try obj(c, "input"), e = try obj(c, "expected")
        try fields(i, ["driftMs", "playing", "now", "allowsRate", "rates", "lastSeek", "rateStarted", "oldRate", "appliedSeek", "appliedPositionChanged"])
        let initial = SyncDriftState(lastSeekMonotonicMillis: try optLong(i, "lastSeek"), rateStartedMonotonicMillis: try optLong(i, "rateStarted"), rate: i["oldRate"] == nil ? 1.0 : try dbl(i, "oldRate"))
        let state = try optLong(i, "appliedSeek").map { SyncTiming.transportApplied(initial, nowMonotonicMillis: $0, positionChanged: i["appliedPositionChanged"] == nil ? true : try bool(i, "appliedPositionChanged")) } ?? initial
        let rates = Set(try (i["rates"]?.items ?? []).map { v -> Double in guard let d = v.double else { throw fail("\(id) rate") }; return d })
        let result = SyncTiming.drift(state: state, currentTicks: 50_000_000 + (try long(i, "driftMs")) * 10_000, targetTicks: 50_000_000, playing: try bool(i, "playing"), nowMonotonicMillis: try long(i, "now"), durationTicks: 100_000_000, sourceAllowsRate: try bool(i, "allowsRate"), engineRates: rates)
        try check(result.action.rawValue == (try text(e, "action")) && result.rate == (try dbl(e, "rate")) && result.targetTicks == 50_000_000, "\(id): \(result.action) \(result.rate)")
        return 1
    }

    // MARK: presentation, departure and stay

    static func presentation(_ c: SyncJSON, _ id: String) throws -> Int {
        func scope(_ key: String) throws -> SyncTitleScope {
            guard let a = c[key]?.items, a.count == 3, let account = a[0].content, let m = a[1].int64, let r = a[2].int64 else { throw fail("\(id) scope") }
            return SyncTitleScope(account: account, membership: m, revision: r)
        }
        func quality(_ key: String) throws -> SyncQuality { guard let q = SyncQuality(rawValue: try text(c, key)) else { throw fail("\(id) quality") }; return q }
        switch try text(c, "op") {
        case "chip-quality":
            try fields(c, ["id", "description", "op", "quality", "expected"])
            try check(SyncPresentation.chipShowsQuality(try quality("quality")) == (try bool(c, "expected")), id)
        case "panel-quality":
            try fields(c, ["id", "description", "op", "quality", "recoveryOffered", "expected"])
            try check(SyncPresentation.panelQuality(try quality("quality"), recoveryOffered: try bool(c, "recoveryOffered")).rawValue == (try text(c, "expected")), id)
        case "quality":
            try fields(c, ["id", "description", "op", "usableClock", "ready", "applied", "errorTicks", "uncertaintyTicks", "expected"])
            let q = SyncPresentation.quality(usableClock: try bool(c, "usableClock"), ready: try bool(c, "ready"), applied: try bool(c, "applied"), errorTicks: try optLong(c, "errorTicks"), uncertaintyTicks: try optLong(c, "uncertaintyTicks"))
            try check(q.rawValue == (try text(c, "expected")), "\(id): \(q)")
        case "title":
            try fields(c, ["id", "description", "op", "request", "current", "entries", "asked", "expected"])
            try check(SyncPresentation.acceptTitle(request: try scope("request"), current: try scope("current"), entryIds: Set(try strings(c, "entries")), requestedEntries: Set(try strings(c, "asked"))) == (try bool(c, "expected")), id)
        case "phase":
            try fields(c, ["id", "description", "op", "phase", "reason", "expected"])
            try check(SyncPresentation.phase(try text(c, "phase"), reason: try optText(c, "reason")).rawValue == (try text(c, "expected")), id)
        case "transport":
            try fields(c, ["id", "description", "op", "usable", "queueEntries", "expected"])
            try check(SyncPresentation.transportAvailable(usable: try bool(c, "usable"), queueEntries: try int(c, "queueEntries")) == (try bool(c, "expected")), id)
        case "directional-transport":
            try fields(c, ["id", "description", "op", "usable", "queueEntries", "navigation", "expectedPrevious", "expectedNext"])
            let navigation = try c["navigation"].flatMap { a -> SyncNavigationAuthority? in
                if a.isNull { return nil }
                return SyncNavigationAuthority(repeatMode: try optText(a, "repeatMode"), entryIds: try strings(a, "entryIds"), currentEntryId: try optText(a, "currentEntryId"))
            }
            let usable = try bool(c, "usable"), entries = try int(c, "queueEntries")
            try check(SyncPresentation.directionAvailable(usable: usable, queueEntries: entries, previous: true, navigation: navigation) == (try bool(c, "expectedPrevious")), "\(id) previous")
            try check(SyncPresentation.directionAvailable(usable: usable, queueEntries: entries, previous: false, navigation: navigation) == (try bool(c, "expectedNext")), "\(id) next")
            return 2
        case "people":
            try fields(c, ["id", "description", "op", "names", "visible", "remaining"])
            let p = SyncPresentation.people(try strings(c, "names"))
            try check(p.names == (try strings(c, "visible")) && p.remaining == (try int(c, "remaining")), id)
        default: throw fail("\(id): unimplemented presentation vector")
        }
        return 1
    }

    static func departure(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "description", "initial", "steps"])
        func cause(_ v: SyncJSON) throws -> SyncDepartureCause { guard let cause = SyncDepartureCause(rawValue: try text(v, "cause")) else { throw fail("\(id) cause") }; return cause }
        func state(_ v: SyncJSON) throws -> SyncDepartureState {
            let pending = try v["pending"].flatMap { p -> SyncDeparture? in
                if p.isNull { return nil }
                return SyncDeparture(id: try long(p, "id"), groupName: try optText(p, "groupName"), cause: try cause(p))
            }
            return SyncDepartureState(scope: try long(v, "scope"), revision: try long(v, "revision"), pending: pending, presentedIn: try optLong(v, "presentedIn"))
        }
        var actual = try state(try obj(c, "initial"))
        guard let steps = c["steps"]?.items, !steps.isEmpty else { throw fail("\(id) steps") }
        for (index, step) in steps.enumerated() {
            let e = try obj(step, "event"), scope = try long(e, "scope")
            let event: SyncDepartureEvent
            switch try text(e, "kind") {
            case "ScopeChanged": event = .scopeChanged(scope: scope)
            case "MembershipStarted": event = .membershipStarted(scope: scope)
            case "Recorded": event = .recorded(scope: scope, name: try optText(e, "name"), cause: try cause(e))
            case "Presented": event = .presented(scope: scope, id: try long(e, "id"), chooserSession: try long(e, "chooserSession"))
            case "Dismissed": event = .dismissed(scope: scope, id: try long(e, "id"), chooserSession: try long(e, "chooserSession"))
            default: throw fail("\(id): unknown departure event")
            }
            actual = actual.reduce(event)
            let expected = try state(try obj(step, "expected"))
            try check(actual == expected, "\(id) step\(index): \(actual)")
        }
        return steps.count
    }

    static func stay(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "description", "op", "input", "expected"])
        let i = try obj(c, "input")
        guard let expected = c["expected"] else { throw fail("\(id) expected") }
        switch try text(c, "op") {
        case "close":
            guard let reason = SyncCloseReason(rawValue: try text(i, "reason")) else { throw fail("\(id) reason") }
            let d = SyncStay.closeDecision(reason, participating: try bool(i, "participating"), following: try bool(i, "following"))
            try check(d.outcome.rawValue == (try text(expected, "outcome")) && d.ignoreWait == (try bool(expected, "ignoreWait")) && d.departure?.rawValue == (try optText(expected, "departure")), "\(id): \(d)")
        case "reminder":
            let r = SyncStay.reminder(SyncStayFacts(groupId: try optText(i, "groupId"), groupName: try optText(i, "groupName"), phase: try text(i, "phase"), queueEntries: try int(i, "queueEntries"), playerOpen: try bool(i, "playerOpen"), connected: try bool(i, "connected"), leavePending: try bool(i, "leavePending")))
            if expected.isNull { try check(r == nil, "\(id): \(String(describing: r))") }
            else {
                guard let r else { throw fail("\(id): no reminder") }
                try check(r.groupName == (try optText(expected, "groupName")) && r.rejoin == (try bool(expected, "rejoin")) && r.leaveUnconfirmed == (try bool(expected, "leaveUnconfirmed")), "\(id): \(r)")
            }
        case "denial":
            try check(SyncStay.endsOnDenial(reason: try text(i, "reason"), playerOpen: try bool(i, "playerOpen")) == (try boolValue(expected)), id)
        case "open":
            try check(SyncStay.opensGroupEntry(following: try bool(i, "following")) == (try boolValue(expected)), id)
        default: throw fail("\(id): unknown stay op")
        }
        return 1
    }

    // MARK: strict readers

    static func fields(_ v: SyncJSON, _ allowed: Set<String>) throws {
        guard v.isObject else { throw fail("expected object") }
        let unknown = Set(v.keys).subtracting(allowed)
        try check(unknown.isEmpty, "Unknown SyncPlay vector fields: \(unknown.sorted())")
    }
    static func value(_ v: SyncJSON, _ key: String) throws -> SyncJSON { guard let x = v[key] else { throw fail("missing \(key)") }; return x }
    static func obj(_ v: SyncJSON, _ key: String) throws -> SyncJSON { let x = try value(v, key); guard x.isObject else { throw fail("\(key) is not an object") }; return x }
    static func text(_ v: SyncJSON, _ key: String) throws -> String { guard let s = try value(v, key).content else { throw fail("\(key) is not text") }; return s }
    static func optText(_ v: SyncJSON, _ key: String) throws -> String? { let x = try value(v, key); if x.isNull { return nil }; guard let s = x.content else { throw fail("\(key) is not text") }; return s }
    static func long(_ v: SyncJSON, _ key: String) throws -> Int64 { guard let n = try value(v, key).int64 else { throw fail("\(key) is not an integer") }; return n }
    static func optLong(_ v: SyncJSON, _ key: String) throws -> Int64? { guard let x = v[key], !x.isNull else { return nil }; guard let n = x.int64 else { throw fail("\(key) is not an integer") }; return n }
    static func int(_ v: SyncJSON, _ key: String) throws -> Int { guard let n = try value(v, key).int32 else { throw fail("\(key) is not an int") }; return n }
    static func dbl(_ v: SyncJSON, _ key: String) throws -> Double { guard let n = try value(v, key).double else { throw fail("\(key) is not a number") }; return n }
    static func bool(_ v: SyncJSON, _ key: String) throws -> Bool { try boolValue(try value(v, key)) }
    static func boolValue(_ v: SyncJSON) throws -> Bool { guard let b = v.bool else { throw fail("not a boolean: \(v.rendered)") }; return b }
    static func strictBool(_ v: SyncJSON, _ key: String) throws -> Bool { let x = try value(v, key); try check(!x.isString, "\(key) must be a JSON boolean"); return try boolValue(x) }
    static func strings(_ v: SyncJSON, _ key: String) throws -> [String] {
        guard let a = try value(v, key).items else { throw fail("\(key) is not an array") }
        return try a.map { guard let s = $0.content else { throw fail("\(key) entry") }; return s }
    }
}
