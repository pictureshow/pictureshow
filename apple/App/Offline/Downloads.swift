// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Network
import AVFoundation
import Security
import os
import PictureshowCore

private let log = Logger(subsystem: "app.pictureshow", category: "downloads")

/// What the viewer sees of one download in flight. Percent only with a known total (OFF-03b).
struct DownloadProgress: Equatable {
    var received: Int64 = 0
    var total: Int64?
    /// An HLS download knows how much of the running time it has, not how many bytes are left.
    var fraction: Double?
    var percent: Int? {
        if let fraction { return Int((fraction * 100).rounded(.down)) }
        guard let total, total > 0 else { return nil }
        return Int((Double(received) / Double(total) * 100).rounded(.down))
    }
}

/// A file this device can play without the server.
struct LocalMedia: Equatable {
    let accountKey: String
    let entry: OfflineEntry
    let url: URL
    let subtitles: [LocalSubtitle]
}

struct LocalSubtitle: Equatable {
    let serverIndex: Int
    let label: String
    let language: String?
    let url: URL
}

/// A server place kept over this device's during a sync (OFF-08). The viewer is told, once.
struct KeptServerPlace: Identifiable, Equatable {
    var id: String { accountKey + "|" + itemID }
    let accountKey: String
    let itemID: String
    let title: String
}

/// The quality sheet: what the server allows, what each choice costs, and what fits.
struct DownloadOptions {
    var choices: [OfflinePresetChoice]
    var originalPlaysHere: Bool
    var freeBytes: Int64?
    var capBytes: Int64?
    var usedBytes: Int64
}

/// The download engine (OFF-01..12 on iOS/iPadOS, OFF-PD02). The pure rules — job states, budget, manifest commit,
/// outbox, resume and next-episode choice — live in PictureshowCore and are frozen by `spec/vectors/offline-*`;
/// this class owns what depends on the OS: files, background transfers, the network and the clock.
///
/// Originals go through a background `URLSession`; a Compatible conversion is the server's finite HLS saved
/// by `AVAssetDownloadURLSession`. Both belong to the OS while unfinished, so a transfer continues after the
/// app is gone and the next launch re-attaches to it instead of starting again.
@MainActor final class Downloads: NSObject, ObservableObject {
    #if os(iOS)
    static let supported = true
    #else
    static let supported = false
    #endif
    /// OFF-PD03: what a TV says where Downloads would be.
    static let unsupportedText = "Offline downloads aren't supported on this device"

    struct Viewer {
        var account: Account
        let files: OfflineFiles
        var manifest: OfflineManifest
        var outbox: [String: OfflineOutboxEntry]
        var lastKnown: [String: OfflineUserData]
        var sequence: Int64
    }

    @Published private(set) var viewers: [String: Viewer] = [:]
    @Published private(set) var progress: [String: DownloadProgress] = [:]
    @Published private(set) var kept: [KeptServerPlace] = []
    @Published private(set) var networkUp = true
    @Published private(set) var networkExpensive = false
    /// Viewers whose server can't issue a download token without their password (Quick Connect off).
    @Published var passwordNeeded: Set<String> = []

    private let api: OfflineAPI
    private var fileSession: URLSession!
    /// Subtitles and artwork: cross-origin redirects are refused, so the viewer's token stays on their server.
    private let sidecarSession = Downloads.sidecarSession(.default)
    nonisolated static func sidecarSession(_ configuration: URLSessionConfiguration) -> URLSession {
        URLSession(configuration: configuration, delegate: OriginRedirects(), delegateQueue: nil)
    }
    #if os(iOS)
    private var hlsSession: AVAssetDownloadURLSession!
    #endif
    private let monitor = NWPathMonitor()
    private var rests: [String: Task<Void, Never>] = [:]
    private var restDelay: [String: TimeInterval] = [:]
    private struct JobKey: Hashable { let viewer: String; let entry: String }
    private var storageDenied: [JobKey: Int64] = [:]
    private var storageTimer: Task<Void, Never>?
    private var revocationTimer: Task<Void, Never>?
    /// Tasks we stopped on purpose (pause, delete, a lost permitted network): their cancellation isn't a failure.
    private var stopping: Set<Int> = []
    private var syncing: Set<String> = []
    /// Where an HLS download was put by the system before it finished, keyed by task.
    private var hlsLocations: [Int: URL] = [:]
    /// Tasks whose file was moved into place, and tasks the server answered with an error (-1: an HTML page).
    private var finished: Set<Int> = []
    private var failedStatus: [Int: Int] = [:]
    private var started = false

    init(deviceName: String, version: String) {
        api = OfflineAPI(deviceName: deviceName, version: version)
        super.init()
    }

    // MARK: Start-up

