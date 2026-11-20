// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// What a catalog page re-reads when the viewer comes back to it (catalog-return).
public enum CatalogReturnRefresh: String, Sendable { case none = "None", homeRows = "HomeRows", titleAndChildren = "TitleAndChildren", loadedCards = "LoadedCards", searchResults = "SearchResults" }

/// One search card's visible text, for `CatalogRules.searchDuplicateLabels` (CAT-14a).
public struct SearchLabelItem: Equatable, Sendable {
    public var id: String
    public var type: String?
    public var name: String?
    public var year: Int?
    public var seriesName: String?
    public init(id: String, type: String? = nil, name: String? = nil, year: Int? = nil, seriesName: String? = nil) {
        self.id = id; self.type = type; self.name = name; self.year = year; self.seriesName = seriesName
    }
    public init(_ item: MediaItem) { self.init(id: item.id, type: item.type, name: item.name, year: item.productionYear, seriesName: item.seriesName) }
}

/// The stream fields the detail format badges read. `MediaStream` doesn't carry VideoRangeType, ChannelLayout, Profile
/// or AudioSpatialFormat yet, so the badges decode their own view of a media source.
public struct MediaBadgeStream: Decodable, Equatable, Sendable {
    public var index: Int
    public var type: String?
    public var isExternal: Bool
    public var width: Int?
    public var height: Int?
    public var videoRangeType: String?
    public var channels: Int?
    public var channelLayout: String?
    public var profile: String?
    public var audioSpatialFormat: String?
    enum CodingKeys: String, CodingKey { case index = "Index", type = "Type", isExternal = "IsExternal", width = "Width", height = "Height", videoRangeType = "VideoRangeType", channels = "Channels", channelLayout = "ChannelLayout", profile = "Profile", audioSpatialFormat = "AudioSpatialFormat" }
    public init(index: Int, type: String?, isExternal: Bool = false, width: Int? = nil, height: Int? = nil, videoRangeType: String? = nil, channels: Int? = nil, channelLayout: String? = nil, profile: String? = nil, audioSpatialFormat: String? = nil) {
        self.index = index; self.type = type; self.isExternal = isExternal; self.width = width; self.height = height; self.videoRangeType = videoRangeType
        self.channels = channels; self.channelLayout = channelLayout; self.profile = profile; self.audioSpatialFormat = audioSpatialFormat
    }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decodeIfPresent(Int.self, forKey: .index) ?? 0
        type = try c.decodeIfPresent(String.self, forKey: .type)
        isExternal = try c.decodeIfPresent(Bool.self, forKey: .isExternal) ?? false
        width = try c.decodeIfPresent(Int.self, forKey: .width)
        height = try c.decodeIfPresent(Int.self, forKey: .height)
        videoRangeType = try c.decodeIfPresent(String.self, forKey: .videoRangeType)
        channels = try c.decodeIfPresent(Int.self, forKey: .channels)
        channelLayout = try c.decodeIfPresent(String.self, forKey: .channelLayout)
        profile = try c.decodeIfPresent(String.self, forKey: .profile)
        audioSpatialFormat = try c.decodeIfPresent(String.self, forKey: .audioSpatialFormat)
    }
}

public struct MediaBadgeSource: Decodable, Equatable, Sendable {
    public var mediaStreams: [MediaBadgeStream]
    public var defaultAudioStreamIndex: Int?
    enum CodingKeys: String, CodingKey { case mediaStreams = "MediaStreams", defaultAudioStreamIndex = "DefaultAudioStreamIndex" }
    public init(mediaStreams: [MediaBadgeStream], defaultAudioStreamIndex: Int? = nil) { self.mediaStreams = mediaStreams; self.defaultAudioStreamIndex = defaultAudioStreamIndex }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mediaStreams = try c.decodeIfPresent([MediaBadgeStream].self, forKey: .mediaStreams) ?? []
        defaultAudioStreamIndex = try c.decodeIfPresent(Int.self, forKey: .defaultAudioStreamIndex)
    }
}

