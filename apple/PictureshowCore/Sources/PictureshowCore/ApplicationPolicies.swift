// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Lossless JSON values preserve server configuration fields the client does not yet understand.
public enum JSONValue: Codable, Equatable, Sendable {
    case null, bool(Bool), integer(Int64), number(Double), string(String), array([JSONValue]), object([String:JSONValue])
    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode(Int64.self) { self = .integer(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode([JSONValue].self) { self = .array(v) }
        else { self = .object(try c.decode([String:JSONValue].self)) }
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self { case .null: try c.encodeNil(); case .bool(let v): try c.encode(v); case .integer(let v): try c.encode(v); case .number(let v): try c.encode(v); case .string(let v): try c.encode(v); case .array(let v): try c.encode(v); case .object(let v): try c.encode(v) }
    }
    public var string: String? { if case .string(let v) = self { return v }; return nil }
}
public struct UserConfiguration: Codable, Equatable, Sendable {
    public var fields: [String:JSONValue]
    public init(fields: [String:JSONValue] = [:]) { self.fields = fields }
    public var audioLanguage: String { fields["AudioLanguagePreference"]?.string ?? "" }
    public var subtitleLanguage: String { fields["SubtitleLanguagePreference"]?.string ?? "" }
    public var subtitleMode: String { fields["SubtitleMode"]?.string ?? "Default" }
    public func withPreference(_ name: String, value: String) throws -> UserConfiguration {
        guard ["AudioLanguagePreference", "SubtitleLanguagePreference", "SubtitleMode"].contains(name) else { throw ClientError.invalidResponse }
        var copy = self; copy.fields[name] = .string(value); return copy
    }
}
public enum SubtitleSize: String, Codable, CaseIterable, Sendable { case system = "System", small = "Small", normal = "Normal", large = "Large", extraLarge = "ExtraLarge"
    public var scale: Double? { switch self { case .system: return nil; case .small: return 0.75; case .normal: return 1; case .large: return 1.25; case .extraLarge: return 1.5 } }
}
public enum SubtitleBackground: String, Codable, CaseIterable, Sendable { case system = "System", none = "None", shadow = "Shadow", box = "Box" }
public enum SegmentType: String, Codable, CaseIterable, Sendable { case unknown = "Unknown", intro = "Intro", outro = "Outro", recap = "Recap", preview = "Preview", commercial = "Commercial" }
public enum SegmentMode: String, Codable, CaseIterable, Sendable { case ask = "Ask", automatic = "Automatic", off = "Off" }
public struct PlaybackPreferences: Codable, Equatable, Sendable {
    public static let bitrates: [Int?] = [nil, 120, 80, 40, 20, 10, 4]
    public var bitrateMbps: Int?
    public var passthrough: Bool
    public var subtitleSize: SubtitleSize
    public var subtitleBackground: SubtitleBackground
    public var segmentModes: [SegmentType:SegmentMode]
    public var maxStreamingBitrate: Int { bitrateMbps.map { $0 * 1_000_000 } ?? 200_000_000 }
    public static func validateBitrate(_ value: Int?) throws { guard bitrates.contains(value) else { throw ClientError.invalidResponse } }
    public init(bitrateMbps: Int? = nil, passthrough: Bool = true, subtitleSize: SubtitleSize = .system, subtitleBackground: SubtitleBackground = .system, segmentModes: [SegmentType:SegmentMode] = [:]) {
        self.bitrateMbps = Self.bitrates.contains(bitrateMbps) ? bitrateMbps : nil
        self.passthrough = passthrough; self.subtitleSize = subtitleSize; self.subtitleBackground = subtitleBackground
        self.segmentModes = Dictionary(uniqueKeysWithValues: SegmentType.allCases.filter { $0 != .unknown }.map { ($0, segmentModes[$0] ?? .ask) })
    }
    enum CodingKeys: String, CodingKey { case bitrateMbps, passthrough, subtitleSize, subtitleBackground, segmentModes }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let rawModes = (try? c.decode([String:String].self, forKey: .segmentModes)) ?? [:]
        self.init(bitrateMbps: try? c.decodeIfPresent(Int.self, forKey: .bitrateMbps), passthrough: (try? c.decode(Bool.self, forKey: .passthrough)) ?? true, subtitleSize: SubtitleSize(rawValue: (try? c.decode(String.self, forKey: .subtitleSize)) ?? "") ?? .system, subtitleBackground: SubtitleBackground(rawValue: (try? c.decode(String.self, forKey: .subtitleBackground)) ?? "") ?? .system, segmentModes: Dictionary(uniqueKeysWithValues: rawModes.compactMap { k,v in guard let type = SegmentType(rawValue: k), type != .unknown else { return nil }; return (type, SegmentMode(rawValue:v) ?? .ask) }))
    }
    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(bitrateMbps, forKey: .bitrateMbps); try c.encode(passthrough, forKey: .passthrough); try c.encode(subtitleSize.rawValue, forKey: .subtitleSize); try c.encode(subtitleBackground.rawValue, forKey: .subtitleBackground); try c.encode(Dictionary(uniqueKeysWithValues: segmentModes.map { ($0.key.rawValue, $0.value.rawValue) }), forKey: .segmentModes)
    }
}
public enum Captions {
    private static let textCodecs: Set<String> = ["subrip", "srt", "ass", "ssa", "webvtt", "vtt", "ttml", "mov_text", "tx3g", "text", "smi", "sami"]
    public static func isText(_ stream: MediaStream) -> Bool {
        stream.isTextSubtitleStream || textCodecs.contains(stream.codec?.lowercased() ?? "")
    }
    public static func language(enabled: Bool, configuration: UserConfiguration?, deviceLanguage: String) -> String? {
        guard enabled, let configuration, configuration.subtitleMode.lowercased() != "none" else { return nil }
        return configuration.subtitleLanguage.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? deviceLanguage : configuration.subtitleLanguage
    }
    public static func usableDefaultIndex(source: MediaSource) -> Int? {
        guard let index = source.defaultSubtitleStreamIndex else { return nil }
        return index == -1 || (index >= 0 && source.mediaStreams.contains { $0.type == "Subtitle" && $0.index == index }) ? index : nil
    }
    public static func index(source: MediaSource, preferredLanguage: String) -> Int? {
        // A user-scoped server default may be a remembered choice from any client.
        // Keep Off distinct from missing, even when the system preference disagrees.
        if let index = usableDefaultIndex(source: source) { return index }
        guard let language = PlaybackSelection.language(preferredLanguage) else { return nil }
        return source.mediaStreams.filter { $0.type == "Subtitle" && PlaybackSelection.language($0.language) == language }.sorted {
            if $0.isForced != $1.isForced { return !$0.isForced }
            if $0.isHearingImpaired != $1.isHearingImpaired { return $0.isHearingImpaired }
            if isText($0) != isText($1) { return isText($0) }
            if $0.isDefault != $1.isDefault { return $0.isDefault }
            return $0.index < $1.index
        }.first?.index
    }
}
public enum PlaybackEndPosition {
    public static func bounded(_ positionMs: Int64, durationMs: Int64) -> Int64 { durationMs > 0 ? min(max(0, positionMs), durationMs) : max(0, positionMs) }
    public static func completed(_ positionMs: Int64, durationMs: Int64) -> Int64 {
        let position = bounded(positionMs, durationMs: durationMs)
        return durationMs > 0 && durationMs <= 86_400_000 && durationMs - position <= 1_000 ? durationMs : position
    }
}
public struct Chapter: Codable, Equatable, Sendable, Identifiable {
    public var startTicks: Int64 = 0; public var name: String = ""; public var imageTag: String?
    public var id: Int64 { startTicks }; public var startMs: Int64 { max(0,startTicks) / 10_000 }
    enum CodingKeys: String, CodingKey { case startTicks = "StartPositionTicks", name = "Name", imageTag = "ImageTag" }
    public init(from decoder: Decoder) throws { let c = try decoder.container(keyedBy: CodingKeys.self); startTicks = try c.decodeIfPresent(Int64.self, forKey: .startTicks) ?? 0; name = try c.decodeIfPresent(String.self, forKey: .name) ?? ""; imageTag = try c.decodeIfPresent(String.self, forKey: .imageTag) }
}
public struct MediaSegment: Codable, Equatable, Sendable, Identifiable {
    public var id: String; public var type: SegmentType; public var startTicks: Int64; public var endTicks: Int64
    public var startMs: Int64 { max(0,startTicks) / 10_000 }; public var endMs: Int64 { max(0,endTicks) / 10_000 }
    public var isPlausible: Bool { let maxLength: Int64 = [.intro,.recap,.preview].contains(type) ? 600_000 : 1_800_000; return type != .unknown && startTicks >= 0 && endMs > startMs && endMs <= 86_400_000 && endMs - startMs <= maxLength }
    enum CodingKeys: String, CodingKey { case id = "Id", type = "Type", startTicks = "StartTicks", endTicks = "EndTicks" }
    public init(from decoder: Decoder) throws { let c = try decoder.container(keyedBy: CodingKeys.self); id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""; type = SegmentType(rawValue: (try? c.decode(String.self, forKey: .type)) ?? "") ?? .unknown; startTicks = try c.decodeIfPresent(Int64.self, forKey: .startTicks) ?? 0; endTicks = try c.decodeIfPresent(Int64.self, forKey: .endTicks) ?? 0 }
}
public enum PlaybackExtras {
    public static func activeSegment(_ segments: [MediaSegment], positionMs: Int64, modes: [SegmentType:SegmentMode] = [:]) -> MediaSegment? { segments.filter { $0.isPlausible && positionMs >= $0.startMs && positionMs < $0.endMs && modes[$0.type] != .off }.min { $0.startTicks < $1.startTicks } }
    public static func creditsStart(durationMs: Int64, segments: [MediaSegment]) -> Int64? { segments.filter { $0.type == .outro && $0.isPlausible && $0.startMs < durationMs }.map(\.startMs).min() }
    public static func nextTrigger(durationMs: Int64, segments: [MediaSegment]) -> Int64? { guard durationMs > 0 else { return nil }; return creditsStart(durationMs: durationMs, segments: segments) ?? max(0,durationMs - 10_000) }
    public static func adjacentEpisode(current: MediaItem, items: [MediaItem]) -> MediaItem? { guard current.type == "Episode", let series = current.seriesId, !series.isEmpty, let index = items.firstIndex(where: { $0.id == current.id }), index + 1 < items.count else { return nil }; let next = items[index+1]; return next.id != current.id && next.type == "Episode" ? next : nil }
}
public struct PersonCredit: Codable, Equatable, Sendable, Identifiable { public var id: String; public var name: String; public var type: String?; public var role: String?; enum CodingKeys: String, CodingKey { case id = "Id", name = "Name", type = "Type", role = "Role" } }
public struct PublicUser: Codable, Sendable, Identifiable { public var id: String; public var name: String; enum CodingKeys: String, CodingKey { case id = "Id", name = "Name" } }
public enum SearchKind: String, CaseIterable, Sendable { case movies = "Movie", shows = "Series", episodes = "Episode", collections = "BoxSet", people = "Person"; public var title: String { switch self { case .movies:return "Movies";case .shows:return "Shows";case .episodes:return "Episodes";case .collections:return "Collections";case .people:return "People" } } }
public struct TrickplayInfo: Codable, Equatable, Sendable {
    public var width: Int; public var height: Int; public var tileWidth: Int; public var tileHeight: Int; public var thumbnailCount: Int; public var interval: Int
    enum CodingKeys: String, CodingKey { case width = "Width", height = "Height", tileWidth = "TileWidth", tileHeight = "TileHeight", thumbnailCount = "ThumbnailCount", interval = "Interval" }
}
public struct TrickplayTile: Equatable, Sendable { public var info: TrickplayInfo; public var sheet: Int; public var column: Int; public var row: Int }
extension PlaybackExtras {
    public static func trickplayTile(_ info: TrickplayInfo, positionMs: Int64) -> TrickplayTile? {
        guard (1...4096).contains(info.width), (1...4096).contains(info.height), (1...100).contains(info.tileWidth), (1...100).contains(info.tileHeight), info.interval > 0, info.thumbnailCount > 0 else { return nil }
        let frame = Int(min(max(0,positionMs) / Int64(info.interval),Int64(info.thumbnailCount - 1))); let perSheet = info.tileWidth * info.tileHeight
        return TrickplayTile(info: info, sheet: frame / perSheet, column: frame % perSheet % info.tileWidth, row: frame % perSheet / info.tileWidth)
    }
    public static func chooseTrickplay(item: MediaItem, mediaSourceID: String?, preferredWidth: Int) -> TrickplayInfo? {
        let widths = mediaSourceID.flatMap { item.trickplay[$0] } ?? (mediaSourceID == nil && item.trickplay.count == 1 ? item.trickplay.values.first : nil)
        let valid = (widths ?? [:]).values.filter { trickplayTile($0,positionMs:0) != nil }.sorted { $0.width < $1.width }
        return valid.first { $0.width >= preferredWidth } ?? valid.last
    }
}