    /// Loads every viewer's ledger, re-attaches transfers the OS kept running, and resumes the rest.
    func start(accounts: [Account]) {
        guard Self.supported, !started else { return }
        started = true
        // A viewer who signed out owns nothing here; one whose session expired keeps its downloads (OFF-09).
        for account in accounts where !account.signedOut { _ = viewer(for: account) }
        loadStoredViewers()
        #if os(iOS)
        let files = URLSessionConfiguration.background(withIdentifier: "app.pictureshow.downloads.files")
        files.sessionSendsLaunchEvents = true
        files.isDiscretionary = false
        fileSession = URLSession(configuration: files, delegate: self, delegateQueue: .main)
        let hls = URLSessionConfiguration.background(withIdentifier: "app.pictureshow.downloads.hls")
        hls.sessionSendsLaunchEvents = true
        hlsSession = AVAssetDownloadURLSession(configuration: hls, assetDownloadDelegate: self, delegateQueue: .main)
        #endif
        monitor.pathUpdateHandler = { [weak self] path in
            Task { @MainActor in self?.pathChanged(up: path.status == .satisfied, expensive: path.isExpensive) }
        }
        monitor.start(queue: .main)
        Task { await reattach() }
        revocationTimer = Task { [weak self] in
            while !Task.isCancelled {
                await self?.retryRevocations()
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    /// A viewer signed in again: later syncs use the newest credentials.
    func signedIn(_ account: Account) {
        guard Self.supported else { return }
        _ = viewer(for: account)
        viewers[account.key]?.account = account
        if started { pump(account.key); Task { await sync(account.key) } }
    }

    /// OFF-09 at start-up: downloads and download tokens that belong to no viewer on this device (a removal cut short,
    /// an older build) are cleared. A token still owed a revocation stays until that retry reaches its server.
    private func loadStoredViewers() {
        let present = (try? FileManager.default.contentsOfDirectory(atPath: OfflineFiles.base.path)) ?? []
        let knownDirs = Set(viewers.values.map { OfflineFiles.viewerDirectory(accountKey: $0.account.key) })
        for dir in OfflineOrphans.folders(present: present, known: knownDirs) {
            log.notice("Clearing downloads that belong to no viewer: \(dir, privacy: .public)")
            try? FileManager.default.removeItem(at: OfflineFiles.base.appendingPathComponent(dir))
        }
        let knownDevices = Set(viewers.values.map { downloadDevice($0.account) })
        let owed = Set(PendingRevocations.all().map(\.deviceID))
        for device in OfflineOrphans.tokens(present: DownloadTokens.allDevices(), known: knownDevices, owed: owed) {
            DownloadTokens.delete(device)
        }
    }

    @discardableResult
    private func viewer(for account: Account) -> Viewer? {
        if let v = viewers[account.key] { return v }
        let files = OfflineFiles(accountKey: account.key)
        guard FileManager.default.fileExists(atPath: files.manifestURL.path) else {
            viewers[account.key] = Viewer(account: account, files: files, manifest: OfflineManifest(), outbox: [:], lastKnown: [:], sequence: 0)
            return viewers[account.key]
        }
        let manifest = (try? String(contentsOf: files.manifestURL, encoding: .utf8)).flatMap { offlineDecodeManifest(text: $0) } ?? OfflineManifest()
        let outbox = (try? JSONDecoder().decode([String: OfflineOutboxEntry].self, from: Data(contentsOf: files.outboxURL))) ?? [:]
        let lastKnown = (try? JSONDecoder().decode([String: OfflineUserData].self, from: Data(contentsOf: files.lastKnownURL))) ?? [:]
        let sequence = outbox.values.map(\.sequence).max() ?? 0
        viewers[account.key] = Viewer(account: account, files: files, manifest: manifest, outbox: outbox, lastKnown: lastKnown, sequence: sequence)
        reconcileFiles(account.key)
        return viewers[account.key]
    }

    /// OFF-12: a ready download whose files are gone (or were evicted) says so instead of failing in the player.
    private func reconcileFiles(_ key: String) {
        guard var v = viewers[key] else { return }
        var inventory: [String: Int64] = [:]
        for entry in v.manifest.entries {
            for resource in entry.resources {
                let url = v.files.resourceURL(entry.id, resource)
                if FileManager.default.fileExists(atPath: url.path) { inventory[offlineEntryDir(entryId: entry.id) + "/" + resource.path] = OfflineFiles.size(of: url) }
            }
        }
        let result = v.manifest.reconcile(inventory: inventory)
        v.manifest = result.manifest
        viewers[key] = v
        if !result.changedEntries.isEmpty { saveManifest(key) }
    }

    #if os(iOS)
    private func reattach() async {
        let fileTasks = await fileSession.allTasks
        let hlsTasks = await hlsSession.allTasks
        var live: Set<String> = []
        for task in fileTasks + hlsTasks {
            guard let tag = TaskTag(task.taskDescription) else { task.cancel(); continue }
            guard let entry = entry(tag.viewer, tag.entryID), entry.job.attempt == tag.attempt, Self.running(entry.job.phase) else {
                stopping.insert(task.taskIdentifier); task.cancel(); continue
            }
            live.insert(tag.viewer + "|" + tag.entryID)
        }
        for (key, v) in viewers {
            for entry in v.manifest.entries where !live.contains(key + "|" + entry.id) {
                if Self.running(entry.job.phase) || entry.job.phase == .deleting {
                    apply(key, entry.id, OfflineJobEvent(op: "process-restart"))
                }
            }
            pump(key)
        }
        for key in viewers.keys { await sync(key) }
    }
    #else
    private func reattach() async {}
    #endif

    static func running(_ phase: OfflinePhase) -> Bool { [.preparing, .transferring, .verifying].contains(phase) }

    // MARK: Reading

    func entries(_ account: Account?) -> [OfflineEntry] {
        guard let account else { return [] }
        return viewers[account.key]?.manifest.entries.sorted { $0.createdSequence < $1.createdSequence } ?? []
    }

    /// The download for this title on this device, the ready one first.
    func entry(for account: Account?, itemID: String) -> OfflineEntry? {
        let matches = entries(account).filter { $0.itemId == itemID }
        return matches.first { $0.job.phase == .ready } ?? matches.first
    }

    private func entry(_ key: String, _ entryID: String) -> OfflineEntry? {
        viewers[key]?.manifest.entries.first { $0.id == entryID }
    }

    func progress(_ account: Account, _ entryID: String) -> DownloadProgress? { progress[account.key + "|" + entryID] }

    func hasReady(_ account: Account?) -> Bool { entries(account).contains { $0.job.phase == .ready } }

    func usage(_ account: Account?) -> (used: Int64, cap: Int64?) {
        guard let account, let v = viewers[account.key] else { return (0, nil) }
        return (v.manifest.budget.usedBytes, v.manifest.budget.capBytes)
    }

    func wifiOnly(_ account: Account?) -> Bool { account.flatMap { viewers[$0.key]?.manifest.wifiOnly } ?? true }

    func outbox(_ account: Account, _ itemID: String) -> OfflineOutboxEntry? { viewers[account.key]?.outbox[itemID] }
    func lastKnown(_ account: Account, _ itemID: String) -> OfflineUserData? { viewers[account.key]?.lastKnown[itemID] }

    func local(_ account: Account, itemID: String) -> LocalMedia? {
        guard let v = viewers[account.key], let entry = v.manifest.entries.first(where: { $0.itemId == itemID && $0.job.phase == .ready }) else { return nil }
        return local(account.key, entry)
    }

    func local(_ key: String, _ entry: OfflineEntry) -> LocalMedia? {
        guard let v = viewers[key], let media = entry.resources.first(where: { $0.role == .media }) else { return nil }
        let url = v.files.resourceURL(entry.id, media)
        guard FileManager.default.fileExists(atPath: url.path) else {
            apply(key, entry.id, OfflineJobEvent(op: "missing"))
            return nil
        }
        let subtitles = entry.tracks.filter { $0.type == "subtitle" }.compactMap { track -> LocalSubtitle? in
            guard let rid = track.resourceId, let resource = entry.resources.first(where: { $0.id == rid && $0.complete }) else { return nil }
            return LocalSubtitle(serverIndex: track.serverIndex, label: track.label, language: track.language, url: v.files.resourceURL(entry.id, resource))
        }
        return LocalMedia(accountKey: key, entry: entry, url: url, subtitles: subtitles)
    }

    /// What follows a local viewing (OFF-11): only a ready download of the next episode.
    func next(after media: LocalMedia, serverAnswered: Bool = false, serverNext: String? = nil) -> LocalMedia? {
        let ready = entries(viewers[media.accountKey]?.account).filter { $0.job.phase == .ready }
        guard let next = offlineNextEpisode(entries: ready, current: media.entry, serverAnswered: serverAnswered, serverNext: serverNext) else { return nil }
        return local(media.accountKey, next)
    }

    // MARK: Choosing a quality

    func options(account: Account, item: MediaItem) async throws -> DownloadOptions {
        let policy = try await api.policy(account)
        let facts = try await api.itemFacts(account, itemID: item.id)
        let candidate = OfflineCandidate(itemId: item.id, type: item.type, isInfinite: facts.isInfinite, sizeBytes: facts.size, bitrate: facts.bitrate,
                                         runtimeTicks: item.runTimeTicks, canDownload: facts.canDownload)
        let qualified: Set<OfflinePreset> = [.video720, .video1080]
        let choices = offlinePresets(policy: OfflinePolicy(enableDownloads: policy.downloads, enablePlayback: policy.playback,
                                                           videoTranscoding: policy.video, audioTranscoding: policy.audio),
                                     candidate: candidate, qualified: qualified)
            .filter { [.original, .video720, .video1080].contains($0.preset) }
        let v = viewer(for: account)
        return DownloadOptions(choices: choices, originalPlaysHere: OriginalSupport.playsHere(item.mediaSources.first),
                               freeBytes: OfflineFiles.freeBytes(), capBytes: v?.manifest.budget.capBytes ?? offlineDefaultCap(quotaBytes: OfflineFiles.totalBytes()),
                               usedBytes: v?.manifest.budget.usedBytes ?? 0)
    }

    // MARK: Viewer actions

    func download(account: Account, item: MediaItem, preset: OfflinePreset, cellular: Bool, subtitleLanguage: String? = nil) {
        guard Self.supported, var v = viewer(for: account) else { return }
        let id = offlineEntryId(itemId: item.id, preset: preset)
        if let existing = v.manifest.entries.first(where: { $0.id == id }) {
            if existing.job.phase == .failed || existing.job.phase == .missing { redownload(account, existing.id) }
            return
        }
        if v.manifest.budget.capBytes == nil {
            v.manifest.budget.capBytes = offlineDefaultCap(quotaBytes: OfflineFiles.totalBytes())
        }
        let source = item.mediaSources.first
        let isVideo = preset != .original
        var resources: [OfflineResource] = []
        let ext = isVideo ? "movpkg" : OriginalSupport.fileExtension(source?.container)
        resources.append(OfflineResource(id: "media", role: .media, path: "media.\(ext)", required: true, mimeType: nil))
        var tracks: [OfflineTrack] = []
        let streams = source?.mediaStreams ?? []
        for audio in streams where audio.type == "Audio" {
            let kept = !isVideo || audio.index == (source?.defaultAudioStreamIndex ?? streams.first { $0.type == "Audio" }?.index)
            tracks.append(OfflineTrack(serverIndex: audio.index, type: "audio", label: audio.displayTitle ?? audio.language ?? "Audio",
                                       language: audio.language, isDefault: audio.isDefault, isForced: false, codec: audio.codec, resourceId: nil, available: kept))
        }
        // Text subtitles are saved beside the media so they work offline in both qualities, and stay selectable.
        let preferred = subtitleLanguage
        let texts = streams.filter { $0.type == "Subtitle" && $0.isTextSubtitleStream }
        let chosen = texts.filter { $0.isDefault || $0.isForced || ($0.language != nil && $0.language == preferred) }
        let saved = Array((chosen.isEmpty ? Array(texts.prefix(2)) : chosen).prefix(4))
        for sub in streams where sub.type == "Subtitle" {
            let save = saved.contains { $0.index == sub.index }
            let rid = save ? "sub-\(sub.index)" : nil
            if save { resources.append(OfflineResource(id: rid!, role: .subtitle, path: "sub-\(sub.index).vtt", required: false, serverStreamIndex: sub.index, mimeType: "text/vtt")) }
            tracks.append(OfflineTrack(serverIndex: sub.index, type: "subtitle", label: sub.displayTitle ?? sub.language ?? "Subtitles", language: sub.language,
                                       isDefault: sub.isDefault, isForced: sub.isForced, codec: sub.codec, resourceId: rid,
                                       available: save || (!isVideo && !sub.isExternal)))
        }
        resources.append(OfflineResource(id: "art", role: .artwork, path: "art.jpg", required: false, mimeType: "image/jpeg"))
        let kind: OfflineMediaKind = item.type == "Episode" ? .episode : .movie
        let labels = OfflineLabels(title: item.name, seriesName: item.seriesName, seriesId: item.seriesId, seasonId: item.seasonId,
                                   seasonNumber: item.parentIndexNumber, episodeNumber: item.indexNumber, year: item.productionYear, overview: item.overview)
        let entry = OfflineEntry(id: id, itemId: item.id, kind: kind, sourceId: source?.id, sourceETag: source?.eTag, container: source?.container,
                                 preset: preset, runtimeTicks: item.runTimeTicks ?? source?.runTimeTicks, labels: labels, resources: resources, tracks: tracks,
                                 job: OfflineJob(cellularAllowed: cellular), createdSequence: v.manifest.nextSequence)
        v.manifest.entries.append(entry)
        v.manifest.nextSequence += 1
        if v.lastKnown[item.id] == nil {
            v.lastKnown[item.id] = OfflineUserData(positionTicks: item.userData.playbackPositionTicks, played: item.isWatched)
        }
        viewers[account.key] = v
        saveManifest(account.key); saveLastKnown(account.key)
        estimates[account.key + "|" + id] = estimate(item: item, preset: preset)
        pump(account.key)
    }

    private var estimates: [String: Int64] = [:]

    private func estimate(item: MediaItem, preset: OfflinePreset) -> Int64 {
        let seconds = Double(item.runTimeTicks ?? 0) / 10_000_000
        switch preset {
        case .video720: return Int64(seconds * (4_000_000 + 128_000) / 8)
        case .video1080: return Int64(seconds * (8_000_000 + 128_000) / 8)
        default: return Int64(seconds * Double(item.mediaSources.first?.bitrate ?? 8_000_000) / 8)
        }
    }

    func pause(_ account: Account, _ entryID: String) { apply(account.key, entryID, OfflineJobEvent(op: "pause")) }
    func resume(_ account: Account, _ entryID: String) { apply(account.key, entryID, OfflineJobEvent(op: "resume")); pump(account.key) }
    func retry(_ account: Account, _ entryID: String) { apply(account.key, entryID, OfflineJobEvent(op: "retry")); pump(account.key) }
    func redownload(_ account: Account, _ entryID: String) { apply(account.key, entryID, OfflineJobEvent(op: "redownload")); pump(account.key) }
    func delete(_ account: Account, _ entryID: String) { apply(account.key, entryID, OfflineJobEvent(op: "delete")); pump(account.key) }

    /// OFF-06a: a per-download, explicit choice to use mobile data.
    func setCellular(_ account: Account, _ entryID: String, _ allowed: Bool) {
        apply(account.key, entryID, OfflineJobEvent(op: "set-cellular", cellularAllowed: allowed))
        if allowed, let e = entry(account.key, entryID), e.job.phase == .waitingNetwork { apply(account.key, entryID, OfflineJobEvent(op: "network-back")) }
        pump(account.key)
    }

    func setWifiOnly(_ account: Account, _ value: Bool) {
        guard viewers[account.key] != nil else { return }
        viewers[account.key]?.manifest.wifiOnly = value
        saveManifest(account.key)
        pathChanged(up: networkUp, expensive: networkExpensive)
    }

    /// OFF-06c: lowering the cap pauses the newest reservations that no longer fit; bytes are never evicted.
    func setCap(_ account: Account, _ bytes: Int64) {
        guard var v = viewers[account.key] else { return }
        let newest = v.manifest.entries.sorted { $0.createdSequence > $1.createdSequence }.map(\.id)
        let result = v.manifest.budget.setCap(capBytes: bytes, jobNewestFirst: newest)
        v.manifest.budget = result.budget
        viewers[account.key] = v
        saveManifest(account.key)
        for id in result.pauseJobs { apply(account.key, id, OfflineJobEvent(op: "pause", reason: "cap")) }
    }

    func deleteAll(_ account: Account) {
        for entry in entries(account) { delete(account, entry.id) }
    }

    func dismissKept(_ notice: KeptServerPlace) {
        kept.removeAll { $0 == notice }
        guard let v = viewers[notice.accountKey], let entry = v.outbox[notice.itemID],
              let result = try? reduceOfflineOutbox(entry, OfflineOutboxEvent(op: "dismiss-conflict")) else { return }
        setOutbox(notice.accountKey, notice.itemID, result.entry)
    }

    /// OFF-09: signing out or removing a viewer stops their downloads, deletes their files and metadata,
    /// and revokes the download set's token (retried later if the server is out of reach).
    func removeViewer(_ account: Account) {
        guard Self.supported else { return }
        let key = account.key
        let files = viewers[key]?.files ?? OfflineFiles(accountKey: key)
        #if os(iOS)
        let prefix = OfflineFiles.viewerDirectory(accountKey: key)
        Task {
            for task in (await fileSession?.allTasks ?? []) + (await hlsSession?.allTasks ?? []) where TaskTag(task.taskDescription)?.viewerDir == prefix {
                stopping.insert(task.taskIdentifier); task.cancel()
            }
        }
        #endif
        for k in progress.keys where k.hasPrefix(key + "|") { progress[k] = nil }
        kept.removeAll { $0.accountKey == key }
        viewers[key] = nil
        try? FileManager.default.removeItem(at: files.root)
        let device = downloadDevice(account)
        if let token = DownloadTokens.read(device) {
            PendingRevocations.add(.init(server: account.server.url.absoluteString, deviceID: device))
            Task { await revoke(server: account.server, deviceID: device, token: token) }
        }
    }

    // MARK: Watching a download

    /// Saves the place of a local viewing in the outbox (OFF-07), to be brought to the server later.
    func recordViewing(_ key: String, itemID: String, positionTicks: Int64) { outboxEvent(key, itemID, op: "viewing", positionTicks: positionTicks) }
    func recordCompleted(_ key: String, itemID: String) { outboxEvent(key, itemID, op: "completed") }

    private func outboxEvent(_ key: String, _ itemID: String, op: String, positionTicks: Int64? = nil) {
        guard var v = viewers[key] else { return }
        v.sequence += 1
        viewers[key] = v
        let event = OfflineOutboxEvent(op: op, server: v.account.server.url.absoluteString, viewer: v.account.userId, itemId: itemID,
                                       positionTicks: positionTicks, sequence: v.sequence, base: v.lastKnown[itemID] ?? OfflineUserData())
        guard let result = try? reduceOfflineOutbox(v.outbox[itemID], event) else { log.error("outbox \(op, privacy: .public) threw"); return }
        log.info("outbox local \(op, privacy: .public) phase \(result.entry?.phase ?? "gone", privacy: .public) ignored \(result.ignored ?? "-", privacy: .public)")
        setOutbox(key, itemID, result.entry)
    }

    /// An online viewing of a title that's also downloaded teaches the download where the server now is,
    /// without making the viewer wait, so the next local viewing starts there.
    func onlineViewingStopped(account: Account, itemID: String) {
        guard Self.supported, viewers[account.key]?.manifest.entries.contains(where: { $0.itemId == itemID }) == true else { return }
        Task {
            guard (try? await api.sameViewer(account)) == true, let data = try? await api.userData(account, itemID: itemID) else { return }
            let current = OfflineUserData(positionTicks: data.positionTicks, played: data.played, playCount: data.playCount, lastPlayedDate: data.lastPlayedDate)
            viewers[account.key]?.lastKnown[itemID] = current
            saveLastKnown(account.key)
            if let entry = viewers[account.key]?.outbox[itemID],
               let result = try? reduceOfflineOutbox(entry, OfflineOutboxEvent(op: "online-viewing", current: current)) {
                setOutbox(account.key, itemID, result.entry)
                if result.entry == nil { kept.removeAll { $0.accountKey == account.key && $0.itemID == itemID } }
            }
        }
    }

    /// OFF-07/08: brings each unsent place to the server, server-wins: a field the server changed since its base stays the server's.
    func sync(_ key: String) async {
        guard Self.supported, !syncing.contains(key), let v = viewers[key], v.account.isSignedIn, !v.outbox.isEmpty, networkUp else { return }
        syncing.insert(key); defer { syncing.remove(key) }
        let account = v.account
        guard (try? await api.sameViewer(account)) == true else { return }
        // Each await can outlast the sign-in: a viewer who signed out (or in again as someone new) sends nothing more.
        func stillOwned() -> Bool { viewers[key]?.account.accessToken == account.accessToken }
        for itemID in v.outbox.keys.sorted() {
            guard stillOwned() else { return }
            guard var entry = viewers[key]?.outbox[itemID] else { continue }
            var event = OfflineOutboxEvent(op: entry.phase == "writing" ? "process-restart" : "reconnect", server: account.server.url.absoluteString, viewer: account.userId)
            var steps = 0
            while steps < 8 {
                steps += 1
                guard let result = try? reduceOfflineOutbox(entry, event) else { log.error("outbox \(event.op, privacy: .public) threw"); break }
                log.info("outbox \(event.op, privacy: .public) -> \(result.effect, privacy: .public) phase \(result.entry?.phase ?? "gone", privacy: .public)")
                setOutbox(key, itemID, result.entry)
                guard let next = result.entry else {
                    // Acknowledged: the server now holds what it last answered, which is the base for the next change.
                    // Without this, the next sync reads our own write as someone else's and holds it as a conflict.
                    if let current = Self.serverStateAfter(result, event) { viewers[key]?.lastKnown[itemID] = current; saveLastKnown(key) }
                    break
                }
                entry = next
                switch result.effect {
                case "read":
                    guard let d = try? await api.userData(account, itemID: itemID) else { return }
                    event = OfflineOutboxEvent(op: entry.phase == "writing" ? "readback" : "server-state", current: Self.core(d))
                case "write":
                    guard stillOwned() else { return }
                    let write = result.write ?? OfflineUserDataWrite()
                    guard let status = try? await api.writeUserData(account, itemID: itemID, positionTicks: write.positionTicks, played: write.played) else { return }
                    log.info("outbox write status \(status, privacy: .public)")
                    event = OfflineOutboxEvent(op: "write-result", status: status)
                case "readback":
                    guard let d = try? await api.userData(account, itemID: itemID) else { return }
                    event = OfflineOutboxEvent(op: "readback", current: Self.core(d))
                case "conflict":
                    let title = viewers[key]?.manifest.entries.first { $0.itemId == itemID }?.labels.title ?? "A download"
                    let notice = KeptServerPlace(accountKey: key, itemID: itemID, title: title)
                    if !kept.contains(notice) { kept.append(notice) }
                    steps = 99
                default:
                    if let d = try? await api.userData(account, itemID: itemID) { viewers[key]?.lastKnown[itemID] = Self.core(d); saveLastKnown(key) }
                    steps = 99
                }
            }
        }
    }

    /// When an outbox step finishes an entry, the server state it was judged against is the new last-known state.
    nonisolated static func serverStateAfter(_ result: OfflineOutboxResult, _ event: OfflineOutboxEvent) -> OfflineUserData? {
        result.entry == nil ? event.current : nil
    }

    private static func core(_ d: OfflineAPI.UserData) -> OfflineUserData {
        OfflineUserData(positionTicks: d.positionTicks, played: d.played, playCount: d.playCount, lastPlayedDate: d.lastPlayedDate)
    }

    private func setOutbox(_ key: String, _ itemID: String, _ entry: OfflineOutboxEntry?) {
        guard viewers[key] != nil else { return }
        viewers[key]?.outbox[itemID] = entry
        if let entry, entry.phase == "synced" {
            viewers[key]?.lastKnown[itemID] = entry.base
            viewers[key]?.outbox[itemID] = nil
            saveLastKnown(key)
        }
        saveOutbox(key)
    }

    // MARK: The job machine

    /// Every state change goes through the shared reducer; this only carries out the effects it names.
    private func apply(_ key: String, _ entryID: String, _ event: OfflineJobEvent) {
        guard var v = viewers[key], let index = v.manifest.entries.firstIndex(where: { $0.id == entryID }) else { return }
        let before = v.manifest.entries[index]
        guard let result = try? reduceOfflineJob(before.job, event) else { return }
        if let ignored = result.ignored {
            if event.op != "network-back" { log.debug("job \(event.op, privacy: .public) ignored: \(ignored, privacy: .public)") }
            return
        }
        if let job = result.job { v.manifest.entries[index].job = job } else { v.manifest.entries.remove(at: index) }
        viewers[key] = v
        saveManifest(key)
        let entry = v.manifest.entries.first { $0.id == entryID } ?? before
        for effect in result.effects {
            switch effect {
            case "start-attempt": startAttempt(key, entryID, attempt: entry.job.attempt)
            case "stop-transfer": stopTransfers(key, entryID, keepResume: event.op != "delete")
            case "verify": verify(key, entryID, attempt: entry.job.attempt)
            case "commit-ready": commit(key, entryID, attempt: entry.job.attempt)
            case "discard-partial": discardPartial(key, entryID)
            case "delete-owned": deleteOwned(key, entryID)
            default: break
            }
        }
        if result.job == nil || [.ready, .failed, .paused, .deleting].contains(result.job?.phase ?? .ready) { progress[key + "|" + entryID] = nil }
        if !Self.running(entry.job.phase) { pump(key) }
    }

    private func pump(_ key: String) {
        guard let v = viewers[key], v.account.isSignedIn else { return }
        let active = v.manifest.entries.filter { Self.running($0.job.phase) }.count
        let queued = v.manifest.entries.filter { $0.job.phase == .queued }.sorted { $0.createdSequence < $1.createdSequence }.map(\.id)
        for id in offlineAdmit(activeCount: active, queuedInOrder: queued) {
            guard let entry = entry(key, id) else { continue }
            if !networkAllows(v.manifest, entry.job) { apply(key, id, OfflineJobEvent(op: "network-lost")); continue }
            apply(key, id, OfflineJobEvent(op: "admit"))
        }
    }

    private func networkAllows(_ manifest: OfflineManifest, _ job: OfflineJob) -> Bool {
        networkUp && (!networkExpensive || !manifest.wifiOnly || job.cellularAllowed)
    }

    private func startAttempt(_ key: String, _ entryID: String, attempt: Int64) {
        Task {
            guard let v = viewers[key], let entry = entry(key, entryID), entry.job.attempt == attempt else { return }
            let account = v.account
            let device = downloadDevice(account)
            let token: String
            do { token = try await downloadToken(account, device: device) }
            catch OfflineAPI.Failure.unreachable { apply(key, entryID, OfflineJobEvent(op: "network-lost")); rest(key, entryID); return }
            catch TokenError.needsPassword {
                passwordNeeded.insert(key)
                apply(key, entryID, OfflineJobEvent(op: "fail", attempt: attempt, reason: "needs-password")); return
            }
            catch { apply(key, entryID, OfflineJobEvent(op: "fail", attempt: attempt, reason: "token")); return }
            guard reserve(key, entryID) else { return }
            apply(key, entryID, OfflineJobEvent(op: "prepared", attempt: attempt))
            await fetchSidecars(key, entryID, account: account, device: device, token: token)
            guard let fresh = self.entry(key, entryID), fresh.job.attempt == attempt, fresh.job.phase == .transferring else { return }
            launchMedia(key, fresh, account: account, device: device, token: token)
        }
    }

    private func reserve(_ key: String, _ entryID: String) -> Bool {
        guard var v = viewers[key], let entry = entry(key, entryID) else { return false }
        let want = max(estimates[key + "|" + entryID] ?? Int64(Double(entry.runtimeTicks ?? 0) / 10_000_000 * 8_128_000 / 8), 64 << 20)
        let have = v.manifest.budget.reservations[entryID] ?? 0
        guard want > have else { return true }
        do {
            let result = try v.manifest.budget.reserve(job: entryID, bytes: want - have, freeBytes: OfflineFiles.freeBytes())
            guard result.granted else {
                storageDenied[JobKey(viewer: key, entry: entryID)] = OfflineFiles.freeBytes() ?? 0
                apply(key, entryID, OfflineJobEvent(op: "storage-denied"))
                watchStorage()
                return false
            }
            v.manifest.budget = result.budget
            viewers[key] = v
            saveManifest(key)
            return true
        } catch { return false }
    }

    /// Waiting for storage rechecks itself every 30 s, and retries only when free space grew by 16 MiB since the denial.
    private func watchStorage() {
        guard storageTimer == nil else { return }
        storageTimer = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(30))
                guard let self else { return }
                let free = OfflineFiles.freeBytes() ?? 0
                for (job, before) in self.storageDenied where free > before + (16 << 20) {
                    self.storageDenied[job] = nil
                    self.apply(job.viewer, job.entry, OfflineJobEvent(op: "storage-available"))
                    self.pump(job.viewer)
                }
                if self.storageDenied.isEmpty { self.storageTimer = nil; return }
            }
        }
    }

