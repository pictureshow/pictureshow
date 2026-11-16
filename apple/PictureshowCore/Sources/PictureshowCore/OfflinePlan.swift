// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

private let offlineLiveTypes: Set<String> = ["TvChannel", "LiveTvChannel", "LiveTvProgram", "Program", "TvProgram"]
private let offlineVideoTypes: Set<String> = ["Movie", "Episode", "Video"]
private extension OfflinePreset {
    var isVideo: Bool { self == .video720 || self == .video1080 }
    var isAudio: Bool { self == .audio128 || self == .audio256 }
    func estimate(_ runtimeTicks: Int64?) -> Int64? {
        guard let runtimeTicks else { return nil }
        let rate: Int64
        switch self {
        case .video720: rate = 516_000
        case .video1080: rate = 1_016_000
        case .audio128: rate = 16_000
        case .audio256: rate = 32_000
        case .original: return nil
        }
        return rate &* runtimeTicks / 10_000_000
    }
}
public func offlineRecordingIncomplete(isInProgress: Bool, recordingStatus: String?) -> Bool {
    isInProgress || (recordingStatus.map { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && $0 != "Completed" } ?? false)
}
private extension OfflineCandidate {
    var isLive: Bool { isInfinite || offlineLiveTypes.contains(type) }
    var recordingIncomplete: Bool { type == "Recording" || offlineRecordingIncomplete(isInProgress: isInProgress, recordingStatus: recordingStatus) }
    var originalEstimate: Int64? {
        guard let bitrate, let runtimeTicks else { return nil }
        return (Int64(bitrate) / 8) &* runtimeTicks / 10_000_000
    }
}

public func offlinePresets(policy: OfflinePolicy, candidate: OfflineCandidate, qualified: Set<OfflinePreset>) -> [OfflinePresetChoice] {
    OfflinePreset.allCases.map { preset in
        let reason: String?
        if !policy.enableDownloads || !policy.enablePlayback || candidate.canDownload == false { reason = "denied" }
        else if candidate.isLive { reason = "live" }
        else if candidate.recordingIncomplete { reason = "recording-incomplete" }
        else if preset == .original { return OfflinePresetChoice(preset: preset, available: true, estimateBytes: candidate.sizeBytes, exactSize: candidate.sizeBytes != nil) }
        else if preset.isVideo && !offlineVideoTypes.contains(candidate.type) { reason = "wrong-media" }
        else if preset.isAudio && candidate.type != "Audio" { reason = "wrong-media" }
        else if preset.isVideo && !policy.videoTranscoding { reason = "no-conversion-right" }
        else if preset.isAudio && !policy.audioTranscoding { reason = "no-conversion-right" }
        else if !qualified.contains(preset) { reason = "not-qualified" }
        else { reason = nil }
        return OfflinePresetChoice(preset: preset, available: reason == nil, reason: reason,
                                   estimateBytes: reason == nil ? preset.estimate(candidate.runtimeTicks) : nil)
    }
}

/// Container expansion preserves the first occurrence of each item and distinguishes estimates from exact Original sizes.
public func offlineSelect(items: [OfflineCandidate], preset: OfflinePreset) -> OfflineSelection {
    var seen = Set<String>(), taken: [OfflineCandidate] = [], skipped: [String] = []
    for item in items where seen.insert(item.itemId).inserted {
        let eligible = !item.isLive && !item.recordingIncomplete && item.canDownload != false &&
            (preset.isVideo ? offlineVideoTypes.contains(item.type) : preset.isAudio ? item.type == "Audio" : true)
        if eligible { taken.append(item) } else { skipped.append(item.itemId) }
    }
    var known: Int64 = 0, estimated = false, unknown = 0
    for item in taken {
        if preset == .original, let exact = item.sizeBytes { known &+= exact }
        else if let estimate = preset == .original ? item.originalEstimate : preset.estimate(item.runtimeTicks) { known &+= estimate; estimated = true }
        else { estimated = true; unknown += 1 }
    }
    return OfflineSelection(itemIds: taken.map(\.itemId), skipped: skipped, knownBytes: known, estimated: estimated, unknownCount: unknown)
}

public func offlineOriginalRequest(itemId: String) -> OfflineRequest { OfflineRequest(path: "/Items/\(itemId)/Download", query: []) }
public func offlineVideoRequest(itemId: String, sourceId: String, preset: OfflinePreset, audioStreamIndex: Int?, deviceId: String, playSessionId: String) throws -> OfflineRequest {
    guard preset.isVideo else { throw OfflineError.invalidArgument("Not a video preset: \(preset)") }
    var query: [OfflineQueryParameter] = [
        .init("mediaSourceId", sourceId), .init("playSessionId", playSessionId), .init("deviceId", deviceId),
        .init("videoCodec", "h264"), .init("audioCodec", "aac"), .init("maxHeight", preset == .video720 ? "720" : "1080"),
        .init("videoBitRate", preset == .video720 ? "4000000" : "8000000"), .init("audioBitRate", "128000"), .init("maxAudioChannels", "2")
    ]
    if let audioStreamIndex { query.append(.init("audioStreamIndex", String(audioStreamIndex))) }
    query += [.init("subtitleMethod", "Drop"), .init("static", "false"), .init("enableAutoStreamCopy", "false"),
              .init("allowVideoStreamCopy", "false"), .init("allowAudioStreamCopy", "false"), .init("container", "mp4")]
    return OfflineRequest(path: "/Videos/\(itemId)/stream.mp4", query: query)
}
public func offlineSidecarAudioRequest(itemId: String, sourceId: String, preset: OfflinePreset, audioStreamIndex: Int, deviceId: String, playSessionId: String) throws -> OfflineRequest {
    guard preset.isVideo else { throw OfflineError.invalidArgument("A sidecar belongs to a video preset") }
    return OfflineRequest(path: "/Audio/\(itemId)/stream.m4a", query: [
        .init("mediaSourceId", sourceId), .init("playSessionId", playSessionId), .init("deviceId", deviceId), .init("audioCodec", "aac"),
        .init("audioBitRate", "128000"), .init("maxAudioChannels", "2"), .init("audioStreamIndex", String(audioStreamIndex)),
        .init("static", "false"), .init("allowAudioStreamCopy", "false")])
}
public func offlineAudioRequest(itemId: String, sourceId: String?, preset: OfflinePreset, deviceId: String, playSessionId: String) throws -> OfflineRequest {
    guard preset.isAudio else { throw OfflineError.invalidArgument("Not an audio preset: \(preset)") }
    var query: [OfflineQueryParameter] = sourceId.map { [.init("mediaSourceId", $0)] } ?? []
    query += [.init("playSessionId", playSessionId), .init("deviceId", deviceId), .init("audioCodec", "aac"),
              .init("audioBitRate", preset == .audio128 ? "128000" : "256000"), .init("maxAudioChannels", "2"),
              .init("static", "false"), .init("allowAudioStreamCopy", "false")]
    return OfflineRequest(path: "/Audio/\(itemId)/stream.m4a", query: query)
}
public func offlineSubtitleRequest(itemId: String, sourceId: String, streamIndex: Int) -> OfflineRequest {
    OfflineRequest(path: "/Videos/\(itemId)/\(sourceId)/Subtitles/\(streamIndex)/0/Stream.vtt", query: [])
}
public func offlineCleanupRequest(deviceId: String, playSessionId: String) -> OfflineRequest {
    OfflineRequest(path: "/Videos/ActiveEncodings", query: [.init("deviceId", deviceId), .init("playSessionId", playSessionId)], method: "DELETE")
}
