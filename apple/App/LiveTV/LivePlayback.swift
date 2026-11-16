// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import PictureshowCore

/// What the live player shows (LIVE-05), measured only from the player's seekable range: never the guide's scheduled
/// duration. AVPlayer's times are absolute on the stream's timeline, so a pause remembers that absolute point, and once
/// the window slides past it the buffer has expired.
struct LivePlayerState: Equatable {
    var window = ObservedLiveWindow(offsetMs: 0, durationMs: 0, defaultPositionMs: 0, seekable: false)
    /// Position relative to the window's start.
    var positionMs: Int64 = 0
    var pausedAbsoluteMs: Int64?
    var paused = false
    var expired = false
    var program: LiveProgram?
    var channel: LiveChannel?

    var canTimeShift: Bool { window.canTimeShift }
    var behindMs: Int64? { expired ? nil : window.behindLiveMs(positionMs) }
    var atEdge: Bool { window.atLiveEdge(positionMs, expired: expired) }
    /// Go live is offered when the viewer is behind the edge or the paused buffer expired.
    var canGoLive: Bool { window.goLivePosition() != nil && (expired || !atEdge) }
    /// The absolute player time of the live edge, in seconds.
    var liveEdgeSeconds: Double? { window.goLivePosition().map { Double(window.offsetMs + $0) / 1000 } }

    /// One sample of the player. `range` is the last seekable range; `liveOffsetSeconds` is how far behind the end of
    /// that range the player keeps itself at the live point (AVPlayerItem's configured or recommended offset).
    mutating func observe(rangeStart: Double?, rangeDuration: Double?, currentSeconds: Double, liveOffsetSeconds: Double?, paused: Bool) {
        if let start = rangeStart, let length = rangeDuration, start.isFinite, length.isFinite, length > 0 {
            let durationMs = Int64(length * 1000)
            let offset = liveOffsetSeconds.flatMap { $0.isFinite && $0 > 0 ? Int64($0 * 1000) : nil } ?? 0
            window = ObservedLiveWindow(offsetMs: Int64(start * 1000), durationMs: durationMs,
                                        defaultPositionMs: max(0, durationMs - min(offset, durationMs)), seekable: true)
        } else {
            window = ObservedLiveWindow(offsetMs: 0, durationMs: 0, defaultPositionMs: 0, seekable: false)
        }
        let absolute = currentSeconds.isFinite ? Int64(currentSeconds * 1000) : nil
        if let absolute { positionMs = absolute - window.offsetMs }
        if paused {
            if !self.paused || pausedAbsoluteMs == nil { pausedAbsoluteMs = absolute }
        } else {
            pausedAbsoluteMs = nil
        }
        self.paused = paused
        expired = paused && window.pausedPositionExpired(pausedAbsoluteMs)
    }

    /// The absolute player time for a requested absolute time: inside the window and never past the live point, so a
    /// forward seek at the edge does nothing and there is no rewind into time the player doesn't hold.
    func clampedSeek(_ seconds: Double) -> Double? {
        guard canTimeShift, let live = window.goLivePosition() else { return nil }
        let relative = min(max(Int64(seconds * 1000) - window.offsetMs, 0), live)
        return Double(window.offsetMs + relative) / 1000
    }
}

extension AVPlayerItem {
    /// The last seekable range, as start and duration in seconds.
    var liveRange: (start: Double, duration: Double)? {
        guard let range = seekableTimeRanges.last?.timeRangeValue, range.start.isNumeric, range.duration.isNumeric else { return nil }
        return (range.start.seconds, range.duration.seconds)
    }

    /// How far behind the end of the seekable range the player sits when it is "live".
    var liveOffsetSeconds: Double? {
        let configured = configuredTimeOffsetFromLive
        if configured.isNumeric, configured.seconds > 0 { return configured.seconds }
        let recommended = recommendedTimeOffsetFromLive
        return recommended.isNumeric && recommended.seconds > 0 ? recommended.seconds : nil
    }
}
