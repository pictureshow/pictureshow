// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Scopes of create requests that may have reached the server but were never confirmed (LIVE-08). A scope stays here
/// until a re-read finds its schedule, so an unresolved request is reconciled, never silently resubmitted.
public protocol LivePendingWrites: Sendable {
    func contains(_ scope: String) async -> Bool
    func add(_ scope: String) async
    func remove(_ scope: String) async
}

public actor MemoryLivePendingWrites: LivePendingWrites {
    private var scopes = Set<String>()
    public init() {}
    public func contains(_ scope: String) -> Bool { scopes.contains(scope) }
    public func add(_ scope: String) { scopes.insert(scope) }
    public func remove(_ scope: String) { scopes.remove(scope) }
}

extension Account {
    /// One viewer on one server: guide caches and pending writes never cross it.
    public var liveKey: String { "\(server.url.absoluteString)#\(userId)" }
}

/// Serializes schedule writes, as the Android client's Mutex does.
private actor LiveWriteLock {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func lock() async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func unlock() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }
}

/// Live TV and DVR over the viewer's own token (spec/features/live-tv.md), translated from the Android reference
/// core/src/commonMain/kotlin/app/pictureshow/core/livetv/LiveTvClient.kt. Reads throw `LiveTVError`; writes return a
/// `LiveWrite` whose outcome is ok, unknown, denied or failed. Every call carries the viewer's userId.
public final class LiveTVClient: @unchecked Sendable {
    public let client: JellyfinClient
    public let pendingWrites: LivePendingWrites
    private let now: @Sendable () -> Int64
    private let writes = LiveWriteLock()
    private var decoder: JSONDecoder { JSONDecoder() }

    public init(client: JellyfinClient, pendingWrites: LivePendingWrites = MemoryLivePendingWrites(),
                now: @escaping @Sendable () -> Int64 = { Int64((Date().timeIntervalSince1970 * 1000).rounded(.down)) }) {
        self.client = client; self.pendingWrites = pendingWrites; self.now = now
    }

    // MARK: Transport

    private enum Answer { case status(Int, Data), timeout, failed(Error) }

    private func send(_ account: Account, _ path: String, query: [URLQueryItem] = [], method: String = "GET", body: [String: Any]? = nil) async throws -> Answer {
        var outcome: PlaybackHTTPOutcome?
        do {
            let data = try await client.request(account: account, path: path, method: method, query: query, body: body, receipt: { outcome = $0 })
            return .status(outcome?.status ?? 200, data)
        } catch is CancellationError {
            throw CancellationError()
        } catch ClientError.invalidCredentials {
            return .status(401, Data())
        } catch ClientError.server(let code) {
            return .status(code, Data())
        } catch {
            if Task.isCancelled { throw CancellationError() }
            return outcome?.failure == "timeout" ? .timeout : .failed(error)
        }
    }

    private func readData(_ account: Account, _ path: String, query: [URLQueryItem] = []) async throws -> Data {
        switch try await send(account, path, query: query) {
        case .timeout: throw LiveTVError(kind: .network, message: "The server took too long to respond. Retry when connected.")
        case .failed: throw LiveTVError(kind: .network, message: "Could not reach the server. Last loaded guide may be out of date. Retry when connected.")
        case .status(let code, let data):
            switch code {
            case 200..<300: return data
            case 401: throw LiveTVError(kind: .invalidCredentials, message: "This sign-in has expired. Sign in again.", status: 401)
            case 403: throw LiveTVError(kind: .server, message: "This account is not allowed to use this Live TV feature.", status: 403)
            case 404: throw LiveTVError(kind: .server, message: "This channel, program or recording is no longer available.", status: 404)
            default: throw LiveTVError(kind: .server, message: "The server could not complete the Live TV request (\(code)). Retry.", status: code)
            }
        }
    }

    private func read<T: Decodable>(_ type: T.Type, _ account: Account, _ path: String, query: [URLQueryItem] = []) async throws -> T {
        let data = try await readData(account, path, query: query)
        do { return try decoder.decode(T.self, from: data) }
        catch { throw LiveTVError(kind: .invalidResponse, message: "The server returned unreadable Live TV data.") }
    }

    private func readObject(_ account: Account, _ path: String, query: [URLQueryItem] = []) async throws -> [String: JSONValue] {
        try await read([String: JSONValue].self, account, path, query: query)
    }

