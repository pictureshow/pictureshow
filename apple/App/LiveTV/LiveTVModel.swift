// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// What the Live TV screens need from the server, so the model can be driven by a stand-in in tests.
/// `LiveTVClient` is the real one; every call carries the viewer's own token (no administrator fallback).
protocol LiveTVService: AnyObject {
    func policy(account: Account) async throws -> LivePolicy
    func info(account: Account) async throws -> LiveInfo
    func channels(account: Account, startIndex: Int, limit: Int, search: String?, favorites: Bool) async throws -> LivePage<LiveChannel>
    func channel(account: Account, id: String) async throws -> LiveChannel
    func programs(account: Account, channelIDs: [String], startUTC: String, endUTC: String, startIndex: Int, limit: Int) async throws -> LivePage<LiveProgram>
    func program(account: Account, id: String) async throws -> LiveProgram
    func recordings(account: Account, startIndex: Int, limit: Int) async throws -> ItemPage
    func timers(account: Account, series: Bool) async throws -> [LiveSchedule]
    func defaults(account: Account, programID: String) async throws -> LiveSchedule
    func createTimer(account: Account, draft: LiveSchedule, series: Bool) async throws -> LiveWrite
    func updateTimer(account: Account, schedule: LiveSchedule, series: Bool) async throws -> LiveWrite
    func cancelTimer(account: Account, id: String, series: Bool) async throws -> LiveWrite
    func deleteRecording(account: Account, id: String) async throws -> LiveWrite
    func favorite(account: Account, id: String, favorite: Bool) async throws -> LiveChannel
}

/// One viewer's Live TV: channels, the bounded guide window, recordings and schedules (LIVE-01..11).
/// Each model belongs to exactly one account and is dropped with it, so a switch never shows another viewer's
/// channels or schedules (LIVE-11). Every load carries a generation; only the newest one may land.
@MainActor final class LiveTVModel: ObservableObject {
    struct State: Equatable {
        var loading = true
        var writing = false
        var policy: LivePolicy?
        var tunerEnabled: Bool?
        var channels: [LiveChannel] = []
        var programs: [LiveProgram] = []
        var recordings: [MediaItem] = []
        var timers: [LiveSchedule] = []
        var series: [LiveSchedule] = []
        var windowStart: Int64
        var fetchedAtMs: Int64?
        var offline = false
        var error: String?
        /// A write's own message (accepted, refused, unknown): shown separately from guide-read failures.
        var notice: String?
        var writeVersion = 0
        var lastWriteOutcome: String?

        /// The core's view of the same snapshot, for the shared presentation rules.
        var core: LiveTVState {
            LiveTVState(loading: loading, policy: policy, tunerEnabled: tunerEnabled, offline: offline, error: error,
                        fetchedAtMs: fetchedAtMs, channels: channels, programs: programs, recordings: recordings, timers: timers, series: series)
        }

        /// Cached data stays readable while a refresh is pending, but only a current snapshot can act (LIVE-11).
        func actionsAvailable(nowMs: Int64) -> Bool { cache.snapshotFresh(nowMs: nowMs) }

        var cache: LiveGuideCache {
            LiveGuideCache(accountKey: "", generation: 0, channelIds: [], programs: [], fetchedAtMs: fetchedAtMs, offline: offline, refreshing: loading)
        }
    }

    static let window: Int64 = 3 * 60 * 60 * 1000
    static let pageSize = 100

    @Published private(set) var state: State
    let account: Account
    var accountKey: String { account.key }
    private let service: LiveTVService
    private let now: () -> Int64
    private var load: Task<Void, Never>?
    private(set) var generation = 0
    private var quietAttemptAt: Int64?

    init(account: Account, service: LiveTVService, now: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.account = account
        self.service = service
        self.now = now
        state = State(windowStart: LivePresentation.guideWindowStart(nowMs: now(), zone: .current))
    }

    var nowMs: Int64 { now() }

    deinit { load?.cancel() }

    /// LIVE-11 (TV review T-5): called while the guide is on screen. At four minutes the snapshot refreshes quietly, so a
    /// viewer just watching the grid never meets "out of date"; its actions stay while that refresh runs. A failed
    /// attempt waits a minute before the next.
    func quietRefreshIfDue(at: Int64) {
        if load != nil || state.writing { return }
        guard state.cache.quietRefreshDue(nowMs: at) else { return }
        if let last = quietAttemptAt, at - last >= 0, at - last < 60_000 { return }
        quietAttemptAt = at
        refresh(quiet: true)
    }

