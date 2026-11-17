// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// A row on a music screen: an album, artist, genre, playlist or song, with the credits the video model leaves out.
public struct MusicItem: Decodable, Equatable, Sendable, Identifiable, Hashable {
    public struct Credit: Decodable, Equatable, Sendable, Hashable {
        public var id: String
        public var name: String
        enum CodingKeys: String, CodingKey { case id = "Id", name = "Name" }
        public init(id: String, name: String) { self.id = id; self.name = name }
        public init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""; name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        }
    }
    public var id: String
    public var name: String
    public var type: String
    public var mediaType: String?
    public var album: String?
    public var albumArtist: String?
    public var artists: [Credit]
    public var albumArtists: [Credit]
    public var runTimeTicks: Int64?
    public var productionYear: Int?
    public var childCount: Int?
    /// A playlist row's own entry id, which removing and moving name (MUS-09).
    public var playlistItemID: String?
    public var isFavorite: Bool
    public var container: String?
    public var codec: String?
    /// The shared catalog model: ordering, disc grouping, credits and artwork (spec/vectors/music-catalog.json).
    public var track: MusicTrack

    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", type = "Type", mediaType = "MediaType", album = "Album", albumArtist = "AlbumArtist"
        case artists = "ArtistItems", albumArtists = "AlbumArtists", runTimeTicks = "RunTimeTicks", productionYear = "ProductionYear"
        case childCount = "ChildCount", playlistItemID = "PlaylistItemId", userData = "UserData", mediaSources = "MediaSources"
    }
    private struct UserData: Decodable { var isFavorite: Bool?; enum CodingKeys: String, CodingKey { case isFavorite = "IsFavorite" } }
    private struct Source: Decodable {
        var container: String?; var streams: [Stream]?
        enum CodingKeys: String, CodingKey { case container = "Container", streams = "MediaStreams" }
        struct Stream: Decodable { var type: String?; var codec: String?; enum CodingKeys: String, CodingKey { case type = "Type", codec = "Codec" } }
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Untitled"
        type = try c.decodeIfPresent(String.self, forKey: .type) ?? ""
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType)
        album = try c.decodeIfPresent(String.self, forKey: .album)
        albumArtist = try c.decodeIfPresent(String.self, forKey: .albumArtist)
        artists = try c.decodeIfPresent([Credit].self, forKey: .artists) ?? []
        albumArtists = try c.decodeIfPresent([Credit].self, forKey: .albumArtists) ?? []
        runTimeTicks = try c.decodeIfPresent(Int64.self, forKey: .runTimeTicks)
        productionYear = try c.decodeIfPresent(Int.self, forKey: .productionYear)
        childCount = try c.decodeIfPresent(Int.self, forKey: .childCount)
        playlistItemID = try c.decodeIfPresent(String.self, forKey: .playlistItemID)
        isFavorite = try c.decodeIfPresent(UserData.self, forKey: .userData)?.isFavorite ?? false
        let source = try c.decodeIfPresent([Source].self, forKey: .mediaSources)?.first
        container = source?.container
        codec = source?.streams?.first { $0.type == "Audio" }?.codec
        track = try MusicTrack(from: decoder)
    }
    public static func == (a: MusicItem, b: MusicItem) -> Bool { a.id == b.id && a.playlistItemID == b.playlistItemID && a.isFavorite == b.isFavorite && a.name == b.name }
    public func hash(into hasher: inout Hasher) { hasher.combine(id); hasher.combine(playlistItemID) }

    /// What opens in the music screens rather than the video grid or detail. A video playlist stays where it was.
    public static func isMusic(type: String, collectionType: String?, mediaType: String?) -> Bool {
        collectionType == "music" || ["MusicAlbum", "MusicArtist", "MusicGenre", "Audio"].contains(type) || (type == "Playlist" && mediaType == "Audio")
    }
    public var isTrack: Bool { type == "Audio" }
    public var isAvailable: Bool { track.isAvailable }
    /// "Artist" under a song, the album artist under an album.
    public var subtitle: String? {
        switch type {
        case "MusicAlbum": return (albumArtists.map(\.name).joined(separator: ", ").nilIfEmpty ?? albumArtist)
        case "Audio": return track.artistLine()
        case "Playlist": return childCount.map { $0 == 1 ? "1 song" : "\($0) songs" }
        default: return nil
        }
    }
}

