// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Portable wording for the negotiated stream. It deliberately accepts metadata only and never returns a URL.
public enum PlaybackInformation {
    private static let unknownReason = "The server reported an additional conversion requirement that this client does not recognize."
    private static let explanations: [String: String] = [
        "containernotsupported": "This device cannot open the file's container. The server repackages the stream into a compatible container.",
        "videocodecnotsupported": "This device cannot decode this video format. The server converts the video. Try a version with a supported video codec.",
        "audiocodecnotsupported": "This device cannot decode this audio format. The server converts the audio. Choose a compatible audio track if available.",
        "subtitlecodecnotsupported": "This device cannot display this subtitle format. The server converts the subtitles or burns them into the video. Choose a text subtitle track or turn subtitles off to avoid burn-in.",
        "audioisexternal": "The audio is in a separate file. The server combines it with the video.",
        "secondaryaudionotsupported": "This device cannot play the selected secondary audio directly. Choose the default audio track to try direct playback.",
        "streamcountexceedslimit": "The file contains more streams than this device supports. The server selects compatible streams.",
        "videoprofilenotsupported": "This device does not support the video's codec profile. The server converts the video.",
        "videorangetypenotsupported": "This device does not support this video's HDR or color range. The server converts the video. Try an SDR version if available.",
        "videocodectagnotsupported": "This device does not recognize the video's codec tag. The server makes a compatible stream.",
        "videolevelnotsupported": "This video exceeds the decoder's supported codec level. The server converts the video.",
        "videoresolutionnotsupported": "This video's resolution exceeds this device's limit. The server reduces the resolution.",
        "videobitdepthnotsupported": "This device cannot decode the video's bit depth. The server converts the video.",
        "videoframeratenotsupported": "This video's frame rate exceeds this device's limit. The server reduces the frame rate.",
        "videorotationnotsupported": "This device cannot apply the video's rotation. The server rotates the video.",
        "refframesnotsupported": "This video needs more reference frames than the decoder supports. The server converts the video.",
        "anamorphicvideonotsupported": "This device cannot display the video's non-square pixels correctly. The server converts the video.",
        "interlacedvideonotsupported": "This device cannot display this interlaced video directly. The server deinterlaces it.",
        "audiochannelsnotsupported": "The selected audio has more channels than the current output supports. The server converts the audio. Choose a track with fewer channels or check your audio output settings.",
        "audioprofilenotsupported": "This device does not support the audio's codec profile. The server converts the audio. Choose another audio track if available.",
        "audiosampleratenotsupported": "This device does not support the audio's sample rate. The server converts the audio.",
        "audiobitdepthnotsupported": "This device does not support the audio's bit depth. The server converts the audio.",
        "audiobitratenotsupported": "The audio exceeds a supported bitrate limit. The server lowers its bitrate. Choose another audio track if available.",
        "unknownvideostreaminfo": "The server could not identify the video's properties. Refresh this item's metadata on the server and try again.",
        "unknownaudiostreaminfo": "The server could not identify the audio's properties. Refresh this item's metadata on the server and try again.",
        "directplayerror": "Direct playback failed. The server is providing a converted stream. Try another version or check the device's codec support."
    ]

    public static func reason(_ value: String, qualityMbps: Int? = nil) -> String {
        let key = value.lowercased()
        if key == "containerbitrateexceedslimit", let qualityMbps, qualityMbps > 0 {
            return "Quality is capped at \(qualityMbps) Mbps in Settings. Raise the limit to try the original quality; device or server limits may still require conversion."
        }
        if key == "containerbitrateexceedslimit" || key == "videobitratenotsupported" {
            return "This stream exceeds a device or server bitrate limit. The server lowers its bitrate. Check the server's streaming limits."
        }
        return explanations[key] ?? unknownReason
    }

    /// Preserve server order while removing duplicate wording for multiple equivalent reasons.
    public static func reasons(_ values: [String], qualityMbps: Int? = nil) -> [String] {
        var seen = Set<String>()
        return values.map { reason($0, qualityMbps: qualityMbps) }.filter { seen.insert($0).inserted }
    }

    public static func reasons(_ values: [String], method: String, qualityMbps: Int? = nil) -> [String] {
        guard method == "Transcode" else { return [] }
        let mapped = reasons(values, qualityMbps: qualityMbps)
        return mapped.isEmpty ? ["The server did not report why it is converting this stream."] : mapped
    }

