// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Music-specific metadata complements the video catalog model without discarding artist or album credits.
public struct MusicTrack: Decodable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var sortName: String?
    public var disc: Int?
    public var number: Int?
    public var artistNames: [String]
    public var albumArtist: String?
    public var albumArtistNames: [String]
    public var location: String?
    public var albumID: String?
    public var albumImageTag: String?
    public var imageTags: [String: String]
    public var type: String
    public var seriesID: String?
    public var seriesImageTag: String?
    public var backdropTags: [String]
    public var parentBackdropID: String?
    public var parentBackdropTags: [String]
    public var parentThumbID: String?
    public var parentThumbTag: String?
    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", sortName = "SortName", disc = "ParentIndexNumber", number = "IndexNumber"
        case artists = "ArtistItems", albumArtist = "AlbumArtist", albumArtists = "AlbumArtists", location = "LocationType"
        case albumID = "AlbumId", albumImageTag = "AlbumPrimaryImageTag", imageTags = "ImageTags"
        case type = "Type", seriesID = "SeriesId", seriesImageTag = "SeriesPrimaryImageTag"
        case backdropTags = "BackdropImageTags", parentBackdropID = "ParentBackdropItemId", parentBackdropTags = "ParentBackdropImageTags"
        case parentThumbID = "ParentThumbItemId", parentThumbTag = "ParentThumbImageTag"
    }
    private struct Credit: Decodable {
        var name: String
        enum CodingKeys: String, CodingKey { case name = "Name" }
        init(from decoder: Decoder) throws { name = try decoder.container(keyedBy: CodingKeys.self).decodeIfPresent(String.self, forKey: .name) ?? "" }
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Untitled"
        sortName = try c.decodeIfPresent(String.self, forKey: .sortName)
        disc = try c.decodeIfPresent(Int.self, forKey: .disc); number = try c.decodeIfPresent(Int.self, forKey: .number)
        artistNames = try c.decodeIfPresent([Credit].self, forKey: .artists)?.map(\.name) ?? []
        albumArtist = try c.decodeIfPresent(String.self, forKey: .albumArtist)
        albumArtistNames = try c.decodeIfPresent([Credit].self, forKey: .albumArtists)?.map(\.name) ?? []
        location = try c.decodeIfPresent(String.self, forKey: .location)
        albumID = try c.decodeIfPresent(String.self, forKey: .albumID)
        albumImageTag = try c.decodeIfPresent(String.self, forKey: .albumImageTag)
        imageTags = try c.decodeIfPresent([String: String].self, forKey: .imageTags) ?? [:]
        type = try c.decodeIfPresent(String.self, forKey: .type) ?? ""
        seriesID = try c.decodeIfPresent(String.self, forKey: .seriesID); seriesImageTag = try c.decodeIfPresent(String.self, forKey: .seriesImageTag)
        backdropTags = try c.decodeIfPresent([String].self, forKey: .backdropTags) ?? []
        parentBackdropID = try c.decodeIfPresent(String.self, forKey: .parentBackdropID)
        parentBackdropTags = try c.decodeIfPresent([String].self, forKey: .parentBackdropTags) ?? []
        parentThumbID = try c.decodeIfPresent(String.self, forKey: .parentThumbID); parentThumbTag = try c.decodeIfPresent(String.self, forKey: .parentThumbTag)
    }
    public var isAvailable: Bool { location != "Virtual" }
    public func artistLine(albumCredit: String? = nil) -> String? {
        let names = artistNames.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: ", ")
        let line = names.isEmpty ? (albumArtist ?? "") : names
        return line.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || line == albumCredit ? nil : line
    }
    public var albumCredit: String {
        let names = albumArtistNames.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }.joined(separator: ", ")
        return names.isEmpty ? (albumArtist ?? "") : names
    }
    public func imageURL(server: URL, width: Int, height: Int? = nil, wide: Bool = false) -> URL? {
        func nonblank(_ text: String?) -> String? { text.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 } }
        func own(_ kind: String) -> (String, String, String)? { nonblank(imageTags[kind]).map { (id, kind, $0) } }
        func backdrop() -> (String, String, String)? {
            if let tag = nonblank(backdropTags.first) { return (id, "Backdrop", tag) }
            if let parentBackdropID, let tag = nonblank(parentBackdropTags.first) { return (parentBackdropID, "Backdrop", tag) }
            return nil
        }
        func parentThumb() -> (String, String, String)? {
            guard let parentThumbID, let tag = nonblank(parentThumbTag) else { return nil }; return (parentThumbID, "Thumb", tag)
        }
        let image: (String, String, String)?
        if wide || type == "Episode" { image = own("Thumb") ?? (type == "Episode" ? own("Primary") : nil) ?? backdrop() ?? parentThumb() ?? own("Primary") }
        else if let primary = own("Primary") { image = primary }
        else if let albumID = nonblank(albumID), let tag = nonblank(albumImageTag) { image = (albumID, "Primary", tag) }
        else if let seriesID, let tag = nonblank(seriesImageTag) { image = (seriesID, "Primary", tag) }
        else { image = backdrop() ?? own("Thumb") }
        guard let image else { return nil }
        var c = URLComponents(url: server.appendingPathComponent("Items").appendingPathComponent(image.0).appendingPathComponent("Images").appendingPathComponent(image.1), resolvingAgainstBaseURL: false)
        var query = [URLQueryItem(name: "tag", value: image.2), URLQueryItem(name: "maxWidth", value: String(max(1, width)))]
        if let height { query.append(URLQueryItem(name: "maxHeight", value: String(max(1, height)))) }
        query.append(URLQueryItem(name: "quality", value: "85")); c?.queryItems = query
        return c?.url
    }
}

