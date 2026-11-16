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
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var error: String?
    @Published private(set) var notice: ConversionNotice?
    @Published var noticeDismissed = false
    @Published private(set) var skippable: MediaSegment?
    @Published private(set) var nextEpisode: MediaItem?
    /// Seconds until the next episode starts on its own; nil when the card isn't showing.
    @Published private(set) var nextCountdown: Int?
    @Published private(set) var cue: String?
    @Published private(set) var finished = false

    let player = AVPlayer()
    let app: AppModel
    let account: Account
    let client: JellyfinClient
    private let fromStart: Bool

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
    private var countdownTask: Task<Void, Never>?

    init(app: AppModel, request: PlayerRequest) {
        self.app = app
        self.account = request.account
        self.client = app.client(for: request.account)
        self.item = request.item
        self.fromStart = request.fromStart
        player.allowsExternalPlayback = true
        player.appliesMediaSelectionCriteriaAutomatically = false
        attachObservers()
    }

    // MARK: Tracks

    var source: MediaSource? { decision?.source ?? item.mediaSources.first }
    var audioStreams: [MediaStream] { source?.mediaStreams.filter { $0.type == "Audio" } ?? [] }
    var subtitleStreams: [MediaStream] { source?.mediaStreams.filter { $0.type == "Subtitle" } ?? [] }
    var selectedAudio: Int? { decision?.audioIndex ?? source?.defaultAudioStreamIndex }
    var selectedSubtitle: Int { decision?.subtitleIndex ?? -1 }

    func label(_ stream: MediaStream) -> String { PlaybackTrackLabels.label(stream, showDefault: false) }

    // MARK: Lifecycle

    func start() async {
        guard !started else { return }
        started = true
        do {
            let full = try await client.item(account: account, id: item.id)
            item = full
            async let segments = try? client.segments(account: account, itemID: full.id)
            async let next = try? client.nextEpisode(account: account, item: full)
            let resumeTicks = fromStart ? 0 : full.userData.playbackPositionTicks
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
        var next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks, audioIndex: audio, subtitleIndex: subtitle,
                                            forceTranscode: forceTranscode, mediaSourceID: source?.id ?? item.mediaSources.first?.id,
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
        let url = try ServerPlaybackURL.make(decisionURL: next.url, server: account.server.url, token: account.accessToken, requiredHeaders: next.headers)
        let playerItem = AVPlayerItem(url: url)
        playerItem.externalMetadata = metadata()
        applySubtitleStyle(playerItem)
        pendingSeek = next.initialSeekSeconds > 1 ? next.initialSeekSeconds : nil
        observe(playerItem)
        player.replaceCurrentItem(with: playerItem)
        player.isMuted = next.audioIndex == -1
        player.play()
        if let previous, previous.playSessionId != next.playSessionId {
            try? await client.stopEncoding(account: account, decision: previous)
        }
        try? await client.reportStart(account: account, decision: next, positionTicks: startTicks)
        lastReport = Date()
        loadCues(for: next)
    }

    /// Ends the viewing: reports where the viewer stopped, tells the server to stop converting, refreshes the catalog.
    func close() {
        guard !finished else { return }
        finished = true
        generation += 1
        let decision = self.decision
        let seconds = currentSeconds
        let durationMs = Int64(duration * 1000)
        player.pause()
        player.replaceCurrentItem(with: nil)
        cueTask?.cancel(); countdownTask?.cancel()
        detachObservers()
        let account = self.account, client = self.client, app = self.app
        Task {
            if let decision {
                let ms = PlaybackEndPosition.completed(Int64(seconds * 1000), durationMs: durationMs)
                try? await client.reportStopped(account: account, decision: decision, positionTicks: ms * 10_000)
                try? await client.stopEncoding(account: account, decision: decision)
            }
            app.catalogChanged()
        }
        app.player = nil
    }

    // MARK: Controls

    var currentSeconds: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? t : position
    }

    func togglePlay() { isPlaying ? player.pause() : player.play() }

    func seek(to seconds: Double) {
        let target = max(0, duration > 0 ? min(seconds, duration - 1) : seconds)
        position = target
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] _ in
            Task { @MainActor in self?.reportProgress(force: true) }
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
        Task { await renegotiate(audio: index, subtitle: selectedSubtitle) }
    }

    func selectSubtitle(_ index: Int) {
        guard index != selectedSubtitle, let decision else { return }
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

    func retry() {
        error = nil
        Task { await renegotiate(audio: selectedAudio, subtitle: selectedSubtitle) }
    }

    func playNext() {
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
            }
        }
        self.decision = nil
        item = next
        nextEpisode = nil; nextCountdown = nil; nextCardDismissed = false
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

    func dismissNextCard() { nextCardDismissed = true; nextCountdown = nil; countdownTask?.cancel() }

    // MARK: Observation

    private func attachObservers() {
        timeObserver = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.25, preferredTimescale: 600), queue: .main) { [weak self] time in
            MainActor.assumeIsolated { self?.tick(time.seconds) }
        }
        observations.append(player.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            let status = player.timeControlStatus
            Task { @MainActor in self?.statusChanged(status) }
        })
    }

    private func detachObservers() {
        if let timeObserver { player.removeTimeObserver(timeObserver) }
        timeObserver = nil
        observations.forEach { $0.invalidate() }
        observations = []
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = nil
    }

    private func observe(_ playerItem: AVPlayerItem) {
        observations.append(playerItem.observe(\.status, options: [.new]) { [weak self] item, _ in
            let status = item.status
            let message = item.error?.localizedDescription
            Task { @MainActor in self?.itemStatusChanged(status, message: message) }
        })
        observations.append(playerItem.observe(\.duration, options: [.new]) { [weak self] item, _ in
            let seconds = item.duration.seconds
            Task { @MainActor in if seconds.isFinite, seconds > 0 { self?.duration = seconds } }
        })
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        endObserver = NotificationCenter.default.addObserver(forName: AVPlayerItem.didPlayToEndTimeNotification, object: playerItem, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.reachedEnd() }
        }
    }

    private func itemStatusChanged(_ status: AVPlayerItem.Status, message: String?) {
        switch status {
        case .readyToPlay:
            if let seek = pendingSeek {
                pendingSeek = nil
                player.seek(to: CMTime(seconds: seek, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                position = seek
            }
            if duration == 0, let ticks = item.runTimeTicks { duration = Ticks.seconds(ticks) }
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
        }
    }

    private func tick(_ seconds: Double) {
        guard seconds.isFinite, !finished, pendingSeek == nil else { return }
        position = seconds
        let ms = Int64(seconds * 1000)
        if !cues.isEmpty { cue = TextSubtitles.text(cues: cues, positionMs: ms) } else if cue != nil { cue = nil }
        updateSegment(ms)
        updateNextCard(ms)
        reportProgress(force: false)
    }

    private func updateSegment(_ ms: Int64) {
        let modes = app.preferences.playback.segmentModes
        let active = PlaybackExtras.activeSegment(segments, positionMs: ms, modes: modes)
        if let active, modes[active.type] == .automatic, !autoSkipped.contains(active.id) {
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
        guard let _ = nextEpisode, !nextCardDismissed, duration > 0 else { return }
        let durationMs = Int64(duration * 1000)
        guard let trigger = PlaybackExtras.nextTrigger(durationMs: durationMs, segments: segments), ms >= trigger else {
            if nextCountdown != nil { nextCountdown = nil; countdownTask?.cancel() }
            return
        }
        guard nextCountdown == nil else { return }
        guard app.preferences.autoplayNextEpisode else { nextCountdown = -1; return }
        nextCountdown = 10
        countdownTask?.cancel()
        countdownTask = Task { [weak self] in
            while let self, let left = self.nextCountdown, left > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                if self.isPlaying { self.nextCountdown = left - 1 }
            }
            if !Task.isCancelled, let self, self.nextCountdown == 0 { self.playNext() }
        }
    }

    private func reachedEnd() {
        if nextEpisode != nil, app.preferences.autoplayNextEpisode, !nextCardDismissed { playNext() } else { close() }
    }

    private func reportProgress(force: Bool) {
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

    private func metadata() -> [AVMetadataItem] {
        func entry(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let item = AVMutableMetadataItem(); item.identifier = id; item.value = value as NSString; item.extendedLanguageTag = "und"; return item
        }
        var items = [entry(.commonIdentifierTitle, item.type == "Episode" ? (item.seriesName ?? item.name) : item.name)]
        if item.type == "Episode" { items.append(entry(.iTunesMetadataTrackSubTitle, [Format.episode(item).nilIfBlank, item.name].compactMap { $0 }.joined(separator: " · "))) }
        if let overview = item.overview { items.append(entry(.commonIdentifierDescription, overview)) }
        return items
    }

    /// Title line for the player chrome.
    var title: String { item.type == "Episode" ? (item.seriesName ?? item.name) : item.name }
    var subtitle: String? { item.type == "Episode" ? [Format.episode(item).nilIfBlank, item.name].compactMap { $0 }.joined(separator: " · ") : nil }

    var methodDescription: String {
        switch decision?.method {
        case .directPlay: return "Playing the original file"
        case .directStream: return "Repackaged by the server"
        case .transcode: return "Converted by the server"
        case nil: return ""
        }
    }
}
