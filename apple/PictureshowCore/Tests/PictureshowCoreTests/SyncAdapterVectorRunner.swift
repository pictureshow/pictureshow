// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// SyncPlay adapter suites that run through the real SyncSessionEngine and the presentation policies the app's
/// Watch together surfaces and player read. Cases that measure native layout, paint or focus stay pending (see
/// PendingSpecCaseManifest.reasons); `partialSuites` names the suites where only some cases execute here.
extension SyncVectorRunner {
    static let adapterSuites: Set<String> = [
        "sync-completion-reporting", "sync-route-consent", "sync-group-social-summary-and-picker-role", "sync-tv-player-coherence"
    ]
    /// Suites with cases that stay pending on Apple; each has a written reason.
    static let partialSuites: Set<String> = ["sync-group-social-summary-and-picker-role", "sync-tv-player-coherence"]

    static func adapter(suite: String, _ c: SyncJSON, _ id: String) throws -> Int {
        switch suite {
        case "sync-completion-reporting": return try completionReporting(c, id)
        case "sync-route-consent": return try routeConsent(c, id)
        case "sync-group-social-summary-and-picker-role": return try socialAndPicker(c, id)
        case "sync-tv-player-coherence": return try roster(c, id)
        default: throw fail("unknown adapter suite \(suite)")
        }
    }

    /// A joined engine on a registered socket with the group's snapshot: `participants` in GroupJoined, then the queue.
    static func groupEngine(_ world: SyncFakeWorld, scope: Int64 = 0, state: String = "Paused", participants: [String] = ["a"],
                            entries: [(String, String)] = [("item", "entry-0")], index: Int = 0, repeatMode: String = "RepeatNone") -> SyncSessionEngine {
        world.serverOffsetMillis = 0
        let engine = world.engine(scope: scope)
        engine.socketOpened(connection: 1)
        engine.socketKeepAlive(connection: 1, valid: true)
        engine.join(groupId: "g")
        let names = participants.map { "\"\($0)\"" }.joined(separator: ",")
        engine.receive(SyncFakeWorld.update("GroupJoined", data: "{\"GroupId\":\"g\",\"GroupName\":\"Friday films\",\"State\":\"\(state)\",\"Participants\":[\(names)],\"LastUpdatedAt\":\"2026-11-10T12:00:00.0000000Z\"}"))
        engine.receive(SyncFakeWorld.queue(entries: entries, index: index, repeatMode: repeatMode))
        return engine
    }

    static let readiness: Set<String> = ["Ping", "Ready", "Buffering"]

