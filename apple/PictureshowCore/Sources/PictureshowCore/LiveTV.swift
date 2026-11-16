// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

// Live TV and DVR models (spec/features/live-tv.md), translated from the Android reference
// core/src/commonMain/kotlin/app/pictureshow/core/livetv/LiveModels.kt. Times are epoch milliseconds as Int64.

public struct LivePage<T: Decodable>: Decodable {
    public var items: [T]
    public var totalRecordCount: Int
    public var startIndex: Int
    public init(items: [T] = [], totalRecordCount: Int = 0, startIndex: Int = 0) {
        self.items = items; self.totalRecordCount = totalRecordCount; self.startIndex = startIndex
    }
    enum CodingKeys: String, CodingKey { case items = "Items", totalRecordCount = "TotalRecordCount", startIndex = "StartIndex" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        items = try c.decodeIfPresent([T].self, forKey: .items) ?? []
        totalRecordCount = try c.decodeIfPresent(Int.self, forKey: .totalRecordCount) ?? 0
        startIndex = try c.decodeIfPresent(Int.self, forKey: .startIndex) ?? 0
    }
}
extension LivePage: Sendable where T: Sendable {}
extension LivePage: Equatable where T: Equatable {}

public struct LiveProgram: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var channelId: String
    public var channelName: String?
    public var startDate: String?
    public var endDate: String?
    public var overview: String?
    public var isSeries: Bool
    public var isRepeat: Bool?
    public var timerId: String?
    public var seriesTimerId: String?
    public var episodeTitle: String?
    public var episodeNumber: Int?
    public var seasonNumber: Int?
    public var imageTags: [String: String]
    public var backdropImageTags: [String]
    public init(id: String, name: String = "Untitled program", channelId: String = "", channelName: String? = nil, startDate: String? = nil,
                endDate: String? = nil, overview: String? = nil, isSeries: Bool = false, isRepeat: Bool? = nil, timerId: String? = nil,
                seriesTimerId: String? = nil, episodeTitle: String? = nil, episodeNumber: Int? = nil, seasonNumber: Int? = nil,
                imageTags: [String: String] = [:], backdropImageTags: [String] = []) {
        self.id = id; self.name = name; self.channelId = channelId; self.channelName = channelName; self.startDate = startDate
        self.endDate = endDate; self.overview = overview; self.isSeries = isSeries; self.isRepeat = isRepeat; self.timerId = timerId
        self.seriesTimerId = seriesTimerId; self.episodeTitle = episodeTitle; self.episodeNumber = episodeNumber
        self.seasonNumber = seasonNumber; self.imageTags = imageTags; self.backdropImageTags = backdropImageTags
    }
    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", channelId = "ChannelId", channelName = "ChannelName", startDate = "StartDate", endDate = "EndDate"
        case overview = "Overview", isSeries = "IsSeries", isRepeat = "IsRepeat", timerId = "TimerId", seriesTimerId = "SeriesTimerId"
        case episodeTitle = "EpisodeTitle", episodeNumber = "IndexNumber", seasonNumber = "ParentIndexNumber", imageTags = "ImageTags"
        case backdropImageTags = "BackdropImageTags"
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Untitled program"
        channelId = try c.decodeIfPresent(String.self, forKey: .channelId) ?? ""
        channelName = try c.decodeIfPresent(String.self, forKey: .channelName)
        startDate = try c.decodeIfPresent(String.self, forKey: .startDate)
        endDate = try c.decodeIfPresent(String.self, forKey: .endDate)
        overview = try c.decodeIfPresent(String.self, forKey: .overview)
        isSeries = try c.decodeIfPresent(Bool.self, forKey: .isSeries) ?? false
        isRepeat = try c.decodeIfPresent(Bool.self, forKey: .isRepeat)
        timerId = try c.decodeIfPresent(String.self, forKey: .timerId)
        seriesTimerId = try c.decodeIfPresent(String.self, forKey: .seriesTimerId)
        episodeTitle = try c.decodeIfPresent(String.self, forKey: .episodeTitle)
        episodeNumber = try c.decodeIfPresent(Int.self, forKey: .episodeNumber)
        seasonNumber = try c.decodeIfPresent(Int.self, forKey: .seasonNumber)
        imageTags = try c.decodeIfPresent([String: String].self, forKey: .imageTags) ?? [:]
        backdropImageTags = try c.decodeIfPresent([String].self, forKey: .backdropImageTags) ?? []
    }
    /// The guide's recording dot: a nonblank one-off TimerId only. A series rule alone does not establish that this airing records.
    public var hasRecordingTimer: Bool { !(timerId ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

extension ItemUserData: Hashable {
    public func hash(into hasher: inout Hasher) { hasher.combine(playbackPositionTicks); hasher.combine(played); hasher.combine(isFavorite) }
}

public struct LiveChannel: Codable, Equatable, Hashable, Sendable, Identifiable {
    public var id: String
    public var name: String
    public var number: String?
    public var imageTags: [String: String]
    public var userData: ItemUserData
    public var currentProgram: LiveProgram?
    public init(id: String, name: String = "Unnamed channel", number: String? = nil, imageTags: [String: String] = [:],
                userData: ItemUserData = ItemUserData(), currentProgram: LiveProgram? = nil) {
        self.id = id; self.name = name; self.number = number; self.imageTags = imageTags; self.userData = userData; self.currentProgram = currentProgram
    }
    enum CodingKeys: String, CodingKey {
        case id = "Id", name = "Name", number = "ChannelNumber", imageTags = "ImageTags", userData = "UserData", currentProgram = "CurrentProgram"
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? "Unnamed channel"
        number = try c.decodeIfPresent(String.self, forKey: .number)
        imageTags = try c.decodeIfPresent([String: String].self, forKey: .imageTags) ?? [:]
        userData = try c.decodeIfPresent(ItemUserData.self, forKey: .userData) ?? ItemUserData()
        currentProgram = try c.decodeIfPresent(LiveProgram.self, forKey: .currentProgram)
    }
}

/// A missing policy flag does not establish permission. The server remains authoritative after every call.
public struct LivePolicy: Equatable, Sendable {
    public var access: Bool
    public var manage: Bool
    public var deleteContent: Bool
    public var userData: Bool
    public init(access: Bool = false, manage: Bool = false, deleteContent: Bool = false, userData: Bool = false) {
        self.access = access; self.manage = manage; self.deleteContent = deleteContent; self.userData = userData
    }
}

public struct LiveInfo: Decodable, Sendable, Equatable {
    public var enabled: Bool
    public var services: [[String: JSONValue]]
    public init(enabled: Bool = false, services: [[String: JSONValue]] = []) { self.enabled = enabled; self.services = services }
    enum CodingKeys: String, CodingKey { case enabled = "IsEnabled", services = "Services" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        services = try c.decodeIfPresent([[String: JSONValue]].self, forKey: .services) ?? []
    }
}

public struct LiveGuideInfo: Decodable, Sendable, Equatable {
    public var startDate: String?
    public var endDate: String?
    public init(startDate: String? = nil, endDate: String? = nil) { self.startDate = startDate; self.endDate = endDate }
    enum CodingKeys: String, CodingKey { case startDate = "StartDate", endDate = "EndDate" }
}

extension JSONValue {
    /// The JSON primitive's text, as kotlinx `JsonPrimitive.content` reads it; nil for null, arrays and objects.
    var primitiveContent: String? {
        switch self {
        case .string(let v): return v
        case .bool(let v): return v ? "true" : "false"
        case .integer(let v): return String(v)
        case .number(let v): return String(v)
        default: return nil
        }
    }
    /// A JSON boolean or its exact text ("true"/"false"), as kotlinx `booleanOrNull` accepts.
    var booleanValue: Bool? {
        if case .bool(let v) = self { return v }
        if case .string(let v) = self { return v == "true" ? true : v == "false" ? false : nil }
        return nil
    }
    /// The value as Foundation JSON (for request bodies and vector comparison).
    public var foundation: Any {
        switch self {
        case .null: return NSNull()
        case .bool(let v): return v
        case .integer(let v): return NSNumber(value: v)
        case .number(let v): return NSNumber(value: v)
        case .string(let v): return v
        case .array(let v): return v.map(\.foundation)
        case .object(let v): return v.mapValues(\.foundation)
        }
    }
}

/// A timer or series rule kept intact, including provider-specific fields the editor cannot understand.
public struct LiveSchedule: Equatable, Sendable, CustomStringConvertible {
    public var fields: [String: JSONValue]
    public var dirtyFields: Set<String>
    public init(fields: [String: JSONValue], dirtyFields: Set<String> = []) { self.fields = fields; self.dirtyFields = dirtyFields }
    public var id: String { string("Id") ?? "" }
    public var name: String { string("Name") ?? "Recording" }
    public var channelId: String { string("ChannelId") ?? "" }
    public var channelName: String? { string("ChannelName") }
    public var programId: String { string("ProgramId") ?? "" }
    public var startDate: String? { string("StartDate") }
    public var endDate: String? { string("EndDate") }
    public var status: String { string("Status") ?? "Unknown" }
    public var errorMessage: String? { string("ErrorMessage") ?? string("Error") }
    public var seriesTimerId: String? { string("SeriesTimerId") }
    public func string(_ key: String) -> String? { fields[key]?.primitiveContent }
    public func boolean(_ key: String) -> Bool? { fields[key]?.booleanValue }
    public func int(_ key: String) -> Int? { fields[key]?.primitiveContent.flatMap { Int($0) } }
    public var description: String { "LiveSchedule(id=\(id), status=\(status), fields=\(fields.keys.sorted()))" }
}

/// `outcome` is ok, unknown, denied or failed. "unknown" means a write may have reached the server: reconcile it, never
/// silently resubmit.
public struct LiveWrite: Equatable, Sendable {
    public var outcome: String
    public var schedule: LiveSchedule?
    public var message: String?
    public init(_ outcome: String, schedule: LiveSchedule? = nil, message: String? = nil) {
        self.outcome = outcome; self.schedule = schedule; self.message = message
    }
}

/// Semantic presentation follows server facts, independently of localized wording.
public enum RecordingTone: String, Sendable { case neutral = "Neutral", live = "Live", error = "Error", muted = "Muted" }

public struct LiveTVError: Error, Equatable, Sendable, LocalizedError {
    public enum Kind: String, Sendable { case network, invalidCredentials, server, invalidResponse }
    public var kind: Kind
    public var message: String
    public var status: Int?
    public init(kind: Kind, message: String, status: Int? = nil) { self.kind = kind; self.message = message; self.status = status }
    public var errorDescription: String? { message }
}