    /// Reads everything again for `start`'s window. A quiet refresh leaves the current snapshot (and its actions) in place
    /// until the new one lands, and a failed quiet refresh changes nothing.
    func refresh(start: Int64? = nil, quiet: Bool = false) {
        load?.cancel()
        generation += 1
        let version = generation
        let start = start ?? state.windowStart
        if !quiet {
            state.loading = true
            state.error = nil
            state.windowStart = start
        }
        load = Task { [weak self] in
            await self?.read(start: start, version: version, quiet: quiet)
        }
    }

    /// The task that owns `version`; exposed so tests can wait for it.
    func waitForLoad() async { await load?.value }

    private func read(start: Int64, version: Int, quiet: Bool) async {
        defer { if version == generation { load = nil } }
        do {
            let policy = try await service.policy(account: account)
            guard version == generation, !Task.isCancelled else { return }
            if !policy.access {
                state = State(loading: false, policy: policy, windowStart: start,
                              error: "Live TV access is disabled for this viewer. Ask your server administrator.")
                return
            }
            state.policy = policy
            if !policy.manage { state.timers = []; state.series = [] }
            let info = try await service.info(account: account)
            var channels: [LiveChannel] = []
            var offset = 0
            while true {
                let page = try await service.channels(account: account, startIndex: offset, limit: Self.pageSize, search: nil, favorites: false)
                channels += page.items
                offset += page.items.count
                if page.items.isEmpty || offset >= page.totalRecordCount { break }
            }
            guard version == generation, !Task.isCancelled else { return }
            var seen = Set<String>()
            channels = channels.filter { seen.insert($0.id).inserted }
            let allowed = Set(channels.map(\.id))
            // A removed or newly blocked channel's programmes go now, even if the guide read then fails (LIVE-02).
            state.tunerEnabled = info.enabled
            state.channels = LiveRules.sortChannels(channels)
            state.programs = state.programs.filter { allowed.contains($0.channelId) }
            let startUTC = Self.iso(start), endUTC = Self.iso(start + Self.window)
            var programs: [LiveProgram] = []
            let ids = channels.map(\.id)
            for batch in stride(from: 0, to: ids.count, by: 100).map({ Array(ids[$0..<min($0 + 100, ids.count)]) }) {
                offset = 0
                while true {
                    let page = try await service.programs(account: account, channelIDs: batch, startUTC: startUTC, endUTC: endUTC, startIndex: offset, limit: Self.pageSize)
                    programs += page.items
                    offset += page.items.count
                    if page.items.isEmpty || offset >= page.totalRecordCount { break }
                }
            }
            var recordings: [MediaItem] = []
            offset = 0
            while true {
                let page = try await service.recordings(account: account, startIndex: offset, limit: Self.pageSize)
                recordings += page.items
                offset += page.items.count
                if page.items.isEmpty || offset >= page.totalRecordCount { break }
            }
            let timers = policy.manage ? try await service.timers(account: account, series: false) : []
            let series = policy.manage ? try await service.timers(account: account, series: true) : []
            guard version == generation, !Task.isCancelled else { return }
            var recordingIDs = Set<String>()
            state.loading = false
            state.programs = (try? LiveRules.guidePrograms(programs, allowedChannels: allowed, startUTC: startUTC, endUTC: endUTC)) ?? []
            state.recordings = recordings.filter { recordingIDs.insert($0.id).inserted }
            state.timers = timers
            state.series = series
            state.fetchedAtMs = now()
            state.offline = false
            state.error = nil
            state.windowStart = start
            quietAttemptAt = nil
        } catch is CancellationError {
        } catch {
            guard version == generation, !Task.isCancelled else { return }
            let failure = error as? LiveTVError
            if let status = failure?.status, status == 401 || status == 403 {
                state = State(loading: false, policy: LivePolicy(access: false, manage: false, deleteContent: false, userData: false),
                              windowStart: start, error: Self.message(error))
            } else if quiet {
                // A failed quiet refresh changes nothing until the snapshot itself turns stale.
            } else {
                state.loading = false
                state.offline = true
                state.error = Self.message(error)
            }
        }
    }