    /// Subtitles and artwork are small; they come first, in-process, with the download set's token.
    private func fetchSidecars(_ key: String, _ entryID: String, account: Account, device: String, token: String) async {
        guard let entry = entry(key, entryID), let files = viewers[key]?.files else { return }
        try? FileManager.default.createDirectory(at: files.entryDirectory(entryID), withIntermediateDirectories: true)
        for resource in entry.resources where resource.role != .media && !resource.complete {
            let url: URL?
            switch resource.role {
            case .subtitle:
                guard let index = resource.serverStreamIndex, let source = entry.sourceId else { continue }
                url = try? requestURL(account.server, offlineSubtitleRequest(itemId: entry.itemId, sourceId: source, streamIndex: index))
            case .artwork:
                url = try? ServerURLs.endpoint(account.server, path: "Items/\(entry.itemId)/Images/Primary", query: [URLQueryItem(name: "maxWidth", value: "600")])
            default: url = nil
            }
            guard let url else { continue }
            var request = URLRequest(url: url, timeoutInterval: 60)
            request.setValue(api.authorization(deviceID: device, token: token), forHTTPHeaderField: "Authorization")
            guard let (data, response) = try? await sidecarSession.data(for: request), let http = response as? HTTPURLResponse,
                  Self.servedBy(account.server.url, http), (200..<300).contains(http.statusCode), !(http.value(forHTTPHeaderField: "Content-Type") ?? "").contains("text/html") else { continue }
            let target = files.resourceURL(entryID, resource)
            guard (try? files.write(data, to: target)) != nil else { continue }
            let sha = try? OfflineFiles.sha256(of: target)
            updateResource(key, entryID, resource.id) { $0.complete = true; $0.bytes = Int64(data.count); $0.sha256 = sha }
            commitBytes(key, entryID, Int64(data.count))
        }
    }

