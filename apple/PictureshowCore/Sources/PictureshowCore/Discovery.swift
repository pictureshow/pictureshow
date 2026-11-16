// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// A trailer the server knows by address only (usually YouTube), from the item's RemoteTrailers.
public struct RemoteTrailer: Codable, Equatable, Sendable {
    public var url: String?
    public var name: String?
    public init(url: String? = nil, name: String? = nil) { self.url = url; self.name = name }
    enum CodingKeys: String, CodingKey { case url = "Url", name = "Name" }
}

/// A server rating name and the age it stands for (`/Localization/ParentalRatings`).
public struct ParentalRating: Codable, Equatable, Sendable {
    public var name: String
    public var value: Int?
    enum CodingKeys: String, CodingKey { case name = "Name", value = "Value" }
    public init(name: String, value: Int?) { self.name = name; self.value = value }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        value = try c.decodeIfPresent(Int.self, forKey: .value)
    }
}

/// One entry in a title's Trailers row (CAT-30). A local trailer is a library video the player plays; a remote one is an
/// address another app opens. `youTubeID` is set when the address is a YouTube video, so the platform can open its app.
public struct TrailerChoice: Equatable, Sendable, Identifiable {
    public let key: String
    public let title: String
    public let item: MediaItem?
    public let url: String?
    public let youTubeID: String?
    public var id: String { key }
}

/// Which discovery reads a detail page makes for an item type (CAT-30..32).
public struct DetailDiscoveryReads: Equatable, Sendable {
    public let trailers: Bool
    public let extras: Bool
    public let similar: Bool
    public var any: Bool { trailers || extras || similar }
}

/// PLAY-30: when the player asks "Are you still watching?" instead of starting the next episode by itself.
public enum StillWatching: String, Codable, CaseIterable, Sendable {
    case off = "Off", twoEpisodes = "TwoEpisodes", threeEpisodes = "ThreeEpisodes", fiveEpisodes = "FiveEpisodes", eightEpisodes = "EightEpisodes"
    public var episodes: Int { switch self { case .off: 0; case .twoEpisodes: 2; case .threeEpisodes: 3; case .fiveEpisodes: 5; case .eightEpisodes: 8 } }
    public var minutes: Int { switch self { case .off: 0; case .twoEpisodes: 60; case .threeEpisodes: 90; case .fiveEpisodes: 150; case .eightEpisodes: 240 } }
}

/// Detail-page discovery, the photo viewer and the screensaver: the rules every platform shares (spec/vectors/discovery.json).
public enum Discovery {
    public static func detailReads(type: String?) -> DetailDiscoveryReads {
        switch type {
        case "Movie", "Series": DetailDiscoveryReads(trailers: true, extras: true, similar: true)
        case "Episode": DetailDiscoveryReads(trailers: false, extras: true, similar: false)
        default: DetailDiscoveryReads(trailers: false, extras: false, similar: false)
        }
    }