public struct MusicPage: Sendable { public var items: [MusicItem]; public var total: Int }

/// What a playlist write came to (MUS-09). "unknown" means it may have reached the server: only a read-back can tell.
public struct PlaylistWrite: Equatable, Sendable {
    public var outcome: String
    public var id: String?
    public init(_ outcome: String, id: String? = nil) { self.outcome = outcome; self.id = id }
}

/// What AVFoundation itself plays, measured on Apple's own decoders (MUS-PD02). FLAC is direct only in its own container
/// (iOS 11+ and tvOS 11+); Opus and Vorbis in Ogg or Matroska aren't demuxed by AVFoundation, so the server converts them.
/// Conversion is AAC in HLS transport stream, capped by the quality choice (MUS-07).
public enum AppleMusicProfile {
    public static let direct: [(container: String, codecs: String)] = [
        ("mp3", "mp3"), ("aac", "aac"), ("m4a,m4b,mp4,mov", "aac,alac"), ("flac", "flac"), ("wav", ReferenceDeviceProfile.audioName("PCM")),
        ("aiff,aif", ReferenceDeviceProfile.audioName("PCM"))
    ]
    public static func json(ceiling: Int, name: String = "Pictureshow music (Apple)") -> [String: Any] {
        let direct: [[String: Any]] = direct.map { ["Container": $0.container, "AudioCodec": $0.codecs, "VideoCodec": "", "Type": "Audio"] }
        let conversion: [[String: Any]] = [["Container": "ts", "Type": "Audio", "VideoCodec": "", "AudioCodec": "aac", "Protocol": "hls", "Context": "Streaming",
                                            "MaxAudioChannels": "2", "MinSegments": 1, "BreakOnNonKeyFrames": true]]
        return ["Name": name, "MaxStreamingBitrate": ceiling, "MaxStaticBitrate": ceiling, "MaxStaticMusicBitrate": ceiling,
                "MusicStreamingTranscodingBitrate": min(256_000, ceiling), "DirectPlayProfiles": direct, "TranscodingProfiles": conversion,
                "ContainerProfiles": [], "CodecProfiles": [], "SubtitleProfiles": []]
    }
}