    private func updateResource(_ key: String, _ entryID: String, _ resourceID: String, _ change: (inout OfflineResource) -> Void) {
        guard var v = viewers[key], let e = v.manifest.entries.firstIndex(where: { $0.id == entryID }),
              let r = v.manifest.entries[e].resources.firstIndex(where: { $0.id == resourceID }) else { return }
        change(&v.manifest.entries[e].resources[r])
        viewers[key] = v
        saveManifest(key)
    }

    /// Committed bytes move out of the reservation; anything beyond it is reserved first (OFF-06d).
    @discardableResult
    private func commitBytes(_ key: String, _ entryID: String, _ bytes: Int64) -> Bool {
        guard var v = viewers[key] else { return false }
        let remaining = v.manifest.budget.reservations[entryID] ?? 0
        if bytes > remaining {
            guard let extra = try? v.manifest.budget.reserve(job: entryID, bytes: bytes - remaining, freeBytes: nil), extra.granted else { return false }
            v.manifest.budget = extra.budget
        }
        guard let result = try? v.manifest.budget.commit(job: entryID, bytes: bytes), result.granted else { return false }
        v.manifest.budget = result.budget
        viewers[key] = v
        saveManifest(key)
        return true
    }

    private func requestURL(_ server: Server, _ request: OfflineRequest, extra: [URLQueryItem] = []) throws -> URL {
        try ServerURLs.endpoint(server, path: String(request.path.drop { $0 == "/" }),
                                query: request.query.map { URLQueryItem(name: $0.name, value: $0.value) } + extra)
    }

