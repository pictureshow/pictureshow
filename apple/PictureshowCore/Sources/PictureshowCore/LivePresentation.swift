// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// A programme placed in the guide's time window. Fractions are of the window's duration; overlapping broadcasts take
/// separate lanes and empty time stays empty.
public struct LiveGuideCell: Equatable, Sendable {
    public var program: LiveProgram
    public var startFraction: Double
    public var widthFraction: Double
    public var lane: Int
    public init(program: LiveProgram, startFraction: Double, widthFraction: Double, lane: Int) {
        self.program = program; self.startFraction = startFraction; self.widthFraction = widthFraction; self.lane = lane
    }
}

/// One device-clock formatter for guide, details, schedules and playback metadata (spec/vectors/live-presentation.json
/// clock and range). Output may contain U+202F / U+00A0 where the locale's pattern does.
public struct LiveTime: Sendable {
    public let twentyFour: Bool
    public let locale: Locale
    public let zone: TimeZone
    public let nowMs: Int64
    public init(twentyFour: Bool, locale: Locale = .current, zone: TimeZone = .current, nowMs: Int64) {
        self.twentyFour = twentyFour; self.locale = locale; self.zone = zone; self.nowMs = nowMs
    }

    private var calendar: Calendar { var c = Calendar(identifier: .gregorian); c.timeZone = zone; c.locale = locale; return c }
    private func formatter(_ pattern: String) -> DateFormatter {
        let f = DateFormatter(); f.locale = locale; f.timeZone = zone; f.calendar = calendar; f.dateFormat = pattern; return f
    }
    private static func date(_ ms: Int64) -> Date { Date(timeIntervalSince1970: Double(ms) / 1000) }
    private var clockPattern: String {
        DateFormatter.dateFormat(fromTemplate: twentyFour ? "Hm" : "hm", options: 0, locale: locale) ?? (twentyFour ? "HH:mm" : "h:mm a")
    }
    /// The local wall time is ambiguous (a DST fold), so the clock carries its offset.
    private func offsetSuffix(_ ms: Int64) -> String? {
        let seconds = Int(floor(Double(ms) / 1000))
        let offset = zone.secondsFromGMT(for: Self.date(ms))
        let local = seconds + offset
        let candidates = Set([zone.secondsFromGMT(for: Self.date(ms - 10_800_000)), zone.secondsFromGMT(for: Self.date(ms + 10_800_000))])
        let ambiguous = candidates.contains { $0 != offset && zone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(local - $0))) == $0 }
        guard ambiguous else { return nil }
        if offset == 0 { return "Z" }
        let absolute = abs(offset)
        return String(format: "%@%02d:%02d", offset < 0 ? "-" : "+", absolute / 3600, absolute % 3600 / 60)
    }
    public func clock(ms: Int64) -> String {
        formatter(clockPattern).string(from: Self.date(ms)) + (offsetSuffix(ms).map { " \($0)" } ?? "")
    }
    public func clock(_ iso: String?) -> String { LiveRules.instant(iso).map { clock(ms: $0) } ?? "Time unavailable" }
    private func dayNumber(_ ms: Int64) -> Int {
        let c = calendar.dateComponents([.year, .month, .day], from: Self.date(ms))
        return calendar.ordinality(of: .day, in: .era, for: calendar.date(from: c) ?? Self.date(ms)) ?? 0
    }
    public func day(ms: Int64) -> String {
        switch dayNumber(ms) - dayNumber(nowMs) {
        case 0: return "Today"
        case 1: return "Tomorrow"
        default: return formatter("EEE d").string(from: Self.date(ms))
        }
    }
    public func range(_ start: String?, _ end: String?) -> String {
        guard let from = LiveRules.instant(start), let to = LiveRules.instant(end) else { return "Time unavailable" }
        let date = dayNumber(from), endDate = dayNumber(to), today = dayNumber(nowMs)
        // Keep both periods for a range crossing AM/PM; omit the repeated first period only when identical.
        var first = clock(ms: from)
        let last = clock(ms: to)
        if !twentyFour && date == endDate {
            let period = formatter("a")
            let a = period.string(from: Self.date(from)), b = period.string(from: Self.date(to))
            if a == b, !a.isEmpty, first.hasSuffix(a), last.hasSuffix(b) {
                first = String(first.dropLast(a.count))
                while let tail = first.unicodeScalars.last, CharacterSet.whitespacesAndNewlines.contains(tail) { first.unicodeScalars.removeLast() }
            }
        }
        return (date != today ? "\(day(ms: from)) · " : "") + first + " – " + (endDate != date ? "\(day(ms: to)) · " : "") + last
    }
}

