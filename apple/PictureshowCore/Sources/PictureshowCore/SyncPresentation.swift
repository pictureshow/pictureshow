// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

// SyncPlay presentation helpers: what the panel, chip, player and stay reminder may claim from local facts.

/// A UI estimate from current local facts, never a qualification of another renderer or physical output.
public enum SyncQuality: String, Sendable { case UNKNOWN, IN_SYNC, CATCHING_UP, OUT_OF_SYNC }

/// Group state never identifies a buffering person.
public enum SyncPhase: String, Sendable { case EMPTY, JOINING, WAITING, WAITING_BUFFER, WAITING_SEEK, PLAYING, PAUSED, RECONNECTING, LEAVING, ERROR }

/// Queue lookup responses belong to the exact account, membership and queue revision that requested them.
public struct SyncTitleScope: Equatable, Sendable {
    public let account: String, membership: Int64, revision: Int64
    public init(account: String, membership: Int64, revision: Int64) { self.account = account; self.membership = membership; self.revision = revision }
}

/// A validated current server snapshot can establish queue bounds; presentation defaults cannot.
public struct SyncNavigationAuthority: Equatable, Sendable {
    public let repeatMode: String?, entryIds: [String], currentEntryId: String?
    public init(repeatMode: String?, entryIds: [String], currentEntryId: String?) { self.repeatMode = repeatMode; self.entryIds = entryIds; self.currentEntryId = currentEntryId }
}

public enum SyncPresentation {
    public static func quality(usableClock: Bool, ready: Bool, applied: Bool, errorTicks: Int64?, uncertaintyTicks: Int64?) -> SyncQuality {
        guard usableClock, ready, applied, let error = errorTicks, let uncertainty = uncertaintyTicks, (0...500_000).contains(uncertainty) else { return .UNKNOWN }
        if (-1_000_000...1_000_000).contains(error) { return .IN_SYNC }
        if (-10_000_000...10_000_000).contains(error) { return .CATCHING_UP }
        return .OUT_OF_SYNC
    }

    public static func acceptTitle(request: SyncTitleScope, current: SyncTitleScope, entryIds: Set<String>, requestedEntries: Set<String>) -> Bool {
        request == current && !requestedEntries.isEmpty && requestedEntries.isSubset(of: entryIds)
    }

    public static func phase(_ phase: String, reason: String?) -> SyncPhase {
        switch phase.lowercased() {
        case "joining": return .JOINING
        case "waiting": return reason == "Buffer" ? .WAITING_BUFFER : reason == "Seek" ? .WAITING_SEEK : .WAITING
        case "playing": return .PLAYING
        case "paused": return .PAUSED
        case "reconnecting": return .RECONNECTING
        case "leaving": return .LEAVING
        case "error": return .ERROR
        default: return .EMPTY
        }
    }

    /// The first four names and how many more.
    public static func people(_ names: [String]) -> (names: [String], remaining: Int) { (Array(names.prefix(4)), max(0, names.count - 4)) }

    /// An empty authoritative group queue has no transport target, even while settings remain usable.
    public static func transportAvailable(usable: Bool, queueEntries: Int) -> Bool { usable && queueEntries > 0 }