    private func launchMedia(_ key: String, _ entry: OfflineEntry, account: Account, device: String, token: String) {
        #if os(iOS)
        guard let v = viewers[key], let media = entry.resources.first(where: { $0.role == .media }) else { return }
        let tag = TaskTag(viewerDir: OfflineFiles.viewerDirectory(accountKey: key), viewer: key, entryID: entry.id, resourceID: media.id, attempt: entry.job.attempt)
        let expensive = !v.manifest.wifiOnly || entry.job.cellularAllowed
        if entry.preset == .original {
            let resumeURL = v.files.resumeDataURL(entry.id, media.id)
            let task: URLSessionDownloadTask
            if let data = try? Data(contentsOf: resumeURL) {
                task = fileSession.downloadTask(withResumeData: data)
                try? FileManager.default.removeItem(at: resumeURL)
            } else {
                guard let url = try? requestURL(account.server, offlineOriginalRequest(itemId: entry.itemId)) else { return }
                var request = URLRequest(url: url)
                request.setValue(api.authorization(deviceID: device, token: token), forHTTPHeaderField: "Authorization")
                request.allowsExpensiveNetworkAccess = expensive
                request.allowsConstrainedNetworkAccess = expensive
                task = fileSession.downloadTask(with: request)
            }
            task.taskDescription = tag.description
            task.resume()
        } else {
            let session = UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            guard let source = entry.sourceId,
                  let request = try? offlineVideoRequest(itemId: entry.itemId, sourceId: source, preset: entry.preset,
                                                         audioStreamIndex: entry.tracks.first { $0.type == "audio" && $0.available }?.serverIndex,
                                                         deviceId: device, playSessionId: session) else { return }
            // The same finite conversion as Android's, asked for as the server's HLS so the system can save it whole.
            let hls = OfflineRequest(path: "/Videos/\(entry.itemId)/master.m3u8",
                                     query: request.query.filter { !["static", "container"].contains($0.name) })
            guard let url = try? requestURL(account.server, hls, extra: [URLQueryItem(name: "ApiKey", value: token)]) else { return }
            let asset = AVURLAsset(url: url)
            var options: [String: Any] = [AVAssetDownloadTaskMinimumRequiredMediaBitrateKey: 0]
            if !expensive { options[AVAssetDownloadTaskPrefersHDRKey] = false }
            guard let task = hlsSession.makeAssetDownloadTask(asset: asset, assetTitle: entry.labels.title, assetArtworkData: nil, options: options) else { return }
            task.taskDescription = tag.description
            task.resume()
        }
        #endif
    }