    private func write(_ account: Account, _ path: String, body: [String: JSONValue]? = nil, method: String = "POST") async throws -> LiveWrite {
        switch try await send(account, path, method: method, body: body.map { $0.mapValues(\.foundation) }) {
        case .timeout: return LiveWrite("unknown", message: "The result is unknown. Refresh schedules before trying again.")
        case .failed: return LiveWrite("unknown", message: "Connection lost. The change may have reached the server; refresh before retrying.")
        case .status(let code, _):
            switch code {
            case 200..<300: return LiveWrite("ok")
            case 401, 403, 404: return LiveWrite("denied", message: "Permission changed or the item was removed. Refresh Live TV.")
            case 500...: return LiveWrite("unknown", message: "The server may have applied the change. Refresh before retrying.")
            default: return LiveWrite("failed", message: "The server refused the change (\(code)). Your original schedule is unchanged locally.")
            }
        }
    }

    private func locked<T>(_ body: () async throws -> T) async rethrows -> T {
        await writes.lock()
        do { let value = try await body(); await writes.unlock(); return value }
        catch { await writes.unlock(); throw error }
    }

    private static func path(_ value: String) -> String { ServerURLs.encode(value) }
    private static func schedulePath(_ series: Bool) -> String { series ? "LiveTv/SeriesTimers" : "LiveTv/Timers" }

    // MARK: Reads

    public func policy(account: Account) async throws -> LivePolicy {
        let user = try await readObject(account, "Users/\(Self.path(account.userId))")
        guard case .object(let fields)? = user["Policy"] else { return LivePolicy() }
        func granted(_ name: String) -> Bool { fields[name]?.booleanValue == true }
        return LivePolicy(access: granted("EnableLiveTvAccess"), manage: granted("EnableLiveTvManagement"),
                          deleteContent: granted("EnableContentDeletion"), userData: granted("EnableUserPreferenceAccess"))
    }

    public func info(account: Account) async throws -> LiveInfo { try await read(LiveInfo.self, account, "LiveTv/Info") }
    public func guideInfo(account: Account) async throws -> LiveGuideInfo { try await read(LiveGuideInfo.self, account, "LiveTv/GuideInfo") }

