// SPDX-License-Identifier: MPL-2.0
import Foundation

extension RemoteReducers {
    static func receiveState(_ raw: [String: Any]) throws -> [String: Any] {
        var d = raw
        let held = d.removeValue(forKey: "held") ?? [Any](), effects = d.removeValue(forKey: "effects") ?? [Any]()
        var result = try dto(d, defaults: ["server": "", "viewer": "", "generation": "0", "connected": true, "policy": "same-viewer", "advertised": [String](), "seen": [String](), "opening": false, "openedAtMs": "0", "openingItem": null, "relayEnforcesRights": false, "viewing": null, "field": "none", "navWindowMs": "0", "navCount": 0, "rejection": null], required: ["server", "viewer"], longs: ["generation", "openedAtMs", "navWindowMs"], ints: ["navCount"], bools: ["connected", "opening", "relayEnforcesRights"], arrays: ["advertised", "seen"], objects: ["viewing"])
        if let v = result["viewing"] as? [String: Any] { result["viewing"] = try viewing(v) }
        result["held"] = try array(held).map { value -> [String: Any] in
            let value = try object(value); try fields(value, ["message", "receivedAtMs"], required: ["message", "receivedAtMs"])
            return ["message": try inbound(object(value["message"])), "receivedAtMs": String(try long(value["receivedAtMs"]))]
        }
        result["effects"] = try array(effects).map { try dto(object($0), defaults: ["op": "", "items": [String](), "index": null, "ticks": null, "level": null, "name": null, "text": null, "generation": "0"], required: ["op"], longs: ["ticks", "generation"], ints: ["index", "level"], arrays: ["items"]) }
        return result
    }
    static func effect(_ op: String, state: [String: Any], items: [String] = [], index: Int? = nil, ticks: String? = nil, level: Int? = nil, name: String? = nil, text: String? = nil) -> [String: Any] {
        ["op": op, "items": items, "index": index as Any? ?? null, "ticks": ticks as Any? ?? null, "level": level as Any? ?? null, "name": name as Any? ?? null, "text": text as Any? ?? null, "generation": state["generation"] ?? "0"]
    }
    static func receive(_ raw: [String: Any], _ rawEvent: [String: Any]) throws -> [String: Any] {
        var state = try receiveState(raw); state["effects"] = [[String: Any]](); state["rejection"] = null
        let op = try string(rawEvent["op"])
        let schemas: [String: Set<String>] = ["message": ["op", "message", "nowMs"], "viewing": ["op", "viewing", "nowMs"], "resolved": ["op", "generation", "ok"], "field": ["op", "field"], "policy": ["op", "policy", "advertised"], "tick": ["op", "nowMs"], "disconnect": ["op"], "reconnect": ["op"], "account": ["op", "server", "viewer"]]
        guard let keys = schemas[op] else { throw failure("unknown remote receive event \(op)") }
        try fields(rawEvent, keys, required: ["op"])
        let event = try dto(rawEvent, defaults: ["op": "", "message": null, "nowMs": "0", "viewing": null, "field": null, "server": null, "viewer": null, "policy": null, "advertised": null, "generation": "0", "ok": false], required: ["op"], longs: ["nowMs", "generation"], bools: ["ok"], arrays: ["advertised"], objects: ["message", "viewing"])
        let nowMs = l(event, "nowMs")
        switch op {
        case "message": return try received(state, inbound(object(event["message"])), nowMs)
        case "viewing":
            let next = try (event["viewing"] as? [String: Any]).map(viewing)
            let arrived = next.map { b($0, "ready") && (!b(state, "opening") || s($0, "item") == s(state, "openingItem")) } ?? false
            state["viewing"] = next as Any? ?? null; state["opening"] = b(state, "opening") && !arrived
            if !arrived { if next == nil && !b(state, "opening") { state["held"] = [[String: Any]]() }; return state }
            var current = next!, effects: [[String: Any]] = []
            for held in state["held"] as! [[String: Any]] where nowMs &- l(held, "receivedAtMs") < 5000 {
                if let output = transportEffect(state, current, held["message"] as! [String: Any]) {
                    effects.append(output)
                    if s(output, "op") == "pause" { current["paused"] = true }
                    if s(output, "op") == "unpause" { current["paused"] = false }
                }
            }
            state["held"] = [[String: Any]](); state["viewing"] = current; state["effects"] = effects
        case "resolved":
            if l(event, "generation") != l(state, "generation") { state["rejection"] = "stale-generation" }
            else if !b(event, "ok") { state["opening"] = false; state["held"] = [[String: Any]](); state["rejection"] = "unavailable" }
        case "field": state["field"] = ["none", "search", "secret"].contains(s(event, "field") ?? "") ? s(event, "field")! : "none"
        case "policy":
            let policy = ["off", "same-viewer", "server-allowed"].contains(s(event, "policy") ?? "") ? s(event, "policy")! : "same-viewer"
            state["policy"] = policy
            if let advertised = event["advertised"] as? [String] { state["advertised"] = advertised }
            if policy == "off" { state["held"] = [[String: Any]]() }
        case "tick":
            state["held"] = (state["held"] as! [[String: Any]]).filter { nowMs &- l($0, "receivedAtMs") < 5000 }
            state["opening"] = b(state, "opening") && nowMs &- l(state, "openedAtMs") < 30000
        case "disconnect": state["connected"] = false; state["held"] = [[String: Any]](); state["navCount"] = 0
        case "reconnect": state["connected"] = true; state["generation"] = String(l(state, "generation") &+ 1); state["held"] = [[String: Any]](); state["navCount"] = 0
        case "account":
            state["server"] = try string(event["server"]); state["viewer"] = try string(event["viewer"]); state["generation"] = String(l(state, "generation") &+ 1)
            state["seen"] = [String](); state["opening"] = false; state["viewing"] = null; state["held"] = [[String: Any]](); state["field"] = "none"; state["navCount"] = 0
        default: throw failure("unknown remote receive event")
        }
        return state
    }
    static func received(_ input: [String: Any], _ message: [String: Any], _ nowMs: Int64) throws -> [String: Any] {
        var state = input
        func reject(_ why: String) -> [String: Any] { var result = state; result["rejection"] = why; return result }
        let kind = s(message, "kind")!
        if ["keepalive", "ignored"].contains(kind) { return state }
        if kind == "malformed" { return reject("malformed") }
        if !b(state, "connected") { return reject("disconnected") }
        guard let id = s(message, "id") else { return reject("malformed") }
        if a(state, "seen").contains(id) { return reject("duplicate") }
        state["seen"] = Array((a(state, "seen") + [id]).suffix(64))
        if s(state, "policy") == "off" { return reject("disabled") }
        guard let controller = s(message, "controller") else { return reject("no-controller") }
        if controller != normalizedID(s(state, "viewer")), s(state, "policy") == "same-viewer" || !b(state, "relayEnforcesRights") { return reject("other-viewer") }
        if kind == "play" {
            guard let op = ["PlayNow": "open", "PlayNext": "queue-next", "PlayLast": "queue-add"][s(message, "command") ?? ""] else { return reject("unsupported") }
            if op == "open" {
                state["opening"] = true; state["openedAtMs"] = String(nowMs)
                let index = try optionalInt(message["index"]) ?? 0, items = a(message, "items")
                state["openingItem"] = items.indices.contains(index) ? items[index] : null
                state["held"] = [[String: Any]](); state["effects"] = [effect(op, state: state, items: items, index: index, ticks: s(message, "ticks"))]
            } else {
                if !(state["viewing"] is [String: Any]) && !b(state, "opening") { return reject("no-active-viewing") }
                state["effects"] = [effect(op, state: state, items: a(message, "items"))]
            }; return state
        }
        if kind == "playstate" {
            let command = s(message, "command") ?? ""
            guard ["Pause", "Unpause", "PlayPause", "Seek", "Stop", "NextTrack", "PreviousTrack", "Rewind", "FastForward"].contains(command) else { return reject("unsupported") }
            if command == "Seek", s(message, "ticks") == nil || l(message, "ticks") < 0 { return reject("invalid-ticks") }
            let viewing = state["viewing"] as? [String: Any]
            if b(state, "opening") || viewing.map({ !b($0, "ready") }) == true {
                if command == "Stop" { state["opening"] = false; state["held"] = [[String: Any]](); state["effects"] = [effect("stop", state: state)]; return state }
                var held = state["held"] as! [[String: Any]]
                if held.count >= 8 { return reject("busy") }
                held.append(["message": message, "receivedAtMs": String(nowMs)]); state["held"] = held; return state
            }
            guard let viewing else { return reject("no-active-viewing") }
            if let output = transportEffect(state, viewing, message) { state["effects"] = [output] }
            else if ["Seek", "Rewind", "FastForward"].contains(command), !b(viewing, "canSeek") { return reject("not-seekable") }
            return state
        }
        if kind == "general" {
            guard let name = s(message, "command") else { throw failure("general message has no command") }
            guard a(state, "advertised").contains(name) else { return reject("unsupported") }
            let arguments = message["arguments"] as! [String: String]
            var output: [String: Any]
            if navigation.contains(name) {
                let sameWindow = nowMs &- l(state, "navWindowMs") < 1000, count = nowMs &- l(state, "navWindowMs") < 1000 ? i(state, "navCount") + 1 : 1
                if count > 12 { return reject("rate-limited") }
                output = effect("nav", state: state, name: name); state["navWindowMs"] = sameWindow ? state["navWindowMs"]! : String(nowMs); state["navCount"] = count
            } else if name == "SendString" {
                if s(state, "field") == "secret" { return reject("secret-field") }
                if s(state, "field") != "search" { return reject("no-active-field") }
                if let reason = textRejection(arguments["String"]) { return reject(reason) }
                output = effect("text", state: state, text: arguments["String"])
            } else if tracks.contains(name) {
                guard let viewing = state["viewing"] as? [String: Any], b(viewing, "ready") else { return reject("no-active-viewing") }
                guard let raw = arguments["Index"], let index = Int32(raw), index >= (name == "SetAudioStreamIndex" ? 0 : -1) else { return reject("invalid-index") }
                output = effect(name == "SetAudioStreamIndex" ? "audio" : "subtitle", state: state, index: Int(index))
            } else if name == "SetVolume" {
                guard let raw = arguments["Volume"], let level = Int32(raw), (0...100).contains(level) else { return reject("invalid-volume") }
                output = effect("volume", state: state, level: Int(level))
            } else if volume.contains(name) { output = effect("volume-key", state: state, name: name) }
            else { return reject("unsupported") }
            state["effects"] = [output]; return state
        }
        return reject("malformed")
    }
    static func transportEffect(_ state: [String: Any], _ viewing: [String: Any], _ message: [String: Any]) -> [String: Any]? {
        switch s(message, "command") {
        case "Pause": return effect(b(viewing, "paused") ? "report" : "pause", state: state)
        case "Unpause": return effect(b(viewing, "paused") ? "unpause" : "report", state: state)
        case "PlayPause": return effect(b(viewing, "paused") ? "unpause" : "pause", state: state)
        case "Stop": return effect("stop", state: state)
        case "NextTrack": return effect("next", state: state)
        case "PreviousTrack": return effect("previous", state: state)
        case "Seek", "Rewind", "FastForward":
            guard b(viewing, "canSeek") else { return nil }
            let command = s(message, "command")!
            // Kotlin signed-long arithmetic wraps; preserve that defined wire behavior.
            let ticks = command == "Seek" ? l(message, "ticks") : command == "Rewind" ? l(viewing, "ticks") &- 300_000_000 : l(viewing, "ticks") &+ 300_000_000
            let bounded = l(viewing, "durationTicks") > 0 ? min(max(0, ticks), l(viewing, "durationTicks")) : max(0, ticks)
            return effect("seek", state: state, ticks: String(bounded))
        default: return nil
        }
    }
}