    /// Only a confirmed Repeat Off boundary removes a direction; all requests remain server-owned.
    public static func directionAvailable(usable: Bool, queueEntries: Int, previous: Bool, navigation: SyncNavigationAuthority?) -> Bool {
        let general = transportAvailable(usable: usable, queueEntries: queueEntries)
        guard general, let navigation, navigation.repeatMode == "RepeatNone" else { return general }
        let entries = navigation.entryIds
        if entries.count != queueEntries || entries.contains(where: { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) || Set(entries).count != entries.count { return general }
        guard let current = navigation.currentEntryId, let index = entries.firstIndex(of: current) else { return general }
        return previous ? index > 0 : index < entries.count - 1
    }

    /// A recovery banner owns its estimate and action; the panel must not duplicate the chip's hint.
    public static func panelQuality(_ quality: SyncQuality, recoveryOffered: Bool) -> SyncQuality { recoveryOffered ? .UNKNOWN : quality }

    /// A glanceable chip omits an unknown estimate rather than reporting a non-fact.
    public static func chipShowsQuality(_ quality: SyncQuality) -> Bool { quality != .UNKNOWN }

    /// An empty authoritative queue has no local playback fact to display.
    public static func showsLocalPlayback(queueEntries: Int) -> Bool { queueEntries > 0 }

    /// Native segmentation supplies grapheme end offsets (UTF-16 lengths here, as String.utf16 counts them) and
    /// measurement supplies `fits`. A name is kept whole, or cut at a grapheme boundary after at least six graphemes.
    public static func chipName(_ name: String, graphemeEnds: [Int], fits: (String) -> Bool) -> String? {
        if fits(name) { return name }
        guard graphemeEnds.count >= 6 else { return nil }
        let units = Array(name.utf16)
        for index in graphemeEnds.indices.reversed() {
            if index < 5 || graphemeEnds[index] == units.count { continue }
            guard graphemeEnds[index] >= 0, graphemeEnds[index] <= units.count else { continue }
            let candidate = String(decoding: units[0..<graphemeEnds[index]], as: UTF16.self) + "…"
            if fits(candidate) { return candidate }
        }
        return nil
    }

    /// Grapheme end offsets in UTF-16 units for `chipName`, from Swift's own segmentation.
    public static func graphemeEnds(_ name: String) -> [Int] {
        var ends: [Int] = [], offset = 0
        for character in name { offset += character.utf16.count; ends.append(offset) }
        return ends
    }

    /// Shared group intent is independent of a decoder temporarily held for synchronization. nil hides the action.
    public static func playerPrimaryAction(participating: Bool, usable: Bool, queueEntries: Int, phase: String, localShowPlayButton: Bool) -> String? {
        if !participating { return localShowPlayButton ? "play" : "pause" }
        if !usable || queueEntries <= 0 { return nil }
        return phase == "Playing" ? "pause" : "play"
    }

    /// A group owns the timeline; personal speed selection resumes after leaving.
    public static func playerSpeedSelection(participating: Bool) -> (selectionAllowed: Bool, explanationRequired: Bool) { (!participating, participating) }
}

public enum SyncCompletionAction: String, Sendable { case HOLD, NEXT, REPEAT, FINISH }

public enum SyncCompletion {
    /// Natural completion is distinct from an explicit Next; the server still owns every continuation.
    public static func action(queue: SyncQueue?, hasSnapshot: Bool, rendererMatches: Bool, unpaused: Bool) -> SyncCompletionAction {
        guard hasSnapshot, rendererMatches, unpaused, let queue, queue.current != nil else { return .HOLD }
        switch queue.repeatMode {
        case "RepeatOne": return .REPEAT
        case "RepeatAll": return .NEXT
        case "RepeatNone": return queue.currentIndex < queue.entries.count - 1 ? .NEXT : .FINISH
        default: return .HOLD
        }
    }
}

/// Facts about this viewer's departure; never infer kick, dissolution or everyone-left.
public enum SyncDepartureCause: String, Sendable { case MANUAL_LEAVE, PLAYBACK_STOPPED, PLAYBACK_ENDED, PLAYBACK_CLOSED, EXTERNAL_ROUTE, MEMBERSHIP_LOST }

public struct SyncDeparture: Equatable, Sendable {
    public let id: Int64, groupName: String?, cause: SyncDepartureCause
    public init(id: Int64, groupName: String?, cause: SyncDepartureCause) { self.id = id; self.groupName = groupName; self.cause = cause }
}

public enum SyncDepartureEvent: Equatable, Sendable {
    case scopeChanged(scope: Int64)
    case membershipStarted(scope: Int64)
    case recorded(scope: Int64, name: String?, cause: SyncDepartureCause)
    case presented(scope: Int64, id: Int64, chooserSession: Int64)
    case dismissed(scope: Int64, id: Int64, chooserSession: Int64)
}

/// `scope` is a distinct account/token epoch, not a request sequence or membership generation.
public struct SyncDepartureState: Equatable, Sendable {
    public var scope: Int64, revision: Int64, pending: SyncDeparture?, presentedIn: Int64?
    public init(scope: Int64 = 0, revision: Int64 = 0, pending: SyncDeparture? = nil, presentedIn: Int64? = nil) {
        self.scope = scope; self.revision = revision; self.pending = pending; self.presentedIn = presentedIn
    }

