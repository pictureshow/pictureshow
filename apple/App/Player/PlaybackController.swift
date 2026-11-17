// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import AVFoundation
import Combine
import OSLog
import SwiftUI
import PictureshowCore
import PictureshowTransport

private let log = Logger(subsystem: "app.pictureshow", category: "Playback")

/// Drives one viewing: negotiates the stream with the server, plays it in AVPlayer, reports progress,
/// handles track changes, segment skipping and the next episode. Platform views only draw it.
@MainActor final class PlaybackController: ObservableObject {
    // What the views draw.
    @Published private(set) var item: MediaItem
    @Published private(set) var decision: PlayDecision?
    @Published private(set) var isPlaying = false
    @Published private(set) var isBuffering = true
    /// SYN-10: the viewer left the group to use AirPlay; the route picker opens once.
    @Published var routePickerPending = false
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var error: String?
    @Published private(set) var notice: ConversionNotice?
    @Published var noticeDismissed = false
    @Published private(set) var skippable: MediaSegment?
    @Published private(set) var nextEpisode: MediaItem?
    /// Seconds until the next episode starts on its own; nil when the card isn't showing.
    @Published private(set) var nextCountdown: Int?
    /// PLAY-30: the next-episode card asks "Are you still watching?" and nothing plays until the viewer answers.
    @Published private(set) var askingStillWatching = false
    @Published private(set) var cue: String?
    @Published private(set) var finished = false
    /// The AirPlay receiver's name while the picture plays there (CAST-07); nil while it plays here.
    @Published private(set) var externalRoute: String?
    /// Live TV (LIVE-04/05): the measured window, pause/expiry and the programme on air; unused for other items.
    @Published private(set) var live = LivePlayerState()
    /// The current item's display size (pixel aspect applied); zero until the first frame's size is known.
    @Published internal(set) var videoSize: CGSize = .zero
    fileprivate var liveTask: Task<Void, Never>?
    /// When the current live item was opened, and how many times it has been reopened without becoming ready.
    fileprivate var liveOpened = Date()
    fileprivate var liveReopens = 0
    /// A channel plays live; its timeline comes from the player, never a duration.
    var isLive: Bool { item.type == "TvChannel" || decision?.isLive == true }

    let player = AVPlayer()
    let app: AppModel
    let account: Account
    let client: JellyfinClient
    private let fromStart: Bool
    private let requestedStartTicks: Int64?
    /// Set when this viewing plays a download from this device: nothing is asked of the server, and the
    /// place goes to the download's outbox to be brought back later (OFF-07, OFF-11).
    private(set) var local: LocalMedia?
    private var localAudio: Int?
    private var localSubtitle = -1

    private var segments: [MediaSegment] = []
    private var autoSkipped: Set<String> = []
    private var cues: [TextSubtitleCue] = []
    private var cueTask: Task<Void, Never>?
    private var timeObserver: Any?
    private var observations: [NSKeyValueObservation] = []
    private var endObserver: NSObjectProtocol?
    private var lastReport = Date.distantPast
    private var started = false
    private var pendingSeek: Double?
    private var generation = 0
    private var triedTranscodeFallback = false
    private var nextCardDismissed = false
    /// The viewing played into its end and stays there on the next card (countdown Off or "still watching?").
    private var heldAtEnd = false
    private var countdownTask: Task<Void, Never>?

    // Watch together (SYN-03..10).
    /// The group entry this viewing was opened for; nil for a solo viewing.
    let sync: SyncStamp?
    /// What the shared engine reads and moves; nil for a solo viewing.
    private(set) var syncRenderer: SyncRendererBox?
    /// The play state the group last asked for. A native rate change that disagrees came from the viewer.
    private var syncExpectPlaying = false
    /// Seeks this controller started; a time jump while any is in flight is ours, not the viewer's.
    private var ownSeeks = 0
    private var lastOwnSeek = Date.distantPast
    private var seekGate = SyncSeekGate()
    private var replacedBySync = false
    private var rateObserver: NSObjectProtocol?
    private var jumpObserver: NSObjectProtocol?
    /// Where the last play-state change came from, so a group's barrier pause doesn't reveal the controls.
    private(set) var lastControlOrigin: SyncControlOrigin = .native

    init(app: AppModel, request: PlayerRequest) {
        self.app = app
        self.account = request.account
        self.client = app.client(for: request.account)
        self.item = request.item
        self.fromStart = request.fromStart
        self.requestedStartTicks = request.startTicks
        self.local = request.local
        self.sync = request.local == nil ? request.sync : nil
        // SYN-10: AirPlay can't carry a group's timeline at launch; the viewer leaves the group to use it.
        player.allowsExternalPlayback = sync == nil
        player.appliesMediaSelectionCriteriaAutomatically = false
        attachObservers()
        app.activeController = self
        nowPlaying.attach(self)
        if sync != nil {
            let box = SyncRendererBox(self)
            syncRenderer = box
            app.syncPlay.engine.bind(box)
        }
    }

    /// In a group right now: transport controls become group requests and the server owns continuation.
    var grouped: Bool { syncRenderer != nil && !finished && app.syncPlay.engine.participating && app.syncPlay.engine.isBound(syncRenderer) }

    /// Lock screen, Control Center and AirPlay receiver controls (CAST-07): the same viewing, the same reports.
    let nowPlaying = NowPlayingBridge()

    /// Sends the server the current place right away, so a remote controller sees a command take effect (REM-04).
    func reportNow() { reportProgress(force: true) }

    // MARK: Tracks

    var source: MediaSource? { decision?.source ?? item.mediaSources.first }
    var audioStreams: [MediaStream] {
        if let local { return LocalTracks.streams(local, type: "audio") }
        return source?.mediaStreams.filter { $0.type == "Audio" } ?? []
    }
    var subtitleStreams: [MediaStream] {
        if let local { return LocalTracks.streams(local, type: "subtitle") }
        return source?.mediaStreams.filter { $0.type == "Subtitle" } ?? []
    }
    var selectedAudio: Int? { local != nil ? localAudio : (decision?.audioIndex ?? source?.defaultAudioStreamIndex) }
    var selectedSubtitle: Int { local != nil ? localSubtitle : (decision?.subtitleIndex ?? -1) }