public struct LiveTimelineReadout: Equatable, Sendable {
    public var status: String
    public var clock: String
    public init(status: String, clock: String) { self.status = status; self.clock = clock }
}

public enum LiveEmptyAction: String, Sendable, CaseIterable {
    case back = "Back", retry = "Retry", allChannels = "AllChannels", channels = "Channels", guide = "Guide", clearFilters = "ClearFilters"
    /// The stable action name (as Android's enum name).
    public var name: String { rawValue }
    public var label: String {
        switch self {
        case .back: return "Back"
        case .retry: return "Retry"
        case .allChannels: return "Show all channels"
        case .channels: return "Go to channels"
        case .guide: return "Browse guide"
        case .clearFilters: return "Clear filters"
        }
    }
}

public struct LiveEmptyPresentation: Equatable, Sendable {
    public var title: String
    public var body: String
    public var action: LiveEmptyAction
    public var showGuideNavigation: Bool
    public init(title: String, body: String, action: LiveEmptyAction, showGuideNavigation: Bool = false) {
        self.title = title; self.body = body; self.action = action; self.showGuideNavigation = showGuideNavigation
    }
}

/// The Live TV screen's loaded facts, enough for empty states and guide notices.
public struct LiveTVState: Equatable, Sendable {
    public var loading: Bool
    public var policy: LivePolicy?
    public var tunerEnabled: Bool?
    public var offline: Bool
    public var error: String?
    public var fetchedAtMs: Int64?
    public var channels: [LiveChannel]
    public var programs: [LiveProgram]
    public var recordings: [MediaItem]
    public var timers: [LiveSchedule]
    public var series: [LiveSchedule]
    public init(loading: Bool = false, policy: LivePolicy? = nil, tunerEnabled: Bool? = nil, offline: Bool = false, error: String? = nil,
                fetchedAtMs: Int64? = nil, channels: [LiveChannel] = [], programs: [LiveProgram] = [], recordings: [MediaItem] = [],
                timers: [LiveSchedule] = [], series: [LiveSchedule] = []) {
        self.loading = loading; self.policy = policy; self.tunerEnabled = tunerEnabled; self.offline = offline; self.error = error
        self.fetchedAtMs = fetchedAtMs; self.channels = channels; self.programs = programs; self.recordings = recordings
        self.timers = timers; self.series = series
    }
    /// A failed guide read leaves the saved guide for reference only.
    public var guideUnavailable: Bool { offline }
    /// LIVE-11: why the guide on screen must not be acted on, if it must not.
    public func guideReferenceNotice(nowMs: Int64) -> String? {
        if offline { return "Saved guide is for reference. Reconnect before scheduling or tuning." }
        guard let fetched = fetchedAtMs else { return nil }
        if nowMs < fetched { return "Guide freshness is unavailable. Refresh before scheduling or tuning." }
        if !LiveGuideCache(accountKey: "", generation: 0, channelIds: [], fetchedAtMs: fetched).snapshotFresh(nowMs: nowMs) {
            return "Guide is out of date. Refresh before scheduling or tuning."
        }
        return nil
    }
}

