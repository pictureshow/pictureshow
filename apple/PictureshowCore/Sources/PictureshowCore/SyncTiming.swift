// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

// SyncPlay clock calibration, command scheduling and drift correction. All arithmetic is checked:
// an overflow is an unavailable answer, never a wrapped clock.

enum SyncMath {
    static let ticksPerMillisecond: Int64 = 10_000
    static func add(_ a: Int64, _ b: Int64) -> Int64? { let r = a.addingReportingOverflow(b); return r.overflow ? nil : r.partialValue }
    static func subtract(_ a: Int64, _ b: Int64) -> Int64? { let r = a.subtractingReportingOverflow(b); return r.overflow ? nil : r.partialValue }
    static func multiply(_ a: Int64, _ b: Int64) -> Int64? { let r = a.multipliedReportingOverflow(by: b); return r.overflow ? nil : r.partialValue }
}

/// UTC milliseconds from one GetUtcTime exchange: t0 local send, t1 server receive, t2 server send, t3 local receive.
public struct SyncClockSample: Equatable, Sendable {
    public let t0Millis: Int64, t1Millis: Int64, t2Millis: Int64, t3Millis: Int64
    public init(t0Millis: Int64, t1Millis: Int64, t2Millis: Int64, t3Millis: Int64) {
        self.t0Millis = t0Millis; self.t1Millis = t1Millis; self.t2Millis = t2Millis; self.t3Millis = t3Millis
    }
    /// Invalid timestamps or overflow are unavailable calibration.
    public func measurement() -> SyncClockMeasurement? {
        guard let local = SyncMath.subtract(t3Millis, t0Millis), local >= 0,
              let processing = SyncMath.subtract(t2Millis, t1Millis), processing >= 0,
              let rtt = SyncMath.subtract(local, processing), (0...1_000).contains(rtt),
              let outward = SyncMath.subtract(t1Millis, t0Millis),
              let inward = SyncMath.subtract(t2Millis, t3Millis),
              let sum = SyncMath.add(outward, inward),
              let offset = SyncMath.multiply(sum, SyncMath.ticksPerMillisecond / 2) else { return nil }
        return SyncClockMeasurement(networkRttMillis: rtt, offsetTicks: offset, uncertaintyTicks: rtt * (SyncMath.ticksPerMillisecond / 2))
    }
}

public struct SyncClockMeasurement: Equatable, Sendable {
    public let networkRttMillis: Int64
    public let offsetTicks: Int64
    /// Half the network RTT; asymmetric delay can leave this much clock uncertainty.
    public let uncertaintyTicks: Int64
}

/// A rolling window of the latest five valid samples; an invalid refresh cannot evict a valid sample.
public struct SyncClockCalibration: Equatable, Sendable {
    public let samples: [SyncClockSample]
    public init(samples: [SyncClockSample] = []) { self.samples = Array(samples.filter { $0.measurement() != nil }.suffix(5)) }
    public var best: SyncClockMeasurement? {
        samples.compactMap { $0.measurement() }.min { $0.networkRttMillis < $1.networkRttMillis }
    }
    public func adding(_ sample: SyncClockSample) -> SyncClockCalibration {
        sample.measurement() == nil ? self : SyncClockCalibration(samples: samples + [sample])
    }
    public func anchor(wallMillis: Int64, monotonicMillis: Int64) -> SyncClockAnchor? {
        guard monotonicMillis >= 0, let measured = best, let wall = SyncTiming.millisecondsToTicks(wallMillis),
              let server = SyncMath.add(wall, measured.offsetTicks) else { return nil }
        return SyncClockAnchor(serverTicksAtAnchor: server, localWallMillis: wallMillis, localMonotonicMillis: monotonicMillis, uncertaintyTicks: measured.uncertaintyTicks)
    }
}