    /// Labels come from the whole chooser list, so untitled tracks get their menu number and duplicates stay distinct.
    func label(_ stream: MediaStream) -> String {
        let list = stream.type == "Audio" ? audioStreams : subtitleStreams
        guard let position = list.firstIndex(where: { $0.index == stream.index }) else { return PlaybackTrackLabels.label(stream, showDefault: false) }
        return PlaybackTrackLabels.labels(list)[position]
    }

    // MARK: Lifecycle

    func start() async {
        guard !started else { return }
        started = true
        if let local { await startLocal(local); return }
        do {
            let full = try await client.item(account: account, id: item.id)
            item = full
            if full.type == "TvChannel" {
                // LIVE-04: always the live point; no resume, segments or next episode.
                try await load(startTicks: 0, audio: full.mediaSources.first?.defaultAudioStreamIndex, subtitle: -1)
                startLiveTick()
                return
            }
            async let segments = try? client.segments(account: account, itemID: full.id)
            async let next = try? client.nextEpisode(account: account, item: full)
            let resumeTicks = requestedStartTicks ?? (fromStart ? 0 : full.userData.playbackPositionTicks)
            let defaultSubtitle = full.mediaSources.first.flatMap(Captions.usableDefaultIndex)
            try await load(startTicks: resumeTicks, audio: full.mediaSources.first?.defaultAudioStreamIndex, subtitle: defaultSubtitle ?? -1)
            self.segments = await segments ?? []
            self.nextEpisode = await next ?? nil
        } catch is CancellationError {
        } catch ClientError.invalidCredentials {
            app.expire(account); close()
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Negotiates and opens the stream. Text subtitles are always fetched separately and drawn by the app, so
    /// choosing one never makes the server convert the video; picture subtitles have to be burned in by the server.
    private func load(startTicks: Int64, audio: Int?, subtitle: Int, forceTranscode: Bool = false) async throws {
        generation += 1
        let mine = generation
        isBuffering = true
        let stream = source?.mediaStreams.first { $0.type == "Subtitle" && $0.index == subtitle }
        let textSubtitle = stream.map(Captions.isText) ?? false
        // A channel's listed source id is the channel itself, and a recording still being written lists its file while
        // PlaybackInfo offers a growing stream under another id; pinning either is refused (NoCompatibleStream, OBSERVED).
        // Neither pins a listed source before the server has named the one it plays.
        let pinned: String? = switch item.type {
        case "TvChannel": nil
        case "Recording": decision?.source.id
        default: source?.id ?? item.mediaSources.first?.id
        }
        var next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks, audioIndex: audio, subtitleIndex: subtitle,
                                            forceTranscode: forceTranscode, mediaSourceID: pinned,
                                            preferences: app.preferences.playback, externalTextSubtitle: textSubtitle)
        // The server may pick a default subtitle we didn't know about until now.
        if !textSubtitle, let chosen = next.subtitleIndex, chosen >= 0,
           let picked = next.source.mediaStreams.first(where: { $0.type == "Subtitle" && $0.index == chosen }), Captions.isText(picked) {
            try? await client.stopEncoding(account: account, decision: next)
            next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks, audioIndex: audio, subtitleIndex: chosen,
                                            forceTranscode: forceTranscode, mediaSourceID: next.source.id, preferences: app.preferences.playback, externalTextSubtitle: true)
        }
        guard mine == generation else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        let previous = decision
        decision = next
        log.notice("Negotiated item=\(next.itemID, privacy: .public) method=\(next.method.rawValue, privacy: .public) session=\(next.playSessionId ?? "none", privacy: .public)")
        notice = app.preferences.playback.showConversionNotice ? ConversionNotice.of(next, subtitleIndex: next.subtitleIndex ?? -1, device: DeviceIdentity.name) : nil
        noticeDismissed = false
        // An opened tuner source lists the headers the server sends upstream (an M3U tuner's User-Agent); the player
        // only ever fetches the server's own same-origin URL, so those never apply to it.
        let url = try ServerPlaybackURL.make(decisionURL: next.url, server: account.server.url, token: account.accessToken,
                                             requiredHeaders: next.isLive ? [:] : next.headers)
        let playerItem = AVPlayerItem(url: url)
        playerItem.externalMetadata = metadata()
        applySubtitleStyle(playerItem)
        pendingSeek = next.initialSeekSeconds > 1 ? next.initialSeekSeconds : nil
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)
        liveOpened = Date(); liveReopens = 0
        // A live remux names no audio track (AudioStreamIndex=-1) yet copies the tuner's audio (OBSERVED), so only a
        // recorded item's "no audio" choice mutes.
        player.isMuted = next.audioIndex == -1 && !next.isLive
        // A group viewing starts paused; the server's command starts it on every device at once.
        if grouped { syncExpectPlaying = false; player.pause() } else { player.play() }
        if let previous, previous.playSessionId != next.playSessionId {
            try? await client.stopEncoding(account: account, decision: previous)
        }
        if let previous, let owned = previous.ownedLiveStreamId, owned != next.ownedLiveStreamId {
            try? await client.closeLiveStream(account: account, liveStreamID: owned)
        }
        try? await client.reportStart(account: account, decision: next, positionTicks: startTicks)
        lastReport = Date()
        loadCues(for: next)
    }

    /// Ends the viewing: reports where the viewer stopped, tells the server to stop converting, refreshes the catalog.
    /// In a group the device stays a member (SYN-08) unless `reason` leaves.
    func close(reason: SyncCloseReason = .CLOSE, reportPlayed: Bool = false) {
        guard !finished else { return }
        if let box = syncRenderer {
            if replacedBySync { app.syncPlay.engine.unbind(box) } else { app.syncPlay.engine.closeViewing(box, reason: reason) }
            app.syncPlay.changed()
        }
        finished = true
        generation += 1
        let decision = self.decision
        let seconds = currentSeconds
        let durationMs = Int64(duration * 1000)
        player.pause()
        player.replaceCurrentItem(with: nil)
        cueTask?.cancel(); countdownTask?.cancel(); liveTask?.cancel()
        detachObservers()
        nowPlaying.detach()
        if app.activeController === self { app.activeController = nil }
        let account = self.account, client = self.client, app = self.app
        if let local {
            let ms = PlaybackEndPosition.completed(Int64(seconds * 1000), durationMs: durationMs)
            if durationMs > 0 && ms == durationMs { app.downloads.recordCompleted(local.accountKey, itemID: local.entry.itemId) }
            else { app.downloads.recordViewing(local.accountKey, itemID: local.entry.itemId, positionTicks: ms * 10_000) }
            app.player = nil
            Task { await app.downloads.sync(local.accountKey); app.catalogChanged() }
            return
        }
        let itemID = item.id
        Task {
            if let decision {
                let ms = PlaybackEndPosition.completed(Int64(seconds * 1000), durationMs: durationMs)
                try? await client.reportStopped(account: account, decision: decision, positionTicks: ms * 10_000)
                // A grouped natural end reports Played once, after Stopped (sync-completion-reporting).
                if reportPlayed { _ = try? await client.played(account: account, itemID: itemID, value: true) }
                try? await client.stopEncoding(account: account, decision: decision)
                // LIVE-04: the stopped report carried LiveStreamId; then the stream this client opened is closed.
                if let owned = decision.ownedLiveStreamId { try? await client.closeLiveStream(account: account, liveStreamID: owned) }
            }
            app.downloads.onlineViewingStopped(account: account, itemID: itemID)
            app.catalogChanged()
        }
        app.player = nil
    }

    // MARK: Controls

    var currentSeconds: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? t : position
    }

    /// SYN-10: the server accepted the Leave the viewer chose for AirPlay; external playback is allowed again.
    func airPlayAfterLeaving() {
        guard !finished else { return }
        player.allowsExternalPlayback = true
        routePickerPending = true
    }

    /// The group's newer entry replaces this viewing; that isn't the viewer closing playback.
    func closeReplacedBySync() { replacedBySync = true; close() }

    /// Native play intent (Jellyfin's IsPlaying): set while buffering too.
    var isUnpaused: Bool { player.rate != 0 }

    func togglePlay() { isUnpaused || isPlaying ? userPause() : userPlay() }

    /// The viewer's own Play and Pause. In a group they ask the server, which moves every device (SYN-03).
    func userPlay(origin: SyncControlOrigin = .viewer) {
        lastControlOrigin = origin
        // LIVE-05: a paused buffer that slid out of the window can only resume at the live point.
        if isLive, live.expired { goLive(); return }
        if grouped { app.syncPlay.engine.requestUnpause(); return }
        player.play()
    }

    func userPause(origin: SyncControlOrigin = .viewer) {
        lastControlOrigin = origin
        if grouped { app.syncPlay.engine.requestPause(); return }
        player.pause()
    }

    func seek(to seconds: Double, origin: SyncControlOrigin = .viewer) {
        if isLive {
            // LIVE-05: only inside the measured window and never past the live point.
            if let target = live.clampedSeek(seconds), abs(target - currentSeconds) > 0.25 { nativeSeek(target) }
            return
        }
        let target = max(0, duration > 0 ? min(seconds, duration - 1) : seconds)
        let ready = player.currentItem?.status == .readyToPlay && pendingSeek == nil
        switch seekGate.seek(millis: Int64(target * 1000), origin: origin, grouped: grouped, ready: ready || !grouped) {
        case let .native(ms)?: nativeSeek(Double(ms) / 1000)
        case let .group(ms)?: app.syncPlay.engine.requestSeek(ticks: (decision?.serverPositionTicks(playerSeconds: Double(ms) / 1000)) ?? ms * 10_000)
        case nil: break
        }
    }

    fileprivate func nativeSeek(_ target: Double, then: (() -> Void)? = nil) {
        position = target
        heldAtEnd = false
        ownSeeks += 1
        lastOwnSeek = Date()
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.ownSeeks = max(0, self.ownSeeks - 1)
                self.lastOwnSeek = Date()
                then?()
                self.reportProgress(force: true)
                self.syncNotify()
            }
        }
    }

    func skip(by seconds: Double) { seek(to: currentSeconds + seconds) }

    func skipSegment() {
        guard let segment = skippable else { return }
        seek(to: Double(segment.endMs) / 1000)
        skippable = nil
    }

    func selectAudio(_ index: Int) {
        guard index != selectedAudio else { return }
        if local != nil { selectLocalAudio(index); return }
        Task { await renegotiate(audio: index, subtitle: selectedSubtitle) }
    }

    func selectSubtitle(_ index: Int) {
        guard index != selectedSubtitle else { return }
        if let local { localSubtitle = index; loadLocalCues(local); return }
        guard let decision else { return }
        let current = subtitleStreams.first { $0.index == selectedSubtitle }
        let next = subtitleStreams.first { $0.index == index }
        let currentLocal = current.map(Captions.isText) ?? true
        let nextLocal = next.map(Captions.isText) ?? true
        // Moving between text subtitles (or turning them off) needs no new stream: the video doesn't change.
        if currentLocal && nextLocal {
            var updated = decision
            updated.subtitleIndex = index
            self.decision = updated
            loadCues(for: updated)
            reportProgress(force: true)
            return
        }
        Task { await renegotiate(audio: selectedAudio, subtitle: index) }
    }

    private func renegotiate(audio: Int?, subtitle: Int) async {
        let ticks = Ticks.from(seconds: currentSeconds)
        do { try await load(startTicks: ticks, audio: audio, subtitle: subtitle) }
        catch is CancellationError {}
        catch { self.error = error.localizedDescription }
    }

    /// SUB-DL-04: a subtitle downloaded mid-viewing. The server renumbers streams when it adds an external file, so
    /// the controller takes the refreshed list and reopens at the current place with the remapped audio and new track.
    func adoptDownloadedSubtitle(_ adopted: AdoptedSubtitle, streams: [MediaStream]) {
        guard local == nil, var updated = decision else { return }
        updated.source.mediaStreams = streams
        decision = updated
        Task { await renegotiate(audio: adopted.audioIndex >= 0 ? adopted.audioIndex : selectedAudio, subtitle: adopted.subtitleIndex) }
    }

    /// What the subtitle panel searches for from the player: only server playback, never a download or live channel.
    var subtitleDownloadTarget: RemoteSubtitleTarget {
        RemoteSubtitleTarget(itemID: item.id, itemType: item.type, playable: local == nil && !["TvChannel", "Program"].contains(item.type) && decision != nil,
                             mediaSourceID: source?.id, streams: source?.mediaStreams ?? [], selectedAudio: selectedAudio ?? -1)
    }

    func retry() {
        error = nil
        Task { await renegotiate(audio: selectedAudio, subtitle: selectedSubtitle) }
    }

    func playNext() {
        if local != nil { playNextLocal(); return }
        if grouped { app.syncPlay.engine.requestNext(); return }
        guard let next = nextEpisode else { return }
        countdownTask?.cancel()
        let decision = self.decision
        let seconds = currentSeconds, durationMs = Int64(duration * 1000)
        let account = self.account, client = self.client
        Task {
            if let decision {
                let ms = PlaybackEndPosition.completed(Int64(seconds * 1000), durationMs: durationMs)
                try? await client.reportStopped(account: account, decision: decision, positionTicks: ms * 10_000)
                try? await client.stopEncoding(account: account, decision: decision)
                // LIVE-04: the stopped report carried LiveStreamId; then the stream this client opened is closed.
                if let owned = decision.ownedLiveStreamId { try? await client.closeLiveStream(account: account, liveStreamID: owned) }
            }
        }
        self.decision = nil
        item = next
        nextEpisode = nil; nextCountdown = nil; nextCardDismissed = false; askingStillWatching = false; heldAtEnd = false
        segments = []; autoSkipped = []; skippable = nil; cues = []; cue = nil
        position = 0; duration = 0; triedTranscodeFallback = false
        started = false
        app.catalogChanged()
        Task {
            started = true
            do {
                let full = try await client.item(account: account, id: next.id)
                item = full
                async let segments = try? client.segments(account: account, itemID: full.id)
                async let after = try? client.nextEpisode(account: account, item: full)
                let defaultSubtitle = full.mediaSources.first.flatMap(Captions.usableDefaultIndex)
                try await load(startTicks: full.userData.playbackPositionTicks, audio: full.mediaSources.first?.defaultAudioStreamIndex, subtitle: defaultSubtitle ?? -1)
                self.segments = await segments ?? []
                self.nextEpisode = await after ?? nil
            } catch is CancellationError {
            } catch { self.error = error.localizedDescription }
        }
    }

    /// PLAY-30: the viewer answered "Keep watching".
    func keepWatching() { ViewerInput.shared.record(); playNext() }

    func dismissNextCard() {
        nextCardDismissed = true; nextCountdown = nil; countdownTask?.cancel()
        // PLAY-17: Hide after the card was left waiting at the very end exits, as the end would have.
        if heldAtEnd { close() }
    }

    // MARK: Observation

    private func attachObservers() {
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time.seconds); self?.nowPlaying.refresh() }
        }
        observations.append(player.observe(\.isExternalPlaybackActive, options: [.initial, .new]) { [weak self] player, _ in
            let active = player.isExternalPlaybackActive
            Task { @MainActor in self?.externalRouteChanged(active) }
        })
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in self?.statusChanged(status) }
        })
        rateObserver = NotificationCenter.default.addObserver(forName: AVPlayer.rateDidChangeNotification, object: player, queue: .main) { [weak self] note in
            let reason = note.userInfo?[AVPlayer.rateDidChangeReasonKey] as? AVPlayer.RateDidChangeReason
            MainActor.assumeIsolated { self?.rateChanged(reason) }
        }
    }

    /// In a group, a play or pause the system player made on its own controls (Apple TV's transport bar, a headset
    /// button) is the viewer asking the group: the local player goes back to the group's state and the request goes out.
    private func rateChanged(_ reason: AVPlayer.RateDidChangeReason?) {
        guard grouped, reason == .setRateCalled else { syncNotify(); return }
        let unpaused = player.rate != 0
        defer { syncNotify() }
        guard unpaused != syncExpectPlaying else { return }
        if duration > 0, currentSeconds >= duration - 0.5 { return }
        lastControlOrigin = .viewer
        if unpaused {
            player.pause()
            app.syncPlay.engine.requestUnpause()
        } else {
            app.syncPlay.engine.requestPause()
        }
    }

    /// A jump this controller didn't start, in a group, is the viewer scrubbing on the system controls: ask the group.
    private func timeJumped() {
        guard grouped, ownSeeks == 0, Date().timeIntervalSince(lastOwnSeek) > 0.5, player.currentItem?.status == .readyToPlay else { return }
        let ticks = decision?.serverPositionTicks(playerSeconds: currentSeconds) ?? Ticks.from(seconds: currentSeconds)
        app.syncPlay.engine.requestSeek(ticks: ticks)
    }

    private func externalRouteChanged(_ active: Bool) {
        guard active else { externalRoute = nil; return }
        // SYN-10: an external route can't follow the group's timeline; leave before it takes the picture.
        if let box = syncRenderer, grouped { app.syncPlay.engine.closeViewing(box, reason: .EXTERNAL_ROUTE); app.syncPlay.changed() }
        let outputs = AVAudioSession.sharedInstance().currentRoute.outputs
        externalRoute = (outputs.first { $0.portType == .airPlay } ?? outputs.first)?.portName ?? "AirPlay"
    }

    private func detachObservers() {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.forEach { $0.invalidate() }
        observations = []
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
        if let rateObserver { NotificationCenter.default.removeObserver(rateObserver) }
        rateObserver = nil
        if let jumpObserver { NotificationCenter.default.removeObserver(jumpObserver) }
        jumpObserver = nil
    }

    private func observe(_ playerItem: AVPlayerItem) {
        videoSize = .zero
        observations.append(playerItem.observe(\.presentationSize, options: [.new]) { [weak self] item, _ in
            let size = item.presentationSize
            Task { @MainActor in self?.videoSize = size }
        })
        observations.append(playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            let message = item.error?.localizedDescription
            Task { @MainActor in self?.itemStatusChanged(status, message: message) }
        })
        observations.append(playerItem.observe(\.duration, options: [.new]) { [weak self] item, _ in
            let seconds = item.duration.seconds
            Task { @MainActor in if seconds.isFinite, seconds > 0 { self?.duration = seconds; self?.viewingChanged() } }
        })
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        let viewing = generation
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: playerItem, queue: .main) { [weak self, weak playerItem] _ in
            // The ended item and viewing are captured: a retired item's late end can't finish a replacement (SYN-09).
            MainActor.assumeIsolated {
                guard let self else { return }
                self.reachedEnd(current: !self.finished && viewing == self.generation && playerItem != nil && self.player.currentItem === playerItem)
            }
        }
        if let jumpObserver { NotificationCenter.default.removeObserver(jumpObserver) }
        jumpObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.timeJumpedNotification, object: playerItem, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.timeJumped() }
        }
        observations.append(playerItem.observe(\.isPlaybackLikelyToKeepUp, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.syncNotify() }
        })
    }

    private func itemStatusChanged(_ status: AVPlayerItem.Status, message: String?) {
        switch status {
        case .readyToPlay:
            if duration == 0, let ticks = item.runTimeTicks { duration = Ticks.seconds(ticks) }
            if let seek = pendingSeek {
                // The start position lands before the group sees this renderer as prepared.
                nativeSeek(seek) { [weak self] in self?.pendingSeek = nil }
            }
            switch seekGate.becameReady(grouped: grouped) {
            case let .native(ms)?: nativeSeek(Double(ms) / 1000)
            case let .group(ms)?: app.syncPlay.engine.requestSeek(ticks: decision?.serverPositionTicks(playerSeconds: Double(ms) / 1000) ?? ms * 10_000)
            case nil: break
            }
            syncNotify()
        case .failed:
            log.error("Item failed method=\(self.decision?.method.rawValue ?? "none", privacy: .public)")
            // A file the server said would play directly can still trip AVPlayer; ask the server to convert it instead.
            if decision?.method != .transcode, !triedTranscodeFallback {
                triedTranscodeFallback = true
                let ticks = Ticks.from(seconds: max(position, decision?.initialSeekSeconds ?? 0))
                Task {
                    do { try await load(startTicks: ticks, audio: selectedAudio, subtitle: selectedSubtitle, forceTranscode: true) }
                    catch is CancellationError {}
                    catch { self.error = error.localizedDescription }
                }
            } else {
                error = message ?? "This video couldn't be played."
                if let decision { Task { try? await client.reportStopped(account: account, decision: decision, positionTicks: Ticks.from(seconds: position), failed: true) } }
            }
        default: break
        }
    }

    private func statusChanged(_ status: AVPlayer.TimeControlStatus) {
        let playing = status == .playing
        isBuffering = status == .waitingToPlayAtSpecifiedRate
        if playing != isPlaying {
            isPlaying = playing
            if status != .waitingToPlayAtSpecifiedRate { reportProgress(force: true) }
            viewingChanged()
        }
        syncNotify()
    }

    /// Tells the group engine the renderer's facts may have changed (prepared, buffering, paused, moved).
    private func syncNotify() {
        guard let box = syncRenderer, !finished else { return }
        app.syncPlay.engine.rendererChanged()
        _ = box
    }

    /// Tells the remote target and the Now Playing card that the viewing moved on (ready, paused, playing).
    private func viewingChanged() {
        if app.activeController === self { app.remoteTarget.viewingChanged() }
        nowPlaying.refresh(force: true)
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite, !finished, pendingSeek == nil else { return }
        if isLive { position = seconds; sampleLive(); reportProgress(force: false); return }
        position = seconds
        let ms = Int64(seconds * 1000)
        if !cues.isEmpty { cue = TextSubtitles.text(cues: cues, positionMs: ms) } else if cue != nil { cue = nil }
        updateSegment(ms)
        updateNextCard(ms)
        reportProgress(force: false)
        if grouped { app.syncPlay.engine.tick() }
    }

    private func updateSegment(_ ms: Int64) {
        let modes = app.preferences.playback.segmentModes
        let active = PlaybackExtras.activeSegment(segments, positionMs: ms, modes: modes)
        // SYN-09: no independent automatic skip in a group; the Skip button still asks the group to seek.
        if let active, modes[active.type] == .automatic, !autoSkipped.contains(active.id), !grouped {
            autoSkipped.insert(active.id)
            seek(to: Double(active.endMs) / 1000)
            skippable = nil
            return
        }
        // Skipping credits is pointless when the next episode card already offers to move on.
        if active?.type == .outro, nextEpisode != nil { skippable = nil; return }
        if skippable?.id != active?.id { skippable = active }
    }

    private func updateNextCard(_ ms: Int64) {
        // SYN-09: the group's queue owns continuation; no local next-episode countdown.
        guard let _ = nextEpisode, !nextCardDismissed, duration > 0, !grouped else { return }
        let durationMs = Int64(duration * 1000)
        guard let trigger = PlaybackExtras.nextTrigger(durationMs: durationMs, segments: segments), ms >= trigger else {
            if nextCountdown != nil { nextCountdown = nil; countdownTask?.cancel() }
            return
        }
        guard nextCountdown == nil else { return }
        guard app.preferences.autoplayNextEpisode, ViewingSettings.shared.countdownSeconds > 0 else { nextCountdown = -1; return }
        if ViewerInput.shared.stillWatchingDue(policy: ViewingSettings.shared.stillWatching) {
            askingStillWatching = true; nextCountdown = -1; return
        }
        nextCountdown = ViewingSettings.shared.countdownSeconds
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            while let self, let left = self.nextCountdown, left > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                if self.isPlaying { self.nextCountdown = left - 1 }
            }
            if !Task.isCancelled, let self, self.nextCountdown == 0 { ViewerInput.shared.startedByItself(); self.playNext() }
        }
    }

    private func reachedEnd(current: Bool) {
        if let box = syncRenderer, grouped {
            // SYN-09: the server owns continuation. The last entry ends the viewing and the device stays in the group;
            // an obsolete decision does nothing. AVPlayer posts played-to-end only for playback that ran into the end.
            let plan = SyncVideoCompletion.plan(app.syncPlay.engine, ended: box, decisionCurrent: current, unpaused: true)
            if plan.exits { close(reason: .NATURAL_END, reportPlayed: plan.reports.contains(.played)) }
            return
        }
        guard current else { return }
        if let local {
            app.downloads.recordCompleted(local.accountKey, itemID: local.entry.itemId)
            // Read again at the end, so an episode that finished downloading during this one follows it.
            if app.preferences.autoplayNextEpisode, !nextCardDismissed, app.downloads.next(after: local) != nil { playNextLocal() } else { close() }
            return
        }
        let action = NaturalEndAction.decide(
            hasNext: nextEpisode != nil, cardDismissed: nextCardDismissed,
            automatic: app.preferences.autoplayNextEpisode && ViewingSettings.shared.countdownSeconds > 0,
            stillWatchingDue: askingStillWatching || ViewerInput.shared.stillWatchingDue(policy: ViewingSettings.shared.stillWatching))
        switch action {
        case .playNext:
            ViewerInput.shared.startedByItself()
            playNext()
        case .askStillWatching:
            // PLAY-30: an unanswered "still watching?" waits at the end; nothing starts by itself.
            askingStillWatching = true; nextCountdown = -1; countdownTask?.cancel(); heldAtEnd = true
        case .waitForPlayNow:
            // PLAY-30: with the countdown Off the card stays at the end and waits for Play now.
            nextCountdown = -1; countdownTask?.cancel(); heldAtEnd = true
        case .close:
            close()
        }
    }

    private func reportProgress(force: Bool) {
        if let local {
            guard !finished, force || Date().timeIntervalSince(lastReport) >= 10 else { return }
            lastReport = Date()
            app.downloads.recordViewing(local.accountKey, itemID: local.entry.itemId, positionTicks: Ticks.from(seconds: currentSeconds))
            return
        }
        guard let decision, !finished else { return }
        guard force || Date().timeIntervalSince(lastReport) >= 10 else { return }
        lastReport = Date()
        let ticks = decision.serverPositionTicks(playerSeconds: currentSeconds)
        let paused = !isPlaying
        let account = self.account, client = self.client
        Task { try? await client.reportProgress(account: account, decision: decision, positionTicks: ticks, paused: paused) }
    }

    // MARK: Subtitles drawn by the app

    private func loadCues(for decision: PlayDecision) {
        cueTask?.cancel()
        cues = []; cue = nil
        guard let index = decision.subtitleIndex, index >= 0,
              let stream = decision.source.mediaStreams.first(where: { $0.type == "Subtitle" && $0.index == index }), Captions.isText(stream),
              let sourceID = decision.source.id else { return }
        let account = self.account, itemID = item.id
        cueTask = Task { [weak self] in
            var failures = 0
            while !Task.isCancelled {
                do {
                    let reference = try ServerURLs.endpoint(account.server,
                        path: "Videos/\(ServerURLs.encode(itemID))/\(ServerURLs.encode(sourceID))/Subtitles/\(index)/0/Stream.vtt",
                        query: [URLQueryItem(name: "CopyTimestamps", value: "true")])
                    let url = try ServerPlaybackURL.make(decisionURL: reference, server: account.server.url, token: account.accessToken)
                    let (data, response) = try await URLSession.shared.data(from: url)
                    guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.badServerResponse) }
                    let parsed = try TextSubtitles.parse(data: data, format: "vtt")
                    guard !Task.isCancelled else { return }
                    self?.cues = parsed
                    return
                } catch is CancellationError { return }
                catch {
                    failures += 1
                    if failures >= 5 { return }
                    try? await Task.sleep(for: .milliseconds(ExternalSubtitleLoading.retryDelayMs(errorCount: failures)))
                }
            }
        }
    }

    var subtitleScale: Double { app.preferences.playback.subtitleSize.scale ?? 1 }
    var subtitleBackground: SubtitleBackground { app.preferences.playback.subtitleBackground }

    private func applySubtitleStyle(_ playerItem: AVPlayerItem) {
        var attributes: [String: Any] = [:]
        if let scale = app.preferences.playback.subtitleSize.scale { attributes[kCMTextMarkupAttribute_RelativeFontSize as String] = scale * 100 }
        if !attributes.isEmpty, let rule = AVTextStyleRule(textMarkupAttributes: attributes) { playerItem.textStyleRules = [rule] }
    }

    fileprivate func metadata() -> [AVMetadataItem] {
        func entry(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let item = AVMutableMetadataItem(); item.identifier = id; item.value = value as NSString; item.extendedLanguageTag = "und"; return item
        }
        if isLive {
            // LIVE-05: the programme on air leads; the channel and airing time are secondary.
            var items = [entry(.commonIdentifierTitle, live.program?.name ?? item.name)]
            items.append(entry(.iTunesMetadataTrackSubTitle, liveSubtitle ?? item.name))
            if let overview = live.program?.overview { items.append(entry(.commonIdentifierDescription, overview)) }
            return items
        }
        var items = [entry(.commonIdentifierTitle, item.type == "Episode" ? (item.seriesName ?? item.name) : item.name)]
        if item.type == "Episode" { items.append(entry(.iTunesMetadataTrackSubTitle, [Format.episode(item).nilIfBlank, item.name].compactMap { $0 }.joined(separator: " · "))) }
        if let overview = item.overview { items.append(entry(.commonIdentifierDescription, overview)) }
        return items
    }

    /// Title line for the player chrome.
    var title: String { isLive ? (live.program?.name ?? item.name) : item.type == "Episode" ? (item.seriesName ?? item.name) : item.name }
    var subtitle: String? { isLive ? liveSubtitle : item.type == "Episode" ? [Format.episode(item).nilIfBlank, item.name].compactMap { $0 }.joined(separator: " · ") : nil }

    var methodDescription: String {
        switch decision?.method {
        case .directPlay: return "Playing the original file"
        case .directStream: return "Repackaged by the server"
        case .transcode: return "Converted by the server"
        case nil: return ""
        }
    }
}