    private func stopTransfers(_ key: String, _ entryID: String, keepResume: Bool) {
        #if os(iOS)
        guard let v = viewers[key] else { return }
        Task {
            for task in await fileSession.allTasks + hlsSession.allTasks {
                guard let tag = TaskTag(task.taskDescription), tag.viewer == key, tag.entryID == entryID else { continue }
                stopping.insert(task.taskIdentifier)
                if keepResume, let download = task as? URLSessionDownloadTask, !(task is AVAssetDownloadTask) {
                    let data = await download.cancelByProducingResumeData()
                    if let data { try? v.files.write(data, to: v.files.resumeDataURL(entryID, tag.resourceID)) }
                } else {
                    task.cancel()
                }
            }
        }
        #endif
        if var v = viewers[key] {
            v.manifest.budget = v.manifest.budget.release(job: entryID).budget
            viewers[key] = v
            saveManifest(key)
        }
    }

    private func discardPartial(_ key: String, _ entryID: String) {
        guard var v = viewers[key], let e = v.manifest.entries.firstIndex(where: { $0.id == entryID }) else { return }
        let committed = v.manifest.entries[e].committedBytes()
        try? FileManager.default.removeItem(at: v.files.entryDirectory(entryID))
        for r in v.manifest.entries[e].resources.indices {
            v.manifest.entries[e].resources[r].complete = false
            v.manifest.entries[e].resources[r].bytes = nil
            v.manifest.entries[e].resources[r].sha256 = nil
        }
        if let result = try? v.manifest.budget.discard(job: entryID, committedBytes: committed) { v.manifest.budget = result.budget }
        viewers[key] = v
        saveManifest(key)
    }

    private func deleteOwned(_ key: String, _ entryID: String) {
        stopTransfers(key, entryID, keepResume: false)
        discardPartial(key, entryID)
        apply(key, entryID, OfflineJobEvent(op: "deleted"))
    }

    // MARK: Finishing

    private func verify(_ key: String, _ entryID: String, attempt: Int64) {
        guard let v = viewers[key], let entry = entry(key, entryID), let media = entry.resources.first(where: { $0.role == .media }) else { return }
        let url = v.files.resourceURL(entryID, media)
        let expected = entry.preset == .original ? expectedLength[key + "|" + entryID] : nil
        let runtime = entry.runtimeTicks
        let conversion = entry.preset != .original
        Task {
            let outcome: (ok: Bool, bytes: Int64, sha: String?, reason: String?) = await Task.detached(priority: .utility) {
                let bytes = OfflineFiles.size(of: url)
                guard bytes > 0 else { return (false, 0, nil, "empty") }
                if let expected, expected > 0, expected != bytes { return (false, bytes, nil, "length") }
                if conversion {
                    let duration = try? await AVURLAsset(url: url).load(.duration)
                    let ticks = duration.flatMap { $0.isNumeric ? Int64($0.seconds * 10_000_000) : nil }
                    if offlineConversionShort(expectedTicks: runtime, measuredTicks: ticks) { return (false, bytes, nil, "short") }
                }
                guard let sha = try? OfflineFiles.sha256(of: url) else { return (false, bytes, nil, "unreadable") }
                return (true, bytes, sha, nil)
            }.value
            guard let current = self.entry(key, entryID), current.job.attempt == attempt else { return }
            if outcome.ok {
                updateResource(key, entryID, media.id) { $0.complete = true; $0.bytes = outcome.bytes; $0.sha256 = outcome.sha }
                guard commitBytes(key, entryID, outcome.bytes) else {
                    apply(key, entryID, OfflineJobEvent(op: "fail", attempt: attempt, reason: "storage")); return
                }
                apply(key, entryID, OfflineJobEvent(op: "verified", attempt: attempt))
            } else {
                let again = verifyRetries[key + "|" + entryID, default: 0] < 1
                verifyRetries[key + "|" + entryID, default: 0] += 1
                apply(key, entryID, again ? OfflineJobEvent(op: "verify-failed", attempt: attempt, reason: outcome.reason)
                                          : OfflineJobEvent(op: "fail", attempt: attempt, reason: outcome.reason ?? "verify"))
                pump(key)
            }
        }
    }

    private var verifyRetries: [String: Int] = [:]
    private var expectedLength: [String: Int64] = [:]

    private func commit(_ key: String, _ entryID: String, attempt: Int64) {
        guard var v = viewers[key] else { return }
        let result = v.manifest.commitReady(entryId: entryID, attempt: attempt)
        guard result.ok else {
            log.error("commit refused: \(result.reason ?? "?", privacy: .public)")
            apply(key, entryID, OfflineJobEvent(op: "fail", attempt: attempt, reason: "commit"))
            return
        }
        v.manifest = result.manifest
        v.manifest.budget = v.manifest.budget.release(job: entryID).budget
        viewers[key] = v
        saveManifest(key)
        progress[key + "|" + entryID] = nil
        verifyRetries[key + "|" + entryID] = nil
        pump(key)
    }

    /// One attempt's transfer ended without the file. Failed means the server said so; otherwise wait for network.
    private func transferEnded(_ tag: TaskTag, error: Error?, status: Int?) {
        guard let entry = entry(tag.viewer, tag.entryID), entry.job.attempt == tag.attempt, entry.job.phase == .transferring,
              let account = viewers[tag.viewer]?.account else { return }
        if let status, !(200..<300).contains(status) {
            apply(tag.viewer, tag.entryID, OfflineJobEvent(op: "fail", attempt: tag.attempt, reason: status == 403 ? "denied" : "http-\(status)"))
            return
        }
        Task {
            let answered: Bool
            do { _ = try await api.itemFacts(account, itemID: entry.itemId); answered = true }
            catch OfflineAPI.Failure.server { answered = true }
            catch { answered = false }
            guard let now = self.entry(tag.viewer, tag.entryID), now.job.attempt == tag.attempt else { return }
            if answered && networkUp {
                // The server is there and the transfer still broke: one restart, then a failed row.
                apply(tag.viewer, tag.entryID, OfflineJobEvent(op: "fail", attempt: tag.attempt, reason: "transfer"))
            } else {
                apply(tag.viewer, tag.entryID, OfflineJobEvent(op: "network-lost"))
                rest(tag.viewer, tag.entryID)
            }
        }
    }