/// An immutable mapping from the local monotonic clock to server time. Recalibrate instead of reusing an invalid one.
public struct SyncClockAnchor: Equatable, Sendable {
    public let serverTicksAtAnchor: Int64
    public let localWallMillis: Int64
    public let localMonotonicMillis: Int64
    public let uncertaintyTicks: Int64
    public init(serverTicksAtAnchor: Int64, localWallMillis: Int64, localMonotonicMillis: Int64, uncertaintyTicks: Int64) {
        self.serverTicksAtAnchor = serverTicksAtAnchor; self.localWallMillis = localWallMillis
        self.localMonotonicMillis = localMonotonicMillis; self.uncertaintyTicks = uncertaintyTicks
    }
    /// nil when the monotonic clock went backwards or the wall clock jumped more than 100 ms against it.
    public func serverNowTicks(wallMillis: Int64, monotonicMillis: Int64) -> Int64? {
        guard let elapsed = SyncMath.subtract(monotonicMillis, localMonotonicMillis), elapsed >= 0,
              let wallElapsed = SyncMath.subtract(wallMillis, localWallMillis),
              let jump = SyncMath.subtract(wallElapsed, elapsed), (-100...100).contains(jump),
              let ticks = SyncTiming.millisecondsToTicks(elapsed) else { return nil }
        return SyncMath.add(serverTicksAtAnchor, ticks)
    }
    public func needsRefresh(monotonicMillis: Int64) -> Bool {
        guard let elapsed = SyncMath.subtract(monotonicMillis, localMonotonicMillis) else { return true }
        return elapsed < 0 || elapsed >= 30_000
    }
}

public struct SyncTarget: Equatable, Sendable {
    public let positionTicks: Int64
    public let delayMillis: Int64
}

public struct SyncDriftState: Equatable, Sendable {
    public var lastSeekMonotonicMillis: Int64?
    public var rateStartedMonotonicMillis: Int64?
    public var rate: Double
    public init(lastSeekMonotonicMillis: Int64? = nil, rateStartedMonotonicMillis: Int64? = nil, rate: Double = 1.0) {
        self.lastSeekMonotonicMillis = lastSeekMonotonicMillis; self.rateStartedMonotonicMillis = rateStartedMonotonicMillis; self.rate = rate
    }
}

public enum SyncDriftAction: String, Sendable { case NONE, RATE, SEEK, WAIT, RESET_RATE }

/// Every decision carries the complete desired rate, including restoration to 1x on seek or wait.
public struct SyncDriftDecision: Equatable, Sendable {
    public let state: SyncDriftState
    public let action: SyncDriftAction
    public let targetTicks: Int64
    public var rate: Double { state.rate }
}

public enum SyncTiming {
    public static func millisecondsToTicks(_ ms: Int64) -> Int64? { SyncMath.multiply(ms, SyncMath.ticksPerMillisecond) }

    /// A failed calibration has no anchor; retry it at a bounded cadence instead of holding forever.
    public static func clockRefreshDue(anchor: SyncClockAnchor?, lastAttemptFinishedMillis: Int64?, monotonicMillis: Int64) -> Bool {
        if let anchor { return anchor.needsRefresh(monotonicMillis: monotonicMillis) }
        guard let finished = lastAttemptFinishedMillis else { return false }
        guard let elapsed = SyncMath.subtract(monotonicMillis, finished) else { return true }
        return elapsed < 0 || elapsed >= 30_000
    }

    /// A generic speed command alone says nothing about an encoded sink's exact-rate support.
    public static func pcmRateSupported(knownPcm: Bool?, softwareRate: Bool, commandAvailable: Bool) -> Bool {
        knownPcm == true && softwareRate && commandAvailable
    }

