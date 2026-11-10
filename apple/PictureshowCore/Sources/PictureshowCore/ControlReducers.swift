// SPDX-License-Identifier: MPL-2.0
import Foundation
import CoreFoundation

/// Strict, deterministic trace adapter for the shared session and queue reducers.
public enum ControlReducers {
    private static let identityKeys: Set<String> = ["server", "viewer", "device", "session", "generation", "kind"]
    private static let intentKeys: Set<String> = ["entry", "ticks", "paused", "queue", "repeat", "shuffle", "audio", "subtitle"]

    public static func reduce(suite: String, operation: String, state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        switch (suite, operation) {
        case ("session-owner", "owner"): return try owner(state: state, event: event)
        case ("session-owner", "temporary"): return try temporary(state: state, event: event)
        case ("session-command", "command"): return try command(state: state, event: event)
        case ("playback-mode", "mode"): return try mode(state: state, event: event)
        case ("queue-edit", "queue"), ("queue-edit", "store"), ("queue-order", "queue"), ("queue-order", "transfer"):
            return try queueReducer(suite: suite, operation: operation, state: state, event: event)
        default: throw failure("unknown suite/operation \(suite)/\(operation)")
        }
    }

    private static func owner(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try fields(input, allowed: ["owner", "local", "candidate", "phase", "intent", "route", "generation", "reportSequence", "effect", "reason"], at: "session-owner.state")
        var current = try identity(input["owner"], at: "session-owner.owner")
        var local = try input["local"].map { try identity($0, at: "session-owner.local") } ?? current
        var candidate = try input["candidate"].flatMap { isNull($0) ? nil : try identity($0, at: "session-owner.candidate") }
        var phase = try input["phase"].map { try strictString($0, at: "owner.phase") } ?? "active"
        var intent = try normalizedIntent(input["intent"])
        var route = try input["route"].map { try strictString($0, at: "owner.route") } ?? "native"
        var generation = try int64(input["generation"] ?? current["generation"], at: "owner.generation")
        var sequence = try input["reportSequence"].map { try int64($0, at: "owner.reportSequence") } ?? -1
        var effect = "none", reason: String?
        let op = try eventString(event, "op")
        try fields(event, allowed: ["op", "identity", "intent", "sequence", "ticks", "confirmed"], required: ["op"], at: "owner.event")
        let target = try event["identity"].flatMap { isNull($0) ? nil : try identity($0, at: "owner.identity") }
        let requestedIntent = try event["intent"].flatMap { isNull($0) ? nil : try normalizedIntent($0) }
        let report = try event["sequence"].map { try int64($0, at: "owner.sequence") } ?? 0
        let ticks = try event["ticks"].flatMap { isNull($0) ? nil : try int64($0, at: "owner.ticks") }
        let confirmed = try event["confirmed"].map { try bool($0, at: "owner.confirmed") } ?? false
        func same(_ a:[String:Any]?,_ b:[String:Any]?) -> Bool { guard let a, let b else { return a == nil && b == nil }; return sameIdentity(a,b) }
        func valid(_ d:[String:Any]) -> Bool {
            ["server","viewer","device","session"].allSatisfy { !(d[$0] as! String).trimmingCharacters(in:.whitespacesAndNewlines).isEmpty }
                && ["local","jellyfin","cast"].contains(d["kind"] as! String) && (try? int64(d["generation"],at:"generation")).map { $0 >= 0 } == true
        }
        switch op {
        case "replace", "viewer-switch":
            if let next = target {
                let n = try int64(next["generation"],at:"identity.generation")
                if !valid(next) || n <= generation { reason = "stale-generation" }
                else { current = next; local = next; candidate = nil; phase = "active"; intent = try requestedIntent ?? normalizedIntent(nil); generation = n; sequence = -1; route = "native" }
            } else { reason = "invalid-owner" }
        case "report":
            if !same(target,current) || report <= sequence || !["active","selecting","connecting","preparing","awaiting-ready"].contains(phase) { reason = "stale-report" }
            else { sequence = report; effect = "report" }
        case "airplay", "native-route":
            if !same(target,current) || current["kind"] as? String != "local" || phase != "active" { reason = "stale-owner" }
            else { route = op == "airplay" ? "airplay":"native" }
        case "select":
            if let next = target {
                let n = try int64(next["generation"],at:"identity.generation")
                if !["active","failed","disconnected"].contains(phase) || !valid(next) || next["kind"] as? String == "local" || next["server"] as? String != current["server"] as? String || next["viewer"] as? String != current["viewer"] as? String || n <= generation { reason = "invalid-target" }
                else { candidate = next; phase = "selecting"; generation = n; intent = requestedIntent ?? intent }
            } else { reason = "invalid-owner" }
        case "connect": if !same(target,candidate) || phase != "selecting" { reason = "stale-target" } else { phase = "connecting" }
        case "prepare": if !same(target,candidate) || phase != "connecting" { reason = "stale-target" } else { phase = "preparing"; effect = "pause-local" }
        case "local-paused": if !same(target,candidate) || phase != "preparing" { reason = "stale-target" } else { phase = "awaiting-ready"; effect = "request-remote" }
        case "ready":
            let intendedTicks = try int64(intent["ticks"],at:"intent.ticks")
            if !same(target,candidate) || phase != "awaiting-ready" || ticks != intendedTicks { reason = "not-ready" }
            else { current = candidate!; candidate = nil; phase = "active"; sequence = -1 }
        case "fail", "timeout", "cancel":
            if !same(target,candidate) || candidate == nil { reason = "stale-target" }
            else { candidate = nil; phase = "failed"; intent["paused"] = true; effect = "resume-here" }
        case "disconnect": if !same(target,current) { reason = "stale-owner" } else { phase = "disconnected"; effect = "refresh" }
        case "return": if !same(target,current) || current["kind"] as? String == "local" { reason = "stale-owner" } else { phase = "returning"; effect = "stop-remote" }
        case "remote-stopped":
            if !same(target,current) || phase != "returning" { reason = "stale-owner" }
            else if confirmed { phase = "return-ready"; effect = "resume-here" }
            else { phase = "return-uncertain"; effect = "require-choice"; reason = "remote-may-continue" }
        case "resume-here":
            if !["return-ready","return-uncertain","failed"].contains(phase) || (phase == "return-uncertain" && !confirmed) { reason = "explicit-choice-required" }
            else if let next = target {
                let n = try int64(next["generation"],at:"identity.generation")
                if !valid(next) || next["kind"] as? String != "local" || next["server"] as? String != local["server"] as? String || next["viewer"] as? String != local["viewer"] as? String || next["device"] as? String != local["device"] as? String || n <= generation { reason = "stale-generation" }
                else { current = next; local = next; generation = n; candidate = nil; phase = "active"; intent["paused"] = false; sequence = -1; effect = "play-local"; route = "native" }
            } else { reason = "invalid-owner" }
        default: throw failure("unknown session-owner event \(op)")
        }
        return ["owner":current,"local":local,"candidate":candidate as Any? ?? NSNull(),"phase":phase,"intent":intent,"route":route,"generation":String(generation),"reportSequence":String(sequence),"effect":effect,"reason":reason as Any? ?? NSNull()]
    }

