// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// What an album or playlist page says about its download, in the Downloads screen's words (MUS-02).
struct MusicDownloadStatus: Equatable {
    var text: String
    /// Tracks done of tracks to fetch, while there's something left to fetch.
    var fraction: Double?
    var error: Bool
    var ready: Bool
}

/// A question the page is asking about a download, or what it has to say. Every question puts Cancel first.
enum MusicDownloadAsk: Equatable {
    case checking
    case confirm(String, [String])
    case notice(String)
    /// Remove (ready) or cancel (still coming) the whole album or playlist.
    case remove(String, confirm: String)
}

enum MusicDownloadWords {
    static func tracks(_ n: Int) -> String { n == 1 ? "1 track" : "\(n) tracks" }

    /// Nil when nothing of this album or playlist is on the device; a removal under way counts as gone.
    static func status(_ manifest: OfflineManifest?, collectionID: String, progress: (String) -> DownloadProgress? = { _ in nil },
                       networkUp: Bool = true) -> MusicDownloadStatus? {
        guard let manifest, let p = manifest.collectionProgress(collectionID) else { return nil }
        let stored = p.tracks - p.absent
        let counted = p.absent > 0 ? "\(p.ready) of \(tracks(p.tracks))" : tracks(p.ready)
        let own: [OfflineEntry] = manifest.collections.first { $0.id == collectionID }.map { c in
            let ids = Set(c.itemIds.map { offlineEntryId(itemId: $0, preset: c.preset) })
            return manifest.entries.filter { ids.contains($0.id) }
        } ?? []
        func words(_ entry: OfflineEntry?) -> String {
            guard let entry else { return "" }
            if entry.job.phase == .waitingNetwork, !networkUp { return "Waiting for a connection" }
            return DownloadWords.state(entry, progress(entry.id), wifiOnly: manifest.wifiOnly)
        }
        let fraction = min(1, max(0, Double(p.ready) / Double(max(1, stored))))
        let done = "\(p.ready) of \(tracks(stored))"
        switch p.phase {
        case "ready":
            return MusicDownloadStatus(text: "Downloaded · \(counted) · \(DownloadWords.bytes(p.readyBytes))", fraction: nil, error: false, ready: true)
        case "waiting":
            let waiting = own.first { $0.job.phase == .waitingNetwork || $0.job.phase == .waitingStorage }
            return MusicDownloadStatus(text: "\(done) downloaded. \(words(waiting))", fraction: fraction, error: false, ready: false)
        case "paused":
            let byCap = own.contains { $0.job.phase == .paused && $0.job.pausedByCap }
            return MusicDownloadStatus(text: "Download paused" + (byCap ? ": over your storage limit" : "") + " · \(done)", fraction: fraction, error: false, ready: false)
        case "failed":
            let failed = own.first { $0.job.phase == .failed || $0.job.phase == .missing }
            return MusicDownloadStatus(text: "Download stopped · \(done). \(words(failed))", fraction: fraction, error: true, ready: false)
        default:
            return MusicDownloadStatus(text: "Downloading · \(done)", fraction: fraction, error: false, ready: false)
        }
    }

    /// What Download asks, from the server's answer: refused rights, nothing downloadable, too big for the limit, or the question
    /// with the count, size and what's left out. Original quality only: compatible audio hasn't passed its checks (OFF-02).
    static func question(allowed: Bool, tracks facts: [OfflineAPI.TrackFacts], manifest: OfflineManifest?, name: String) -> MusicDownloadAsk {
        guard allowed else { return .notice("Your account can't download from this server.") }
        let selection = offlineSelect(items: facts.map(\.candidate), preset: .original)
        guard !selection.itemIds.isEmpty else { return .notice("None of these tracks can be downloaded.") }
        let stored = Set(manifest?.entries.map(\.id) ?? [])
        let chosen = Set(selection.itemIds)
        // What's already here costs nothing more.
        let fresh = facts.filter { chosen.contains($0.item.id) && !stored.contains(offlineEntryId(itemId: $0.item.id, preset: .original)) }
        let needed = offlineSelect(items: fresh.map(\.candidate), preset: .original).knownBytes
        if let budget = manifest?.budget, budget.refusal(bytes: needed) != nil {
            return .notice("\(name) needs about \(DownloadWords.bytes(needed)), and your download limit has \(DownloadWords.bytes(budget.roomBytes() ?? 0)) left. "
                           + "Remove downloads or raise the limit in Downloads, then try again.")
        }
        let count = selection.itemIds.count
        let size: String
        if selection.unknownCount == count { size = "size not known" }
        else if selection.estimated { size = "about \(DownloadWords.bytes(selection.knownBytes))" }
        else { size = DownloadWords.bytes(selection.knownBytes) }
        let skipped = selection.skipped.isEmpty ? "" : " \(tracks(selection.skipped.count)) can't be downloaded and will stream as usual."
        return .confirm("Download \(tracks(count)) (\(size)) in their original quality?\(skipped)", selection.itemIds)
    }

