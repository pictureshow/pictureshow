// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Live TV rules (spec/features/live-tv.md, spec/vectors/live-guide.json and live-dvr.json), translated from the Android
/// reference core/src/commonMain/kotlin/app/pictureshow/core/livetv/LiveRules.kt.
public enum LiveRules {
    public struct RuleError: Error, Equatable, LocalizedError {
        public let message: String
        public var errorDescription: String? { message }
    }

    /// LIVE-11: one minute before a guide snapshot stops being fresh.
    public static let quietRefreshMs: Int64 = 240_000

    /// An ISO-8601 instant with an explicit zone (`Z` or `±hh:mm`), fractional seconds of any length, as epoch milliseconds.
    /// Local times without a zone are not instants and return nil.
    public static func instant(_ iso: String?) -> Int64? {
        guard let text = iso else { return nil }
        let u = Array(text.utf8)
        func digits(_ at: Int, _ count: Int) -> Int? {
            guard at + count <= u.count else { return nil }
            var v = 0
            for i in at..<(at + count) { let c = u[i]; guard c >= 48, c <= 57 else { return nil }; v = v * 10 + Int(c - 48) }
            return v
        }
        var i = 0
        var yearSign = 1
        if i < u.count, u[i] == 45 || u[i] == 43 { yearSign = u[i] == 45 ? -1 : 1; i += 1 }
        var yearDigits = 0
        while i + yearDigits < u.count, u[i + yearDigits] >= 48, u[i + yearDigits] <= 57 { yearDigits += 1 }
        guard yearDigits >= 4, let y = digits(i, yearDigits) else { return nil }
        i += yearDigits
        guard i < u.count, u[i] == 45, let month = digits(i + 1, 2), i + 3 < u.count, u[i + 3] == 45, let day = digits(i + 4, 2) else { return nil }
        i += 6
        guard i < u.count, u[i] == 84 || u[i] == 116, let hour = digits(i + 1, 2), i + 3 < u.count, u[i + 3] == 58, let minute = digits(i + 4, 2) else { return nil }
        i += 6
        var second = 0, nanos = 0
        if i < u.count, u[i] == 58 {
            guard let s = digits(i + 1, 2) else { return nil }
            second = s; i += 3
            if i < u.count, u[i] == 46 || u[i] == 44 {
                i += 1
                var count = 0, value = 0
                while i < u.count, u[i] >= 48, u[i] <= 57 { if count < 9 { value = value * 10 + Int(u[i] - 48) }; count += 1; i += 1 }
                guard count > 0, count <= 9 else { return nil }
                for _ in count..<9 { value *= 10 }
                nanos = value
            }
        }
        var offset = 0
        guard i < u.count else { return nil }
        if u[i] == 90 || u[i] == 122 { i += 1 }
        else if u[i] == 43 || u[i] == 45 {
            let sign = u[i] == 45 ? -1 : 1
            guard let oh = digits(i + 1, 2) else { return nil }
            i += 3
            var om = 0
            if i < u.count, u[i] == 58 { guard let m = digits(i + 1, 2) else { return nil }; om = m; i += 3 }
            else if let m = digits(i, 2) { om = m; i += 2 }
            guard oh <= 18, om < 60 else { return nil }
            offset = sign * (oh * 3600 + om * 60)
        } else { return nil }
        guard i == u.count else { return nil }
        let year = yearSign * y
        let leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
        let lengths = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]
        guard (1...12).contains(month), day >= 1, day <= lengths[month - 1], hour < 24, minute < 60, second < 60 else { return nil }
        // Days from civil (proleptic Gregorian).
        let yy = month <= 2 ? year - 1 : year
        let era = (yy >= 0 ? yy : yy - 399) / 400
        let yoe = yy - era * 400
        let mp = (month + 9) % 12
        let doy = (153 * mp + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = Int64(era) * 146_097 + Int64(doe) - 719_468
        let seconds = days * 86_400 + Int64(hour * 3600 + minute * 60 + second) - Int64(offset)
        return seconds * 1000 + Int64(nanos / 1_000_000)
    }

