// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CoreFoundation

/// Deterministic intent, reporting and saved-playlist policies. Effects are consumed once by the native host.
public enum MusicReducers {
    public static func session(state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try MusicInput.fields(state, ["intent", "interrupted", "ducked", "effect", "keys"])
        try MusicInput.fields(event, ["op", "key"], required: ["op"])
        var intent = try MusicInput.string(state, "intent", default: "stopped")
        guard ["play", "pause", "stopped"].contains(intent) else { throw MusicInput.failure("invalid intent") }
        var interrupted = try MusicInput.bool(state, "interrupted"), ducked = try MusicInput.bool(state, "ducked")
        var keys = try MusicInput.strings(state, "keys", default: [])
        _ = try MusicInput.string(state, "effect", default: "none")
        let key = try MusicInput.optionalString(event, "key")
        var effect = "none"
        let active = intent == "play" || intent == "pause"
        switch try MusicInput.string(event, "op") {
        case "key":
            if let key, !keys.contains(key) { keys = Array((keys + [key]).suffix(8)); effect = "accept" }
            else { effect = "ignore" }
        case "viewer-play": if intent != "play" || interrupted { intent = "play"; interrupted = false; effect = "play" }
        case "viewer-pause": if active && intent != "pause" { intent = "pause"; effect = "pause" }
        case "interrupted": if intent == "play" && !interrupted { interrupted = true; effect = "pause" }
        case "interruption-ended": if interrupted { interrupted = false; if intent == "play" { effect = "play" } }
        case "focus-lost", "noisy": if intent == "play" { intent = "pause"; interrupted = false; effect = "pause" }
        case "duck": ducked = true
        case "unduck": ducked = false
        case "route-connected": break
        case "video-start", "stop": if active { intent = "stopped"; interrupted = false; ducked = false; effect = "stop-keep" }
        case "queue-clear", "sign-out", "revoked": intent = "stopped"; interrupted = false; ducked = false; effect = "stop-clear"
        default: throw MusicInput.failure("unknown session event")
        }
        return ["intent": intent, "interrupted": interrupted, "ducked": ducked, "effect": effect, "keys": keys]
    }

    public static func report(state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try MusicInput.fields(state, ["viewing", "serial", "effects"])
        try MusicInput.fields(event, ["op", "entry", "media", "ticks", "paused", "failed", "natural", "durationTicks"], required: ["op"])
        if state["effects"] != nil {
            for effect in try MusicInput.objects(state, "effects") {
                try MusicInput.fields(effect, ["report", "media", "serial", "ticks", "paused", "failed"], required: ["report", "media", "serial"])
                _ = try MusicInput.string(effect, "report"); _ = try MusicInput.string(effect, "media")
                _ = try MusicInput.long(effect, "serial"); _ = try MusicInput.long(effect, "ticks", default: 0)
                _ = try MusicInput.bool(effect, "paused"); _ = try MusicInput.bool(effect, "failed")
            }
        }
        let entry = try MusicInput.optionalString(event, "entry"), media = try MusicInput.optionalString(event, "media")
        var serial = try MusicInput.long(state, "serial", default: 0)
        var viewing = try MusicInput.optionalObject(state, "viewing")
        if let v = viewing {
            try MusicInput.fields(v, ["entry", "media", "serial", "started"], required: ["entry", "media", "serial"])
            _ = try MusicInput.string(v, "entry"); _ = try MusicInput.string(v, "media"); _ = try MusicInput.long(v, "serial")
            _ = try MusicInput.bool(v, "started")
        }
        let ticks = try MusicInput.long(event, "ticks", default: 0)
        let duration = try MusicInput.long(event, "durationTicks", default: 0)
        let paused = try MusicInput.bool(event, "paused"), failed = try MusicInput.bool(event, "failed"), natural = try MusicInput.bool(event, "natural")
        let started = try viewing.map { try MusicInput.bool($0, "started") } ?? false
        var effects: [[String: Any]] = []
        func effect(_ name: String, _ at: Int64, paused: Bool = false, failed: Bool = false) throws -> [String: Any] {
            guard let viewing else { throw MusicInput.failure("report needs viewing") }
            return ["report": name, "media": try MusicInput.string(viewing, "media"), "serial": String(try MusicInput.long(viewing, "serial")), "ticks": String(at), "paused": paused, "failed": failed]
        }
        func close(_ failed: Bool = false) throws -> [[String: Any]] {
            try started ? [effect("stopped", natural && duration > 0 ? duration : ticks, failed: failed)] : []
        }
        switch try MusicInput.string(event, "op") {
        case "load":
            if let entry, let media, !(viewing?["entry"] as? String == entry && !started) {
                effects = try close()
                let added = serial.addingReportingOverflow(1)
                guard !added.overflow else { throw MusicInput.failure("viewing serial overflow") }; serial = added.partialValue
                viewing = ["entry": entry, "media": media, "serial": String(serial), "started": false]
            }
        case "playing":
            if viewing != nil {
                effects = try [effect(started ? "progress" : "start", ticks, paused: started && paused)]
                viewing?["started"] = true
            }
        case "progress": if started { effects = try [effect("progress", ticks, paused: paused)] }
        case "ended", "stop": effects = try close(failed); viewing = nil
        default: throw MusicInput.failure("unknown report event")
        }
        return ["viewing": viewing as Any? ?? NSNull(), "serial": String(serial), "effects": effects]
    }