public struct LiveScheduleGroup: Equatable, Sendable {
    public var title: String
    public var schedules: [LiveSchedule]
    public var series: Bool
    public init(title: String, schedules: [LiveSchedule], series: Bool) { self.title = title; self.schedules = schedules; self.series = series }
}

/// Live TV presentation rules shared by every Apple screen, translated from the Android reference
/// (core livetv/LivePresentation.kt and the app's LiveTime, LiveGuide*, LivePlayerPresentation, PlayerTimeline and
/// LiveScheduleEditor rules). Pixel sizes and components stay native.
public enum LivePresentation {
    // MARK: Guide

    /// Android's guide width for three hours; viewport geometry in the vectors is in these units.
    public static let guideWidth: Double = 1080
    public static let guideWindowMs: Int64 = 10_800_000

    public static func guideCells(_ programs: [LiveProgram], startMs: Int64, endMs: Int64) -> [LiveGuideCell] {
        guard endMs > startMs else { return [] }
        var seen = Set<String>()
        let unique = programs.filter { seen.insert($0.id).inserted }.enumerated().sorted { a, b in
            let x = LiveRules.instant(a.element.startDate), y = LiveRules.instant(b.element.startDate)
            switch (x, y) {
            case (nil, nil): return a.offset < b.offset
            case (nil, _): return true
            case (_, nil): return false
            case let (l?, r?): return l == r ? a.offset < b.offset : l < r
            }
        }.map(\.element)
        var lanes: [Int64] = []
        let span = Double(endMs - startMs)
        return unique.compactMap { program in
            guard let rawFrom = LiveRules.instant(program.startDate), let rawTo = LiveRules.instant(program.endDate) else { return nil }
            let from = max(rawFrom, startMs), to = min(rawTo, endMs)
            guard to > from else { return nil }
            var lane = lanes.firstIndex { $0 <= from } ?? -1
            if lane < 0 { lane = lanes.count; lanes.append(to) } else { lanes[lane] = to }
            return LiveGuideCell(program: program, startFraction: Double(from - startMs) / span, widthFraction: Double(to - from) / span, lane: lane)
        }
    }

    /// The airing on now, else the next one, else the last one shown.
    public static func initialFocus(_ cells: [LiveGuideCell], nowMs: Int64) -> String? {
        cells.first { LiveRules.canTune($0.program, nowMs: nowMs) }?.program.id
            ?? cells.first { (LiveRules.instant($0.program.startDate) ?? Int64.min) > nowMs }?.program.id
            ?? cells.last?.program.id
    }

    /// The Now action's focus key ("p:<id>"): the selected channel's current airing if its row has programmes, else the
    /// first channel with any.
    public static func guideNowFocus(channels: [LiveChannel], programs: [LiveProgram], selectedKey: String?, startMs: Int64, nowMs: Int64) -> String? {
        let selected = channels.first { "c:\($0.id)" == selectedKey }?.id ?? programs.first { "p:\($0.id)" == selectedKey }?.channelId
        var rows: [String: [LiveGuideCell]] = [:]
        for (channel, shows) in Dictionary(grouping: programs, by: \.channelId) { rows[channel] = guideCells(shows, startMs: startMs, endMs: startMs + guideWindowMs) }
        let channel = channels.first { $0.id == selected && !(rows[$0.id] ?? []).isEmpty } ?? channels.first { !(rows[$0.id] ?? []).isEmpty }
        return channel.flatMap { initialFocus(rows[$0.id] ?? [], nowMs: nowMs) }.map { "p:\($0)" }
    }