    /// Exact half-open overlap with a bounded (at most 24 hour) UTC window, duplicates removed, ordered by start, channel, id.
    public static func guidePrograms(_ programs: [LiveProgram], allowedChannels: Set<String>, startUTC: String, endUTC: String) throws -> [LiveProgram] {
        guard let start = instant(startUTC) else { throw RuleError(message: "Guide start must be a UTC instant.") }
        guard let end = instant(endUTC) else { throw RuleError(message: "Guide end must be a UTC instant.") }
        guard end > start, end - start <= 86_400_000 else { throw RuleError(message: "Guide windows must be positive and at most 24 hours.") }
        var seen = Set<String>()
        let kept = programs.filter {
            guard let from = instant($0.startDate), let to = instant($0.endDate) else { return false }
            return allowedChannels.contains($0.channelId) && to > from && to > start && from < end
        }.filter { seen.insert($0.id).inserted }
        return kept.sorted { a, b in
            let x = instant(a.startDate)!, y = instant(b.startDate)!
            if x != y { return x < y }
            if a.channelId != b.channelId { return codeUnitsPrecede(a.channelId, b.channelId) }
            return codeUnitsPrecede(a.id, b.id)
        }
    }

    public static func canTune(_ program: LiveProgram, nowMs: Int64) -> Bool {
        guard let start = instant(program.startDate), let end = instant(program.endDate) else { return false }
        return start <= nowMs && nowMs < end
    }

    /// UTF-16 order, as Kotlin's String.compareTo.
    static func codeUnitsPrecede(_ a: String, _ b: String) -> Bool { a.utf16.lexicographicallyPrecedes(b.utf16) }
    static func compare(_ a: String, _ b: String) -> Int { a == b ? 0 : codeUnitsPrecede(a, b) ? -1 : 1 }

    private static func numberParts(_ s: String) -> [String] {
        var parts: [String] = []
        var current = ""
        var digit: Bool?
        for ch in s {
            let d = ch.isASCII && ch.isNumber
            if digit == d { current.append(ch) } else { if !current.isEmpty { parts.append(current) }; current = String(ch); digit = d }
        }
        if !current.isEmpty { parts.append(current) }
        return parts
    }

