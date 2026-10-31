// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct MediaStream: Codable, Equatable, Sendable, Identifiable {
    public var index: Int
    public var type: String?
    public var codec: String?
    public var language: String?
    public var displayTitle: String?
    public var title: String?
    public var channels: Int?
    public var isExternal: Bool
    public var isDefault: Bool
    public var isHearingImpaired: Bool
    public var deliveryMethod: String?
    public var realFrameRate: Double?
    public var averageFrameRate: Double?
    public var isForced: Bool
    public var id: Int { index }
    public var label: String { displayTitle ?? title ?? [language, codec].compactMap { $0 }.joined(separator: " · ").nilIfEmpty ?? "Track \(index)" }
    enum CodingKeys: String, CodingKey { case index = "Index", type = "Type", codec = "Codec", language = "Language", displayTitle = "DisplayTitle", title = "Title", channels = "Channels", isExternal = "IsExternal", isDefault = "IsDefault", isForced = "IsForced", isHearingImpaired = "IsHearingImpaired", deliveryMethod = "DeliveryMethod", realFrameRate = "RealFrameRate", averageFrameRate = "AverageFrameRate" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        index = try c.decodeIfPresent(Int.self, forKey: .index) ?? 0
        type = try c.decodeIfPresent(String.self, forKey: .type)
        codec = try c.decodeIfPresent(String.self, forKey: .codec)
        language = try c.decodeIfPresent(String.self, forKey: .language)
        displayTitle = try c.decodeIfPresent(String.self, forKey: .displayTitle)
        title = try c.decodeIfPresent(String.self, forKey: .title)
        channels = try c.decodeIfPresent(Int.self, forKey: .channels)
        isExternal = try c.decodeIfPresent(Bool.self, forKey: .isExternal) ?? false
        isDefault = try c.decodeIfPresent(Bool.self, forKey: .isDefault) ?? false
        isForced = try c.decodeIfPresent(Bool.self, forKey: .isForced) ?? false
        isHearingImpaired = try c.decodeIfPresent(Bool.self, forKey: .isHearingImpaired) ?? false
        deliveryMethod = try c.decodeIfPresent(String.self, forKey: .deliveryMethod)
        realFrameRate = try c.decodeIfPresent(Double.self, forKey: .realFrameRate)
        averageFrameRate = try c.decodeIfPresent(Double.self, forKey: .averageFrameRate)
    }
}

public struct MediaSource: Codable, Equatable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public var id: String?
    public var container: String?
    public var name: String?
    public var eTag: String?
    public var liveStreamId: String?
    public var isInfiniteStream: Bool = false
    public var transcodingSubProtocol: String?
    public var transcodingContainer: String?
    public var runTimeTicks: Int64?
    public var supportsDirectPlay: Bool
    public var supportsDirectStream: Bool
    public var supportsTranscoding: Bool
    public var transcodingUrl: String?
    public var mediaStreams: [MediaStream]
    public var requiredHttpHeaders: [String:String]
    public var defaultAudioStreamIndex: Int?
    public var defaultSubtitleStreamIndex: Int?
    public init(id: String? = nil, container: String? = nil, supportsDirectPlay: Bool = false, supportsDirectStream: Bool = false, transcodingUrl: String? = nil) {
        self.id = id; self.container = container; self.supportsDirectPlay = supportsDirectPlay
        self.supportsDirectStream = supportsDirectStream; self.transcodingUrl = transcodingUrl
        supportsTranscoding = false; mediaStreams = []; requiredHttpHeaders = [:]
    }
    enum CodingKeys: String, CodingKey { case id = "Id", container = "Container", name = "Name", eTag = "ETag", runTimeTicks = "RunTimeTicks", liveStreamId = "LiveStreamId", isInfiniteStream = "IsInfiniteStream", transcodingSubProtocol = "TranscodingSubProtocol", transcodingContainer = "TranscodingContainer", supportsDirectPlay = "SupportsDirectPlay", supportsDirectStream = "SupportsDirectStream", supportsTranscoding = "SupportsTranscoding", transcodingUrl = "TranscodingUrl", mediaStreams = "MediaStreams", requiredHttpHeaders = "RequiredHttpHeaders", defaultAudioStreamIndex = "DefaultAudioStreamIndex", defaultSubtitleStreamIndex = "DefaultSubtitleStreamIndex" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id)
        container = try c.decodeIfPresent(String.self, forKey: .container)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        eTag = try c.decodeIfPresent(String.self, forKey: .eTag)
        liveStreamId = try c.decodeIfPresent(String.self, forKey: .liveStreamId)
        isInfiniteStream = try c.decodeIfPresent(Bool.self, forKey: .isInfiniteStream) ?? false
        transcodingSubProtocol = try c.decodeIfPresent(String.self, forKey: .transcodingSubProtocol)
        transcodingContainer = try c.decodeIfPresent(String.self, forKey: .transcodingContainer)
        runTimeTicks = try c.decodeIfPresent(Int64.self, forKey: .runTimeTicks)
        supportsDirectPlay = try c.decodeIfPresent(Bool.self, forKey: .supportsDirectPlay) ?? false
        supportsDirectStream = try c.decodeIfPresent(Bool.self, forKey: .supportsDirectStream) ?? false
        supportsTranscoding = try c.decodeIfPresent(Bool.self, forKey: .supportsTranscoding) ?? false
        transcodingUrl = try c.decodeIfPresent(String.self, forKey: .transcodingUrl)
        mediaStreams = try c.decodeIfPresent([MediaStream].self, forKey: .mediaStreams) ?? []
        let raw = try c.decodeIfPresent([String:String?].self, forKey: .requiredHttpHeaders) ?? [:]
        requiredHttpHeaders = raw.compactMapValues { $0 }
        defaultAudioStreamIndex = try c.decodeIfPresent(Int.self, forKey: .defaultAudioStreamIndex)
        defaultSubtitleStreamIndex = try c.decodeIfPresent(Int.self, forKey: .defaultSubtitleStreamIndex)
    }
    public var description: String { "MediaSource(id=\(id ?? ""), container=\(container ?? ""), credentials=REDACTED)" }
    public var debugDescription: String { description }
}