    /// Downloads' Play on a music row: the album or playlist it came with, ready tracks in their saved order, starting at this one
    /// (MUS-10). `collectionID` is the row it was played from; otherwise the newest collection holding the track. A track no
    /// collection holds plays alone. Nil when it isn't a ready track.
    static func play(owner: String, manifest: OfflineManifest?, entry: OfflineEntry, collectionID: String? = nil, art: (OfflineEntry) -> URL?) -> MusicPlay? {
        guard entry.kind == .audio, let manifest else { return nil }
        let collection = collectionID.flatMap { id in manifest.collections.first { $0.id == id && $0.itemIds.contains(entry.itemId) } }
            ?? manifest.collectionFor(entry.itemId)
        let preset = collection?.preset ?? entry.preset
        var byItem: [String: OfflineEntry] = [:]
        for e in manifest.entries where e.kind == .audio && e.job.phase == .ready && e.preset == preset { byItem[e.itemId] = e }
        let order = collection.map { manifest.collectionPlayOrder($0.id) } ?? [entry.itemId]
        let tracks = order.compactMap { byItem[$0].map { card($0, art: art) } }
        guard let start = order.firstIndex(of: entry.itemId), tracks.count == order.count else { return nil }
        let context = collection.map { ($0.kind == .album ? "MusicAlbum:" : "Playlist:") + $0.id } ?? "download:\(entry.itemId)"
        return MusicPlay(owner: owner, tracks: tracks, start: start, context: context, contextName: collection?.title ?? entry.labels.title)
    }

    /// A downloaded track as the queue shows it, with no server: art from the saved file.
    static func card(_ entry: OfflineEntry, art: (OfflineEntry) -> URL?) -> MusicCard {
        MusicCard(id: entry.itemId, title: entry.labels.title, artist: entry.labels.artist, album: entry.labels.album, albumID: entry.labels.albumId,
                  durationTicks: entry.runtimeTicks, art: art(entry))
    }

    /// A listen reported at the track's length counts as played; a second short still counts, as rounding lands either side.
    static func listenFinished(ticks: Int64, runtimeTicks: Int64?) -> Bool {
        guard let runtimeTicks, runtimeTicks > 0 else { return false }
        return ticks >= runtimeTicks - 10_000_000
    }
}

/// One line of the Downloads page: a single download, or an album or playlist standing for its tracks (MUS-10).
struct DownloadGroup: Identifiable {
    enum Section { case active, ready, stopped }
    var id: String { collection.map { "collection:\($0.id)" } ?? entry.id }
    /// The download, or a collection's first ready track (its art, its Play).
    var entry: OfflineEntry
    var collection: OfflineCollection?
    /// A collection's tracks, each once, in its order.
    var tracks: [OfflineEntry] = []
    var progress: OfflineCollectionProgress?
    var section: Section
    var sequence: Int64

