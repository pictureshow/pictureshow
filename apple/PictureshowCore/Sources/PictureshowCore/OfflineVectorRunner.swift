// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CoreFoundation

/// Adapter for the shared Kotlin offline traces. Reducers never read expected projections or fixture identities.
public enum OfflineVectorRunner {
    public static let operations: [String: Set<String>] = [
        "offline-budget": ["budget", "default-cap"], "offline-detail-source": ["detail-source", "detail-start"],
        "offline-fmp4": ["box-header", "parse-moof", "parse-moov", "index-boxes"], "offline-job": ["job", "admit"],
        "offline-manifest": ["blockers", "commit", "reconcile", "decode", "roundtrip", "track-switch", "entry-id", "entry-dir", "safe-path", "preview"],
        "offline-next": ["next-episode"], "offline-outbox": ["outbox", "download-set", "sha256", "device", "resume"],
        "offline-plan": ["presets", "select", "original-request", "video-request", "sidecar-request", "audio-request", "subtitle-request", "cleanup-request"],
        "offline-reconnect": ["reconnect-tracks", "reconnect-decision", "reconnect-track-on-server", "handoff-selection"],
        "offline-resume": ["range-header", "decision", "body-end", "verify", "durable-offset"]
    ]
    public static func reduce(suite: String, operation: String, state: Any, event e: [String: Any]) throws -> [String: Any] {
        guard operations[suite]?.contains(operation) == true else { throw OfflineError.invalidArgument("Unknown offline suite/operation \(suite)/\(operation)") }
        let v = OfflineVectorInput(e)
        switch operation {
        case "job": return try encoded(reduceOfflineJob(decoded(OfflineJob.self, state), decoded(OfflineJobEvent.self, e)))
        case "admit": return ["start": offlineAdmit(activeCount: try v.int("activeCount"), queuedInOrder: try v.value([String].self, "queued"))]
        case "budget":
            let budget = try decoded(OfflineBudget.self, state), op = try v.string("op")
            let result: OfflineBudgetResult
            switch op {
            case "reserve": result = try budget.reserve(job: v.string("job"), bytes: v.long("bytes"), freeBytes: v.optionalLong("freeBytes"))
            case "commit": result = try budget.commit(job: v.string("job"), bytes: v.long("bytes"))
            case "release": result = try budget.release(job: v.string("job"))
            case "discard": result = try budget.discard(job: v.string("job"), committedBytes: v.long("committedBytes"))
            case "setCap": result = try budget.setCap(capBytes: v.optionalLong("capBytes"), jobNewestFirst: v.value([String].self, "jobNewestFirst"))
            default: throw OfflineError.invalidArgument("Unknown budget operation: \(op)")
            }
            return try encoded(result)
        case "default-cap": return ["cap": boxed(offlineDefaultCap(quotaBytes: try v.optionalLong("quotaBytes")))]
        case "range-header": return ["header": boxed(offlineRangeHeader(partialBytes: try v.long("partialBytes"), validator: try v.optional(OfflineValidator.self, "validator")))]
        case "decision": return try encoded(offlineResumeDecision(partialBytes: v.long("partialBytes"), stored: v.optional(OfflineValidator.self, "stored"), response: v.value(OfflineResponse.self, "response")))
        case "body-end": return try encoded(offlineBodyEnd(receivedBytes: v.long("receivedBytes"), totalBytes: v.optionalLong("totalBytes"), cleanEof: v.bool("cleanEof")))
        case "durable-offset": return ["offset": offlineDurableOffset(partialBytes: try v.long("partialBytes"))]
        case "verify": return try encoded(offlineVerify(OfflineVerifyInput(actualBytes: v.long("actualBytes"), expectedBytes: v.optionalLong("expectedBytes"), actualSha256: v.string("actualSha256"), expectedSha256: v.optionalString("expectedSha256"), sourceETagBefore: v.optionalString("sourceETagBefore"), sourceETagNow: v.optionalString("sourceETagNow"), expectedRuntimeTicks: v.optionalLong("expectedRuntimeTicks"), measuredTicks: v.optionalLong("measuredTicks"))))
        case "blockers": return ["blockers": offlineReadyBlockers(entry: try v.value(OfflineEntry.self, "entry"))]
        case "commit": return try encoded(decoded(OfflineManifest.self, state).commitReady(entryId: v.string("entryId"), attempt: v.long("attempt")))
        case "reconcile": return try encoded(decoded(OfflineManifest.self, state).reconcile(inventory: v.value([String: Int64].self, "inventory")))
        case "decode": return ["manifest": try offlineDecodeManifest(text: v.string("text")).map { try encoded($0) } as Any? ?? NSNull()]
        case "roundtrip":
            let manifest = try v.value(OfflineManifest.self, "manifest")
            return ["same": try offlineDecodeManifest(text: offlineEncodeManifest(manifest)) == manifest]
        case "track-switch": return ["action": try v.value(OfflineEntry.self, "entry").trackSwitch(type: v.string("type"), serverIndex: v.optionalInt("serverIndex"))]
        case "reconnect-tracks": return ["indexes": try v.value(OfflineEntry.self, "entry").undownloadedTracks(type: v.string("type")).map(\.serverIndex)]
        case "detail-source":
            let text = try v.string("server")
            guard let server = OfflineServerReach(rawValue: text) else { throw OfflineError.invalidArgument("Unknown server reach: \(text)") }
            return ["source": try offlineDetailPlaySource(ready: v.bool("ready"), server: server, explicitStream: v.bool("explicitStream")).rawValue]
        case "detail-start": return ["startTicks": String(try offlineDetailStartTicks(fromStart: v.bool("fromStart"), outbox: v.optional(OfflineOutboxEntry.self, "outbox"), lastKnown: v.optional(OfflineUserData.self, "lastKnown"), server: v.optional(OfflineUserData.self, "server")))]
        case "next-episode": return ["entry": boxed(try offlineNextEpisode(entries: v.value([OfflineEntry].self, "entries"), current: v.value(OfflineEntry.self, "current"), serverAnswered: v.bool("serverAnswered"), serverNext: v.optionalString("serverNext"))?.id)]
        case "reconnect-decision": return try encoded(offlineReconnectDecision(trackSwitch: v.string("trackSwitch"), network: v.string("network"), facts: OfflineReconnectFacts(server: v.optionalString("server") ?? "unknown", playbackAllowed: v.optionalBool("playbackAllowed"), trackOnServer: v.optionalBool("trackOnServer"))))
        case "reconnect-track-on-server": return ["found": try offlineTrackOnServer(track: v.value(OfflineTrack.self, "track"), sourceId: v.optionalString("sourceId"), sources: v.value([OfflineServerSource].self, "sources"))]
        case "handoff-selection": return try encoded(offlineHandoffSelection(type: v.string("type"), index: v.int("index"), currentAudio: v.optionalInt("currentAudio"), currentSubtitle: v.optionalInt("currentSubtitle")))
        case "entry-id":
            let id = try offlineEntryId(itemId: v.string("itemId"), preset: v.preset())
            let dir = offlineEntryDir(entryId: id)
            return ["id": id, "dir": dir, "dirIsSafe": offlineSafePath(path: dir)]
        case "entry-dir": return ["dir": offlineEntryDir(entryId: try v.string("entryId"))]
        case "safe-path": return ["safe": offlineSafePath(path: try v.string("path"))]
        case "preview": return try encoded(decoded(OfflineManifest.self, state).watchedPreview(played: Set(v.value([String].self, "played"))))
        case "outbox":
            let entry = state is NSNull ? nil : try decoded(OfflineOutboxEntry.self, state)
            let event = try OfflineOutboxEvent(op: v.string("op"), server: v.optionalString("server"), viewer: v.optionalString("viewer"), itemId: v.optionalString("itemId"), positionTicks: v.optionalLong("positionTicks"), played: v.optionalBool("played"), sequence: v.optionalLong("sequence"), current: v.optional(OfflineUserData.self, "current"), base: v.optional(OfflineUserData.self, "base"), status: v.optionalInt("status"))
            return try encoded(reduceOfflineOutbox(entry, event))
        case "resume": return ["ticks": String(try offlineResumeTicks(entry: v.optional(OfflineOutboxEntry.self, "entry"), lastKnown: v.optional(OfflineUserData.self, "lastKnown")))]
        case "download-set":
            guard let object = state as? [String: Any] else { throw OfflineError.invalidArgument("Expected download-set state") }
            return try ControlReducers.reduce(suite: "session-owner", operation: "temporary", state: object, event: e)
        case "sha256": return ["hex": offlineSha256Hex(bytes: Array(try v.string("text").utf8))]
        case "device": return ["device": try offlineDownloadSetDevice(installationDeviceId: v.string("installationDeviceId"), serverId: v.string("serverId"), userId: v.string("userId"))]
        case "presets": return ["choices": try encodedArray(offlinePresets(policy: v.value(OfflinePolicy.self, "policy"), candidate: v.value(OfflineCandidate.self, "candidate"), qualified: Set(v.value([OfflinePreset].self, "qualified"))))]
        case "select": return try encoded(offlineSelect(items: v.value([OfflineCandidate].self, "items"), preset: v.preset()))
        case "original-request": return request(offlineOriginalRequest(itemId: try v.string("itemId")))
        case "video-request", "sidecar-request", "audio-request":
            // Kotlin's refused wrapper catches invalid arguments from conversion request construction only.
            let itemId = try v.string("itemId"), preset = try v.preset(), deviceId = try v.string("deviceId"), session = try v.string("playSessionId")
            let source = try v.optionalString("sourceId")
            if operation != "audio-request" && source == nil { throw OfflineError.invalidArgument("Missing sourceId") }
            let audio = try v.optionalInt("audioStreamIndex")
            if operation == "sidecar-request" && audio == nil { throw OfflineError.invalidArgument("Missing audioStreamIndex") }
            do {
                switch operation {
                case "video-request": return request(try offlineVideoRequest(itemId: itemId, sourceId: source!, preset: preset, audioStreamIndex: audio, deviceId: deviceId, playSessionId: session))
                case "sidecar-request": return request(try offlineSidecarAudioRequest(itemId: itemId, sourceId: source!, preset: preset, audioStreamIndex: audio!, deviceId: deviceId, playSessionId: session))
                default: return request(try offlineAudioRequest(itemId: itemId, sourceId: source, preset: preset, deviceId: deviceId, playSessionId: session))
                }
            } catch OfflineError.invalidArgument { return ["error": true] }
        case "subtitle-request": return request(try offlineSubtitleRequest(itemId: v.string("itemId"), sourceId: v.string("sourceId"), streamIndex: v.int("streamIndex")))
        case "cleanup-request": return request(try offlineCleanupRequest(deviceId: v.string("deviceId"), playSessionId: v.string("playSessionId")))
        case "box-header":
            let h = fmp4BoxHeader(bytes: try v.hex(), length: try v.int("length"))
            return ["header": h.map { ["type": $0.type, "size": $0.size] as [String: Any] } as Any? ?? NSNull()]
        case "parse-moof": return ["fragment": try fmp4ParseMoof(moof: v.hex(), trackId: v.int("trackId"), trexDefaultDuration: v.long("trexDefaultDuration")).map { try encoded($0) } as Any? ?? NSNull()]
        case "parse-moov": return ["track": try fmp4ParseMoov(moov: v.hex()).map { try encoded($0) } as Any? ?? NSNull()]
        case "index-boxes":
            let track = try v.value(Fmp4Track.self, "track"), fragments = try v.value([Fmp4Fragment].self, "fragments"), maxBytes = try v.int("maxBytes")
            do {
                let out = try fmp4IndexBoxes(track: track, fragments: fragments, maxBytes: maxBytes)
                return ["hex": out.map { String(format: "%02x", $0) }.joined(), "bytes": out.count]
            } catch OfflineError.invalidArgument { return ["error": true] }
        default: throw OfflineError.invalidArgument("Unknown offline operation: \(operation)")
        }
    }
    public static func nextState(operation: String, output: [String: Any], previous: Any) throws -> Any {
        let key: String
        switch operation {
        case "job": key = "job"
        case "budget": key = "budget"
        case "outbox": key = "entry"
        case "download-set": return output
        default: return previous
        }
        guard let state = output[key] else { throw OfflineError.invalidArgument("Missing reducer output \(key)") }
        return state
    }
    private static func boxed<T>(_ value: T?) -> Any { value as Any? ?? NSNull() }
    private static func request(_ r: OfflineRequest) -> [String: Any] { ["path": r.path, "query": r.query.map { [$0.name, $0.value] }, "method": r.method] }
    private static func decoded<T: Decodable>(_ type: T.Type, _ value: Any) throws -> T { try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])) }
    private static func encoded<T: Encodable>(_ value: T) throws -> [String: Any] {
        guard let result = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) as? [String: Any] else { throw OfflineError.invalidArgument("Expected encoded object") }
        return result
    }
    private static func encodedArray<T: Encodable>(_ value: [T]) throws -> Any { try JSONSerialization.jsonObject(with: JSONEncoder().encode(value)) }
}

