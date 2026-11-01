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
    /// Complement the action label with actual state, without repeating requested intent.
    public static func actionDescription(currentState: String?, pending: Bool, available: Bool, ready: Bool, requested: Bool) -> String? {
        if !available { return "Unavailable while saving progress." }
        if requested || pending { return currentState }
        if ready { return (["Ready"] + [currentState].compactMap { $0 }).joined(separator: ". ") }
        return currentState
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
}

public enum DetailAvailability {
    /// Empty means a complete successful read, including dependent episode and season shelves.
    public static func showEmptyState(itemType: String?, loading: Bool, readSucceeded: Bool, readFailed: Bool, episodeCount: Int, seasonCount: Int) -> Bool {
        ["Series", "Season"].contains(itemType) && !loading && readSucceeded && !readFailed && episodeCount == 0 && seasonCount == 0
    }
}
