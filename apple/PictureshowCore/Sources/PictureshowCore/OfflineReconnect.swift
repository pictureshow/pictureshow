// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation

extension OfflineEntry {
    public func undownloadedTracks(type: String) -> [OfflineTrack] {
        tracks.filter { $0.type == type && trackSwitch(type: type, serverIndex: $0.serverIndex) == "reconnect" }.sorted { $0.serverIndex < $1.serverIndex }
    }
}
public func offlineReconnectDecision(trackSwitch: String, network: String, facts: OfflineReconnectFacts) -> OfflineReconnectDecision {
    let outcome: String
    if trackSwitch == "local" { outcome = "local" }
    else if network == "none" { outcome = "no-connection" }
    else if facts.server == "unreachable" { outcome = "server-unreachable" }
    else if facts.server == "signed-out" { outcome = "signed-out" }
    else if facts.server != "ok" { outcome = "checking" }
    else if facts.playbackAllowed == false { outcome = "not-allowed" }
    else if facts.trackOnServer == false { outcome = "track-gone" }
    else if facts.playbackAllowed == nil || facts.trackOnServer == nil { outcome = "checking" }
    else { outcome = "offer" }
    return OfflineReconnectDecision(outcome: outcome, metered: outcome == "offer" && network == "metered")
}
public func offlineTrackOnServer(track: OfflineTrack, sourceId: String?, sources: [OfflineServerSource]) -> Bool {
    guard let source = sourceId == nil ? sources.first : sources.first(where: { $0.id == sourceId }) else { return false }
    return source.tracks.contains { candidate in
        candidate.index == track.serverIndex && candidate.type.lowercased() == track.type.lowercased() &&
            (candidate.language == nil || track.language == nil || candidate.language?.lowercased() == track.language?.lowercased())
    }
}
public func offlineHandoffSelection(type: String, index: Int, currentAudio: Int?, currentSubtitle: Int?) -> OfflineHandoffSelection {
    OfflineHandoffSelection(audio: type == "audio" ? index : currentAudio.flatMap { $0 >= 0 ? $0 : nil },
                            subtitle: type == "subtitle" ? index : currentSubtitle)
}
public func offlineReadyEntry(entries: [OfflineEntry], itemId: String, preset: OfflinePreset) -> OfflineEntry? {
    // Keep the first entry on a tie, as Kotlin minWithOrNull does.
    entries.filter { $0.itemId == itemId && $0.job.phase == .ready }.reduce(nil) { best, entry in
        guard let best else { return entry }
        if (entry.preset == preset && best.preset != preset) ||
            ((entry.preset == preset) == (best.preset == preset) && entry.createdSequence < best.createdSequence) { return entry }
        return best
    }
}
public func offlineNextEpisode(entries: [OfflineEntry], current: OfflineEntry, serverAnswered: Bool, serverNext: String?) -> OfflineEntry? {
    guard current.kind == .episode else { return nil }
    if serverAnswered { return serverNext.flatMap { offlineReadyEntry(entries: entries, itemId: $0, preset: current.preset) } }
    guard let series = current.labels.seriesId, let season = current.labels.seasonNumber, let episode = current.labels.episodeNumber,
          let next = entries.first(where: { $0.kind == .episode && $0.labels.seriesId == series && $0.labels.seasonNumber == season && $0.labels.episodeNumber == episode &+ 1 }) else { return nil }
    return offlineReadyEntry(entries: entries, itemId: next.itemId, preset: current.preset)
}