    private static func compareChannelNumbers(_ left: String?, _ right: String?) -> Int {
        let a = (left ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let b = (right ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if a.isEmpty || b.isEmpty { return a == b ? 0 : a.isEmpty ? 1 : -1 }
        let aa = numberParts(a), bb = numberParts(b)
        for (x, y) in zip(aa, bb) {
            let numeric = x.first!.isASCII && x.first!.isNumber && y.first!.isASCII && y.first!.isNumber
            func normal(_ v: String) -> String {
                if numeric { let t = String(v.drop { $0 == "0" }); return t.isEmpty ? "0" : t }
                return v.lowercased()
            }
            let xx = normal(x), yy = normal(y)
            let order = numeric && xx.count != yy.count ? (xx.count < yy.count ? -1 : 1) : compare(xx, yy)
            if order != 0 { return order }
        }
        return aa.count == bb.count ? 0 : aa.count < bb.count ? -1 : 1
    }

    /// Number components are natural, never floating point (2.10 follows 2.9); a missing number sorts last. Names, then
    /// IDs, break ties deterministically.
    public static func sortChannels(_ channels: [LiveChannel]) -> [LiveChannel] {
        var seen = Set<String>()
        return channels.filter { seen.insert($0.id).inserted }.sorted { a, b in
            var order = compareChannelNumbers(a.number, b.number)
            if order == 0 { order = compare(a.name.lowercased(), b.name.lowercased()) }
            if order == 0 { order = compare(a.id, b.id) }
            return order < 0
        }
    }

    private static let timerFields: Set<String> = [
        "ChannelId", "ProgramId", "Name", "Overview", "StartDate", "EndDate", "PrePaddingSeconds", "PostPaddingSeconds",
        "IsPrePaddingRequired", "IsPostPaddingRequired", "Priority", "ServiceName", "ExternalChannelId", "ExternalId",
        "ExternalProgramId", "KeepUntil", "ChannelName"
    ]
    static let weekdays: Set<String> = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
    private static let timerEdits: Set<String> = ["PrePaddingSeconds", "PostPaddingSeconds", "Priority"]
    private static let seriesEdits: Set<String> = timerEdits.union(["RecordAnyTime", "RecordAnyChannel", "RecordNewOnly", "Days", "KeepUpTo", "SkipEpisodesInLibrary", "KeepUntil"])

    /// Defaults are a SeriesTimerInfoDto. A one-off projects only TimerInfoDto's common writable fields; a series keeps every
    /// default except identity and status. The airing's identity and times always come from the freshly read program.
    ///
    /// Jellyfin's TimerInfoDto declares StartDate and EndDate as non-nullable, so a draft without both, with a time that
    /// isn't an instant, or one that ends before it starts could only be refused by the server. There is no draft then
    /// (nil), and the caller tells the viewer to refresh Live TV instead of sending that body.
    public static func timerDraft(defaults: LiveSchedule, program: LiveProgram, series: Bool) -> LiveSchedule? {
        guard let start = program.startDate, let end = program.endDate,
              let from = instant(start), let to = instant(end), to > from else { return nil }
        var fields = defaults.fields.filter { series ? !["Id", "SeriesTimerId", "Status"].contains($0.key) : timerFields.contains($0.key) }
        fields["ProgramId"] = .string(program.id)
        fields["ChannelId"] = .string(program.channelId)
        fields["Name"] = .string(program.name)
        fields["StartDate"] = .string(start)
        fields["EndDate"] = .string(end)
        return LiveSchedule(fields: fields)
    }

    /// LIVE-06: what the viewer reads when the fresh program has no schedulable times.
    public static let invalidDraftNotice = "This program no longer has valid schedule dates. Refresh Live TV."

    /// Only explicit dirty fields change; missing provider capabilities are not invented by an editor.
    public static func editSchedule(_ original: LiveSchedule, edits: [String: JSONValue], series: Bool) throws -> LiveSchedule {
        let allowed = series ? seriesEdits : timerEdits
        guard edits.keys.allSatisfy({ allowed.contains($0) && original.fields[$0] != nil }) else {
            throw RuleError(message: "The provider does not support one of these fields.")
        }
        for (key, value) in edits {
            if timerEdits.contains(key) || key == "KeepUpTo" {
                let number = value.primitiveContent.flatMap { Int($0) }
                guard let number, key == "Priority" || number >= 0 else { throw RuleError(message: "Padding and retention must be nonnegative integers.") }
            }
            if ["RecordAnyTime", "RecordAnyChannel", "RecordNewOnly", "SkipEpisodesInLibrary"].contains(key) {
                guard value.booleanValue != nil else { throw RuleError(message: "Recording options must be booleans.") }
            }
            if key == "Days" {
                guard case .array(let days) = value else { throw RuleError(message: "Select at least one valid day without duplicates.") }
                let names = days.map { $0.primitiveContent }
                guard !names.isEmpty, Set(names).count == names.count, names.allSatisfy({ $0.map(weekdays.contains) == true }) else {
                    throw RuleError(message: "Select at least one valid day without duplicates.")
                }
            }
            if key == "KeepUntil" {
                guard let v = value.primitiveContent, ["UntilDeleted", "UntilSpaceNeeded", "UntilWatched", "UntilDate"].contains(v) else {
                    throw RuleError(message: "Unknown retention option.")
                }
            }
        }
        return LiveSchedule(fields: original.fields.merging(edits) { $1 }, dirtyFields: original.dirtyFields.union(edits.keys))
    }

    public static func recordingStatus(_ status: String) -> String {
        switch status {
        case "New": return "Scheduled"
        case "InProgress": return "Recording"
        case "Completed": return "Completed"
        case "Cancelled": return "Cancelled"
        case "ConflictedOk": return "Overlaps"
        case "ConflictedNotOk": return "Won’t record"
        case "Error": return "Failed"
        default: return "Unknown status (\(status))"
        }
    }

    public static func recordingTone(_ status: String?) -> RecordingTone {
        switch status {
        case "InProgress": return .live
        case "Error", "ConflictedNotOk": return .error
        case "Cancelled": return .muted
        default: return .neutral
        }
    }

    /// LIVE-PD03: the TV guide header names the focused programme, led by where and when it is ("Now on Test A: News").
    /// Without a channel name the lead is dropped; unparseable times read "On".
    public static func previewLine(program: LiveProgram, channelName: String?, nowMs: Int64) -> String {
        let start = instant(program.startDate), end = instant(program.endDate)
        let lead: String
        if let start, let end { lead = nowMs < start ? "Later on" : nowMs >= end ? "Earlier on" : "Now on" } else { lead = "On" }
        guard let channelName, !channelName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return program.name }
        return "\(lead) \(channelName): \(program.name)"
    }

    /// A channel response cannot label another channel's playback, or keep a program after its half-open airing interval.
    public static func playerProgram(channel: LiveChannel?, nowMs: Int64) -> LiveProgram? {
        guard let channel, let program = channel.currentProgram else { return nil }
        let blank = program.channelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        return (blank || program.channelId == channel.id) && canTune(program, nowMs: nowMs) ? program : nil
    }
}

/// Cache ownership and a request generation prevent late pages and account switches from revealing another viewer's guide.
public struct LiveGuideCache: Equatable, Sendable {
    public var accountKey: String
    public var generation: Int64
    public var channelIds: Set<String>
    public var programs: [LiveProgram]
    public var fetchedAtMs: Int64?
    public var offline: Bool
    public var refreshing: Bool
    public init(accountKey: String, generation: Int64, channelIds: Set<String>, programs: [LiveProgram] = [], fetchedAtMs: Int64? = nil,
                offline: Bool = false, refreshing: Bool = false) {
        self.accountKey = accountKey; self.generation = generation; self.channelIds = channelIds; self.programs = programs
        self.fetchedAtMs = fetchedAtMs; self.offline = offline; self.refreshing = refreshing
    }

