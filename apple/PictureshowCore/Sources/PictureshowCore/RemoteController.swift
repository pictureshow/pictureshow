// SPDX-License-Identifier: MPL-2.0
import Foundation

extension RemoteReducers {
    static let targetTransport = ["play", "pause", "seek", "stop", "play-now"]
    static func controllerState(_ raw: [String: Any]) throws -> [String: Any] {
        var state = try dto(raw, defaults: ["server": "", "viewer": "", "phase": "detached", "generation": "0", "target": null, "replacement": null, "command": null, "followUp": null, "effect": "none", "rejection": null], required: ["server", "viewer"], longs: ["generation"], objects: ["target", "command", "followUp"])
        if let value = state["target"] as? [String: Any] { state["target"] = try target(value) }
        if let value = state["followUp"] as? [String: Any] { state["followUp"] = try dto(value, defaults: ["id": "0", "op": "", "sentAtMs": "0", "baselineItem": null, "audio": null, "subtitle": null, "volume": null, "result": "sent"], required: ["id", "op", "sentAtMs"], longs: ["id", "sentAtMs"], ints: ["audio", "subtitle", "volume"]) }
        if let value = state["command"] as? [String: Any] {
            var command = try dto(value, defaults: ["owner": null, "capabilities": ["play", "pause", "seek", "stop"], "authorized": true, "fresh": true, "observed": [String: Any](), "durationTicks": String(Int64.max), "sequence": "0", "seekTarget": null, "pending": null, "effect": "none", "rejection": null], required: ["owner"], longs: ["durationTicks", "sequence", "seekTarget"], bools: ["authorized", "fresh"], arrays: ["capabilities"], objects: ["owner", "observed", "pending"])
            command["owner"] = try dto(object(command["owner"]), defaults: ["server": "", "viewer": "", "device": "", "session": "", "generation": "0", "kind": ""], required: ["server", "viewer", "device", "session", "generation", "kind"], longs: ["generation"])
            command["observed"] = try dto(object(command["observed"]), defaults: ["entry": null, "ticks": "0", "paused": true, "queue": [String](), "repeat": "off", "shuffle": [String](), "audio": null, "subtitle": null], longs: ["ticks"], ints: ["audio", "subtitle"], bools: ["paused"], arrays: ["queue", "shuffle"])
            if let pending = command["pending"] as? [String: Any] {
                command["pending"] = try dto(pending, defaults: ["id": "0", "operation": "", "entry": null, "ticks": null, "paused": null, "sentAtMs": "0", "stopped": null, "result": "sent"], required: ["id", "operation", "sentAtMs"], longs: ["id", "ticks", "sentAtMs"], bools: ["paused", "stopped"])
            }
            state["command"] = command
        }
        return state
    }
    static func commandReduce(_ state: [String: Any], _ event: [String: Any]) throws -> [String: Any] {
        try ControlReducers.reduce(suite: "session-command", operation: "command", state: state, event: event)
    }
    static func syncCommand(_ input: [String: Any], _ now: [String: Any]?) -> [String: Any] {
        var command = input
        command["capabilities"] = now.map { b($0, "canSeek") ? targetTransport : targetTransport.filter { $0 != "seek" } } ?? targetTransport
        command["durationTicks"] = now.map { l($0, "durationTicks") > 0 ? $0["durationTicks"]! : String(Int64.max) } ?? String(Int64.max)
        var observed = command["observed"] as? [String: Any] ?? [:]
        observed["entry"] = now?["item"] ?? null; observed["ticks"] = now?["positionTicks"] ?? "0"; observed["paused"] = now?["paused"] ?? true; command["observed"] = observed
        return command
    }
    static func unresolved(_ raw: Any?) -> Any {
        guard var follow = raw as? [String: Any] else { return null }
        if s(follow, "result") == "sent" { follow["result"] = "unknown" }; return follow
    }
    static func disconnected(_ command: [String: Any]) throws -> [String: Any] {
        var result = try commandReduce(command, ["op": "disconnect", "owner": command["owner"]!]); result["effect"] = "none"
        if let pending = command["pending"] as? [String: Any], s(pending, "result") != "sent" { result["pending"] = pending }; return result
    }
    static func lost(_ input: [String: Any], _ phase: String, replacement: String? = nil) throws -> [String: Any] {
        var state = input; state["phase"] = phase; state["replacement"] = replacement as Any? ?? null; state["followUp"] = unresolved(state["followUp"])
        if let command = state["command"] as? [String: Any] { state["command"] = try disconnected(command) }; return state
    }
    static func controller(_ raw: [String: Any], _ rawEvent: [String: Any]) throws -> [String: Any] {
        var state = try controllerState(raw); state["effect"] = "none"; state["rejection"] = null
        let op = try string(rawEvent["op"])
        let schemas: [String: Set<String>] = ["attach": ["op", "target"], "detach": ["op"], "account": ["op", "server", "viewer"], "refresh": ["op", "targets", "nowMs"], "resume": ["op"], "signed-out": ["op"], "intent": ["op", "id", "nowMs", "value", "entry", "index", "level"], "sent": ["op", "id", "status"], "answer": ["op", "status"], "tick": ["op", "nowMs"]]
        guard let keys = schemas[op] else { throw failure("unknown remote controller event \(op)") }; try fields(rawEvent, keys, required: ["op"])
        var without = rawEvent; let targetsRaw = without.removeValue(forKey: "targets") ?? [Any]()
        var event = try dto(without, defaults: ["op": "", "target": null, "nowMs": "0", "id": "0", "value": null, "entry": null, "index": null, "level": null, "status": 0, "server": null, "viewer": null], required: ["op"], longs: ["nowMs", "id"], ints: ["index", "level", "status"], objects: ["target"])
        event["targets"] = try array(targetsRaw).map { try target(object($0)) }
        if let value = event["target"] as? [String: Any] { event["target"] = try target(value) }
        func reset(_ phase: String, server: String? = nil, viewer: String? = nil) throws -> [String: Any] { try controllerState(["server": server ?? s(state, "server")!, "viewer": viewer ?? s(state, "viewer")!, "phase": phase, "generation": String(l(state, "generation") &+ 1)]) }
        switch op {
        case "attach":
            let target = try object(event["target"]), generation = l(state, "generation") &+ 1
            let owner: [String: Any] = ["server": state["server"]!, "viewer": target["viewer"] is NSNull ? "shared" : target["viewer"]!, "device": target["device"]!, "session": target["session"]!, "generation": String(generation), "kind": "jellyfin"]
            let command: [String: Any] = ["owner": owner, "capabilities": targetTransport, "authorized": true, "fresh": true, "durationTicks": String(Int64.max), "sequence": "0", "seekTarget": null, "pending": null, "observed": ["entry": null, "ticks": "0", "paused": true], "effect": "none", "rejection": null]
            state["phase"] = "attached"; state["generation"] = String(generation); state["target"] = target; state["replacement"] = null; state["followUp"] = null; state["command"] = syncCommand(command, target["now"] as? [String: Any])
        case "detach": state["phase"] = "detached"; state["target"] = null; state["replacement"] = null; state["command"] = null; state["followUp"] = null
        case "account":
            let server = try string(event["server"]), viewer = try string(event["viewer"])
            if server != s(state, "server") || viewer != s(state, "viewer") { return try reset("detached", server: server, viewer: viewer) }
        case "refresh": return try refreshController(state, event)
        case "resume":
            guard let command = state["command"] as? [String: Any] else { return state }
            state["command"] = try disconnected(command); state["followUp"] = unresolved(state["followUp"]); state["effect"] = s(state, "phase") == "detached" ? "none" : "refresh"
        case "signed-out": return try reset("signed-out")
        case "intent":
            guard s(state, "phase") == "attached" else { state["rejection"] = s(state, "phase") == "denied" ? "denied" : "not-attached"; return state }
            var command = try object(state["command"])
            let value = try string(event["value"])
            if !["next", "previous", "audio", "subtitle", "volume", "queue"].contains(value) {
                var ce: [String: Any] = ["op": "intent", "owner": command["owner"]!, "id": event["id"]!, "nowMs": event["nowMs"]!, "value": value]
                if let entry = s(event, "entry") { ce["entry"] = entry }
                let next = try commandReduce(command, ce); state["command"] = next; state["effect"] = next["effect"]; state["rejection"] = next["rejection"]; return state
            }
            if !b(command, "authorized") { state["rejection"] = "denied"; return state }
            if !b(command, "fresh") { state["rejection"] = "refresh-required"; return state }
            if l(event, "id") <= l(command, "sequence") { state["rejection"] = "stale-command"; return state }
            if let required = ["audio": "SetAudioStreamIndex", "subtitle": "SetSubtitleStreamIndex", "volume": "SetVolume"][value], !a(try object(state["target"]), "commands").contains(required) { state["rejection"] = "unsupported"; return state }
            command["sequence"] = event["id"]; state["command"] = command
            state["followUp"] = ["id": event["id"]!, "op": value, "sentAtMs": event["nowMs"]!, "baselineItem": (command["observed"] as? [String: Any])?["entry"] ?? null, "audio": value == "audio" ? event["index"]! : null, "subtitle": value == "subtitle" ? event["index"]! : null, "volume": value == "volume" ? event["level"]! : null, "result": "sent"]
            state["effect"] = "dispatch"
        case "sent", "answer":
            guard var command = state["command"] as? [String: Any] else { return state }
            var pending = command["pending"] as? [String: Any], follow = state["followUp"] as? [String: Any]
            if op == "sent", pending.map({ l($0, "id") == l(event, "id") }) != true && follow.map({ l($0, "id") == l(event, "id") }) != true { return state }
            let status = i(event, "status")
            if status == 401 { return try reset("signed-out") }
            if status == 403 { state["phase"] = "denied"; state["command"] = try commandReduce(command, ["op": "denied", "owner": command["owner"]!]); state["followUp"] = unresolved(follow); return state }
            if status == 404 { return try lost(state, "target-gone") }
            if op == "sent", !(200...299).contains(status) {
                if pending.map({ l($0, "id") == l(event, "id") && s($0, "result") == "sent" }) == true { pending?["result"] = "unknown"; command["pending"] = pending }
                command["fresh"] = false; command["seekTarget"] = null; state["command"] = command
                if follow.map({ l($0, "id") == l(event, "id") }) == true { state["followUp"] = unresolved(follow) }; state["effect"] = "refresh"
            }
        case "tick": return try tickController(state, event)
        default: throw failure("unknown remote controller event")
        }
        return state
    }
    static func refreshController(_ input: [String: Any], _ event: [String: Any]) throws -> [String: Any] {
        var state = input
        guard let attached = state["target"] as? [String: Any] else { return state }
        let sameDevice = (event["targets"] as! [[String: Any]]).first { s($0, "device") == s(attached, "device") && s($0, "viewer") == s(attached, "viewer") }
        let row = sameDevice.flatMap { s($0, "session") == s(attached, "session") ? $0 : nil }
        if s(state, "phase") != "attached" {
            if ["denied", "detached"].contains(s(state, "phase") ?? "") { return state }
            state["phase"] = sameDevice == nil ? "target-gone" : "target-restarted"; state["replacement"] = sameDevice?["session"] ?? null; return state
        }
        guard let row else { return try lost(state, sameDevice == nil ? "target-gone" : "target-restarted", replacement: s(sameDevice ?? [:], "session")) }
        var command = try object(state["command"]), updated = command
        let now = row["now"] as? [String: Any], owner = command["owner"]!
        if !b(command, "fresh") {
            command["durationTicks"] = String(Int64.max)
            var e: [String: Any] = ["op": "refresh", "owner": owner, "value": now?["positionTicks"] ?? "0", "capabilities": targetTransport]
            if let item = now?["item"] { e["entry"] = item }; if let paused = now?["paused"] { e["paused"] = paused }
            updated = try commandReduce(command, e); updated["effect"] = "none"
        } else if let pending = command["pending"] as? [String: Any], s(pending, "result") == "sent" {
            var confirmedSeek: String?
            if let ticks = s(pending, "ticks"), let now {
                let position = l(now, "positionTicks"), target = Int64(ticks)!
                if position >= target &- 10_000_000, position <= target &+ 60_000_000 { confirmedSeek = ticks }
            }
            command["durationTicks"] = String(Int64.max)
            var e: [String: Any] = ["op": "observe", "owner": owner, "id": pending["id"]!, "nowMs": event["nowMs"]!, "value": confirmedSeek ?? (now?["positionTicks"] as? String ?? "0")]
            if let item = now?["item"] { e["entry"] = item }
            if let paused = now?["paused"] { e["paused"] = paused } else if b(pending, "stopped") { e["paused"] = true }
            if b(pending, "stopped") { e["stopped"] = now == nil }
            updated = try commandReduce(command, e)
        }
        if var follow = state["followUp"] as? [String: Any], s(follow, "result") == "sent" {
            var done = false
            switch s(follow, "op") {
            case "next", "previous": done = now != nil && (s(follow, "baselineItem") == nil || s(now!, "item") != s(follow, "baselineItem"))
            case "audio": done = (try optionalInt(now?["audio"])) == (try optionalInt(follow["audio"]))
            case "subtitle": done = (try optionalInt(now?["subtitle"])) == (try optionalInt(follow["subtitle"]))
            case "volume": done = (try optionalInt(now?["volume"])) == (try optionalInt(follow["volume"]))
            default: break
            }
            if done { follow["result"] = "confirmed" }; state["followUp"] = follow
        }
        let refresh = s(updated, "effect") == "refresh"
        updated["effect"] = "none"; updated["rejection"] = null; state["target"] = row; state["command"] = syncCommand(updated, now)
        state = try tickController(state, event)
        if refresh { state["effect"] = "refresh" }; return state
    }
    static func tickController(_ input: [String: Any], _ event: [String: Any]) throws -> [String: Any] {
        var state = input
        guard var command = state["command"] as? [String: Any] else { return state }
        if let pending = command["pending"] as? [String: Any] {
            command = try commandReduce(command, ["op": "timeout", "owner": command["owner"]!, "id": pending["id"]!, "nowMs": event["nowMs"]!])
        }
        if var follow = state["followUp"] as? [String: Any], s(follow, "result") == "sent", l(event, "nowMs") &- l(follow, "sentAtMs") >= 5000 { follow["result"] = "unknown"; state["followUp"] = follow }
        if s(command, "effect") == "refresh" { state["effect"] = "refresh" }; command["effect"] = "none"; state["command"] = command; return state
    }
}
