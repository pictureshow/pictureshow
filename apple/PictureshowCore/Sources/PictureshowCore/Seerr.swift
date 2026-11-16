// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/*
 * Seerr (Jellyseerr/Overseerr, one project since v3) request integration: models and the shared rules every platform
 * translates (spec/features/seerr.md, spec/vectors/seerr.json). Shapes are the ones Seerr v3.5.2 answers.
 * Translated from the Android reference (core/seerr/SeerrModels.kt).
 */

/// Seerr permission bits (Seerr's server/lib/permissions.ts).
public enum SeerrPermission {
    public static let admin: Int64 = 2
    public static let request: Int64 = 32
    public static let requestMovie: Int64 = 262_144
    public static let requestTV: Int64 = 524_288
}

/// Movie requests need ADMIN, REQUEST or REQUEST_MOVIE; series need ADMIN, REQUEST or REQUEST_TV (4K is not offered).
public func seerrCanRequest(_ permissions: Int64, type: String) -> Bool {
    let specific: Int64
    switch type {
    case "movie": specific = SeerrPermission.requestMovie
    case "tv": specific = SeerrPermission.requestTV
    default: return false
    }
    return permissions & (SeerrPermission.admin | SeerrPermission.request | specific) != 0
}

public struct SeerrUser: Codable, Equatable, Sendable {
    public var id: Int
    public var displayName: String?
    public var username: String?
    public var jellyfinUsername: String?
    public var jellyfinUserId: String?
    public var permissions: Int64

    public init(id: Int, displayName: String? = nil, username: String? = nil, jellyfinUsername: String? = nil, jellyfinUserId: String? = nil, permissions: Int64 = 0) {
        self.id = id; self.displayName = displayName; self.username = username
        self.jellyfinUsername = jellyfinUsername; self.jellyfinUserId = jellyfinUserId; self.permissions = permissions
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        displayName = try c.decodeIfPresent(String.self, forKey: .displayName)
        username = try c.decodeIfPresent(String.self, forKey: .username)
        jellyfinUsername = try c.decodeIfPresent(String.self, forKey: .jellyfinUsername)
        jellyfinUserId = try c.decodeIfPresent(String.self, forKey: .jellyfinUserId)
        permissions = try c.decodeIfPresent(Int64.self, forKey: .permissions) ?? 0
    }

    public var name: String {
        if let displayName, !displayName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return displayName }
        return jellyfinUsername ?? username ?? String(id)
    }

    public func canRequest(_ type: String) -> Bool { seerrCanRequest(permissions, type: type) }
}

public struct SeerrSeasonStatus: Codable, Equatable, Sendable {
    public var seasonNumber: Int
    public var status: Int

    public init(seasonNumber: Int, status: Int = 1) { self.seasonNumber = seasonNumber; self.status = status }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seasonNumber = try c.decode(Int.self, forKey: .seasonNumber)
        status = try c.decodeIfPresent(Int.self, forKey: .status) ?? 1
    }
}

/// A request as embedded in a title's mediaInfo: only what decides which seasons can still be requested.
public struct SeerrMediaRequest: Codable, Equatable, Sendable {
    public var id: Int
    public var status: Int
    public var is4k: Bool
    public var seasons: [SeerrSeasonStatus]

    public init(id: Int = 0, status: Int = 1, is4k: Bool = false, seasons: [SeerrSeasonStatus] = []) {
        self.id = id; self.status = status; self.is4k = is4k; self.seasons = seasons
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(Int.self, forKey: .id) ?? 0
        status = try c.decodeIfPresent(Int.self, forKey: .status) ?? 1
        is4k = try c.decodeIfPresent(Bool.self, forKey: .is4k) ?? false
        seasons = try c.decodeIfPresent([SeerrSeasonStatus].self, forKey: .seasons) ?? []
    }
}

public struct SeerrMedia: Codable, Equatable, Sendable {
    public var tmdbId: Int
    public var mediaType: String
    public var status: Int
    public var seasons: [SeerrSeasonStatus]
    public var requests: [SeerrMediaRequest]

