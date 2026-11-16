// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct HomePin: Codable, Equatable, Sendable {
    public var kind: String
    public var value: String
    public var key: String { "pin:\(kind):\(value)" }
    public init(kind: String, value: String = "") { self.kind = kind; self.value = value }
    enum CodingKeys: String, CodingKey { case kind, value }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decode(String.self, forKey: .kind); value = try c.decodeIfPresent(String.self, forKey: .value) ?? ""
    }
}
public struct LibraryDisplay: Codable, Equatable, Sendable {
    public var sortBy = "SortName"
    public var descending = false
    public var list = false
    enum CodingKeys: String, CodingKey { case sortBy, descending, list }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sortBy = try c.decodeIfPresent(String.self, forKey: .sortBy) ?? "SortName"
        descending = try c.decodeIfPresent(Bool.self, forKey: .descending) ?? false
        list = try c.decodeIfPresent(Bool.self, forKey: .list) ?? false
    }
}
public struct HomePreferences: Codable, Equatable, Sendable {
    public var order: [String] = []
    public var hidden: [String] = []
    public var pins: [HomePin] = []
    public var mergeWatching = true
    public var hiddenSeries: [String] = []
    public var landing = "home"
    public var libraries: [String: LibraryDisplay] = [:]
    public init(mergeWatching: Bool = true) { self.mergeWatching = mergeWatching }
    enum CodingKeys: String, CodingKey { case order, hidden, pins, mergeWatching, hiddenSeries, landing, libraries }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        order = try c.decodeIfPresent([String].self, forKey: .order) ?? []
        hidden = try c.decodeIfPresent([String].self, forKey: .hidden) ?? []
        pins = try c.decodeIfPresent([HomePin].self, forKey: .pins) ?? []
        mergeWatching = try c.decodeIfPresent(Bool.self, forKey: .mergeWatching) ?? true
        hiddenSeries = try c.decodeIfPresent([String].self, forKey: .hiddenSeries) ?? []
        landing = try c.decodeIfPresent(String.self, forKey: .landing) ?? "home"
        libraries = try c.decodeIfPresent([String: LibraryDisplay].self, forKey: .libraries) ?? [:]
    }
}

