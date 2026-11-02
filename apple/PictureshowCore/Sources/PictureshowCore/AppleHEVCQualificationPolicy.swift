// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

/// Measurements from one real hardware-required decode of the bundled reference.
/// This is a tested lower bound, never an inventory of a decoder's maximum limits.
public struct AppleHEVCDecodeMeasurement: Sendable {
    public let configurationRecord: Data
    public let width: Int
    public let height: Int
    public let sourceFrameCount: Int
    public let decodedFrameCount: Int
    public let droppedFrameCount: Int
    public let decodeErrorCount: Int
    public let elapsedSeconds: Double
    public let sourceDurationSeconds: Double
    public let sourceFrameRate: Double
    public let hardwareDecoder: Bool
    public let colorPrimaries: String?
    public let transferFunction: String?
    public let yCbCrMatrix: String?
    public init(measuredConfigurationRecord: Data, width: Int, height: Int, sourceFrameCount: Int, decodedFrameCount: Int, droppedFrameCount: Int, decodeErrorCount: Int, elapsedSeconds: Double, sourceDurationSeconds: Double, sourceFrameRate: Double, hardwareDecoder: Bool, colorPrimaries: String?, transferFunction: String?, yCbCrMatrix: String?) {
        configurationRecord = measuredConfigurationRecord; self.width = width; self.height = height
        self.sourceFrameCount = sourceFrameCount; self.decodedFrameCount = decodedFrameCount
        self.droppedFrameCount = droppedFrameCount; self.decodeErrorCount = decodeErrorCount
        self.elapsedSeconds = elapsedSeconds; self.sourceDurationSeconds = sourceDurationSeconds; self.sourceFrameRate = sourceFrameRate
        self.hardwareDecoder = hardwareDecoder; self.colorPrimaries = colorPrimaries; self.transferFunction = transferFunction; self.yCbCrMatrix = yCbCrMatrix
    }
}

public enum AppleHEVCQualificationPolicy {
    /// Validate the actual HEVCDecoderConfigurationRecord, not a codec-wide Boolean.
    public static func matchesReference(configurationRecord: Data, width: Int, height: Int) -> Bool {
        let bytes = Array(configurationRecord)
        guard width == 1920, height == 1080, bytes.count >= 23,
              bytes[0] == 1, bytes[1] == 1, bytes[12] == 120,
              bytes[16] & 3 == 1, bytes[17] & 7 == 0, bytes[18] & 7 == 0 else { return false }
        var offset = 23
        var parameterSets = Set<Int>()
        for _ in 0..<Int(bytes[22]) {
            guard offset + 3 <= bytes.count else { return false }
            let type = Int(bytes[offset] & 0x3f)
            let count = Int(bytes[offset + 1]) * 256 + Int(bytes[offset + 2]); offset += 3
            guard count > 0 else { return false }
            for _ in 0..<count {
                guard offset + 2 <= bytes.count else { return false }
                let size = Int(bytes[offset]) * 256 + Int(bytes[offset + 1]); offset += 2
                guard size >= 2, size <= bytes.count - offset, Int((bytes[offset] >> 1) & 0x3f) == type else { return false }
                parameterSets.insert(type); offset += size
            }
        }
        return offset == bytes.count && parameterSets.isSuperset(of: [32, 33, 34])
    }

    public static func capabilities(measured result: AppleHEVCDecodeMeasurement, displayWidth: Int, displayHeight: Int, displayRefreshRate: Double) -> DeviceCapabilities? {
        guard matchesReference(configurationRecord: result.configurationRecord, width: result.width, height: result.height),
              result.hardwareDecoder, result.sourceFrameCount == 60, result.decodedFrameCount == 60,
              result.droppedFrameCount == 0, result.decodeErrorCount == 0,
              result.elapsedSeconds.isFinite, result.elapsedSeconds > 0, result.elapsedSeconds <= 2,
              result.sourceDurationSeconds.isFinite, abs(result.sourceDurationSeconds - 2) < 0.000001,
              result.sourceFrameRate.isFinite, abs(result.sourceFrameRate - 30) < 0.000001,
              result.colorPrimaries == "ITU_R_709_2", result.transferFunction == "ITU_R_709_2", result.yCbCrMatrix == "ITU_R_709_2",
              displayWidth > 0, displayHeight > 0, displayRefreshRate.isFinite, displayRefreshRate > 0 else { return nil }
        let level = CodecLevel(major: 4)
        let decoder = VideoDecoder(codec: "HEVC", profiles: ["Main"], maxLevel: level, maxWidth: 1920, maxHeight: 1080, maxFrameRate: 30,
                                   maxFrameRateAtMaxSize: 30, maxBitDepth: 8, hdrFormats: [], dolbyVisionProfiles: [], hardware: true,
                                   sustainablePerformance: VideoPerformance(width: 1920, height: 1080, frameRate: 30),
                                   profileCapabilities: [VideoTuple(profile: "Main", maxLevel: level, maxBitDepth: 8, hdrFormats: [])])
        let capabilities = DeviceCapabilities(name: "Apple measured HEVC Main reference", videoDecoders: [decoder],
                                              display: DisplayCapabilities(maxWidth: max(displayWidth, displayHeight), maxHeight: min(displayWidth, displayHeight), maxRefreshRate: displayRefreshRate, hdrFormats: []),
                                              audioDecoders: [], audioOutput: AudioOutput(pcmChannels: 2, passthrough: []), settings: nil)
        return ReferenceDeviceProfile.prefersHevcTranscode(capabilities) ? capabilities : nil
    }
}

public enum AppleHEVCTranscoding {
    private static func parameters(_ decision: PlayDecision) -> [String: String]? {
        guard let items = URLComponents(url: decision.url, resolvingAgainstBaseURL: false)?.queryItems else { return nil }
        var result: [String: String] = [:]
        for item in items {
            let key = item.name.lowercased()
            guard result[key] == nil, let value = item.value else { return nil }
            result[key] = value
        }
        return result
    }
    public static func isHEVC(_ decision: PlayDecision) -> Bool {
        decision.method == .transcode && parameters(decision)?["videocodec"]?.lowercased() == "hevc"
    }
    /// These are explicit server output constraints. They do not substitute for
    /// native transport/bitstream acceptance on the negotiated server version.
    public static func isBounded(_ decision: PlayDecision) -> Bool {
        guard isHEVC(decision), decision.source.transcodingSubProtocol?.lowercased() == "hls",
              decision.source.transcodingContainer?.lowercased() == "mp4", decision.url.path.lowercased().hasSuffix("/master.m3u8"),
              let query = parameters(decision), query["segmentcontainer"]?.lowercased() == "mp4",
              query["allowvideostreamcopy"]?.lowercased() == "false",
              query["hevc-profile"]?.lowercased() == "main", query["hevc-rangetype"] == "SDR",
              let level = Double(query["hevc-level"] ?? ""), level.isFinite, level > 0, level <= 120,
              let depth = Int(query["hevc-videobitdepth"] ?? ""), depth == 8,
              let width = Int(query["maxwidth"] ?? ""), width > 0, width <= 1920,
              let height = Int(query["maxheight"] ?? ""), height > 0, height <= 1080,
              let rate = Double(query["maxframerate"] ?? ""), rate.isFinite, rate > 0, rate <= 30 else { return false }
        return true
    }
}

public enum ApplePlaybackRecovery {
    public static func canRetry(fallbackUsed: Bool, hevcFallbackUsed: Bool, isHEVCTranscode: Bool) -> Bool {
        isHEVCTranscode ? !hevcFallbackUsed : !fallbackUsed
    }
}
