// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

// SyncPlay wire parsing and the scoped group/membership reducer (spec/features/syncplay.md).
// The app feeds every `SyncPlayCommand` / `SyncPlayGroupUpdate` socket text to `SyncPlay.parseMessage`,
// then `SyncMembership.reduce(_:generation:)` with the generation it owns; it acts on the resulting
// phase, queue and accepted `command`.

/// IDs belong to this server. Participants are only the names the authorized server returned.
public struct SyncGroup: Equatable, Sendable {
    public var id: String
    public var name: String
    public var state: String
    public var participants: [String]
    public var lastUpdatedTicks: Int64
    public init(id: String, name: String, state: String, participants: [String], lastUpdatedTicks: Int64) {
        self.id = id; self.name = name; self.state = state; self.participants = participants; self.lastUpdatedTicks = lastUpdatedTicks
    }
}

public struct SyncQueueEntry: Equatable, Sendable {
    public var itemId: String
    public var playlistItemId: String
    public init(itemId: String, playlistItemId: String) { self.itemId = itemId; self.playlistItemId = playlistItemId }
}

public struct SyncQueue: Equatable, Sendable {
    public var entries: [SyncQueueEntry]
    public var currentIndex: Int
    public var startPositionTicks: Int64
    public var isPlaying: Bool
    public var repeatMode: String
    public var shuffleMode: String
    public var lastUpdateTicks: Int64
    public init(entries: [SyncQueueEntry], currentIndex: Int, startPositionTicks: Int64, isPlaying: Bool, repeatMode: String, shuffleMode: String, lastUpdateTicks: Int64) {
        self.entries = entries; self.currentIndex = currentIndex; self.startPositionTicks = startPositionTicks; self.isPlaying = isPlaying
        self.repeatMode = repeatMode; self.shuffleMode = shuffleMode; self.lastUpdateTicks = lastUpdateTicks
    }
    public var current: SyncQueueEntry? { entries.indices.contains(currentIndex) ? entries[currentIndex] : nil }
}

public struct SyncCommand: Equatable, Sendable {
    public var groupId: String
    public var playlistItemId: String
    /// Unpause, Pause, Seek or Stop.
    public var command: String
    public var whenMs: Int64
    public var positionTicks: Int64?
    public var emittedAtTicks: Int64
    /// Parsed wire commands retain 100 ns execution identity independently of millisecond scheduling.
    public var whenTicks: Int64?
    public init(groupId: String, playlistItemId: String, command: String, whenMs: Int64, positionTicks: Int64?, emittedAtTicks: Int64, whenTicks: Int64? = nil) {
        self.groupId = groupId; self.playlistItemId = playlistItemId; self.command = command; self.whenMs = whenMs
        self.positionTicks = positionTicks; self.emittedAtTicks = emittedAtTicks; self.whenTicks = whenTicks
    }
}

public enum SyncEvent: Equatable, Sendable {
    case joined(groupId: String, messageId: String?, group: SyncGroup)
    case queue(groupId: String, messageId: String?, queue: SyncQueue)
    case state(groupId: String, messageId: String?, state: String, reason: String)
    case participant(groupId: String, messageId: String?, name: String, joined: Bool)
    case left(groupId: String, messageId: String?)
    case denied(groupId: String?, messageId: String?, reason: String)
    case command(groupId: String, messageId: String?, command: SyncCommand)
    /// A recognized SyncPlay message that could not be read; the owner resnapshots paused.
    case invalid(groupId: String?, messageId: String?)

    public var groupId: String? {
        switch self {
        case let .joined(g, _, _), let .queue(g, _, _), let .state(g, _, _, _), let .participant(g, _, _, _), let .left(g, _), let .command(g, _, _): return g
        case let .denied(g, _, _), let .invalid(g, _): return g
        }
    }
    public var messageId: String? {
        switch self {
        case let .joined(_, m, _), let .queue(_, m, _), let .state(_, m, _, _), let .participant(_, m, _, _), let .left(_, m),
             let .denied(_, m, _), let .command(_, m, _), let .invalid(_, m): return m
        }
    }
}

private struct SyncWireError: Error {}

public enum SyncPlay {
    static let states: Set<String> = ["Idle", "Waiting", "Paused", "Playing"]
    static let commands: Set<String> = ["Unpause", "Pause", "Seek", "Stop"]
    static let repeatModes: Set<String> = ["RepeatNone", "RepeatOne", "RepeatAll"]
    static let shuffleModes: Set<String> = ["Sorted", "Shuffle"]