/// Catalog presentation rules shared with the Android reference (core CatalogModels, CatalogAccessibility,
/// CatalogReturnPolicy, SearchPresentation, SearchClient, MediaBadges).
public enum CatalogRules {
    /// Re-entered catalog pages reconcile server-owned user data, even when their layout is cached.
    public static func returnRefresh(page: String) -> CatalogReturnRefresh {
        switch page {
        case "Home": return .homeRows
        case "Detail": return .titleAndChildren
        case "Library", "Person": return .loadedCards
        case "Search": return .searchResults
        default: return .none
        }
    }

    /// Local continuity until the server answers. Only a physical end of file marks played locally (the server applies
    /// its own thresholds on Stop); favorites and other server fields are kept.
    public static func playbackReturnData(previous: ItemUserData, positionTicks: Int64, ended: Bool) -> ItemUserData {
        var result = previous
        result.playbackPositionTicks = ended ? 0 : max(0, positionTicks)
        result.played = previous.played || ended
        return result
    }

    /// Distinct copies of one title and year (same kind) need their library named, even with identical artwork.
    public static func searchDuplicateIds(_ items: [MediaItem]) -> Set<String> {
        let groups = Dictionary(grouping: items) { "\($0.type)\u{0}\($0.name.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())\u{0}\($0.productionYear.map(String.init) ?? "nil")" }
        return Set(groups.values.filter { Set($0.map(\.id)).count > 1 }.flatMap { $0.map(\.id) })
    }

    /// Only a real viewer-visible library, never a filesystem path or a guessed parent folder's name.
    public static func searchLibraryName(ancestors: [MediaItem], libraries: [MediaItem]) -> String? {
        for ancestor in ancestors { if let library = libraries.first(where: { $0.id == ancestor.id }) { return library.name } }
        return ancestors.first { $0.type == "CollectionFolder" }?.name
    }

    /// Which search results need their library named because another result of the same kind shows the same card text:
    /// title, year and (for episodes) series (CAT-14a). People are never labelled. Input order.
    public static func searchDuplicateLabels(_ items: [SearchLabelItem]) -> [String] {
        func norm(_ s: String?) -> String { (s ?? "").split(whereSeparator: { $0.isWhitespace }).joined(separator: " ").lowercased() }
        func key(_ i: SearchLabelItem) -> String { [i.type ?? "", norm(i.name), i.year.map(String.init) ?? "", norm(i.seriesName)].joined(separator: "\u{0}") }
        var counts: [String: Int] = [:]
        for item in items where item.type != "Person" && !norm(item.name).isEmpty { counts[key(item), default: 0] += 1 }
        return items.filter { (counts[key($0)] ?? 0) > 1 }.map(\.id)
    }

    /// A display safety ceiling, not a correction of the playback timeline (CAT-05): under a minute names its seconds,
    /// rounded to the nearest and never "0 s"; unknown or implausible totals (over 24 h) are omitted.
    public static func runtimeLabel(_ ticks: Int64?) -> String {
        guard let ticks, ticks >= 1, ticks <= 864_000_000_000 else { return "" }
        if ticks < 6_000_000_000 {
            let seconds = max(1, (ticks + 5_000_000) / 10_000_000)
            let minutes = seconds / 60, remainder = seconds % 60
            if minutes == 0 { return "\(seconds) s" }
            return remainder == 0 ? "\(minutes)m" : "\(minutes)m \(remainder)s"
        }
        let minutes = ticks / 600_000_000, hours = minutes / 60
        return hours > 0 ? "\(hours)h \(minutes % 60)m" : "\(minutes)m"
    }

    /// One stable spoken description of a card, independent of artwork, truncation and decorative badges.
    public static func announcement(_ item: MediaItem) -> String {
        var parts = [item.name]
        if item.type == "Episode" {
            if let series = item.seriesName, !series.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { parts.append(series) }
            if let season = item.parentIndexNumber { parts.append("Season \(season)") }
            if let episode = item.indexNumber { parts.append("Episode \(episode)") }
        }
        if let year = item.productionYear { parts.append(String(year)) }
        if item.isWatched { parts.append("Watched") }
        else if !item.isEmptyContainer && item.userData.playbackPositionTicks > 0 { parts.append(Catalog.resumeLabel(item.userData.playbackPositionTicks)) }
        if let unplayed = item.userData.unplayedItemCount, !item.isEmptyContainer { parts.append("\(unplayed) unplayed") }
        if item.userData.isFavorite { parts.append("Favorite") }
        return parts.joined(separator: ", ")
    }