    /// Future commands wait (rounded up to the next local millisecond), then the caller recalculates at execution.
    /// Only playing commands advance after When.
    public static func target(positionTicks: Int64, whenUtcMillis: Int64, serverNowTicks: Int64, playing: Bool, durationTicks: Int64) -> SyncTarget? {
        guard durationTicks >= 0, let whenTicks = millisecondsToTicks(whenUtcMillis) else { return nil }
        let base = min(max(positionTicks, 0), durationTicks)
        if whenTicks > serverNowTicks {
            guard let wait = SyncMath.subtract(whenTicks, serverNowTicks) else { return nil }
            return SyncTarget(positionTicks: base, delayMillis: wait / SyncMath.ticksPerMillisecond + (wait % SyncMath.ticksPerMillisecond == 0 ? 0 : 1))
        }
        if !playing { return SyncTarget(positionTicks: base, delayMillis: 0) }
        guard let elapsed = SyncMath.subtract(serverNowTicks, whenTicks) else { return nil }
        return SyncTarget(positionTicks: elapsed >= durationTicks - base ? durationTicks : base + elapsed, delayMillis: 0)
    }

    /// Skip a decoder seek only for an Unpause already within the playing tolerance at execution.
    public static func scheduledPosition(command: String, currentTicks: Int64, targetTicks: Int64) -> Int64? {
        if command == "Unpause", currentTicks >= 0, targetTicks >= 0, (-800_000...800_000).contains(currentTicks - targetTicks) { return nil }
        return targetTicks
    }

    /// A future resume can keep an already prepared, paused source instead of seeking at a late wake.
    public static func preparedUnpause(command: String, acceptedFuture: Bool, ready: Bool, buffering: Bool, unpaused: Bool, positionApplicationPending: Bool, currentTicks: Int64, baseTicks: Int64) -> Bool {
        command == "Unpause" && acceptedFuture && ready && !buffering && !unpaused && !positionApplicationPending &&
            currentTicks >= 0 && baseTicks >= 0 && (-800_000...800_000).contains(currentTicks - baseTicks)
    }

    /// A Ready response may freshly emit an Unpause whose positional timeline already took effect.
    public static func sameUnpauseTimeline(applied: SyncCommand?, received: SyncCommand) -> Bool {
        guard let applied else { return false }
        return applied.command == "Unpause" && received.command == "Unpause" && applied.groupId == received.groupId &&
            applied.playlistItemId == received.playlistItemId && applied.whenMs == received.whenMs && applied.whenTicks == received.whenTicks &&
            applied.positionTicks != nil && applied.positionTicks == received.positionTicks
    }

    /// Recheck autonomous correction against live output, advancing its sampled target to execution time.
    public static func correctionPosition(currentTicks: Int64, targetTicks: Int64, playing: Bool, sampledAtMillis: Int64, executionAtMillis: Int64, durationTicks: Int64, resolutionTicks: Int64 = 1) -> Int64? {
        guard currentTicks >= 0, targetTicks >= 0, durationTicks >= 0, sampledAtMillis >= 0, executionAtMillis >= sampledAtMillis, resolutionTicks > 0 else { return nil }
        let base = min(targetTicks, durationTicks)
        let elapsed = executionAtMillis - sampledAtMillis
        let target: Int64
        if !playing { target = base }
        else if elapsed > (durationTicks - base) / SyncMath.ticksPerMillisecond { target = durationTicks }
        else { target = base + elapsed * SyncMath.ticksPerMillisecond }
        let representable = target / resolutionTicks * resolutionTicks
        let difference = min(currentTicks, durationTicks) - representable
        return (playing && (-800_000...800_000).contains(difference)) || difference == 0 ? nil : representable
    }

    /// A phase notification cannot turn an already-applied paused Seek into a playing timeline.
    public static func timelinePlaying(command: String, appliedPlaying: Bool) -> Bool { command == "Unpause" || (command == "Seek" && appliedPlaying) }

    /// Waiting holds output without erasing a pending command's retained mode.
    public static func scheduledPlaying(intendedPlaying: Bool, phase: String) -> Bool { intendedPlaying && phase == "Playing" }