    public static func playlist(state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        try MusicInput.fields(state, ["phase", "entries", "pending", "effect", "message"])
        try MusicInput.fields(event, ["op", "edit", "entries"], required: ["op"])
        _ = try MusicInput.string(state, "effect", default: "none"); _ = try MusicInput.optionalString(state, "message")
        var phase = try MusicInput.string(state, "phase", default: "idle")
        guard ["idle", "sending", "reading", "unknown", "read-only"].contains(phase) else { throw MusicInput.failure("invalid playlist phase") }
        var entries = try MusicInput.strings(state, "entries", default: [])
        var pending = try MusicInput.optionalString(state, "pending")
        let edit = try MusicInput.optionalString(event, "edit"), received = try MusicInput.strings(event, "entries", default: [])
        var effect = "none", message: String?
        switch try MusicInput.string(event, "op") {
        case "loaded": phase = "idle"; entries = received; pending = nil
        case "submit":
            switch phase {
            case "unknown": effect = "read-back"; message = "Checking whether the last change saved."
            case "sending", "reading": message = "Still saving the last change."
            case "read-only": message = "You can't edit this playlist."
            default: phase = "sending"; pending = edit; effect = "send"
            }
        case "sent": if phase == "sending" { phase = "reading"; effect = "read-back" }
        case "denied": if phase == "sending" { phase = "idle"; pending = nil; message = "The server didn't allow that change." }
        case "failed": if phase == "sending" { phase = "idle"; pending = nil; message = "That change didn't save. Try again." }
        case "ambiguous": if phase == "sending" { phase = "unknown"; effect = "read-back"; message = "The server didn't answer, so the change may have saved." }
        case "read": if phase == "reading" || phase == "unknown" {
            let changed = received != entries
            if phase == "unknown" { message = changed ? "That change saved." : "That change didn't save. Try again." }
            else if !changed { message = "The server didn't apply that change." }
            phase = "idle"; entries = received; pending = nil
        }
        case "read-failed": if phase == "reading" || phase == "unknown" { phase = "unknown"; message = "Couldn't read the playlist back. Check it before changing it again." }
        case "read-only": phase = "read-only"
        default: throw MusicInput.failure("unknown playlist event")
        }
        return ["phase": phase, "entries": entries, "pending": pending as Any? ?? NSNull(), "effect": effect, "message": message as Any? ?? NSNull()]
    }

    public static func createPlaylistBody(name: String, viewer: String, items: [String], isPublic: Bool = false) -> [String: Any] {
        ["Name": name, "MediaType": "Audio", "UserId": viewer, "Ids": items, "IsPublic": isPublic]
    }
    public static func renamePlaylistBody(name: String) -> [String: Any] { ["Name": name] }
}

/// Shared strict JSON boundary. In particular, an Int64 never passes through Double or a platform-sized Int.
enum MusicInput {
    static func failure(_ reason: String) -> NSError { NSError(domain: "MusicVectorRunner", code: 1, userInfo: [NSLocalizedDescriptionKey: reason]) }
    static func fields(_ d: [String: Any], _ allowed: Set<String>, required: Set<String> = []) throws {
        guard Set(d.keys).isSubset(of: allowed), required.isSubset(of: Set(d.keys)) else { throw failure("unknown or missing fields: \(d.keys.sorted())") }
    }
    static func string(_ d: [String: Any], _ key: String, default fallback: String? = nil) throws -> String {
        if let value = d[key] { guard let s = value as? String else { throw failure("\(key) must be a string") }; return s }
        guard let fallback else { throw failure("missing \(key)") }; return fallback
    }
    static func optionalString(_ d: [String: Any], _ key: String) throws -> String? {
        guard let raw = d[key], !(raw is NSNull) else { return nil }
        guard let s = raw as? String else { throw failure("\(key) must be a string or null") }; return s
    }
    static func bool(_ d: [String: Any], _ key: String, default fallback: Bool = false) throws -> Bool {
        guard let raw = d[key] else { return fallback }
        guard let n = raw as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw failure("\(key) must be boolean") }; return n.boolValue
    }
    static func int(_ d: [String: Any], _ key: String, default fallback: Int? = nil) throws -> Int {
        guard let raw = d[key] else { guard let fallback else { throw failure("missing \(key)") }; return fallback }
        guard let n = raw as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), let value = Int(n.stringValue), String(value) == n.stringValue else { throw failure("\(key) must be an integer") }; return value
    }
    static func long(_ d: [String: Any], _ key: String, default fallback: Int64? = nil) throws -> Int64 {
        guard d[key] != nil else { guard let fallback else { throw failure("missing \(key)") }; return fallback }
        let raw = try string(d, key)
        guard let value = Int64(raw), String(value) == raw else { throw failure("\(key) must be canonical signed Int64 decimal") }; return value
    }
    static func strings(_ d: [String: Any], _ key: String, default fallback: [String]? = nil) throws -> [String] {
        guard let raw = d[key] else { guard let fallback else { throw failure("missing \(key)") }; return fallback }
        guard let values = raw as? [Any] else { throw failure("\(key) must be an array") }
        return try values.map { guard let s = $0 as? String else { throw failure("\(key) must contain strings") }; return s }
    }
    static func object(_ raw: Any?) throws -> [String: Any] {
        guard let result = raw as? [String: Any] else { throw failure("expected object") }; return result
    }
    static func optionalObject(_ d: [String: Any], _ key: String) throws -> [String: Any]? {
        guard let raw = d[key], !(raw is NSNull) else { return nil }; return try object(raw)
    }
    static func objects(_ d: [String: Any], _ key: String) throws -> [[String: Any]] {
        guard let values = d[key] as? [Any] else { throw failure("\(key) must be array") }; return try values.map { try object($0) }
    }
    static func decode<T: Decodable>(_ type: T.Type, _ raw: Any) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: raw, options: [.fragmentsAllowed]))
    }
}
