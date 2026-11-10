// SPDX-License-Identifier: MPL-2.0
import Foundation
import CoreFoundation

extension RemoteReducers {
    static func wire(operation: String, state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        switch operation {
        case "request":
            // A request trace keeps its target context across steps rather than chaining wire replies.
            try fields(state, ["target"], required: ["target"])
            return try request(target: target(object(state["target"])), command: event)
        case "parse": try fields(event, ["text"], required: ["text"]); return try parseMessage(string(event["text"]))
        case "capabilities":
            try fields(event, ["enabled", "commands"], required: ["enabled", "commands"])
            let enabled = try boolean(event["enabled"]), commands = try strings(event["commands"])
            return ["PlayableMediaTypes": enabled ? ["Video"] : [], "SupportedCommands": enabled ? commands : [], "SupportsMediaControl": enabled, "SupportsPersistentIdentifier": true]
        case "advertised":
            try fields(event, ["fixedVolume"], required: ["fixedVolume"])
            return ["commands": navigation + ["SendString"] + tracks + (try boolean(event["fixedVolume"]) ? [] : volume)]
        case "relay-rights":
            try fields(event, ["version"])
            let version = event["version"] is NSNull || event["version"] == nil ? nil : try string(event["version"])
            let parts = version?.components(separatedBy: ".").map(Int.init)
            let valid = parts?.allSatisfy { $0 != nil } == true && (parts?.count ?? 0) >= 2
            let major = parts?.first ?? nil, minor = parts?.dropFirst().first ?? nil
            return ["enforces": valid && ((major ?? 0) > 12 || (major == 12 && (minor ?? 0) >= 4))]
        case "socket-url":
            try fields(event, ["server"], required: ["server"])
            var base = try string(event["server"]).trimmingCharacters(in: .whitespacesAndNewlines)
            while base.hasSuffix("/") { base.removeLast() }
            let prefix = base.lowercased().hasPrefix("https://") ? ("wss://", 8) : base.lowercased().hasPrefix("http://") ? ("ws://", 7) : nil
            guard let prefix else { return ["url": null] }
            let rest = String(base.dropFirst(prefix.1)); return ["url": rest.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? null : prefix.0 + rest + "/socket"]
        case "targets-path": try fields(event, ["viewer"], required: ["viewer"]); return ["path": "Sessions?controllableByUserId=\(try string(event["viewer"]))"]
        case "cap-note":
            try fields(event, ["sent", "total"], required: ["sent", "total"])
            func grouped(_ n: Int) -> String { let t = String(n); return String(t.reversed().enumerated().map { $0.offset > 0 && $0.offset % 3 == 0 ? ",\($0.element)" : String($0.element) }.joined().reversed()) }
            return ["text": "Sending \(grouped(try integer(event["sent"]))) of \(grouped(try integer(event["total"]))) to the TV…"]
        default: throw failure("unknown remote-wire operation \(operation)")
        }
    }
    public static func request(target t: [String: Any], command raw: [String: Any]) throws -> [String: Any] {
        let t = try target(t)
        let c = try dto(raw, defaults: ["op": "", "items": [String](), "ticks": null, "index": null, "level": null, "name": null, "text": null], required: ["op"], longs: ["ticks"], ints: ["index", "level"], arrays: ["items"])
        func rejected(_ why: String) -> [String: Any] { ["request": null, "rejection": why, "capped": null] }
        func made(_ path: String, query: [String: String] = [:], body: [String: Any]? = nil, cap: [String: Any]? = nil) -> [String: Any] {
            ["request": ["method": "POST", "path": "Sessions/\(s(t, "session")!)/\(path)", "query": query, "body": body as Any? ?? null], "rejection": null, "capped": cap as Any? ?? null]
        }
        func general(_ name: String, arguments: [String: String] = [:]) -> [String: Any] {
            guard a(t, "commands").contains(name) else { return rejected("unsupported") }; return made("Command", body: ["Name": name, "Arguments": arguments])
        }
        let op = s(c, "op")!
        if let mode = ["play-now": "PlayNow", "play-next": "PlayNext", "add-to-queue": "PlayLast"][op] {
            let items = a(c, "items").compactMap(normalizedID)
            guard !items.isEmpty, items.count == a(c, "items").count else { return rejected("invalid-item") }
            guard l(c, "ticks") >= 0 else { return rejected("invalid-ticks") }
            if op != "play-now", !(c["ticks"] is NSNull) || !(c["index"] is NSNull) { return rejected("invalid-position") }
            let index = try optionalInt(c["index"])
            if let index, !items.indices.contains(index) { return rejected("invalid-index") }
            let from = max(0, min(index ?? 0, items.count - playItemLimit)), end = min(items.count, from + playItemLimit)
            var query = ["playCommand": mode, "itemIds": items[from..<end].joined(separator: ",")]
            if let ticks = c["ticks"] as? String { query["startPositionTicks"] = ticks }
            if let index { query["startIndex"] = String(index - from) }
            return made("Playing", query: query, cap: items.count > end - from ? ["sent": end - from, "total": items.count] : nil)
        }
        if let mode = ["pause": "Pause", "play": "Unpause", "stop": "Stop", "next": "NextTrack", "previous": "PreviousTrack"][op] { return made("Playing/\(mode)") }
        switch op {
        case "seek": guard let ticks = c["ticks"] as? String, l(c, "ticks") >= 0 else { return rejected("invalid-ticks") }; return made("Playing/Seek", query: ["seekPositionTicks": ticks])
        case "audio", "subtitle": guard let index = try optionalInt(c["index"]), index >= (op == "audio" ? 0 : -1) else { return rejected("invalid-index") }; return general(op == "audio" ? "SetAudioStreamIndex" : "SetSubtitleStreamIndex", arguments: ["Index": String(index)])
        case "volume": guard let level = try optionalInt(c["level"]), (0...100).contains(level) else { return rejected("invalid-volume") }; return general("SetVolume", arguments: ["Volume": String(level)])
        case "general": guard let name = s(c, "name"), (navigation + volume).contains(name) else { return rejected("unsupported") }; return general(name)
        case "text": if let reason = textRejection(s(c, "text")) { return rejected(reason) }; return general("SendString", arguments: ["String": s(c, "text")!])
        default: return rejected("unsupported")
        }
    }
    /// Reads numeric lexemes before Foundation decoding so fractions/exponents are never accepted as whole ticks.
    public static func parseMessage(_ text: String) throws -> [String: Any] {
        func malformed(_ reason: String) throws -> [String: Any] { try inbound(["kind": "malformed", "reason": reason]) }
        if text.utf16.count > 65_536 { return try malformed("oversize") }
        let marker = UUID().uuidString + ":"
        let chars = Array(text); var pos = 0
        func whitespace() { while pos < chars.count, [" ", "\t", "\r", "\n"].contains(chars[pos]) { pos += 1 } }
        func quotedString() throws -> String {
            guard pos < chars.count, chars[pos] == "\"" else { throw failure("expected JSON string") }
            let start = pos; pos += 1; var escaped = false
            while pos < chars.count {
                let ch = chars[pos]; pos += 1
                if escaped { escaped = false; continue }
                if ch == "\\" { escaped = true; continue }
                if ch == "\"" {
                    let literal = String(chars[start..<pos])
                    guard let data = literal.data(using: .utf8), let value = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) as? String else { throw failure("invalid JSON string") }
                    return value
                }
            }
            throw failure("unterminated JSON string")
        }
        func value(_ depth: Int) throws -> Any {
            guard depth < 512 else { throw failure("JSON nesting too deep") }; whitespace()
            guard pos < chars.count else { throw failure("missing JSON value") }
            let ch = chars[pos]
            if ch == "\"" { return try quotedString() }
            if ch == "{" {
                pos += 1; whitespace(); var object: [String: Any] = [:]
                if pos < chars.count, chars[pos] == "}" { pos += 1; return object }
                while true {
                    whitespace(); let key = try quotedString(); whitespace()
                    guard pos < chars.count, chars[pos] == ":" else { throw failure("missing JSON colon") }; pos += 1
                    object[key] = try value(depth + 1); whitespace()
                    guard pos < chars.count else { throw failure("unterminated JSON object") }
                    if chars[pos] == "}" { pos += 1; return object }
                    guard chars[pos] == "," else { throw failure("missing JSON comma") }; pos += 1
                }
            }
            if ch == "[" {
                pos += 1; whitespace(); var array: [Any] = []
                if pos < chars.count, chars[pos] == "]" { pos += 1; return array }
                while true {
                    array.append(try value(depth + 1)); whitespace()
                    guard pos < chars.count else { throw failure("unterminated JSON array") }
                    if chars[pos] == "]" { pos += 1; return array }
                    guard chars[pos] == "," else { throw failure("missing JSON comma") }; pos += 1
                }
            }
            if ch == "-" || ch.isNumber {
                let start = pos; pos += 1
                while pos < chars.count, "0123456789eE+.-".contains(chars[pos]) { pos += 1 }
                let token = String(chars[start..<pos])
                guard token.range(of: "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?$", options: .regularExpression) != nil else { throw failure("invalid JSON number") }
                return marker + token
            }
            for (literal, result) in [("true", true as Any), ("false", false as Any), ("null", null as Any)] {
                let token = Array(literal)
                if pos + token.count <= chars.count, Array(chars[pos..<(pos + token.count)]) == token { pos += token.count; return result }
            }
            throw failure("invalid JSON value")
        }
        let decoded: Any
        do { decoded = try value(0); whitespace(); guard pos == chars.count else { throw failure("trailing JSON content") } }
        catch { return try malformed("not-json") }
        guard let root = decoded as? [String: Any] else { return try malformed("not-an-object") }
        func textValue(_ v: Any?) -> String? { guard let s = v as? String, !s.hasPrefix(marker) else { return nil }; return s }
        func whole(_ v: Any?) -> Int64? { guard let raw = v as? String, raw.hasPrefix(marker) else { return nil }; let lexeme = String(raw.dropFirst(marker.count)); guard lexeme.allSatisfy({ $0.isNumber || $0 == "-" }) else { return nil }; return Int64(lexeme) }
        guard let type = textValue(root["MessageType"]) else { return try malformed("no-type") }
        let id = textValue(root["MessageId"]).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        var result: [String: Any] = ["kind": "ignored", "id": id as Any? ?? null]
        if type == "ForceKeepAlive" {
            guard let seconds = whole(root["Data"]), (1...3600).contains(seconds) else { return try malformed("bad-keepalive") }
            result["kind"] = "keepalive"; result["keepAliveSeconds"] = Int(seconds); return try inbound(result)
        }
        guard ["Play", "Playstate", "GeneralCommand"].contains(type) else { result["command"] = type; return try inbound(result) }
        guard let body = root["Data"] as? [String: Any] else { return try malformed("no-data") }
        let commandKey = type == "Play" ? "PlayCommand" : type == "Playstate" ? "Command" : "Name"
        guard let command = textValue(body[commandKey]) else { return try malformed("no-command") }
        result["kind"] = type == "Play" ? "play" : type == "Playstate" ? "playstate" : "general"
        result["command"] = command; result["controller"] = normalizedID(textValue(body["ControllingUserId"])) as Any? ?? null
        if type == "Play" {
            guard let raw = body["ItemIds"] as? [Any] else { return try malformed("no-items") }
            let items = raw.compactMap { normalizedID(textValue($0)) }
            guard !raw.isEmpty, raw.count <= 1000, items.count == raw.count else { return try malformed("bad-items") }; result["items"] = items
            if let v = body["StartPositionTicks"] { guard let ticks = whole(v), ticks >= 0 else { return try malformed("bad-ticks") }; result["ticks"] = String(ticks) }
            if let v = body["StartIndex"] { guard let index = whole(v), index >= 0, index < items.count else { return try malformed("bad-index") }; result["index"] = Int(index) }
        } else if type == "Playstate", let v = body["SeekPositionTicks"] {
            guard let ticks = whole(v) else { return try malformed("bad-ticks") }; result["ticks"] = String(ticks)
        } else if type == "GeneralCommand" {
            var arguments: [String: String] = [:]
            for (key, v) in body["Arguments"] as? [String: Any] ?? [:] {
                if let raw = v as? String { arguments[key] = raw.hasPrefix(marker) ? String(raw.dropFirst(marker.count)) : raw }
                else if v is NSNull { return try malformed("bad-arguments") }
                else if let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() { arguments[key] = n.boolValue ? "true" : "false" }
                else { return try malformed("bad-arguments") }
            }; result["arguments"] = arguments
        }
        return try inbound(result)
    }
    public static func targets(_ sessions: [Any], viewer: String, selfDevice: String) throws -> [[String: Any]] {
        func text(_ v: Any?) -> String? { guard let s = v as? String, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }; return s }
        func flag(_ v: Any?) -> Bool { (try? boolean(v)) ?? false }
        func small(_ v: Any?) -> Any { (try? integer(v)) as Any? ?? null }
        func tick(_ v: Any?) -> String {
            guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), let value = Int64(n.stringValue) else { return "0" }; return String(max(0, value))
        }
        func texts(_ v: Any?) -> [String] { (v as? [Any] ?? []).compactMap(text) }
        var rows: [[String: Any]] = []
        for element in sessions {
            guard let row = element as? [String: Any], let session = text(row["Id"]), session.allSatisfy({ $0.isLetter || $0.isNumber || $0 == "-" }), let device = text(row["DeviceId"]), device != selfDevice, flag(row["SupportsRemoteControl"]), texts(row["PlayableMediaTypes"]).contains("Video") else { continue }
            let user = normalizedID(text(row["UserId"])).flatMap { $0.allSatisfy({ $0 == "0" }) ? nil : $0 }, same = user != nil && user == normalizedID(viewer)
            let item = row["NowPlayingItem"] as? [String: Any] ?? [:], play = row["PlayState"] as? [String: Any] ?? [:]
            var now: Any = null
            if let id = normalizedID(text(item["Id"])) {
                let tracks: [[String: Any]] = same ? (item["MediaStreams"] as? [Any] ?? []).compactMap { raw in
                    guard let stream = raw as? [String: Any], let type = text(stream["Type"]), ["Audio", "Subtitle"].contains(type), let index = try? integer(stream["Index"]) else { return nil }
                    return ["type": type, "index": index, "label": text(stream["DisplayTitle"]) ?? text(stream["Language"]) ?? "\(type) \(index)"]
                } : []
                now = ["item": id, "title": same ? (text(item["Name"]) as Any? ?? null) : null, "durationTicks": tick(item["RunTimeTicks"]), "positionTicks": tick(play["PositionTicks"]), "paused": flag(play["IsPaused"]), "canSeek": flag(play["CanSeek"]), "audio": small(play["AudioStreamIndex"]), "subtitle": small(play["SubtitleStreamIndex"]), "volume": small(play["VolumeLevel"]), "muted": flag(play["IsMuted"]), "tracks": tracks]
            }
            var seen = Set<String>()
            rows.append(["session": session, "device": device, "name": text(row["DeviceName"]) ?? "Unnamed device", "client": text(row["Client"]) ?? "", "viewer": user as Any? ?? null, "viewerName": user == nil ? null : (text(row["UserName"]) as Any? ?? null), "sameViewer": same, "duplicateName": false, "commands": texts(row["SupportedCommands"]).filter { seen.insert($0).inserted }, "now": now])
        }
        let counts = Dictionary(grouping: rows, by: { s($0, "name")! }).mapValues(\.count)
        return rows.map { row in var row = row; row["duplicateName"] = (counts[s(row, "name")!] ?? 0) > 1; return row }.sorted {
            if b($0, "sameViewer") != b($1, "sameViewer") { return b($0, "sameViewer") }
            if s($0, "name") != s($1, "name") { return s($0, "name")! < s($1, "name")! }; return s($0, "session")! < s($1, "session")!
        }
    }
}