    public static func method(_ method: String, url: String) -> String {
        switch method {
        case "DirectPlay": return "Direct play"
        case "DirectStream": return "Direct stream (remux)"
        case "Transcode":
            let query = URLComponents(string: url)?.queryItems ?? []
            func singleParameter(_ name: String) -> String? {
                let matches = query.filter { $0.name.caseInsensitiveCompare(name) == .orderedSame }
                guard matches.count == 1 else { return nil }
                return matches[0].value?.lowercased()
            }
            return singleParameter("videocodec") == "copy" && singleParameter("audiocodec") == "copy" ? "Direct stream (remux)" : "Transcode"
        default: return "Not reported"
        }
    }

    public static func resolution(width: Int?, height: Int?) -> String {
        guard let width, let height, width > 0, height > 0 else { return "Not reported" }
        return "\(width) × \(height)"
    }

    public static func codec(_ value: String?) -> String {
        let identifier = container(value)
        switch identifier {
        case "H264", "H.264", "AVC", "AVC1", "AVC3": return "H.264"
        case "HEVC", "H265", "H.265", "HVC1", "HEV1": return "HEVC"
        case "AV1", "AV01": return "AV1"
        case "VP8", "VP08": return "VP8"
        case "VP9", "VP09": return "VP9"
        case "MPEG2", "MPEG2VIDEO": return "MPEG-2"
        case "MPEG4": return "MPEG-4"
        case "VC1", "WVC1": return "VC-1"
        case "AC3", "AC-3": return "AC-3"
        case "EAC3", "E-AC-3", "EC-3": return "E-AC-3"
        default: return identifier
        }
    }

    /// Container identifiers use the same safety check without codec alias interpretation.
    public static func container(_ value: String?) -> String {
        guard let value, !value.isEmpty, value.utf8.count <= 32,
              value.utf8.allSatisfy({
                  (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || [45, 46, 95].contains(Int($0))
              }) else { return "Not reported" }
        return value.uppercased()
    }

    public static func subtitle(source: MediaSource, selectedIndex: Int) -> String {
        guard selectedIndex >= 0 else { return "Off" }
        guard let stream = source.mediaStreams.first(where: { $0.index == selectedIndex && $0.type?.lowercased() == "subtitle" }) else { return "Not reported" }
        let delivery = stream.deliveryMethod?.lowercased()
        if delivery == "encode" { return "Burned in" }
        if stream.isExternal || stream.isExternalURL || delivery == "external" { return "External" }
        return "Embedded"
    }

    public static func bitrate(_ bitsPerSecond: Int64?) -> String {
        guard let bitsPerSecond, bitsPerSecond > 0 else { return "Not reported" }
        if bitsPerSecond < 1_000_000 {
            return "\(bitsPerSecond / 1_000) kbps"
        }
        let units = bitsPerSecond / 10_000
        let rounded = units + (bitsPerSecond % 10_000 >= 5_000 ? 1 : 0)
        return String(format: "%lld.%02lld Mbps", rounded / 100, rounded % 100)
    }
}

public enum PlaybackSpeeds {
    public static let choices: [Double] = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2]

    /// Encoded output is only considered compatible at normal speed.
    public static func canApply(_ speed: Double, encodedOutput: Bool) -> Bool {
        choices.contains(speed) && (!encodedOutput || speed == 1)
    }

    public static func warning(isTranscoding: Bool) -> String? {
        isTranscoding ? transcodeNotice : nil
    }

    public static let unsupportedOutputNotice = "Speed is limited to 1× while audio passthrough or encoded offload is active. Turn off audio passthrough in Settings and reopen playback to use other speeds."
    public static let transcodeNotice = "Faster speeds may buffer while the server converts."

    public static func label(_ speed: Double) -> String {
        let value: String
        if let choice = choices.first(where: { $0 == speed }) {
            value = choice.rounded() == choice ? String(Int(choice)) : String(choice)
        } else {
            value = String(speed)
        }
        return "\(value)×"
    }
}

/// Rate is scoped to one viewing. Internal decoder replacements keep the instance; a new item resets it.
public struct PlaybackSpeedState: Equatable, Sendable {
    public private(set) var rate: Double = 1

    public init() {}

    @discardableResult public mutating func select(_ requested: Double, encodedOutput: Bool) -> Bool {
        guard PlaybackSpeeds.canApply(requested, encodedOutput: encodedOutput) else {
            rate = 1
            return false
        }
        rate = requested
        return true
    }

    public mutating func beginNextViewing() { rate = 1 }
}
