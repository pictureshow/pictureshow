// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public enum PlaybackSelection {
    /// Server choices are authoritative. A missing subtitle choice means off, including forced/default flags.
    public static func defaultStream(streams: [MediaStream], type: String, serverIndex: Int?, preferredLanguage: String = "") -> Int {
        if type == "Subtitle" { return serverIndex ?? -1 }
        if let serverIndex { return serverIndex }
        let candidates = streams.filter { $0.type == type }
        if let index = candidates.first(where: { $0.isDefault })?.index { return index }
        if let preferred = language(preferredLanguage), let index = candidates.first(where: { language($0.language) == preferred })?.index { return index }
        return type == "Audio" ? (candidates.first?.index ?? -1) : -1
    }
    /// Language comparisons ignore a region and accept common ISO 639-2 bibliographic aliases.
    public static func language(_ value: String?) -> String? {
        guard let value else { return nil }
        let base = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().replacingOccurrences(of: "_", with: "-").split(separator: "-").first.map(String.init) ?? ""
        guard !base.isEmpty, base != "und" else { return nil }
        let aliases = ["en":"eng", "fr":"fra", "fre":"fra", "de":"deu", "ger":"deu", "es":"spa", "it":"ita", "pt":"por", "ja":"jpn", "ko":"kor", "zh":"zho", "chi":"zho", "ru":"rus", "ar":"ara", "hi":"hin", "nl":"nld", "dut":"nld", "sv":"swe", "no":"nor", "da":"dan", "fi":"fin", "pl":"pol", "tr":"tur", "el":"ell", "gre":"ell", "he":"heb", "uk":"ukr", "cs":"ces", "cze":"ces", "ro":"ron", "rum":"ron", "hu":"hun", "th":"tha", "vi":"vie", "id":"ind"]
        return aliases[base] ?? base
    }
    public static func startPositionMillis(ticks: Int64, resume: Bool, explicitPositionMs: Int64? = nil) -> Int64 {
        if let explicitPositionMs { return max(0, explicitPositionMs) }
        return resume ? max(0, ticks) / 10_000 : 0
    }
}
