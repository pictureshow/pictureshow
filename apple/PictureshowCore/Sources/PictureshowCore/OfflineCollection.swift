// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum OfflineCollectionKind: String, Codable, Sendable { case album, playlist }

/// An album or playlist as it was when the viewer downloaded it (MUS-10): its tracks in the order the page showed, a song a
/// playlist holds twice listed twice. Each track is stored once, as its own entry, so an album and a playlist that share a song
/// share its file.
public struct OfflineCollection: Codable, Equatable, Sendable {
    public var id: String
    public var kind: OfflineCollectionKind
    public var title: String
    public var artist: String?
    public var itemIds: [String]
    public var preset: OfflinePreset
    public var createdSequence: Int64
    public init(id: String, kind: OfflineCollectionKind, title: String, artist: String? = nil, itemIds: [String] = [], preset: OfflinePreset = .original, createdSequence: Int64 = 0) {
        self.id = id
        self.kind = kind
        self.title = title
        self.artist = artist
        self.itemIds = itemIds
        self.preset = preset
        self.createdSequence = createdSequence
    }
    private enum CodingKeys: String, CodingKey { case id, kind, title, artist, itemIds, preset, createdSequence }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        kind = try c.decode(OfflineCollectionKind.self, forKey: .kind)
        title = try c.decode(String.self, forKey: .title)
        artist = c.contains(.artist) ? try c.decodeIfPresent(String.self, forKey: .artist) : nil
        itemIds = c.contains(.itemIds) ? try c.decode([String].self, forKey: .itemIds) : []
        preset = c.contains(.preset) ? try c.decode(OfflinePreset.self, forKey: .preset) : .original
        createdSequence = c.contains(.createdSequence) ? try c.decode(Int64.self, forKey: .createdSequence) : 0
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        try c.encode(kind, forKey: .kind)
        try c.encode(title, forKey: .title)
        try c.encode(artist, forKey: .artist)
        try c.encode(itemIds, forKey: .itemIds)
        try c.encode(preset, forKey: .preset)
        try c.encode(createdSequence, forKey: .createdSequence)
    }
    var entryIds: Set<String> { Set(itemIds.map { offlineEntryId(itemId: $0, preset: preset) }) }
}

/// How far a collection's download has got, counted per stored track (a duplicate counts once). `absent` counts tracks with no
/// entry: removed one by one from Downloads, or never stored because the server refused them.
public struct OfflineCollectionProgress: Equatable, Sendable {
    public var tracks: Int
    public var ready: Int
    public var transferring: Int
    public var waiting: Int
    public var paused: Int
    public var failed: Int
    public var absent: Int
    public var readyBytes: Int64
    /// "ready", "failed", "paused", "waiting" or "downloading", in that precedence once nothing is left to fetch.
    public var phase: String {
        if ready == tracks - absent && ready > 0 { return "ready" }
        if transferring > 0 { return "downloading" }
        if waiting > 0 { return "waiting" }
        if paused > 0 { return "paused" }
        if failed > 0 { return "failed" }
        return "downloading"
    }
}

extension OfflineManifest {
    /// Adds a collection, or replaces the one with its id: downloading an album again takes a new snapshot of its order.
    public func withCollection(_ collection: OfflineCollection) -> OfflineManifest {
        var next = self
        next.collections = collections.filter { $0.id != collection.id } + [collection]
        return next
    }

    /// The entries a collection's removal deletes: its tracks', except any another collection still holds. Only entries that
    /// exist and aren't already being removed.
    public func collectionRemoval(_ collectionId: String) -> [String] {
        guard let target = collections.first(where: { $0.id == collectionId }) else { return [] }
        let kept = collections.filter { $0.id != collectionId }.reduce(into: Set<String>()) { $0.formUnion($1.entryIds) }
        let gone = target.entryIds.subtracting(kept)
        return entries.filter { gone.contains($0.id) && $0.job.phase != .deleting }.map(\.id)
    }

