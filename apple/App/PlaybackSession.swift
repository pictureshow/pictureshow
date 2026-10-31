// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVKit
import PictureshowCore
import PictureshowTransport
import OSLog

@MainActor final class PlaybackSession: ObservableObject, Identifiable {
    let id = UUID()
    let player = AVPlayer()
    let item: MediaItem
    @Published private(set) var decision: PlayDecision?
    @Published private(set) var status = "Preparing playback…"
    @Published private(set) var error: String?
    @Published private(set) var reportError: String?
    @Published private(set) var positionTicks: Int64 = 0
    @Published private(set) var changingTracks = false
    @Published private(set) var isPaused = false
    @Published private(set) var waitingForVideo = true
    private let client: JellyfinClient
    private let account: Account
    private var relay: PlaybackRelay?
    private var observation: NSKeyValueObservation?
    private var timeObservation: Any?
    private var endedObservation: NSObjectProtocol?
    private var selectionObservation: NSObjectProtocol?
    private var loop: Task<Void, Never>?
    private var startTask: Task<Void, Never>?
    private var selectionTask: Task<Void, Never>?
    private var started = false
    private var timelineReady = false
    private var stopped = false
    private var fallbackUsed = false
    private var closed = false
    private var generation = 0
    private var terminalCloseTask: Task<Void, Never>?
    private var streamStopTask: Task<Void, Never>?