    /// Positive drift means this renderer is ahead. Within 80 ms nothing happens; up to 250 ms an exact 0.97x/1.03x
    /// rate runs for at most five seconds; otherwise a seek, subject to a two-second cooldown. Never sends group requests.
    public static func drift(state: SyncDriftState, currentTicks: Int64, targetTicks: Int64, playing: Bool, nowMonotonicMillis: Int64, durationTicks: Int64, sourceAllowsRate: Bool, engineRates: Set<Double>) -> SyncDriftDecision {
        var reset = state; reset.rateStartedMonotonicMillis = nil; reset.rate = 1.0
        guard durationTicks >= 0, currentTicks >= 0, targetTicks >= 0, nowMonotonicMillis >= 0 else {
            return SyncDriftDecision(state: reset, action: .WAIT, targetTicks: 0)
        }
        let target = min(targetTicks, durationTicks)
        let current = min(currentTicks, durationTicks)
        let drift = current - target
        let small = (-800_000...800_000).contains(drift)
        let wasChangingRate = state.rate != 1.0 || state.rateStartedMonotonicMillis != nil
        let lastSeekElapsed = state.lastSeekMonotonicMillis.map { SyncMath.subtract(nowMonotonicMillis, $0) }
        let rateElapsed = state.rateStartedMonotonicMillis.map { SyncMath.subtract(nowMonotonicMillis, $0) }
        func invalid(_ e: Int64??) -> Bool { if case let .some(value) = e { return value == nil || value! < 0 }; return false }
        if invalid(lastSeekElapsed) || invalid(rateElapsed) {
            return SyncDriftDecision(state: SyncDriftState(), action: .WAIT, targetTicks: target)
        }
        if (playing && small) || (!playing && drift == 0) {
            return SyncDriftDecision(state: reset, action: wasChangingRate ? .RESET_RATE : .NONE, targetTicks: target)
        }
        let requested = drift > 0 ? 0.97 : 1.03
        let eligible = playing && (-2_500_000...2_500_000).contains(drift) && sourceAllowsRate && engineRates.contains(requested)
        let rateRun = rateElapsed ?? nil
        if eligible && (rateRun == nil || rateRun! < 5_000) {
            var next = state
            next.rateStartedMonotonicMillis = state.rateStartedMonotonicMillis ?? nowMonotonicMillis
            next.rate = requested
            return SyncDriftDecision(state: next, action: .RATE, targetTicks: target)
        }
        if let seek = lastSeekElapsed ?? nil, seek < 2_000 {
            return SyncDriftDecision(state: reset, action: .WAIT, targetTicks: target)
        }
        var seeking = reset; seeking.lastSeekMonotonicMillis = nowMonotonicMillis
        return SyncDriftDecision(state: seeking, action: .SEEK, targetTicks: target)
    }

    /// A scheduled transport action that actually seeks starts a fresh correction cooldown; 1x is restored.
    public static func transportApplied(_ state: SyncDriftState, nowMonotonicMillis: Int64, positionChanged: Bool) -> SyncDriftState {
        SyncDriftState(lastSeekMonotonicMillis: positionChanged ? nowMonotonicMillis : state.lastSeekMonotonicMillis, rateStartedMonotonicMillis: nil, rate: 1.0)
    }

    /// An equivalent applied Unpause restores its rate without restarting an active correction window.
    public static func unpauseReplayRate(_ state: SyncDriftState, sameAppliedTimeline: Bool, playing: Bool, sourceAllowsRate: Bool, nowMonotonicMillis: Int64) -> Double {
        guard sameAppliedTimeline, playing, sourceAllowsRate, state.rate == 0.97 || state.rate == 1.03,
              let started = state.rateStartedMonotonicMillis, let elapsed = SyncMath.subtract(nowMonotonicMillis, started) else { return 1.0 }
        return (0..<5_000).contains(elapsed) ? state.rate : 1.0
    }

    /// Stop or Leave discards the old viewing's correction deadlines and restores normal speed.
    public static func stoppedDrift() -> SyncDriftState { SyncDriftState() }

    /// A common group timeline requires finite, seekable server VOD; local copies have no shared server session.
    public static func sourceEligible(durationTicks: Int64, seekable: Bool, live: Bool, infinite: Bool, offline: Bool) -> Bool {
        durationTicks > 0 && seekable && !live && !infinite && !offline
    }
}
