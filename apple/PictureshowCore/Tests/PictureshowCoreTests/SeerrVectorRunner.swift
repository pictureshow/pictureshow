// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
@testable import PictureshowCore

/// Executes spec/vectors/seerr.json cases against the Seerr rules (spec/features/seerr.md); results are JSON values
/// shaped like each case's `expected`.
enum SeerrVectorRunner {
    static let operations: Set<String> = ["url", "query", "poster", "media-status", "request-status", "request-display", "can-request",
                                          "movie-requestable", "seasons", "offers", "error", "headers", "link", "session-cookie"]

    struct Malformed: Error, CustomStringConvertible { let description: String }

    private static func decode<T: Decodable>(_ type: T.Type, _ value: Any?) throws -> T {
        guard let value else { throw Malformed(description: "missing input") }
        return try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }

    private static func string(_ input: [String: Any], _ key: String) throws -> String {
        guard let value = input[key] as? String else { throw Malformed(description: "missing string \(key)") }
        return value
    }

    private static func int(_ input: [String: Any], _ key: String) throws -> Int {
        guard let value = input[key] as? NSNumber, CFGetTypeID(value) != CFBooleanGetTypeID() else { throw Malformed(description: "missing integer \(key)") }
        return value.intValue
    }

    private static func flag(_ input: [String: Any], _ key: String) -> Bool { input[key] as? Bool ?? false }
    private static func nullable(_ value: String?) -> Any { value ?? NSNull() }

    static func execute(operation: String, input: [String: Any]) throws -> Any {
        switch operation {
        case "url": return nullable(normalizeSeerrURL(try string(input, "value")))
        case "query": return encodeSeerrQuery(try string(input, "value"))
        case "poster": return nullable(seerrPosterURL(input["path"] as? String)?.absoluteString)
        case "media-status": return seerrMediaStatus(try int(input, "value")).rawValue
        case "request-status": return seerrRequestStatus(try int(input, "value")).rawValue
        case "request-display":
            let request = SeerrRequest(id: 1, status: try int(input, "status"), type: "movie", media: SeerrMedia(status: try int(input, "mediaStatus")))
            return seerrRequestDisplay(request).rawValue
        case "can-request":
            guard let permissions = input["permissions"] as? NSNumber else { throw Malformed(description: "missing permissions") }
            return seerrCanRequest(permissions.int64Value, type: try string(input, "type"))
        case "movie-requestable":
            let status = (input["mediaStatus"] as? NSNumber)?.intValue
            return seerrMovieRequestable(SeerrTitle(id: 1, mediaType: "movie", mediaInfo: status.map { SeerrMedia(status: $0) }))
        case "seasons":
            let title = try decode(SeerrTitle.self, input["title"])
            let taken = seerrTakenSeasons(title).reduce(into: [String: String]()) { $0[String($1.key)] = $1.value.rawValue }
            return ["selectable": seerrSelectableSeasons(title), "taken": taken] as [String: Any]
        case "offers":
            let titles = try decode([SeerrTitle].self, input["titles"])
            guard let library = input["library"] as? [[String: Any]] else { throw Malformed(description: "missing library") }
            let owned = try library.map { (name: try string($0, "name"), year: ($0["year"] as? NSNumber)?.intValue) }
            return seerrSearchOffers(titles, library: owned).map { "\($0.mediaType)-\($0.id)" }
        case "error":
            return seerrError(status: try int(input, "status"), body: try string(input, "body"), login: flag(input, "login"), apiKey: flag(input, "apiKey")).rawValue
        case "headers":
            guard let mode = SeerrMode(rawValue: try string(input, "mode")) else { throw Malformed(description: "unknown mode") }
            let secret = SeerrSecret(apiKey: input["apiKey"] as? String ?? "", cookie: input["cookie"] as? String ?? "")
            do {
                return try seerrAuthHeaders(mode: mode, secret: secret, userId: try int(input, "userId"), lookup: flag(input, "lookup")).map { [$0.0, $0.1] }
            } catch let error as SeerrError {
                return error.rawValue
            }
        case "link":
            let users = try decode([SeerrUser].self, input["users"])
            return seerrLinkedUser(users, jellyfinUserId: try string(input, "jellyfinUserId")).map { $0.id as Any } ?? NSNull()
        case "session-cookie":
            guard let cookies = input["setCookie"] as? [String] else { throw Malformed(description: "missing setCookie") }
            return nullable(seerrSessionCookie(cookies))
        default:
            throw Malformed(description: "unknown seerr operation \(operation)")
        }
    }
}