    /// The Downloads page's lines: one per album or playlist (a track two of them hold shows under both), then every download
    /// no album or playlist holds, each in the order it was downloaded.
    static func groups(_ manifest: OfflineManifest) -> [DownloadGroup] {
        let sorted = manifest.entries.sorted { $0.createdSequence < $1.createdSequence }
        var byItem: [String: OfflineEntry] = [:]
        for e in sorted where e.kind == .audio && byItem[e.itemId] == nil { byItem[e.itemId] = e }
        var grouped = Set<String>()
        var groups: [DownloadGroup] = []
        for collection in manifest.collections {
            var seen = Set<String>()
            let tracks = collection.itemIds.filter { seen.insert($0).inserted }.compactMap { byItem[$0] }
            guard let first = tracks.first, let progress = manifest.collectionProgress(collection.id) else { continue }
            tracks.forEach { grouped.insert($0.id) }
            let section: Section = progress.phase == "ready" ? .ready : ["failed", "paused"].contains(progress.phase) ? .stopped : .active
            groups.append(DownloadGroup(entry: tracks.first { $0.job.phase == .ready } ?? first, collection: collection, tracks: tracks,
                                        progress: progress, section: section, sequence: collection.createdSequence))
        }
        for entry in sorted where !grouped.contains(entry.id) {
            let phase = entry.job.phase
            let section: Section = phase == .ready ? .ready : [.failed, .missing, .paused].contains(phase) ? .stopped : .active
            groups.append(DownloadGroup(entry: entry, section: section, sequence: entry.createdSequence))
        }
        return groups.sorted { $0.sequence < $1.sequence }
    }
}

/// MusicHost's view of the download engine: files to play and the outbox their listens go to.
@MainActor final class DownloadsMusicLocal: MusicLocal {
    let downloads: Downloads
    init(downloads: Downloads) { self.downloads = downloads }

    func decision(_ account: Account, media: String) -> PlayDecision? {
        guard let (entry, url) = downloads.trackFile(account, media: media) else { return nil }
        var source = MediaSource(id: entry.sourceId, container: entry.container, supportsDirectPlay: true)
        source.runTimeTicks = entry.runtimeTicks
        return PlayDecision(itemID: entry.itemId, url: url, method: .directPlay, source: source)
    }

    func downloaded(_ account: Account) -> Set<String> { downloads.downloadedTracks(account) }

    func art(_ account: Account, media: String) -> URL? {
        downloads.readyTrack(account, media: media).flatMap { downloads.savedArt(account.key, $0) }
    }

    func report(_ account: Account, media: String, serial: String, report: String, ticks: Int64, runtimeTicks: Int64?) {
        switch report {
        case "start": break
        case "progress": downloads.recordViewing(account.key, itemID: media, positionTicks: ticks)
        default:
            // A listen that reached the end counts as played; one cut short keeps its place, as video does.
            if MusicDownloadWords.listenFinished(ticks: ticks, runtimeTicks: runtimeTicks) { downloads.recordCompleted(account.key, itemID: media) }
            else { downloads.recordViewing(account.key, itemID: media, positionTicks: ticks) }
            downloads.syncSoon(account.key)
        }
    }
}

/// One album or playlist page's download: asks, starts and removes (MUS-02).
@MainActor final class MusicDownloadController: ObservableObject {
    @Published var ask: MusicDownloadAsk?
    private var collection: OfflineCollection?
    private var facts: [OfflineAPI.TrackFacts] = []

    /// Reads the viewer's rights and every track's size, then asks with the count and size, or says why it can't (OFF-01, OFF-06).
    func request(downloads: Downloads, account: Account, kind: OfflineCollectionKind, id: String, name: String, artist: String?, order: [String]) {
        guard ask != .checking, !order.isEmpty else { return }
        ask = .checking
        collection = OfflineCollection(id: id, kind: kind, title: name, artist: artist, itemIds: order, preset: .original)
        Task {
            do {
                let (allowed, tracks) = try await downloads.trackFacts(account: account, ids: order)
                facts = tracks
                ask = MusicDownloadWords.question(allowed: allowed, tracks: tracks, manifest: downloads.manifest(account), name: name)
            } catch {
                ask = .notice("Can't reach the server. Downloads need it to start; what's already saved still plays.")
            }
        }
    }

    func start(downloads: Downloads, account: Account, wanted: [String]) {
        ask = nil
        guard var collection else { return }
        let keep = Set(wanted)
        collection.itemIds = collection.itemIds.filter { keep.contains($0) }
        if !downloads.downloadCollection(account: account, collection: collection, tracks: facts.filter { keep.contains($0.item.id) }) {
            ask = .notice("Downloads can't start until the download list on this device can be read. Open Downloads to see why.")
        }
    }

    /// The album or playlist a removal is about.
    private(set) var collectionID: String?

    func askRemove(id: String, name: String, ready: Bool) {
        collectionID = id
        ask = ready
            ? .remove("Remove \(name) from this device? You can still stream it from your server.", confirm: "Remove")
            : .remove("Stop downloading \(name) and remove what's saved so far?", confirm: "Stop download")
    }
}