public struct PlaybackInfo: Decodable, Sendable {
    public var mediaSources: [MediaSource]
    public var playSessionId: String?
    public var errorCode: String?
    public init(mediaSources: [MediaSource], playSessionId: String? = nil, errorCode: String? = nil) {
        self.mediaSources = mediaSources; self.playSessionId = playSessionId; self.errorCode = errorCode
    }
    enum CodingKeys: String, CodingKey { case mediaSources = "MediaSources", playSessionId = "PlaySessionId", errorCode = "ErrorCode" }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mediaSources = try c.decodeIfPresent([MediaSource].self, forKey: .mediaSources) ?? []
        playSessionId = try c.decodeIfPresent(String.self, forKey: .playSessionId)
        errorCode = try c.decodeIfPresent(String.self, forKey: .errorCode)
    }
}

public enum PlayMethod: String, Codable, Sendable { case directPlay = "DirectPlay", directStream = "DirectStream", transcode = "Transcode" }

public struct PlayDecision: Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public var itemID: String
    public var url: URL
    public var headers: [String:String]
    public var method: PlayMethod
    public var source: MediaSource
    public var playSessionId: String?
    public var startTicks: Int64
    public var audioIndex: Int?
    public var subtitleIndex: Int?
    public var transcodeReasons: [String]
    /// Jellyfin VOD HLS advertises the full source timeline, just like a static file.
    /// A server-side progressive seek would need a separate explicit timeline model.
    public var timelineOffsetTicks: Int64 { 0 }
    public func serverPositionTicks(playerSeconds: Double) -> Int64 {
        Ticks.adding(Ticks.from(seconds: playerSeconds), timelineOffsetTicks)
    }
    public var initialSeekSeconds: Double { Ticks.seconds(startTicks) }
    public var description: String { "PlayDecision(item=\(itemID), method=\(method.rawValue), credentials=REDACTED)" }
    public var debugDescription: String { description }
}

public enum Ticks {
    public static let perSecond: Int64 = 10_000_000
    public static func seconds(_ ticks: Int64) -> Double { Double(ticks) / Double(perSecond) }
    public static func from(nanoseconds: Int64) -> Int64 { nanoseconds / 100 }
    public static func from(seconds: Double) -> Int64 {
        guard seconds.isFinite else { return 0 }
        let value = seconds * Double(perSecond)
        if value >= Double(Int64.max) { return Int64.max }
        if value <= Double(Int64.min) { return Int64.min }
        return Int64(value)
    }
    public static func adding(_ a: Int64, _ b: Int64) -> Int64 {
        let (value, overflow) = a.addingReportingOverflow(b)
        return overflow ? (a > 0 ? Int64.max : Int64.min) : value
    }
}