    private static func temporary(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try fields(input, allowed: ["records", "effect", "reason"], at: "temporary.state")
        let rawRecords = input["records"] ?? [[String:Any]]()
        guard let array = rawRecords as? [[String:Any]] else { throw failure("temporary.records: expected object array") }
        var records = try array.map { try temporaryRecord($0, at: "temporary.record") }
        let op = try eventString(event,"op")
        try fields(event,allowed:["op","binding","returnedViewer","secretRef"],required:["op","binding"],at:"temporary.event")
        let rawBinding = try object(event["binding"],at:"temporary.binding")
        let recordKeys:Set<String> = ["server","viewer","device","sender","generation","phase","secretRef"]
        try fields(rawBinding,allowed:recordKeys,required:["server","viewer","device","sender","generation"],at:"temporary.binding")
        let binding = try temporaryBinding(rawBinding.filter { ["server","viewer","device","sender","generation"].contains($0.key) },at:"temporary.binding")
        for key in ["server","viewer","device","sender"] { _ = try strictString(binding[key]!,at:"temporary.binding.\(key)") }
        let generation = try int64(binding["generation"],at:"temporary.generation")
        let index = records.firstIndex { sameStableDevice($0,binding) }
        var effect = "none", reason:String?
        if op == "create" {
            if index != nil { reason = "cleanup-required" }
            else if generation < 0 || ["server","viewer","device","sender"].contains(where: { (binding[$0] as! String).trimmingCharacters(in:.whitespacesAndNewlines).isEmpty }) { reason = "invalid-binding" }
            else { records.append(binding.merging(["phase":"creating","secretRef":NSNull()]) { _,n in n }); effect = "provision" }
        } else {
            guard ["issued","use","end","persisted","logout-ok","logout-failed","reconnect"].contains(op) else { throw failure("unknown temporary event \(op)") }
            if let i = index, try int64(records[i]["generation"],at:"record.generation") == generation, records[i]["sender"] as? String == binding["sender"] as? String {
                let phase = records[i]["phase"] as! String
                switch op {
                case "issued":
                    let secret = try event["secretRef"].flatMap { isNull($0) ? nil : try strictString($0,at:"temporary.secretRef") }
                    let viewer = try event["returnedViewer"].flatMap { isNull($0) ? nil : try strictString($0,at:"temporary.returnedViewer") }
                    if !["creating","cancelled-creating"].contains(phase) || secret?.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty != false { reason = "invalid-issue" }
                    else { let matches = viewer == records[i]["viewer"] as? String && phase != "cancelled-creating"; records[i]["phase"] = matches ? "active":"pending-persist"; records[i]["secretRef"] = secret!; effect = matches ? "use":"persist-revocation" }
                case "use": if phase == "active" { effect = "use" } else { reason = "cleanup-required" }
                case "end":
                    if ["cancelled-creating","pending-persist","revoking","pending-revoke"].contains(phase) { break }
                    if records[i]["secretRef"] is NSNull { records[i]["phase"] = "cancelled-creating"; effect = "await-issue" }
                    else { records[i]["phase"] = "pending-persist"; effect = "persist-revocation" }
                case "persisted": if phase == "pending-persist" { records[i]["phase"] = "revoking"; effect = "logout" } else { reason = "invalid-phase" }
                case "logout-ok": if phase == "revoking" { records.remove(at:i); effect = "erase-secret" } else { reason = "invalid-phase" }
                case "logout-failed": if phase == "revoking" { records[i]["phase"] = "pending-revoke"; effect = "pending" } else { reason = "invalid-phase" }
                case "reconnect": if ["pending-revoke","revoking"].contains(phase) { records[i]["phase"] = "revoking"; effect = "logout" }
                default: break
                }
            } else { reason = "stale-binding" }
        }
        return ["records":records,"effect":effect,"reason":reason as Any? ?? NSNull()]
    }

    private static func mode(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try fields(input, allowed: ["ownerKind", "media", "explicitAudio", "group", "requestedRoute", "leaving", "effect"], at: "playback-mode.state")
        var ownerKind = input["ownerKind"] as? String ?? "local"
        var media = input["media"] as? String ?? "video"
        var explicitAudio = input["explicitAudio"] as? Bool ?? false
        var group = input["group"] ?? NSNull()
        var requestedRoute = input["requestedRoute"] ?? NSNull()
        var leaving = input["leaving"] as? Bool ?? false
        var effect = "none"
        let op = try eventString(event, "op")
        let allowed: [String: Set<String>] = ["background": ["op"], "transport": ["op"], "route": ["op", "route"], "leave": ["op"], "left": ["op"], "cancel": ["op"]]
        guard let keys = allowed[op] else { throw failure("unknown playback-mode event \(op)") }
        try fields(event, allowed: keys, required: keys.subtracting(["route"]), at: "playback-mode.\(op)")
        switch op {
        case "background": effect = ownerKind == "local" ? (media == "audio" && explicitAudio ? "keep-audio" : "pause-video") : "keep-receiver"
        case "transport": effect = isNull(group) ? "owner-command" : "group-request"
        case "route":
            let route = try eventString(event, "route")
            guard ["cast", "airplay"].contains(route) else { throw failure("invalid playback-mode route \(route)") }
            if !isNull(group) { requestedRoute = route; effect = "require-leave" }
            else { requestedRoute = route; effect = "select-route" }
        case "leave":
            if !isNull(group), !isNull(requestedRoute) { leaving = true; effect = "leave-group" }
        case "left":
            if leaving, !isNull(group), !isNull(requestedRoute) { group = NSNull(); leaving = false; effect = "select-route" }
        case "cancel": requestedRoute = NSNull(); leaving = false
        default: break
        }
        // Read back unchanged input fields to catch malformed types too.
        if let value = input["ownerKind"] { ownerKind = try strictString(value, at: "playback-mode.ownerKind") }
        if let value = input["media"] { media = try strictString(value, at: "playback-mode.media") }
        if let value = input["explicitAudio"] { explicitAudio = try bool(value, at: "playback-mode.explicitAudio") }
        return ["ownerKind": ownerKind, "media": media, "explicitAudio": explicitAudio, "group": group, "requestedRoute": requestedRoute, "leaving": leaving, "effect": effect]
    }

    private static func ownerState(owner: [String: Any], local: [String: Any], candidate: [String: Any]?, phase: String, intent: [String: Any], route: String, generation: Int64, reportSequence: Int64, effect: String, reason: String?, returnConfirmed: Bool) -> [String: Any] {
        ["owner": owner, "local": local, "candidate": candidate as Any? ?? NSNull(), "phase": phase, "intent": intent, "route": route,
         "generation": String(generation), "reportSequence": String(reportSequence), "effect": effect, "reason": reason as Any? ?? NSNull(), "returnConfirmed": returnConfirmed]
    }

    private static func normalizedIntent(_ raw: Any?) throws -> [String: Any] {
        guard let raw else { return ["entry": NSNull(), "ticks": "0", "paused": true, "queue": [String](), "repeat": "off", "shuffle": [String](), "audio": NSNull(), "subtitle": NSNull()] }
        if raw is NSNull { return ["entry": NSNull(), "ticks": "0", "paused": true, "queue": [String](), "repeat": "off", "shuffle": [String](), "audio": NSNull(), "subtitle": NSNull()] }
        let d = try object(raw, at: "control.intent")
        try fields(d, allowed: intentKeys, at: "control.intent")
        let ticks = try d["ticks"].map { String(try int64($0, at: "intent.ticks")) } ?? "0"
        let queue = try stringArray(d["queue"] ?? [String](), at: "intent.queue")
        let shuffle = try stringArray(d["shuffle"] ?? [String](), at: "intent.shuffle")
        return ["entry": d["entry"] ?? NSNull(), "ticks": ticks, "paused": try d["paused"].map { try bool($0, at: "intent.paused") } ?? true,
                "queue": queue, "repeat": try d["repeat"].map { try strictString($0, at: "intent.repeat") } ?? "off",
                "shuffle": shuffle, "audio": d["audio"] ?? NSNull(), "subtitle": d["subtitle"] ?? NSNull()]
    }