// MARK: - Playing a download

extension PlaybackController {
    fileprivate func startLocal(_ media: LocalMedia) async {
        let account = self.account
        let ticks = fromStart ? 0 : offlineDetailStartTicks(fromStart: false, outbox: app.downloads.outbox(account, media.entry.itemId),
                                                             lastKnown: app.downloads.lastKnown(account, media.entry.itemId), server: nil)
        localAudio = media.entry.tracks.first { $0.type == "audio" && $0.available && $0.isDefault }?.serverIndex
            ?? media.entry.tracks.first { $0.type == "audio" && $0.available }?.serverIndex
        localSubtitle = media.subtitles.first { sub in media.entry.tracks.contains { $0.serverIndex == sub.serverIndex && ($0.isDefault || $0.isForced) } }?.serverIndex ?? -1
        notice = nil
        let asset = AVURLAsset(url: media.url)
        let playerItem = AVPlayerItem(asset: asset)
        playerItem.externalMetadata = metadata()
        applySubtitleStyle(playerItem)
        let seconds = Ticks.seconds(ticks)
        pendingSeek = seconds > 1 ? seconds : nil
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)
        player.play()
        lastReport = Date()
        loadLocalCues(media)
        nextEpisode = app.downloads.next(after: media)?.entry.offlineItem
    }

    fileprivate func loadLocalCues(_ media: LocalMedia) {
        cueTask?.cancel()
        cues = []; cue = nil
        guard localSubtitle >= 0, let file = media.subtitles.first(where: { $0.serverIndex == localSubtitle }) else { return }
        cueTask = Task { [weak self] in
            guard let data = try? Data(contentsOf: file.url), let parsed = try? TextSubtitles.parse(data: data, format: "vtt"), !Task.isCancelled else { return }
            self?.cues = parsed
        }
    }

    /// An Original keeps its own audio tracks; the player's audible group lists them in the file's order.
    fileprivate func selectLocalAudio(_ index: Int) {
        guard let local, let playerItem = player.currentItem else { return }
        let order = local.entry.tracks.filter { $0.type == "audio" && $0.available }.map(\.serverIndex)
        guard let position = order.firstIndex(of: index) else { return }
        localAudio = index
        Task {
            guard let group = try? await playerItem.asset.loadMediaSelectionGroup(for: .audible), position < group.options.count else { return }
            playerItem.select(group.options[position], in: group)
        }
    }

    /// The next title opens its own local player, from its own place; an episode that isn't here ends the viewing.
    fileprivate func playNextLocal() {
        guard let current = local, let next = app.downloads.next(after: current) else { close(); return }
        app.downloads.recordCompleted(current.accountKey, itemID: current.entry.itemId)
        countdownTask?.cancel()
        finished = true
        player.pause()
        player.replaceCurrentItem(with: nil)
        cueTask?.cancel()
        detachObservers()
        nowPlaying.detach()
        if app.activeController === self { app.activeController = nil }
        app.playLocal(next)
    }
}