    private static func require(_ condition: Bool) throws { if !condition { throw SyncWireError() } }
    private static func text(_ o: SyncJSON, _ name: String) -> String? { o[name]?.content }
    private static func requiredText(_ o: SyncJSON, _ name: String) throws -> String {
        guard let value = text(o, name), !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { throw SyncWireError() }
        return value
    }
    private static func object(_ o: SyncJSON, _ name: String) throws -> SyncJSON {
        guard let value = o[name], value.isObject else { throw SyncWireError() }
        return value
    }

    public static func parseGroup(_ value: SyncJSON) throws -> SyncGroup {
        let id = try requiredText(value, "GroupId")
        guard let name = text(value, "GroupName") else { throw SyncWireError() }
        let state = try requiredText(value, "State")
        try require(states.contains(state))
        guard let names = value["Participants"]?.items else { throw SyncWireError() }
        let participants = try names.map { name -> String in guard let t = name.content else { throw SyncWireError() }; return t }
        guard let updated = timestampTicks(try requiredText(value, "LastUpdatedAt")) else { throw SyncWireError() }
        return SyncGroup(id: id, name: name, state: state, participants: participants, lastUpdatedTicks: updated)
    }

    public static func parseQueue(_ value: SyncJSON) throws -> SyncQueue {
        guard let rows = value["Playlist"]?.items else { throw SyncWireError() }
        let entries = try rows.map { row -> SyncQueueEntry in
            guard row.isObject else { throw SyncWireError() }
            return SyncQueueEntry(itemId: try requiredText(row, "ItemId"), playlistItemId: try requiredText(row, "PlaylistItemId"))
        }
        try require(Set(entries.map(\.playlistItemId)).count == entries.count)
        guard let index = value["PlayingItemIndex"]?.int32 else { throw SyncWireError() }
        try require(entries.isEmpty ? (-1...0).contains(index) : entries.indices.contains(index))
        guard let start = value["StartPositionTicks"]?.int64, start >= 0 else { throw SyncWireError() }
        guard let playing = value["IsPlaying"]?.bool else { throw SyncWireError() }
        let repeatMode = try requiredText(value, "RepeatMode"); try require(repeatModes.contains(repeatMode))
        let shuffleMode = try requiredText(value, "ShuffleMode"); try require(shuffleModes.contains(shuffleMode))
        guard let updated = timestampTicks(try requiredText(value, "LastUpdate")) else { throw SyncWireError() }
        return SyncQueue(entries: entries, currentIndex: index, startPositionTicks: start, isPlaying: playing, repeatMode: repeatMode, shuffleMode: shuffleMode, lastUpdateTicks: updated)
    }

    static let denials: Set<String> = ["NotInGroup", "GroupDoesNotExist", "LibraryAccessDenied", "CreateGroupDenied", "JoinGroupDenied"]

    /// Guid.Empty in either of Jellyfin's spellings: "N" (32 zeros) or dashed.
    public static func emptyGroupId(_ value: String) -> Bool { !value.isEmpty && value.allSatisfy { $0 == "0" || $0 == "-" } }