    private static func identity(_ raw: Any?, at path: String) throws -> [String: Any] {
        let d = try object(raw, at: path)
        try fields(d, allowed: identityKeys, required: identityKeys, at: path)
        _ = try int64(d["generation"], at: "\(path).generation")
        for key in ["server", "viewer", "device", "session", "kind"] { _ = try strictString(d[key]!, at: "\(path).\(key)") }
        return d
    }

    private static func sameIdentity(_ a: [String: Any], _ b: [String: Any]) -> Bool {
        identityKeys.allSatisfy { key in
            if key == "generation" { return (try? int64(a[key], at: key)) == (try? int64(b[key], at: key)) }
            return (a[key] as? String) == (b[key] as? String)
        }
    }

    private static func temporaryBinding(_ raw: Any?, at path: String) throws -> [String: Any] {
        let d = try object(raw, at: path)
        let keys: Set<String> = ["device", "generation", "sender", "server", "viewer"]
        try fields(d, allowed: keys, required: keys, at: path)
        _ = try int64(d["generation"], at: "\(path).generation")
        return d
    }

    private static func temporaryRecord(_ raw: [String: Any], at path: String) throws -> [String: Any] {
        let keys: Set<String> = ["device", "generation", "sender", "server", "viewer", "phase", "secretRef"]
        try fields(raw, allowed: keys, required: keys, at: path)
        _ = try int64(raw["generation"], at: "\(path).generation")
        _ = try strictString(raw["phase"]!, at: "\(path).phase")
        return raw
    }

    private static func sameBinding(_ record: [String: Any], _ binding: [String: Any]) -> Bool {
        ["device", "generation", "sender", "server", "viewer"].allSatisfy { (record[$0] as? String) == (binding[$0] as? String) }
    }

    private static func sameStableDevice(_ record: [String: Any], _ binding: [String: Any]) -> Bool {
        ["device", "server", "viewer"].allSatisfy { (record[$0] as? String) == (binding[$0] as? String) }
    }

    private static func fields(_ value: [String: Any], allowed: Set<String>, required: Set<String> = [], at path: String) throws {
        let unknown = Set(value.keys).subtracting(allowed)
        guard unknown.isEmpty else { throw failure("\(path): unknown fields \(unknown.sorted())") }
        let missing = required.subtracting(value.keys)
        guard missing.isEmpty else { throw failure("\(path): missing fields \(missing.sorted())") }
    }

    private static func object(_ value: Any?, at path: String) throws -> [String: Any] {
        guard let value = value as? [String: Any] else { throw failure("\(path): expected object") }
        return value
    }

    private static func eventString(_ d: [String: Any], _ key: String) throws -> String {
        guard let raw = d[key] else { throw failure("missing event field \(key)") }
        return try strictString(raw, at: key)
    }

    private static func strictString(_ value: Any, at path: String) throws -> String {
        guard let result = value as? String else { throw failure("\(path): expected string") }
        return result
    }