/// Track rows for a download, in the shape the player menus already draw.
enum LocalTracks {
    static func streams(_ media: LocalMedia, type: String) -> [MediaStream] {
        let tracks = media.entry.tracks.filter { track in track.type == type && track.available && (type == "audio" || media.subtitles.contains { s in s.serverIndex == track.serverIndex }) }
        let rows: [[String: Any]] = tracks.map { track in
            var row: [String: Any] = ["Index": track.serverIndex, "Type": type == "audio" ? "Audio" : "Subtitle", "DisplayTitle": track.label,
                                      "IsDefault": track.isDefault, "IsForced": track.isForced, "IsExternal": type == "subtitle",
                                      "IsTextSubtitleStream": type == "subtitle"]
            if let language = track.language { row["Language"] = language }
            if let codec = track.codec { row["Codec"] = codec }
            return row
        }
        guard let data = try? JSONSerialization.data(withJSONObject: rows) else { return [] }
        return (try? JSONDecoder().decode([MediaStream].self, from: data)) ?? []
    }
}

// MARK: - Watch together renderer

extension PlaybackController {
    /// What the group engine reads: the actual native state, never a guess.
    var syncFacts: SyncRendererFacts {
        let playerItem = player.currentItem
        let prepared = playerItem?.status == .readyToPlay && pendingSeek == nil && decision != nil && ownSeeks == 0
        // Playing is never buffering, even when HLS reports a pessimistic keep-up estimate. AVPlayer also passes through
        // "waiting" (evaluating the buffering rate) on every start: only a wait to minimise stalls is a real stall, or the
        // group would see Buffering after each Unpause and wait again. A paused player is buffering until it can keep up.
        let buffering: Bool
        switch player.timeControlStatus {
        case .playing: buffering = false
        case .waitingToPlayAtSpecifiedRate: buffering = player.reasonForWaitingToPlay != .evaluatingBufferingRate
        default: buffering = playerItem.map { !($0.isPlaybackLikelyToKeepUp || $0.isPlaybackBufferFull) } ?? true
        }
        let durationTicks = duration > 0 ? Ticks.from(seconds: duration) : (item.runTimeTicks ?? 0)
        let eligible = SyncTiming.sourceEligible(durationTicks: durationTicks, seekable: durationTicks > 0, live: item.type == "TvChannel",
                                                 infinite: source?.isInfiniteStream ?? false, offline: local != nil)
        return SyncRendererFacts(entryId: sync?.playlistItemId, generation: sync?.generation ?? -1, prepared: prepared, buffering: buffering,
                                 unpaused: isUnpaused, positionTicks: decision?.serverPositionTicks(playerSeconds: currentSeconds) ?? Ticks.from(seconds: currentSeconds),
                                 durationTicks: durationTicks, eligible: eligible)
    }