    /// Rests between attempts: 5 s doubling to 5 minutes, each download on its own timer. A real network change ends every rest.
    private func rest(_ key: String, _ entryID: String) {
        let tag = key + "|" + entryID
        let delay = min(restDelay[tag].map { $0 * 2 } ?? 5, 300)
        restDelay[tag] = delay
        rests[tag]?.cancel()
        rests[tag] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self else { return }
            self.rests[tag] = nil
            self.apply(key, entryID, OfflineJobEvent(op: "network-back"))
            self.pump(key)
        }
    }

    private func pathChanged(up: Bool, expensive: Bool) {
        let changed = up != networkUp || expensive != networkExpensive
        networkUp = up; networkExpensive = expensive
        guard changed, started else { return }
        for tag in rests.keys { rests[tag]?.cancel() }
        rests = [:]; restDelay = [:]
        for (key, v) in viewers {
            for entry in v.manifest.entries {
                let allowed = networkAllows(v.manifest, entry.job)
                if Self.running(entry.job.phase), !allowed { apply(key, entry.id, OfflineJobEvent(op: "network-lost")) }
                if entry.job.phase == .waitingNetwork, allowed { apply(key, entry.id, OfflineJobEvent(op: "network-back")) }
            }
            pump(key)
            if up { Task { await sync(key) } }
        }
        if up { Task { await retryRevocations() } }
    }

    // MARK: The download set's own token (OFF-10)

    enum TokenError: Error { case needsPassword }

    private func downloadDevice(_ account: Account) -> String {
        let serverId = account.server.serverId
        return offlineDownloadSetDevice(installationDeviceId: account.deviceID, serverId: serverId.isEmpty ? account.server.url.absoluteString : serverId, userId: account.userId)
    }

    private func downloadToken(_ account: Account, device: String) async throws -> String {
        if let token = DownloadTokens.read(device) { return token }
        guard try await api.quickConnectEnabled(account.server, deviceID: device) else { throw TokenError.needsPassword }
        let token = try await api.issueByQuickConnect(account, deviceID: device)
        guard settleIssuedToken(token, account: account, device: device) else { throw CancellationError() }
        return token
    }

    /// A token can arrive after its viewer signed out (the request was in flight): it isn't kept, and the server is
    /// asked to end it, so a removed viewer leaves no download sign-in behind (OFF-09/10).
    @discardableResult
    func settleIssuedToken(_ token: String, account: Account, device: String) -> Bool {
        if viewers[account.key] != nil { DownloadTokens.write(token, device); return true }
        log.notice("A download token arrived after its viewer signed out; ending it")
        PendingRevocations.add(.init(server: account.server.url.absoluteString, deviceID: device))
        Task { await revoke(server: account.server, deviceID: device, token: token) }
        return false
    }

    /// The viewer typed their password because Quick Connect is off; it's used once, for the token, and not kept.
    func providePassword(_ account: Account, _ password: String) async -> Bool {
        let device = downloadDevice(account)
        guard let token = try? await api.issueByPassword(account, deviceID: device, password: password),
              settleIssuedToken(token, account: account, device: device) else { return false }
        passwordNeeded.remove(account.key)
        for entry in entries(account) where entry.job.phase == .failed && entry.job.failure == "needs-password" { retry(account, entry.id) }
        return true
    }

    private func revoke(server: Server, deviceID: String, token: String) async {
        do {
            try await api.revoke(server, deviceID: deviceID, token: token)
            DownloadTokens.delete(deviceID)
            PendingRevocations.remove(deviceID)
        } catch OfflineAPI.Failure.signedOut {
            DownloadTokens.delete(deviceID); PendingRevocations.remove(deviceID)
        } catch {}
    }

    /// A revocation owed keeps being asked: every minute while the app runs, on any network change, and on every start.
    private func retryRevocations() async {
        for pending in PendingRevocations.all() {
            guard let url = URL(string: pending.server) else { continue }
            guard let token = DownloadTokens.read(pending.deviceID) else { PendingRevocations.remove(pending.deviceID); continue }
            await revoke(server: Server(url: url), deviceID: pending.deviceID, token: token)
        }
    }

    // MARK: Persistence

    /// Storage full can't take the buttons away: a refused write keeps the state in memory and is tried again later.
    private func saveManifest(_ key: String) {
        guard let v = viewers[key] else { return }
        do { try v.files.prepare(); try v.files.write(Data(offlineEncodeManifest(v.manifest).utf8), to: v.files.manifestURL) }
        catch { log.error("manifest write failed: \(error.localizedDescription, privacy: .public)") }
    }

    private func saveOutbox(_ key: String) {
        guard let v = viewers[key], let data = try? JSONEncoder().encode(v.outbox) else { return }
        try? v.files.prepare(); try? v.files.write(data, to: v.files.outboxURL)
    }

    private func saveLastKnown(_ key: String) {
        guard let v = viewers[key], let data = try? JSONEncoder().encode(v.lastKnown) else { return }
        try? v.files.prepare(); try? v.files.write(data, to: v.files.lastKnownURL)
    }
}

// MARK: - System transfer callbacks

extension Downloads {
    /// Whether the response came from the viewer's server, after any redirects (`OfflineAPI.call` checks the same).
    nonisolated static func servedBy(_ server: URL, _ response: HTTPURLResponse) -> Bool {
        guard let final = response.url else { return false }
        return ServerURLs.isSameOrigin(server, final)
    }
}

#if os(iOS)

extension Downloads: URLSessionDownloadDelegate, AVAssetDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let description = downloadTask.taskDescription
        MainActor.assumeIsolated {
            guard let tag = TaskTag(description) else { return }
            progress[tag.viewer + "|" + tag.entryID] = DownloadProgress(received: totalBytesWritten, total: totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : nil)
        }
    }

    /// The system's temporary file lasts only until this returns, so it's moved here, before anything else.
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        let description = downloadTask.taskDescription
        let http = downloadTask.response as? HTTPURLResponse
        MainActor.assumeIsolated {
            guard let tag = TaskTag(description), let v = viewers[tag.viewer], let entry = entry(tag.viewer, tag.entryID),
                  entry.job.attempt == tag.attempt, let media = entry.resources.first(where: { $0.id == tag.resourceID }) else { return }
            // Background sessions follow redirects without asking the delegate, so a file another host answered is
            // refused here rather than saved as the viewer's media.
            guard let http, Self.servedBy(v.account.server.url, http) else { failedStatus[downloadTask.taskIdentifier] = -1; return }
            let status = http.statusCode
            // A sign-in page isn't a file: an HTML answer is a network interruption, before anything touches the partial.
            if (http.value(forHTTPHeaderField: "Content-Type") ?? "").lowercased().contains("text/html") {
                failedStatus[downloadTask.taskIdentifier] = -1; return
            }
            guard (200..<300).contains(status) else { failedStatus[downloadTask.taskIdentifier] = status; return }
            let target = v.files.resourceURL(entry.id, media)
            do {
                try v.files.prepare()
                try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                try? FileManager.default.removeItem(at: target)
                // Name the file before hashing it: a kill after this costs a hash, not the download.
                try FileManager.default.moveItem(at: location, to: target)
                if http.expectedContentLength > 0, status == 200 { let length = http.expectedContentLength; expectedLength[tag.viewer + "|" + tag.entryID] = length }
                finished.insert(downloadTask.taskIdentifier)
            } catch {
                log.error("move failed: \(error.localizedDescription, privacy: .public)")
            }
        }
    }

    nonisolated func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didLoad timeRange: CMTimeRange, totalTimeRangesLoaded loadedTimeRanges: [NSValue], timeRangeExpectedToLoad: CMTimeRange) {
        let description = assetDownloadTask.taskDescription
        let loaded = loadedTimeRanges.map { $0.timeRangeValue.duration.seconds }.reduce(0, +)
        let total = timeRangeExpectedToLoad.duration.seconds
        MainActor.assumeIsolated {
            guard let tag = TaskTag(description), total > 0 else { return }
            progress[tag.viewer + "|" + tag.entryID] = DownloadProgress(fraction: min(1, loaded / total))
        }
    }

    nonisolated func urlSession(_ session: URLSession, assetDownloadTask: AVAssetDownloadTask, didFinishDownloadingTo location: URL) {
        let id = assetDownloadTask.taskIdentifier
        MainActor.assumeIsolated { hlsLocations[id] = location }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let description = task.taskDescription, id = task.taskIdentifier
        let isAsset = task is AVAssetDownloadTask
        MainActor.assumeIsolated {
            defer { stopping.remove(id); finished.remove(id); failedStatus[id] = nil; hlsLocations[id] = nil }
            guard let tag = TaskTag(description) else { return }
            if stopping.contains(id) {
                if let location = hlsLocations[id] { try? FileManager.default.removeItem(at: location) }
                return
            }
            if isAsset {
                guard error == nil, let location = hlsLocations[id], let v = viewers[tag.viewer], let entry = entry(tag.viewer, tag.entryID),
                      entry.job.attempt == tag.attempt, let media = entry.resources.first(where: { $0.id == tag.resourceID }) else {
                    if let location = hlsLocations[id] { try? FileManager.default.removeItem(at: location) }
                    transferEnded(tag, error: error, status: nil); return
                }
                let target = v.files.resourceURL(entry.id, media)
                do {
                    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
                    try? FileManager.default.removeItem(at: target)
                    try FileManager.default.moveItem(at: location, to: target)
                } catch {
                    log.error("package move failed: \(error.localizedDescription, privacy: .public)")
                    transferEnded(tag, error: error, status: nil); return
                }
                apply(tag.viewer, tag.entryID, OfflineJobEvent(op: "transfer-complete", attempt: tag.attempt))
                return
            }
            if let status = failedStatus[id] { transferEnded(tag, error: nil, status: status == -1 ? nil : status); return }
            if error == nil, finished.contains(id) {
                apply(tag.viewer, tag.entryID, OfflineJobEvent(op: "transfer-complete", attempt: tag.attempt))
                return
            }
            // A dropped body keeps what the system can resume from.
            if let data = (error as NSError?)?.userInfo[NSURLSessionDownloadTaskResumeData] as? Data, let v = viewers[tag.viewer] {
                try? v.files.write(data, to: v.files.resumeDataURL(tag.entryID, tag.resourceID))
            }
            transferEnded(tag, error: error, status: nil)
        }
    }
}
#endif

