// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Shared spec cases the Apple core doesn't execute yet: every case in spec/vectors that SpecCaseManifest doesn't list.
///
/// Derived when the tests load, so a merge that adds vectors on main needs no edit here: new cases are pending until an
/// Apple port moves them into SpecCaseManifest. Only the reasons below are written by hand. Expected results stay in
/// spec/vectors.
enum PendingSpecCaseManifest {
    struct Suite {
        let count: Int
        let reason: String
    }

    /// The reason a suite first seen on main gets; SpecVectorTests skips envelope checks it hasn't learned for these.
    static let defaultReason = "Merged on main after the Apple core was last synced; not ported to Apple yet."

    /// Why a suite still waits on Apple, where that is known more precisely than `defaultReason`.
    static let reasons: [String: String] = [
        "application": "Additional main contracts lack an Apple executor; existing active contracts in this suite continue to execute.",
        "cast-capability": "Audio reload capability awaits the Apple sender port.",
        "cast-handoff": "System commands and receiver intent reconciliation await the Apple sender port.",
        "cast-queue": "DMR queue identity and audio reload contracts await the Apple sender port.",
        "cast-session": "Pending audio reload reattachment awaits the Apple sender port.",
        "frame-rate-match": "Display-mode policy and native display qualification are not ported to Apple.",
        "live-dvr": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "live-guide": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "live-presentation": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "live-source": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "playback-settings": "The merged playback tuning, network and preference policies are not ported to Apple.",
        "playback-settings-layout": "These eight measured Android Compose layouts require native Apple UI acceptance; a pure core answer would not prove them.",
        "playback-start": "The merged start-origin and rewind policy is not ported to Apple.",
        "player-overlay-layout": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "player-quality": "The merged per-viewing quality selection policy is not ported to Apple.",
        "seerr": "Seerr requests (leg 163, Android) are not ported to Apple: client, account-bound encrypted store and request screens.",
        "reference": "Additional main contracts lack an Apple executor; existing active contracts in this suite continue to execute.",
        "settings-presentation": "The merged advanced playback setting labels and consequences are not ported to Apple.",
        "sync-completion-reporting": "Merged on main after the Apple core was last synced; core ported, needs the Apple playback host's report pipeline (Android TouchPlaybackHostTest).",
        "sync-group-social-summary-and-picker-role": "Merged on main after the Apple core was last synced; core ported, needs native measured layout, paint and focus of the Watch together surfaces.",
        "sync-phone-player-chrome": "Merged on main after the Apple core was last synced; core ported, needs native iPhone player chrome rendering.",
        "sync-phone-quality-cue": "Merged on main after the Apple core was last synced; core ported, needs native iPhone quality-cue rendering.",
        "sync-phone-remote-chrome": "Merged on main after the Apple core was last synced; core ported, needs native iPhone remote chrome rendering.",
        "sync-phone-remote-search": "Merged on main after the Apple core was last synced; core ported, needs native iPhone remote search rendering.",
        "sync-phone-surfaces": "Merged on main after the Apple core was last synced; core ported, needs native iPhone Watch together surfaces.",
        "sync-rate-presentation": "Merged on main after the Apple core was last synced; core ported, needs the Apple player's viewer-speed state fed by real rate reports (Android PlaybackSpeedTest).",
        "sync-route-consent": "Merged on main after the Apple core was last synced; core ported, needs the Apple SyncPlay host's route-consent script (Android SyncPlayHostTest); vectors carry no inputs.",
        "sync-speed-intent-origin": "Merged on main after the Apple core was last synced; core ported, needs the Apple player's speed-intent origin tracking.",
        "sync-tv-attention": "Merged on main after the Apple core was last synced; core ported, needs native tvOS rendering and focus.",
        "sync-tv-player-coherence": "Merged on main after the Apple core was last synced; core ported, needs native tvOS player rendering.",
        "sync-tv-rhythm": "Merged on main after the Apple core was last synced; core ported, needs native tvOS layout measurement.",
        "sync-tv-structure-discovery": "Merged on main after the Apple core was last synced; core ported, needs native tvOS focus and structure.",
        "sync-tv-surfaces": "Merged on main after the Apple core was last synced; core ported, needs native tvOS Watch together surfaces.",
    ]

    /// Every shared case as `id → suite/operation` (live-source cases are `observed-window`, cases without an operation
    /// are `case`), read from spec/vectors.
    static let vectorContracts: [String: String] = {
        let vectors = RemoteVectorRunner.specRoot().appendingPathComponent("vectors")
        var contracts: [String: String] = [:]
        let files = (FileManager.default.enumerator(at: vectors, includingPropertiesForKeys: nil)?.compactMap { $0 as? URL } ?? [])
            .filter { $0.pathExtension == "json" }.sorted { $0.path < $1.path }
        for file in files {
            guard let data = try? Data(contentsOf: file), let document = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
                  let suite = document["suite"] as? String, let cases = document["cases"] as? [[String: Any]] else { continue }
            for vector in cases {
                guard let id = vector["id"] as? String, contracts[id] == nil else { continue }
                contracts[id] = suite + "/" + (suite == "live-source" ? "observed-window" : (vector["operation"] as? String ?? "case"))
            }
        }
        return contracts
    }()

    static let contracts: [String: String] = vectorContracts.filter { SpecCaseManifest.contracts[$0.key] == nil }

    static let suites: [String: Suite] = Dictionary(grouping: contracts.values) { String($0.prefix { $0 != "/" }) }
        .reduce(into: [:]) { result, entry in result[entry.key] = Suite(count: entry.value.count, reason: reasons[entry.key] ?? defaultReason) }

    static func validate(id: String, suite: String, operation: String) throws {
        guard contracts[id] == suite + "/" + operation else {
            throw NSError(domain: "Unknown pending vector ID or mismatched operation: \(id)", code: 1)
        }
    }

    static func validateEnvelope(_ vector: [String: Any], suite: String) throws {
        if suite == "live-source" {
            guard vector["operation"] == nil, vector["input"] == nil, vector["state"] == nil, vector["steps"] == nil,
                  vector["window"] is [String: Any], vector["positionMs"] is NSNumber,
                  vector["pausedAbsoluteMs"] is NSNumber || vector["pausedAbsoluteMs"] is NSNull,
                  vector["expected"] is [String: Any] else {
                throw NSError(domain: "Malformed pending observed live-window vector", code: 1)
            }
        } else if suite.hasPrefix("offline-") || ["cast-capability", "cast-session", "cast-handoff", "cast-queue"].contains(suite) {
            let permitsNoRecord = suite == "offline-outbox" && (vector["operation"] as? String) == "outbox"
            guard vector["state"] is [String: Any] || (permitsNoRecord && vector["state"] is NSNull),
                  let steps = vector["steps"] as? [[String: Any]], !steps.isEmpty,
                  vector["input"] == nil, vector["expected"] == nil else {
                throw NSError(domain: "Malformed pending trace vector", code: 1)
            }
            for step in steps {
                guard step["event"] is [String: Any], let expected = step["expected"] as? [String: Any], !expected.isEmpty else {
                    throw NSError(domain: "Malformed pending trace vector step", code: 1)
                }
            }
        } else {
            guard vector["input"] is [String: Any], vector["expected"] != nil,
                  vector["state"] == nil, vector["steps"] == nil else {
                throw NSError(domain: "Malformed pending policy or layout vector", code: 1)
            }
        }
    }
}