    /// Server-origin application: moves only this player, never a group request.
    func syncApply(positionTicks: Int64?, playing: Bool) {
        guard !finished else { return }
        log.debug("sync apply seek=\(positionTicks != nil, privacy: .public) playing=\(playing, privacy: .public)")
        lastControlOrigin = .server
        syncExpectPlaying = playing
        if let positionTicks {
            let offsetTicks = (decision?.serverPositionTicks(playerSeconds: 0)) ?? 0
            nativeSeek(Double(max(0, positionTicks - offsetTicks)) / 10_000_000)
        }
        if playing { player.play() } else { player.pause() }
    }
}

/// Lets the shared engine (which isn't actor-bound) read and move a main-actor controller. The engine is only ever
/// driven from the main actor.
final class SyncRendererBox: SyncRenderer {
    private weak var controller: PlaybackController?
    init(_ controller: PlaybackController) { self.controller = controller }

    var syncFacts: SyncRendererFacts {
        MainActor.assumeIsolated {
            controller?.syncFacts ?? SyncRendererFacts(entryId: nil, generation: -1, prepared: false, buffering: true, unpaused: false,
                                                       positionTicks: 0, durationTicks: 0, eligible: false)
        }
    }

    func syncApply(positionTicks: Int64?, playing: Bool) {
        MainActor.assumeIsolated { controller?.syncApply(positionTicks: positionTicks, playing: playing) }
    }
}