    /// A collection none of whose tracks has an entry left (all removed) is gone too.
    public func prunedCollections() -> OfflineManifest {
        let present = Set(entries.filter { $0.job.phase != .deleting }.map(\.id))
        let kept = collections.filter { !$0.entryIds.isDisjoint(with: present) }
        guard kept.count != collections.count else { return self }
        var next = self
        next.collections = kept
        return next
    }

    public func collectionProgress(_ collectionId: String) -> OfflineCollectionProgress? {
        guard let collection = collections.first(where: { $0.id == collectionId }) else { return nil }
        var byId: [String: OfflineEntry] = [:]
        for entry in entries where entry.job.phase != .deleting { byId[entry.id] = entry }
        var seen = Set<String>()
        let distinct = collection.itemIds.filter { seen.insert($0).inserted }
        let own = distinct.map { byId[offlineEntryId(itemId: $0, preset: collection.preset)] }
        let present = own.compactMap { $0 }
        guard !present.isEmpty else { return nil }
        func count(_ phases: Set<OfflinePhase>) -> Int { present.filter { phases.contains($0.job.phase) }.count }
        return OfflineCollectionProgress(
            tracks: own.count,
            ready: count([.ready]),
            transferring: count([.queued, .preparing, .transferring, .verifying]),
            waiting: count([.waitingNetwork, .waitingStorage]),
            paused: count([.paused]),
            failed: count([.failed, .missing]),
            absent: own.count - present.count,
            readyBytes: present.filter { $0.job.phase == .ready }.reduce(0) { $0 &+ $1.committedBytes() }
        )
    }

    /// What plays offline: the collection's ready tracks in the snapshot's order, a duplicate kept where it stood.
    public func collectionPlayOrder(_ collectionId: String) -> [String] {
        guard let collection = collections.first(where: { $0.id == collectionId }) else { return [] }
        let ready = Set(entries.filter { $0.job.phase == .ready }.map(\.id))
        return collection.itemIds.filter { ready.contains(offlineEntryId(itemId: $0, preset: collection.preset)) }
    }

    /// The collection a downloaded track plays inside from Downloads: the newest one holding it, or none.
    public func collectionFor(_ itemId: String) -> OfflineCollection? {
        collections.filter { $0.itemIds.contains(itemId) }.max { $0.createdSequence < $1.createdSequence }
    }
}

extension OfflineBudget {
    /// Whether a download of `bytes` (known sizes plus estimates) can start inside the limit, before anything is queued
    /// (OFF-06): nil when it fits or no limit is set yet, "cap" when it can't fit in what the limit leaves after what's stored
    /// and reserved. Free disk is checked per chunk, as for video.
    public func refusal(bytes: Int64) -> String? {
        guard let cap = capBytes else { return nil }
        return bytes > cap &- usedBytes &- reservations.values.reduce(0, &+) ? "cap" : nil
    }

    /// What the limit leaves, never negative; nil with no limit.
    public func roomBytes() -> Int64? {
        capBytes.map { max(0, $0 &- usedBytes &- reservations.values.reduce(0, &+)) }
    }
}

/// What the queue does with a track it couldn't get from the server for want of a connection (MUS-10).
public struct MusicUnreachable: Equatable, Sendable {
    /// "skip": move on through the queue's error step, which skips each entry once per traversal. "pause": stay here, saved.
    public var step: String
    public var message: String
}

public let MUSIC_UNREACHABLE_TEXT = "Can't reach the server. Your queue is saved: press Play to try again."