    init(client: JellyfinClient, account: Account, item: MediaItem) {
        self.client = client; self.account = account; self.item = item
    }
    func prepare(startTicks: Int64, audio: Int?, subtitle: Int?, forceHLS: Bool = false) async throws {
        guard !closed else { throw CancellationError() }
        waitingForVideo = true
        let expectedGeneration = generation
        #if os(iOS) || os(tvOS)
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try AVAudioSession.sharedInstance().setActive(true)
        #endif
        let next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks, audioIndex: audio, subtitleIndex: subtitle, forceTranscode: forceHLS || audio != nil, mediaSourceID: item.mediaSources.first?.id)
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Negotiated \(next.method.rawValue, privacy: .public), forceHLS=\(forceHLS), audio=\(audio ?? -999), subtitle=\(subtitle ?? -999), reasons=\(next.transcodeReasons.joined(separator: ","), privacy: .public)")
        #endif
        let relay = PlaybackRelay(server: account.server.url, headers: next.headers)
        do {
            try await relay.start()
            guard !closed, generation == expectedGeneration else { throw CancellationError() }
        } catch {
            relay.stop()
            try? await client.stopEncoding(account: account, decision: next)
            throw error
        }
        let url = try relay.url(for: next.url)
        self.relay = relay; decision = next; positionTicks = startTicks; started = false; timelineReady = false; stopped = false
        status = next.method == .directPlay ? "Direct play" : next.method == .directStream ? "Direct stream" : "Server HLS"
        let avItem = AVPlayerItem(url: url)
        player.appliesMediaSelectionCriteriaAutomatically = false
        observation = avItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === observed, !self.stopped else { return }
                if observed.status == .readyToPlay { self.ready() }
                else if observed.status == .failed { await self.failed() }
            }
        }
        endedObservation = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: avItem, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.stop() }
        }
        selectionObservation = NotificationCenter.default.addObserver(forName: AVPlayerItem.mediaSelectionDidChangeNotification, object: avItem, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.recordNativeSelection(avItem) }
        }
        player.replaceCurrentItem(with: avItem)
        timeObservation = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] time in
            Task { @MainActor in
                guard let self, !self.stopped, self.timelineReady, let decision = self.decision else { return }
                self.positionTicks = decision.serverPositionTicks(playerSeconds: time.seconds)
            }
        }
    }
    private func ready() {
        guard !started, !stopped, let decision else { return }
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Ready to play, seek=\(decision.initialSeekSeconds)")
        #endif
        started = true
        startTask = Task {
            if decision.initialSeekSeconds > 0 {
                let didSeek = await player.seek(to: CMTime(seconds: decision.initialSeekSeconds, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                guard didSeek else {
                    error = "The saved position could not be loaded. Try again."
                    waitingForVideo = false
                    return
                }
            }
            guard !Task.isCancelled, !stopped else { return }
            if decision.subtitleIndex == -1, decision.method != .transcode,
               decision.source.mediaStreams.contains(where: { $0.type == "Subtitle" && !$0.isExternal }),
               let group = try? await player.currentItem?.asset.loadMediaSelectionGroup(for: .legible) {
                player.currentItem?.select(nil, in: group)
            }
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Initial subtitle selection applied")
            #endif
            guard !Task.isCancelled, !stopped else { return }
            timelineReady = true
            player.play()
            isPaused = false
            waitingForVideo = false
            if let item = player.currentItem {
                selectionTask = Task { [weak self] in if let self { await self.recordNativeSelection(item) } }
            }
            #if DEBUG
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Playback requested, controlStatus=\(self.player.timeControlStatus.rawValue)")
            #endif
            do { try await client.reportStart(account: account, decision: self.decision ?? decision, positionTicks: positionTicks) }
            catch { reportError = "The server could not record playback. Resume may be unavailable." }
            guard !Task.isCancelled, !stopped else { return }
            loop = Task {
                while !Task.isCancelled && !stopped {
                    do { try await Task.sleep(for: .seconds(5)) } catch { break }
                    guard !Task.isCancelled, !stopped else { break }
                    await reportProgress()
                }
            }
        }
    }
    func togglePlayback() {
        guard timelineReady, !stopped else { return }
        isPaused = player.timeControlStatus != .paused
        if isPaused { player.pause() } else { player.play() }
    }
    func seek(by seconds: Double) {
        guard timelineReady, !stopped else { return }
        let duration = player.currentItem?.duration.seconds ?? .infinity
        let end = duration.isFinite ? max(0, duration - 0.1) : .infinity
        let target = min(end, max(0, player.currentTime().seconds + seconds))
        player.seek(to: CMTime(seconds: target, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }
    /// Native AVKit menus can select embedded tracks without another server request. Report
    /// only an unambiguous server index; AVFoundation can omit tracks, so ordinal mapping lies.
    private func recordNativeSelection(_ avItem: AVPlayerItem) async {
        guard timelineReady, !stopped, player.currentItem === avItem,
              var updated = decision, updated.method != .transcode else { return }
        do {
            if let group = try await avItem.asset.loadMediaSelectionGroup(for: .audible) {
                updated.audioIndex = nativeIndex(avItem.currentMediaSelection.selectedMediaOption(in: group), type: "Audio", streams: updated.source.mediaStreams)
            } else { updated.audioIndex = nil }
        } catch { updated.audioIndex = nil }
        do {
            if let group = try await avItem.asset.loadMediaSelectionGroup(for: .legible), let option = avItem.currentMediaSelection.selectedMediaOption(in: group) {
                updated.subtitleIndex = nativeIndex(option, type: "Subtitle", streams: updated.source.mediaStreams)
            } else { updated.subtitleIndex = -1 }
        } catch { updated.subtitleIndex = nil }
        guard !stopped, player.currentItem === avItem else { return }
        decision = updated
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Native selection metadata loaded")
        #endif
    }
    private func nativeIndex(_ option: AVMediaSelectionOption?, type: String, streams: [MediaStream]) -> Int? {
        guard let option else { return nil }
        var candidates = streams.filter { $0.type == type && !$0.isExternal }
        if let language = option.extendedLanguageTag ?? option.locale?.language.languageCode?.identifier {
            func normalized(_ value: String) -> String {
                let base = value.lowercased().split(separator: "-").first.map(String.init) ?? value
                return ["eng":"en", "spa":"es", "fra":"fr", "fre":"fr", "deu":"de", "ger":"de", "ita":"it", "jpn":"ja", "zho":"zh", "chi":"zh", "por":"pt", "kor":"ko", "rus":"ru" ][base] ?? base
            }
            if normalized(language) != "und" {
                candidates = candidates.filter { $0.language == nil || $0.language == "und" || $0.language.map(normalized) == normalized(language) }
            }
        }
        if candidates.count == 1 { return candidates[0].index }
        let name = option.displayName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let titled = candidates.filter { stream in
            [stream.title, stream.displayTitle].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }.contains(name)
        }
        if titled.count == 1 { return titled[0].index }
        return nil
    }
    private func reportProgress() async {
        guard timelineReady, !stopped, let decision else { return }
        do {
            try await client.reportProgress(account: account, decision: decision, positionTicks: currentTicks, paused: player.timeControlStatus == .paused)
            reportError = nil
        } catch { reportError = "The server could not save progress. Check your connection." }
    }
    private var currentTicks: Int64 {
        guard let decision, timelineReady else { return positionTicks }
        let value = player.currentTime().seconds
        return value.isFinite ? max(0, decision.serverPositionTicks(playerSeconds: value)) : positionTicks
    }
    /// Closing the presentation is terminal, even while a replacement is negotiating.
    func beginClosing() {
        guard !closed else { return }
        closed = true
        generation += 1
        player.pause()
        terminalCloseTask = Task { await stopStream() }
    }
    func stop() async {
        beginClosing()
        await terminalCloseTask?.value
    }
    private func stopStream() async {
        if let streamStopTask { await streamStopTask.value; return }
        guard !stopped else { return }
        positionTicks = currentTicks; stopped = true
        waitingForVideo = false
        player.pause()
        let progressTask = loop
        let startingTask = startTask
        progressTask?.cancel(); startingTask?.cancel()
        loop = nil; startTask = nil
        selectionTask?.cancel(); selectionTask = nil
        observation = nil
        if let timeObservation { player.removeTimeObserver(timeObservation); self.timeObservation = nil }
        if let endedObservation { NotificationCenter.default.removeObserver(endedObservation); self.endedObservation = nil }
        if let selectionObservation { NotificationCenter.default.removeObserver(selectionObservation); self.selectionObservation = nil }
        player.replaceCurrentItem(with: nil)
        relay?.stop(); relay = nil
        let lastDecision = decision
        let lastPosition = positionTicks
        let didStart = started
        let task = Task { @MainActor in
            await progressTask?.value
            await startingTask?.value
            if let decision = lastDecision {
                if didStart {
                    do { try await client.reportStopped(account: account, decision: decision, positionTicks: lastPosition, failed: error != nil) }
                    catch { reportError = "The server could not save the stop position. Resume may be unavailable." }
                }
                try? await client.stopEncoding(account: account, decision: decision)
            }
        }
        streamStopTask = task
        await task.value
        streamStopTask = nil
        status = "Stopped"
    }
    func changeTracks(audio: Int?, subtitle: Int?) async {
        guard !changingTracks, !closed else { return }
        generation += 1
        let expectedGeneration = generation
        changingTracks = true
        let position = currentTicks
        await stopStream()
        guard !closed, generation == expectedGeneration else { changingTracks = false; return }
        error = nil
        do { try await prepare(startTicks: position, audio: audio, subtitle: subtitle, forceHLS: true) }
        catch { self.error = error.localizedDescription }
        changingTracks = false
    }
    private func failed() async {
        #if DEBUG
        let failure = player.currentItem?.error as NSError?
        Logger(subsystem: "app.pictureshow", category: "Playback").error("AVPlayer failed domain=\(failure?.domain ?? "unknown", privacy: .public), code=\(failure?.code ?? 0)")
        var underlying = failure?.userInfo[NSUnderlyingErrorKey] as? NSError
        for _ in 0..<8 {
            guard let next = underlying else { break }
            Logger(subsystem: "app.pictureshow", category: "Playback").error("Underlying media error domain=\(next.domain, privacy: .public), code=\(next.code)")
            underlying = next.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        for event in player.currentItem?.errorLog()?.events ?? [] {
            Logger(subsystem: "app.pictureshow", category: "Playback").error("Media request error domain=\(event.errorDomain, privacy: .public), code=\(event.errorStatusCode)")
        }
        #endif
        guard !fallbackUsed, decision?.method != .transcode else { error = "Playback failed. Check the server and try again."; await stop(); return }
        fallbackUsed = true
        generation += 1
        let expectedGeneration = generation
        let position = currentTicks
        let audio = decision?.audioIndex
        let subtitle = decision?.subtitleIndex
        await stopStream()
        guard !closed, generation == expectedGeneration else { return }
        do { try await prepare(startTicks: position, audio: audio, subtitle: subtitle, forceHLS: true) }
        catch { self.error = error.localizedDescription }
    }
}

struct PlayerScreen: View {
    @ObservedObject var session: PlaybackSession
    var close: () async -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var showingTracks = false
    @State private var selectedAudio: Int?
    @State private var selectedSubtitle: Int? = -1
    @FocusState private var controlFocus: String?
    var body: some View {
        ZStack(alignment: .top) {
            #if os(tvOS)
            Color.black.ignoresSafeArea()
            #else
            NativePlayer(session: session, close: finish).ignoresSafeArea()
            #endif
            VStack {
                HStack(spacing: 24) {
                    Button("Back to details", systemImage: "chevron.left") { finish() }.accessibilityIdentifier("closePlayer").focused($controlFocus, equals: "back")
                    Spacer()
                    Button("Audio and subtitles", systemImage: "captions.bubble") { showingTracks = true }.accessibilityIdentifier("playerTracks").focused($controlFocus, equals: "tracks")
                }.buttonStyle(PlayerControlStyle()).padding().background(.black.opacity(0.65)).televisionFocusSection()
                #if os(tvOS)
                // Burned-in captions belong to the video image. Keep the persistent
                // transport outside its bounds instead of dimming cues with chrome.
                ZStack {
                    TelevisionVideo(player: session.player)
                    if session.waitingForVideo || session.player.timeControlStatus == .waitingToPlayAtSpecifiedRate {
                        ProgressView("Loading video…").tint(Theme.accent).allowsHitTesting(false)
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
                #else
                Spacer()
                #endif
                if let error = session.error { Text(error).foregroundStyle(.orange).padding().background(.black) }
                if let reportError = session.reportError { Text(reportError).font(.caption).padding().background(.black) }
                #if os(tvOS)
                HStack(spacing: 36) {
                    Button("Back 10 seconds", systemImage: "gobackward.10") { session.seek(by: -10) }.accessibilityIdentifier("seekBackward").focused($controlFocus, equals: "seekBackward")
                    Button(session.isPaused ? "Play" : "Pause", systemImage: session.isPaused ? "play.fill" : "pause.fill") { session.togglePlayback() }.accessibilityIdentifier("togglePlayback").focused($controlFocus, equals: "togglePlayback")
                    Button("Forward 10 seconds", systemImage: "goforward.10") { session.seek(by: 10) }.accessibilityIdentifier("seekForward").focused($controlFocus, equals: "seekForward")
                }.buttonStyle(PlayerControlStyle()).padding().frame(maxWidth: .infinity).background(.black.opacity(0.65)).focusSection()
                #endif
                HStack {
                    Text(session.item.name)
                    Spacer()
                    Text(session.status).accessibilityIdentifier("playbackMethod")
                    Text(Catalog.resumeLabel(session.positionTicks).replacingOccurrences(of: "Resume from ", with: "")).monospacedDigit().accessibilityIdentifier("playbackPosition")
                }.font(.caption).foregroundStyle(.white).padding().background(.black.opacity(0.65))
            }
        }
        .background(.black)
        .onAppear { controlFocus = "back" }
        .onDisappear { Task { await session.stop() } }
        #if os(tvOS)
        .onExitCommand { finish() }
        .onPlayPauseCommand { session.togglePlayback() }
        #endif
        .sheet(isPresented: $showingTracks) {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 20) {
                    Text("Audio and subtitles").modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader)
                    Spacer()
                    Button("Cancel") { showingTracks = false }.buttonStyle(PlayerControlStyle()).accessibilityIdentifier("cancelTracks")
                }
                ScrollView {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Audio").modifier(TokenFont(DesignTokens.bodySize, weight: .semibold)).accessibilityAddTraits(.isHeader)
                        trackChoice("Server default", selected: selectedAudio == nil, identifier: "audio-default") { selectedAudio = nil }
                        ForEach(session.decision?.source.mediaStreams.filter { $0.type == "Audio" } ?? []) { stream in
                            trackChoice(stream.label, selected: selectedAudio == stream.index, identifier: "audio-track-\(stream.index)") { selectedAudio = stream.index }
                        }
                        Text("Subtitles").modifier(TokenFont(DesignTokens.bodySize, weight: .semibold)).accessibilityAddTraits(.isHeader).padding(.top, 12)
                        trackChoice("Off", selected: selectedSubtitle == -1, identifier: "subtitle-off") { selectedSubtitle = -1 }
                        ForEach(session.decision?.source.mediaStreams.filter { $0.type == "Subtitle" } ?? []) { stream in
                            trackChoice(stream.label, selected: selectedSubtitle == stream.index, identifier: "subtitle-track-\(stream.index)") { selectedSubtitle = stream.index }
                        }
                    }.padding(6)
                }
                Button("Apply tracks") {
                        showingTracks = false
                        Task { await session.changeTracks(audio: selectedAudio, subtitle: selectedSubtitle) }
                }.buttonStyle(PlayerControlStyle()).accessibilityIdentifier("applyTracks")
            }
            .padding(24).frame(maxWidth: 800, maxHeight: 800).background(Theme.background)
            #if os(tvOS)
            .onExitCommand { showingTracks = false }
            #endif
            .onAppear { selectedAudio = session.decision?.audioIndex; selectedSubtitle = session.decision?.subtitleIndex ?? -1 }
        }
    }
    private func trackChoice(_ title: String, selected: Bool, identifier: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack { Text(title); Spacer(); if selected { Image(systemName: "checkmark").accessibilityHidden(true) } }
        }.buttonStyle(PlayerControlStyle()).accessibilityValue(selected ? "Selected" : "").accessibilityIdentifier(identifier)
    }
    private func finish() { session.beginClosing(); Task { await close(); dismiss() } }
}