// MARK: - Live TV (LIVE-04/05)

extension PlaybackController {
    /// Samples the player's seekable range once a second (the periodic observer stops while paused) and keeps the
    /// on-air programme current.
    fileprivate func startLiveTick() {
        liveTask?.cancel()
        liveTask = Task { [weak self] in
            var lastProgramRead = Date.distantPast
            while !Task.isCancelled {
                guard let self, !self.finished else { return }
                self.sampleLive()
                let now = LiveFormat.nowMs()
                let ended = self.live.program.map { LiveRules.instant($0.endDate).map { $0 <= now } ?? true } ?? true
                if Date().timeIntervalSince(lastProgramRead) >= (ended ? 30 : 300) {
                    lastProgramRead = Date()
                    await self.readLiveProgram()
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    fileprivate func sampleLive() {
        guard isLive, let playerItem = player.currentItem else { return }
        if LiveStartWatchdog.shouldReopen(ready: playerItem.status != .unknown, openedSecondsAgo: Date().timeIntervalSince(liveOpened), reopens: liveReopens),
           let asset = playerItem.asset as? AVURLAsset {
            reopenLive(asset.url)
            return
        }
        var next = live
        let range = playerItem.liveRange
        next.observe(rangeStart: range?.start, rangeDuration: range?.duration, currentSeconds: player.currentTime().seconds,
                     paused: player.rate == 0 && !isBuffering)
        if next != live { live = next }
    }

    fileprivate func readLiveProgram() async {
        let channel = try? await app.liveService(for: account).channel(account: account, id: item.id)
        guard !finished else { return }
        let program = LiveRules.playerProgram(channel: channel, nowMs: LiveFormat.nowMs())
        var next = live
        next.channel = channel ?? next.channel
        next.program = program
        guard next != live else { return }
        live = next
        player.currentItem?.externalMetadata = metadata()
        nowPlaying.refresh(force: true)
    }

    /// Channel, then the programme's airing time: "Test A · 2:00 – 3:00 PM".
    var liveSubtitle: String? {
        guard isLive else { return nil }
        let channel = live.channel.map(\.accessibleName) ?? item.name
        guard let program = live.program, let start = program.startDate, let end = program.endDate else { return channel }
        return "\(channel) · \(LiveFormat.time(nowMs: LiveFormat.nowMs()).range(start, end))"
    }

    /// A fresh live remux starts with two segments, less than the player's hold-back. The Apple TV player can give up
    /// on that first playlist and never ask again (OBSERVED on the tvOS simulator: item status unknown, error -12783,
    /// playlist reloads stop while the server's playlist keeps growing). The same URL opened again plays.
    fileprivate func reopenLive(_ url: URL) {
        liveReopens += 1
        liveOpened = Date()
        log.notice("Reopening live item attempt=\(self.liveReopens)")
        let playerItem = AVPlayerItem(url: url)
        playerItem.externalMetadata = metadata()
        applySubtitleStyle(playerItem)
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)
        player.play()
    }

    /// LIVE-05: jump to the player's own live point and play.
    func goLive() {
        guard let edge = live.liveEdgeSeconds else { player.play(); return }
        nativeSeek(edge) { [weak self] in self?.player.play() }
    }
}

/// When a live item that never became ready is opened again: after 10 seconds, at most three times.
enum LiveStartWatchdog {
    static let patienceSeconds: Double = 10
    static let maxReopens = 3
    static func shouldReopen(ready: Bool, openedSecondsAgo: Double, reopens: Int) -> Bool {
        !ready && openedSecondsAgo >= patienceSeconds && reopens < maxReopens
    }
}

// MARK: Picture in picture (TOUCH-15)

extension PlaybackController {
    /// TOUCH-15's gate, read from this viewing. The interface and the device's support come from the platform.
    func pictureInPictureState(tvInterface: Bool, deviceSupports: Bool) -> PictureInPictureState {
        PictureInPictureState(
            tvInterface: tvInterface,
            deviceSupports: deviceSupports,
            hasVideo: player.currentItem != nil && videoSize.width > 0 && videoSize.height > 0,
            error: error != nil,
            // A stream being negotiated (the next episode, a reopened source) has nothing settled on screen yet.
            switching: local == nil && decision == nil,
            exiting: finished,
            atEnd: pictureInPictureAtEnd,
            // The picture plays on an AirPlay receiver; there is nothing here to keep watching in a small window.
            handedOff: externalRoute != nil
        )
    }

    /// A finished title, or one waiting at its end on "Are you still watching?".
    var pictureInPictureAtEnd: Bool {
        if isLive { return false }
        if askingStillWatching, nextCountdown == -1 { return true }
        return duration > 0 && position >= duration - 0.5
    }

    /// Back 10, Play or Pause, Forward 10 for the window, with what each can do right now.
    var pictureInPictureActions: [PictureInPictureAction] {
        let canSeek = !isLive || (live.canTimeShift && !live.expired)
        let primary: String? = isLive && !live.canTimeShift ? nil : (isUnpaused || isPlaying ? "pause" : "play")
        return PictureInPicture.actions(primary: primary, canSeekBack: canSeek, canSeekForward: canSeek && !(isLive && live.atEdge))
    }
}

/// What a viewing does when it plays into its physical end (PLAY-17, PLAY-30).
enum NaturalEndAction: Equatable {
    case playNext, askStillWatching, waitForPlayNow, close

    /// - Parameters:
    ///   - automatic: the countdown is on (Settings' "Next episode countdown" isn't Off).
    ///   - cardDismissed: the viewer chose Hide; the end then exits.
    static func decide(hasNext: Bool, cardDismissed: Bool, automatic: Bool, stillWatchingDue: Bool) -> NaturalEndAction {
        guard hasNext, !cardDismissed else { return .close }
        guard automatic else { return .waitForPlayNow }
        return stillWatchingDue ? .askStillWatching : .playNext
    }
}