    /// What a library-like tile with no art shows instead (CAT-20): the kind of media it holds, never an initial. Nil for
    /// a title, which keeps its own fallback; each platform maps a kind to its own icon.
    public static func placeholderKind(_ item: MediaItem) -> String? {
        let library = ["CollectionFolder", "UserView"].contains(item.type) || (item.type == "Folder" && item.collectionType != nil)
        if library {
            switch item.collectionType {
            case "movies": return "movies"
            case "tvshows": return "shows"
            case "music": return "music"
            case "musicvideos", "homevideos": return "videos"
            case "photos": return "photos"
            case "books": return "books"
            case "livetv": return "livetv"
            case "playlists": return "playlists"
            case "boxsets": return "collections"
            default: return "folder"
            }
        }
        switch item.type {
        case "BoxSet": return "collections"
        case "Playlist": return "playlists"
        case "Folder": return "folder"
        default: return nil
        }
    }

    /// The primary detail action's words. A series or season names the episode Play starts ("Play S1:E1 · A Quiet
    /// Morning", "Resume S1:E2 · Arrivals"); a movie or episode keeps "Play" or "Resume from 12:34".
    public static func detailPlayLabel(item: MediaItem, playItem: MediaItem?, finished: Bool = false) -> String {
        if item.isMissing { return "Not in your library" }
        // PLAY-17: the return from a natural end replays a film or episode from the start; a series or season names its episode.
        if finished && !["Series", "Season"].contains(item.type) { return "Play again" }
        let position = playItem?.userData.playbackPositionTicks ?? 0
        guard let playItem, ["Series", "Season"].contains(item.type), playItem.type == "Episode" else {
            return position > 0 ? Catalog.resumeLabel(position) : "Play"
        }
        let verb = position > 0 ? "Resume" : "Play"
        let name = playItem.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || playItem.name == "Untitled" ? nil : playItem.name
        return ["\(verb) \(playItem.episodeLabel)", name].compactMap { $0 }.joined(separator: " · ")
    }

    /// Format badges for an item's default version, in order: resolution, dynamic range, channel layout, spatial audio.
    /// Only what the server reported; SDR, stereo and unknown values add nothing.
    public static func mediaBadges(_ source: MediaBadgeSource?) -> [String] {
        guard let source else { return [] }
        let video = source.mediaStreams.first { $0.type == "Video" && !$0.isExternal }
        let audio = source.mediaStreams.first { $0.type == "Audio" && $0.index == source.defaultAudioStreamIndex } ?? source.mediaStreams.first { $0.type == "Audio" }
        var badges: [String] = []
        if let video {
            // Scope and cropped 4K masters keep the width and lose height (3840x1600), so either axis counts.
            if (video.width ?? 0) >= 3200 || (video.height ?? 0) >= 2000 { badges.append("4K") }
            switch video.videoRangeType {
            case "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithEL", "DOVIWithHDR10Plus", "DOVIWithELHDR10Plus": badges.append("Dolby Vision")
            case "HDR10Plus": badges.append("HDR10+")
            case "HDR10": badges.append("HDR10")
            case "HLG": badges.append("HLG")
            default: break // DOVIInvalid: unusable Dolby Vision metadata; claiming any range would be a guess.
            }
        }
        if let audio {
            let layout = (audio.channelLayout ?? "").lowercased()
            if layout.hasPrefix("7.1") || audio.channels == 8 { badges.append("7.1") }
            else if layout.hasPrefix("5.1") || audio.channels == 6 { badges.append("5.1") }
            let profile = audio.profile ?? ""
            if let format = audio.audioSpatialFormat {
                // A server that reports the field has decided, "None" included.
                if format.caseInsensitiveCompare("DolbyAtmos") == .orderedSame { badges.append("Atmos") }
                else if format.caseInsensitiveCompare("DTSX") == .orderedSame { badges.append("DTS:X") }
            } else if profile.range(of: "Dolby Atmos", options: .caseInsensitive) != nil { badges.append("Atmos") }
            else if profile.range(of: "DTS:X", options: .caseInsensitive) != nil { badges.append("DTS:X") }
        }
        return badges
    }
}