    public init(tmdbId: Int = 0, mediaType: String = "movie", status: Int = 1, seasons: [SeerrSeasonStatus] = [], requests: [SeerrMediaRequest] = []) {
        self.tmdbId = tmdbId; self.mediaType = mediaType; self.status = status; self.seasons = seasons; self.requests = requests
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        tmdbId = try c.decodeIfPresent(Int.self, forKey: .tmdbId) ?? 0
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType) ?? "movie"
        status = try c.decodeIfPresent(Int.self, forKey: .status) ?? 1
        seasons = try c.decodeIfPresent([SeerrSeasonStatus].self, forKey: .seasons) ?? []
        requests = try c.decodeIfPresent([SeerrMediaRequest].self, forKey: .requests) ?? []
    }
}

public struct SeerrSeason: Codable, Equatable, Sendable {
    public var seasonNumber: Int
    public var name: String
    public var episodeCount: Int

    public init(seasonNumber: Int, name: String = "", episodeCount: Int = 0) {
        self.seasonNumber = seasonNumber; self.name = name; self.episodeCount = episodeCount
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        seasonNumber = try c.decode(Int.self, forKey: .seasonNumber)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""
        episodeCount = try c.decodeIfPresent(Int.self, forKey: .episodeCount) ?? 0
    }
}

public struct SeerrTitle: Codable, Equatable, Sendable, Identifiable {
    public var id: Int
    public var mediaType: String
    public var title: String?
    public var name: String?
    public var posterPath: String?
    public var releaseDate: String?
    public var firstAirDate: String?
    public var overview: String?
    public var mediaInfo: SeerrMedia?
    public var seasons: [SeerrSeason]

    public init(id: Int, mediaType: String = "", title: String? = nil, name: String? = nil, posterPath: String? = nil, releaseDate: String? = nil,
                firstAirDate: String? = nil, overview: String? = nil, mediaInfo: SeerrMedia? = nil, seasons: [SeerrSeason] = []) {
        self.id = id; self.mediaType = mediaType; self.title = title; self.name = name; self.posterPath = posterPath
        self.releaseDate = releaseDate; self.firstAirDate = firstAirDate; self.overview = overview; self.mediaInfo = mediaInfo; self.seasons = seasons
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        mediaType = try c.decodeIfPresent(String.self, forKey: .mediaType) ?? ""
        title = try c.decodeIfPresent(String.self, forKey: .title)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        posterPath = try c.decodeIfPresent(String.self, forKey: .posterPath)
        releaseDate = try c.decodeIfPresent(String.self, forKey: .releaseDate)
        firstAirDate = try c.decodeIfPresent(String.self, forKey: .firstAirDate)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        mediaInfo = try c.decodeIfPresent(SeerrMedia.self, forKey: .mediaInfo)
        seasons = try c.decodeIfPresent([SeerrSeason].self, forKey: .seasons) ?? []
    }

    public var label: String { title ?? name ?? String(id) }
    public var year: String { String((releaseDate ?? firstAirDate ?? "").prefix(4)) }
    public var poster: URL? { seerrPosterURL(posterPath) }
    public var status: SeerrStatus { seerrMediaStatus(mediaInfo?.status ?? 1) }
}

public struct SeerrPageInfo: Codable, Equatable, Sendable {
    public var results: Int
    public var pages: Int
    public init(results: Int = 0, pages: Int = 1) { self.results = results; self.pages = pages }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        results = try c.decodeIfPresent(Int.self, forKey: .results) ?? 0
        pages = try c.decodeIfPresent(Int.self, forKey: .pages) ?? 1
    }
}

public struct SeerrPage: Codable, Equatable, Sendable {
    public var results: [SeerrTitle]
    public var totalPages: Int
    public var page: Int
    public init(results: [SeerrTitle] = [], totalPages: Int = 1, page: Int = 1) { self.results = results; self.totalPages = totalPages; self.page = page }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        results = try c.decodeIfPresent([SeerrTitle].self, forKey: .results) ?? []
        totalPages = try c.decodeIfPresent(Int.self, forKey: .totalPages) ?? 1
        page = try c.decodeIfPresent(Int.self, forKey: .page) ?? 1
    }
}

public struct SeerrUsers: Codable, Equatable, Sendable {
    public var results: [SeerrUser]
    public var pageInfo: SeerrPageInfo
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        results = try c.decodeIfPresent([SeerrUser].self, forKey: .results) ?? []
        pageInfo = try c.decodeIfPresent(SeerrPageInfo.self, forKey: .pageInfo) ?? SeerrPageInfo()
    }
}

public struct SeerrRequest: Codable, Equatable, Sendable, Identifiable {
    public var id: Int
    public var status: Int
    public var type: String
    public var is4k: Bool
    public var media: SeerrMedia
    public var seasons: [SeerrSeasonStatus]