    public func page(account: String, generation requestGeneration: Int64, incoming: [LiveProgram], fetchedAt: Int64) -> LiveGuideCache {
        guard account == accountKey, requestGeneration == generation else { return self }
        var copy = self
        var seen = Set<String>()
        copy.programs = (programs + incoming).filter { channelIds.contains($0.channelId) }.filter { seen.insert($0.id).inserted }
        copy.fetchedAtMs = fetchedAt
        copy.offline = false
        return copy
    }

    public func snapshotFresh(nowMs: Int64) -> Bool {
        guard !offline, !refreshing, let fetched = fetchedAtMs else { return false }
        return nowMs >= fetched && nowMs - fetched <= 300_000
    }

    /// LIVE-11: a guide on screen refreshes itself quietly once it is four minutes old, before it turns stale at five. Not
    /// while a refresh is running, offline (that needs the viewer's Retry), or with a clock that runs backwards.
    public func quietRefreshDue(nowMs: Int64) -> Bool {
        guard !offline, !refreshing, let fetched = fetchedAtMs else { return false }
        return nowMs >= fetched && nowMs - fetched >= LiveRules.quietRefreshMs
    }

    public func actionAllowed(_ program: LiveProgram, nowMs: Int64) -> Bool {
        snapshotFresh(nowMs: nowMs) && channelIds.contains(program.channelId) && (LiveRules.instant(program.endDate).map { $0 > nowMs } ?? false)
    }
}

/// Positions are relative to a measured player window, never the guide's scheduled duration (LIVE-05).
public struct ObservedLiveWindow: Equatable, Sendable {
    public var offsetMs: Int64
    public var durationMs: Int64
    public var defaultPositionMs: Int64
    public var seekable: Bool
    public init(offsetMs: Int64, durationMs: Int64, defaultPositionMs: Int64, seekable: Bool) {
        self.offsetMs = offsetMs; self.durationMs = durationMs; self.defaultPositionMs = defaultPositionMs; self.seekable = seekable
    }
    public var canTimeShift: Bool { seekable && durationMs > 0 }
    public func contains(_ positionMs: Int64) -> Bool { canTimeShift && positionMs >= 0 && positionMs <= durationMs }
    public func pausedPositionExpired(_ absolutePositionMs: Int64?) -> Bool {
        guard let absolute = absolutePositionMs, canTimeShift else { return false }
        return absolute < offsetMs || absolute - offsetMs > durationMs
    }
    /// The decoder's default live position is the target; scheduled program times never enter this calculation.
    public func behindLiveMs(_ positionMs: Int64) -> Int64? {
        guard let live = goLivePosition(), positionMs >= 0, positionMs <= durationMs else { return nil }
        return max(live - positionMs, 0)
    }
    public func atLiveEdge(_ positionMs: Int64, expired: Bool = false) -> Bool {
        guard !expired, let behind = behindLiveMs(positionMs) else { return false }
        return behind <= 2_000
    }
    public func goLivePosition() -> Int64? {
        durationMs > 0 && defaultPositionMs >= 0 && defaultPositionMs <= durationMs ? defaultPositionMs : nil
    }
}