private struct OfflineVectorInput {
    let object: [String: Any]
    init(_ object: [String: Any]) { self.object = object }
    func optionalString(_ key: String) throws -> String? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        guard let text = value as? String else { throw OfflineError.invalidArgument("Expected string: \(key)") }; return text
    }
    func string(_ key: String) throws -> String {
        guard let value = try optionalString(key) else { throw OfflineError.invalidArgument("Missing string: \(key)") }; return value
    }
    func optionalLong(_ key: String) throws -> Int64? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        if let text = value as? String, let long = Int64(text) { return long }
        if let n = value as? NSNumber, CFGetTypeID(n) != CFBooleanGetTypeID(), let long = Int64(n.stringValue) { return long }
        throw OfflineError.invalidArgument("Expected Int64: \(key)")
    }
    func long(_ key: String) throws -> Int64 { guard let value = try optionalLong(key) else { throw OfflineError.invalidArgument("Missing Int64: \(key)") }; return value }
    func optionalInt(_ key: String) throws -> Int? { try optionalLong(key).flatMap { Int(exactly: $0) } }
    func int(_ key: String) throws -> Int { guard let value = try optionalInt(key) else { throw OfflineError.invalidArgument("Missing integer: \(key)") }; return value }
    func optionalBool(_ key: String) throws -> Bool? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { throw OfflineError.invalidArgument("Expected boolean: \(key)") }
        return number.boolValue
    }
    func bool(_ key: String) throws -> Bool { guard let value = try optionalBool(key) else { throw OfflineError.invalidArgument("Missing boolean: \(key)") }; return value }
    func optional<T: Decodable>(_ type: T.Type, _ key: String) throws -> T? {
        guard let value = object[key], !(value is NSNull) else { return nil }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }
    func value<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
        guard let value = try optional(type, key) else { throw OfflineError.invalidArgument("Missing field: \(key)") }; return value
    }
    func preset() throws -> OfflinePreset { guard let preset = OfflinePreset(rawValue: try string("preset")) else { throw OfflineError.invalidArgument("Unknown preset") }; return preset }
    func hex() throws -> [UInt8] {
        let text = try string("hex"), chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { throw OfflineError.invalidArgument("Odd hex length") }
        return try stride(from: 0, to: chars.count, by: 2).map {
            guard let byte = UInt8(String(decoding: chars[$0..<$0 + 2], as: UTF8.self), radix: 16) else { throw OfflineError.invalidArgument("Invalid hex") }; return byte
        }
    }
}