    public init(id: Int, status: Int, type: String, is4k: Bool = false, media: SeerrMedia, seasons: [SeerrSeasonStatus] = []) {
        self.id = id; self.status = status; self.type = type; self.is4k = is4k; self.media = media; self.seasons = seasons
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(Int.self, forKey: .id)
        status = try c.decode(Int.self, forKey: .status)
        type = try c.decode(String.self, forKey: .type)
        is4k = try c.decodeIfPresent(Bool.self, forKey: .is4k) ?? false
        media = try c.decode(SeerrMedia.self, forKey: .media)
        seasons = try c.decodeIfPresent([SeerrSeasonStatus].self, forKey: .seasons) ?? []
    }
}

public struct SeerrRequests: Codable, Equatable, Sendable {
    public var results: [SeerrRequest]
    public var pageInfo: SeerrPageInfo
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        results = try c.decodeIfPresent([SeerrRequest].self, forKey: .results) ?? []
        pageInfo = try c.decodeIfPresent(SeerrPageInfo.self, forKey: .pageInfo) ?? SeerrPageInfo()
    }
}

public enum SeerrMode: String, Codable, Sendable {
    case jellyfin = "JELLYFIN"
    case apiKey = "API_KEY"
}

/// The non-secret half of a connection. In API-key mode `user` is always the viewer's own linked Seerr user.
public struct SeerrConfig: Codable, Equatable, Sendable {
    public var url: String
    public var mode: SeerrMode
    public var user: SeerrUser
    public init(url: String, mode: SeerrMode, user: SeerrUser) { self.url = url; self.mode = mode; self.user = user }
}

/// The secret half. Never printed: description, debug description and reflection are redacted.
public struct SeerrSecret: Codable, Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible, CustomReflectable {
    public var apiKey: String
    public var cookie: String
    public var username: String
    public var password: String

    public init(apiKey: String = "", cookie: String = "", username: String = "", password: String = "") {
        self.apiKey = apiKey; self.cookie = cookie; self.username = username; self.password = password
    }

    public var description: String { "SeerrSecret([redacted])" }
    public var debugDescription: String { description }
    public var customMirror: Mirror { Mirror(self, children: [], displayStyle: .struct) }
}

/// The address a viewer typed, as the base every API path is appended to; nil when it is refused. No scheme means
/// https. Credentials, queries, fragments, other schemes and spaces are refused; a default port, a trailing slash and a
/// trailing "/api/v1" (pasted from Seerr's docs) are removed.
public func normalizeSeerrURL(_ value: String) -> String? {
    let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty, !trimmed.unicodeScalars.contains(where: { CharacterSet.whitespacesAndNewlines.contains($0) }) else { return nil }
    let raw = trimmed.contains("://") ? trimmed : "https://" + trimmed
    guard !raw.contains("?"), !raw.contains("#"), let parts = URLComponents(string: raw), let scheme = parts.scheme?.lowercased(),
          scheme == "http" || scheme == "https",
          raw.components(separatedBy: "://")[0].lowercased() == scheme,
          parts.user == nil, parts.password == nil, parts.percentEncodedQuery == nil, parts.percentEncodedFragment == nil,
          var host = parts.percentEncodedHost?.lowercased(), !host.isEmpty else { return nil }
    if host.contains(":") && !host.hasPrefix("[") { host = "[" + host + "]" }
    var path = parts.percentEncodedPath
    while path.hasSuffix("/") { path.removeLast() }
    if path.hasSuffix("/api/v1") { path.removeLast("/api/v1".count) }
    while path.hasSuffix("/") { path.removeLast() }
    let defaultPort = scheme == "https" ? 443 : 80
    let port = parts.port.map { $0 > 0 && $0 != defaultPort ? ":\($0)" : "" } ?? ""
    return "\(scheme)://\(host)\(port)\(path)"
}

/// RFC 3986 unreserved bytes only. Seerr's OpenAPI validator rejects raw reserved characters such as !'()* (400).
public func encodeSeerrQuery(_ value: String) -> String {
    let hex = Array("0123456789ABCDEF")
    var out = ""
    for byte in value.utf8 {
        switch byte {
        case UInt8(ascii: "A")...UInt8(ascii: "Z"), UInt8(ascii: "a")...UInt8(ascii: "z"), UInt8(ascii: "0")...UInt8(ascii: "9"),
             UInt8(ascii: "-"), UInt8(ascii: "."), UInt8(ascii: "_"), UInt8(ascii: "~"):
            out.unicodeScalars.append(Unicode.Scalar(byte))
        default:
            out.append("%"); out.append(hex[Int(byte >> 4)]); out.append(hex[Int(byte & 15)])
        }
    }
    return out
}