    /// Unrelated socket messages return nil. A malformed or unknown SyncPlay message returns `.invalid`,
    /// which the reducer turns into a paused resnapshot.
    public static func parseMessage(_ text: String) -> SyncEvent? {
        guard let envelope = try? SyncJSON.parse(text), envelope.isObject, let type = Self.text(envelope, "MessageType") else { return nil }
        guard type == "SyncPlayCommand" || type == "SyncPlayGroupUpdate" else { return nil }
        var id: String?
        var group: String?
        do {
            id = Self.text(envelope, "MessageId").flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
            let data = try object(envelope, "Data")
            // Jellyfin sends its "no group" answers (GroupDoesNotExist after a Join, NotInGroup) with an all-zero GroupId,
            // which names no group: without this a session whose group ended while it was away never hears it (measured 12.0).
            group = Self.text(data, "GroupId").flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || emptyGroupId($0) ? nil : $0 }
            if type == "SyncPlayGroupUpdate", let reason = Self.text(data, "Type"), denials.contains(reason) {
                return .denied(groupId: group, messageId: id, reason: reason)
            }
            guard let groupId = group else { throw SyncWireError() }
            if type == "SyncPlayCommand" {
                let command = try requiredText(data, "Command")
                try require(commands.contains(command))
                let ticks = data["PositionTicks"]?.int64
                try require(ticks == nil || ticks! >= 0)
                try require(command == "Stop" || ticks != nil)
                let entry = try requiredText(data, "PlaylistItemId")
                let when = try requiredText(data, "When")
                guard let whenMs = timestampMillis(when), let emitted = timestampTicks(try requiredText(data, "EmittedAt")),
                      let whenTicks = timestampTicks(when) else { throw SyncWireError() }
                return .command(groupId: groupId, messageId: id, command: SyncCommand(groupId: groupId, playlistItemId: entry, command: command, whenMs: whenMs, positionTicks: ticks, emittedAtTicks: emitted, whenTicks: whenTicks))
            }
            let updateType = try requiredText(data, "Type")
            switch updateType {
            case "GroupJoined":
                let info = try parseGroup(try object(data, "Data"))
                try require(info.id == groupId)
                return .joined(groupId: groupId, messageId: id, group: info)
            case "PlayQueue":
                return .queue(groupId: groupId, messageId: id, queue: try parseQueue(try object(data, "Data")))
            case "StateUpdate":
                let state = try object(data, "Data")
                let phase = try requiredText(state, "State")
                try require(states.contains(phase))
                return .state(groupId: groupId, messageId: id, state: phase, reason: try requiredText(state, "Reason"))
            case "UserJoined", "UserLeft":
                guard let name = data["Data"]?.content else { throw SyncWireError() }
                return .participant(groupId: groupId, messageId: id, name: name, joined: updateType == "UserJoined")
            case "GroupLeft":
                return .left(groupId: groupId, messageId: id)
            default:
                return .invalid(groupId: groupId, messageId: id)
            }
        } catch {
            return .invalid(groupId: group, messageId: id)
        }
    }

    /// RFC 3339 timestamps keep 100 ns ordering even when two events fall within one millisecond.
    public static func timestampTicks(_ text: String) -> Int64? {
        let pattern = "^([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,7}))?(Z|[+-][0-9]{2}:[0-9]{2})$"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) else { return nil }
        func group(_ i: Int) -> String { Range(match.range(at: i), in: text).map { String(text[$0]) } ?? "" }
        guard let year = Int64(group(1)), let month = Int(group(2)), let day = Int64(group(3)), let hour = Int64(group(4)),
              let minute = Int64(group(5)), let second = Int64(group(6)) else { return nil }
        guard (1...9999).contains(year), (1...12).contains(month), (0...23).contains(hour), (0...59).contains(minute), (0...59).contains(second) else { return nil }
        let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        let monthDays: [Int64] = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        guard (1...monthDays[month - 1]).contains(day) else { return nil }
        let previous = year - 1
        let days = previous * 365 + previous / 4 - previous / 100 + previous / 400 + monthDays.prefix(month - 1).reduce(0, +) + day - 1 - 719_162
        let zone = group(8)
        var offset: Int64 = 0
        if zone != "Z" {
            let chars = Array(zone)
            guard let zh = Int64(String(chars[1...2])), let zm = Int64(String(chars[4...5])), (0...23).contains(zh), (0...59).contains(zm) else { return nil }
            offset = (zh * 3600 + zm * 60) * (chars[0] == "-" ? -1 : 1)
        }
        let fraction = group(7)
        let padded = fraction + String(repeating: "0", count: 7 - fraction.count)
        guard let hundreds = Int64(padded) else { return nil }
        return (days * 86400 + hour * 3600 + minute * 60 + second - offset) * 10_000_000 + hundreds
    }

    public static func timestampMillis(_ text: String) -> Int64? {
        timestampTicks(text).map { $0 >= 0 ? $0 / 10_000 : ($0 - 9999) / 10_000 }
    }

    /// UTC formatting used by readiness requests; covers negative epochs and day boundaries.
    public static func utcTimestamp(_ epochMillis: Int64) -> String {
        precondition((-62_135_596_800_000...253_402_300_799_999).contains(epochMillis))
        let seconds = epochMillis >= 0 ? epochMillis / 1000 : (epochMillis - 999) / 1000
        let millis = epochMillis - seconds * 1000
        let days = seconds >= 0 ? seconds / 86400 : (seconds - 86399) / 86400
        let daySeconds = seconds - days * 86400
        let ordinal = days + 719_162
        func beforeYear(_ year: Int64) -> Int64 { let p = year - 1; return p * 365 + p / 4 - p / 100 + p / 400 }
        var low: Int64 = 1, high: Int64 = 10000
        while low + 1 < high { let mid = (low + high) / 2; if beforeYear(mid) <= ordinal { low = mid } else { high = mid } }
        let year = low
        var dayOfYear = ordinal - beforeYear(year)
        let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        let months: [Int64] = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        var month = 0
        while dayOfYear >= months[month] { dayOfYear -= months[month]; month += 1 }
        func pad(_ v: Int64, _ n: Int = 2) -> String { let s = String(v); return String(repeating: "0", count: max(0, n - s.count)) + s }
        return "\(pad(year, 4))-\(pad(Int64(month + 1)))-\(pad(dayOfYear + 1))T\(pad(daySeconds / 3600)):\(pad(daySeconds / 60 % 60)):\(pad(daySeconds % 60)).\(pad(millis, 3))Z"
    }
}