    /// SYN-09: the ended decision's currency gates every action; a terminal Repeat Off entry stays in the group, reports
    /// Stopped then Played and closes once; earlier entries and Repeat One ask the server and do nothing locally.
    static func completionReporting(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "operation", "currentDecision", "expectedMembership", "expectedWire", "expectedReports", "expectedExits",
                       "expectedLocalNext", "queueCount", "currentIndex", "repeatMode"])
        try check(try text(c, "operation") == "video-completion", "\(id) operation")
        let count = try int(c, "queueCount"), index = try int(c, "currentIndex")
        let world = SyncFakeWorld()
        let engine = groupEngine(world, state: "Playing", entries: (0..<count).map { ("same-media", "entry-\($0)") }, index: index, repeatMode: try text(c, "repeatMode"))
        let ended = SyncFakeRenderer(SyncRendererFacts(entryId: "entry-\(index)", generation: engine.membership.generation, prepared: true, buffering: false,
                                                       unpaused: true, positionTicks: 36_000_000_000, durationTicks: 36_000_000_000, eligible: true))
        engine.bind(ended)
        world.posted.removeAll()
        let queueBefore = engine.membership.queue
        let plan = SyncVideoCompletion.plan(engine, ended: ended, decisionCurrent: try strictBool(c, "currentDecision"), unpaused: true)
        // PlaybackController.close(reason: .NATURAL_END) hands the closing renderer to the engine before its reports.
        var exits = 0
        if plan.exits { engine.closeViewing(ended, reason: .NATURAL_END); exits += 1 }
        let reports = plan.reports.map { $0 == .stopped ? "/Sessions/Playing/Stopped" : "catalog:played" }
        let wire = world.operations.filter { !readiness.contains($0) }
        try check(wire == (try strings(c, "expectedWire")), "\(id): wire \(wire)")
        try check(reports == (try strings(c, "expectedReports")), "\(id): reports \(reports)")
        try check(exits == (try int(c, "expectedExits")), "\(id): exits \(exits)")
        // A grouped completion never consults the local next-episode lookup: the plan has no local continuation.
        try check((try strings(c, "expectedLocalNext")).isEmpty, "\(id): local next")
        try check(engine.participating == (try strictBool(c, "expectedMembership")), "\(id): membership")
        try check(engine.departure == nil, "\(id): a stay records no departure")
        try check(engine.membership.queue == queueBefore, "\(id): only the server changes the queue")
        return 6
    }

    /// SYN-10 consent: bound to the account/token scope (a rebind builds a new engine, as SyncPlaySession does) and to
    /// its own identity; the route starts only after an accepted Leave.
    static func routeConsent(_ c: SyncJSON, _ id: String) throws -> Int {
        let world = SyncFakeWorld()
        var engine = groupEngine(world, scope: 1)
        world.posted.removeAll()
        var oldRoutes = 0, newRoutes = 0
        try check(engine.confirmExternalRoute { oldRoutes += 1 }, "\(id): consent needed while grouped")
        guard let old = engine.externalRouteConsent else { throw fail("\(id): no consent") }
        if c["rebind"] != nil {
            try fields(c, ["id", "rebind", "expectedPending", "expectedRoutes"])
            let next: (String?, String?)
            switch try text(c, "rebind") {
            case "account": next = ("server|other", "token-other")
            case "token": next = ("server|viewer", "rotated")
            case "logout": next = (nil, nil)
            case "same": next = ("server|viewer", "token")
            default: throw fail("\(id): rebind")
            }
            if SyncAccountScope.rebinds(currentKey: "server|viewer", currentToken: "token", nextKey: next.0, nextToken: next.1) {
                engine.terminate()
                try check(engine.externalRouteConsent == nil, "\(id): terminated engine keeps no consent")
                engine = world.engine(scope: 2)
            }
            try check((engine.pendingExternalRoute != nil) == (try strictBool(c, "expectedPending")), "\(id): pending")
            engine.leaveForExternalRoute()
            try check(oldRoutes == (try int(c, "expectedRoutes")), "\(id): routes \(oldRoutes)")
            engine.leaveForExternalRoute()
            try check(oldRoutes == (try int(c, "expectedRoutes")), "\(id): no repeated route")
            return 3
        }
        try fields(c, ["id", "expectedPendingAfterObsolete", "expectedRoutesAfterObsolete", "expectedFinalRoutes", "expectedLeaveRequests"])
        var assertions = 0
        switch id {
        case "duplicate":
            engine.leaveForExternalRoute(scope: old.scope, id: old.id)
            engine.leaveForExternalRoute(scope: old.scope, id: old.id)
            try check((engine.pendingExternalRoute != nil) == (try strictBool(c, "expectedPendingAfterObsolete")), "\(id): pending")
            try check(oldRoutes == (try int(c, "expectedRoutesAfterObsolete")), "\(id): routes after repeat")
            assertions += 2
        case "scope-change-during-leave":
            world.holdAnswers = true
            engine.leaveForExternalRoute(scope: old.scope, id: old.id)
            try check(oldRoutes == 0, "\(id): no route before the Leave is answered")
            // The token rotates while the Leave is on the wire: SyncPlaySession ends this engine and builds the next.
            try check(SyncAccountScope.rebinds(currentKey: "server|viewer", currentToken: "token", nextKey: "server|viewer", nextToken: "rotated"), "\(id): rebind")
            engine.terminate()
            let replacement = world.engine(scope: 2)
            world.release()
            try check((engine.pendingExternalRoute != nil || replacement.pendingExternalRoute != nil) == (try strictBool(c, "expectedPendingAfterObsolete")), "\(id): pending")
            try check(oldRoutes == (try int(c, "expectedRoutesAfterObsolete")), "\(id): routes after scope change")
            assertions += 3
        default:
            try check(engine.confirmExternalRoute { newRoutes += 1 }, "\(id): fresh consent")
            guard let fresh = engine.externalRouteConsent, fresh.id > old.id else { throw fail("\(id): fresh identity") }
            switch id {
            case "stale-confirm": engine.leaveForExternalRoute(scope: old.scope, id: old.id)
            case "stale-cancel": engine.cancelExternalRoute(scope: old.scope, id: old.id)
            case "wrong-scope-confirm": engine.leaveForExternalRoute(scope: fresh.scope - 1, id: fresh.id)
            case "wrong-scope-cancel": engine.cancelExternalRoute(scope: fresh.scope - 1, id: fresh.id)
            default: throw fail("\(id): unknown consent identity case")
            }
            try check((engine.pendingExternalRoute != nil) == (try strictBool(c, "expectedPendingAfterObsolete")), "\(id): obsolete callback keeps the fresh consent")
            try check(oldRoutes + newRoutes == (try int(c, "expectedRoutesAfterObsolete")), "\(id): routes after obsolete")
            try check(engine.participating && world.posted.isEmpty, "\(id): an obsolete callback sends nothing and doesn't leave")
            engine.leaveForExternalRoute(scope: fresh.scope, id: fresh.id)
            try check(oldRoutes == 0, "\(id): the replaced consent's route never runs")
            assertions += 4
        }
        try check(oldRoutes + newRoutes == (try int(c, "expectedFinalRoutes")), "\(id): final routes \(oldRoutes + newRoutes)")
        try check(world.operations.filter { $0 == "Leave" }.count == (try int(c, "expectedLeaveRequests")), "\(id): Leave requests \(world.operations)")
        try check(engine.pendingExternalRoute == nil && engine.externalRouteConsent == nil, "\(id): consent consumed")
        return assertions + 3
    }

    /// Group count text and the TV chooser's role; Previous/Next from the authoritative queue snapshot. Phone layout and
    /// TV baseline/focus cases are pending.
    static func socialAndPicker(_ c: SyncJSON, _ id: String) throws -> Int {
        let input = try obj(c, "input"), expected = try obj(c, "expected")
        if c["profile"]?.content == "authoritative-directional-transport" {
            try fields(c, ["id", "profile", "input", "expected"])
            try fields(input, ["snapshot"])
            try fields(expected, ["previousEnabled", "nextEnabled", "playEnabled", "membershipRetained"])
            let snapshot = try obj(input, "snapshot")
            let world = SyncFakeWorld()
            world.serverOffsetMillis = 0
            let engine = world.engine()
            engine.socketOpened(connection: 1); engine.socketKeepAlive(connection: 1, valid: true)
            engine.join(groupId: "g")
            engine.receive(SyncFakeWorld.joined(state: "Paused"))
            // The snapshot reaches the engine as the server's PlayQueue update, parsed by the production reader.
            engine.receive(SyncFakeWorld.update("PlayQueue", data: "{\"Reason\":\"NewPlaylist\"," + snapshot.rendered.dropFirst()))
            guard let queue = engine.membership.queue, queue.current != nil else { throw fail("\(id): snapshot not accepted") }
            world.posted.removeAll()
            let surface = SyncGroupTransport(engine)
            try check(surface.previousEnabled == (try strictBool(expected, "previousEnabled")), "\(id): previous")
            try check(surface.nextEnabled == (try strictBool(expected, "nextEnabled")), "\(id): next")
            try check(surface.playEnabled == (try strictBool(expected, "playEnabled")), "\(id): play")
            // Every press reaches the engine; only an available direction becomes a server request.
            engine.requestPrevious(); engine.requestNext()
            let sent = world.operations.filter { !readiness.contains($0) }
            let wanted = (surface.previousEnabled ? ["PreviousItem"] : []) + (surface.nextEnabled ? ["NextItem"] : [])
            try check(sent == wanted, "\(id): requests \(sent)")
            try check(engine.membership.queue == queue, "\(id): no optimistic queue change")
            try check(engine.participating == (try strictBool(expected, "membershipRetained")), "\(id): membership")
            return 6
        }
        try fields(c, ["id", "input", "expected"])
        try fields(input, ["request", "mediaRequestPresent"])
        let presentation = RemotePickerPresentation(mediaRequestPresent: try strictBool(input, "mediaRequestPresent"))
        try check(presentation.title == (try text(expected, "dialogTitle")), "\(id): title \(presentation.title)")
        try check(presentation.titleIsHeading == (try strictBool(expected, "titleIsHeading")), "\(id): heading")
        if expected["thisDeviceOption"] != nil {
            try fields(expected, ["dialogTitle", "titleIsHeading", "thisDeviceOption"])
            try check(presentation.offersThisDevice == (try strictBool(expected, "thisDeviceOption")), "\(id): this device")
        } else { try fields(expected, ["dialogTitle", "titleIsHeading"]) }
        try check(try text(input, "request") == (presentation.mediaRequestPresent ? "play-media" : "remote-control"), "\(id): role")
        return 3
    }

    /// The group's count follows the server's participant list through joins and departures and never sends anything.
    static func roster(_ c: SyncJSON, _ id: String) throws -> Int {
        try fields(c, ["id", "profile", "input", "expected"])
        try check(try text(c, "profile") == "native-tv-roster", "\(id): profile")
        let input = try obj(c, "input"), expected = try obj(c, "expected")
        // Window, density and font are the Android capture's; the count text is the same at any size.
        try fields(input, ["windowWidthPx", "windowHeightPx", "densityDpi", "fontScale", "phase", "participantSnapshots"])
        try fields(expected, ["countTexts", "groupCommands"])
        let snapshots = try (input["participantSnapshots"]?.items ?? []).map { row -> [String] in try (row.items ?? []).map { guard let name = $0.content else { throw fail("\(id): name") }; return name } }
        let world = SyncFakeWorld()
        let engine = groupEngine(world, state: try text(input, "phase"), participants: snapshots.first ?? [])
        world.posted.removeAll()
        var texts: [String] = []
        var previous: [String] = snapshots.first ?? []
        for (index, names) in snapshots.enumerated() {
            if index > 0 {
                for gone in previous where !names.contains(gone) { engine.receive(SyncFakeWorld.update("UserLeft", data: "\"\(gone)\"")) }
                for added in names where !previous.contains(added) { engine.receive(SyncFakeWorld.update("UserJoined", data: "\"\(added)\"")) }
            }
            try check((engine.membership.group?.participants ?? []) == names, "\(id): roster \(index)")
            texts.append(SyncPresentation.watchingCount(engine.membership.group?.participants.count ?? 0))
            previous = names
        }
        try check(texts == (try strings(expected, "countTexts")), "\(id): \(texts)")
        let commands = world.operations.filter { !readiness.contains($0) }.count
        try check(commands == (try int(expected, "groupCommands")), "\(id): commands \(commands)")
        return 3
    }
}