    /// Pending facts survive hidden time and refresh; only a matching visible chooser dismissal consumes them.
    public func reduce(_ event: SyncDepartureEvent) -> SyncDepartureState {
        var next = self
        switch event {
        case let .scopeChanged(scope):
            if scope != self.scope { next.scope = scope; next.pending = nil; next.presentedIn = nil }
        case let .membershipStarted(scope):
            if scope == self.scope { next.pending = nil; next.presentedIn = nil }
        case let .recorded(scope, name, cause):
            if scope == self.scope {
                next.revision = revision + 1
                let kept = name.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
                next.pending = SyncDeparture(id: next.revision, groupName: kept, cause: cause)
                next.presentedIn = nil
            }
        case let .presented(scope, id, chooser):
            if scope == self.scope, id == pending?.id, chooser >= (presentedIn ?? Int64.min) { next.presentedIn = chooser }
        case let .dismissed(scope, id, chooser):
            if scope == self.scope, id == pending?.id, chooser == presentedIn { next.pending = nil; next.presentedIn = nil }
        }
        return next
    }
}

/// SYN-08: closing the player keeps this viewer in its group. Only an explicit Leave, an external route
/// handoff, a sign-out or the server removes it.
public enum SyncCloseReason: String, Sendable { case BACK, CLOSE, STOP_BUTTON, ERROR_BACK, NATURAL_END, BACKGROUND_IDLE, BACKGROUND_RENDERING, EXTERNAL_ROUTE, MANUAL_LEAVE, SIGN_OUT }
public enum SyncCloseOutcome: String, Sendable { case NONE, STAY, LEAVE }

/// `ignoreWait` asks the server not to hold the group's wait barrier for a device that renders nothing.
/// `departure` is the retained cause for a Leave; a stay records none.
public struct SyncCloseDecision: Equatable, Sendable {
    public let outcome: SyncCloseOutcome, ignoreWait: Bool, departure: SyncDepartureCause?
    public init(_ outcome: SyncCloseOutcome, ignoreWait: Bool = false, departure: SyncDepartureCause? = nil) { self.outcome = outcome; self.ignoreWait = ignoreWait; self.departure = departure }
}

public struct SyncStayFacts: Equatable, Sendable {
    public let groupId: String?, groupName: String?, phase: String, queueEntries: Int, playerOpen: Bool, connected: Bool, leavePending: Bool
    public init(groupId: String?, groupName: String?, phase: String, queueEntries: Int, playerOpen: Bool, connected: Bool, leavePending: Bool) {
        self.groupId = groupId; self.groupName = groupName; self.phase = phase; self.queueEntries = queueEntries
        self.playerOpen = playerOpen; self.connected = connected; self.leavePending = leavePending
    }
}

/// "Still in <group> · Leave", with Watch (rejoin playback) only when there is something to rejoin.
public struct SyncStayReminder: Equatable, Sendable {
    public let groupName: String?, rejoin: Bool, leaveUnconfirmed: Bool
}

public enum SyncStay {
    /// `following` is true while this device renders, or is opening, the group's current entry.
    public static func closeDecision(_ reason: SyncCloseReason, participating: Bool, following: Bool) -> SyncCloseDecision {
        guard participating else { return SyncCloseDecision(.NONE) }
        switch reason {
        case .BACK, .CLOSE, .STOP_BUTTON, .ERROR_BACK, .NATURAL_END: return SyncCloseDecision(.STAY, ignoreWait: following)
        case .BACKGROUND_IDLE: return SyncCloseDecision(.STAY)
        case .BACKGROUND_RENDERING: return SyncCloseDecision(.LEAVE, departure: .MEMBERSHIP_LOST)
        case .EXTERNAL_ROUTE: return SyncCloseDecision(.LEAVE, departure: .EXTERNAL_ROUTE)
        case .MANUAL_LEAVE: return SyncCloseDecision(.LEAVE, departure: .MANUAL_LEAVE)
        case .SIGN_OUT: return SyncCloseDecision(.LEAVE)
        }
    }

    public static func reminder(_ facts: SyncStayFacts) -> SyncStayReminder? {
        if facts.playerOpen { return nil }
        let name = facts.groupName.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        if facts.leavePending { return SyncStayReminder(groupName: name, rejoin: false, leaveUnconfirmed: true) }
        if facts.groupId == nil { return nil }
        let rejoin = facts.connected && facts.queueEntries > 0 && !["Error", "Reconnecting"].contains(facts.phase)
        return SyncStayReminder(groupName: name, rejoin: rejoin, leaveUnconfirmed: false)
    }

    /// NotInGroup or GroupDoesNotExist ends a stayed membership locally with no Leave request;
    /// an open player keeps its existing error and Leave recovery.
    public static func endsOnDenial(reason: String, playerOpen: Bool) -> Bool { !playerOpen && ["NotInGroup", "GroupDoesNotExist"].contains(reason) }

    /// A stayed device never opens a new group entry by itself; Watch on the reminder starts following again.
    public static func opensGroupEntry(following: Bool) -> Bool { following }
}