/// Posters come straight from TMDB's public image CDN with no credentials: the Seerr session or key and the Jellyfin
/// token never go there. Only a TMDB file path ("/abc.jpg") is accepted, so a hostile response can't point elsewhere.
public func seerrPosterURL(_ path: String?, width: Int = 342) -> URL? {
    guard let path, path.count > 1, path.hasPrefix("/"), !path.contains(".."),
          path.dropFirst().unicodeScalars.allSatisfy({ $0.isASCII && (CharacterSet.alphanumerics.contains($0) || "._-".unicodeScalars.contains($0)) })
    else { return nil }
    return URL(string: "https://image.tmdb.org/t/p/w\(width)\(path)")
}

/// Search words as the library search normalizes them (Android `normalizeSearch`): trimmed, at most 200 characters.
public func seerrNormalizeSearch(_ query: String) -> String {
    String(query.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)).replacingOccurrences(of: "\\s+$", with: "", options: .regularExpression)
}

public enum SeerrStatus: String, Sendable {
    case unknown = "UNKNOWN", pending = "PENDING", processing = "PROCESSING", partial = "PARTIAL", available = "AVAILABLE"
    case blocked = "BLOCKED", deleted = "DELETED", approved = "APPROVED", declined = "DECLINED", failed = "FAILED", completed = "COMPLETED"
}

/// MediaStatus: UNKNOWN=1, PENDING, PROCESSING, PARTIALLY_AVAILABLE, AVAILABLE, BLOCKLISTED, DELETED=7.
public func seerrMediaStatus(_ value: Int) -> SeerrStatus {
    switch value {
    case 2: return .pending
    case 3: return .processing
    case 4: return .partial
    case 5: return .available
    case 6: return .blocked
    case 7: return .deleted
    default: return .unknown
    }
}

/// MediaRequestStatus: PENDING=1, APPROVED, DECLINED, FAILED, COMPLETED=5.
public func seerrRequestStatus(_ value: Int) -> SeerrStatus {
    switch value {
    case 1: return .pending
    case 2: return .approved
    case 3: return .declined
    case 4: return .failed
    case 5: return .completed
    default: return .unknown
    }
}

/// What a request's row says. A request the server finished, or whose title is now in the library, reads Available;
/// one still waiting for an admin reads Pending approval; approved but not yet in the library reads Approved.
public func seerrRequestDisplay(_ request: SeerrRequest) -> SeerrStatus {
    let media = seerrMediaStatus(request.media.status)
    if media == .available || request.status == 5 { return .available }
    if media == .partial && request.status == 2 { return .partial }
    if media == .processing && request.status == 2 { return .processing }
    return seerrRequestStatus(request.status)
}

private func seerrTitleKey(_ name: String, _ year: String) -> String { seerrNormalizeSearch(name).lowercased() + "|" + year }

/// The "Not in your library" offers for a search: movies and series only (TMDB multi-search also returns people),
/// never one Seerr already reports available or one the library search itself found (same title and year, compared
/// the way library search normalizes text), each once, in Seerr's order.
public func seerrSearchOffers(_ titles: [SeerrTitle], library: [(name: String, year: Int?)] = []) -> [SeerrTitle] {
    let owned = Set(library.map { seerrTitleKey($0.name, $0.year.map(String.init) ?? "") })
    var seen = Set<String>()
    return titles.filter { title in
        guard ["movie", "tv"].contains(title.mediaType), title.mediaInfo?.status != 5, !owned.contains(seerrTitleKey(title.label, title.year)) else { return false }
        return seen.insert("\(title.mediaType)-\(title.id)").inserted
    }
}

/// Each unavailable season and why: AVAILABLE, PARTIAL, PROCESSING, PENDING or APPROVED (requested) or BLOCKED.
public func seerrTakenSeasons(_ title: SeerrTitle) -> [Int: SeerrStatus] {
    guard let media = title.mediaInfo else { return [:] }
    var taken: [Int: SeerrStatus] = [:]
    for season in media.seasons where season.status != 1 && season.status != 7 { taken[season.seasonNumber] = seerrMediaStatus(season.status) }
    for request in media.requests where !request.is4k && request.status != 3 && request.status != 5 {
        for season in request.seasons where taken[season.seasonNumber] == nil {
            taken[season.seasonNumber] = request.status == 2 ? .approved : .pending
        }
    }
    return taken
}

