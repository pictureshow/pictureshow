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
        "live-presentation": "Android-only Compose geometry and constants (guide tick, lane, ruler and channel-column dp; focus-shift and guide-scroll bring-into-view; recording shelf pivot, tail and caption height; compact height; disclosure rotation; content inset; quality-menu scroll cues; seek glyph alphas; review-surface, label and mutation-slot dp), the Android TV review header's Left/Right event script, and Android touch-tab routing. The native Apple Live TV views own these measurements; every logic operation of the suite executes.",
        "playback-settings": "The merged playback tuning, network and preference policies are not ported to Apple.",
        "playback-settings-layout": "These eight measured Android Compose layouts require native Apple UI acceptance; a pure core answer would not prove them.",
        "playback-start": "The merged start-origin and rewind policy is not ported to Apple.",
        "player-overlay-layout": "Newly merged main policy or presentation contracts are not ported to Apple; native acceptance remains owed.",
        "player-quality": "The merged per-viewing quality selection policy is not ported to Apple.",
        "reference": "Additional main contracts lack an Apple executor; existing active contracts in this suite continue to execute.",
        "settings-presentation": "The merged advanced playback setting labels and consequences are not ported to Apple.",
        "sync-group-social-summary-and-picker-role": "Apple executes the five native-direction cases other than none-middle and both picker-role cases (SyncAdapterVectorRunner). Pending: phone-empty-* (3) and phone-joined-shared-playback-scope-* (2) assert Android's measured arrival layout (whole count above the footer, no rescue scroll, 1 px geometry, 48 dp hits, 8 dp footer gap, measured reading order); tv-joined-name-count-first-baseline-* (2) assert a 1 px baseline at 156 dpi and first focus on Ignore wait; native-direction-none-middle asserts TV focus leaving a disabled Next. Apple has no native SwiftUI layout/focus measurement harness for these yet.",
        "sync-phone-player-chrome": "All ten profiles measure Android's touch player chrome: gradient paint samples, seek-row 1 px alignment, 48 dp targets by Android tag (touch:open:Speed, touch:orientation), the Quality detail row, grouped-overlay clearance against the measured chip and the live header's right edge. Apple's iPhone player has a different SwiftUI chrome (no Speed sheet, no Quality context) and no native measurement harness.",
        "sync-phone-quality-cue": "Apple's player shows no adaptive-quality fact or changed-quality cue (AVPlayer picks HLS variants internally and the app has no Quality context), so there is no cue lifetime, priority, reset or Quality detail to execute.",
        "sync-phone-remote-chrome": "Measures Android's phone remote screen (64 dp status bar, 24 dp TV icon, divider coverage, scroll-thumb paint, 48 dp exit targets) and its duplicate-name label 'name (ab12)'. Apple's remote (RemoteViews) has no duplicate-name disambiguation or status bar of that shape and no layout harness.",
        "sync-phone-remote-search": "Apple's phone remote has no send-text (SendString) search field yet, so the guarded keyboard submit, the 256-character validation and the helper text have nothing to run.",
        "sync-phone-surfaces": "Compact touch initial-transport reveal: Android's measured window (590 dp wide, 280/360 dp high), fade clearance, scroll offsets and the Speed reason placement in its group panel. Apple's Watch together sheet is a SwiftUI Form with no compact player-panel reveal and no speed control; needs a native layout harness.",
        "sync-rate-presentation": "Apple's player has no viewer playback-speed selection (PlaybackSpeedState is in the core but no app surface sets a speed) and the engine advertises no temporary 0.97/1.03 rates (drift is corrected by seeking, SYN-04/05), so neither a 1.5x viewer speed nor a temporary rate can occur. Owed with PLAY-25 in the Apple player.",
        "sync-speed-intent-origin": "Apple's player has no viewer speed selection and AVPlayer gives the app no audio-output maintenance callback that reapplies a native rate (no PCM/encoded output parameters), so neither origin in these profiles exists on Apple. Owed with PLAY-25 in the Apple player.",
        "sync-tv-attention": "Measured TV paint and focus of Android's chooser (theme tokens, 3:1 focused-versus-rest fill contrast, cold-empty first focus with no implicit keyboard, measured reading order with 12 dp gaps). tvOS draws focus through the system focus engine; Apple has no harness that measures it.",
        "sync-tv-player-coherence": "Apple executes both native-tv-roster cases (the count text through participant changes, no group commands). Pending: native-tv-actions-one-row-* and native-tv-safe-edges-* measure Android's own TV player action row (one row within 1 px, 48 dp targets and insets, glyph bearing, title ink). Apple TV's transport bar is AVPlayerViewController's system UI, which the app doesn't lay out.",
        "sync-tv-rhythm": "Pixel measurements at 156 dpi of Android's TV group panel (12 dp preview/rule and setting gaps, plate coverage, typography tokens bodyLarge/titleSmall). No equivalent Apple layout or measurement harness.",
        "sync-tv-structure-discovery": "Profiles measure Android's panel: personal and group headings with measured reading order and first focus, the forward-scroll indicator's contrast, 12 dp painted gaps, the phone footer divider, Android's Settings order (settings:syncplay before settings:supporter) and empty-transport focus. empty-transport-* also render a presentation without an authoritative snapshot (both directions enabled for one entry); Apple's sheet derives transport only from the engine's snapshot, where one Repeat Off entry disables both directions.",
        "sync-tv-surfaces": "Pixel paint and IME/back routing of Android's TV chooser and repeat control (focus-mark thickness and contrast, divider channel deltas, glyph ink, 40 dp bottom fade mask, back after a zero-inset IME). tvOS edits text on the system keyboard screen with the system focus engine; no Apple harness.",
        "touch-player-director-clearance-paint-edges": "Android touch-player geometry and paint: free-band clearance, seek-target backing over white video, ink right edges, chip/skip/next-card clearance in portrait, landscape and RTL, and the conversion notice's action band. Apple's iPhone player is a different SwiftUI layout without a measurement harness.",
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