    /// The calendar half hour containing now, keeping the current offset through a DST fold (quarter-hour zones too).
    public static func guideWindowStart(nowMs: Int64, zone: TimeZone = .current) -> Int64 {
        let seconds = Int64(floor(Double(nowMs) / 1000))
        let offset = Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(seconds))))
        let local = seconds + offset
        let inHour = ((local % 3600) + 3600) % 3600
        let floored = local - inHour + (inHour / 60 / 30 * 30) * 60
        var result = floored - offset
        let resolved = Int64(zone.secondsFromGMT(for: Date(timeIntervalSince1970: Double(result))))
        if resolved != offset { result = floored - resolved }
        return result * 1000
    }

    /// Keep text in the visible interval without moving its time-placed programme cell.
    public static func textWindow(cellX: Double, cellWidth: Double, viewportStart: Double, viewportWidth: Double) -> (inset: Double, width: Double) {
        let width = max(cellWidth, 0)
        let left = max(cellX, viewportStart)
        let right = min(cellX + width, viewportStart + max(viewportWidth, 0))
        return (min(max(viewportStart - cellX, 0), width), max(right - left, 0))
    }

    public static func guideShowsText(width: Double) -> Bool { width >= 72 }

    /// After horizontal scrolling, the focus stays on its cell if visible; otherwise the cell covering the viewport's
    /// leading edge (same lane first), else the visible cell nearest to it.
    public static func guideViewportFocus(_ cells: [LiveGuideCell], currentID: String?, viewportStart: Double, viewportWidth: Double, inset: Double = 0) -> String? {
        guard viewportWidth > 0 else { return nil }
        func left(_ c: LiveGuideCell) -> Double { inset + c.startFraction * guideWidth }
        func right(_ c: LiveGuideCell) -> Double { left(c) + c.widthFraction * guideWidth }
        let visible = cells.filter { right($0) > viewportStart && left($0) < viewportStart + viewportWidth }
        let current = cells.first { $0.program.id == currentID }
        if let current, visible.contains(current) { return current.program.id }
        let covering = visible.filter { left($0) <= viewportStart && right($0) > viewportStart }
        if let hit = covering.first(where: { $0.lane == current?.lane }) ?? covering.first { return hit.program.id }
        var best: LiveGuideCell?
        for cell in visible {
            guard let b = best else { best = cell; continue }
            let d = abs(left(cell) - viewportStart), e = abs(left(b) - viewportStart)
            if d < e || (d == e && cell.lane == current?.lane && b.lane != current?.lane) { best = cell }
        }
        return best?.program.id
    }

    /// Ruler labels never move their tick or show only a clipped part.
    public static func guideRulerLabelFits(x: Double, width: Double, viewportStart: Double, viewportWidth: Double) -> Bool {
        width > 0 && x >= viewportStart + 8 && x + width <= viewportStart + viewportWidth - 8
    }

    public static func channelIdleLabel(hasPrograms: Bool, unavailable: Bool = false) -> String {
        unavailable ? "Guide unavailable" : hasPrograms ? "Nothing on now" : "No guide data"
    }
    public static func guideEmptyMessage(unavailable: Bool) -> String { unavailable ? "Guide unavailable" : "No guide data" }
    /// TV Now is also an explicit focus destination; touch has no remote selection to return from.
    public static func guideNowEnabled(tv: Bool, enabled: Bool, atNow: Bool) -> Bool { enabled && (tv || !atNow) }
    public static func scrollOwner(tab: String, linear: Bool) -> String { tab == "Guide" ? (linear ? "Guide:List" : "Guide:Grid") : tab }
    public static func channelMonogramVisible(imageLoaded: Bool) -> Bool { !imageLoaded }

    // MARK: Time

    public static func guideFreshness(fetchedAtMs: Int64?, offline: Bool, time: LiveTime) -> String? {
        guard let fetchedAtMs else { return nil }
        let clock = time.clock(ms: fetchedAtMs)
        return offline ? "Offline · last loaded \(clock)" : "Updated \(clock)"
    }

    // MARK: State

    public static func emptyPresentation(state: LiveTVState, tab: String, filtered: Int, favorites: Bool, search: String) -> LiveEmptyPresentation? {
        if state.policy?.access == false {
            return LiveEmptyPresentation(title: "Live TV isn't available on this account", body: "Ask your server administrator to allow Live TV access.", action: .back)
        }
        if state.loading { return nil }
        if tab == "Schedules" && state.policy?.manage == false {
            return LiveEmptyPresentation(title: "Recording schedules aren't available", body: "Ask your server administrator to allow recording management.", action: .channels)
        }
        let hasContent: Bool
        switch tab {
        case "Recordings": hasContent = !state.recordings.isEmpty
        case "Schedules": hasContent = !state.timers.isEmpty || !state.series.isEmpty
        case "Guide": hasContent = !state.programs.isEmpty
        default: hasContent = !state.channels.isEmpty
        }
        if state.offline && !hasContent {
            return LiveEmptyPresentation(title: "Couldn't load Live TV", body: "Couldn't reach the server. Check your connection and try again.", action: .retry)
        }
        if tab == "Channels" || tab == "Guide" {
            if state.channels.isEmpty {
                return LiveEmptyPresentation(title: state.tunerEnabled == false ? "No tuner configured" : "No channels available",
                                             body: "Ask your server administrator to add a tuner in Jellyfin's Live TV settings.", action: .retry)
            }
            if filtered == 0 && favorites && search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return LiveEmptyPresentation(title: "No favorite channels yet", body: "Star a channel to pin it here.", action: .allChannels)
            }
            if filtered == 0 {
                return LiveEmptyPresentation(title: "No matching channels", body: "Try another channel name or clear your search and filters.", action: .clearFilters)
            }
            if tab == "Guide" && state.programs.isEmpty {
                return LiveEmptyPresentation(title: "No guide data", body: "Try another time, or ask your server administrator to set up a TV guide.", action: .channels, showGuideNavigation: true)
            }
            return nil
        }
        if tab == "Recordings" && state.recordings.isEmpty {
            return LiveEmptyPresentation(title: "No recordings yet", body: "Record a program from the guide to watch it here later.", action: .guide)
        }
        if tab == "Schedules" && state.timers.isEmpty && state.series.isEmpty {
            return LiveEmptyPresentation(title: "No scheduled recordings", body: "Choose a program in the guide to record once or as a series.", action: .guide)
        }
        return nil
    }

    /// Touch shows a pull indicator for a pull; TV (and touch without a pull) shows the header's progress instead.
    public static func refreshPresentation(loading: Bool, writing: Bool, tv: Bool, pulled: Bool) -> (pullRefreshing: Bool, headerLoading: Bool) {
        (loading && pulled && !tv, writing || (loading && (tv || !pulled)))
    }

    /// Transport details (HTTP words or status codes) never reach the viewer.
    public static func visibleError(_ message: String) -> String {
        message.range(of: #"(?i)\bhttp\b|\b[45][0-9]{2}\b"#, options: .regularExpression) != nil
            ? "Couldn't complete this change. Check your connection and try again." : message
    }

    // MARK: Programme

    public static func programEpisodeLabel(_ program: LiveProgram) -> String? {
        guard program.seasonNumber != nil || program.episodeNumber != nil else { return nil }
        var item = MediaItem(id: program.id)
        item.parentIndexNumber = program.seasonNumber; item.indexNumber = program.episodeNumber
        return item.episodeLabel
    }
    public static func programTimeLabel(range: String, start: String?, end: String?, nowMs: Int64) -> String {
        let from = LiveRules.instant(start), to = LiveRules.instant(end)
        if let to, to <= nowMs { return "Ended · \(range)" }
        if let from, from > nowMs {
            if from - nowMs <= 3_600_000 { return "Starts in \((from - nowMs + 59_999) / 60_000) min · \(range)" }
            return "Upcoming · \(range)"
        }
        return range
    }
    public static func programEnded(endISO: String?, nowMs: Int64) -> Bool { LiveRules.instant(endISO).map { $0 <= nowMs } ?? false }
    public static func programCreationAvailable(endISO: String?, nowMs: Int64) -> Bool { LiveRules.instant(endISO).map { $0 > nowMs } ?? false }
    public static func programRecordingLabel(status: String) -> String { status == "New" ? "Recording scheduled" : LiveRules.recordingStatus(status) }
    /// TV: one line; touch: where and when, then what.
    public static func programMetadataLines(channel: String?, range: String, episode: String?, title: String?, tv: Bool) -> [String] {
        func join(_ parts: [String?]) -> String { parts.compactMap { $0 }.joined(separator: " · ") }
        if tv { return [join([channel, range, episode, title])] }
        return [join([channel, range]), join([episode, title])].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }
    public static func programRecordInitialFocus(tv: Bool, primary: Bool) -> Bool { tv && primary }

    // MARK: Schedules

    /// Invalid intervals or a failed/cancelled current timer never claim a recording is in progress.
    public static func scheduleGroup(_ schedule: LiveSchedule, nowMs: Int64) -> String {
        guard let start = LiveRules.instant(schedule.startDate), let end = LiveRules.instant(schedule.endDate), end > start else { return "Other schedules" }
        if end <= nowMs { return "Past" }
        if start > nowMs { return "Upcoming" }
        return ["New", "InProgress"].contains(schedule.status) ? "Recording now" : "Other schedules"
    }
    public static func scheduleGroups(timers: [LiveSchedule], series: [LiveSchedule], nowMs: Int64) -> [LiveScheduleGroup] {
        func group(_ title: String) -> LiveScheduleGroup { LiveScheduleGroup(title: title, schedules: timers.filter { scheduleGroup($0, nowMs: nowMs) == title }, series: false) }
        return [group("Recording now"), group("Upcoming"), LiveScheduleGroup(title: "Series rules", schedules: series, series: true), group("Other schedules"), group("Past")]
    }
    public static func scheduleFocusKey(_ schedule: LiveSchedule, series: Bool) -> String { "s:\(series ? "series" : "timer"):\(schedule.id)" }
    public static func scheduleStatus(_ schedule: LiveSchedule, series: Bool) -> String {
        if let status = schedule.string("Status"), !status.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return LiveRules.recordingStatus(status) }
        return series ? "Series rule" : "Status unavailable"
    }
    public static func scheduleReviewSummary(_ schedule: LiveSchedule, series: Bool, existing: Bool, time: LiveTime, touch: Bool = false) -> String {
        if existing { return "Applies these options to this \(series ? "series rule" : "recording schedule")." }
        if !series {
            if touch { return "Records this airing once." }
            return "Records \(schedule.name) on \(schedule.channelName ?? "this channel"), \(time.range(schedule.startDate, schedule.endDate))."
        }
        let episodes: String
        switch schedule.boolean("RecordNewOnly") { case true?: episodes = "new episodes"; case false?: episodes = "all episodes"; case nil: episodes = "episodes" }
        let channels: String
        switch schedule.boolean("RecordAnyChannel") { case true?: channels = " on any channel"; case false?: channels = " on this channel"; case nil: channels = " using the options below" }
        return "Records \(episodes)\(channels). Recordings you already have stay if you cancel."
    }
    /// Whether any edit differs from the schedule; Days compares as a set.
    public static func scheduleHasChanges(_ schedule: LiveSchedule, edits: [String: JSONValue]) -> Bool {
        edits.contains { key, value in
            if key == "Days", case .array(let a)? = schedule.fields[key], case .array(let b) = value {
                func members(_ list: [JSONValue]) -> Set<String> { Set(list.map { $0.primitiveContent.map { "p:\($0)" } ?? "\($0)" }) }
                return members(a) != members(b)
            }
            return schedule.fields[key] != value
        }
    }
    public static func scheduleReviewLabel(existing: Bool, changed: Bool) -> String { !existing ? "Review recording" : changed ? "Review changes" : "Review schedule" }
    public static func scheduleCanConfirm(existing: Bool, changed: Bool, busy: Bool, canSubmit: Bool = true) -> Bool { canSubmit && !busy && (!existing || changed) }
    public static func recordingPriorityLabel(_ value: String?) -> String {
        switch value { case "-2": return "Lowest"; case "-1": return "Low"; case "0": return "Normal"; case "1": return "High"; case "2": return "Highest"; default: return "Provider setting" }
    }
    public static let weekdayOrder = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
    public static func recordingDaysLabel(_ days: Set<String>) -> String {
        if days == Set(weekdayOrder) { return "Every day" }
        if days == Set(weekdayOrder.prefix(5)) { return "Weekdays" }
        if days == Set(weekdayOrder.suffix(2)) { return "Weekends" }
        let ordered = weekdayOrder.filter(days.contains)
        return ordered.isEmpty ? "No days selected" : ordered.joined(separator: ", ")
    }
    public static let scheduleOrder = ["Recording now", "Upcoming", "Series rules", "Other schedules", "Past"]
    public static func reviewSummaryFocusable(tv: Bool) -> Bool { tv }
    public static func editorShouldRevealHeader(tv: Bool, firstRowFocused: Bool) -> Bool { tv && firstRowFocused }

    // MARK: Recordings

    public static func recordingTitle(_ item: MediaItem) -> String {
        if let t = item.episodeTitle, !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return t }
        if !item.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && item.name != item.seriesName { return item.name }
        return recordingEpisode(item) ?? "Recording"
    }
    private static func recordingEpisode(_ item: MediaItem) -> String? { item.indexNumber != nil || item.parentIndexNumber != nil ? item.episodeLabel : nil }
    private static func growing(_ item: MediaItem) -> Bool { item.isInProgress || item.recordingStatus == "InProgress" }
    public static func recordingMetadata(_ item: MediaItem, time: LiveTime) -> String {
        let start = LiveRules.instant(item.recordingStartDate), end = LiveRules.instant(item.recordingEndDate)
        let episode = recordingEpisode(item).flatMap { $0 == recordingTitle(item) ? nil : $0 }
        let when: String?
        if growing(item), let start, let end, end > start { when = "Ends \(time.clock(ms: end))" }
        else { when = start.map { "\(time.day(ms: $0)) · \(time.clock(ms: $0))" } }
        let runtime = CatalogRules.runtimeLabel(item.runTimeTicks)
        return [episode, when, runtime.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : runtime].compactMap { $0 }.joined(separator: " · ")
    }
    /// A reported zero is authoritative; elapsed guide time never invents recording progress.
    public static func recordingProgress(_ item: MediaItem) -> Double? {
        guard growing(item), let p = item.recordingCompletionPercentage, p.isFinite, p >= 0, p <= 100 else { return nil }
        return p / 100
    }
    public static func recordingActionOrder(hasFromStart: Bool, hasDownload: Bool) -> [String] {
        ["primary"] + (hasFromStart ? ["from-start"] : []) + (hasDownload ? ["download"] : []) + ["played", "favorite"]
    }
    /// The Live TV view is reached through its own destination, never listed as an ordinary library.
    public static func ordinaryLibraries(_ views: [MediaItem]) -> [MediaItem] { views.filter { !$0.isLiveTvLibrary } }

    // MARK: Live player

    public static func behindLiveClock(_ behindMs: Int64?) -> String {
        guard let behindMs else { return "Live" }
        let seconds = max(behindMs, 0) / 1000, hours = seconds / 3600
        let text = hours > 0 ? String(format: "%lld:%02lld:%02lld", hours, seconds % 3600 / 60, seconds % 60) : String(format: "%lld:%02lld", seconds / 60, seconds % 60)
        return "\u{2212}\(text)"
    }
    public static func behindDescription(_ behindMs: Int64) -> String {
        let seconds = max(behindMs, 0) / 1000, minutes = seconds / 60
        return minutes > 0 ? "\(minutes) min \(seconds % 60) sec behind live" : "\(seconds) sec behind live"
    }
    public static func timelineReadout(window: ObservedLiveWindow?, position: Int64, paused: Bool, expired: Bool, canTimeShift: Bool) -> LiveTimelineReadout {
        if expired { return LiveTimelineReadout(status: "Paused · Buffer expired", clock: "") }
        if !canTimeShift { return LiveTimelineReadout(status: "Time shift unavailable", clock: "") }
        guard let window, let behind = window.behindLiveMs(position) else { return LiveTimelineReadout(status: "Live position unavailable", clock: "") }
        if window.atLiveEdge(position, expired: expired) { return LiveTimelineReadout(status: paused ? "Paused · At live edge" : "Live", clock: "") }
        return LiveTimelineReadout(status: paused ? "Paused" : "", clock: behindLiveClock(behind))
    }
    public static func forwardSeekAllowed(live: Bool, window: ObservedLiveWindow?, position: Int64, expired: Bool) -> Bool {
        guard live else { return true }
        guard let window else { return false }
        return window.contains(position) && !expired && !window.atLiveEdge(position, expired: expired)
    }
    public static func trackPositionFraction(window: ObservedLiveWindow?, position: Int64, expired: Bool = false) -> Double? {
        guard let w = window, w.canTimeShift, w.contains(position), !expired, let edge = w.goLivePosition(), edge > 0 else { return nil }
        return w.atLiveEdge(position) ? 1 : min(max(Double(position) / Double(edge), 0), 1)
    }
    public static func bufferScrubEnd(window: ObservedLiveWindow) -> Int64 {
        if let live = window.goLivePosition(), live > 0 { return live }
        return max(window.durationMs, 0)
    }
    public static func bufferSliderPosition(window: ObservedLiveWindow, position: Int64, expired: Bool) -> Double {
        let end = bufferScrubEnd(window: window)
        if let f = trackPositionFraction(window: window, position: position, expired: expired) { return f * Double(end) }
        return Double(min(max(position, 0), end))
    }
    public static func bufferEndpointLabels(window: ObservedLiveWindow?, position: Int64, expired: Bool) -> (String, String) {
        if expired { return ("Buffer expired", "") }
        guard window?.canTimeShift == true else { return ("Time shift unavailable", "") }
        return trackPositionFraction(window: window, position: position, expired: expired) != nil ? ("Available buffer · start", "Live edge") : ("Measured buffer", "Live edge unavailable")
    }
    /// Keep the compact negative clock, with explicit units nearby to distinguish seconds from programme minutes.
    public static func timelineVisualStatus(_ readout: LiveTimelineReadout, behindMs: Int64?) -> String {
        guard !readout.clock.isEmpty, let behindMs else { return readout.status }
        return [readout.status, behindDescription(behindMs)].filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: " · ")
    }
    /// The spoken (accessibility) timeline label.
    public static func timelineLabel(paused: Bool, canTimeShift: Bool, expired: Bool, behindMs: Int64?) -> String {
        if expired { return "Paused · Buffer expired. Go live to resume." }
        if !canTimeShift { return "Live · Time shift unavailable" }
        guard let behindMs else { return "Live position unavailable" }
        if paused { return behindMs > 2_000 ? "Paused · \(behindDescription(behindMs))" : "Paused · At live edge" }
        return behindMs > 2_000 ? behindDescription(behindMs) : "Live"
    }
    public static func touchLiveSliderAvailable(window: ObservedLiveWindow?, expired: Bool) -> Bool { window?.canTimeShift == true && !expired }
    public static func pauseRequestsControlFocus(live: Bool, surfaceFocused: Bool) -> Bool { live && surfaceFocused }
}
