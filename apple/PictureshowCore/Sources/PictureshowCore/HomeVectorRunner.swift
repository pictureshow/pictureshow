// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Deterministic adapters for the shared Home contracts; expectations are never read by this runner.
public enum HomeVectorRunner {
    static func decode<T: Decodable>(_ type: T.Type, _ value: Any) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: .fragmentsAllowed))
    }
    static func object<T: Encodable>(_ value: T) throws -> Any { try JSONSerialization.jsonObject(with: JSONEncoder().encode(value), options: .fragmentsAllowed) }
    static func unique(_ values: [String]) -> [String] { var seen = Set<String>(); return values.filter { seen.insert($0).inserted } }
    static func rows(_ input: [String: Any], wire: Bool) throws -> [HomeRow] {
        try (input["rows"] as! [[String: Any]]).map { row in
            let items: [MediaItem] = try (row["items"] as! [[String: Any]]).map { raw in
                if wire { return try decode(MediaItem.self, raw) }
                var item = MediaItem(id: raw["id"] as! String); item.seriesId = raw["seriesId"] as? String; return item
            }
            return HomeRow(id: row["key"] as! String, title: row["title"] as! String, items: items)
        }
    }
    public static func execute(operation op: String, input i: [String: Any]) throws -> Any {
        func text(_ key: String, _ fallback: String = "") -> String { i[key] as? String ?? fallback }
        func bool(_ key: String) -> Bool { i[key] as? Bool ?? false }
        func strings(_ key: String) -> [String] { i[key] as? [String] ?? [] }
        func prefs() throws -> HomePreferences { try decode(HomePreferences.self, i["preferences"] ?? [:]) }
        switch op {
        case "home-rows", "home-rows-captioned":
            return try Catalog.customizedHome(rows: rows(i, wire: op == "home-rows-captioned"), preferences: prefs()).map { row -> [String: Any] in
                var result: [String: Any] = ["key": row.id, "ids": row.items.map(\.id)]
                if op == "home-rows-captioned" { result["title"] = row.title; result["captions"] = row.captions }
                return result
            }
        case "home-duplicate-ids":
            let rows = try rows(i, wire: true), ids = Catalog.homeDuplicateIds(rows: rows)
            return unique(rows.flatMap(\.items).map(\.id)).filter(ids.contains)
        case "home-card-subtitle": return try Catalog.homeCardSubtitle(item: decode(MediaItem.self, i["item"]!), caption: i["caption"] as? String, library: i["library"] as? String) as Any? ?? NSNull()
        case "detail-play-item": return try Catalog.detailPlayItem(item: decode(MediaItem.self, i["item"]!), nextUp: decode([MediaItem].self, i["nextUp"]!), episodes: decode([MediaItem].self, i["episodes"]!))?.id as Any? ?? NSNull()
        case "missing-item-label": return try Catalog.missingItemLabel(item: decode(MediaItem.self, i["item"]!), today: text("today")) as Any? ?? NSNull()
        case "home-preferences-document":
            let before = try decode([String: JSONValue].self, i["document"]!), p = try prefs(), after = try Catalog.homePreferencesDocument(before: before, preferences: p)
            var preserved = before.filter { $0.key != "CustomPrefs" }.allSatisfy { after[$0.key] == $0.value }
            if case .object(let custom) = before["CustomPrefs"], case .object(let updated) = after["CustomPrefs"] { preserved = preserved && custom.allSatisfy { updated[$0.key] == $0.value } }
            return ["preserved": preserved, "roundtrip": Catalog.readHomePreferences(document: after) == p]
        case "home-user-configuration":
            let libraries = try decode([MediaItem].self, i["libraries"]!), latest = try decode([String: [MediaItem]].self, i["latest"]!), config = i["configuration"] as? [String: Any] ?? [:]
            let excluded = config["LatestItemsExcludes"] as? [String] ?? []
            let filtered = latest.filter { !excluded.contains($0.key) }.mapValues { values in values.filter { !(config["HidePlayedInLatest"] as? Bool == true && $0.userData.played) } }
            return Catalog.assembleHome(views: libraries, resume: [], nextUp: [], latest: filtered).map { row -> [String: Any] in
                let hidden = config["MyMediaExcludes"] as? [String] ?? []
                return ["key": row.id, "ids": row.items.filter { !row.library || !hidden.contains($0.id) }.map(\.id)]
            }
        case "home-landing":
            guard bool("ready"), !bool("applied") else { return NSNull() }
            let choice = text("selection")
            return choice == "libraries" || (choice.hasPrefix("library:") && strings("libraryIds").contains(String(choice.dropFirst(8)))) ? choice : "home"
        case "home-editor-pin-label":
            let pin = try decode(HomePin.self, i["pin"]!), rows = try rows(i, wire: false)
            return rows.first { $0.id == pin.key && !$0.items.isEmpty }?.title ?? "Pinned \(pin.kind.lowercased())"
        case "editor-visible-rows": return try Catalog.editableHomeKeys(keys: strings("keys"), preferences: prefs())
        case "editor-move-row": return Catalog.moveHomeRow(order: strings("order"), visible: strings("visible"), key: text("key"), delta: i["delta"] as! Int)
        case "editor-move-sequence":
            var p = HomePreferences(mergeWatching: false); p.order = strings("order")
            for delta in i["deltas"] as! [Int] { p.order = Catalog.moveHomeRow(order: p.order, visible: Catalog.editableHomeKeys(keys: strings("visible"), preferences: p), key: text("key"), delta: delta) }
            return p.order
        case "editor-row-hint":
            var parts: [String] = []; if !bool("shown") { parts.append("Hidden") }; if bool("moving") { parts.append(bool("tv") ? "Moving · Up/Down · OK to drop" : "Moving · Release to drop") }; return parts.joined(separator: " · ").nilIfEmpty as Any? ?? NSNull()
        case "named-picker-choices":
            let page = i["page"] as! [[String: Any]], current = i["current"] as! [[String: Any]]
            return unique(((text("term").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && i["offset"] as? Int == 0 ? current : []) + page).map { $0["id"] as! String })
        case "named-picker-focus-target": return bool("editing") ? "search-field" : (!bool("hasChoices") && !bool("hasAny") ? "search" : (bool("hasAny") && (!bool("hasSelection") || !bool("hasChoices")) ? "any" : "choice"))
        case "restore-all-series": return Set(strings("hidden")).count > 1 || strings("hidden").contains { !strings("available").contains($0) }
        case "next-up-series-hint": return bool("shown") ? "Shown in Next up" : "Removed from Next up"
        case "home-pin-picker-support":
            let parts = [bool("pinned") ? "On your home" : nil, (i["count"] as? Int).map { titleCount($0, exact: true) }].compactMap { $0 }; return parts.joined(separator: " · ").nilIfEmpty as Any? ?? NSNull()
        case "reset-home-layout":
            var p = try prefs(); if bool("confirmed") { var reset = HomePreferences(); reset.libraries = p.libraries; p = reset }
            return try object(p)
        case "title-count-label": return titleCount(i["count"] as! Int, exact: i["exact"] as? Bool ?? true)
        case "resolution-summary": return resolutionSummary(text("resolution"))
        case "sort-direction-label": return bool("descending") ? "Descending" : "Ascending"
        case "home-editor-entry": return bool("preferencesReady")
        case "control-selection": return i["selected"] is Bool
        case "home-save-presentation": return bool("saving") ? (bool("tv") ? "progress" : "none") : (bool("retryable") && bool("hasMessage") ? "failure" : "none")
        case "home-retry-on-open": return bool("ready") && bool("retryable") && !bool("saving")
        case "library-filter-parameters": return filterParameters(i)
        case "library-filter-remove": return removing(i["filters"] as! [String: Any], key: text("key"))
        case "filter-constraint-label": return constraintLabel(i["filters"] as! [String: Any], key: text("key"))
        case "filter-presentation":
            let f = i["filters"] as! [String: Any], keys = constraintKeys(f), kind = ["movies": "movies", "tvshows": "shows", "music": "music"][text("collectionType")] ?? "titles"
            var result: [String: Any] = ["keys": keys, "active": !keys.isEmpty, "empty": "No \(kind) match these filters"]
            if let remove = i["remove"] as? String { result["remaining"] = constraintKeys(removing(f, key: remove)) }; return result
        case "home-pin-query":
            let kind = text("kind"), container = ["COLLECTION", "PLAYLIST"].contains(kind)
            var filters: [String: Any] = [:]
            if kind == "FAVORITES" { filters["favorite"] = true }; if kind == "GENRE" { filters["genre"] = text("value") }; if kind == "STUDIO" { filters["studio"] = text("value") }
            return ["parentId": container ? text("value") : "", "recursive": !container, "filters": filterParameters(filters)]
        case "library-browse-scope":
            if let type = ["movies": "Movie", "tvshows": "Series", "boxsets": "BoxSet"][text("collectionType")] { return ["recursive": true, "includeItemTypes": type] as [String: Any] }; return ["recursive": false]
        case "letter-jump":
            let letters = strings("sortNames").map(jumpLetter), all = ["#"] + (65...90).map { String(UnicodeScalar($0)!) }
            return ["index": letters.firstIndex(of: text("letter")) ?? -1, "available": all.filter(letters.contains)]
        case "collection-play-scope":
            if text("type") == "BoxSet" { return ["includeItemTypes": "Movie,Episode,Video", "recursive": true, "filtered": false] as [String: Any] }
            if ["CollectionFolder", "UserView"].contains(text("type")), text("collectionType") == "movies" { return ["includeItemTypes": "Movie", "recursive": true, "filtered": true] as [String: Any] }; return NSNull()
        case "queue-limit": return 300
        case "home-notice":
            var kind = "None", message: String?, downloads = false, retry = false
            if bool("unreachable"), bool("hasContent") { kind = "ShowingEarlier"; message = "Can't reach \(text("serverName").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "the server"). Showing what was loaded earlier."; downloads = bool("hasDownloads"); retry = true }
            else if bool("unreachable"), bool("hasDownloads") { kind = "DownloadsFirst"; message = "Could not reach the server. Your downloads still play."; downloads = true; retry = true }
            else if let error = i["error"] as? String { kind = "Error"; message = error; retry = true }
            return ["kind": kind, "text": message as Any? ?? NSNull(), "watchDownloads": downloads, "retry": retry]
        case "home-row-destination":
            let key = text("key"); return (key == "libraries" ? "libraries" : (key.hasPrefix("latest:") && key.count > 7 ? "library:" + key.dropFirst(7) : nil)) as Any? ?? NSNull()
        default: throw ClientError.invalidResponse
        }
    }
    static func titleCount(_ count: Int, exact: Bool) -> String { count == 1 && exact ? "1 title" : "\(count)\(exact ? "" : "+") titles" }
    static func resolutionSummary(_ value: String) -> String { ["ANY": "Any", "SD": "SD", "HD": "HD", "FULL_HD": "Full HD", "UHD": "4K and above"][value] ?? "Any" }
    static func jumpLetter(_ name: String) -> String {
        guard let first = name.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().first else { return "#" }
        let groups = ["A":"ÀÁÂÃÄÅĀĂĄ", "C":"ÇĆĈĊČ", "D":"ĎĐ", "E":"ÈÉÊËĒĔĖĘĚ", "G":"ĜĞĠĢ", "H":"ĤĦ", "I":"ÌÍÎÏĨĪĬĮİ", "J":"Ĵ", "K":"Ķ", "L":"ĹĻĽĿŁ", "N":"ÑŃŅŇ", "O":"ÒÓÔÕÖØŌŎŐ", "R":"ŔŖŘ", "S":"ŚŜŞŠ", "T":"ŢŤŦ", "U":"ÙÚÛÜŨŪŬŮŰŲ", "W":"Ŵ", "Y":"ÝŸŶ", "Z":"ŹŻŽ"]
        if let base = groups.first(where: { $0.value.contains(first) })?.key { return base }
        return first >= "A" && first <= "Z" ? String(first) : "#"
    }
    static func constraintKeys(_ f: [String: Any]) -> [String] {
        var keys: [String] = []
        func nonblank(_ key: String) -> Bool { !(f[key] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        if nonblank("genre") { keys.append("genre") }; if f["year"] is Int { keys.append("year") }; if nonblank("officialRating") { keys.append("rating") }
        if f["unwatched"] as? Bool == true { keys.append("unwatched") }; if f["favorite"] as? Bool == true { keys.append("favorite") }; if let r = f["resolution"] as? String, r != "ANY" { keys.append("resolution") }
        if nonblank("personId") { keys.append("person") }; if nonblank("studio") { keys.append("studio") }; return keys
    }
    static func removing(_ f: [String: Any], key: String) -> [String: Any] {
        var result = f
        let field = ["rating": "officialRating", "person": "personId"][key] ?? key
        result[field] = nil; if key == "person" { result["personName"] = nil }; return result
    }
    static func constraintLabel(_ f: [String: Any], key: String) -> String {
        switch key {
        case "genre": return f["genre"] as? String ?? "Genre"
        case "year": return (f["year"] as? Int).map(String.init) ?? "Year"
        case "rating": return (f["officialRating"] as? String).map { "Rated \($0)" } ?? "Rating"
        case "unwatched": return "Unwatched"
        case "favorite": return "Favorites"
        case "resolution": return resolutionSummary(f["resolution"] as? String ?? "ANY")
        case "person": return constraintKeys(f).contains("person") ? ((f["personName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty ?? "Selected person") : "Selected person"
        case "studio": return f["studio"] as? String ?? "Studio"
        default: return "Filter"
        }
    }
    static func filterParameters(_ f: [String: Any]) -> [String: String] {
        var result: [String: String] = [:]
        for (field, query) in [("genre", "genres"), ("officialRating", "officialRatings"), ("personId", "personIds"), ("studio", "studios")] {
            if let value = f[field] as? String, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { result[query] = value }
        }
        if let year = f["year"] as? Int { result["years"] = String(year) }; if f["unwatched"] as? Bool == true { result["isPlayed"] = "false" }; if f["favorite"] as? Bool == true { result["isFavorite"] = "true" }
        switch f["resolution"] as? String {
        case "SD": result["maxWidth"] = "719"
        case "HD": result["minWidth"] = "720"; result["maxWidth"] = "1919"
        case "FULL_HD": result["minWidth"] = "1920"; result["maxWidth"] = "3839"
        case "UHD": result["minWidth"] = "3840"
        default: break
        }; return result
    }
}
