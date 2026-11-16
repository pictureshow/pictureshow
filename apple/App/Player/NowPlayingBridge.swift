// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import MediaPlayer
import PictureshowCore
#if canImport(UIKit)
import UIKit
#endif

/// Lock screen, Control Center, headphone and AirPlay-receiver controls for the viewing on iPhone and iPad (CAST-07).
///
/// The commands call the same controller methods as the on-screen buttons, so progress reports and the remote target
/// see them too. On Apple TV, AVPlayerViewController publishes Now Playing itself, so this stays out of its way.
@MainActor
final class NowPlayingBridge {
    #if os(iOS)
    private weak var controller: PlaybackController?
    private var removals: [() -> Void] = []
    private var lastRefresh = Date.distantPast
    private var artworkItem: String?
    private var artwork: MPMediaItemArtwork?
    #endif

    func attach(_ controller: PlaybackController) {
        #if os(iOS)
        self.controller = controller
        let center = MPRemoteCommandCenter.shared()
        func on(_ command: MPRemoteCommand, _ action: @escaping @MainActor (PlaybackController, MPRemoteCommandEvent) -> Void) {
            command.isEnabled = true
            let token = command.addTarget { [weak self] event in
                MainActor.assumeIsolated {
                    guard let c = self?.controller, !c.finished else { return .noActionableNowPlayingItem }
                    action(c, event); return .success
                }
            }
            removals.append { command.removeTarget(token) }
        }
        on(center.playCommand) { c, _ in c.player.play() }
        on(center.pauseCommand) { c, _ in c.player.pause() }
        on(center.togglePlayPauseCommand) { c, _ in c.togglePlay() }
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        on(center.skipForwardCommand) { c, _ in c.skip(by: 10) }
        on(center.skipBackwardCommand) { c, _ in c.skip(by: -10) }
        on(center.changePlaybackPositionCommand) { c, event in
            if let e = event as? MPChangePlaybackPositionCommandEvent { c.seek(to: e.positionTime) }
        }
        refresh(force: true)
        #endif
    }

    func detach() {
        #if os(iOS)
        removals.forEach { $0() }
        removals = []
        controller = nil
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        #endif
    }

    /// Publishes title, place and rate. Called every tick; writes at most once a second unless something changed.
    func refresh(force: Bool = false) {
        #if os(iOS)
        guard let c = controller, !c.finished else { return }
        guard force || Date().timeIntervalSince(lastRefresh) >= 1 else { return }
        lastRefresh = Date()
        let item = c.item
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: item.name,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: c.currentSeconds,
            MPNowPlayingInfoPropertyPlaybackRate: c.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue,
        ]
        if c.duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = c.duration }
        if let series = item.seriesName { info[MPMediaItemPropertyArtist] = series }
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        loadArtwork(for: c)
        #endif
    }

    #if os(iOS)
    private func loadArtwork(for c: PlaybackController) {
        guard artworkItem != c.item.id, c.local == nil, let url = c.client.imageURL(account: c.account, item: c.item, maxWidth: 600) else { return }
        artworkItem = c.item.id
        let client = c.client, account = c.account
        Task { [weak self] in
            guard let data = try? await client.imageData(account: account, url: url), let image = UIImage(data: data) else { return }
            self?.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self?.refresh(force: true)
        }
    }
    #endif
}
