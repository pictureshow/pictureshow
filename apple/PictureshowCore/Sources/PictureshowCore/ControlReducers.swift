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
        try fields(input, allowed: ["owner", "local", "candidate", "phase", "intent", "route", "generation", "reportSequence", "effect", "reason", "returnConfirmed"], at: "session-owner.state")
        let owner = try identity(input["owner"], at: "session-owner.owner")
        let local = try input["local"].map { try identity($0, at: "session-owner.local") } ?? owner
        let currentGeneration = try input["generation"].map { try int64($0, at: "session-owner.generation") } ?? int64(owner["generation"], at: "owner.generation")
        let reportSequence = try input["reportSequence"].map { try int64($0, at: "session-owner.reportSequence") } ?? -1
        var candidate = try input["candidate"].flatMap { $0 is NSNull ? nil : try identity($0, at: "session-owner.candidate") }
        var phase = input["phase"] as? String ?? "active"
        var intent = try normalizedIntent(input["intent"])
        var route = input["route"] as? String ?? "native"
        var sequence = reportSequence
        var effect = "none"
        var reason: String?
        var newOwner = owner
        var newLocal = local
        var generation = currentGeneration
        var returnConfirmed = input["returnConfirmed"] as? Bool ?? false

        let op = try eventString(event, "op")
        let eventFields: [String: Set<String>] = [
            "replace": ["op", "identity"], "viewer-switch": ["op", "identity"], "report": ["op", "identity", "sequence"],
            "airplay": ["op", "identity"], "native-route": ["op", "identity"], "select": ["op", "identity", "intent"],
            "connect": ["op", "identity"], "prepare": ["op", "identity"], "local-paused": ["op", "identity"],
            "ready": ["op", "identity", "ticks"], "fail": ["op", "identity"], "timeout": ["op", "identity"],
            "cancel": ["op", "identity"], "disconnect": ["op", "identity"], "return": ["op", "identity"],
            "remote-stopped": ["op", "identity", "confirmed"], "resume-here": ["op", "identity", "confirmed"]
        ]
        guard let allowed = eventFields[op] else { throw failure("unknown session-owner event \(op)") }
        try fields(event, allowed: allowed, required: allowed.subtracting(["confirmed", "intent"]), at: "session-owner.\(op)")

        if ["replace", "viewer-switch"].contains(op) {
            let next = try identity(event["identity"], at: "session-owner.identity")
            let nextGeneration = try int64(next["generation"], at: "identity.generation")
            if nextGeneration <= generation { reason = "stale-generation" }
            else {
                newOwner = next; generation = nextGeneration; candidate = nil; phase = "active"; route = "native"; sequence = -1
                if next["kind"] as? String == "local" { newLocal = next }
                if op == "viewer-switch" || next["server"] as? String != owner["server"] as? String || next["viewer"] as? String != owner["viewer"] as? String {
                    intent = try normalizedIntent(nil)
                }
            }
        } else if op == "report" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            let report = try int64(event["sequence"], at: "session-owner.sequence")
            if phase == "active" || phase == "selecting" || phase == "connecting" || phase == "preparing" || phase == "awaiting-ready" {
                if sameIdentity(identity, newOwner), report > sequence { sequence = report; effect = "report" }
                else { reason = "stale-report" }
            }
        } else if op == "airplay" || op == "native-route" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            if sameIdentity(identity, newLocal) { route = op == "airplay" ? "airplay" : "native" }
            else { reason = "stale-owner" }
        } else if op == "select" {
            let target = try identity(event["identity"], at: "session-owner.identity")
            let targetGeneration = try int64(target["generation"], at: "identity.generation")
            let wanted = try normalizedIntent(event["intent"])
            guard target["kind"] as? String != "local", target["server"] as? String == newOwner["server"] as? String,
                  target["viewer"] as? String == newOwner["viewer"] as? String, targetGeneration > generation else {
                candidate = nil; reason = "invalid-target"
                return ownerState(owner: newOwner, local: newLocal, candidate: candidate, phase: phase, intent: intent, route: route, generation: generation, reportSequence: sequence, effect: effect, reason: reason, returnConfirmed: returnConfirmed)
            }
            candidate = target; intent = wanted; phase = "selecting"
        } else if ["connect", "prepare", "local-paused", "ready", "fail", "timeout", "cancel"].contains(op) {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            guard let target = candidate, sameIdentity(identity, target) else {
                reason = "not-ready"
                return ownerState(owner: newOwner, local: newLocal, candidate: candidate, phase: phase, intent: intent, route: route, generation: generation, reportSequence: sequence, effect: effect, reason: reason, returnConfirmed: returnConfirmed)
            }
            switch op {
            case "connect": if phase == "selecting" { phase = "connecting" } else { reason = "not-ready" }
            case "prepare": if phase == "connecting" { phase = "preparing"; effect = "pause-local" } else { reason = "not-ready" }
            case "local-paused": if phase == "preparing" { phase = "awaiting-ready"; effect = "request-remote" } else { reason = "not-ready" }
            case "ready":
                let ticks = try int64(event["ticks"], at: "session-owner.ticks")
                if phase == "awaiting-ready", ticks == (try int64(intent["ticks"], at: "intent.ticks")) {
                    newOwner = target; generation = try int64(target["generation"], at: "candidate.generation"); candidate = nil; phase = "active"; sequence = -1; route = "native"
                } else { reason = "not-ready" }
            case "fail", "timeout", "cancel":
                if ["selecting", "connecting", "preparing", "awaiting-ready"].contains(phase) {
                    phase = "failed"; candidate = nil; intent["paused"] = true; effect = "resume-here"
                } else { reason = "not-ready" }
            default: break
            }
        } else if op == "return" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            if sameIdentity(identity, newOwner), newOwner["kind"] as? String != "local" { phase = "returning"; effect = "stop-remote" }
            else { reason = "stale-owner" }
        } else if op == "remote-stopped" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            let confirmed = try bool(event["confirmed"], at: "session-owner.confirmed")
            if sameIdentity(identity, newOwner), phase == "returning" {
                returnConfirmed = confirmed
                phase = confirmed ? "return-ready" : "return-uncertain"
                effect = confirmed ? "resume-here" : "require-choice"
            } else { reason = "not-ready" }
        } else if op == "resume-here" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            let nextGeneration = try int64(identity["generation"], at: "identity.generation")
            let confirmed = event["confirmed"] as? Bool ?? false
            guard identity["kind"] as? String == "local", identity["server"] as? String == newOwner["server"] as? String,
                  identity["viewer"] as? String == newOwner["viewer"] as? String, nextGeneration > generation else { reason = "invalid-target"; return ownerState(owner: newOwner, local: newLocal, candidate: candidate, phase: phase, intent: intent, route: route, generation: generation, reportSequence: sequence, effect: effect, reason: reason, returnConfirmed: returnConfirmed) }
            if phase == "return-uncertain" && !confirmed { reason = "explicit-choice-required" }
            else if !["failed", "return-ready", "return-uncertain", "disconnected"].contains(phase) { reason = "not-ready" }
            else {
                if phase == "return-uncertain" && !confirmed { reason = "explicit-choice-required" }
                else {
                    intent["paused"] = false; newOwner = identity; newLocal = identity; generation = nextGeneration
                    phase = "active"; route = "native"; sequence = -1; candidate = nil; effect = "play-local"; returnConfirmed = false
                }
            }
        } else if op == "disconnect" {
            let identity = try identity(event["identity"], at: "session-owner.identity")
            if sameIdentity(identity, newOwner) { phase = "disconnected"; effect = "refresh" }
            else { reason = "stale-owner" }
        }

        return ownerState(owner: newOwner, local: newLocal, candidate: candidate, phase: phase, intent: intent, route: route, generation: generation, reportSequence: sequence, effect: effect, reason: reason, returnConfirmed: returnConfirmed)
    }

    private static func temporary(state input: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try fields(input, allowed: ["records", "effect", "reason"], at: "temporary.state")
        var records = try (input["records"] as? [[String: Any]] ?? []).map { try temporaryRecord($0, at: "temporary.record") }
        let op = try eventString(event, "op")
        let allowed: [String: Set<String>] = [
            "create": ["op", "binding"], "issued": ["op", "binding", "returnedViewer", "secretRef"], "end": ["op", "binding"],
            "persisted": ["op", "binding"], "logout-failed": ["op", "binding"], "reconnect": ["op", "binding"],
            "logout-ok": ["op", "binding"], "use": ["op", "binding"]
        ]
        guard let keys = allowed[op] else { throw failure("unknown temporary event \(op)") }
        try fields(event, allowed: keys, required: keys.subtracting(["returnedViewer", "secretRef"]), at: "temporary.\(op)")
        let binding = try temporaryBinding(event["binding"], at: "temporary.binding")
        var effect = "none", reason: String?
        let recordIndex = records.firstIndex { sameBinding($0, binding) }
        switch op {
        case "create":
            let occupied = records.contains { sameStableDevice($0, binding) }
            if occupied { reason = "cleanup-required" }
            else { records.append(binding.merging(["phase": "creating", "secretRef": NSNull()]) { _, n in n }); effect = "provision" }
        case "issued":
            guard let index = recordIndex, records[index]["sender"] as? String == binding["sender"] as? String,
                  records[index]["generation"] as? String == binding["generation"] as? String else { reason = "stale-binding"; break }
            let secret = try eventString(event, "secretRef")
            let returnedViewer = try eventString(event, "returnedViewer")
            if records[index]["phase"] as? String == "cancelled-creating" || returnedViewer != binding["viewer"] as? String {
                records[index]["phase"] = "pending-persist"; records[index]["secretRef"] = secret; effect = "persist-revocation"
            } else if records[index]["phase"] as? String == "creating" {
                records[index]["phase"] = "active"; records[index]["secretRef"] = secret; effect = "use"
            } else { reason = "stale-binding" }
        case "use":
            if let index = recordIndex, records[index]["phase"] as? String == "active" { effect = "use" }
            else { reason = "cleanup-required" }
        case "end":
            guard let index = recordIndex else { reason = "stale-binding"; break }
            switch records[index]["phase"] as? String {
            case "creating": records[index]["phase"] = "cancelled-creating"; effect = "await-issue"
            case "active": records[index]["phase"] = "pending-persist"; effect = "persist-revocation"
            default: reason = "cleanup-required"
            }
        case "persisted":
            if let index = recordIndex, records[index]["phase"] as? String == "pending-persist" { records[index]["phase"] = "revoking"; effect = "logout" }
            else { reason = "stale-binding" }
        case "logout-failed":
            if let index = recordIndex, records[index]["phase"] as? String == "revoking" { records[index]["phase"] = "pending-revoke"; effect = "pending" }
            else { reason = "stale-binding" }
        case "reconnect":
            if let index = recordIndex, ["pending-revoke", "revoking"].contains(records[index]["phase"] as? String ?? "") { records[index]["phase"] = "revoking"; effect = "logout" }
            else { reason = "stale-binding" }
        case "logout-ok":
            if let index = recordIndex, ["revoking", "pending-revoke"].contains(records[index]["phase"] as? String ?? "") { records.remove(at: index); effect = "erase-secret" }
            else { reason = "stale-binding" }
        default: break
        }
        return ["records": records, "effect": effect, "reason": reason as Any? ?? NSNull()]
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
            "refresh": ["op", "owner", "value", "entry", "paused", "capabilities"],
            "denied": ["op", "owner"], "disconnect": ["op", "owner"]
        ]
        guard let eventKeys = allowed[op] else { throw failure("unknown session-command event \(op)") }
        try fields(event, allowed: eventKeys, required: eventKeys.subtracting(["entry", "nowMs", "paused", "stopped", "value", "capabilities"]), at: "session-command.\(op)")
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
            case "play", "play-now": commandCapability = "play"
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
            var entry: Any = NSNull(), ticks: Any = NSNull(), paused: Any = NSNull(), stopped: Any = NSNull()
            if value == "play" { paused = false }
            else if value == "pause" { paused = true }
            else if value == "stop" { paused = true; stopped = true }
            else if value == "play-now" {
                guard let rawEntry = event["entry"] as? String, !rawEntry.isEmpty else { rejection = "missing-entry"; break }
                entry = rawEntry; paused = false; ticks = "0"
            } else if value == "seek-absolute" || value == "seek-relative" {
                guard let rawDelta = event["entry"] as? String,
                      let delta = try? int64(rawDelta, at: "session-command.entry"),
                      value == "seek-relative" || delta >= 0 else { rejection = "invalid-ticks"; break }
                let base: Int64
                if value == "seek-relative" { base = try seekTarget ?? int64(observed["ticks"], at: "observed.ticks") }
                else { base = 0 }
                let (added, overflow) = base.addingReportingOverflow(delta)
                let target = min(max(0, overflow ? (delta >= 0 ? Int64.max : Int64.min) : added), duration)
                ticks = String(target); seekTarget = target
            }
            if rejection == nil {
                let next: [String: Any] = ["id": String(commandID), "operation": value, "entry": entry, "ticks": ticks, "paused": paused,
                                           "sentAtMs": String(now), "result": "sent", "stopped": stopped]
                let duplicateIntent = pending?["operation"] as? String == value && ["sent", "confirmed"].contains(pending?["result"] as? String ?? "")
                    && pending?["entry"] as? String == entry as? String && pending?["ticks"] as? String == ticks as? String && pending?["paused"] as? Bool == paused as? Bool
                pending = next; sequence = commandID
                if !duplicateIntent { effect = "dispatch" }
            }
        case "http":
            _ = try int64(event["id"], at: "session-command.id")
        case "timeout":
            let id = try int64(event["id"], at: "session-command.id")
            let now = try int64(event["nowMs"], at: "session-command.nowMs")
            if let current = pending, int64String(current["id"]) == id, current["result"] as? String == "sent",
               now >= int64String(current["sentAtMs"]) + 5_000 {
                pending?["result"] = "unknown"; effect = "refresh"
            }
        case "observe":
            let id = try int64(event["id"], at: "session-command.id")
            let now = try event["nowMs"].map { try int64($0, at: "session-command.nowMs") } ?? (pending.map { int64String($0["sentAtMs"]) } ?? 0)
            let reportedTicks = try event["value"].map { try int64($0, at: "session-command.value") }
            let reportedEntry = event["entry"] as? String
            let reportedPaused = try event["paused"].map { try bool($0, at: "session-command.paused") }
            let reportedStopped = try event["stopped"].map { try bool($0, at: "session-command.stopped") } ?? false
            if var current = pending, int64String(current["id"]) == id, current["result"] as? String == "sent" {
                let deadline = now - int64String(current["sentAtMs"]) < 5_000
                let matchesTicks = current["ticks"] is NSNull || (reportedTicks != nil && String(reportedTicks!) == current["ticks"] as? String)
                let matchesEntry = current["entry"] is NSNull || reportedEntry == current["entry"] as? String
                let matchesPaused = current["paused"] is NSNull || reportedPaused == current["paused"] as? Bool
                let matchesStopped = current["stopped"] is NSNull || current["stopped"] as? Bool != true || reportedStopped
                if !deadline { current["result"] = "unknown"; pending = current; effect = "refresh" }
                else if matchesTicks && matchesEntry && matchesPaused && matchesStopped {
                    current["result"] = "confirmed"; pending = current
                    if let reportedTicks { observed["ticks"] = String(reportedTicks) }
                    if let reportedEntry { observed["entry"] = reportedEntry }
                    if let reportedPaused { observed["paused"] = reportedPaused }
                    if current["operation"] as? String == "seek-absolute" || current["operation"] as? String == "seek-relative" { seekTarget = nil }
                }
            }
        case "refresh":
            fresh = true
            capabilities = try event["capabilities"].map { try stringArray($0, at: "session-command.capabilities") } ?? capabilities
            if let rawTicks = event["value"] { observed["ticks"] = String(try int64(rawTicks, at: "session-command.value")) }
            if let entry = event["entry"] { observed["entry"] = try strictString(entry, at: "session-command.entry") }
            if let value = event["paused"] { observed["paused"] = try bool(value, at: "session-command.paused") }
            seekTarget = nil
            authorized = true
            return commandState(owner: owner, capabilities: capabilities, authorized: authorized, fresh: fresh, observed: observed, duration: duration, sequence: sequence, seekTarget: seekTarget, pending: pending, effect: effect, rejection: rejection)
        case "denied":
            authorized = false
            if let current = pending, current["result"] as? String == "sent" { pending?["result"] = "rejected" }
            capabilities = []
            return commandState(owner: owner, capabilities: capabilities, authorized: authorized, fresh: fresh, observed: observed, duration: duration, sequence: sequence, seekTarget: seekTarget, pending: pending, effect: effect, rejection: rejection)
        case "disconnect":
            fresh = false; effect = "refresh"
            if let current = pending, current["result"] as? String == "sent" { pending?["result"] = "unknown" }
        default: break
        }
        return commandState(owner: owner, capabilities: capabilities, authorized: authorized, fresh: fresh, observed: observed, duration: duration, sequence: sequence, seekTarget: seekTarget, pending: pending, effect: effect, rejection: rejection)
    }

    private static func commandObservation(_ raw: Any?) throws -> [String: Any] {
        guard let raw else { return ["entry": NSNull(), "ticks": "0", "paused": true] }
        let d = try object(raw, at: "session-command.observed")
        try fields(d, allowed: ["entry", "ticks", "paused"], at: "session-command.observed")
        return ["entry": d["entry"] ?? NSNull(), "ticks": String(try d["ticks"].map { try int64($0, at: "observed.ticks") } ?? 0), "paused": try d["paused"].map { try bool($0, at: "observed.paused") } ?? true]
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

    private static let queueKeys: Set<String> = ["server", "viewer", "entries", "order", "current", "positionTicks", "paused", "history", "visited", "revision", "generation", "completion", "repeat", "shuffled", "failed", "operations"]

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
                "completion": String(completion), "repeat": repeatMode, "shuffled": shuffled, "failed": failed, "operations": operations]
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
            "play-now": ["op", "revision", "generation", "entries", "operationId"], "play-next": ["op", "revision", "generation", "entries", "operationId"],
            "append": ["op", "revision", "generation", "entries", "operationId"], "remove": ["op", "revision", "generation", "entry", "operationId"],
            "move": ["op", "revision", "generation", "entry", "index", "operationId"], "clear-upcoming": ["op", "revision", "generation", "operationId"],
            "clear-all": ["op", "revision", "generation", "operationId"], "shuffle": ["op", "revision", "generation", "permutation", "operationId"],
            "unshuffle": ["op", "revision", "generation", "operationId"], "repeat": ["op", "revision", "generation", "mode", "operationId"],
            "end": ["op", "revision", "generation", "completion"], "next": ["op", "revision", "generation", "completion"],
            "previous": ["op", "revision", "generation"], "position": ["op", "revision", "generation", "ticks"],
            "play": ["op", "revision", "generation"], "pause": ["op", "revision", "generation"], "stop": ["op", "revision", "generation"],
            "error": ["op", "revision", "generation", "completion", "music"], "retry": ["op", "revision", "generation"]
        ]
        guard let allowed = keys[op] else { throw failure("unknown queue event \(op)") }
        try fields(event, allowed: allowed, required: ["op"], at: "queue.\(op)")
        let revision = try event["revision"].map { try int64($0, at: "queue.revision") } ?? 0
        let generation = try event["generation"].map { try int64($0, at: "queue.generation") } ?? 0
        let currentRevision = try int64(q["revision"], at: "queue.revision")
        let currentGeneration = try int64(q["generation"], at: "queue.generation")
        let operationID = try event["operationId"].map { try strictString($0, at: "queue.operationId") }
        var outcome: String = "changed", removed: [String] = []
        if let operationID, !operationID.isEmpty, (q["operations"] as? [String] ?? []).contains(operationID) {
            outcome = "duplicate"
            return ["queue": q, "outcome": outcome, "removed": removed]
        }
        if generation != currentGeneration { return ["queue": q, "outcome": "stale-generation", "removed": removed] }
        if revision != currentRevision { return ["queue": q, "outcome": "refresh-required", "removed": removed] }

        var entries = q["entries"] as! [[String: Any]]
        var order = q["order"] as! [String]
        var history = q["history"] as! [String]
        var visited = q["visited"] as! [String]
        var failed = q["failed"] as! [String]
        var operations = q["operations"] as! [String]
        var current = (q["current"] as? String)
        var ticks = try int64(q["positionTicks"], at: "queue.positionTicks")
        var paused = q["paused"] as! Bool
        var completion = try int64(q["completion"], at: "queue.completion")
        var repeatMode = q["repeat"] as! String
        var shuffled = q["shuffled"] as! Bool
        let originalCurrent = current
        let orderIndex = current.flatMap { order.firstIndex(of: $0) }

        let editing = ["play-now", "play-next", "append", "remove", "move", "clear-upcoming", "clear-all"].contains(op)
        if editing, let rawID = operationID, !rawID.isEmpty { operations.append(rawID) }
        if op == "play-now" || op == "play-next" || op == "append" {
            guard let rawEntries = event["entries"] else { return ["queue": q, "outcome": "invalid-entries", "removed": removed] }
            let added = try array(rawEntries, at: "queue.entries").enumerated().map { try normalizeEntry($0.element, at: "queue.entries[\($0.offset)]") }
            let addedIDs = added.compactMap { $0["id"] as? String }
            let collisionBase = op == "play-now" ? Set<String>() : Set(entries.compactMap { $0["id"] as? String })
            guard !added.isEmpty, Set(addedIDs).count == addedIDs.count, Set(addedIDs).isDisjoint(with: collisionBase) else {
                return ["queue": q, "outcome": "invalid-entries", "removed": removed]
            }
            if op == "play-now" {
                entries = added; order = addedIDs; current = addedIDs.first; ticks = 0; paused = false; history = []; visited = []; failed = []; completion += 1; shuffled = false
            } else {
                entries.append(contentsOf: added)
                let insertion = current.flatMap { order.firstIndex(of: $0).map { $0 + 1 } } ?? 0
                if op == "play-next" || current == nil { order.insert(contentsOf: addedIDs, at: insertion) }
                else { order.append(contentsOf: addedIDs) }
                if current == nil { paused = true }
            }
        } else if op == "remove" {
            let id = try eventString(event, "entry")
            guard let index = order.firstIndex(of: id), let entryIndex = entries.firstIndex(where: { $0["id"] as? String == id }) else { return ["queue": q, "outcome": "missing-entry", "removed": removed] }
            let wasCurrent = current == id
            entries.remove(at: entryIndex); order.remove(at: index)
            history.removeAll { $0 == id }; visited.removeAll { $0 == id }; failed.removeAll { $0 == id }
            if wasCurrent {
                current = index < order.count ? order[index] : order.last
                if let current { history.removeAll { $0 == current } }
                ticks = 0
                if current == nil { paused = true }
                completion += 1
            }
        } else if op == "move" {
            let id = try eventString(event, "entry")
            guard let source = entries.firstIndex(where: { $0["id"] as? String == id }), let rawIndex = event["index"] else { return ["queue": q, "outcome": "invalid-move", "removed": removed] }
            let target = try smallInt(rawIndex, at: "queue.index")
            guard target >= 0, target < entries.count else { return ["queue": q, "outcome": "invalid-move", "removed": removed] }
            let entry = entries.remove(at: source); entries.insert(entry, at: target)
            if !shuffled { order = entries.compactMap { $0["id"] as? String } }
        } else if op == "clear-upcoming" {
            let keep = Set(order.prefix((orderIndex ?? -1) + 1))
            removed = order.filter { !keep.contains($0) }
            order.removeAll { !keep.contains($0) }; entries.removeAll { !keep.contains($0["id"] as? String ?? "") }
            history.removeAll { !keep.contains($0) }; visited.removeAll { !keep.contains($0) }; failed.removeAll { !keep.contains($0) }
        } else if op == "clear-all" {
            removed = entries.compactMap { $0["id"] as? String }
            entries = []; order = []; current = nil; ticks = 0; paused = true; history = []; visited = []; failed = []; completion += 1; shuffled = false
        } else if op == "shuffle" {
            let permutation = try event["permutation"].map { try stringArray($0, at: "queue.permutation") } ?? []
            let upcoming = order.dropFirst((orderIndex ?? -1) + 1).filter { !visited.contains($0) }
            guard permutation.count == upcoming.count, Set(permutation) == Set(upcoming) else { return ["queue": q, "outcome": "invalid-permutation", "removed": removed] }
            let prefix = Array(order.prefix((orderIndex ?? -1) + 1))
            order = prefix + permutation; shuffled = true
        } else if op == "unshuffle" {
            if shuffled {
                let prefix = Array(order.prefix((orderIndex ?? -1) + 1))
                let seen = Set(prefix + visited)
                let canonicalUpcoming = entries.compactMap { $0["id"] as? String }.filter { !seen.contains($0) }
                order = prefix + canonicalUpcoming; shuffled = false
            }
        } else if op == "repeat" {
            let mode = try eventString(event, "mode")
            guard ["off", "one", "all"].contains(mode) else { return ["queue": q, "outcome": "invalid-repeat", "removed": removed] }
            repeatMode = mode
        } else if op == "position" {
            let target = try int64(event["ticks"], at: "queue.ticks")
            guard target >= 0 else { return ["queue": q, "outcome": "invalid-position", "removed": removed] }
            ticks = target
        } else if op == "play" {
            paused = false; completion += 1
        } else if op == "pause" {
            paused = true
        } else if op == "stop" {
            paused = true; current = nil; ticks = 0; completion += 1
        } else if ["end", "next"].contains(op) {
            let token = try event["completion"].map { try int64($0, at: "queue.completion") } ?? completion
            guard token == completion else { return ["queue": q, "outcome": "duplicate-completion", "removed": removed] }
            completion += 1
            if op == "end", repeatMode == "one", current != nil {
                ticks = 0; paused = false
            } else {
                let idx = current.flatMap { order.firstIndex(of: $0) }
                if let idx, idx + 1 < order.count {
                    if let current {
                        history.append(current)
                        if (shuffled || !visited.isEmpty || repeatMode == "all") && !visited.contains(current) { visited.append(current) }
                    }
                    current = order[idx + 1]; ticks = 0; paused = false
                } else if repeatMode == "all", !order.isEmpty {
                    if let current { history.append(current) }
                    current = order[0]; ticks = 0; paused = false
                    if shuffled { visited = [] }
                } else {
                    paused = true
                    outcome = "stopped"
                }
            }
        } else if op == "previous" {
            if ticks > 30_000_000 {
                ticks = 0
            } else if let last = history.popLast() {
                current = last; ticks = 0
                visited = Array(order.prefix(while: { $0 != last }))
            } else if repeatMode == "all", !order.isEmpty {
                current = order.last; ticks = 0
            } else {
                ticks = 0
            }
            completion += 1
        } else if op == "error" {
            let token = try event["completion"].map { try int64($0, at: "queue.completion") } ?? completion
            guard token == completion else { return ["queue": q, "outcome": "duplicate-completion", "removed": removed] }
            completion += 1
            let isMusic = try event["music"].map { try bool($0, at: "queue.music") } ?? false
            if !isMusic {
                paused = true; outcome = "video-error"
            } else if let active = current {
                if !failed.contains(active) { failed.append(active) }
                if let idx = order.firstIndex(of: active), let next = order.dropFirst(idx + 1).first(where: { !failed.contains($0) }) {
                    history.append(active)
                    if shuffled && !visited.contains(active) { visited.append(active) }
                    current = next; ticks = 0; paused = false; outcome = "skipped-unavailable"
                } else {
                    paused = true; outcome = "exhausted"
                }
            } else { paused = true; outcome = "exhausted" }
        } else if op == "retry" {
            failed = []; paused = false; completion += 1
        }

        if editing || ["shuffle", "unshuffle", "repeat", "position", "play", "pause", "stop", "end", "next", "previous", "error", "retry"].contains(op) {
            q["revision"] = String(currentRevision + 1)
        }
        q["entries"] = entries; q["order"] = order; q["history"] = history; q["visited"] = visited; q["failed"] = failed; q["operations"] = operations
        q["current"] = current as Any? ?? NSNull(); q["positionTicks"] = String(ticks); q["paused"] = paused
        q["completion"] = String(completion); q["repeat"] = repeatMode; q["shuffled"] = shuffled
        _ = originalCurrent
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
        switch op {
        case "edit":
            try fields(event, allowed: ["op", "edit"], required: ["op", "edit"], at: "store.edit")
            if pending != nil { reason = "commit-pending"; break }
            let edit = try object(event["edit"], at: "store.edit.edit")
            let result = try queueEvent(queue: committed, suite: "queue-edit", event: edit)
            let outcome = result["outcome"] as? String ?? ""
            if outcome == "changed" {
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
            else { reason = "no-retry" }
        case "restore":
            try fields(event, allowed: ["op", "saved", "accessible", "offline"], required: ["op", "saved", "accessible"], at: "store.restore")
            if pending != nil { reason = "commit-pending"; break }
            let saved = try normalizeQueue(object(event["saved"], at: "store.saved"), at: "store.saved")
            guard saved["server"] as? String == committed["server"] as? String, saved["viewer"] as? String == committed["viewer"] as? String else { reason = "wrong-partition"; break }
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
            restored["visited"] = (saved["visited"] as! [String]).filter { retainedIDs.contains($0) }
            restored["failed"] = (saved["failed"] as! [String]).filter { retainedIDs.contains($0) }
            restored["generation"] = String(try int64(committed["generation"], at: "store.generation") + 1)
            restored["revision"] = String(try int64(committed["revision"], at: "store.revision") + 1)
            pending = try normalizeQueue(restored, at: "store.pending"); retry = nil; ticket += 1; effect = "persist"
        case "partition":
            try fields(event, allowed: ["op", "server", "viewer"], required: ["op", "server", "viewer"], at: "store.partition")
            let server = try eventString(event, "server"), viewer = try eventString(event, "viewer")
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
