// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// PLAY-27: metadata-only short explanation and remedy for device-forced video conversion.
public struct ConversionNotice: Equatable, Sendable {
    public let title: String
    public let reason: String
    public let fix: String
    public static let subtitleFix = "Choose text subtitles, such as SRT, or turn them off to avoid it."
    public static func of(method: String, url: String, source: MediaSource, reasons: [String], subtitleIndex: Int = -1, device: String = "this device") -> ConversionNotice? {
        guard method == "Transcode" else { return nil }
        let codes = Set(reasons.map { $0.lowercased() })
        let burnedIn = codes.contains("subtitlecodecnotsupported") && PlaybackInformation.subtitle(source: source, selectedIndex: subtitleIndex) == "Burned in"
        let query = URLComponents(string: url)?.queryItems ?? []
        let videos = query.filter { $0.name.caseInsensitiveCompare("VideoCodec") == .orderedSame }
        let copied = PlaybackInformation.method(method, url: url) != "Transcode" || (videos.count == 1 && videos[0].value?.lowercased() == "copy")
        let categories: [(Set<String>, String, String)] = [
            (["videocodecnotsupported"], "This video format cannot play on %@.", "a 1080p H.264 copy"),
            (["videorangetypenotsupported"], "This HDR format cannot play on %@.", "an SDR copy"),
            (["videoprofilenotsupported", "videolevelnotsupported", "videobitdepthnotsupported", "refframesnotsupported"], "The video encoding is too demanding for %@.", "an 8-bit 1080p H.264 copy"),
            (["videoresolutionnotsupported"], "The resolution is too high for %@.", "a 1080p copy"),
            (["videoframeratenotsupported"], "The frame rate is too high for %@.", "a 30 fps copy"),
            (["interlacedvideonotsupported"], "Interlaced video cannot play on %@.", "a deinterlaced copy"),
            (["anamorphicvideonotsupported"], "The non-square pixels cannot display correctly on %@.", "a square-pixel copy"),
            (["videorotationnotsupported"], "The rotation cannot display correctly on %@.", "an upright copy")
        ]
        let title = "Converting this video for \(device)"
        if !copied, let category = categories.first(where: { !$0.0.isDisjoint(with: codes) }) {
            return Self(title: title, reason: category.1.replacingOccurrences(of: "%@", with: device), fix: "\(category.2.prefix(1).uppercased())\(category.2.dropFirst()) on the server would play without conversion.")
        }
        return burnedIn ? Self(title: title, reason: "The selected subtitles must be burned in for \(device).", fix: subtitleFix) : nil
    }
    public static func of(_ decision: PlayDecision, subtitleIndex: Int = -1, device: String = "this device") -> ConversionNotice? {
        of(method: decision.method.rawValue, url: decision.url.absoluteString, source: decision.source, reasons: decision.transcodeReasons, subtitleIndex: subtitleIndex, device: device)
    }
}
