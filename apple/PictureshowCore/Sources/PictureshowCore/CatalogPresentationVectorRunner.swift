// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Deterministic adapters for the catalog-return, search-presentation and catalog presentation application contracts;
/// expectations are never read by this runner.
public enum CatalogPresentationVectorRunner {
    public static let applicationOperations: Set<String> = ["detail-play-item", "catalog-announcement", "runtime-label", "catalog-placeholder-kind", "search-duplicate-labels", "media-badges", "detail-play-label"]

    static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T {
        guard let value else { throw NSError(domain: "Missing vector input for \(T.self)", code: 1) }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed))
    }
    static func optional<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T? {
        value == nil || value is NSNull ? nil : try decode(type, value)
    }
    static func userData(_ data: ItemUserData) -> [String: Any] {
        var result: [String: Any] = ["PlaybackPositionTicks": data.playbackPositionTicks, "Played": data.played, "IsFavorite": data.isFavorite]
        if let value = data.playedPercentage { result["PlayedPercentage"] = value }
        if let value = data.unplayedItemCount { result["UnplayedItemCount"] = value }
        return result
    }

    /// Expected user data compares as the decoded model (absent fields take their defaults), as the Kotlin test does.
    public static func canonicalExpected(suite: String, operation: String, _ expected: Any) throws -> Any {
        suite == "catalog-return" && operation == "progress" ? userData(try decode(ItemUserData.self, expected)) : expected
    }

    public static func execute(suite: String, operation op: String, input i: [String: Any]) throws -> Any {
        switch (suite, op) {
        case ("catalog-return", "refresh"):
            return CatalogRules.returnRefresh(page: try decode(String.self, i["page"])).rawValue
        case ("catalog-return", "progress"):
            let previous = try decode(ItemUserData.self, i["previous"])
            return userData(CatalogRules.playbackReturnData(previous: previous, positionTicks: try decode(Int64.self, i["positionTicks"]), ended: try decode(Bool.self, i["ended"])))
        case ("search-presentation", "search-duplicate-ids"):
            return CatalogRules.searchDuplicateIds(try decode([MediaItem].self, i["items"])).sorted()
        case ("search-presentation", "search-library-name"):
            return CatalogRules.searchLibraryName(ancestors: try decode([MediaItem].self, i["ancestors"]), libraries: try decode([MediaItem].self, i["libraries"])) as Any? ?? NSNull()
        case ("application", "detail-play-item"):
            return Catalog.detailPlayItem(item: try decode(MediaItem.self, i["item"]), nextUp: try decode([MediaItem].self, i["nextUp"]), episodes: try decode([MediaItem].self, i["episodes"]))?.id as Any? ?? NSNull()
        case ("application", "catalog-announcement"):
            return CatalogRules.announcement(try decode(MediaItem.self, i["item"]))
        case ("application", "runtime-label"):
            return CatalogRules.runtimeLabel(try optional(Int64.self, i["ticks"]))
        case ("application", "catalog-placeholder-kind"):
            return CatalogRules.placeholderKind(try decode(MediaItem.self, i["item"])) as Any? ?? NSNull()
        case ("application", "search-duplicate-labels"):
            let items = try (i["items"] as? [[String: Any]] ?? []).map { raw in
                SearchLabelItem(id: try decode(String.self, raw["id"]), type: raw["type"] as? String, name: raw["name"] as? String, year: raw["year"] as? Int, seriesName: raw["seriesName"] as? String)
            }
            return CatalogRules.searchDuplicateLabels(items)
        case ("application", "media-badges"):
            return CatalogRules.mediaBadges(try optional(MediaBadgeSource.self, i["source"]))
        case ("application", "detail-play-label"):
            return CatalogRules.detailPlayLabel(item: try decode(MediaItem.self, i["item"]), playItem: try optional(MediaItem.self, i["playItem"]), finished: (i["finished"] as? Bool) ?? false)
        default:
            throw NSError(domain: "Unknown catalog presentation operation \(suite)/\(op)", code: 1)
        }
    }
}
