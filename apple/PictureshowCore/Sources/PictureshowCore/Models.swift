// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct Server: Codable, Equatable, Sendable {
    public var url: URL
    public var id: String
    public var name: String
    public var version: String
    public init(url: URL, id: String = "", name: String = "Jellyfin", version: String = "") {
        self.url = url; self.id = id; self.name = name; self.version = version
    }
}

public struct Account: Codable, Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public var server: Server
    public var userId: String
    public var username: String
    public var accessToken: String
    public var deviceID: String
    public init(server: Server, userId: String, username: String, accessToken: String, deviceID: String) {
        self.server = server; self.userId = userId; self.username = username
        self.accessToken = accessToken; self.deviceID = deviceID
    }
    public var description: String { "Account(userId=\(userId), accessToken=REDACTED)" }
    public var debugDescription: String { description }
}

public struct QuickConnectState: Decodable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let secret: String
    public let code: String
    public let authenticated: Bool
    enum CodingKeys: String, CodingKey { case secret = "Secret", code = "Code", authenticated = "Authenticated" }
    public var description: String { "QuickConnectState(REDACTED)" }
    public var debugDescription: String { description }
}

public struct ItemUserData: Codable, Equatable, Sendable {
    public var playbackPositionTicks: Int64
    public var played: Bool
    public var isFavorite: Bool
    public var playedPercentage: Double?
    public var unplayedItemCount: Int?
    public init(playbackPositionTicks: Int64 = 0, played: Bool = false, isFavorite: Bool = false, playedPercentage: Double? = nil, unplayedItemCount: Int? = nil) {
        self.playbackPositionTicks = playbackPositionTicks; self.played = played; self.isFavorite = isFavorite
        self.playedPercentage = playedPercentage; self.unplayedItemCount = unplayedItemCount
    }
    enum CodingKeys: String, CodingKey { case playbackPositionTicks = "PlaybackPositionTicks", played = "Played", isFavorite = "IsFavorite", playedPercentage = "PlayedPercentage", unplayedItemCount = "UnplayedItemCount" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        playbackPositionTicks = try c.decodeIfPresent(Int64.self, forKey: .playbackPositionTicks) ?? 0
        played = try c.decodeIfPresent(Bool.self, forKey: .played) ?? false
        isFavorite = try c.decodeIfPresent(Bool.self, forKey: .isFavorite) ?? false
        playedPercentage = try c.decodeIfPresent(Double.self, forKey: .playedPercentage)
        unplayedItemCount = try c.decodeIfPresent(Int.self, forKey: .unplayedItemCount)
    }
}

public struct MediaItem: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var type: String
    public var collectionType: String?
    public var isFolder: Bool
    public var childCount: Int?
    public var recursiveItemCount: Int?
    public var overview: String?
    public var productionYear: Int?
    public var officialRating: String?
    public var communityRating: Double?
    public var runTimeTicks: Int64?
    public var genres: [String]
    public var seriesName: String?
    public var seriesId: String?
    public var seasonId: String?
    public var indexNumber: Int?
    public var parentIndexNumber: Int?
    public var imageTags: [String: String]
    public var backdropImageTags: [String]
    public var parentBackdropItemId: String?
    public var parentBackdropImageTags: [String]
    public var seriesPrimaryImageTag: String?
    public var parentThumbItemId: String?
    public var parentThumbImageTag: String?
    public var userData: ItemUserData
    public var people: [PersonCredit]
    public var chapters: [Chapter]
    public var trickplay: [String:[String:TrickplayInfo]]
    public var mediaSources: [MediaSource]
    public init(id: String, name: String = "Untitled", type: String = "", userData: ItemUserData = ItemUserData(), runTimeTicks: Int64? = nil) {
        self.id = id; self.name = name; self.type = type; self.userData = userData; self.runTimeTicks = runTimeTicks
        isFolder = false; genres = []; imageTags = [:]; backdropImageTags = []; parentBackdropImageTags = []; mediaSources = []; people = []; chapters = []; trickplay = [:]
    }
    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", type = "Type", collectionType = "CollectionType", isFolder = "IsFolder", childCount = "ChildCount", recursiveItemCount = "RecursiveItemCount", overview = "Overview", productionYear = "ProductionYear", officialRating = "OfficialRating", communityRating = "CommunityRating", runTimeTicks = "RunTimeTicks", genres = "Genres", seriesName = "SeriesName", seriesId = "SeriesId", seasonId = "SeasonId", indexNumber = "IndexNumber", parentIndexNumber = "ParentIndexNumber", imageTags = "ImageTags", backdropImageTags = "BackdropImageTags", parentBackdropItemId = "ParentBackdropItemId", parentBackdropImageTags = "ParentBackdropImageTags", seriesPrimaryImageTag = "SeriesPrimaryImageTag", parentThumbItemId = "ParentThumbItemId", parentThumbImageTag = "ParentThumbImageTag", userData = "UserData", mediaSources = "MediaSources", people = "People", chapters = "Chapters", trickplay = "Trickplay"
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Untitled"
        type = try c.decodeIfPresent(String.self, forKey: .type) ?? ""
        collectionType = try c.decodeIfPresent(String.self, forKey: .collectionType)
        isFolder = try c.decodeIfPresent(Bool.self, forKey: .isFolder) ?? false
        childCount = try c.decodeIfPresent(Int.self, forKey: .childCount)
        recursiveItemCount = try c.decodeIfPresent(Int.self, forKey: .recursiveItemCount)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        productionYear = try c.decodeIfPresent(Int.self, forKey: .productionYear)
        officialRating = try c.decodeIfPresent(String.self, forKey: .officialRating)
        communityRating = try c.decodeIfPresent(Double.self, forKey: .communityRating)
        runTimeTicks = try c.decodeIfPresent(Int64.self, forKey: .runTimeTicks)
        genres = try c.decodeIfPresent([String].self, forKey: .genres) ?? []
        seriesName = try c.decodeIfPresent(String.self, forKey: .seriesName)
        seriesId = try c.decodeIfPresent(String.self, forKey: .seriesId)
        seasonId = try c.decodeIfPresent(String.self, forKey: .seasonId)
        indexNumber = try c.decodeIfPresent(Int.self, forKey: .indexNumber)
        parentIndexNumber = try c.decodeIfPresent(Int.self, forKey: .parentIndexNumber)
        imageTags = try c.decodeIfPresent([String:String].self, forKey: .imageTags) ?? [:]
        backdropImageTags = try c.decodeIfPresent([String].self, forKey: .backdropImageTags) ?? []
        parentBackdropItemId = try c.decodeIfPresent(String.self, forKey: .parentBackdropItemId)
        parentBackdropImageTags = try c.decodeIfPresent([String].self, forKey: .parentBackdropImageTags) ?? []
        seriesPrimaryImageTag = try c.decodeIfPresent(String.self, forKey: .seriesPrimaryImageTag)
        parentThumbItemId = try c.decodeIfPresent(String.self, forKey: .parentThumbItemId)
        parentThumbImageTag = try c.decodeIfPresent(String.self, forKey: .parentThumbImageTag)
        userData = try c.decodeIfPresent(ItemUserData.self, forKey: .userData) ?? ItemUserData()
        mediaSources = try c.decodeIfPresent([MediaSource].self, forKey: .mediaSources) ?? []
        people = try c.decodeIfPresent([PersonCredit].self, forKey: .people) ?? []
        chapters = try c.decodeIfPresent([Chapter].self, forKey: .chapters) ?? []
        trickplay = try c.decodeIfPresent([String:[String:TrickplayInfo]].self, forKey: .trickplay) ?? [:]
    }
    public var isEmptyContainer: Bool {
        let container = isFolder || ["BoxSet", "Playlist", "Folder", "CollectionFolder", "Series", "Season"].contains(type)
        return container && !(childCount.map { $0 > 0 } ?? false) && !(recursiveItemCount.map { $0 > 0 } ?? false) && (childCount == 0 || recursiveItemCount == 0)
    }
    public var isWatched: Bool { userData.played && !isEmptyContainer }
    public var progress: Double {
        if let p = userData.playedPercentage, p.isFinite { return min(1, max(0, p / 100)) }
        guard let ticks = runTimeTicks, ticks > 0 else { return 0 }
        return min(1, max(0, Double(userData.playbackPositionTicks) / Double(ticks)))
    }
    public var episodeLabel: String {
        let season = parentIndexNumber.map { $0 == 0 ? "Special" : "S\($0)" }
        let episode = indexNumber.map { "E\($0)" }
        if parentIndexNumber == 0 { return [season, episode].compactMap { $0 }.joined(separator: " ") }
        return [season, episode].compactMap { $0 }.joined(separator: ":").nilIfEmpty ?? "Episode"
    }
}