/// Music reads and writes over the viewer's own token (spec/features/music.md). Every id list goes in batches of 100.
extension JellyfinClient {
    public func musicPage(account: Account, request: MusicRequest) async throws -> MusicPage {
        let query = request.parameters.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) }
        let data = try await self.request(account: account, path: request.path, query: query)
        struct Page: Decodable {
            var items: [MusicItem]; var total: Int?
            enum CodingKeys: String, CodingKey { case items = "Items", total = "TotalRecordCount" }
        }
        let page = try JSONDecoder().decode(Page.self, from: data)
        return MusicPage(items: page.items, total: page.total ?? page.items.count)
    }

    /// Every page of a list, up to the queue limit, in the server's order.
    public func musicAll(account: Account, _ request: (Int) -> MusicRequest) async throws -> [MusicItem] {
        var rows: [MusicItem] = [], page = MusicPageState()
        while !page.ended && rows.count < MusicCatalog.queueLimit {
            let answer = try await musicPage(account: account, request: request(page.offset))
            page.merge(ids: answer.items.map(\.id), total: answer.total)
            rows += answer.items
        }
        return rows
    }

    /// The rows for these ids that this viewer can still open, batched so no request line gets too long.
    public func musicItems(account: Account, ids: [String], fields: String? = nil) async throws -> [MusicItem] {
        var rows: [MusicItem] = []
        for batch in MusicCatalog.idBatches(ids) {
            var parameters = ["ids": batch.joined(separator: ","), "limit": String(batch.count)]
            if let fields { parameters["fields"] = fields }
            rows += try await musicPage(account: account, request: MusicRequest(path: "Items", parameters: parameters)).items
        }
        return rows
    }

    /// A playlist the server doesn't name this user on (or any failure) is read-only, so edits are refused before they're sent.
    public func playlistCanEdit(account: Account, playlist: String) async -> Bool {
        guard let data = try? await request(account: account, path: "Playlists/\(ServerURLs.encode(playlist))/Users/\(ServerURLs.encode(account.userId))", includeUserID: false),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return object["CanEdit"] as? Bool == true
    }

    private func playlistWrite(account: Account, path: String, method: String = "POST", query: [URLQueryItem] = [], body: [String: Any]? = nil) async -> PlaylistWrite {
        do {
            let data = try await request(account: account, path: path, method: method, query: query, body: body, includeUserID: false)
            let id = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["Id"] as? String
            return PlaylistWrite("ok", id: id?.isEmpty == false ? id : nil)
        } catch ClientError.invalidCredentials { return PlaylistWrite("denied") }
        catch ClientError.server(let status) where status == 403 || status == 404 { return PlaylistWrite("denied") }
        catch ClientError.server(let status) where status >= 500 { return PlaylistWrite("unknown") }
        catch ClientError.server { return PlaylistWrite("failed") }
        catch is CancellationError { return PlaylistWrite("unknown") }
        catch { return PlaylistWrite("unknown") } // The request may have reached the server before the connection went.
    }

    /// A long list goes in several requests. The first that isn't "ok" ends it; after an earlier batch landed, it's "unknown".
    private func inBatches(_ ids: [String], _ send: ([String]) async -> PlaylistWrite) async -> PlaylistWrite {
        var last = PlaylistWrite("ok")
        for (index, batch) in MusicCatalog.idBatches(ids).enumerated() {
            last = await send(batch)
            if last.outcome != "ok" { return index > 0 ? PlaylistWrite("unknown") : last }
        }
        return last
    }

    /// New playlists are private to the viewer (`IsPublic: false`).
    public func createPlaylist(account: Account, name: String, items: [String]) async -> PlaylistWrite {
        await playlistWrite(account: account, path: "Playlists", body: MusicReducers.createPlaylistBody(name: name, viewer: account.userId, items: items))
    }
    public func renamePlaylist(account: Account, playlist: String, name: String) async -> PlaylistWrite {
        await playlistWrite(account: account, path: "Playlists/\(ServerURLs.encode(playlist))", body: MusicReducers.renamePlaylistBody(name: name))
    }
    public func addToPlaylist(account: Account, playlist: String, items: [String]) async -> PlaylistWrite {
        await inBatches(items) { batch in
            await playlistWrite(account: account, path: "Playlists/\(ServerURLs.encode(playlist))/Items", query: [URLQueryItem(name: "ids", value: batch.joined(separator: ",")), URLQueryItem(name: "userId", value: account.userId)])
        }
    }
    public func removeFromPlaylist(account: Account, playlist: String, entries: [String]) async -> PlaylistWrite {
        await inBatches(entries) { batch in
            await playlistWrite(account: account, path: "Playlists/\(ServerURLs.encode(playlist))/Items", method: "DELETE", query: [URLQueryItem(name: "entryIds", value: batch.joined(separator: ","))])
        }
    }
    public func movePlaylistEntry(account: Account, playlist: String, entry: String, index: Int) async -> PlaylistWrite {
        await playlistWrite(account: account, path: "Playlists/\(ServerURLs.encode(playlist))/Items/\(ServerURLs.encode(entry))/Move/\(max(0, index))")
    }

    /// One song's stream: the original file from `/Audio/{id}/stream` when AVFoundation plays it, else the server's AAC HLS.
    public func prepareMusic(account: Account, itemID: String, ceiling: Int) async throws -> PlayDecision {
        let body = PlaybackRequests.body(userID: account.userId, profile: AppleMusicProfile.json(ceiling: ceiling), maxStreamingBitrate: ceiling,
                                         enableDirectPlay: true, enableDirectStream: false, allowVideoStreamCopy: false, allowAudioStreamCopy: true)
        let data = try await request(account: account, path: "Items/\(ServerURLs.encode(itemID))/PlaybackInfo", method: "POST", body: body, includeUserID: false)
        let info = try JSONDecoder().decode(PlaybackInfo.self, from: data)
        var decision = try PlayDecisions.decide(account: account, itemID: itemID, response: info)
        if decision.method != .transcode, var parts = URLComponents(url: decision.url, resolvingAgainstBaseURL: false) {
            let marker = "/Videos/\(ServerURLs.encode(itemID))/stream"
            if let range = parts.percentEncodedPath.range(of: marker, options: .backwards) {
                parts.percentEncodedPath.replaceSubrange(range, with: "/Audio/\(ServerURLs.encode(itemID))/stream")
                if let url = parts.url { decision.url = url }
            }
        }
        return decision
    }
}

