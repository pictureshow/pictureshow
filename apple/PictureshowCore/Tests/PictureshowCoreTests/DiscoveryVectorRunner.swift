// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
@testable import PictureshowCore

/// Executes spec/vectors/discovery.json (CAT-30..32, PLAY-30, PHOTO-01/02, SAVER-01) against the Swift core.
enum DiscoveryVectorRunner {
    static let operations: Set<String> = ["detail-reads", "youtube-id", "trailers", "extra-label", "extras", "more-like-this", "still-watching", "photo-step", "screensaver-art", "photo-url"]

    private static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T {
        guard let value else { throw NSError(domain: "Missing discovery vector input", code: 1) }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }
    private static func number(_ i: [String: Any], _ key: String) throws -> Int {
        guard let n = i[key] as? NSNumber else { throw NSError(domain: "Missing discovery vector number \(key)", code: 1) }
        return n.intValue
    }
    private static func flag(_ i: [String: Any], _ key: String) throws -> Bool {
        guard let b = i[key] as? Bool else { throw NSError(domain: "Missing discovery vector flag \(key)", code: 1) }
        return b
    }

    static func execute(operation: String, input i: [String: Any]) throws -> Any {
        switch operation {
        case "detail-reads":
            let reads = Discovery.detailReads(type: i["type"] as? String)
            return ["trailers": reads.trailers, "extras": reads.extras, "similar": reads.similar]
        case "youtube-id":
            return Discovery.youTubeVideoID(try decode(String.self, i["url"])) as Any? ?? NSNull()
        case "trailers":
            return Discovery.trailerChoices(local: try decode([MediaItem].self, i["local"]), remote: try decode([RemoteTrailer].self, i["remote"])).map {
                ["key": $0.key, "title": $0.title, "itemId": $0.item?.id as Any? ?? NSNull(), "url": $0.url as Any? ?? NSNull(), "youTubeId": $0.youTubeID as Any? ?? NSNull()] as [String: Any]
            }
        case "extra-label":
            return Discovery.extraLabel(i["extraType"] as? String) as Any? ?? NSNull()
        case "extras":
            return Discovery.specialFeatureExtras(try decode([MediaItem].self, i["items"])).map(\.id)
        case "more-like-this":
            return Discovery.moreLikeThis(of: try decode(MediaItem.self, i["self"]), similar: try decode([MediaItem].self, i["similar"]), limit: try number(i, "limit")).map(\.id)
        case "still-watching":
            guard let policy = StillWatching(rawValue: try decode(String.self, i["policy"])) else { throw NSError(domain: "Unknown still-watching policy", code: 1) }
            return Discovery.stillWatchingDue(policy: policy, episodesInARow: try number(i, "episodesInARow"), minutesSinceInput: Int64(try number(i, "minutesSinceInput")))
        case "photo-step":
            return Discovery.photoStep(index: try number(i, "index"), count: try number(i, "count"), forward: try flag(i, "forward"), slideshow: try flag(i, "slideshow")) as Any? ?? NSNull()
        case "screensaver-art":
            let ratings = try decode([String: Int].self, i["ratings"])
            let maxAge = (i["maxAge"] as? NSNumber)?.intValue
            return Discovery.screensaverArt(try decode([MediaItem].self, i["items"]), maxAge: maxAge) { ratings[$0] }.map(\.id)
        case "photo-url":
            guard let server = URL(string: try decode(String.self, i["server"])) else { throw NSError(domain: "Bad server", code: 1) }
            return Discovery.photoImageURL(server: server, item: try decode(MediaItem.self, i["item"]), maxWidth: try number(i, "maxWidth"), maxHeight: try number(i, "maxHeight"))?.absoluteString as Any? ?? NSNull()
        default:
            throw NSError(domain: "Unknown discovery operation \(operation)", code: 1)
        }
    }
}
