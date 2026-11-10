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
    public static func readCanReplaceCompletion(completionItem: String, completionRead: Int64, item: String, read: Int64, succeeded: Bool) -> Bool {
        succeeded && completionItem == item && read > completionRead
    }

    public static func needsPendingControls(loading: Bool, firstArrival: Bool, canQueue: Bool, returningFromPlayback: Bool = false) -> Bool {
        loading && firstArrival && canQueue && !returningFromPlayback
    }

    public static func requestedActionLabel(target: String, newlyAccepted: Bool) -> String {
        guard newlyAccepted else { return "\(heldActionLabel(target: target)) already requested" }
        switch target {
        case "primary": return "Play requested"
        case "from-start": return "Start requested"
        default: return "\(heldActionLabel(target: target)) change requested"
        }
    }

    public static func catalogRevisionNeedsRefresh(previous: Int64?, current: Int64) -> Bool { previous == nil || previous != current }
    public static func readBandText(retrying: Bool, reason: String) -> String { retrying ? "Loading item. Please wait." : reason }
    public static func sessionNeedsRead(newRevision: Bool, refreshed: Bool, container: Bool, alreadyConfirmed: Bool) -> Bool {
        newRevision && (refreshed || container) && !(refreshed && alreadyConfirmed)
    }
    public static func readHasFailure(readFailed: Bool, reason: String?, mutationReady: Bool) -> Bool { reason != nil && (readFailed || !mutationReady) }
    public static func requestCueRemainsActive(active: Bool, readerLeftAction: Bool, requested: Bool = true, presented: Bool = true) -> Bool { active && requested && (!readerLeftAction || !presented) }
    public static func hasConfirmedLeafRead(readSucceeded: Bool, sameUserData: Bool, container: Bool) -> Bool { readSucceeded && sameUserData && !container }
    public static func readRequiresDependents(type: String) -> Bool { ["Series", "Season"].contains(type) }

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

    public static func readyCue(actionLabel: String) -> String { "Ready. \(actionLabel)" }

    public static func catalogArrivalUsesAnimation(accessibilityEnabled: Bool, sourceIsPlayback: Bool, destinationIsCatalog: Bool, returning: Bool = false) -> Bool {
        if returning { return !(sourceIsPlayback && destinationIsCatalog) }
        return !accessibilityEnabled || sourceIsPlayback || !destinationIsCatalog
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

    public static func pendingPresentationVisible(waiting: Bool, resumed: Bool, published: Bool) -> Bool { waiting && resumed && published }
    public static func pendingPresentationLabel(held: Bool, requested: Bool, available: Bool, plain: String, pending: String) -> String { held || requested || !available ? pending : plain }
    public static func heldLabelPresented(selectedWhileHeld: Bool, heldLabelRequested: Bool) -> Bool { selectedWhileHeld && heldLabelRequested }
    public static func feedbackSource(currentPage: Bool, selectedAction: Bool) -> Bool { currentPage && selectedAction }
    public static func quietDepartingChange(handoff: Bool, action: Bool, onlyLabelChanged: Bool) -> Bool { handoff && action && onlyLabelChanged }
    public static func quietRequestRediscovery(action: Bool, onlyLabelChanged: Bool, rediscovery: Bool) -> Bool { action && onlyLabelChanged && rediscovery }
    public static func readerFocusMayClear(currentPage: Bool, ownedHeader: Bool, sameNativeNode: Bool) -> Bool { currentPage && ownedHeader && sameNativeNode }
    public static func retryDescription(reason: String) -> String {
        let reason = reason.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "."))
        return "Retry. Loading failed. \(reason)"
    }
    public static func retryFailureCue(baseline: Int64?, attempt: Int64, loading: Bool, failed: Bool, resumed: Bool) -> Bool {
        baseline.map { attempt > $0 } == true && !loading && failed && resumed
    }
    public static func retryAttemptSucceeded(baseline: Int64, attempt: Int64, loading: Bool, failed: Bool) -> Bool { attempt > baseline && !loading && !failed }
    public static func retryCueCanWait(accessibilityEnabled: Bool, elapsedMillis: Int64) -> Bool { accessibilityEnabled && elapsedMillis < 30_000 }
    public static func completionPresentedPlayed(played: Bool, completionIntent: Bool) -> Bool { played || completionIntent }
    public static func completionIntentRetained(reportFailed: Bool, reportPending: Bool, rawReadSucceeded: Bool) -> Bool { !reportFailed && (reportPending || !rawReadSucceeded) }
    public static func rawReadMatchesAcknowledgement(raw: ItemUserData?, acknowledged: ItemUserData) -> Bool { raw == acknowledged }
    public static func playedRequestNotice(wanted: Bool, current: Bool) -> String? {
        wanted == current ? "Nothing changed. This item is already \(current ? "watched" : "unwatched")." : nil
    }

    /// A viewer choice accepted after a completion report begins supersedes that report.
    public static func completionMayApply(reportChoiceRevision: Int64?, currentChoiceRevision: Int64) -> Bool {
        reportChoiceRevision == currentChoiceRevision
    }

    /// Use known retained UserData for a plain pending label; unknown season state stays neutral.
    public static func pendingPlainLabel(target: String, known: Bool, played: Bool, favorite: Bool) -> String {
        guard known else { return heldActionLabel(target: target) }
        switch target {
        case "played": return played ? "Mark unwatched" : "Mark watched"
        case "favorite": return favorite ? "Unfavorite" : "Favorite"
        default: return heldActionLabel(target: target)
        }
    }

}

