// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// What a library grid asks the server for: which items, in what order, narrowed by which filters.
public struct LibraryQuery: Equatable, Sendable {
    public enum Sort: String, CaseIterable, Sendable {
        case name = "SortName", dateAdded = "DateCreated", releaseDate = "PremiereDate", rating = "CommunityRating", runtime = "Runtime", random = "Random"
        public var title: String {
            switch self {
            case .name: return "Name"
            case .dateAdded: return "Date added"
            case .releaseDate: return "Release date"
            case .rating: return "Rating"
            case .runtime: return "Runtime"
            case .random: return "Random"
            }
        }
        /// The direction a viewer expects when first choosing this sort.
        public var defaultDescending: Bool { self != .name && self != .random }
    }
    public enum Filter: String, CaseIterable, Sendable {
        case unplayed = "IsUnplayed", played = "IsPlayed", resumable = "IsResumable", favorites = "IsFavorite"
        public var title: String {
            switch self {
            case .unplayed: return "Unwatched"
            case .played: return "Watched"
            case .resumable: return "In progress"
            case .favorites: return "Favorites"
            }
        }
    }

    public var parentID: String
    public var collectionType: String?
    public var sort: Sort = .name
    public var descending = false
    public var filters: Set<Filter> = []
    public var genres: Set<String> = []

    public init(parentID: String, collectionType: String?) {
        self.parentID = parentID; self.collectionType = collectionType
    }

    /// Movie and show libraries list their titles recursively, so folders on disk don't leak into the grid.
    /// Anything else browses its direct children.
    public var itemTypes: String? {
        switch collectionType {
        case "movies": return "Movie"
        case "tvshows": return "Series"
        case "boxsets": return "BoxSet"
        case "musicvideos": return "MusicVideo"
        case "homevideos": return "Video,Photo"
        default: return nil
        }
    }

    public func queryItems(startIndex: Int, limit: Int, fields: String) -> [URLQueryItem] {
        let sortBy = sort == .name ? "SortName" : "\(sort.rawValue),SortName"
        var items = [
            URLQueryItem(name: "parentId", value: parentID),
            URLQueryItem(name: "sortBy", value: sortBy),
            URLQueryItem(name: "sortOrder", value: descending ? "Descending" : "Ascending"),
            URLQueryItem(name: "startIndex", value: String(max(0, startIndex))),
            URLQueryItem(name: "limit", value: String(min(200, max(1, limit)))),
            URLQueryItem(name: "enableTotalRecordCount", value: "true"),
            URLQueryItem(name: "fields", value: fields),
        ]
        if let itemTypes {
            items.append(URLQueryItem(name: "recursive", value: "true"))
            items.append(URLQueryItem(name: "includeItemTypes", value: itemTypes))
        }
        if !filters.isEmpty { items.append(URLQueryItem(name: "filters", value: Filter.allCases.filter(filters.contains).map(\.rawValue).joined(separator: ","))) }
        if !genres.isEmpty { items.append(URLQueryItem(name: "genres", value: genres.sorted().joined(separator: "|"))) }
        return items
    }
}