public enum PlayDecisions {
    public static func decide(account: Account, itemID: String, response: PlaybackInfo, startTicks: Int64 = 0, audioIndex: Int? = nil, subtitleIndex: Int? = nil, mediaSourceID: String? = nil, deviceName: String = "Apple", version: String = "0.1.0") throws -> PlayDecision {
        if response.errorCode != nil { throw ClientError.serverRefused }
        if let mediaSourceID, !response.mediaSources.contains(where: { $0.id == mediaSourceID }) { throw ClientError.sourceNotFound }
        let sources = response.mediaSources.filter { mediaSourceID == nil || $0.id == mediaSourceID }
        guard let source = sources.first(where: { $0.supportsDirectPlay || $0.supportsDirectStream || !($0.transcodingUrl ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else { throw ClientError.noCompatibleSource }
        let method: PlayMethod
        let url: URL
        if source.supportsDirectPlay || source.supportsDirectStream {
            method = source.supportsDirectPlay ? .directPlay : .directStream
            url = try ServerURLs.endpoint(account.server, path: "Videos/\(ServerURLs.encode(itemID))/stream", query: [URLQueryItem(name: "static", value: "true"), URLQueryItem(name: "mediaSourceId", value: source.id), URLQueryItem(name: "playSessionId", value: response.playSessionId), URLQueryItem(name: "tag", value: source.eTag)].filter { $0.value != nil })
        } else {
            method = .transcode
            url = try ServerURLs.resolve(server: account.server, reference: source.transcodingUrl ?? "")
        }
        guard ServerURLs.isSameOrigin(account.server.url, url) else { throw ClientError.unsafeStreamURL }
        let cleanURL = ServerURLs.strippingCredentials(url)
        var headers = source.requiredHttpHeaders.filter { !ServerURLs.credentialNames.contains($0.key.lowercased()) && $0.key.lowercased() != "authorization" }
        headers["Authorization"] = AuthorizationHeader.build(deviceID: account.deviceID, deviceName: deviceName, version: version, token: account.accessToken)
        let reasons = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name.lowercased() == "transcodereasons" })?.value?.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) } ?? []
        return PlayDecision(itemID: itemID, url: cleanURL, headers: headers, method: method, source: source, playSessionId: response.playSessionId, startTicks: max(0, startTicks), audioIndex: audioIndex ?? PlaybackSelection.defaultStream(streams: source.mediaStreams, type: "Audio", serverIndex: source.defaultAudioStreamIndex), subtitleIndex: subtitleIndex ?? PlaybackSelection.defaultStream(streams: source.mediaStreams, type: "Subtitle", serverIndex: source.defaultSubtitleStreamIndex), transcodeReasons: reasons)
    }
}

/// Conservative AVFoundation profile: unsupported containers/codecs negotiate server HLS.
/// No Android decoder, passthrough or software-FFmpeg claims apply to Apple devices.
public enum AppleProfile {
    public static func json(supportsHEVC: Bool = false, maxStreamingBitrate: Int = 200_000_000) -> [String:Any] {
        func condition(_ name: String, _ property: String, _ value: String) -> [String:Any] {
            ["Condition": name, "Property": property, "Value": value, "IsRequired": false]
        }
        let h264: [String:Any] = ["Type": "Video", "Codec": "h264", "Conditions": [
            condition("LessThanEqual", "VideoBitDepth", "8"),
            condition("EqualsAny", "VideoProfile", "high|main|baseline|constrained baseline"),
            condition("LessThanEqual", "Width", "1920"), condition("LessThanEqual", "Height", "1080"),
            condition("EqualsAny", "VideoRangeType", "SDR")
        ]]
        let hevc: [String:Any] = ["Type": "Video", "Codec": "hevc", "Conditions": [
            condition("LessThanEqual", "VideoBitDepth", "10"), condition("EqualsAny", "VideoRangeType", "SDR")
        ]]
        let audio: [String:Any] = ["Type": "VideoAudio", "Codec": "aac,mp3,alac,ac3,eac3", "Conditions": [condition("LessThanEqual", "AudioChannels", "6")]]
        let direct: [String:Any] = ["Container": "mp4,m4v,mov", "Type": "Video", "VideoCodec": supportsHEVC ? "h264,hevc" : "h264", "AudioCodec": "aac,mp3,alac,ac3,eac3"]
        let transcode: [String:Any] = ["Container": "ts", "Type": "Video", "VideoCodec": "h264", "AudioCodec": "aac", "Protocol": "hls", "Context": "Streaming", "MaxAudioChannels": "2", "MinSegments": 2, "SegmentLength": 6, "EnableSubtitlesInManifest": false, "BreakOnNonKeyFrames": false]
        // AVFoundation can demux embedded MP4 timed text even when subtitles are Off.
        // Advertising it avoids Jellyfin rejecting the entire direct-play container.
        // Positive subtitle selections still explicitly negotiate server burn-in.
        let embedded = [["Format": "mov_text", "Method": "Embed", "Container": "mp4,m4v,mov"]]
        let subtitles = embedded + ["srt", "ass", "ssa", "sub", "pgssub", "dvdsub", "vtt", "webvtt"].map { ["Format": $0, "Method": "Encode"] }
        return ["Name": "Pictureshow Apple", "MaxStreamingBitrate": maxStreamingBitrate, "MaxStaticBitrate": maxStreamingBitrate,
                "DirectPlayProfiles": [direct], "TranscodingProfiles": [transcode], "CodecProfiles": [h264, hevc, audio], "SubtitleProfiles": subtitles]
    }
}
