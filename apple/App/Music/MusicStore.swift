// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import CryptoKit
import Foundation
import PictureshowCore

/// What the queue shows for a song, kept with the saved queue so a restored queue has titles before the server answers.
struct MusicCard: Codable, Equatable, Hashable {
    var id: String
    var title: String
    var artist: String?
    var album: String?
    var albumID: String?
    var albumArtist: String?
    var durationTicks: Int64?
    var art: URL?

    init(id: String, title: String, artist: String? = nil, album: String? = nil, albumID: String? = nil, albumArtist: String? = nil, durationTicks: Int64? = nil, art: URL? = nil) {
        self.id = id; self.title = title; self.artist = artist; self.album = album; self.albumID = albumID
        self.albumArtist = albumArtist; self.durationTicks = durationTicks; self.art = art
    }

    init(_ item: MusicItem, server: URL) {
        self.init(id: item.id, title: item.name, artist: item.track.artistLine(), album: item.album, albumID: item.track.albumID,
                  albumArtist: item.track.albumCredit.nilIfBlank, durationTicks: item.runTimeTicks,
                  art: item.track.imageURL(server: server, width: 600))
    }
}

/// One viewer's saved queue (MUS-08): the committed reducer state as JSON plus what the screens need to show it.
struct MusicSnapshot {
    var queue: [String: Any]
    var context: String?
    var contextName: String?
    var sequence: Int64
    var cards: [MusicCard]
    var position: (entry: String, ticks: Int64)?
}

/// Saved queues, one file per server and viewer in Application Support, never shared between viewers.
@MainActor protocol MusicQueueStore: AnyObject {
    func save(_ key: String, _ snapshot: MusicSnapshot) -> Bool
    func savePosition(_ key: String, entry: String?, ticks: Int64)
    func load(_ key: String) -> MusicSnapshot?
    func delete(_ key: String)
    /// Deletes every saved queue whose viewer isn't one of these.
    func retain(_ keys: Set<String>)
}

@MainActor final class FileMusicQueueStore: MusicQueueStore {
    private let directory: URL
    init(directory: URL? = nil) {
        self.directory = directory ?? (FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("music-queues", isDirectory: true))
    }

    private func file(_ key: String) -> URL {
        let name = SHA256.hash(data: Data(key.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(name + ".json")
    }

    func save(_ key: String, _ snapshot: MusicSnapshot) -> Bool {
        var object: [String: Any] = ["owner": key, "queue": snapshot.queue, "sequence": String(snapshot.sequence)]
        object["context"] = snapshot.context
        object["contextName"] = snapshot.contextName
        object["cards"] = (try? JSONSerialization.jsonObject(with: JSONEncoder().encode(snapshot.cards))) ?? []
        // A new queue starts from its own saved position; a stale one from the last queue would land on the wrong track.
        if let current = snapshot.queue["current"] as? String { object["position"] = ["entry": current, "ticks": snapshot.queue["positionTicks"] as? String ?? "0"] }
        return write(key, object)
    }

    func savePosition(_ key: String, entry: String?, ticks: Int64) {
        guard var object = read(key) else { return }
        object["position"] = entry.map { ["entry": $0, "ticks": String(max(0, ticks))] } ?? NSNull()
        _ = write(key, object)
    }

    func load(_ key: String) -> MusicSnapshot? {
        guard let object = read(key), object["owner"] as? String == key, var queue = object["queue"] as? [String: Any] else { return nil }
        let cards = (object["cards"]).flatMap { try? JSONSerialization.data(withJSONObject: $0) }.flatMap { try? JSONDecoder().decode([MusicCard].self, from: $0) } ?? []
        var position: (String, Int64)?
        if let saved = object["position"] as? [String: Any], let entry = saved["entry"] as? String, let ticks = Int64(saved["ticks"] as? String ?? "") {
            position = (entry, ticks)
            if queue["current"] as? String == entry { queue["positionTicks"] = String(ticks) }
        }
        return MusicSnapshot(queue: queue, context: object["context"] as? String, contextName: object["contextName"] as? String,
                             sequence: Int64(object["sequence"] as? String ?? "") ?? 0, cards: cards, position: position)
    }

    func delete(_ key: String) { try? FileManager.default.removeItem(at: file(key)) }

    func retain(_ keys: Set<String>) {
        guard let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil) else { return }
        for url in files where url.pathExtension == "json" {
            guard let data = try? Data(contentsOf: url), let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let owner = object["owner"] as? String else { try? FileManager.default.removeItem(at: url); continue }
            if !keys.contains(owner) { try? FileManager.default.removeItem(at: url) }
        }
    }

    private func read(_ key: String) -> [String: Any]? {
        guard let data = try? Data(contentsOf: file(key)) else { return nil }
        return try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func write(_ key: String, _ object: [String: Any]) -> Bool {
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var url = directory
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try? url.setResourceValues(values)
            let data = try JSONSerialization.data(withJSONObject: object)
            #if os(iOS)
            try data.write(to: file(key), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            #else
            try data.write(to: file(key), options: .atomic)
            #endif
            return true
        } catch { return false }
    }
}

/// The server side of music playback, so tests can stand in for it.
@MainActor protocol MusicGateway: AnyObject {
    func prepare(_ account: Account, media: String, ceiling: Int) async throws -> PlayDecision
    func report(_ account: Account, kind: String, decision: PlayDecision, ticks: Int64, paused: Bool, failed: Bool) async
    /// The ids among these this viewer can still open. Throws when the server can't say.
    func accessible(_ account: Account, ids: [String]) async throws -> Set<String>
}

@MainActor final class JellyfinMusicGateway: MusicGateway {
    private let client: (Account) -> JellyfinClient
    init(client: @escaping (Account) -> JellyfinClient) { self.client = client }

    func prepare(_ account: Account, media: String, ceiling: Int) async throws -> PlayDecision {
        var decision = try await client(account).prepareMusic(account: account, itemID: media, ceiling: ceiling)
        decision.url = try ServerPlaybackURLBridge.make(decision: decision, account: account)
        return decision
    }

    func report(_ account: Account, kind: String, decision: PlayDecision, ticks: Int64, paused: Bool, failed: Bool) async {
        let client = client(account)
        switch kind {
        case "start": try? await client.reportStart(account: account, decision: decision, positionTicks: ticks, paused: paused, isMuted: false)
        case "progress": try? await client.reportProgress(account: account, decision: decision, positionTicks: ticks, paused: paused, isMuted: false)
        default: try? await client.reportStopped(account: account, decision: decision, positionTicks: ticks, failed: failed)
        }
    }

    func accessible(_ account: Account, ids: [String]) async throws -> Set<String> {
        var found = Set<String>()
        for batch in MusicCatalog.idBatches(ids) {
            found.formUnion(try await client(account).musicItems(account: account, ids: batch).map(\.id))
        }
        return found
    }
}

import PictureshowTransport

/// The token goes on the stream URL as for video, after the same-origin check (`ServerPlaybackURL`).
enum ServerPlaybackURLBridge {
    static func make(decision: PlayDecision, account: Account) throws -> URL {
        try ServerPlaybackURL.make(decisionURL: decision.url, server: account.server.url, token: account.accessToken,
                                   requiredHeaders: decision.source.requiredHttpHeaders)
    }
}