public struct MusicRequest: Equatable, Sendable {
    public var path: String
    public var parameters: [String: String]
    public var json: [String: Any] { ["path": path, "parameters": parameters] }
}
public enum MusicSection: String, CaseIterable, Sendable {
    case albums = "ALBUMS", albumArtists = "ALBUM_ARTISTS", artists = "ARTISTS", songs = "SONGS"
    case genres = "GENRES", playlists = "PLAYLISTS", favorites = "FAVORITES"
}
public enum MusicArtistRole: String, Sendable { case albumArtist = "ALBUM_ARTIST", artist = "ARTIST" }
public struct MusicPageState: Equatable, Sendable {
    public var ids: [String] = []
    public var offset = 0
    public var total = 0
    public var ended = false
    public init() {}
    public mutating func merge(ids page: [String], total: Int) {
        offset += page.count; self.total = total; ended = page.isEmpty || offset >= total
        var seen = Set(ids); ids += page.filter { seen.insert($0).inserted }
    }
}
public struct MusicDisc: Equatable, Sendable {
    public var disc: Int?
    public var tracks: [String]
    public var json: [String: Any] { ["disc": disc as Any? ?? NSNull(), "tracks": tracks] }
}
public enum MusicCatalog {
    public static let pageSize = 60, randomLimit = 200, queueLimit = 2000, idBatchSize = 100
    private static let cardFields = "PrimaryImageAspectRatio,ProductionYear,Genres,ChildCount,RecursiveItemCount,SortName"
    private static var trackFields: String { cardFields + ",ParentId,MediaSources" }
    private static let trackOrder = "ParentIndexNumber,IndexNumber,SortName"
    private static func paged(_ start: Int, _ limit: Int, sort: String = "SortName", descending: Bool = false) -> [String: String] {
        ["startIndex": String(max(0, start)), "limit": String(min(200, max(1, limit))), "enableTotalRecordCount": "true", "sortBy": sort, "sortOrder": descending ? "Descending" : "Ascending", "fields": cardFields]
    }
    public static func section(_ section: MusicSection, library: String, start: Int = 0, limit: Int = pageSize, sort: String = "SortName", descending: Bool = false) -> MusicRequest {
        var p = paged(start, limit, sort: sort, descending: descending); p["parentId"] = library
        var path = "Items"
        switch section {
        case .albumArtists: path = "Artists/AlbumArtists"
        case .artists: path = "Artists"
        case .genres: path = "Genres"
        case .albums: p["includeItemTypes"] = "MusicAlbum"; p["recursive"] = "true"
        case .songs: p["includeItemTypes"] = "Audio"; p["recursive"] = "true"; p["fields"] = trackFields
        case .playlists: p.removeValue(forKey: "parentId"); p["includeItemTypes"] = "Playlist"; p["recursive"] = "true"; p["mediaTypes"] = "Audio"
        case .favorites: p["includeItemTypes"] = "MusicAlbum,Audio"; p["recursive"] = "true"; p["filters"] = "IsFavorite"; p["fields"] = trackFields
        }
        return MusicRequest(path: path, parameters: p)
    }
    public static func favoriteArtists(library: String, start: Int = 0, limit: Int = pageSize) -> MusicRequest {
        var p = paged(start, limit); p["parentId"] = library; p["isFavorite"] = "true"
        return MusicRequest(path: "Artists", parameters: p)
    }
    public static func artist(_ artist: String, role: MusicArtistRole, library: String?, tracks: Bool = false, start: Int = 0, limit: Int? = nil) -> MusicRequest {
        var p = paged(start, limit ?? (tracks ? 200 : pageSize), sort: tracks ? "Album," + trackOrder : "ProductionYear,SortName", descending: !tracks)
        p[role == .albumArtist ? "albumArtistIds" : (tracks ? "artistIds" : "contributingArtistIds")] = artist
        p["parentId"] = library; p["recursive"] = "true"; p["includeItemTypes"] = tracks ? "Audio" : "MusicAlbum"
        if tracks { p["fields"] = trackFields }
        return MusicRequest(path: "Items", parameters: p)
    }
    public static func genre(_ genre: String, library: String, tracks: Bool = false, start: Int = 0, limit: Int? = nil) -> MusicRequest {
        var p = paged(start, limit ?? (tracks ? 200 : pageSize), sort: tracks ? "Album," + trackOrder : "SortName")
        p["parentId"] = library; p["genreIds"] = genre; p["recursive"] = "true"; p["includeItemTypes"] = tracks ? "Audio" : "MusicAlbum"
        if tracks { p["fields"] = trackFields }
        return MusicRequest(path: "Items", parameters: p)
    }
    public static func albumTracks(_ album: String, start: Int = 0, limit: Int = 200) -> MusicRequest {
        var p = paged(start, limit, sort: trackOrder); p["parentId"] = album; p["recursive"] = "true"; p["includeItemTypes"] = "Audio"; p["fields"] = trackFields
        return MusicRequest(path: "Items", parameters: p)
    }
    public static func playlistItems(_ playlist: String, start: Int = 0, limit: Int = 200) -> MusicRequest {
        var p = paged(start, limit); p.removeValue(forKey: "sortBy"); p.removeValue(forKey: "sortOrder"); p["fields"] = trackFields
        return MusicRequest(path: "Playlists/\(playlist)/Items", parameters: p)
    }
    public static func instantMix(_ item: String, limit: Int = 100) -> MusicRequest {
        MusicRequest(path: "Items/\(item)/InstantMix", parameters: ["limit": String(min(200, max(1, limit))), "fields": trackFields])
    }
    public static func randomSongs(library: String, limit: Int = randomLimit) -> MusicRequest {
        var p = paged(0, limit, sort: "Random"); p.removeValue(forKey: "sortOrder"); p["parentId"] = library; p["recursive"] = "true"; p["includeItemTypes"] = "Audio"; p["fields"] = trackFields
        return MusicRequest(path: "Items", parameters: p)
    }
    public static func order(_ tracks: [MusicTrack]) -> [MusicTrack] {
        tracks.sorted { a, b in
            if (a.disc ?? 1) != (b.disc ?? 1) { return (a.disc ?? 1) < (b.disc ?? 1) }
            if (a.number == nil) != (b.number == nil) { return a.number != nil }
            if (a.number ?? 0) != (b.number ?? 0) { return (a.number ?? 0) < (b.number ?? 0) }
            let an = (a.sortName ?? a.name).lowercased(), bn = (b.sortName ?? b.name).lowercased()
            return an != bn ? an < bn : a.id < b.id
        }
    }
    public static func idBatches(_ ids: [String]) -> [[String]] {
        stride(from: 0, to: ids.count, by: idBatchSize).map { Array(ids[$0..<min($0 + idBatchSize, ids.count)]) }
    }
    public static func discs(_ tracks: [MusicTrack]) -> [MusicDisc] {
        let ordered = order(tracks), numbers = Set(tracks.map { $0.disc ?? 1 }).sorted()
        if numbers.count < 2 { return ordered.isEmpty ? [] : [MusicDisc(disc: nil, tracks: ordered.map(\.id))] }
        return numbers.map { disc in MusicDisc(disc: disc, tracks: ordered.filter { ($0.disc ?? 1) == disc }.map(\.id)) }
    }
    public static func listState(loading: Bool, items: Int, errorKind: String?, status: Int?) -> String {
        if items > 0 { return "READY" }; if errorKind == "EXPIRED" { return "EXPIRED" }
        if status == 403 || status == 404 { return "INACCESSIBLE" }; if errorKind != nil { return "FAILED" }
        return loading ? "LOADING" : "EMPTY"
    }
}