    /// Jellyfin has no channel search term, so a search reads every authorized channel page (100 at a time), sorts and
    /// filters by name or number, then pages the matches.
    public func channels(account: Account, startIndex: Int = 0, limit: Int = 100, search: String? = nil, favorites: Bool = false) async throws -> LivePage<LiveChannel> {
        precondition(startIndex >= 0 && (1...500).contains(limit), "Channel pages are bounded.")
        if let search, !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            var all: [LiveChannel] = []
            var offset = 0
            while true {
                let page = try await channels(account: account, startIndex: offset, limit: 100, search: nil, favorites: favorites)
                all += page.items
                offset += page.items.count
                if page.items.count < 100 || (page.totalRecordCount > 0 && offset >= page.totalRecordCount) { break }
                if offset >= 50_000 {
                    throw LiveTVError(kind: .invalidResponse, message: "Too many channel pages. Narrow the server's channel list before searching.")
                }
            }
            let matching = LiveRules.sortChannels(all).filter {
                $0.name.range(of: search, options: .caseInsensitive) != nil || ($0.number ?? "").range(of: search, options: .caseInsensitive) != nil
            }
            return LivePage(items: Array(matching.dropFirst(startIndex).prefix(limit)), totalRecordCount: matching.count, startIndex: startIndex)
        }
        var query = [URLQueryItem(name: "startIndex", value: String(startIndex)), URLQueryItem(name: "limit", value: String(limit)),
                     URLQueryItem(name: "enableUserData", value: "true"), URLQueryItem(name: "addCurrentProgram", value: "true")]
        if favorites { query.append(URLQueryItem(name: "isFavorite", value: "true")) }
        return try await read(LivePage<LiveChannel>.self, account, "LiveTv/Channels", query: query)
    }

    public func channel(account: Account, id: String) async throws -> LiveChannel {
        try await read(LiveChannel.self, account, "LiveTv/Channels/\(Self.path(id))")
    }

    /// One bounded guide batch: 1 to 100 channels over a positive window of at most 24 hours.
    public func programs(account: Account, channelIDs: [String], startUTC: String, endUTC: String, startIndex: Int = 0, limit: Int = 500) async throws -> LivePage<LiveProgram> {
        precondition(startIndex >= 0 && (1...500).contains(limit), "Programme pages are bounded.")
        precondition(!channelIDs.isEmpty && channelIDs.count <= 100, "Fetch bounded channel batches.")
        _ = try LiveRules.guidePrograms([], allowedChannels: Set(channelIDs), startUTC: startUTC, endUTC: endUTC)
        let query = [("channelIds", channelIDs.joined(separator: ",")), ("minEndDate", startUTC), ("maxStartDate", endUTC),
                     ("startIndex", String(startIndex)), ("limit", String(limit)), ("enableImages", "true"), ("enableUserData", "true"),
                     ("fields", "Overview")].map { URLQueryItem(name: $0.0, value: $0.1) }
        return try await read(LivePage<LiveProgram>.self, account, "LiveTv/Programs", query: query)
    }

    public func program(account: Account, id: String) async throws -> LiveProgram {
        try await read(LiveProgram.self, account, "LiveTv/Programs/\(Self.path(id))")
    }

    public func recordings(account: Account, startIndex: Int = 0, limit: Int = 100) async throws -> ItemPage {
        precondition(startIndex >= 0 && (1...500).contains(limit), "Recording pages are bounded.")
        return try await read(ItemPage.self, account, "LiveTv/Recordings", query: [URLQueryItem(name: "startIndex", value: String(startIndex)),
            URLQueryItem(name: "limit", value: String(limit)), URLQueryItem(name: "enableUserData", value: "true")])
    }

    public func timers(account: Account, series: Bool = false) async throws -> [LiveSchedule] {
        let object = try await readObject(account, Self.schedulePath(series))
        guard case .array(let rows)? = object["Items"] else { return [] }
        return rows.compactMap { if case .object(let fields) = $0 { return LiveSchedule(fields: fields) }; return nil }
    }

    public func defaults(account: Account, programID: String) async throws -> LiveSchedule {
        LiveSchedule(fields: try await readObject(account, "LiveTv/Timers/Defaults", query: [URLQueryItem(name: "programId", value: programID)]))
    }

    // MARK: Writes

    private func permission(_ account: Account, delete: Bool = false) async throws -> LiveWrite? {
        let rights: LivePolicy
        do { rights = try await policy(account: account) }
        catch let error as LiveTVError { return LiveWrite([401, 403].contains(error.status ?? 0) ? "denied" : "failed", message: error.message) }
        guard rights.access && (delete ? rights.deleteContent : rights.manage) else {
            return LiveWrite("denied", message: "This account cannot \(delete ? "delete recorded files" : "manage recording schedules").")
        }
        return nil
    }

    /// Comparing all four scope fields distinguishes a different airing of the same episode.
    private static func sameScope(_ a: LiveSchedule, _ b: LiveSchedule) -> Bool {
        guard !a.programId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, a.programId == b.programId, a.channelId == b.channelId,
              let start = LiveRules.instant(a.startDate), let end = LiveRules.instant(a.endDate) else { return false }
        return start == LiveRules.instant(b.startDate) && end == LiveRules.instant(b.endDate)
    }

    private static let editable: Set<String> = ["PrePaddingSeconds", "PostPaddingSeconds", "Priority", "IsPrePaddingRequired", "IsPostPaddingRequired",
        "KeepUntil", "RecordAnyTime", "RecordAnyChannel", "RecordNewOnly", "Days", "KeepUpTo", "SkipEpisodesInLibrary"]

    private static func confirmedCreate(_ draft: LiveSchedule, _ saved: LiveSchedule) -> LiveWrite {
        draft.fields.filter { editable.contains($0.key) }.allSatisfy { saved.fields[$0.key] == $0.value }
            ? LiveWrite("ok", schedule: saved)
            : LiveWrite("failed", schedule: saved, message: "A schedule for this airing exists with different recording options. Review or edit that schedule.")
    }

    private static func refusal(_ error: Error) -> String {
        [401, 403, 404].contains((error as? LiveTVError)?.status ?? 0) ? "denied" : "failed"
    }

    /// Records one airing or a series, at most once: an existing matching schedule (or a series rule the program already
    /// links) is confirmed rather than duplicated, and a request whose result is unknown stays pending until a re-read
    /// finds it.
    public func createTimer(account: Account, draft: LiveSchedule, series: Bool = false) async throws -> LiveWrite {
        try await locked {
            if let denied = try await permission(account) { return denied }
            let scope = "\(account.liveKey):\(series):\(draft.programId):\(draft.channelId):\(draft.startDate ?? "null"):\(draft.endDate ?? "null")"
            let before: [LiveSchedule]
            do { before = try await timers(account: account, series: series) }
            catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite("failed", message: "Could not refresh schedules. Nothing was submitted.") }
            // A server link identifies the series rule even when it was created from another episode or airing.
            var seriesProgram: LiveProgram?
            if series {
                do { seriesProgram = try await program(account: account, id: draft.programId) }
                catch is CancellationError { throw CancellationError() }
                catch { return LiveWrite(Self.refusal(error), message: "Could not refresh this program. Nothing was submitted.") }
                if let linkedID = seriesProgram?.seriesTimerId, !linkedID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    let linked = before.filter { $0.id == linkedID && $0.status != "Cancelled" }
                    guard linked.count == 1 else {
                        return LiveWrite("failed", message: "This program identifies an existing series rule that could not be read. Refresh schedules.")
                    }
                    await pendingWrites.remove(scope)
                    var result = Self.confirmedCreate(draft, linked[0])
                    if result.outcome == "failed" { result.message = "This series already has a rule with different recording options. Review or edit that rule." }
                    return result
                }
            }
            let existing = before.filter { Self.sameScope($0, draft) && $0.status != "Cancelled" }
            if existing.count == 1 {
                await pendingWrites.remove(scope)
                return Self.confirmedCreate(draft, existing[0])
            }
            if existing.count > 1 { return LiveWrite("unknown", message: "An earlier request is still unresolved. Check schedules before creating another recording.") }
            if await pendingWrites.contains(scope) {
                return LiveWrite("unknown", message: "An earlier request is still unresolved. Check schedules before creating another recording.")
            }
            let airing: LiveProgram
            if let seriesProgram { airing = seriesProgram } else {
                do { airing = try await program(account: account, id: draft.programId) }
                catch is CancellationError { throw CancellationError() }
                catch { return LiveWrite(Self.refusal(error), message: "Could not refresh this program. Nothing was submitted.") }
            }
            let start = LiveRules.instant(airing.startDate), end = LiveRules.instant(airing.endDate)
            guard airing.channelId == draft.channelId, let start, start == LiveRules.instant(draft.startDate), let end, end > start,
                  end == LiveRules.instant(draft.endDate), end > now(), !series || airing.isSeries else {
                return LiveWrite("failed", message: "This airing changed, ended, or does not support a series recording. Refresh the program.")
            }
            do { _ = try await channel(account: account, id: draft.channelId) }
            catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite(Self.refusal(error), message: "Could not confirm access to this channel. Refresh Live TV before recording.") }
            // Mark BEFORE transport: cancellation can also happen after the server accepted the request.
            await pendingWrites.add(scope)
            let answer = try await write(account, Self.schedulePath(series), body: draft.fields)
            if answer.outcome == "denied" || answer.outcome == "failed" { await pendingWrites.remove(scope); return answer }
            if let after = try? await timers(account: account, series: series) {
                let matches = after.filter { Self.sameScope($0, draft) && $0.status != "Cancelled" }
                if matches.count == 1 {
                    await pendingWrites.remove(scope)
                    return Self.confirmedCreate(draft, matches[0])
                }
            }
            return LiveWrite("unknown", message: "The recording request needs confirmation. Refresh schedules; do not submit it again.")
        }
    }

    /// Applies only the schedule's dirty fields to the server's current copy, then confirms the server kept them.
    public func updateTimer(account: Account, schedule: LiveSchedule, series: Bool = false) async throws -> LiveWrite {
        try await locked {
            if let denied = try await permission(account) { return denied }
            guard !schedule.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return LiveWrite("failed", message: "This schedule has no identity. Refresh schedules.")
            }
            let path = "\(Self.schedulePath(series))/\(Self.path(schedule.id))"
            let refreshed: LiveSchedule
            do { refreshed = LiveSchedule(fields: try await readObject(account, path)) }
            catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite("failed", message: "Could not refresh the schedule. Nothing was submitted.") }
            let dirty = schedule.fields.filter { schedule.dirtyFields.contains($0.key) }
            let updated: LiveSchedule
            do { updated = try LiveRules.editSchedule(refreshed, edits: dirty, series: series) }
            catch { return LiveWrite("failed", message: "The provider no longer supports one of these edits. Refresh the form.") }
            if dirty.isEmpty { return LiveWrite("ok", schedule: refreshed) }
            let answer = try await write(account, path, body: updated.fields)
            guard answer.outcome == "ok" else { return answer }
            guard let after = try? LiveSchedule(fields: await readObject(account, path)) else {
                return LiveWrite("unknown", message: "The update was accepted, but its saved values could not be read. Refresh schedules.")
            }
            return dirty.allSatisfy({ after.fields[$0.key] == $0.value })
                ? LiveWrite("ok", schedule: after)
                : LiveWrite("failed", schedule: after, message: "The server did not retain every edit. Review its saved values before retrying.")
        }
    }

    public func cancelTimer(account: Account, id: String, series: Bool = false) async throws -> LiveWrite {
        try await locked {
            if let denied = try await permission(account) { return denied }
            let answer = try await write(account, "\(Self.schedulePath(series))/\(Self.path(id))", method: "DELETE")
            guard answer.outcome == "ok" else { return answer }
            if let after = try? await timers(account: account, series: series), !after.contains(where: { $0.id == id && $0.status != "Cancelled" }) {
                return LiveWrite("ok")
            }
            return LiveWrite("unknown", message: "Cancellation was accepted but could not be confirmed. Refresh schedules.")
        }
    }

    /// Deletes a recorded file through the permission-checked content endpoint (the Live TV DELETE only unlists it), then
    /// confirms both the listing and the item are gone.
    public func deleteRecording(account: Account, id: String) async throws -> LiveWrite {
        try await locked {
            let rights: LivePolicy
            do { rights = try await policy(account: account) }
            catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite("failed", message: "Could not refresh recording permissions. Nothing was deleted.") }
            guard rights.access else { return LiveWrite("denied", message: "This account cannot access Live TV recordings.") }
            let path = "LiveTv/Recordings/\(Self.path(id))"
            let recording: MediaItem
            do { recording = try await read(MediaItem.self, account, path) }
            catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite("failed", message: "Could not refresh this recording. Nothing was deleted.") }
            if recording.canDelete == false || (recording.canDelete != true && !rights.deleteContent) {
                return LiveWrite("denied", message: "This account cannot delete this recorded file.")
            }
            let itemPath = "Items/\(Self.path(id))"
            let answer = try await write(account, itemPath, method: "DELETE")
            guard answer.outcome == "ok" else { return answer }
            do {
                _ = try await read(MediaItem.self, account, path)
                return LiveWrite("unknown", message: "The recorded file is still listed. Refresh recordings before retrying.")
            } catch let error as LiveTVError where error.status == 404 {
                do {
                    _ = try await read(MediaItem.self, account, itemPath)
                    return LiveWrite("unknown", message: "The recorded item is still available. Refresh recordings.")
                } catch let error as LiveTVError where error.status == 404 {
                    return LiveWrite("ok")
                } catch is CancellationError { throw CancellationError() }
                catch { return LiveWrite("unknown", message: "Content deletion could not be confirmed. Refresh recordings.") }
            } catch is CancellationError { throw CancellationError() }
            catch { return LiveWrite("unknown", message: "Deletion was accepted but could not be confirmed. Refresh recordings.") }
        }
    }

    /// Stars or unstars a channel for this viewer and returns the server's channel afterwards.
    public func favorite(account: Account, id: String, favorite: Bool) async throws -> LiveChannel {
        let rights = try? await policy(account: account)
        guard let rights, rights.access, rights.userData else {
            throw LiveTVError(kind: .server, message: "This account cannot change channel favorites.", status: 403)
        }
        let answer = try await write(account, "Users/\(Self.path(account.userId))/FavoriteItems/\(Self.path(id))", method: favorite ? "POST" : "DELETE")
        guard answer.outcome == "ok" else { throw LiveTVError(kind: .server, message: answer.message ?? "Could not change the favorite.") }
        return try await channel(account: account, id: id)
    }
}