struct PlayerControlStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { Control(configuration: configuration) }
    private struct Control: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label.labelStyle(.titleAndIcon)
                .modifier(TokenFont(DesignTokens.bodySize, weight: .semibold))
                .foregroundStyle(focused ? Theme.background : Color.white)
                .padding(.horizontal, 18).padding(.vertical, 14)
                .background(focused ? Theme.accent : Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                .overlay { RoundedRectangle(cornerRadius: 12).stroke(focused ? Theme.accent : Theme.muted.opacity(0.6), lineWidth: focused ? 3 : 1) }
                .scaleEffect(configuration.isPressed ? 0.97 : 1)
        }
    }
}

#if os(tvOS)
/// A passive video surface leaves remote focus in SwiftUI's transport controls.
struct TelevisionVideo: UIViewRepresentable {
    let player: AVPlayer
    func makeUIView(context: Context) -> VideoSurface {
        let view = VideoSurface()
        view.isUserInteractionEnabled = false
        view.videoLayer.videoGravity = .resizeAspect
        view.videoLayer.player = player
        return view
    }
    func updateUIView(_ view: VideoSurface, context: Context) { view.videoLayer.player = player }
    final class VideoSurface: UIView {
        override class var layerClass: AnyClass { AVPlayerLayer.self }
        var videoLayer: AVPlayerLayer { layer as! AVPlayerLayer }
    }
}
#endif

struct NativePlayer: UIViewControllerRepresentable {
    @ObservedObject var session: PlaybackSession
    var close: () -> Void
    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        session.player.appliesMediaSelectionCriteriaAutomatically = false
        controller.player = session.player
        controller.delegate = context.coordinator
        controller.allowsPictureInPicturePlayback = false
        return controller
    }
    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) { }
    func makeCoordinator() -> Coordinator { Coordinator(session: session, close: close) }
    final class Coordinator: NSObject, AVPlayerViewControllerDelegate {
        let session: PlaybackSession
        let close: () -> Void
        init(session: PlaybackSession, close: @escaping () -> Void) { self.session = session; self.close = close }
        #if os(tvOS)
        func playerViewControllerShouldDismiss(_ playerViewController: AVPlayerViewController) -> Bool { close(); return false }
        #endif
    }
}