/// The current track isn't downloaded and the server can't be reached. A queue with a downloaded track still ahead on this
/// traversal (the wrap included under Repeat All, entries already skipped left out) skips it, saying why: the queue's error
/// step marks it, so it is skipped once per traversal and a queue with nothing left to play stops instead of spinning. Nothing
/// downloaded ahead: stay on it, paused and saved, and say whether that's because nothing here is downloaded at all or only
/// nothing after it. `local` holds the media ids that have a ready download; `queue` is the shared queue state.
public func musicUnreachable(queue q: [String: Any], local: Set<String>, title: String) -> MusicUnreachable {
    let entries = q["entries"] as? [[String: Any]] ?? []
    var media: [String: String] = [:]
    for e in entries { if let id = e["id"] as? String, let m = e["media"] as? String { media[id] = m } }
    let name = title.isEmpty ? "This track" : title
    if !entries.contains(where: { local.contains($0["media"] as? String ?? "") }) { return MusicUnreachable(step: "pause", message: MUSIC_UNREACHABLE_TEXT) }
    let order = q["order"] as? [String] ?? []
    let failed = Set(q["failed"] as? [String] ?? [])
    let repeatMode = q["repeat"] as? String ?? "off"
    let at = (q["current"] as? String).flatMap { order.firstIndex(of: $0) }
    var ahead: [String] = []
    if let at, repeatMode != "one" {
        ahead = Array(order[(at + 1)...])
        if repeatMode == "all" { ahead += order[..<at] }
    }
    let downloadedAhead = ahead.contains { !failed.contains($0) && local.contains(media[$0] ?? "\u{0}") }
    return downloadedAhead
        ? MusicUnreachable(step: "skip", message: "Skipped \(name): it isn't downloaded, and the server can't be reached.")
        : MusicUnreachable(step: "pause", message: "\(name) isn't downloaded, and the server can't be reached. Nothing after it is downloaded.")
}

/// Runs `spec/vectors/music-offline.json`: what a queue does without its server, and the album/playlist snapshot a download keeps.
public enum MusicOfflineVectorRunner {
    public static let suite = "music-offline"
    public static let operations: Set<String> = ["unreachable", "collection", "budget"]

    public static func execute(operation: String, input: [String: Any]) throws -> [String: Any] {
        func decoded<T: Decodable>(_ type: T.Type, _ key: String) throws -> T {
            guard let value = input[key] else { throw OfflineError.invalidArgument("missing \(key)") }
            return try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
        }
        func string(_ key: String) throws -> String {
            guard let value = input[key] as? String else { throw OfflineError.invalidArgument("missing \(key)") }
            return value
        }
        func box<T>(_ value: T?) -> Any { value.map { $0 as Any } ?? NSNull() }
        switch operation {
        case "unreachable":
            guard let queue = input["queue"] as? [String: Any], let local = input["local"] as? [String] else { throw OfflineError.invalidArgument("unreachable input") }
            let result = musicUnreachable(queue: queue, local: Set(local), title: try string("title"))
            return ["step": result.step, "message": result.message]
        case "collection":
            let manifest = try decoded(OfflineManifest.self, "manifest")
            let id = try string("collection"), item = try string("item")
            let progress: Any = manifest.collectionProgress(id).map { p -> Any in
                ["tracks": p.tracks, "ready": p.ready, "transferring": p.transferring, "waiting": p.waiting, "paused": p.paused,
                 "failed": p.failed, "absent": p.absent, "readyBytes": p.readyBytes, "phase": p.phase]
            } ?? NSNull()
            return ["removal": manifest.collectionRemoval(id), "progress": progress, "playOrder": manifest.collectionPlayOrder(id),
                    "collectionFor": box(manifest.collectionFor(item)?.id), "prunedCollections": manifest.prunedCollections().collections.map(\.id)]
        case "budget":
            let budget = try decoded(OfflineBudget.self, "budget")
            guard let bytes = (input["bytes"] as? NSNumber)?.int64Value else { throw OfflineError.invalidArgument("bytes") }
            return ["refusal": box(budget.refusal(bytes: bytes)), "room": box(budget.roomBytes())]
        default:
            throw OfflineError.invalidArgument("Unknown music-offline operation: \(operation)")
        }
    }
}