/// Generation is supplied by the socket owner, never trusted from an event payload.
public struct SyncMembership: Equatable, Sendable {
    public var generation: Int64 = 0
    public var groupId: String?
    /// Idle, Joining, Waiting, Paused, Playing, Reconnecting, Leaving or Error.
    public var phase: String = "Idle"
    public var group: SyncGroup?
    public var queue: SyncQueue?
    /// The command accepted by the latest event; the app schedules it, then it clears on the next event.
    public var command: SyncCommand?
    public var lastCommandTicks: Int64?
    public var lastAcceptedCommand: SyncCommand?
    public var observedState: String?
    public var seenMessageIds: [String] = []
    public var notice: String?

    public init(generation: Int64 = 0, groupId: String? = nil, phase: String = "Idle") {
        self.generation = generation; self.groupId = groupId; self.phase = phase
    }

    public var active: Bool { !["Leaving", "Error"].contains(phase) && (groupId != nil || phase == "Joining" || phase == "Reconnecting") }
    public var hasSnapshot: Bool { group != nil && queue != nil }
    /// A missing recovery snapshot is never evidence that the viewer selected another title.
    public var canSelectSource: Bool { active && group != nil && !["Joining", "Reconnecting"].contains(phase) && (hasSnapshot || phase == "Idle") }

    public func beginningJoin(groupId: String? = nil) -> SyncMembership { SyncMembership(generation: generation + 1, groupId: groupId, phase: "Joining") }
    /// The local resume stays in the renderer; leaving never sends Stop to the group.
    public func leaving() -> SyncMembership { SyncMembership(generation: generation + 1) }
    public func recovering(notice: String = "Synchronization needs a fresh group snapshot.") -> SyncMembership {
        var next = self; next.phase = "Reconnecting"; next.queue = nil; next.command = nil; next.notice = notice; return next
    }

    public func reduce(_ event: SyncEvent, generation eventGeneration: Int64) -> SyncMembership {
        if eventGeneration != generation || !active { return self }
        if let mine = groupId, let theirs = event.groupId, mine != theirs { return self }
        if let id = event.messageId, seenMessageIds.contains(id) { return self }
        var next = self
        if let id = event.messageId { next.seenMessageIds.append(id) }
        next.command = nil
        switch event {
        case let .joined(groupId, _, info):
            if let current = group, info.lastUpdatedTicks < current.lastUpdatedTicks { return next.recovering() }
            next.groupId = groupId
            var adopted = info; adopted.state = observedState ?? info.state
            next.group = adopted
            next.phase = queue == nil ? "Joining" : (observedState ?? info.state)
            next.notice = nil
            return next
        case let .queue(groupId, _, incoming):
            if let current = queue, incoming.lastUpdateTicks < current.lastUpdateTicks { return next.recovering() }
            next.groupId = groupId
            next.queue = incoming
            next.phase = group == nil ? "Joining" : (observedState ?? group!.state)
            next.notice = nil
            return next
        case let .state(_, _, state, _):
            next.observedState = state
            next.group?.state = state
            next.phase = hasSnapshot ? state : "Joining"
            return next
        case let .participant(_, _, name, joined):
            if var g = group {
                if joined {
                    var names: [String] = []
                    for n in g.participants + [name] where !names.contains(n) { names.append(n) }
                    g.participants = names
                } else if let i = g.participants.firstIndex(of: name) {
                    g.participants.remove(at: i)
                }
                next.group = g
            }
            return next
        case let .command(_, _, command):
            if let last = lastCommandTicks, command.emittedAtTicks < last {
                // Readiness replies may arrive out of emission order; an exact older repeat adds nothing.
                if hasSnapshot, let previous = lastAcceptedCommand {
                    var same = command; same.emittedAtTicks = previous.emittedAtTicks
                    if same == previous { return next }
                }
                return next.recovering()
            }
            if command.command == "Stop" && group != nil && queue == nil {
                next.command = command; next.lastCommandTicks = command.emittedAtTicks; next.lastAcceptedCommand = command; next.phase = "Idle"
                return next
            }
            guard hasSnapshot, let queue else { return next.recovering() }
            if command.emittedAtTicks < queue.lastUpdateTicks { return next }
            if command.playlistItemId != queue.current?.playlistItemId { return next.recovering() }
            next.command = command; next.lastCommandTicks = command.emittedAtTicks; next.lastAcceptedCommand = command
            switch command.command {
            case "Unpause": next.phase = "Playing"
            case "Stop": next.phase = "Idle"
            case "Pause": next.phase = "Paused"
            default: break
            }
            return next
        case .left:
            return next.leaving()
        case let .denied(_, _, reason):
            next.phase = "Error"; next.queue = nil
            switch reason {
            case "LibraryAccessDenied": next.notice = "This viewer cannot access the group's media. Leave or ask the server administrator for access."
            case "GroupDoesNotExist", "NotInGroup": next.notice = "This group is no longer available. Choose a group again."
            default: next.notice = "This viewer is not allowed to create or join this group."
            }
            return next
        case .invalid:
            return next.recovering()
        }
    }
}

