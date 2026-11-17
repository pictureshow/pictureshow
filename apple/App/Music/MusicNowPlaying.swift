// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import Foundation
import MediaPlayer
import PictureshowCore
#if canImport(UIKit)
import UIKit
#endif

/// Lock screen, Control Center, headset and Siri Remote controls for music (MUS-05, MUS-PD02). Every command goes
/// through the host's own functions, so it takes the same road as the on-screen buttons and acts once.
@MainActor final class MusicNowPlaying {
    weak var host: MusicHost?
    private var removals: [() -> Void] = []
    private var lastRefresh = Date.distantPast
    private var artworkURL: URL?
    private var artwork: MPMediaItemArtwork?
    private(set) var attached = false

    func attach() {
        guard !attached else { return }
        attached = true
        let center = MPRemoteCommandCenter.shared()
        func on(_ command: MPRemoteCommand, _ action: @escaping @MainActor (MusicHost, MPRemoteCommandEvent) -> Void) {
            command.isEnabled = true
            let token = command.addTarget { [weak self] event in
                MainActor.assumeIsolated {
                    guard let host = self?.host, host.hasQueue else { return .noActionableNowPlayingItem }
                    action(host, event); return .success
                }
            }
            removals.append { command.removeTarget(token); command.isEnabled = false }
        }
        on(center.playCommand) { host, _ in host.play() }
        on(center.pauseCommand) { host, _ in host.pause() }
        on(center.togglePlayPauseCommand) { host, _ in host.toggle() }
        on(center.stopCommand) { host, _ in host.stop() }
        on(center.nextTrackCommand) { host, _ in host.next() }
        on(center.previousTrackCommand) { host, _ in host.previous() }
        on(center.changePlaybackPositionCommand) { host, event in
            if let e = event as? MPChangePlaybackPositionCommandEvent { host.seek(to: e.positionTime) }
        }
        // Music moves by track: the video player's skip buttons would hide Next and Previous on the lock screen.
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
        refresh(force: true)
    }

    func detach() {
        guard attached else { return }
        attached = false
        removals.forEach { $0() }
        removals = []
        artwork = nil
        artworkURL = nil
        // No stale title or art stays for the next viewer (MUS-05).
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        #if os(iOS)
        MPNowPlayingInfoCenter.default().playbackState = .stopped
        #endif
    }

    /// Title, artist, album, art, place and rate. Called on every change and each second while playing.
    func refresh(force: Bool = false) {
        guard attached, let host, let card = host.currentCard else { return }
        guard force || Date().timeIntervalSince(lastRefresh) >= 1 else { return }
        lastRefresh = Date()
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: card.title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: host.positionSeconds,
            MPNowPlayingInfoPropertyPlaybackRate: host.isPlaying ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyDefaultPlaybackRate: 1.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
            MPNowPlayingInfoPropertyPlaybackQueueCount: host.order.count,
            MPNowPlayingInfoPropertyPlaybackQueueIndex: host.order.firstIndex(of: host.current ?? "") ?? 0,
        ]
        if let artist = card.artist ?? card.albumArtist { info[MPMediaItemPropertyArtist] = artist }
        if let album = card.album { info[MPMediaItemPropertyAlbumTitle] = album }
        if let duration = host.durationSeconds { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let artwork, artworkURL == card.art { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        #if os(iOS)
        MPNowPlayingInfoCenter.default().playbackState = host.isPlaying ? .playing : .paused
        #endif
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = host.hasNext
        loadArtwork(card)
    }

    private func loadArtwork(_ card: MusicCard) {
        guard let url = card.art, url != artworkURL, let host, let account = host.account, let client = host.client?(account) else { return }
        artworkURL = url
        artwork = nil
        Task { [weak self] in
            guard let image = await ArtworkLoader.shared.image(url, account: account, client: client) else { return }
            guard let self, self.artworkURL == url, self.host?.account?.key == account.key else { return }
            self.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self.refresh(force: true)
        }
    }
}

/// The playback audio session music needs, and the OS events that pause and resume it (MUS-04).
@MainActor enum MusicAudioSession {
    private static var observers: [NSObjectProtocol] = []
    static weak var host: MusicHost?

    static func activate() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, policy: .longFormAudio)
        try? session.setActive(true)
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
                let options = AVAudioSession.InterruptionOptions(rawValue: note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                MainActor.assumeIsolated {
                    guard let type else { return }
                    host?.interruption(began: type == .began, shouldResume: options.contains(.shouldResume))
                }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { note in
                let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt).flatMap(AVAudioSession.RouteChangeReason.init)
                MainActor.assumeIsolated { if reason == .oldDeviceUnavailable { host?.outputLost() } }
            },
        ]
    }

    /// Music is done: video gets its own movie-playback session back, and other apps may resume.
    static func deactivate() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        let session = AVAudioSession.sharedInstance()
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        #if os(iOS)
        try? session.setCategory(.playback, mode: .moviePlayback)
        #endif
    }

    /// The kind of output the music goes to, for the lossless label (no lossless claim over Bluetooth or AirPlay).
    static func route() -> String {
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs.map(\.portType)
        if outputs.contains(where: { [.bluetoothA2DP, .bluetoothLE, .bluetoothHFP].contains($0) }) { return "bluetooth" }
        if outputs.contains(.airPlay) { return "remote" }
        if outputs.contains(where: { [.usbAudio].contains($0) }) { return "usb" }
        if outputs.contains(where: { [.headphones].contains($0) }) { return "wired" }
        return "device"
    }
}