public struct ItemPage: Decodable, Sendable {
    public let items: [MediaItem]
    public let totalRecordCount: Int
    enum CodingKeys: String, CodingKey { case items = "Items", totalRecordCount = "TotalRecordCount" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([MediaItem].self, forKey: .items) ?? []
        totalRecordCount = try c.decodeIfPresent(Int.self, forKey: .totalRecordCount) ?? items.count
    }
}

public struct HomeRow: Identifiable, Equatable, Sendable {
    public let id: String
    public let title: String
    public let items: [MediaItem]
    public let library: Bool
    public let wide: Bool
    public init(id: String, title: String, items: [MediaItem], library: Bool = false, wide: Bool = false) {
        self.id = id; self.title = title; self.items = items; self.library = library; self.wide = wide
    }
}

public struct HomeContent: Equatable, Sendable {
    public let rows: [HomeRow]
    public let warnings: [String]
    public init(rows: [HomeRow], warnings: [String]) { self.rows = rows; self.warnings = warnings }
}

public enum Catalog {
    public static func assembleHome(views: [MediaItem], resume: [MediaItem], nextUp: [MediaItem], latest: [String:[MediaItem]]) -> [HomeRow] {
        var rows: [HomeRow] = []
        if !resume.isEmpty { rows.append(HomeRow(id: "resume", title: "Continue watching", items: resume, wide: true)) }
        if !nextUp.isEmpty { rows.append(HomeRow(id: "nextup", title: "Next up", items: nextUp, wide: true)) }
        if !views.isEmpty { rows.append(HomeRow(id: "libraries", title: "Libraries", items: views, library: true)) }
        for view in views where ["movies", "tvshows"].contains(view.collectionType ?? "") {
            if let items = latest[view.id], !items.isEmpty { rows.append(HomeRow(id: "latest:\(view.id)", title: "Latest in \(view.name)", items: items)) }
        }
        return rows
    }
    public static func resumeLabel(_ ticks: Int64) -> String {
        let s = max(0, ticks) / Ticks.perSecond
        return "Resume from " + (s >= 3600 ? String(format: "%lld:%02lld:%02lld", s / 3600, (s % 3600) / 60, s % 60) : String(format: "%lld:%02lld", s / 60, s % 60))
    }
    public static func runtimeLabel(_ ticks: Int64?) -> String {
        guard let ticks, ticks > 0, ticks <= 864_000_000_000 else { return "" }
        let minutes = max(1, ticks / 600_000_000)
        return minutes >= 60 ? "\(minutes / 60)h \(minutes % 60)m" : "\(minutes)m"
    }
}

extension String { var nilIfEmpty: String? { isEmpty ? nil : self } }