/// Seasons the viewer can still request, the same rule the server applies: specials and empty seasons are never
/// offered; a season is taken when a non-4K request that is neither declined nor completed names it, or when the
/// media's own season status is anything but unknown or deleted.
public func seerrSelectableSeasons(_ title: SeerrTitle) -> [Int] {
    let taken = seerrTakenSeasons(title)
    return Array(Set(title.seasons.filter { $0.seasonNumber > 0 && $0.episodeCount > 0 && taken[$0.seasonNumber] == nil }.map(\.seasonNumber))).sorted()
}

/// A movie can be requested when Seerr knows nothing of it, or it was deleted; anything else is already on its way.
public func seerrMovieRequestable(_ title: SeerrTitle) -> Bool {
    let status = title.mediaInfo?.status ?? 1
    return status == 1 || status == 7
}

public enum SeerrError: String, Error, Sendable {
    case unreachable = "UNREACHABLE", password = "PASSWORD", apiKey = "API_KEY", expired = "EXPIRED", permission = "PERMISSION"
    case quota = "QUOTA", alreadyRequested = "ALREADY_REQUESTED", blocked = "BLOCKED", notLinked = "NOT_LINKED", server = "SERVER"
    case address = "ADDRESS", storage = "STORAGE"
}

/// A failed answer in the viewer's terms. Seerr v3.5.2: wrong Jellyfin password 401 INVALID_CREDENTIALS; no session
/// 401 (validator) or a stale one 403; no permission 403 "You do not have permission..."; quota 403 "... Quota
/// exceeded."; blocklisted 403 "This media is blocklisted."; duplicate 409; nothing left to request 202 (a success
/// status the client treats as a failure). `apiKey` marks the API-key sign-in, where 401/403 mean the key is wrong.
public func seerrError(status: Int, body: String, login: Bool = false, apiKey: Bool = false) -> SeerrError {
    let text = body.lowercased()
    if (300...399).contains(status) { return .address }
    if status == 404 && (login || apiKey) { return .address }
    if text.contains("quota") { return .quota }
    if text.contains("blocklist") { return .blocked }
    if status == 409 || status == 202 || text.contains("already exists") || text.contains("no seasons available") { return .alreadyRequested }
    if login && (status == 401 || status == 403) { return .password }
    if apiKey && (status == 401 || status == 403) { return .apiKey }
    if status == 401 { return .expired }
    if status == 403 { return .permission }
    return .server
}

/// The authentication headers for one call. Jellyfin mode sends the session cookie. API-key mode always names the
/// viewer's own linked Seerr user with X-API-User, and refuses to call without one (no id means the key alone would act
/// as Seerr's admin, user 1); only the user lookup during connect (`lookup`) may send the key alone.
public func seerrAuthHeaders(mode: SeerrMode, secret: SeerrSecret, userId: Int, lookup: Bool = false) throws -> [(String, String)] {
    switch mode {
    case .jellyfin: return secret.cookie.isEmpty ? [] : [("Cookie", secret.cookie)]
    case .apiKey:
        if lookup { return [("X-Api-Key", secret.apiKey)] }
        guard userId > 0 else { throw SeerrError.notLinked }
        return [("X-Api-Key", secret.apiKey), ("X-API-User", String(userId))]
    }
}

/// The viewer's own Seerr user: the one imported from this Jellyfin user. Never a fallback to anyone else.
public func seerrLinkedUser(_ users: [SeerrUser], jellyfinUserId: String) -> SeerrUser? {
    func normalized(_ value: String) -> String { value.replacingOccurrences(of: "-", with: "").lowercased() }
    let id = normalized(jellyfinUserId)
    guard !id.isEmpty else { return nil }
    return users.first { $0.jellyfinUserId.map(normalized) == id }
}

/// The session cookie from a sign-in's Set-Cookie headers, as "connect.sid=value", or nil.
public func seerrSessionCookie(_ setCookie: [String]) -> String? {
    let prefix = "connect.sid="
    return setCookie.map { ($0.components(separatedBy: ";").first ?? "").trimmingCharacters(in: .whitespacesAndNewlines) }
        .first { $0.hasPrefix(prefix) && $0.count > prefix.count }
}