extension Catalog {
    public static func customizedHome(rows: [HomeRow], preferences p: HomePreferences) -> [HomeRow] {
        let resume = rows.first { $0.id == "resume" }?.items ?? []
        let next = (rows.first { $0.id == "nextup" }?.items ?? []).filter { !p.hiddenSeries.contains($0.seriesId ?? "") }
        var prepared: [HomeRow]
        if p.mergeWatching {
            let series = Set(resume.compactMap(\.seriesId)), ids = Set(resume.map(\.id))
            let candidates = next.filter { !ids.contains($0.id) && !($0.seriesId.map(series.contains) ?? false) }
            let upcoming = candidates.filter { !$0.isResumable }
            var seen = Set<String>()
            let combined = (resume + candidates.filter(\.isResumable) + upcoming).filter { seen.insert($0.id).inserted }
            var captions: [String: String] = [:]
            for item in upcoming { captions[item.id] = item.type == "Episode" ? "Next · \(item.episodeLabel)" : "Next" }
            prepared = [HomeRow(id: "watching", title: "Continue watching", items: combined, wide: true, captions: captions)] + rows.filter { !["resume", "nextup"].contains($0.id) }
        } else {
            prepared = rows.map { $0.id == "nextup" ? HomeRow(id: $0.id, title: $0.title, items: next, library: $0.library, wide: $0.wide, captions: $0.captions, libraryNames: $0.libraryNames) : $0 }
        }
        return prepared.enumerated().filter { !p.hidden.contains($0.element.id) && !$0.element.items.isEmpty }.sorted {
            let a = p.order.firstIndex(of: $0.element.id) ?? Int.max, b = p.order.firstIndex(of: $1.element.id) ?? Int.max
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
    }
    public static func homeDuplicateIds(rows: [HomeRow]) -> Set<String> {
        func key(_ value: String?) -> String { (value ?? "").split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").lowercased() }
        var seen = Set<String>()
        let items = rows.filter { !$0.library }.flatMap(\.items).filter { seen.insert($0.id).inserted }
        let episodes = Dictionary(grouping: items.filter { $0.type == "Episode" && $0.seriesId != nil && !key($0.seriesName).isEmpty }, by: { key($0.seriesName) })
        let titles = Dictionary(grouping: items.filter { $0.type != "Episode" && !key($0.name).isEmpty }, by: { "\($0.type)|\(key($0.name))|\($0.productionYear.map(String.init) ?? "")" })
        return Set(episodes.values.filter { Set($0.compactMap(\.seriesId)).count > 1 }.flatMap { $0 }.map(\.id) + titles.values.filter { $0.count > 1 }.flatMap { $0 }.map(\.id))
    }
    public static func homeCardSubtitle(item: MediaItem, caption: String?, library: String?) -> String? {
        let parts = item.type == "Episode" ? [item.seriesName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty, library, caption ?? item.episodeLabel] : [caption, library]
        return parts.compactMap { $0 }.joined(separator: " · ").nilIfEmpty
    }
    public static func detailPlayItem(item: MediaItem?, nextUp: [MediaItem], episodes: [MediaItem]) -> MediaItem? {
        let available = episodes.filter { !$0.isMissing }
        switch item?.type {
        case "Series": return nextUp.first { !$0.isMissing } ?? available.first { !$0.userData.played } ?? available.first
        case "Season": return available.first { !$0.userData.played } ?? available.first
        case "Movie", "Episode", "Video", "MusicVideo": return item.flatMap { $0.isMissing ? nil : $0 }
        default: return nil
        }
    }
    public static func missingItemLabel(item: MediaItem, today: String) -> String? {
        guard item.isMissing else { return nil }
        let date = String((item.premiereDate ?? "").prefix(10)), parts = date.split(separator: "-")
        guard date.range(of: #"^\d{4}-\d{2}-\d{2}$"#, options: .regularExpression) != nil, parts.count == 3,
              let month = Int(parts[1]), (1...12).contains(month), let day = Int(parts[2]), (1...31).contains(day) else { return "Not in your library" }
        let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        return "Not in your library · \(date > today ? "Airs" : "Aired") \(months[month - 1]) \(day), \(parts[0])"
    }
    public static func homePreferencesDocument(before: [String: JSONValue], preferences: HomePreferences) throws -> [String: JSONValue] {
        var result = before, custom: [String: JSONValue] = [:]
        if case .object(let value) = before["CustomPrefs"] { custom = value }
        custom["pictureshow.home.v1"] = .string(String(decoding: try JSONEncoder().encode(preferences), as: UTF8.self))
        result["CustomPrefs"] = .object(custom); result["Client"] = .string("pictureshow")
        return result
    }
    public static func readHomePreferences(document: [String: JSONValue]) -> HomePreferences? {
        guard case .object(let custom) = document["CustomPrefs"], let raw = custom["pictureshow.home.v1"]?.string else { return nil }
        return try? JSONDecoder().decode(HomePreferences.self, from: Data(raw.utf8))
    }
    public static func editableHomeKeys(keys: [String], preferences p: HomePreferences) -> [String] {
        let inactive = p.mergeWatching ? ["resume", "nextup"] : ["watching"]
        var seen = Set<String>()
        return keys.filter { !inactive.contains($0) && seen.insert($0).inserted }.enumerated().sorted {
            let a = p.order.firstIndex(of: $0.element) ?? Int.max, b = p.order.firstIndex(of: $1.element) ?? Int.max
            return a == b ? $0.offset < $1.offset : a < b
        }.map(\.element)
    }
    public static func moveHomeRow(order: [String], visible: [String], key: String, delta: Int) -> [String] {
        var seen = Set<String>(), active = visible.filter { seen.insert($0).inserted }
        guard let from = active.firstIndex(of: key), !active.isEmpty else { return order }
        let to = max(0, min(active.count - 1, from + delta))
        guard from != to else { return order }
        active.insert(active.remove(at: from), at: to)
        seen = []; var index = 0
        return (order + visible).filter { seen.insert($0).inserted }.map { value in
            guard visible.contains(value) else { return value }; defer { index += 1 }; return active[index]
        }
    }
}