/// Captures the control selected during the quiet grace and returns one explicit pending cue.
public struct DetailPendingFeedback: Sendable {
    private var graceTarget: String?
    private var wasSubmitted = false

    public init() {}

    public mutating func update(waiting: Bool, graceExpired: Bool, focused: String?, resumed: Bool, cancelled: Bool) -> String? {
        guard waiting, resumed, !cancelled else {
            graceTarget = nil
            wasSubmitted = false
            return nil
        }
        if !graceExpired { graceTarget = focused }
        return focused.flatMap { target in
            graceExpired && !wasSubmitted && target == graceTarget ? target : nil
        }
    }

    public mutating func submitted() { wasSubmitted = true }
}

public enum DetailAvailability {
    /// Retain a reader target during dependent reads only while the exact season card remains.
    public static func retainsSeasonFocus(selectedSection: String, restored: Bool, semanticsRevealed: Bool, selectedId: String, seasonIds: [String]) -> Bool {
        selectedSection == "seasons" && restored && semanticsRevealed && seasonIds.contains(selectedId)
    }

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
    private var presentedFocus: String?
    public init() { }
    public mutating func update(held: Bool, focused: String?, resumed: Bool, usable: Bool = true, presented: Bool = false, cancelled: Bool = false, nativePresented: String? = nil, submitted: Bool = true) -> String? {
        guard resumed, !cancelled, let focused else {
            wasHeld = false; previousFocus = nil; presentedFocus = nil
            return nil
        }
        let sameFocus = focused == previousFocus
        if !sameFocus { presentedFocus = nil }
        if held && presented { presentedFocus = focused }
        // Native label queries can arrive before the SwiftUI settlement publication.
        // They must name this same selected held episode, never a prefetched peer.
        if sameFocus && wasHeld && nativePresented == focused { presentedFocus = focused }
        let cue: String? = wasHeld && !held && usable && sameFocus && presentedFocus == focused ? "Ready" : nil
        wasHeld = held || (wasHeld && sameFocus && (!usable || (cue != nil && !submitted)))
        if cue != nil && submitted { presentedFocus = nil }
        previousFocus = focused
        return cue
    }
}

/// Request identity belongs to the accepted event, independently of observer restarts.
/// A rejected native submission remains eligible; only a successful one consumes it.
public struct DetailRequestOnceFeedback: Sendable {
    private var consumed: Set<Int64> = []
    public init() { }
    public mutating func update(request: Int64?, eligible: Bool, submitted: Bool = true) -> Bool {
        guard let request, eligible, !consumed.contains(request) else { return false }
        if submitted { consumed.insert(request) }
        return true
    }
}

public enum PlaybackVisibility {
    public static func playWhenReady(requested: Bool, foregroundRequired: Bool, foreground: Bool, pauseLatched: Bool) -> Bool {
        requested && !pauseLatched && (!foregroundRequired || foreground)
    }
}