public struct MusicWindowPlan: Equatable, Sendable {
    public var replace = false
    public var removeBefore = 0
    public var removeAfter = 0
    public var add: [String] = []
    public var json: [String: Any] { ["replace": replace, "removeBefore": removeBefore, "removeAfter": removeAfter, "add": add] }
}
public enum MusicQueue {
    public static func entries(_ media: [String], firstSequence: Int64) throws -> [[String: String]] {
        try media.enumerated().map { index, media in
            let value = firstSequence.addingReportingOverflow(Int64(index))
            guard !value.overflow else { throw MusicInput.failure("entry sequence overflow") }
            return ["id": "m\(value.partialValue)", "media": media]
        }
    }
    public static func permutation(_ ids: [String], seed: Int64) -> [String] {
        var result = ids, state = ((seed % 2_147_483_646 + 2_147_483_646) % 2_147_483_646) + 1
        if result.count > 1 { for i in stride(from: result.count - 1, through: 1, by: -1) {
            state = state * 48271 % 2_147_483_647; result.swapAt(i, Int(state % Int64(i + 1)))
        } }
        return result
    }
    public static func window(order: [String], current: String?, repeatMode: String, failed: Set<String>, size: Int = 3) -> [String] {
        guard let current, let at = order.firstIndex(of: current) else { return [] }
        if repeatMode == "one" { return [current] }
        let after = Array(order.dropFirst(at + 1)) + (repeatMode == "all" ? Array(order.prefix(at)) : [])
        return [current] + after.filter { !failed.contains($0) }.prefix(max(0, size - 1))
    }
    public static func outputRepeats(order: [String], current: String?, repeatMode: String) -> Bool {
        current != nil && (repeatMode == "one" || (repeatMode == "all" && order.count == 1))
    }
    public static func upcoming(order: [String], current: String?) -> [String] {
        guard let current, let index = order.firstIndex(of: current) else { return order }
        return Array(order.dropFirst(index + 1))
    }
    public static func plan(held: [String], playing: String?, target: [String]) -> MusicWindowPlan {
        guard let head = target.first else { return MusicWindowPlan(replace: !held.isEmpty) }
        guard playing == head, let at = held.firstIndex(of: head) else { return MusicWindowPlan(replace: true, add: target) }
        let after = Array(held.dropFirst(at + 1)), wanted = Array(target.dropFirst())
        let keep = zip(after, wanted).prefix { $0 == $1 }.count
        return MusicWindowPlan(removeBefore: at, removeAfter: after.count - keep, add: Array(wanted.dropFirst(keep)))
    }
    public static func playDecision(context: String?, queueEntries: Int, queueContext: String?, remoteAttached: Bool = false, castAttached: Bool = false) -> String {
        if remoteAttached || castAttached { return "confirm-destination" }; if queueEntries == 0 { return "play" }
        return context != nil && context == queueContext ? "jump" : "confirm-replace"
    }
}