/// Readiness, renderer binding and access policies around the membership.
public enum SyncReadiness {
    /// Only the exact entry explicitly stopped while Idle suppresses readiness; another entry must report.
    public static func reports(phase: String, currentEntryId: String, stoppedEntryId: String?) -> Bool {
        phase != "Idle" || currentEntryId != stoppedEntryId
    }
    /// While an entry is opening, the renderer must own the current playlist entry.
    public static func rendererOwnsEntry(pendingEntryId: String?, renderedEntryId: String?, currentEntryId: String) -> Bool {
        pendingEntryId == nil || renderedEntryId == currentEntryId
    }
    /// A delayed renderer binds only to the exact membership, playlist entry and media that requested it.
    public static func openMatches(openGeneration: Int64, currentGeneration: Int64, openEntryId: String, currentEntryId: String?, openItemId: String, currentItemId: String?) -> Bool {
        openGeneration == currentGeneration && openEntryId == currentEntryId && openItemId == currentItemId
    }
}

/// Deduplicates readiness observations; a real seek invalidates the old position's acknowledgement.
public struct SyncReadinessState: Equatable, Sendable {
    public var entryId: String?
    public var ready: Bool?
    public init(entryId: String? = nil, ready: Bool? = nil) { self.entryId = entryId; self.ready = ready }
    public func afterPositionApplied(changed: Bool) -> SyncReadinessState { changed ? SyncReadinessState(entryId: entryId, ready: nil) : self }
    /// Returns the next state and whether a Ready/Buffering request should be sent.
    public func observe(entryId current: String, ready currentReady: Bool) -> (state: SyncReadinessState, report: Bool) {
        (SyncReadinessState(entryId: current, ready: currentReady), entryId != current || ready != currentReady)
    }
}

/// Policy lookup state is distinct from a confirmed server denial.
public enum SyncAccessNotice: String, Sendable { case UNKNOWN, CHECKING, UNAVAILABLE, DENIED, ALLOWED }

public struct SyncAccessPresentation: Equatable, Sendable {
    public let notice: SyncAccessNotice
    public let canJoin: Bool
    public let canCreate: Bool
    public let canRefresh: Bool

    /// Unknown or failed access never inherits the last viewer's permission; denied viewers can recheck while idle.
    public init(policy: String, known: Bool, busy: Bool, failed: Bool) {
        let confirmed = known && !failed
        let join = confirmed && (policy == "CreateAndJoinGroups" || policy == "JoinGroups")
        if failed { notice = .UNAVAILABLE }
        else if !known && busy { notice = .CHECKING }
        else if !known { notice = .UNKNOWN }
        else if policy == "None" { notice = .DENIED }
        else if join { notice = .ALLOWED }
        else { notice = .UNAVAILABLE }
        canJoin = join
        canCreate = confirmed && policy == "CreateAndJoinGroups"
        canRefresh = !busy
    }
}