    private static func bool(_ value: Any?, at path: String) throws -> Bool {
        guard let n = value as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw failure("\(path): expected boolean") }
        return n.boolValue
    }

    private static func int64(_ value: Any?, at path: String) throws -> Int64 {
        guard let raw = value as? String, canonicalDecimal(raw), let number = Int64(raw), String(number) == raw else { throw failure("\(path): expected canonical signed 64-bit decimal string") }
        return number
    }

    private static func canonicalDecimal(_ text: String) -> Bool {
        guard !text.isEmpty else { return false }
        if text == "0" { return true }
        let digits = text.first == "-" ? text.dropFirst() : text[...]
        guard !digits.isEmpty, digits.first != "0" else { return false }
        return digits.allSatisfy { $0 >= "0" && $0 <= "9" } && (text.first != "-" || digits.contains(where: { $0 != "0" }))
    }

    private static func stringArray(_ value: Any, at path: String) throws -> [String] {
        guard let values = value as? [Any] else { throw failure("\(path): expected string array") }
        return try values.enumerated().map { try strictString($0.element, at: "\(path)[\($0.offset)]") }
    }

    private static func isNull(_ value: Any) -> Bool { value is NSNull }
    private static func failure(_ message: String) -> NSError { NSError(domain: "ControlTraceRunner", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }

    private static func command(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        let stateKeys: Set<String> = ["owner", "capabilities", "authorized", "fresh", "observed", "durationTicks", "sequence", "seekTarget", "pending", "effect", "rejection"]
        try fields(input, allowed: stateKeys, at: "session-command.state")
        let owner = try identity(input["owner"], at: "session-command.owner")
        var capabilities = try stringArray(input["capabilities"] ?? ["play", "pause", "seek", "stop"], at: "session-command.capabilities")
        var authorized = try input["authorized"].map { try bool($0, at: "session-command.authorized") } ?? true
        var fresh = try input["fresh"].map { try bool($0, at: "session-command.fresh") } ?? true
        let duration = try input["durationTicks"].map { try int64($0, at: "session-command.durationTicks") } ?? Int64.max
        var sequence = try input["sequence"].map { try int64($0, at: "session-command.sequence") } ?? 0
        var observed = try commandObservation(input["observed"])
        var seekTarget: Int64? = try input["seekTarget"].flatMap { isNull($0) ? nil : try int64($0, at: "session-command.seekTarget") }
        var pending = try input["pending"].flatMap { isNull($0) ? nil : try commandPending($0) }
        var effect = "none", rejection: String?

        let op = try eventString(event, "op")
        let allowed: [String: Set<String>] = [
            "intent": ["op", "owner", "id", "value", "entry", "nowMs"],
            "observe": ["op", "owner", "id", "nowMs", "entry", "value", "paused", "stopped"],
            "http": ["op", "owner", "id"], "timeout": ["op", "owner", "id", "nowMs"],
            "refresh": ["op", "owner", "value", "entry", "paused", "capabilities", "authorized"],
            "denied": ["op", "owner"], "disconnect": ["op", "owner"]
        ]
        guard let eventKeys = allowed[op] else { throw failure("unknown session-command event \(op)") }
        try fields(event, allowed: eventKeys, required: eventKeys.subtracting(["entry", "nowMs", "paused", "stopped", "value", "capabilities", "authorized"]), at: "session-command.\(op)")
        let eventOwner = try identity(event["owner"], at: "session-command.event.owner")
        guard sameIdentity(eventOwner, owner) else {
            rejection = "stale-owner"
            return commandState(owner: owner, capabilities: capabilities, authorized: authorized, fresh: fresh, observed: observed, duration: duration, sequence: sequence, seekTarget: seekTarget, pending: pending, effect: effect, rejection: rejection)
        }

        switch op {
        case "intent":
            let rawID = try eventString(event, "id")
            guard let commandID = try? int64(rawID, at: "event.id"), commandID > 0 else { rejection = "stale-command"; break }
            let value = try eventString(event, "value")
            var commandCapability: String? = nil
            switch value {
            case "play": commandCapability = "play"
            case "play-now": commandCapability = "play-now"
            case "pause": commandCapability = "pause"
            case "stop": commandCapability = "stop"
            case "seek-absolute", "seek-relative": commandCapability = "seek"
            default: rejection = "unsupported"; break
            }
            guard rejection == nil else { break }
            if value == "play-now" {
                guard let targetEntry = event["entry"] as? String, !targetEntry.isEmpty else { rejection = "missing-entry"; break }
            }
            guard authorized else { rejection = "denied"; break }
            guard fresh else { rejection = "refresh-required"; break }
            guard let commandCapability, capabilities.contains(commandCapability) || capabilities.contains(value) else { rejection = "unsupported"; break }
            guard commandID > sequence else { rejection = "stale-command"; break }

            let now = try event["nowMs"].map { try int64($0, at: "session-command.nowMs") } ?? 0
            guard now >= 0 else { rejection = "stale-command"; break }
            var entry: Any = event["entry"] ?? NSNull(), ticks: Any = NSNull(), paused: Any = NSNull(), stopped: Any = NSNull()
            if value == "play" { paused = false }
            else if value == "pause" { paused = true }
            else if value == "stop" { paused = true; stopped = true }
            else if value == "play-now" {
                guard let rawEntry = event["entry"] as? String, !rawEntry.isEmpty else { rejection = "missing-entry"; break }
                entry = rawEntry; paused = false
            } else if value == "seek-absolute" || value == "seek-relative" {
                guard let rawDelta = event["entry"] as? String,
                      let delta = try? int64(rawDelta, at: "session-command.entry"),
                      value == "seek-relative" || delta >= 0 else { rejection = "invalid-ticks"; break }
                let base: Int64
                if value == "seek-relative" { base = try seekTarget ?? int64(observed["ticks"], at: "observed.ticks") }
                else { base = 0 }
                let (added, overflow) = base.addingReportingOverflow(delta)
                let target = min(max(0, overflow ? (delta >= 0 ? Int64.max : Int64.min) : added), duration)
                ticks = String(target); seekTarget = target; entry = NSNull()
            }
            if rejection == nil {
                let next: [String: Any] = ["id": String(commandID), "operation": value, "entry": entry, "ticks": ticks, "paused": paused,
                                           "sentAtMs": String(now), "result": "sent", "stopped": stopped]
                pending = next; sequence = commandID; effect = "dispatch"
            }
        case "http":
            _ = try int64(event["id"], at: "session-command.id")
        case "timeout":
            let id = try int64(event["id"], at: "session-command.id")
            let now = try int64(event["nowMs"], at: "session-command.nowMs")
            if let current = pending, int64String(current["id"]) == id, current["result"] as? String == "sent",
               now >= int64String(current["sentAtMs"]), now &- int64String(current["sentAtMs"]) >= 5_000 {
                pending?["result"] = "unknown"; fresh = false; seekTarget = nil; effect = "refresh"
            }
        case "observe":
            let id = try int64(event["id"], at: "session-command.id")
            let now = try event["nowMs"].map { try int64($0, at: "session-command.nowMs") } ?? (pending.map { int64String($0["sentAtMs"]) } ?? 0)
            let reportedTicks = try event["value"].map { try int64($0, at: "session-command.value") }
            let reportedEntry = event["entry"] as? String
            let reportedPaused = try event["paused"].flatMap { $0 is NSNull ? nil : try bool($0, at: "session-command.paused") }
            let reportedStopped = try event["stopped"].flatMap { $0 is NSNull ? nil : try bool($0, at: "session-command.stopped") } ?? false
            guard let reportedTicks, reportedTicks >= 0, reportedTicks <= duration else { rejection = "invalid-ticks"; break }
            if var current = pending, int64String(current["id"]) == id, current["result"] as? String == "sent" {
                let start = int64String(current["sentAtMs"])
                let deadline = now >= start && now &- start < 5_000
                let matchesTicks = current["ticks"] is NSNull || String(reportedTicks) == current["ticks"] as? String
                let matchesEntry = current["entry"] is NSNull || reportedEntry == current["entry"] as? String
                let matchesPaused = current["paused"] is NSNull || reportedPaused == current["paused"] as? Bool
                let matchesStopped = current["stopped"] is NSNull || current["stopped"] as? Bool == reportedStopped
                if !deadline { current["result"] = "unknown"; pending = current; fresh = false; seekTarget = nil; effect = "refresh" }
                else {
                    observed["ticks"] = String(reportedTicks); observed["entry"] = reportedEntry as Any? ?? NSNull()
                    if let reportedPaused { observed["paused"] = reportedPaused }
                    if seekTarget == reportedTicks { seekTarget = nil }
                    if matchesTicks && matchesEntry && matchesPaused && matchesStopped { current["result"] = "confirmed" }
                    pending = current
                }
            }
        case "refresh":
            guard let ticks = try? int64(event["value"], at: "session-command.value"), ticks >= 0, ticks <= duration else { rejection = "invalid-ticks"; break }
            fresh = true
            let offered = try event["capabilities"].map { try stringArray($0, at: "session-command.capabilities") } ?? []
            var unique = Set<String>(); capabilities = offered.filter { unique.insert($0).inserted }
            observed["ticks"] = String(ticks); observed["entry"] = event["entry"] ?? NSNull()
            observed["paused"] = try event["paused"].map { try bool($0, at: "session-command.paused") } ?? true
            seekTarget = nil
            authorized = try event["authorized"].map { try bool($0, at: "session-command.authorized") } ?? true
            if let current = pending, current["result"] as? String == "sent" { pending?["result"] = "unknown" }
        case "denied":
            authorized = false; seekTarget = nil; capabilities = []
            if pending != nil { pending?["result"] = "rejected" }
        case "disconnect":
            fresh = false; seekTarget = nil; effect = "refresh"
            if pending != nil { pending?["result"] = "unknown" }
        default: break
        }
        return commandState(owner: owner, capabilities: capabilities, authorized: authorized, fresh: fresh, observed: observed, duration: duration, sequence: sequence, seekTarget: seekTarget, pending: pending, effect: effect, rejection: rejection)
    }

    private static func commandObservation(_ raw: Any?) throws -> [String: Any] {
        try normalizedIntent(raw)
    }

    private static func commandPending(_ raw: Any) throws -> [String: Any] {
        let d = try object(raw, at: "session-command.pending")
        try fields(d, allowed: ["id", "operation", "entry", "ticks", "paused", "sentAtMs", "result", "stopped"], at: "session-command.pending")
        if let value = d["id"] { _ = try int64(value, at: "pending.id") }
        if let value = d["ticks"], !isNull(value) { _ = try int64(value, at: "pending.ticks") }
        if let value = d["sentAtMs"] { _ = try int64(value, at: "pending.sentAtMs") }
        return d
    }

    private static func commandState(owner: [String: Any], capabilities: [String], authorized: Bool, fresh: Bool, observed: [String: Any], duration: Int64, sequence: Int64, seekTarget: Int64?, pending: [String: Any]?, effect: String, rejection: String?) -> [String: Any] {
        ["owner": owner, "capabilities": capabilities, "authorized": authorized, "fresh": fresh, "observed": observed,
         "durationTicks": String(duration), "sequence": String(sequence), "seekTarget": seekTarget.map(String.init) as Any? ?? NSNull(),
         "pending": pending as Any? ?? NSNull(), "effect": effect, "rejection": rejection as Any? ?? NSNull()]
    }

    private static func int64String(_ raw: Any?) -> Int64 {
        (raw as? String).flatMap(Int64.init) ?? 0
    }

    private static func queueReducer(suite: String, operation: String, state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        if operation == "store" { return try store(state: state, event: event) }
        if operation == "transfer" { return try transfer(state: state, event: event) }
        let queue = try normalizeQueue(state, at: "\(suite).queue")
        return try queueEvent(queue: queue, suite: suite, event: event)
    }

    private static let queueKeys: Set<String> = ["server", "viewer", "entries", "order", "current", "positionTicks", "paused", "history", "visited", "revision", "generation", "completion", "repeat", "shuffled", "failed", "operations", "automatic"]

    private static func normalizeQueue(_ raw: [String: Any], at path: String) throws -> [String: Any] {
        try fields(raw, allowed: queueKeys, required: ["server", "viewer"], at: path)
        let server = try strictString(raw["server"]!, at: "\(path).server")
        let viewer = try strictString(raw["viewer"]!, at: "\(path).viewer")
        let rawEntries = try raw["entries"].map { try array($0, at: "\(path).entries") } ?? []
        let entries = try rawEntries.enumerated().map { try normalizeEntry($0.element, at: "\(path).entries[\($0.offset)]") }
        let entryIDs = entries.compactMap { $0["id"] as? String }
        guard Set(entryIDs).count == entryIDs.count else { throw failure("\(path).entries: duplicate entry IDs") }
        let order = try raw["order"].map { try stringArray($0, at: "\(path).order") } ?? entryIDs
        guard order.count == entryIDs.count, Set(order) == Set(entryIDs) else { throw failure("\(path).order: expected a permutation of entry IDs") }
        let current = try nullableString(raw["current"] ?? NSNull(), at: "\(path).current")
        guard current == nil || entryIDs.contains(current!) else { throw failure("\(path).current: unknown entry ID") }
        let ticks = try raw["positionTicks"].map { try int64($0, at: "\(path).positionTicks") } ?? 0
        guard ticks >= 0 else { throw failure("\(path).positionTicks: expected nonnegative ticks") }
        let paused = try raw["paused"].map { try bool($0, at: "\(path).paused") } ?? true
        let history = try raw["history"].map { try stringArray($0, at: "\(path).history") } ?? []
        let visited = try raw["visited"].map { try stringArray($0, at: "\(path).visited") } ?? Array(NSOrderedSet(array: history)) as? [String] ?? history
        let automatic = try raw["automatic"].map { try stringArray($0, at: "\(path).automatic") } ?? []
        guard (history + visited + automatic).allSatisfy({ entryIDs.contains($0) }) else { throw failure("\(path): unknown entry ID") }
        let failed = try raw["failed"].map { try stringArray($0, at: "\(path).failed") } ?? []
        let operations = try raw["operations"].map { try stringArray($0, at: "\(path).operations") } ?? []
        let revision = try raw["revision"].map { try int64($0, at: "\(path).revision") } ?? 0
        let generation = try raw["generation"].map { try int64($0, at: "\(path).generation") } ?? 0
        let completion = try raw["completion"].map { try int64($0, at: "\(path).completion") } ?? 0
        let repeatMode = try raw["repeat"].map { try strictString($0, at: "\(path).repeat") } ?? "off"
        guard ["off", "one", "all"].contains(repeatMode) else { throw failure("\(path).repeat: expected off, one or all") }
        let shuffled = try raw["shuffled"].map { try bool($0, at: "\(path).shuffled") } ?? false
        return ["server": server, "viewer": viewer, "entries": entries, "order": order,
                "current": current as Any? ?? NSNull(), "positionTicks": String(ticks), "paused": paused,
                "history": history, "visited": visited, "revision": String(revision), "generation": String(generation),
                "completion": String(completion), "repeat": repeatMode, "shuffled": shuffled, "failed": failed, "operations": operations, "automatic": automatic]
    }

    private static func normalizeEntry(_ raw: Any, at path: String) throws -> [String: Any] {
        let d = try object(raw, at: path)
        try fields(d, allowed: ["id", "media", "downloaded"], required: ["id", "media"], at: path)
        let id = try strictString(d["id"]!, at: "\(path).id")
        let media = try strictString(d["media"]!, at: "\(path).media")
        guard !id.isEmpty, !media.isEmpty else { throw failure("\(path): id and media must be nonempty") }
        let downloaded = try d["downloaded"].map { try bool($0, at: "\(path).downloaded") } ?? false
        return ["id": id, "media": media, "downloaded": downloaded]
    }

    private static func queueEvent(queue initial: [String: Any], suite: String, event: [String: Any]) throws -> [String: Any] {
        var q = initial
        let op = try eventString(event, "op")
        let keys: [String: Set<String>] = [
            "play-now": ["op", "revision", "generation", "entries", "automatic", "entry", "permutation", "operationId"], "play-next": ["op", "revision", "generation", "entries", "automatic", "operationId"],
            "append": ["op", "revision", "generation", "entries", "automatic", "operationId"], "remove": ["op", "revision", "generation", "entry", "operationId"],
            "move": ["op", "revision", "generation", "entry", "index", "operationId"], "clear-upcoming": ["op", "revision", "generation", "operationId"],
            "clear-all": ["op", "revision", "generation", "operationId"], "shuffle": ["op", "revision", "generation", "permutation", "operationId"],
            "unshuffle": ["op", "revision", "generation", "operationId"], "repeat": ["op", "revision", "generation", "repeat", "operationId"],
            "end": ["op", "revision", "generation", "completion"], "next": ["op", "revision", "generation", "completion"],
            "jump": ["op", "revision", "generation", "entry", "operationId"],
            "previous": ["op", "revision", "generation"], "position": ["op", "revision", "generation", "ticks"],
            "play": ["op", "revision", "generation"], "pause": ["op", "revision", "generation"],
            "error": ["op", "revision", "generation", "completion", "music"], "retry": ["op", "revision", "generation"]
        ]
        guard let allowed = keys[op] else { throw failure("unknown queue event \(op)") }
        try fields(event, allowed: allowed.union(["operationId"]), required: ["op"], at: "queue.\(op)")
        let revision = try event["revision"].map { try int64($0, at: "queue.revision") } ?? 0
        let generation = try event["generation"].map { try int64($0, at: "queue.generation") } ?? 0
        let currentRevision = try int64(q["revision"], at: "queue.revision")
        let currentGeneration = try int64(q["generation"], at: "queue.generation")
        let operationID = try event["operationId"].map { try strictString($0, at: "queue.operationId") }
        var outcome: String = "changed", removed: [String] = []
        if generation != currentGeneration { return ["queue": q, "outcome": "stale-generation", "removed": removed] }
        if let operationID, !operationID.isEmpty, (q["operations"] as? [String] ?? []).contains(operationID) {
            outcome = "duplicate"
            return ["queue": q, "outcome": outcome, "removed": removed]
        }
        if revision != currentRevision { return ["queue": q, "outcome": "refresh-required", "removed": removed] }

        var entries = q["entries"] as! [[String: Any]]
        var order = q["order"] as! [String]
        var history = q["history"] as! [String]
        var visited = q["visited"] as! [String]
        var automatic = q["automatic"] as! [String]
        var failed = q["failed"] as! [String]
        var operations = q["operations"] as! [String]
        var current = (q["current"] as? String)
        var ticks = try int64(q["positionTicks"], at: "queue.positionTicks")
        var paused = q["paused"] as! Bool
        var completion = try int64(q["completion"], at: "queue.completion")
        var repeatMode = q["repeat"] as! String
        var shuffled = q["shuffled"] as! Bool
        let orderIndex = current.flatMap { order.firstIndex(of: $0) }

        if currentRevision == Int64.max { return ["queue": q, "outcome": "revision-exhausted", "removed": removed] }
        if completion == Int64.max && ["play-now", "remove", "clear-all", "previous", "play", "retry", "jump"].contains(op) {
            return ["queue": q, "outcome": "completion-exhausted", "removed": removed]
        }
        let editing = ["play-now", "play-next", "append", "remove", "move", "clear-upcoming", "clear-all"].contains(op)
        if op == "play-now" || op == "play-next" || op == "append" {
            let added = try array(event["entries"] ?? [], at: "queue.entries").enumerated().map { try normalizeEntry($0.element, at: "queue.entries[\($0.offset)]") }
            let continuation = try array(event["automatic"] ?? [], at: "queue.automatic").enumerated().map { try normalizeEntry($0.element, at: "queue.automatic[\($0.offset)]") }
            let block = added.map { $0["id"] as! String }, tail = continuation.map { $0["id"] as! String }
            let all = block + tail
            let ids = entries.map { $0["id"] as! String }
            guard !all.isEmpty, (op != "play-now" || !block.isEmpty), Set(all).count == all.count,
                  op == "play-now" || Set(all).isDisjoint(with: Set(ids)) else {
                return ["queue": q, "outcome": "invalid-entries", "removed": removed]
            }
            if op == "play-now" {
                let permutation = try event["permutation"].map { try stringArray($0, at: "queue.permutation") } ?? []
                guard permutation.isEmpty || (permutation.count == all.count && Set(permutation) == Set(all)) else { return ["queue": q, "outcome": "invalid-permutation", "removed": removed] }
                order = permutation.isEmpty ? all : permutation
                let start = try nullableString(event["entry"] ?? NSNull(), at: "queue.entry") ?? order[0]
                guard all.contains(start) else { return ["queue": q, "outcome": "missing-entry", "removed": removed] }
                entries = added + continuation; current = start; automatic = tail
                ticks = 0; paused = false; history = []; visited = Array(order.prefix(while: { $0 != start })); failed = []; completion += 1; shuffled = !permutation.isEmpty
            } else {
                let upcoming = Set(order.dropFirst((orderIndex ?? -1) + 1))
                let named = Set(added.map { $0["media"] as! String })
                let superseded = Set(entries.filter { automatic.contains($0["id"] as! String) && upcoming.contains($0["id"] as! String) && named.contains($0["media"] as! String) }.map { $0["id"] as! String })
                automatic.removeAll { superseded.contains($0) }
                let upcomingAutomatic = Set(automatic.filter { upcoming.contains($0) })
                func insertAt(_ list: [String]) -> Int {
                    guard let current else { return 0 }
                    let before = list.firstIndex(where: { upcomingAutomatic.contains($0) }) ?? list.count
                    return op == "play-next" ? min((list.firstIndex(of: current) ?? -1) + 1, before) : before
                }
                order.removeAll { superseded.contains($0) }
                entries.removeAll { superseded.contains($0["id"] as! String) }
                order.insert(contentsOf: block, at: insertAt(order))
                entries.insert(contentsOf: added, at: insertAt(entries.map { $0["id"] as! String }))
                var present = Set(entries.map { $0["media"] as! String })
                let fresh = continuation.filter { present.insert($0["media"] as! String).inserted }
                let freshIDs = fresh.map { $0["id"] as! String }
                entries += fresh; order += freshIDs; automatic += freshIDs
                history.removeAll { superseded.contains($0) }; visited.removeAll { superseded.contains($0) }; failed.removeAll { superseded.contains($0) }
                if current == nil { paused = true }
            }
        } else if op == "remove" {
            let id = try eventString(event, "entry")
            guard let index = order.firstIndex(of: id), let entryIndex = entries.firstIndex(where: { $0["id"] as? String == id }) else { return ["queue": q, "outcome": "missing-entry", "removed": removed] }
            let wasCurrent = current == id
            entries.remove(at: entryIndex); order.remove(at: index)
            history.removeAll { $0 == id }; visited.removeAll { $0 == id }; failed.removeAll { $0 == id }; automatic.removeAll { $0 == id }
            if wasCurrent {
                current = index < order.count ? order[index] : order.last
                ticks = 0
                if current == nil { paused = true }
                completion += 1
            }
            if let current { history.removeAll { $0 == current }; visited.removeAll { $0 == current } }
        } else if op == "move" {
            let id = try eventString(event, "entry")
            guard let source = entries.firstIndex(where: { $0["id"] as? String == id }), let rawIndex = event["index"] else { return ["queue": q, "outcome": "invalid-move", "removed": removed] }
            let target = try smallInt(rawIndex, at: "queue.index")
            guard target >= 0, target < entries.count else { return ["queue": q, "outcome": "invalid-move", "removed": removed] }
            let entry = entries.remove(at: source); entries.insert(entry, at: target)
            if !shuffled { order = entries.compactMap { $0["id"] as? String } }
        } else if op == "clear-upcoming" {
            let keep = Set(history + (current.map { [$0] } ?? []))
            order.removeAll { !keep.contains($0) }; entries.removeAll { !keep.contains($0["id"] as? String ?? "") }
            visited.removeAll { !keep.contains($0) }; automatic.removeAll { !keep.contains($0) }
        } else if op == "clear-all" {
            entries = []; order = []; current = nil; ticks = 0; paused = true; history = []; visited = []; automatic = []; failed = []; completion += 1; shuffled = false
        } else if op == "shuffle" {
            let permutation = try event["permutation"].map { try stringArray($0, at: "queue.permutation") } ?? []
            let seen = Set(visited + (current.map { [$0] } ?? []))
            let upcoming = entries.map { $0["id"] as! String }.filter { !seen.contains($0) }
            guard permutation.count == upcoming.count, Set(permutation) == Set(upcoming) else { return ["queue": q, "outcome": "invalid-permutation", "removed": removed] }
            order = order.filter { seen.contains($0) } + permutation.filter { !automatic.contains($0) } + permutation.filter { automatic.contains($0) }; shuffled = true
        } else if op == "unshuffle" {
            let seen = Set(visited + (current.map { [$0] } ?? []))
            order = order.filter { seen.contains($0) } + entries.map { $0["id"] as! String }.filter { !seen.contains($0) }; shuffled = false
        } else if op == "repeat" {
            let mode = try event["repeat"].map { try strictString($0, at: "queue.repeat") } ?? "off"
            guard ["off", "one", "all"].contains(mode) else { return ["queue": q, "outcome": "invalid-repeat", "removed": removed] }
            repeatMode = mode
        } else if op == "position" {
            let target = try int64(event["ticks"], at: "queue.ticks")
            guard target >= 0 else { return ["queue": q, "outcome": "invalid-ticks", "removed": removed] }
            ticks = target
        } else if op == "play" {
            current = current ?? order.first; paused = order.isEmpty; failed = []; completion += 1
        } else if op == "pause" {
            paused = true
        } else if op == "jump" {
            let id = try eventString(event, "entry")
            guard order.contains(id) else { return ["queue": q, "outcome": "missing-entry", "removed": removed] }
            history = (history + (current != id ? current.map { [$0] } ?? [] : [])).filter { $0 != id }
            visited = Array(order.prefix(while: { $0 != id })); current = id; ticks = 0; paused = false; completion += 1; failed = []
        } else if op == "previous" {
            guard let active = current else { return ["queue": q, "outcome": "no-current", "removed": removed] }
            if ticks <= 30_000_000 {
                let prior = history.last ?? ((orderIndex ?? 0) > 0 ? order[(orderIndex ?? 0) - 1] : nil) ?? (repeatMode == "all" ? order.last : nil) ?? active
                current = prior; visited = Array(order.prefix(while: { $0 != prior }))
                if history.last == prior { history.removeLast() }
            }
            ticks = 0; failed = []; completion += 1
        } else if op == "retry" {
            guard current != nil else { return ["queue": q, "outcome": "no-current", "removed": removed] }
            failed = []; paused = false; completion += 1
        } else if ["end", "next", "error"].contains(op) {
            let token = try event["completion"].map { try int64($0, at: "queue.completion") } ?? 0
            guard token == completion else { return ["queue": q, "outcome": "duplicate-completion", "removed": removed] }
            guard let active = current else { return ["queue": q, "outcome": "no-current", "removed": removed] }
            guard completion < Int64.max else { return ["queue": q, "outcome": "completion-exhausted", "removed": removed] }
            completion += 1
            let isError = op == "error", music = try event["music"].map { try bool($0, at: "queue.music") } ?? false
            if isError && !music { paused = true; outcome = "video-error" }
            else if op == "end" && repeatMode == "one" { ticks = 0; failed = [] }
            else {
                failed = isError ? Array(NSOrderedSet(array: failed + [active])) as! [String] : []
                let idx = orderIndex ?? -1
                let upcoming = Array(order.dropFirst(idx + 1)) + (repeatMode == "all" ? Array(order.prefix(idx + 1)) : [])
                if let next = upcoming.first(where: { !failed.contains($0) }) {
                    history.append(active)
                    visited = (order.firstIndex(of: next) ?? 0) <= idx ? [] : Array(NSOrderedSet(array: visited + [active])) as! [String]
                    current = next; ticks = 0; outcome = isError ? "skipped-unavailable" : "changed"
                } else { paused = true; outcome = isError ? "exhausted" : "stopped" }
            }
        }

        if editing || ["shuffle", "unshuffle", "repeat", "position", "play", "pause", "end", "next", "previous", "error", "retry", "jump"].contains(op) {
            q["revision"] = String(currentRevision + 1)
        }
        if let operationID, !operationID.isEmpty { operations.append(operationID) }
        q["entries"] = entries; q["order"] = order; q["history"] = history; q["visited"] = visited; q["failed"] = failed; q["operations"] = operations; q["automatic"] = automatic
        q["current"] = current as Any? ?? NSNull(); q["positionTicks"] = String(ticks); q["paused"] = paused
        q["completion"] = String(completion); q["repeat"] = repeatMode; q["shuffled"] = shuffled
        return ["queue": q, "outcome": outcome, "removed": removed]
    }

    private static func store(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        let keys: Set<String> = ["committed", "pending", "retry", "ticket", "effect", "reason", "removed"]
        try fields(input, allowed: keys, at: "queue-edit.store")
        var committed = try normalizeQueue(input["committed"].map { try object($0, at: "store.committed") } ?? [:], at: "store.committed")
        var pending: [String: Any]? = try optionalQueue(input["pending"], at: "store.pending")
        var retry: [String: Any]? = try optionalQueue(input["retry"], at: "store.retry")
        var ticket = try input["ticket"].map { try int64($0, at: "store.ticket") } ?? 0
        let op = try eventString(event, "op")
        var effect = "none", reason: String?, removed: [String] = []
        if ["edit", "restore", "partition", "retry-persist"].contains(op), ticket == Int64.max {
            return ["committed": committed, "pending": pending as Any? ?? NSNull(), "retry": retry as Any? ?? NSNull(), "ticket": String(ticket), "effect": "none", "reason": "ticket-exhausted", "removed": input["removed"] ?? []]
        }
        switch op {
        case "edit":
            try fields(event, allowed: ["op", "edit"], required: ["op", "edit"], at: "store.edit")
            if pending != nil { reason = "commit-pending"; break }
            let edit = try object(event["edit"], at: "store.edit.edit")
            let result = try queueEvent(queue: committed, suite: "queue-edit", event: edit)
            let outcome = result["outcome"] as? String ?? ""
            if ["changed", "video-error", "exhausted", "stopped", "skipped-unavailable"].contains(outcome) {
                pending = try normalizeQueue(result["queue"] as! [String: Any], at: "store.pending")
                retry = nil; ticket += 1; effect = "persist"; removed = result["removed"] as? [String] ?? []
            } else { reason = outcome }
        case "persisted":
            try fields(event, allowed: ["op", "ticket"], required: ["op", "ticket"], at: "store.persisted")
            let acknowledged = try int64(event["ticket"], at: "store.ticket")
            if let value = pending, acknowledged == ticket { committed = value; pending = nil; retry = nil; effect = "publish" }
            else { reason = "stale-ticket" }
        case "persist-failed":
            try fields(event, allowed: ["op", "ticket"], required: ["op", "ticket"], at: "store.persist-failed")
            let acknowledged = try int64(event["ticket"], at: "store.ticket")
            if let value = pending, acknowledged == ticket { retry = value; pending = nil; effect = "offer-retry" }
            else { reason = "stale-ticket" }
        case "retry-persist":
            try fields(event, allowed: ["op"], required: ["op"], at: "store.retry-persist")
            if pending != nil { reason = "commit-pending" }
            else if let value = retry { pending = value; retry = nil; ticket += 1; effect = "persist" }
            else { reason = "nothing-to-retry" }
        case "restore":
            try fields(event, allowed: ["op", "saved", "accessible", "offline"], required: ["op", "saved", "accessible"], at: "store.restore")
            if pending != nil { reason = "commit-pending"; break }
            let saved = try normalizeQueue(object(event["saved"], at: "store.saved"), at: "store.saved")
            guard saved["server"] as? String == committed["server"] as? String, saved["viewer"] as? String == committed["viewer"] as? String else { reason = "wrong-partition"; break }
            let generation = max(try int64(saved["generation"], at: "store.saved.generation"), try int64(committed["generation"], at: "store.generation"))
            guard generation < Int64.max else { reason = "invalid-queue"; break }
            let accessible = Set(try stringArray(event["accessible"]!, at: "store.accessible"))
            let offline = try event["offline"].map { try bool($0, at: "store.offline") } ?? false
            let savedEntries = saved["entries"] as! [[String: Any]]
            let retained = savedEntries.filter { entry in
                guard let id = entry["id"] as? String else { return false }
                return accessible.contains(id) && (!offline || entry["downloaded"] as? Bool == true)
            }
            let retainedIDs = Set(retained.compactMap { $0["id"] as? String })
            removed = savedEntries.compactMap { $0["id"] as? String }.filter { !retainedIDs.contains($0) }
            var restored = saved
            restored["entries"] = retained
            let savedOrder = saved["order"] as! [String]
            let restoredOrder = savedOrder.filter { retainedIDs.contains($0) }
            restored["order"] = restoredOrder
            var restoredCurrent = saved["current"] as? String
            var restoredTicks = try int64(saved["positionTicks"], at: "store.saved.positionTicks")
            if let c = restoredCurrent, !retainedIDs.contains(c) { restoredCurrent = restoredOrder.first; restoredTicks = 0 }
            if restoredCurrent == nil, let first = restoredOrder.first { restoredCurrent = first; restoredTicks = 0 }
            restored["current"] = restoredCurrent as Any? ?? NSNull(); restored["positionTicks"] = String(restoredTicks)
            restored["paused"] = true
            restored["history"] = (saved["history"] as! [String]).filter { retainedIDs.contains($0) && $0 != restoredCurrent }
            restored["visited"] = (saved["visited"] as! [String]).filter { retainedIDs.contains($0) && $0 != restoredCurrent }
            restored["failed"] = []; restored["automatic"] = (saved["automatic"] as! [String]).filter { retainedIDs.contains($0) }
            restored["generation"] = String(generation + 1)
            restored["operations"] = [String]()
            pending = try normalizeQueue(restored, at: "store.pending"); retry = nil; ticket += 1; effect = "persist"
        case "partition":
            try fields(event, allowed: ["op", "server", "viewer"], required: ["op", "server", "viewer"], at: "store.partition")
            let server = try eventString(event, "server"), viewer = try eventString(event, "viewer")
            guard !server.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !viewer.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { reason = "invalid-partition"; break }
            guard try int64(committed["generation"], at: "store.generation") < Int64.max else { reason = "generation-exhausted"; break }
            ticket += 1; pending = nil; retry = nil
            committed = try normalizeQueue(["server": server, "viewer": viewer, "entries": [], "order": [], "current": NSNull(), "positionTicks": "0", "paused": true,
                                              "history": [], "visited": [], "revision": "0", "generation": String(try int64(committed["generation"], at: "store.generation") + 1),
                                              "completion": "0", "repeat": "off", "shuffled": false, "failed": [], "operations": []], at: "store.partition")
        default: throw failure("unknown store event \(op)")
        }
        return ["committed": committed, "pending": pending as Any? ?? NSNull(), "retry": retry as Any? ?? NSNull(), "ticket": String(ticket), "effect": effect, "reason": reason as Any? ?? NSNull(), "removed": removed]
    }

    private static func transfer(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try fields(input, allowed: ["queue", "intent", "effect", "unavailable"], required: ["queue", "intent"], at: "queue-order.transfer")
        var queue = try normalizeQueue(object(input["queue"], at: "transfer.queue"), at: "transfer.queue")
        var intent = try transferIntent(input["intent"])
        let op = try eventString(event, "op")
        var effect = "none", unavailable: [String] = []
        if op == "prepare" {
            let allowed: Set<String> = ["op", "capabilities", "matchingAudio", "matchingSubtitles", "confirmed"]
            try fields(event, allowed: allowed, required: ["op", "capabilities"], at: "transfer.prepare")
            let caps = Set(try stringArray(event["capabilities"]!, at: "transfer.capabilities"))
            let audioMatches = try intArray(event["matchingAudio"] ?? [], at: "transfer.matchingAudio")
            let subtitleMatches = try intArray(event["matchingSubtitles"] ?? [], at: "transfer.matchingSubtitles")
            let confirmed = try event["confirmed"].map { try bool($0, at: "transfer.confirmed") } ?? false
            let consistent = (intent["queue"] as? [String] == queue["order"] as? [String]) && (intent["entry"] as? String == queue["current"] as? String)
                && (intent["ticks"] as? String == queue["positionTicks"] as? String) && (intent["paused"] as? Bool == queue["paused"] as? Bool)
                && (intent["repeat"] as? String == queue["repeat"] as? String)
                && ((intent["shuffle"] as? [String] ?? []).isEmpty || queue["shuffled"] as? Bool == true)
            guard consistent else { effect = "refresh"; return ["queue": queue, "intent": intent, "effect": effect, "unavailable": unavailable] }
            if !caps.contains("queue") { unavailable.append("queue") }
            if (intent["repeat"] as? String != "off") && !caps.contains("repeat") { unavailable.append("repeat") }
            if !(intent["shuffle"] as? [String] ?? []).isEmpty && !caps.contains("shuffle") { unavailable.append("shuffle") }
            if let audio = intent["audio"] as? Int, (!caps.contains("audio") || !audioMatches.contains(audio)) { unavailable.append("audio") }
            if let subtitle = intent["subtitle"] as? Int, (!caps.contains("subtitle") || !subtitleMatches.contains(subtitle)) { unavailable.append("subtitle") }
            if !unavailable.isEmpty && !confirmed { effect = "require-replacement" }
            else {
                if unavailable.contains("repeat") { intent["repeat"] = "off" }
                if unavailable.contains("shuffle") { intent["shuffle"] = [String]() }
                if unavailable.contains("audio") { intent["audio"] = NSNull() }
                if unavailable.contains("subtitle") { intent["subtitle"] = NSNull() }
                if unavailable.contains("queue") { intent["queue"] = [String]() }
                effect = "transfer"
            }
        } else if op == "attach" {
            try fields(event, allowed: ["op", "authoritative"], required: ["op", "authoritative"], at: "transfer.attach")
            let authoritative = try normalizeQueue(object(event["authoritative"], at: "transfer.authoritative"), at: "transfer.authoritative")
            if authoritative["server"] as? String != queue["server"] as? String || authoritative["viewer"] as? String != queue["viewer"] as? String {
                effect = "reject-partition"
            } else {
                queue = authoritative
                intent = ["entry": queue["current"]!, "ticks": queue["positionTicks"]!, "paused": queue["paused"]!, "queue": queue["order"]!,
                          "repeat": queue["repeat"]!, "shuffle": (queue["shuffled"] as? Bool == true) ? queue["order"]! : [String](), "audio": NSNull(), "subtitle": NSNull()]
                effect = "attached"
            }
        } else { throw failure("unknown transfer event \(op)") }
        return ["queue": queue, "intent": intent, "effect": effect, "unavailable": unavailable]
    }

    private static func transferIntent(_ raw: Any?) throws -> [String: Any] {
        let d = try object(raw, at: "transfer.intent")
        let keys: Set<String> = ["entry", "ticks", "paused", "queue", "repeat", "shuffle", "audio", "subtitle"]
        try fields(d, allowed: keys, required: keys, at: "transfer.intent")
        let entry = try nullableString(d["entry"] ?? NSNull(), at: "transfer.intent.entry")
        let ticks = try int64(d["ticks"], at: "transfer.intent.ticks")
        guard ticks >= 0 else { throw failure("transfer.intent.ticks: expected nonnegative ticks") }
        let paused = try bool(d["paused"], at: "transfer.intent.paused")
        let queue = try stringArray(d["queue"]!, at: "transfer.intent.queue")
        let repeatMode = try strictString(d["repeat"]!, at: "transfer.intent.repeat")
        guard ["off", "one", "all"].contains(repeatMode) else { throw failure("transfer.intent.repeat: invalid mode") }
        let shuffle = try stringArray(d["shuffle"]!, at: "transfer.intent.shuffle")
        let audio = try nullableSmallInt(d["audio"], at: "transfer.intent.audio")
        let subtitle = try nullableSmallInt(d["subtitle"], at: "transfer.intent.subtitle")
        return ["entry": entry as Any? ?? NSNull(), "ticks": String(ticks), "paused": paused, "queue": queue, "repeat": repeatMode, "shuffle": shuffle,
                "audio": audio as Any? ?? NSNull(), "subtitle": subtitle as Any? ?? NSNull()]
    }

    private static func optionalQueue(_ raw: Any?, at path: String) throws -> [String: Any]? {
        guard let raw, !isNull(raw) else { return nil }
        return try normalizeQueue(object(raw, at: path), at: path)
    }

    private static func array(_ raw: Any, at path: String) throws -> [Any] {
        guard let values = raw as? [Any] else { throw failure("\(path): expected array") }
        return values
    }

    private static func nullableString(_ raw: Any, at path: String) throws -> String? {
        if isNull(raw) { return nil }
        return try strictString(raw, at: path)
    }

    private static func smallInt(_ raw: Any, at path: String) throws -> Int {
        guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.rounded(.towardZero) == n.doubleValue,
              n.doubleValue >= Double(Int.min), n.doubleValue <= Double(Int.max) else { throw failure("\(path): expected small integer") }
        return n.intValue
    }

    private static func nullableSmallInt(_ raw: Any?, at path: String) throws -> Int? {
        guard let raw, !isNull(raw) else { return nil }
        return try smallInt(raw, at: path)
    }

    private static func intArray(_ raw: Any, at path: String) throws -> [Int] {
        try array(raw, at: path).enumerated().map { try smallInt($0.element, at: "\(path)[\($0.offset)]") }
    }

}