    /// Fetches the recording defaults for a programme after a fresh policy and programme read (LIVE-06).
    func draft(program: LiveProgram, series: Bool) async -> LiveSchedule? {
        guard !state.writing else { return nil }
        state.writing = true; state.error = nil; state.notice = nil
        defer { state.writing = false }
        do {
            let policy = try await service.policy(account: account)
            state.policy = policy
            guard policy.access, policy.manage else { state.notice = "Recording management is disabled for this viewer."; return nil }
            let fresh = try await service.program(account: account, id: program.id)
            let defaults = try await service.defaults(account: account, programID: program.id)
            return LiveRules.timerDraft(defaults: defaults, program: fresh, series: series)
        } catch {
            state.notice = Self.message(error)
            return nil
        }
    }

    /// Every write rechecks policy at the server; a failed or ambiguous create is never resubmitted on its own (LIVE-06/10).
    @discardableResult
    func save(_ schedule: LiveSchedule, series: Bool, existing: Bool) async -> LiveWrite? {
        await write(deleting: false, itemDeleteAllowed: nil) { [service, account] in
            existing ? try await service.updateTimer(account: account, schedule: schedule, series: series)
                     : try await service.createTimer(account: account, draft: schedule, series: series)
        }
    }

    @discardableResult
    func cancel(_ schedule: LiveSchedule, series: Bool) async -> LiveWrite? {
        await write(deleting: false, itemDeleteAllowed: nil) { [service, account] in
            try await service.cancelTimer(account: account, id: schedule.id, series: series)
        }
    }

    /// Deletes the recorded file (not its schedule) through the normal content operation (LIVE-09/10).
    @discardableResult
    func delete(_ item: MediaItem) async -> LiveWrite? {
        await write(deleting: true, itemDeleteAllowed: item.canDelete) { [service, account] in
            try await service.deleteRecording(account: account, id: item.id)
        }
    }

    private func write(deleting: Bool, itemDeleteAllowed: Bool?, _ operation: @escaping () async throws -> LiveWrite) async -> LiveWrite? {
        guard !state.writing else { return nil }
        state.writing = true; state.notice = nil
        defer { state.writing = false }
        do {
            let policy = try await service.policy(account: account)
            state.policy = policy
            let allowed = deleting ? (itemDeleteAllowed == true || (policy.deleteContent && itemDeleteAllowed != false)) : policy.manage
            guard policy.access, allowed else {
                state.notice = "This viewer does not have permission for this recording change."
                return LiveWrite("denied", message: state.notice)
            }
        } catch {
            state.notice = Self.message(error)
            return nil
        }
        let result: LiveWrite
        do { result = try await operation() } catch {
            state.notice = Self.message(error)
            state.writeVersion += 1
            refresh()
            return nil
        }
        state.notice = result.outcome == "ok" ? Self.outcomeMessage("ok", deleting: deleting)
            : (result.message.map(LivePresentation.visibleError) ?? Self.outcomeMessage(result.outcome, deleting: deleting))
        state.writeVersion += 1
        state.lastWriteOutcome = result.outcome
        refresh()
        return result
    }

    func favorite(_ channel: LiveChannel) async {
        guard !state.writing else { return }
        state.writing = true; state.notice = nil
        defer { state.writing = false }
        do {
            let policy = try await service.policy(account: account)
            guard policy.userData else { state.notice = "Favorites are disabled for this viewer."; return }
            _ = try await service.favorite(account: account, id: channel.id, favorite: !channel.userData.isFavorite)
            refresh()
        } catch {
            state.notice = Self.message(error)
        }
    }

    func clearNotice() { state.notice = nil }

    /// Fresh reads for one programme's detail; nil when the server no longer has it.
    func freshProgram(_ id: String) async -> LiveProgram? { try? await service.program(account: account, id: id) }

    nonisolated static func iso(_ ms: Int64) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: Date(timeIntervalSince1970: Double(ms) / 1000))
    }

    /// Viewer copy never carries HTTP status numbers (round 3).
    nonisolated static func message(_ error: Error) -> String {
        if let failure = error as? LiveTVError, !failure.message.isEmpty { return LivePresentation.visibleError(failure.message) }
        if error is URLError { return "Couldn't reach the server." }
        return "Couldn't reach the server."
    }

    nonisolated static func outcomeMessage(_ outcome: String, deleting: Bool) -> String? {
        switch outcome {
        case "ok": return deleting ? "Recording deleted." : nil
        case "unknown": return "The server didn't confirm this change. Refresh before trying again."
        case "denied": return "This viewer does not have permission for this recording change."
        default: return "The server refused this change."
        }
    }
}

/// The core client already has exactly these calls; the protocol lets tests stand in for the server.
extension LiveTVClient: LiveTVService {}
