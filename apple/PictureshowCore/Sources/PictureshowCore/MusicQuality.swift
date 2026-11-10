// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

public struct MusicOutput: Equatable, Sendable {
    public var label: String
    public var lossless: Bool
    public var converted: Bool
    public var reason: String?
    public var json: [String: Any] { ["label": label, "lossless": lossless, "converted": converted, "reason": reason as Any? ?? NSNull()] }
}
public enum MusicQuality {
    public static let choices = ["auto", "original", "256", "128"]
    public static func ceiling(quality: String, metered: Bool) -> Int {
        switch quality { case "original": return 320_000_000; case "256": return 256_000; case "128": return 128_000; default: return metered ? 256_000 : 320_000_000 }
    }
    public static func isLossless(codec: String?) -> Bool {
        guard let codec = codec?.lowercased() else { return false }
        return Set(["flac", "alac", "ape", "wavpack", "tak", "truehd", "mlp"]).contains(codec) || codec.hasPrefix("pcm_")
    }
    /// Reference container contract for shared parity; Apple runtime must supply a measured demuxer/decoder contract.
    public static func referenceDeviceProfile(decoders: Set<String>, ceiling: Int, name: String = "Pictureshow music") -> [String: Any] {
        let containers: [(String, Set<String>)] = [
            ("flac", ["FLAC"]), ("mp3", ["MP3"]), ("aac", ["AAC"]),
            ("m4a,m4b,mp4,mov", ["AAC", "ALAC", "FLAC", "MP3", "OPUS"]),
            ("ogg,oga", ["VORBIS", "OPUS", "FLAC"]), ("opus", ["OPUS"]),
            ("webm,webma", ["OPUS", "VORBIS"]), ("wav", ["PCM"]),
            ("mka,mkv", ["AAC", "ALAC", "FLAC", "MP3", "OPUS", "VORBIS", "PCM"])
        ]
        let direct: [[String: Any]] = containers.compactMap { container, supported in
            let codecs = ReferenceDeviceProfile.audioOrder.filter { supported.contains($0) && decoders.contains($0) }
            guard !codecs.isEmpty else { return nil }
            return ["Container": container, "AudioCodec": codecs.map(ReferenceDeviceProfile.audioName).joined(separator: ","), "VideoCodec": "", "Type": "Audio"]
        }
        let conversion: [[String: Any]] = decoders.contains("AAC") ? [["Container": "ts", "Type": "Audio", "VideoCodec": "", "AudioCodec": "aac", "Protocol": "Hls", "MaxAudioChannels": "2", "BreakOnNonKeyFrames": true]] : []
        return ["Name": name, "MaxStreamingBitrate": ceiling, "MaxStaticBitrate": ceiling, "MaxStaticMusicBitrate": ceiling,
                "MusicStreamingTranscodingBitrate": min(256_000, ceiling), "DirectPlayProfiles": direct, "TranscodingProfiles": conversion]
    }
    public static func output(codec rawCodec: String?, container: String?, method: String, route: String, ceiling: Int, reasons: [String] = []) -> MusicOutput {
        let codec = (rawCodec?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false ? rawCodec : container)?.uppercased() ?? ""
        let converted = method == "Transcode", sourceLossless = isLossless(codec: rawCodec)
        let lossless = !converted && sourceLossless && !["bluetooth", "cast", "remote"].contains(route)
        let label: String
        if converted { label = "Converted to AAC, up to \(min(ceiling, 256_000) / 1000) kbps (lossy)" }
        else {
            let name = codec.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "Original file" : codec
            if lossless { label = name + " (lossless)" }
            else if sourceLossless { label = name + (route == "bluetooth" ? " sent over Bluetooth (lossy)" : " sent to another device") }
            else { label = name + " (original)" }
        }
        var reason: String?
        if converted {
            if reasons.contains(where: { $0.contains("Bitrate") }) { reason = "It's above the Music quality you picked. Change Music quality in Settings to hear the original." }
            else {
                let name = codec.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "this file" : codec
                let shape = [("AudioChannelsNotSupported", "channel count", "a stereo copy"), ("AudioSampleRateNotSupported", "sample rate", "a 48 kHz copy"), ("AudioBitDepthNotSupported", "bit depth", "a 16-bit copy")].first { reasons.contains($0.0) }
                let why = shape.map { "This device can't play \(name) at its \($0.1)" } ?? "This device can't decode \(name)"
                let copy = shape?.2 ?? (sourceLossless && rawCodec?.lowercased() != "flac" ? "a FLAC copy" : "an AAC copy")
                reason = "\(why), so the server converts it. It still plays, and \(copy) on the server plays directly."
            }
        }
        return MusicOutput(label: label, lossless: lossless, converted: converted, reason: reason)
    }
}
