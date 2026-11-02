// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct DetailFailurePresentation: Equatable, Sendable {
    public var key: String
    public var message: String
    public init(key: String, message: String) { self.key = key; self.message = message }
}

/// Portable presentation decisions. The app owns focus and announcement delivery.
public enum DetailFeedback {
    /// Actual toggle state only. Availability belongs to native disabled semantics.
    public static func actionDescription(currentState: String?, available: Bool) -> String? {
        available ? currentState : nil
    }

    /// Compatibility for callers using the previous presentation signature.
    public static func actionDescription(currentState: String?, pending: Bool, available: Bool, ready: Bool, requested: Bool) -> String? {
        actionDescription(currentState: currentState, available: available)
    }

    public static func heldActionLabel(target: String) -> String {
        switch target { case "primary": return "Play"; case "from-start": return "Play from start"; case "played": return "Watched"; default: return "Favorite" }
    }

    public static func unavailableActionLabel(target: String) -> String {
        target == "from-start" ? "Start unavailable" : "\(heldActionLabel(target: target)) unavailable"
    }

    /// Transport generations share one error episode until Retry or settlement clears it.
    public static func failureKey(readRecovery: Bool, retrying: Bool, writeAttempt: Int64?) -> String? {
        if readRecovery { return retrying ? nil : "read" }
        return writeAttempt.map { "write:\($0)" }
    }

    /// Freeze the first visible and spoken reason together for each error episode.
    public static func presentFailure(previous: DetailFailurePresentation?, key: String?, message: String?) -> DetailFailurePresentation? {
        guard let key, let message else { return nil }
        return previous?.key == key ? previous : DetailFailurePresentation(key: key, message: message)
    }

    public static func requestIsUrgent(newlyAccepted: Bool, requested: Bool, available: Bool) -> Bool {
        newlyAccepted && requested && available
    }

    public static func catalogArrivalUsesAnimation(accessibilityEnabled: Bool, sourceIsPlayback: Bool, destinationIsCatalog: Bool) -> Bool {
        !accessibilityEnabled || sourceIsPlayback || !destinationIsCatalog
    }

    /// Initial metadata waits for the action destination; retained pages and playback returns do not.
    public static func arrivalAwaitsAction(loading: Bool, contentRevealed: Bool, returningFromPlayback: Bool) -> Bool {
        loading && !contentRevealed && !returningFromPlayback
    }

    public static func metadataAwaitsHandoff(hasActions: Bool, handoffFinished: Bool, returningFromPlayback: Bool, failed: Bool) -> Bool {
        hasActions && !handoffFinished && !returningFromPlayback && !failed
    }

    public static func suppressesEmptyHostFocus(ordinaryArrivalWithActions: Bool, handoffFinished: Bool, returningFromPlayback: Bool, recoveryHandoff: Bool, failed: Bool) -> Bool {
        recoveryHandoff || (!handoffFinished && (returningFromPlayback || (ordinaryArrivalWithActions && !failed)))
    }

    public static func arrivalStillPending(wasPending: Bool, handoffFinished: Bool) -> Bool {
        wasPending && !handoffFinished
    }

}

public enum DetailAvailability {
    /// Empty means a complete successful read, including dependent episode and season shelves.
    public static func showEmptyState(itemType: String?, loading: Bool, readSucceeded: Bool, readFailed: Bool, episodeCount: Int, seasonCount: Int) -> Bool {
        ["Series", "Season"].contains(itemType) && !loading && readSucceeded && !readFailed && episodeCount == 0 && seasonCount == 0
    }
}

/// Construction owns the arrival read. The first appearance observes it; later returns refresh.
public struct DetailReadLifecycle: Sendable {
    private var hasResumed = false
    public init() { }
    public mutating func resumed() -> Bool {
        let refresh = hasResumed
        hasResumed = true
        return refresh
    }
}

/// A readiness cue belongs only to the selected control's current held episode.
public struct DetailReadyFeedback: Sendable {
    private var wasHeld = false
    private var previousFocus: String?
    public init() { }
    public mutating func update(held: Bool, focused: String?, resumed: Bool, usable: Bool = true) -> String? {
        let sameFocus = focused != nil && focused == previousFocus
        let cue: String? = wasHeld && !held && usable && sameFocus && resumed ? "Ready" : nil
        wasHeld = held || (wasHeld && !usable && sameFocus)
        previousFocus = focused
        return cue
    }
}
