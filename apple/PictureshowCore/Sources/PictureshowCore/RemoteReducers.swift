// SPDX-License-Identifier: MPL-2.0
import Foundation
import CoreFoundation

/// Pure remote protocol and lifecycle reducers, shared by controller and receiver adapters.
/// Long values use canonical decimal strings at the JSON boundary, preserving all Int64 bits.
public enum RemoteReducers {
    static let null = NSNull()
    static let navigation = ["MoveUp", "MoveDown", "MoveLeft", "MoveRight", "Select", "Back", "GoHome"]
    static let tracks = ["SetAudioStreamIndex", "SetSubtitleStreamIndex"]
    static let volume = ["SetVolume", "VolumeUp", "VolumeDown", "Mute", "Unmute", "ToggleMute"]
    public static let playItemLimit = 175
    public static func reduce(suite: String, operation: String, state: [String: Any], event: [String: Any]) throws -> [String: Any] {
        switch (suite, operation) {
        case ("remote-targets", "targets"):
            try fields(state, ["sessions", "viewer", "selfDevice"], required: ["sessions", "viewer", "selfDevice"]); try fields(event, [])
            return ["targets": try targets(array(state["sessions"]), viewer: string(state["viewer"]), selfDevice: string(state["selfDevice"]))]
        case ("remote-targets", "track-selected"):
            try fields(state, ["reported", "offered"], required: ["reported", "offered"]); try fields(event, [])
            let reported = try optionalInt(state["reported"]), offered = try integer(state["offered"])
            return ["selected": reported != nil && reported == offered]
        case ("remote-wire", _): return try wire(operation: operation, state: state, event: event)
        case ("remote-receive", "receive"): return try receive(state, event)
        case ("remote-reconnect", "controller"): return try controller(state, event)
        case ("remote-queue", "queue"): return try queue(state, event)
        default: throw failure("unknown remote suite/operation \(suite)/\(operation)")
        }
    }
    static func failure(_ message: String) -> NSError { NSError(domain: "RemoteReducers", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
    static func fields(_ d: [String: Any], _ allowed: Set<String>, required: Set<String> = []) throws {
        let unknown = Set(d.keys).subtracting(allowed), missing = required.subtracting(d.keys)
        guard unknown.isEmpty, missing.isEmpty else { throw failure("unknown fields \(unknown.sorted()); missing fields \(missing.sorted())") }
    }
    static func object(_ v: Any?) throws -> [String: Any] { guard let d = v as? [String: Any] else { throw failure("expected object") }; return d }
    static func array(_ v: Any?) throws -> [Any] { guard let a = v as? [Any] else { throw failure("expected array") }; return a }
    static func string(_ v: Any?) throws -> String { guard let s = v as? String else { throw failure("expected string") }; return s }
    static func strings(_ v: Any?) throws -> [String] { try array(v).map { try string($0) } }
    static func boolean(_ v: Any?) throws -> Bool { guard let n = v as? NSNumber, CFGetTypeID(n) == CFBooleanGetTypeID() else { throw failure("expected boolean") }; return n.boolValue }
    static func integer(_ v: Any?) throws -> Int {
        guard let n = v as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), n.doubleValue.isFinite,
              n.doubleValue.rounded(.towardZero) == n.doubleValue, n.doubleValue >= Double(Int32.min), n.doubleValue <= Double(Int32.max) else { throw failure("expected small integer") }; return n.intValue
    }
    static func long(_ v: Any?) throws -> Int64 {
        let s = try string(v)
        guard let n = Int64(s), String(n) == s else { throw failure("expected canonical Int64 decimal string") }; return n
    }
    static func optionalInt(_ v: Any?) throws -> Int? { guard let v, !(v is NSNull) else { return nil }; return try integer(v) }
    static func l(_ d: [String: Any], _ k: String) -> Int64 { Int64(d[k] as? String ?? "0") ?? 0 }
    static func i(_ d: [String: Any], _ k: String) -> Int { d[k] as? Int ?? 0 }
    static func b(_ d: [String: Any], _ k: String) -> Bool { d[k] as? Bool ?? false }
    static func s(_ d: [String: Any], _ k: String) -> String? { d[k] as? String }
    static func a(_ d: [String: Any], _ k: String) -> [String] { d[k] as? [String] ?? [] }
    static func normalizedID(_ value: String?) -> String? {
        guard let compact = value?.lowercased().replacingOccurrences(of: "-", with: ""), compact.count == 32,
              compact.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }) else { return nil }; return compact
    }
    /// Normalizes a declared DTO schema; rejects unknown properties and wrong scalar types.
    static func dto(_ d: [String: Any], defaults: [String: Any], required: Set<String> = [], longs: Set<String> = [], ints: Set<String> = [], bools: Set<String> = [], arrays: Set<String> = [], objects: Set<String> = []) throws -> [String: Any] {
        try fields(d, Set(defaults.keys), required: required)
        var out = defaults
        for (k, v) in d {
            if v is NSNull { guard defaults[k] is NSNull else { throw failure("nonnullable field \(k)") }; out[k] = v; continue }
            if longs.contains(k) { out[k] = String(try long(v)) }
            else if ints.contains(k) { out[k] = try integer(v) }
            else if bools.contains(k) { out[k] = try boolean(v) }
            else if arrays.contains(k) { out[k] = try strings(v) }
            else if objects.contains(k) { out[k] = try object(v) }
            else { out[k] = try string(v) }
        }
        return out
    }
    static func inbound(_ d: [String: Any]) throws -> [String: Any] {
        var result = try dto(d, defaults: ["kind": "", "id": null, "controller": null, "command": null, "items": [String](), "ticks": null, "index": null, "arguments": [String: Any](), "keepAliveSeconds": null, "reason": null], required: ["kind"], longs: ["ticks"], ints: ["index", "keepAliveSeconds"], arrays: ["items"], objects: ["arguments"])
        result["arguments"] = try object(result["arguments"]).mapValues { try string($0) }; return result
    }
    static func viewing(_ d: [String: Any]) throws -> [String: Any] {
        try dto(d, defaults: ["item": "", "ready": false, "durationTicks": "0", "ticks": "0", "paused": false, "canSeek": false], required: ["item"], longs: ["durationTicks", "ticks"], bools: ["ready", "paused", "canSeek"])
    }
    static func now(_ d: [String: Any]) throws -> [String: Any] {
        var without = d; let rawTracks = without.removeValue(forKey: "tracks") ?? [Any]()
        var result = try dto(without, defaults: ["item": "", "title": null, "durationTicks": "0", "positionTicks": "0", "paused": false, "canSeek": false, "audio": null, "subtitle": null, "volume": null, "muted": false], required: ["item"], longs: ["durationTicks", "positionTicks"], ints: ["audio", "subtitle", "volume"], bools: ["paused", "canSeek", "muted"])
        result["tracks"] = try array(rawTracks).map { try dto(object($0), defaults: ["type": "", "index": 0, "label": ""], required: ["type", "index", "label"], ints: ["index"]) }; return result
    }
    static func target(_ d: [String: Any]) throws -> [String: Any] {
        var result = try dto(d, defaults: ["session": "", "device": "", "name": "", "client": "", "viewer": null, "viewerName": null, "sameViewer": false, "duplicateName": false, "commands": [String](), "now": null], required: ["session", "device", "name"], bools: ["sameViewer", "duplicateName"], arrays: ["commands"], objects: ["now"])
        if let raw = result["now"] as? [String: Any] { result["now"] = try now(raw) }; return result
    }
    static func textRejection(_ text: String?) -> String? {
        guard let text, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return "invalid-text" }
        if text.utf16.count > 256 { return "text-too-long" }
        if text.unicodeScalars.contains(where: { $0.value < 32 || (127...159).contains($0.value) }) { return "invalid-text" }; return nil
    }
}