/// Each system task says whose download it is and which attempt started it, so a callback from an
/// attempt that was replaced (or from a viewer who signed out) touches nothing.
struct TaskTag: CustomStringConvertible {
    let viewerDir: String
    let viewer: String
    let entryID: String
    let resourceID: String
    let attempt: Int64

    init(viewerDir: String, viewer: String, entryID: String, resourceID: String, attempt: Int64) {
        self.viewerDir = viewerDir; self.viewer = viewer; self.entryID = entryID; self.resourceID = resourceID; self.attempt = attempt
    }

    init?(_ text: String?) {
        guard let text, let data = Data(base64Encoded: text),
              let parts = try? JSONDecoder().decode([String].self, from: data), parts.count == 6, parts[0] == "v1",
              let attempt = Int64(parts[5]) else { return nil }
        self.init(viewerDir: parts[1], viewer: parts[2], entryID: parts[3], resourceID: parts[4], attempt: attempt)
    }

    var description: String {
        (try? JSONEncoder().encode(["v1", viewerDir, viewer, entryID, resourceID, String(attempt)]))?.base64EncodedString() ?? ""
    }
}

/// Which originals this device can play as they are (OFF-02: Original requires local codec and track support).
enum OriginalSupport {
    static func playsHere(_ source: MediaSource?) -> Bool {
        guard let source else { return false }
        let containers = Set((source.container ?? "").lowercased().split(separator: ",").map(String.init))
        guard !containers.isDisjoint(with: ["mp4", "m4v", "mov"]) else { return false }
        let video = source.mediaStreams.first { $0.type == "Video" }?.codec?.lowercased()
        let audio = source.mediaStreams.filter { $0.type == "Audio" }.compactMap { $0.codec?.lowercased() }
        let videoOK = video == nil || ["h264", "hevc", "h265", "mpeg4"].contains(video!)
        let audioOK = audio.isEmpty || audio.contains { ["aac", "ac3", "eac3", "mp3", "alac", "flac", "opus"].contains($0) }
        return videoOK && audioOK
    }

    static func fileExtension(_ container: String?) -> String {
        let first = (container ?? "mp4").lowercased().split(separator: ",").first.map(String.init) ?? "mp4"
        return ["mp4", "m4v", "mov"].contains(first) ? first : "mp4"
    }
}

/// The download set's token, in the Keychain on a device; an unsigned simulator build has no Keychain,
/// so there (only) it falls back to a protected file, as the account store does.
/// What start-up may clear: per-viewer folders and download tokens no known viewer owns.
enum OfflineOrphans {
    static func folders(present: [String], known: Set<String>) -> [String] {
        present.filter { $0.hasPrefix("v-") && !known.contains($0) }.sorted()
    }
    static func tokens(present: [String], known: Set<String>, owed: Set<String>) -> [String] {
        present.filter { !known.contains($0) && !owed.contains($0) }.sorted()
    }
}

enum DownloadTokens {
    private static func service(_ device: String) -> String { "app.pictureshow.offline-token." + device }
    private static func fallback(_ device: String) -> URL { OfflineFiles.base.appendingPathComponent(".t-" + device) }
    private static var simulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    static func read(_ device: String) -> String? {
        let (data, status) = SystemKeychain().read(service: service(device))
        if let data { return String(data: data, encoding: .utf8) }
        if simulator || status == errSecMissingEntitlement, let data = try? Data(contentsOf: fallback(device)) { return String(data: data, encoding: .utf8) }
        return nil
    }

    static func write(_ token: String, _ device: String) {
        let status = SystemKeychain().write(Data(token.utf8), service: service(device))
        guard status != errSecSuccess, simulator || status == errSecMissingEntitlement else { return }
        try? OfflineFiles.makeProtectedDirectory(OfflineFiles.base)
        #if os(iOS)
        try? Data(token.utf8).write(to: fallback(device), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #endif
    }

    /// Every download-set device with a stored token, from the Keychain and the simulator fallback files.
    static func allDevices() -> [String] {
        var devices: Set<String> = []
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecReturnAttributes as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitAll]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess, let items = result as? [[String: Any]] {
            for item in items {
                if let svc = item[kSecAttrService as String] as? String, svc.hasPrefix("app.pictureshow.offline-token.") {
                    devices.insert(String(svc.dropFirst("app.pictureshow.offline-token.".count)))
                }
            }
        }
        for name in (try? FileManager.default.contentsOfDirectory(atPath: OfflineFiles.base.path)) ?? [] where name.hasPrefix(".t-") {
            devices.insert(String(name.dropFirst(3)))
        }
        return devices.sorted()
    }

    static func delete(_ device: String) {
        SystemKeychain().delete(service: service(device))
        try? FileManager.default.removeItem(at: fallback(device))
    }
}

/// Download-set tokens still owed a revocation after a sign-out with the server out of reach. No token here.
enum PendingRevocations {
    struct Item: Codable, Equatable { let server: String; let deviceID: String }
    private static let key = "offline.pendingRevocations.v1"
    static func all() -> [Item] {
        (UserDefaults.standard.data(forKey: key)).flatMap { try? JSONDecoder().decode([Item].self, from: $0) } ?? []
    }
    static func add(_ item: Item) { save(all().filter { $0.deviceID != item.deviceID } + [item]) }
    static func remove(_ device: String) { save(all().filter { $0.deviceID != device }) }
    private static func save(_ items: [Item]) { UserDefaults.standard.set(try? JSONEncoder().encode(items), forKey: key) }
}

extension OfflineEntry {
    /// The title as the manifest remembers it, for screens that draw a `MediaItem` while the server is away.
    var offlineItem: MediaItem {
        var item = MediaItem(id: itemId, name: labels.title, type: kind == .episode ? "Episode" : "Movie", runTimeTicks: runtimeTicks)
        item.seriesName = labels.seriesName
        item.seriesId = labels.seriesId
        item.seasonId = labels.seasonId
        item.parentIndexNumber = labels.seasonNumber
        item.indexNumber = labels.episodeNumber
        item.productionYear = labels.year
        item.overview = labels.overview
        return item
    }

    var qualityLabel: String {
        switch preset {
        case .original: "Original"
        case .video720: "Compatible 720p"
        case .video1080: "Compatible 1080p"
        case .audio128: "Compatible audio 128 kbps"
        case .audio256: "Compatible audio 256 kbps"
        }
    }

    /// "Not downloaded: Spanish audio; English and French subtitles", each language once.
    var notDownloadedSummary: String? {
        func names(_ type: String) -> [String] {
            var seen: [String] = [], unnamed = 0
            for track in undownloadedTracks(type: type) {
                guard let code = track.language, !code.isEmpty else { unnamed += 1; continue }
                let name = Locale.current.localizedString(forLanguageCode: code) ?? code
                if !seen.contains(name) { seen.append(name) }
            }
            if unnamed > 0 { seen.append(unnamed == 1 ? "1 other" : "\(unnamed) others") }
            return seen
        }
        func list(_ items: [String]) -> String {
            items.count <= 1 ? (items.first ?? "") : items.dropLast().joined(separator: ", ") + " and " + items.last!
        }
        var parts: [String] = []
        let audio = names("audio"), subs = names("subtitle")
        if !audio.isEmpty { parts.append(list(audio) + " audio") }
        if !subs.isEmpty { parts.append(list(subs) + " subtitles") }
        return parts.isEmpty ? nil : "Not downloaded: " + parts.joined(separator: "; ")
    }
}
