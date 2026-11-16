// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Viewer labels use chooser order, never the server's stream index. Pass each audio/subtitle list separately.
public enum PlaybackTrackLabels {
    public static func language(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, value != "und" else { return nil }
        let tag = value.replacingOccurrences(of: "_", with: "-")
        let base = tag.components(separatedBy: "-")[0]
        let normalized = PlaybackSelection.language(base) ?? base
        let locale = Locale(identifier: "en")
        if let display = locale.localizedString(forLanguageCode: normalized), display.caseInsensitiveCompare(normalized) != .orderedSame { return display }
        return value
    }

    // Use Unicode letters/numbers, literal punctuation and the original string's full boundary context.
    // In particular, Non-Forced is not a Forced badge, but anon-Forced and non-Forced / Forced are.
    private static func names(_ text: String, _ phrase: String) -> Bool {
        let pattern = #"(?<![\p{L}\p{N}])(?<!\bnon\p{Pd})"# + NSRegularExpression.escapedPattern(for: phrase) + #"(?![\p{L}\p{N}])"#
        guard let expression = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return false }
        return expression.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
    }
    private static func same(_ lhs: String, _ rhs: String) -> Bool { lhs.caseInsensitiveCompare(rhs) == .orderedSame }

    /// Muxer handler names (spec/features/track-labels.md): whole-string, case-insensitive, untrimmed.
    private static let handlerNames: Set<String> = Set([
        "Core Media Audio", "Core Media Video", "Core Media Text", "Core Media Subtitle", "Core Media Closed Caption",
        "Core Media Metadata", "DataHandler", "PictureHandler", "VideoHandler", "SoundHandler", "ClosedCaptionHandler",
        "SubtitleHandler", "HintHandler", "TimeCodeHandler", "GoPro MET", "CAM meta", "CAM dbgi",
        "GPAC ISO Video Handler", "GPAC ISO Auxiliary Video Handler", "GPAC ISO Picture Sequence Handler",
        "GPAC ISO Audio Handler", "GPAC ISO Hint Handler", "GPAC Timed MetaData Handler", "GPAC MPEG-4 OD Handler",
        "GPAC MPEG-4 OCR Handler", "GPAC MPEG-4 Scene Description Handler", "GPAC MPEG-4 MPEG-7 Handler",
        "GPAC MPEG-4 OCI Handler", "GPAC MPEG-4 IPMP Handler", "GPAC MPEG-4 MPEG-J Handler", "GPAC Streaming Text Handler",
        "GPAC MPEG Subtitle Handler", "GPAC DIMS Handler", "GPAC TMCD Handler", "GPAC IsoMedia Handler", "Lavf"
    ].map { $0.lowercased() })
    private static let lavfVersion = try! NSRegularExpression(pattern: #"\Alavf[0-9]+\.[0-9]+\.[0-9]+\z"#, options: [.caseInsensitive])

    /// A handler name written by a muxer is not a viewer's title.
    public static func isHandlerName(_ title: String) -> Bool {
        if handlerNames.contains(title.lowercased()) { return true }
        return lavfVersion.firstMatch(in: title, range: NSRange(title.startIndex..., in: title)) != nil
    }

    public static func label(_ stream: MediaStream, trackNumber: Int? = nil, showDefault: Bool = true) -> String {
        let codecs = ["eac3":"E-AC-3", "ac3":"AC-3", "truehd":"TrueHD", "dts":"DTS", "subrip":"SRT", "srt":"SRT", "hdmv_pgs_subtitle":"PGS", "pgssub":"PGS", "pgs":"PGS", "dvd_subtitle":"VobSub", "dvdsub":"VobSub", "vobsub":"VobSub", "mov_text":"mov_text", "opus":"Opus"]
        let codec = stream.codec.map { codecs[$0.lowercased()] ?? $0.uppercased() } ?? "Unknown format"
        var channels = ""
        if stream.type == "Audio", let count = stream.channels {
            channels = [1:" mono", 2:" stereo", 6:" 5.1", 8:" 7.1"][count] ?? " \(count) channels"
        }
        let format = codec + channels
        let lang = language(stream.language)
        let title = stream.title.flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isHandlerName($0) ? nil : $0 }
        let name: String?
        if let lang, let title, names(title, lang) { name = title } else { name = lang ?? title }
        var parts: [String] = []
        if let name {
            parts.append(name)
            let titleCarriesFormat = title.map { !same($0, name) && !same($0, format) && names($0, format) } ?? false
            if !names(name, format) && !titleCarriesFormat { parts.append(format) }
        } else { parts.append(format) }
        if lang != nil, let title, !same(title, name ?? ""), !same(title, format) { parts.append(title) }
        if lang == nil, let trackNumber { parts.append("Track \(trackNumber)") }
        for (word, enabled) in [("Default", showDefault && stream.isDefault), ("Forced", stream.isForced), ("SDH", stream.isHearingImpaired), ("External", stream.isExternal || stream.isExternalURL)] {
            if enabled && !(title.map { names($0, word) } ?? false) { parts.append(word) }
        }
        return parts.joined(separator: " · ")
    }

    public static func labels(_ streams: [MediaStream]) -> [String] {
        let informativeDefault = streams.count > 1 && streams.filter(\.isDefault).count == 1
        var result = streams.enumerated().map { label($1, trackNumber: $0 + 1, showDefault: informativeDefault) }
        while Set(result).count != result.count {
            let counts = Dictionary(grouping: result, by: { $0 }).mapValues(\.count)
            result = result.enumerated().map { counts[$0.element, default: 0] > 1 ? "\($0.element) · Track \($0.offset + 1)" : $0.element }
        }
        return result
    }
}
