// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum SeekSteps {
    public static func target(positionMs: Int64, pendingMs: Int64?, deltaMs: Int64, durationMs: Int64) -> Int64 {
        let duration = max(0, durationMs)
        let base = min(duration, max(0, pendingMs ?? positionMs))
        return min(duration, max(0, Ticks.adding(base, deltaMs)))
    }
}

/// The fields describe the actual immutable body, with independent viewer intent.
public enum PlaybackReportFields {
    public static func describe(kind: String, positionTicks: Int64, audio: Int?, subtitle: Int?, audioViewer: Bool, subtitleViewer: Bool) -> String {
        precondition(["start", "progress", "stop"].contains(kind))
        let a = kind == "stop" ? nil : audio, s = kind == "stop" ? nil : subtitle
        return "kind=\(kind) positionMs=\(positionTicks / 10_000) audio=\(a.map(String.init) ?? "omitted") subtitle=\(s.map(String.init) ?? "omitted") audioOrigin=\(audioViewer ? "viewer" : "automatic") subtitleOrigin=\(subtitleViewer ? "viewer" : "automatic")"
    }
}

public struct PlaybackHTTPOutcome: Equatable, Sendable {
    public let status: Int?
    public let failure: String
    public let elapsedMs: Int64
    public var succeeded: Bool { failure == "none" }
    public init(status: Int?, failure: String, elapsedMs: Int64) {
        self.status = status; self.failure = failure; self.elapsedMs = max(0, elapsedMs)
    }
}