    /// An http(s) address with a host, or nil.
    private static func webURL(_ text: String) -> URLComponents? {
        guard let c = URLComponents(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = c.scheme?.lowercased(), ["http", "https"].contains(scheme),
              let host = c.host, !host.isEmpty else { return nil }
        return c
    }

    /// The 11-character id of a YouTube video address, or nil. Watch, short-link, embed, shorts, v and live forms over
    /// http(s); playlists, channels and other hosts are not videos.
    public static func youTubeVideoID(_ url: String) -> String? {
        guard let c = webURL(url), var host = c.host?.lowercased() else { return nil }
        if host.hasPrefix("www.") { host.removeFirst(4) }
        if host.hasPrefix("m.") { host.removeFirst(2) }
        let segments = c.path.split(separator: "/").map(String.init)
        let id: String?
        switch host {
        case "youtu.be": id = segments.first
        case "youtube.com", "youtube-nocookie.com", "music.youtube.com":
            switch segments.first {
            case "watch": id = c.queryItems?.first { $0.name == "v" }?.value
            case "embed", "shorts", "v", "live": id = segments.count > 1 ? segments[1] : nil
            default: id = nil
            }
        default: id = nil
        }
        guard let id, id.range(of: "^[A-Za-z0-9_-]{11}$", options: .regularExpression) != nil else { return nil }
        return id
    }

    /// CAT-30: local trailers first in the server's order, then remote ones with an http(s) address. A remote trailer
    /// repeating an earlier address (or the same YouTube video in another form) is dropped. Untitled entries are numbered
    /// "Trailer", "Trailer 2", … across the row.
    public static func trailerChoices(local: [MediaItem], remote: [RemoteTrailer]) -> [TrailerChoice] {
        var choices: [TrailerChoice] = []
        var untitled = 0
        func fallback() -> String { untitled += 1; return untitled == 1 ? "Trailer" : "Trailer \(untitled)" }
        var localIDs = Set<String>()
        for item in local where !item.isMissing && localIDs.insert(item.id).inserted {
            let title = item.name.trimmingCharacters(in: .whitespaces).isEmpty || item.name == "Untitled" ? fallback() : item.name
            choices.append(TrailerChoice(key: "local:\(item.id)", title: title, item: item, url: nil, youTubeID: nil))
        }
        var seen = Set<String>()
        for trailer in remote {
            let url = (trailer.url ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !url.isEmpty, webURL(url) != nil else { continue }
            let youTube = youTubeVideoID(url)
            guard seen.insert(youTube.map { "youtube:\($0)" } ?? url).inserted else { continue }
            let name = trailer.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            choices.append(TrailerChoice(key: "remote:\(youTube ?? url)", title: name.isEmpty ? fallback() : name, item: nil, url: url, youTubeID: youTube))
        }
        return choices
    }

    private static let extraOrder = ["BehindTheScenes", "Featurette", "Interview", "DeletedScene", "Scene", "Short", "Clip", "Unknown"]

    /// What the Extras row says under a special feature (CAT-31); nil for types that are not extras to watch.
    public static func extraLabel(_ extraType: String?) -> String? {
        switch extraType ?? "Unknown" {
        case "BehindTheScenes": "Behind the scenes"
        case "Featurette": "Featurette"
        case "Interview": "Interview"
        case "DeletedScene": "Deleted scene"
        case "Scene": "Scene"
        case "Short": "Short"
        case "Clip": "Clip"
        case "Unknown": "Extra"
        default: nil
        }
    }

    /// CAT-31: the Extras row. Theme songs/videos, samples and trailers are left out, as are items with no file; the rest
    /// is grouped by kind, keeping the server's order inside each kind.
    public static func specialFeatureExtras(_ items: [MediaItem]) -> [MediaItem] {
        var seen = Set<String>()
        let kept = items.filter { !$0.isMissing && seen.insert($0.id).inserted && extraOrder.contains($0.extraType ?? "Unknown") }
        return kept.enumerated().sorted {
            let a = extraOrder.firstIndex(of: $0.element.extraType ?? "Unknown")!, b = extraOrder.firstIndex(of: $1.element.extraType ?? "Unknown")!
            return a != b ? a < b : $0.offset < $1.offset
        }.map(\.element)
    }

    /// CAT-32: drops the title itself, missing items, repeats and other copies of the same title (same name and year),
    /// then keeps at most `limit`.
    public static func moreLikeThis(of item: MediaItem, similar: [MediaItem], limit: Int = 12) -> [MediaItem] {
        func key(_ other: MediaItem) -> String { other.name.lowercased() + "|" + (other.productionYear.map(String.init) ?? "") }
        var ids = Set<String>(), titles = Set<String>()
        var result: [MediaItem] = []
        for other in similar where result.count < max(0, limit) {
            guard other.id != item.id, !other.isMissing, key(other) != key(item) else { continue }
            guard ids.insert(other.id).inserted, titles.insert(key(other)).inserted else { continue }
            result.append(other)
        }
        return result
    }

    /// PLAY-30: `episodesInARow` counts the episode ending now plus each that started by itself since the viewer's last
    /// input; `minutesSinceInput` is the time since that input.
    public static func stillWatchingDue(policy: StillWatching, episodesInARow: Int, minutesSinceInput: Int64) -> Bool {
        policy != .off && (episodesInARow >= policy.episodes || minutesSinceInput >= Int64(policy.minutes))
    }

    /// PHOTO-01: a photo at a display size; the server scales the original down, never up, and keeps its aspect.
    public static func photoImageURL(server: URL, item: MediaItem, maxWidth: Int, maxHeight: Int) -> URL? {
        guard let tag = item.imageTags["Primary"], !tag.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        var base = server.absoluteString
        while base.hasSuffix("/") { base.removeLast() }
        guard var c = URLComponents(string: base + "/Items/" + ServerURLs.encode(item.id) + "/Images/Primary") else { return nil }
        c.queryItems = [URLQueryItem(name: "tag", value: tag), URLQueryItem(name: "maxWidth", value: String(max(1, maxWidth))),
                        URLQueryItem(name: "maxHeight", value: String(max(1, maxHeight))), URLQueryItem(name: "quality", value: "90")]
        c.percentEncodedQuery = c.queryItems?.map { "\(ServerURLs.encode($0.name))=\(ServerURLs.encode($0.value ?? ""))" }.joined(separator: "&")
        return c.url
    }

    /// PHOTO-02: manual moves stop at the ends; a running slideshow wraps from the last photo to the first.
    public static func photoStep(index: Int, count: Int, forward: Bool, slideshow: Bool) -> Int? {
        guard count > 0 else { return nil }
        let current = min(max(index, 0), count - 1)
        let next = forward ? current + 1 : current - 1
        if (0..<count).contains(next) { return next }
        if slideshow && forward && count > 1 { return 0 }
        return nil
    }

    /// PHOTO-02: slideshow speeds, in seconds a photo stays.
    public static let slideshowSeconds = [3, 5, 10, 20]

    /// SAVER-01: titles with their own backdrop, nothing missing, no repeats and, with a ceiling, only titles whose rating
    /// is known to be at or under it.
    public static func screensaverArt(_ items: [MediaItem], maxAge: Int?, ratingAge: (String) -> Int?) -> [MediaItem] {
        var seen = Set<String>()
        return items.filter { item in
            guard item.backdropImageTags.contains(where: { !$0.trimmingCharacters(in: .whitespaces).isEmpty }), !item.isMissing else { return false }
            if let maxAge {
                guard let age = item.officialRating.flatMap(ratingAge), age <= maxAge else { return false }
            }
            return seen.insert(item.id).inserted
        }
    }

    /// SAVER-01 delay choices in minutes (0 is Off) and rating ceilings (nil is Any).
    public static let screensaverMinutes = [0, 2, 5, 10, 15, 30]
    public static let screensaverAges = [7, 10, 13, 16]
}
