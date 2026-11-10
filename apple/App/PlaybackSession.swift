// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import Combine
import AVKit
import PictureshowCore
import PictureshowTransport
import OSLog
import MediaAccessibility

/// AVKit drives the same public seek API as app controls. Capture a viewing-owned
/// receipt before invoking AVFoundation; forwarding the original completion keeps
/// AVKit's transport behavior intact while giving the session an actual result.
final class ViewingAVPlayer: AVPlayer {
    // AVKit's native transport calls public Play/rate setters. Gate those before
    // AVFoundation can start a decoder while the scene is covered. App-owned
    // replacements apply retained intent through the separate methods below.
    var nativePlayRequested: (() -> Bool)?
    private var applyingViewingIntent = false
    private func acceptsPlayRequest() -> Bool {
        guard Thread.isMainThread else { return false }
        return applyingViewingIntent || (nativePlayRequested?() ?? true)
    }
    override func play() {
        guard acceptsPlayRequest() else { super.pause(); return }
        applyingViewingIntent = true
        defer { applyingViewingIntent = false }
        super.play()
    }
    override func playImmediately(atRate rate: Float) {
        guard rate == 0 || acceptsPlayRequest() else { super.pause(); return }
        applyingViewingIntent = true
        defer { applyingViewingIntent = false }
        super.playImmediately(atRate: rate)
    }
    override var rate: Float {
        get { super.rate }
        set {
            guard newValue == 0 || acceptsPlayRequest() else { super.pause(); return }
            super.rate = newValue
        }
    }
    func playFromViewing() {
        applyingViewingIntent = true
        defer { applyingViewingIntent = false }
        super.play()
    }
    func playFromViewing(rate: Float) {
        applyingViewingIntent = true
        defer { applyingViewingIntent = false }
        if rate == 1 { super.play() } else { super.playImmediately(atRate: rate) }
    }
    func pauseFromViewing() {
        applyingViewingIntent = true
        defer { applyingViewingIntent = false }
        super.pause()
    }
    struct SeekReceipt: Equatable, Sendable { let generation: Int; let revision: Int; let target: Int64 }
    private let receiptLock = NSLock()
    private var pendingReceipt: (SeekReceipt, @Sendable (SeekReceipt, Bool) -> Void)?
    func arm(_ receipt: SeekReceipt?, completion: @escaping @Sendable (SeekReceipt, Bool) -> Void = { _, _ in }) {
        receiptLock.lock(); defer { receiptLock.unlock() }
        pendingReceipt = receipt.map { ($0, completion) }
    }
    private func observer(for time: CMTime) -> ((Bool) -> Void)? {
        receiptLock.lock(); let value = pendingReceipt; receiptLock.unlock()
        guard time.seconds.isFinite, let (receipt, callback) = value, abs(Ticks.seconds(receipt.target) - time.seconds) < 0.001 else { return nil }
        return { callback(receipt, $0) }
    }
    override func seek(to time: CMTime) {
        let observe = observer(for: time)
        super.seek(to: time, toleranceBefore: .positiveInfinity, toleranceAfter: .positiveInfinity) { observe?($0) }
    }
    override func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime) {
        let observe = observer(for: time)
        super.seek(to: time, toleranceBefore: toleranceBefore, toleranceAfter: toleranceAfter) { observe?($0) }
    }
    override func seek(to time: CMTime, completionHandler: @escaping (Bool) -> Void) {
        let observe = observer(for: time)
        super.seek(to: time, completionHandler: { finished in observe?(finished); completionHandler(finished) })
    }
    override func seek(to time: CMTime, toleranceBefore: CMTime, toleranceAfter: CMTime, completionHandler: @escaping (Bool) -> Void) {
        let observe = observer(for: time)
        super.seek(to: time, toleranceBefore: toleranceBefore, toleranceAfter: toleranceAfter) { finished in
            observe?(finished); completionHandler(finished)
        }
    }
}

/// A viewing owns reports. Decoder replacements own encoders, never another viewing Start/Stop.
@MainActor final class PlaybackSession: ObservableObject, Identifiable {
    let id = UUID()
    let player: AVPlayer
    @Published private(set) var item: MediaItem
    @Published private(set) var decision: PlayDecision? { didSet { trackLabels.update(decision?.source.mediaStreams ?? [], sourceID: decision.map { "\($0.itemID)|\($0.source.id ?? "")" }) } }
    @Published private(set) var status = "Preparing playback…"
    @Published private(set) var error: String? { didSet { if error != nil { trackLabels.cancelOpen() } } }
    @Published private(set) var reportError: String?
    @Published private(set) var positionTicks: Int64 = 0
    @Published private(set) var playbackRate: Double = 1
    @Published private(set) var changingTracks = false
    @Published private(set) var terminal = false
    @Published private(set) var isPaused = false
    @Published private(set) var waitingForVideo = true
    @Published private(set) var chapters: [Chapter] = []
    @Published private(set) var skipSegment: MediaSegment?
    @Published private(set) var nextItem: MediaItem?
    @Published private(set) var nextCountdown: Int?
    @Published private(set) var nextCardVisible = false
    @Published private(set) var speedLimitation: String?
    @Published private(set) var extrasError: String?
    @Published var panelOpen = false { didSet { if panelOpen { nextCountdown = nil } } }
    @Published var controlsVisible = false
    @Published private(set) var subtitleDelayMs = 0
    let externalSubtitles: ExternalTextSubtitles
    @Published private(set) var subtitleCue: String?
    @Published private(set) var subtitleStatus: String?
    let trackLabels = TrackLabelCache()
    let diagnostics: PlaybackDiagnostics
    private(set) var audioChosenByViewer = false
    private(set) var subtitleChosenByViewer = false
    private var trackTail: Task<Void, Never>?
    private var nativeSelectionBaseline: [String: Int] = [:]
    private var nativeSelectionRevision = 0
    private var selectionIntentRevision = 0
    private var subtitleSelectionCount = 0
    private var observedSubtitleSelection = false
    private var observedSubtitleIndex: Int?
    private var subtitleObservation: AnyCancellable?
    private let client: JellyfinClient
    private let account: Account
    let preferences: PlaybackPreferences
    private var statusObservation: NSKeyValueObservation?
    private var controlObservation: NSKeyValueObservation?
    private var clockTask: Task<Void, Never>?
    private var endedObservation: NSObjectProtocol?
    private var selectionObservation: NSObjectProtocol?
    private var startTask: Task<Void, Never>?
    private var extrasTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    private var reportTail: Task<Void, Never>?
    private var reportsQueued = 0
    private var reportSequence = 0
    private var failedStart: (PlayDecision, Int64, Bool, Bool)?
    private var failedProgress: (PlayDecision, Int64, Bool)?
    private var finalReport: (PlayDecision, Int64, Bool)?
    private var failedStop: (PlayDecision, Int64, Bool)?
    private var failedPlayedID: String?
    private var completionTask: Task<Bool, Never>?
    private var failedEncodings: [PlayDecision] = []
    private var viewingID = UUID()
    private var started = false
    private var timelineReady = false
    private var stopped = false
    private var fallbackUsed = false
    private var hevcFallbackUsed = false
    private var closed = false
    private var generation = 0
    private var closeTask: Task<Void, Never>?
    private var pauseIntent = false
    private(set) var isForeground = true
    private var stopGeneration = 0
    private var lifecycleObservers: [NSObjectProtocol] = []
    // The catalog owns played presentation separately from raw server data.
    var onCompletionChoiceRevision: ((String) -> Int64)?
    var onCompletionBegan: ((String, Int64) -> UUID)?
    var onCompletionMayApply: ((String, UUID) -> Bool)?
    var onCompletionReportFinished: ((String, UUID, Bool) -> Void)?
    private var completionOwners: [String: UUID] = [:]
    private var completionChoiceRevisions: [String: Int64] = [:]
    var onCompletionFinished: ((String, UUID, Bool) -> Void)?
    private var pendingSeekTicks: Int64?
    private var seekFallbackTicks: Int64?
    private var nativeSeekPending = false
    private var seekRevision = 0
    private var segments: [MediaSegment] = []
    private var extrasItem: MediaItem?
    private var imageCache: [URL: UIImage] = [:]
    private var skipped = Set<String>()
    private var continuationCancelled = false
    private var nextLookupFailed = false
    private var nextLookupPending = true
    private var switching = false
    private var completionCommitted = false
    private var speedState = PlaybackSpeedState()
    private var completedTicks: Int64?
    private var lastReportTime = Date.distantPast
    private var lastTickTime = Date()
    private var countdownElapsed: Double = 0
    private var lastSeekTime = Date.distantPast
    private var stalledSince: Date?
    private var lastObservedTicks: Int64 = 0

    init(client: JellyfinClient, account: Account, item: MediaItem, preferences: PlaybackPreferences = .init(), player: AVPlayer = ViewingAVPlayer(), externalSubtitles: ExternalTextSubtitles? = nil, diagnostics: PlaybackDiagnostics? = nil) {
        self.client = client; self.account = account; self.item = item; self.preferences = preferences; self.player = player
        self.diagnostics = diagnostics ?? PlaybackDiagnostics()
        self.externalSubtitles = externalSubtitles ?? ExternalTextSubtitles()
        subtitleObservation = self.externalSubtitles.$loading.combineLatest(self.externalSubtitles.$unavailable)
            .map { loading, unavailable -> String? in
                if unavailable { return "Subtitles are unavailable. Retrying while video continues." }
                return loading ? "Loading selected subtitles. Video playback can continue." : nil
            }.removeDuplicates().sink { [weak self] in self?.subtitleStatus = $0 }
        player.allowsExternalPlayback = true
        (player as? ViewingAVPlayer)?.nativePlayRequested = { [weak self] in
            MainActor.assumeIsolated { self?.nativePlayRequested() ?? false }
        }
        // Install before prepare's first suspension, not when PlayerScreen appears.
        // Apple policy treats inactive as a covered viewing too (Control Center,
        // system interruptions and Home). Active restores visibility only.
        for name in [UIApplication.willResignActiveNotification, UIApplication.didEnterBackgroundNotification] {
            lifecycleObservers.append(NotificationCenter.default.addObserver(forName: name, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.backgrounded() }
            })
        }
        lifecycleObservers.append(NotificationCenter.default.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.foregrounded() }
        })
        if UIApplication.shared.applicationState != .active { backgrounded() }
    }
    deinit {
        for observer in lifecycleObservers { NotificationCenter.default.removeObserver(observer) }
    }
    func prepare(startTicks: Int64, audio: Int?, subtitle: Int?, forceHLS: Bool = false, forceVideoTranscode: Bool = false) async throws {
        guard !closed else { throw CancellationError() }
        let version = generation
        waitingForVideo = true; timelineReady = false; stopped = false; error = nil
        status = "Preparing playback…"
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try AVAudioSession.sharedInstance().setActive(true)
        let selectedSubtitle = subtitle
        var captionLanguage: String?
        if subtitle == nil, MACaptionAppearanceGetDisplayType(.user) == .alwaysOn,
           let configuration = try? await client.configuration(account: account),
           let language = Captions.language(enabled: true, configuration: configuration, deviceLanguage: Locale.preferredLanguages.first ?? "") {
            captionLanguage = language
        }
        let knownSource = decision?.source ?? item.mediaSources.first
        // With no explicit pick, discover the user-scoped server choice before
        // consulting cached DTO metadata or applying the system fallback.
        var usesExternalText = selectedSubtitle != nil && externalTextStream(selectedSubtitle, source: knownSource) != nil
        var next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks,
            audioIndex: audio, subtitleIndex: selectedSubtitle, forceTranscode: forceVideoTranscode,
            mediaSourceID: decision?.source.id ?? item.mediaSources.first?.id, preferences: preferences, forceHLS: forceHLS || audio != nil, externalTextSubtitle: usesExternalText, forceAVCTranscode: hevcFallbackUsed)
        guard !closed, generation == version else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        // Episode/list responses need not contain streams. Apply captions to the fresh
        // negotiated source, never to the previous episode's metadata.
        if subtitle == nil, let captionLanguage, let preferred = Captions.index(source: next.source, preferredLanguage: captionLanguage), next.subtitleIndex != preferred {
            let sourceID = next.source.id
            usesExternalText = externalTextStream(preferred, source: next.source) != nil
            next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks,
                audioIndex: audio, subtitleIndex: preferred, forceTranscode: forceVideoTranscode,
                mediaSourceID: sourceID, preferences: preferences, forceHLS: forceHLS || audio != nil, externalTextSubtitle: usesExternalText, forceAVCTranscode: hevcFallbackUsed)
        }
        guard !closed, generation == version else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        // A discovery response may be the first place external stream metadata
        // exists. Negotiate A/V with captions Off before opening its URL; optional
        // text extraction must never become part of the A/V asset's READY path.
        if !usesExternalText, externalTextStream(next.subtitleIndex, source: next.source) != nil {
            usesExternalText = true
            next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks,
                audioIndex: audio, subtitleIndex: next.subtitleIndex, forceTranscode: forceVideoTranscode,
                mediaSourceID: next.source.id, preferences: preferences, forceHLS: forceHLS || audio != nil,
                externalTextSubtitle: true, forceAVCTranscode: hevcFallbackUsed)
        }
        guard !closed, generation == version else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        let url: URL
        do { url = try ServerPlaybackURL.make(decisionURL: next.url, server: account.server.url,
                                             token: account.accessToken, requiredHeaders: next.headers) }
        catch { try? await client.stopEncoding(account: account, decision: next); throw error }
        decision = next; positionTicks = pendingSeekTicks ?? startTicks
        observeSubtitleSelection(next.subtitleIndex)
        selectExternalText(for: next)
        // Session identifiers support scoped encoder cleanup after an interrupted pilot.
        // Never log the asset URL, request headers or viewer credential.
        let hevcHLS = AppleHEVCTranscoding.isHEVC(next) && next.source.transcodingSubProtocol?.lowercased() == "hls"
        let selectedContainer = next.method == .transcode ? next.source.transcodingContainer : next.source.container
        // Container diagnostics are a fixed vocabulary, never a server-supplied string.
        let container = selectedContainer.map { ["mp4", "m4v", "mov", "ts", "mpegts"].contains($0.lowercased()) ? $0.lowercased() : "other" } ?? "unknown"
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Negotiated viewing=\(self.viewingID.uuidString, privacy: .public) playSession=\(next.playSessionId ?? "none", privacy: .public) item=\(next.itemID, privacy: .public) source=\(next.source.id ?? "none", privacy: .public) method=\(String(describing: next.method), privacy: .public) hevcHLS=\(hevcHLS, privacy: .public) boundedHEVC=\(AppleHEVCTranscoding.isBounded(next), privacy: .public) container=\(container, privacy: .public)")
        status = playbackMethodStatus
        player.isMuted = audio == -1
        let avItem = AVPlayerItem(url: url)
        avItem.audioTimePitchAlgorithm = .timeDomain
        var textAttributes: [String: Any] = [:]
        if let scale = preferences.subtitleSize.scale { textAttributes[kCMTextMarkupAttribute_RelativeFontSize as String] = scale * 100 }
        if preferences.subtitleBackground != .system {
            textAttributes[kCMTextMarkupAttribute_BackgroundColorARGB as String] = preferences.subtitleBackground == .box ? [0.8, 0, 0, 0] : [0, 0, 0, 0]
            textAttributes[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = preferences.subtitleBackground == .shadow ? kCMTextMarkupCharacterEdgeStyle_DropShadow : kCMTextMarkupCharacterEdgeStyle_None
        }
        if !textAttributes.isEmpty, let rule = AVTextStyleRule(textMarkupAttributes: textAttributes) { avItem.textStyleRules = [rule] }
        let title = AVMutableMetadataItem(); title.identifier = .commonIdentifierTitle; title.value = item.name as NSString; title.extendedLanguageTag = "und"
        let description = AVMutableMetadataItem(); description.identifier = .commonIdentifierDescription; description.value = (item.overview ?? item.seriesName ?? "") as NSString; description.extendedLanguageTag = "und"
        avItem.externalMetadata = [title, description]
        statusObservation = avItem.observe(\.status, options: [.initial, .new]) { [weak self] observed, _ in
            Task { @MainActor in
                guard let self, self.player.currentItem === observed, self.generation == version, !self.closed else { return }
                if observed.status == .readyToPlay { self.ready(version: version) }
                else if observed.status == .failed { await self.failed() }
            }
        }
        controlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] observed, _ in
            Task { @MainActor in
                guard let self, self.timelineReady, self.generation == version, !self.closed else { return }
                if observed.timeControlStatus != .waitingToPlayAtSpecifiedRate {
                    let ended = self.durationSeconds.map { observed.currentTime().seconds >= $0 - 0.05 } ?? false
                    if !self.isForeground {
                        self.pauseIntent = true
                        if observed.timeControlStatus != .paused { self.pausePlayer() }
                    } else if observed.timeControlStatus != .paused || !ended {
                        self.pauseIntent = observed.timeControlStatus == .paused
                    }
                    self.isPaused = self.pauseIntent
                    self.logPlayState()
                    self.enqueueProgress()
                }
            }
        }
        endedObservation = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: avItem, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.physicalEnd() }
        }
        selectionObservation = NotificationCenter.default.addObserver(forName: AVPlayerItem.mediaSelectionDidChangeNotification, object: avItem, queue: .main) { [weak self] _ in
            Task { @MainActor in await self?.recordNativeSelection(avItem) }
        }
        // AVKit can fail while opening auxiliary QuickTime tracks if automatic
        // selection was disabled before attachment. Keep its startup selection
        // enabled for every new item; apply explicit choices only after READY.
        player.appliesMediaSelectionCriteriaAutomatically = true
        player.replaceCurrentItem(with: avItem)
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").debug("Attached item status=\(avItem.status.rawValue) viewing=\(self.viewingID.uuidString, privacy: .public)")
        #endif
        // AVPlayer's media-time observer can stop delivering callbacks when the
        // decoder stalls. Sample on wall time so a ready-but-frozen item still
        // reaches the waiting hint and bounded recovery deadline.
        clockTask = Task { [weak self] in
            while !Task.isCancelled {
                do { try await Task.sleep(for: .milliseconds(500)) } catch { return }
                guard let self, !self.closed, self.generation == version else { return }
                self.tick()
            }
        }
        timeoutTask = Task { [weak self] in
            do { try await Task.sleep(for: .seconds(12)) } catch { return }
            guard let self, self.showStartupWaitHint(version: version) else { return }
            do { try await Task.sleep(for: .seconds(33)) } catch { return }
            guard !self.timelineReady, self.generation == version, !self.closed else { return }
            self.error = "Playback took too long. Go back to details and try again."
            await self.stop()
        }
        if extrasTask == nil { loadExtras() }
    }
    private var playbackMethodStatus: String {
        guard let decision else { return "Preparing playback…" }
        return decision.method == .directPlay ? "Direct play" : decision.method == .directStream ? "Direct stream" : "Server HLS"
    }
    /// Explicit readiness/deadline events allow hosted tests to exercise delayed
    /// startup without requiring a real decoder or waiting for wall-clock deadlines.
    @discardableResult func showStartupWaitHint(version: Int) -> Bool {
        guard !timelineReady, generation == version, !closed else { return false }
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("Playback startup waiting itemStatus=\(self.player.currentItem?.status.rawValue ?? -1) controlStatus=\(self.player.timeControlStatus.rawValue) startTask=\(self.startTask != nil) paused=\(self.pauseIntent) viewing=\(self.viewingID.uuidString, privacy: .public)")
        #endif
        status = "Still waiting for playback. You can go back and try again."
        return true
    }
    func ready(version: Int) {
        guard !timelineReady, startTask == nil, !closed, generation == version, let decision else { return }
        startTask = Task { [weak self] in
            guard let self, !Task.isCancelled, !self.closed, self.generation == version else { return }
            self.player.appliesMediaSelectionCriteriaAutomatically = false
            if (decision.subtitleIndex == -1 || self.externalSubtitles.selected != nil), decision.method != .transcode,
               let avItem = self.player.currentItem,
               let group = try? await avItem.asset.loadMediaSelectionGroup(for: .legible) {
                guard !Task.isCancelled, !self.closed, self.generation == version,
                      self.player.currentItem === avItem else { return }
                avItem.select(nil, in: group)
            }
            guard !Task.isCancelled, !self.closed, self.generation == version else { return }
            // Initial AVKit/programmatic selection is a baseline, never a choice.
            await self.recordNativeSelection(self.player.currentItem, establishingBaseline: true)
            guard !Task.isCancelled, !self.closed, self.generation == version else { return }
            var target = self.pendingSeekTicks ?? decision.startTicks
            var correctingSeek = false
            while target > 0 || correctingSeek {
                let revision = self.seekRevision
                let success = await self.player.seek(to: CMTime(seconds: Ticks.seconds(target), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                guard !Task.isCancelled, !self.closed, self.generation == version else { return }
                // A newer viewer seek can arrive while the startup seek is in flight.
                // Resolve that intent before interpreting the older completion or reporting
                // readiness. Zero also requires a corrective seek after a nonzero request.
                if self.seekRevision != revision {
                    target = self.pendingSeekTicks ?? decision.startTicks
                    correctingSeek = true
                    continue
                }
                guard success else { self.error = "The saved position could not be loaded. Try again."; self.waitingForVideo = false; return }
                break
            }
            self.pendingSeekTicks = nil; self.seekFallbackTicks = nil; self.positionTicks = target
            self.timelineReady = true; self.waitingForVideo = false
            self.status = self.playbackMethodStatus
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("Native ready viewing=\(self.viewingID.uuidString, privacy: .public) durationSeconds=\(self.durationSeconds ?? -1) targetTicks=\(target) paused=\(self.pauseIntent) foreground=\(self.isForeground)")
            self.timeoutTask?.cancel(); self.timeoutTask = nil
            self.isPaused = self.pauseIntent
            if self.pauseIntent { self.pausePlayer() } else { self.playPlayer() }
            self.lastTickTime = Date(); self.lastObservedTicks = target; self.stalledSince = nil
            if !self.started {
                self.started = true
                self.enqueueReport(decision: decision, kind: "start", ticks: target, paused: self.pauseIntent)
            } else { self.enqueueProgress() }
            self.installChapterMarkers()
        }
    }
    func togglePlayback() {
        guard !closed, isForeground else { return }
        pauseIntent.toggle(); isPaused = pauseIntent
        if pauseIntent { nextCountdown = nil }
        if timelineReady { if pauseIntent { pausePlayer() } else { playPlayer() } }
        enqueueProgress()
    }
    func nativeSeekBegan(_ seconds: Double) { lastSeekTime = Date(); countdownElapsed = 0 }
    /// AVKit owns transport seeks. Return the viewing target synchronously so its
    /// internal seek uses the accumulated intent even while currentTime lags.
    func nativeSeekTarget(from oldSeconds: Double, to targetSeconds: Double) -> Double {
        guard !closed, oldSeconds.isFinite, targetSeconds.isFinite else { return targetSeconds }
        if pendingSeekTicks == nil { seekFallbackTicks = Ticks.from(seconds: oldSeconds) }
        let upper = durationSeconds.map(Ticks.from(seconds:)) ?? Int64.max
        let target = SeekSteps.target(positionMs: Ticks.from(seconds: oldSeconds) / 10_000,
                                      pendingMs: pendingSeekTicks.map { $0 / 10_000 },
                                      deltaMs: Ticks.from(seconds: targetSeconds - oldSeconds) / 10_000,
                                      durationMs: upper / 10_000) * 10_000
        seekRevision += 1; pendingSeekTicks = target; nativeSeekPending = true
        (player as? ViewingAVPlayer)?.arm(.init(generation: generation, revision: seekRevision, target: target)) { [weak self] receipt, success in
            Task { @MainActor in self?.nativeSeekCompleted(receipt, success: success) }
        }
        positionTicks = target; nativeSeekBegan(Ticks.seconds(target))
        #if DEBUG
        if ProcessInfo.processInfo.environment["PICTURESHOW_SEEK_DIAGNOSTICS"] == "1" {
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("seek-native uptimeMs=\(Int64(ProcessInfo.processInfo.systemUptime * 1_000)) fromMs=\(Ticks.from(seconds: oldSeconds) / 10_000) proposedMs=\(Ticks.from(seconds: targetSeconds) / 10_000) targetMs=\(target / 10_000) revision=\(self.seekRevision) generation=\(self.generation)")
        }
        #endif
        return Ticks.seconds(target)
    }
    func nativeSeekCompleted(_ receipt: ViewingAVPlayer.SeekReceipt, success: Bool) {
        guard nativeSeekPending, !closed, receipt.generation == generation,
              receipt.revision == seekRevision, receipt.target == pendingSeekTicks else { return }
        nativeSeekPending = false; pendingSeekTicks = nil
        let observed = player.currentTime().seconds
        positionTicks = success ? receipt.target : max(0, observed.isFinite ? Ticks.from(seconds: observed) : seekFallbackTicks ?? 0)
        seekFallbackTicks = nil
        (player as? ViewingAVPlayer)?.arm(nil)
        diagnostics.record(success ? "seek.nativeCompleted" : "seek.nativeFailed")
        #if DEBUG
        if ProcessInfo.processInfo.environment["PICTURESHOW_SEEK_DIAGNOSTICS"] == "1" {
            Logger(subsystem: "app.pictureshow", category: "Playback").notice("seek-native-complete uptimeMs=\(Int64(ProcessInfo.processInfo.systemUptime * 1_000)) targetMs=\(receipt.target / 10_000) revision=\(receipt.revision) generation=\(receipt.generation) success=\(success) actualMs=\(self.positionTicks / 10_000)")
        }
        #endif
        enqueueProgress()
    }
    private func playPlayer() {
        guard isForeground, !pauseIntent else { pausePlayer(); return }
        player.defaultRate = Float(playbackRate)
        player.currentItem?.audioTimePitchAlgorithm = .timeDomain
        if let player = player as? ViewingAVPlayer { player.playFromViewing(rate: Float(playbackRate)) }
        else if playbackRate == 1 { player.play() }
        else { player.playImmediately(atRate: Float(playbackRate)) }
    }

    /// Applies the portable speed policy to the current viewing. The caller supplies
    /// output evidence when an encoded route cannot change rate; Apple currently
    /// reports its AVKit-managed output as unknown and does not claim passthrough.
    @discardableResult func selectPlaybackSpeed(_ requested: Double, encodedOutput: Bool = false) -> Bool {
        let accepted = speedState.select(requested, encodedOutput: encodedOutput)
        playbackRate = speedState.rate
        speedLimitation = accepted ? nil : PlaybackSpeeds.unsupportedOutputNotice
        player.defaultRate = Float(playbackRate)
        player.currentItem?.audioTimePitchAlgorithm = .timeDomain
        if timelineReady, isForeground, !pauseIntent {
            if let player = player as? ViewingAVPlayer { player.playFromViewing(rate: Float(playbackRate)) }
            else if playbackRate == 1 { player.play() }
            else { player.playImmediately(atRate: Float(playbackRate)) }
        }
        return accepted
    }

    /// A requested rate is not proof of output acceptance. Check the running
    /// player's rate, and fail back to normal if AVKit/output cannot retain it.
    func verifyPlaybackRate() {
        guard timelineReady, player.timeControlStatus == .playing, player.rate > 0,
              abs(Double(player.rate) - playbackRate) > 0.01 else { return }
        _ = speedState.select(1, encodedOutput: false); playbackRate = speedState.rate
        speedLimitation = "This playback output could not apply the selected speed. Playback is at 1×. Try reopening playback on this device."
        player.defaultRate = 1
        if !pauseIntent { playPlayer() }
    }
    var playbackSpeedWarning: String? { PlaybackSpeeds.warning(isTranscoding: decision?.method == .transcode) }
    private func pausePlayer() {
        if let player = player as? ViewingAVPlayer { player.pauseFromViewing() }
        else { player.pause() }
    }
    /// Called synchronously by AVKit's public native transport Play path. A native
    /// Play before READY updates intent but must not bypass startup correction.
    @discardableResult func nativePlayRequested() -> Bool {
        guard !closed, isForeground else { return false }
        pauseIntent = false; isPaused = false
        enqueueProgress()
        return timelineReady
    }
    func foregrounded() {
        guard !closed else { return }
        isForeground = true
        logLifecycle()
    }
    func backgrounded() {
        guard !closed else { return }
        if isForeground { stopGeneration += 1 }
        isForeground = false; nextCountdown = nil
        trackLabels.cancelOpen(); pauseIntent = true; isPaused = true
        pausePlayer(); enqueueProgress(); logLifecycle()
    }
    private func logLifecycle() {
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("lifecycle foreground=\(self.isForeground) paused=\(self.pauseIntent) positionMs=\(self.currentTicks / 10_000)")
    }
    private func logPlayState() {
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("play-state playing=\(self.player.timeControlStatus == .playing) paused=\(self.pauseIntent) foreground=\(self.isForeground) positionMs=\(self.currentTicks / 10_000)")
    }
    @discardableResult func seek(by seconds: Double) -> Int64 {
        let upper = durationSeconds.map(Ticks.from(seconds:)) ?? Int64.max
        let target = SeekSteps.target(positionMs: currentTicks / 10_000, pendingMs: pendingSeekTicks.map { $0 / 10_000 }, deltaMs: Ticks.from(seconds: seconds) / 10_000, durationMs: upper / 10_000) * 10_000
        seek(toTicks: target)
        return target
    }
    func seek(toTicks ticks: Int64) {
        guard !closed else { return }
        if pendingSeekTicks == nil { seekFallbackTicks = currentTicks }
        nativeSeekPending = false; (player as? ViewingAVPlayer)?.arm(nil)
        seekRevision += 1
        let revision = seekRevision, version = generation
        let upper = durationSeconds.map(Ticks.from(seconds:)) ?? Int64.max
        let target = min(upper, max(0, ticks)); pendingSeekTicks = target; positionTicks = target; lastSeekTime = Date()
        countdownElapsed = 0
        guard timelineReady else { positionTicks = target; return }
        player.seek(to: CMTime(seconds: Ticks.seconds(target), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            Task { @MainActor in
                // AVPlayer can finish an earlier seek after another seek or a
                // track/episode replacement. It must not publish into that stream.
                guard let self, !self.closed, self.generation == version,
                      self.seekRevision == revision, self.pendingSeekTicks == target else { return }
                self.pendingSeekTicks = nil
                let observed = self.player.currentTime().seconds
                self.positionTicks = success ? target : max(0, observed.isFinite ? Ticks.from(seconds: observed) : self.seekFallbackTicks ?? 0)
                self.seekFallbackTicks = nil
                // A media Play/Pause received during the asynchronous seek is newer than
                // the intent at seek start. Apply that current intent on completion.
                if self.pauseIntent { self.pausePlayer() } else { self.playPlayer() }
                self.isPaused = self.pauseIntent
                self.enqueueProgress()
            }
        }
    }
    var durationSeconds: Double? {
        let duration = player.currentItem?.duration.seconds ?? .nan
        return duration.isFinite && duration > 0 && duration <= 86400 ? duration : nil
    }
    var subtitleTimingAvailable: Bool { externalSubtitles.selected != nil }
    func adjustSubtitleDelay(by milliseconds: Int?) {
        guard subtitleTimingAvailable else { return }
        subtitleDelayMs = milliseconds.map { max(-10_000, min(10_000, subtitleDelayMs + $0)) / 100 * 100 } ?? 0
        refreshSubtitleCue()
    }
    private func refreshSubtitleCue() {
        let seconds = player.currentTime().seconds
        let fullMs = (seconds.isFinite ? Ticks.from(seconds: seconds) : positionTicks) / 10_000
        subtitleCue = externalSubtitles.text(atFullMediaMs: fullMs - Int64(subtitleDelayMs))
    }
    var subtitleTimingExplanation: String {
        if externalSubtitles.selected != nil {
            return "These external text subtitles use Pictureshow’s video overlay. Earlier and Later adjust timing for this viewing."
        }
        return decision?.method == .transcode && (decision?.subtitleIndex ?? -1) >= 0
        ? "These subtitles are burned into the video by the server. Timing and appearance cannot be changed."
        : "AVKit uses the system caption renderer. Per-viewing subtitle timing is not exposed by Apple's public player API."
    }
    private var currentTicks: Int64 {
        if let pendingSeekTicks { return pendingSeekTicks }
        guard timelineReady else { return positionTicks }
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, Ticks.from(seconds: seconds)) : positionTicks
    }
    private func tick() {
        guard timelineReady, !closed else { return }
        positionTicks = currentTicks
        verifyPlaybackRate()
        refreshSubtitleCue()
        let now = Date(), elapsed = min(1, now.timeIntervalSince(lastTickTime)); lastTickTime = now
        let waiting = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
        if !pauseIntent && (waiting || lastObservedTicks == positionTicks) {
            if stalledSince == nil { stalledSince = now }
            if now.timeIntervalSince(stalledSince!) > 12 { status = "Still waiting for playback. You can go back and try again." }
            if now.timeIntervalSince(stalledSince!) > 45 {
                Logger(subsystem: "app.pictureshow", category: "Playback").error("Playback clock stalled past recovery deadline")
                Task { await failed() }; stalledSince = nil
            }
        } else {
            if stalledSince != nil, error == nil {
                status = playbackMethodStatus
            }
            stalledSince = nil
        }
        lastObservedTicks = positionTicks
        if now.timeIntervalSince(lastReportTime) >= 5 { enqueueProgress(); lastReportTime = now }
        let modes = preferences.segmentModes
        skipSegment = PlaybackExtras.activeSegment(segments, positionMs: positionTicks / 10_000, modes: modes)
        if let segment = skipSegment, modes[segment.type] == .automatic, !pauseIntent, !waiting,
           !skipped.contains(segment.id) { skipped.insert(segment.id); seek(toTicks: segment.endTicks); skipSegment = nil }
        updateNextCard(elapsed: elapsed, now: now, waiting: waiting)
    }
    /// Presentation adapter: the portable policy owns the displayed wall-clock number.
    /// The media clock remains unchanged by playback speed.
    func updateNextCard(elapsed: Double, now: Date = Date(), waiting: Bool = false, reader: Bool? = nil, durationSeconds observedDuration: Double? = nil, mediaPositionTicks observedPosition: Int64? = nil, preparing: Bool? = nil) {
        let positionTicks = observedPosition ?? self.positionTicks
        guard let duration = observedDuration ?? durationSeconds, nextItem != nil, !continuationCancelled,
              let trigger = PlaybackExtras.nextTrigger(durationMs: Int64(duration * 1000), segments: segments),
              positionTicks / 10_000 >= trigger else { nextCardVisible = false; nextCountdown = nil; return }
        nextCardVisible = true
        let credits = PlaybackExtras.creditsStart(durationMs: Int64(duration * 1000), segments: segments) != nil
        #if os(tvOS)
        let transportObscures = controlsVisible
        #else
        let transportObscures = false
        #endif
        let suspended = transportObscures || !isForeground || pauseIntent || waiting || (preparing ?? waitingForVideo) || panelOpen || now.timeIntervalSince(lastSeekTime) <= 2
        let autoPlay = !(reader ?? UIAccessibility.isVoiceOverRunning)
        if credits && !suspended && autoPlay { countdownElapsed += max(0, min(1, elapsed)) }
        nextCountdown = NextEpisodeCard.seconds(wallSeconds: max(0, 10 - Int64(countdownElapsed)),
            creditsCountdown: credits, mediaLeftMs: max(0, Int64(duration * 1_000) - positionTicks / 10_000),
            speed: playbackRate, autoPlay: autoPlay, ended: false, suspended: suspended).map(Int.init)
        if credits && countdownElapsed >= 10 && !suspended && autoPlay { Task { await advance(automatic: true) } }
    }
    func skip() { guard let segment = skipSegment else { return }; skipped.insert(segment.id); seek(toTicks: segment.endTicks); skipSegment = nil }
    func cancelContinuation() { continuationCancelled = true; nextCardVisible = false; nextCountdown = nil }
    private func loadExtras() {
        let item = item
        extrasTask = Task { [weak self] in
            guard let self else { return }
            do { let full = try await client.playbackExtras(account: account, itemID: item.id); guard !closed, self.item.id == item.id else { return }; chapters = full.chapters; extrasItem = full; installChapterMarkers() }
            catch { extrasError = "Chapter metadata unavailable. Playback remains available." }
            do { let values = try await client.segments(account: account, itemID: item.id); guard !closed, self.item.id == item.id else { return }; segments = values }
            catch { extrasError = "Skip markers unavailable. Playback remains available." }
            do { let value = try await client.nextEpisode(account: account, item: item); guard !closed, self.item.id == item.id else { return }; nextItem = value; nextLookupFailed = false }
            catch { nextLookupFailed = true; extrasError = "Next episode unavailable. It will be retried at the end." }
            nextLookupPending = false
        }
    }
    func physicalEnd(readerRequiresManual: Bool? = nil) async {
        guard !closed, !switching, isForeground, !pauseIntent, pendingSeekTicks == nil else { return }
        captureCompletionChoiceRevision(for: item.id)
        let lifecycleVersion = stopGeneration
        let manualContinuation = readerRequiresManual ?? UIAccessibility.isVoiceOverRunning
        if item.type == "Episode", !continuationCancelled {
            if nextLookupPending { status = "Finding next episode…"; await extrasTask?.value }
            if nextLookupFailed { status = "Finding next episode…"; do { nextItem = try await client.nextEpisode(account: account, item: item); nextLookupFailed = false } catch { extrasError = "Could not find the next episode." } }
            guard !closed, isForeground, !pauseIntent, stopGeneration == lifecycleVersion else { return }
            if nextItem != nil, !manualContinuation { await advance(automatic: true); return }
            if nextItem != nil {
                // Reader mode changes continuation, not whether a genuine EOS
                // completed this item. Retain completion errors for detail Retry.
                _ = await completeIfNeeded()
                guard !closed, isForeground, stopGeneration == lifecycleVersion else { return }
                nextCardVisible = true; nextCountdown = nil; pauseIntent = true; isPaused = true; return
            }
        }
        _ = await completeIfNeeded()
        guard !closed, isForeground, stopGeneration == lifecycleVersion else { return }
        await stop()
    }
    private func completeIfNeeded() async -> Bool {
        if completionCommitted { return true }
        if let completionTask { return await completionTask.value }
        let itemID = item.id
        if let duration = durationSeconds { positionTicks = PlaybackEndPosition.completed(currentTicks / 10_000, durationMs: Int64(duration * 1000)) * 10_000; completedTicks = positionTicks }
        captureCompletionChoiceRevision(for: itemID)
        let choiceRevision = completionChoiceRevisions[itemID] ?? 0
        let token = onCompletionBegan?(itemID, choiceRevision)
        if let token { completionOwners[itemID] = token }
        let task = Task { [self] in
            var succeeded = false
            defer { if let token { onCompletionFinished?(itemID, token, succeeded) } }
            if let token, onCompletionMayApply?(itemID, token) == false {
                completionCommitted = true; failedPlayedID = nil
                return true
            }
            do {
                _ = try await client.played(account: account, itemID: itemID, value: true)
                try Task.checkCancellation()
                completionCommitted = true; failedPlayedID = nil
                succeeded = token.map { onCompletionMayApply?(itemID, $0) ?? true } ?? true
                return true
            } catch { failedPlayedID = itemID; reportError = "The server could not mark this title watched. Retry saving progress from details."; return false }
        }
        completionTask = task
        let result = await task.value; completionTask = nil; return result
    }
    func advance(automatic: Bool = false) async {
        guard !switching, !closed, isForeground, (!automatic || !pauseIntent), let next = nextItem else { return }
        captureCompletionChoiceRevision(for: item.id)
        let lifecycleVersion = stopGeneration
        switching = true
        guard await completeIfNeeded(), !closed else {
            error = "Completion could not be saved. Go back to details and retry saving progress before continuing."
            pauseIntent = true; isPaused = true; pausePlayer(); switching = false; return
        }
        // A background transition during an awaited completion cancels this route,
        // even if the viewer already returned. Keep the completed destination.
        guard isForeground, stopGeneration == lifecycleVersion, (!automatic || !pauseIntent) else { switching = false; return }
        await finishViewing()
        guard !closed, reportError == nil else {
            if !closed { error = "Progress could not be saved. Go back to details and retry saving progress before continuing." }
            switching = false; return
        }
        guard isForeground, stopGeneration == lifecycleVersion, (!automatic || !pauseIntent) else { switching = false; return }
        externalSubtitles.release(); subtitleCue = nil; subtitleDelayMs = 0
        speedState.beginNextViewing(); playbackRate = speedState.rate; speedLimitation = nil; player.defaultRate = Float(playbackRate)
        item = next; decision = nil; completedTicks = nil; pendingSeekTicks = nil; seekFallbackTicks = nil; nativeSeekPending = false
        viewingID = UUID(); reportSequence = 0; audioChosenByViewer = false; subtitleChosenByViewer = false; subtitleSelectionCount = 0; observedSubtitleSelection = false; observedSubtitleIndex = nil; started = false; stopped = false; completionCommitted = false; fallbackUsed = false; hevcFallbackUsed = false
        nextCardVisible = false; continuationCancelled = false; countdownElapsed = 0; nextCountdown = nil; nextItem = nil; nextLookupPending = true; nextLookupFailed = false
        segments = []; chapters = []; skipped = []; extrasTask = nil; pauseIntent = false
        do { try await prepare(startTicks: 0, audio: nil, subtitle: nil) } catch { self.error = "Could not start the next episode. Go back and try again." }
        switching = false
    }
    /// Snapshot only after the previous attempt completes. A queued periodic report
    /// observes the viewer's current selection instead of replaying an obsolete body.
    private func enqueueReport(decision: PlayDecision, kind: String, ticks: Int64, paused: Bool) {
        if kind == "progress", reportsQueued >= 2 { return }
        let viewing = viewingID
        reportsQueued += 1
        let previous = reportTail
        reportTail = Task { [self] in
            await previous?.value
            defer { reportsQueued -= 1 }
            guard viewingID == viewing else { return }
            let value = kind == "stop" ? decision : self.decision ?? decision
            let position = kind == "progress" ? currentTicks : ticks
            let pause = kind == "stop" ? paused : pauseIntent || !isForeground
            let succeeded = await attemptReport(value, kind: kind, ticks: position, paused: pause)
            // Start/Progress failure is visible but is never replayed as an old snapshot.
            // A later current check-in may recover. Stop gets one immediate retry.
            if !succeeded, kind == "stop" {
                _ = await attemptReport(value, kind: kind, ticks: position, paused: pause)
            }
        }
    }
    @discardableResult private func attemptReport(_ value: PlayDecision, kind: String, ticks: Int64, paused: Bool) async -> Bool {
        let report = reportingDecision(value)
        let audioViewer = audioChosenByViewer, subtitleViewer = subtitleChosenByViewer
        let body = kind == "stop"
            ? PlaybackReports.stopped(decision: report, positionTicks: max(0, ticks), failed: error != nil)
            : PlaybackReports.progress(decision: report, positionTicks: max(0, ticks), paused: paused,
                                       audioIndex: report.audioIndex, subtitleIndex: report.subtitleIndex,
                                       isMuted: value.audioIndex == -1)
        reportSequence += 1
        let sequence = reportSequence
        let fields = PlaybackReportFields.describe(kind: kind, positionTicks: max(0, ticks), audio: report.audioIndex,
                                                   subtitle: report.subtitleIndex, audioViewer: audioViewer, subtitleViewer: subtitleViewer)
        let item = value.itemID, session = value.playSessionId ?? "-"
        let outcome = await client.sendReportAttempt(account: account, kind: kind, body: body)
        let line = "report item=\(item) session=\(session) seq=\(sequence) \(fields) status=\(outcome.status.map(String.init) ?? "none") failure=\(outcome.failure) elapsedMs=\(outcome.elapsedMs) paused=\(kind == "stop" ? "omitted" : String(paused))"
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("\(line, privacy: .public)")
        diagnostics.report(line)
        if outcome.succeeded {
            failedStart = nil; failedProgress = nil
            if kind == "stop" { failedStop = nil }
            clearReportErrorIfRecovered()
        } else {
            if kind == "progress" || kind == "start" { failedProgress = (report, ticks, paused) }
            if kind == "stop" { failedStop = (report, ticks, error != nil) }
            reportError = "The server could not save progress. Resume may be unavailable. Check your connection."
        }
        return outcome.succeeded
    }
    private func enqueueProgress() { if started, !stopped, let decision { enqueueReport(decision: decision, kind: "progress", ticks: currentTicks, paused: pauseIntent) } }
    func beginClosing() {
        guard !closed else { return }; trackLabels.cancelOpen(); closed = true; generation += 1; pausePlayer()
        for observer in lifecycleObservers { NotificationCenter.default.removeObserver(observer) }
        lifecycleObservers = []
        externalSubtitles.release(); subtitleCue = nil
        closeTask = Task { [self] in await finishViewing(); terminal = true }
    }
    func stop() async { beginClosing(); await closeTask?.value }
    private func releaseStream() async {
        positionTicks = completedTicks ?? currentTicks; generation += 1; timelineReady = false
        (player as? ViewingAVPlayer)?.arm(nil)
        nativeSelectionBaseline = [:]; nativeSelectionRevision += 1
        timeoutTask?.cancel(); timeoutTask = nil
        startTask?.cancel(); await startTask?.value; startTask = nil
        statusObservation = nil; controlObservation = nil
        clockTask?.cancel(); clockTask = nil
        if let endedObservation { NotificationCenter.default.removeObserver(endedObservation); self.endedObservation = nil }
        if let selectionObservation { NotificationCenter.default.removeObserver(selectionObservation); self.selectionObservation = nil }
        pausePlayer(); player.replaceCurrentItem(with: nil)
        if let decision {
            do { try await client.stopEncoding(account: account, decision: decision) }
            catch { failedEncodings.append(decision); reportError = "The server could not stop an encoding. Retry saving progress from details." }
        }
    }
    var completionReportingFailed: Bool { failedPlayedID != nil || failedStop != nil }
    var requiresPlayedSave: Bool { failedPlayedID != nil || (completionTask != nil && !completionCommitted) }
    private func captureCompletionChoiceRevision(for itemID: String) {
        if completionChoiceRevisions[itemID] == nil {
            completionChoiceRevisions[itemID] = onCompletionChoiceRevision?(itemID) ?? 0
        }
    }
    func retrySaving() async {
        await reportTail?.value
        if let failedPlayedID {
            if let previousOwner = completionOwners[failedPlayedID], onCompletionMayApply?(failedPlayedID, previousOwner) == false {
                self.failedPlayedID = nil; completionCommitted = true
            } else {
            captureCompletionChoiceRevision(for: failedPlayedID)
            let choiceRevision = completionChoiceRevisions[failedPlayedID] ?? 0
            let token = onCompletionBegan?(failedPlayedID, choiceRevision)
            if let token { completionOwners[failedPlayedID] = token }
            var succeeded = false
            defer { if let token { onCompletionFinished?(failedPlayedID, token, succeeded) } }
            do {
                _ = try await client.played(account: account, itemID: failedPlayedID, value: true)
                try Task.checkCancellation()
                self.failedPlayedID = nil; completionCommitted = true
                succeeded = token.map { onCompletionMayApply?(failedPlayedID, $0) ?? true } ?? true
            } catch { reportError = "The server could not mark this title watched. Check your connection and retry." }
            }
        }
        if failedStop == nil, failedProgress != nil, let finalReport { failedStop = finalReport }
        if let (decision, ticks, _) = failedStop {
            guard await attemptReport(decision, kind: "stop", ticks: ticks, paused: true) else { return }
        }
        let pending = failedEncodings; failedEncodings = []
        for decision in pending {
            do { try await client.stopEncoding(account: account, decision: decision) }
            catch { failedEncodings.append(decision) }
        }
        clearReportErrorIfRecovered()
        if let token = completionOwners[item.id] { onCompletionReportFinished?(item.id, token, !completionReportingFailed) }
        if !failedEncodings.isEmpty { reportError = "The server could not stop an encoding. Check your connection and retry." }
    }
    private func clearReportErrorIfRecovered() {
        if failedStart == nil && failedProgress == nil && failedStop == nil && failedPlayedID == nil && failedEncodings.isEmpty { reportError = nil }
    }
    private func finishViewing() async {
        guard !stopped else { return }; positionTicks = completedTicks ?? currentTicks; stopped = true
        extrasTask?.cancel(); timeoutTask?.cancel()
        if let completionTask { _ = await completionTask.value }
        let last = decision.map(reportingDecision)
        await releaseStream()
        if started, let last { finalReport = (last, positionTicks, error != nil); enqueueReport(decision: last, kind: "stop", ticks: positionTicks, paused: true) }
        await reportTail?.value
        if let token = completionOwners[item.id] { onCompletionReportFinished?(item.id, token, !completionReportingFailed) }
        status = "Stopped"; waitingForVideo = false
    }
    /// A panel submits only the chosen type. The other type is read when this
    /// serialized command executes, after any earlier replacement completes.
    func selectTrack(type: String, index: Int, menuGeneration: Int? = nil) async {
        guard type == "Audio" || type == "Subtitle", !closed else { return }
        let viewing = viewingID
        let previous = trackTail
        let command = Task { [weak self] in
            await previous?.value
            guard let self, !self.closed, self.viewingID == viewing, let current = self.decision,
                  menuGeneration == nil || self.trackLabels.prepared?.generation == menuGeneration else { return }
            self.selectionIntentRevision += 1
            if type == "Audio" { self.audioChosenByViewer = true }
            else {
                self.subtitleChosenByViewer = true
                self.observeSubtitleSelection(index, explicit: true)
            }
            let selected = type == "Audio" ? current.audioIndex : current.subtitleIndex
            if let selected, TrackSelection.unchanged(currentIndex: selected, pickedIndex: index) {
                if self.timelineReady { self.enqueueProgress() }
                return // A same-active pick changes provenance only.
            }
            await self.applyTracks(audio: type == "Audio" ? index : current.audioIndex,
                                   subtitle: type == "Subtitle" ? index : current.subtitleIndex)
        }
        trackTail = command
        await command.value
    }
    /// Decoder recovery/legacy callers preserve provenance. Viewer UI uses selectTrack.
    func changeTracks(audio: Int?, subtitle: Int?, forceVideoTranscode: Bool = false) async {
        let viewing = viewingID
        let previous = trackTail
        let command = Task { [weak self] in
            await previous?.value
            guard let self, !self.closed, self.viewingID == viewing else { return }
            await self.applyTracks(audio: forceVideoTranscode ? self.decision?.audioIndex : audio, subtitle: forceVideoTranscode ? self.decision?.subtitleIndex : subtitle, forceVideoTranscode: forceVideoTranscode)
        }
        trackTail = command
        await command.value
    }
    private func applyTracks(audio: Int?, subtitle: Int?, forceVideoTranscode: Bool = false) async {
        guard !closed else { return }
        if !forceVideoTranscode, let current = decision,
           audio == current.audioIndex, subtitle == current.subtitleIndex { enqueueProgress(); return }
        if !forceVideoTranscode, var current = decision, audio == current.audioIndex,
           externalSubtitles.selected != nil || current.subtitleIndex == -1,
           subtitle == -1 || externalTextStream(subtitle, source: current.source) != nil {
            current.subtitleIndex = subtitle; decision = current
            observeSubtitleSelection(current.subtitleIndex)
            selectExternalText(for: current); subtitleCue = externalSubtitles.text(atFullMediaMs: currentTicks / 10_000 - Int64(subtitleDelayMs))
            enqueueProgress()
            return // A text choice does not replace, pause or seek the A/V item.
        }
        changingTracks = true
        externalSubtitles.select(nil); subtitleCue = nil
        let position = pendingSeekTicks ?? currentTicks
        enqueueProgress(); await reportTail?.value; await releaseStream()
        guard !closed else { changingTracks = false; return }
        pendingSeekTicks = pendingSeekTicks ?? position
        do { try await prepare(startTicks: pendingSeekTicks ?? position, audio: audio, subtitle: subtitle, forceHLS: true, forceVideoTranscode: forceVideoTranscode) }
        catch { self.error = "Could not apply the selected tracks. Go back and try again." }
        changingTracks = false
    }
    private func reportingDecision(_ value: PlayDecision) -> PlayDecision {
        var report = value
        report.audioIndex = TrackSelectionReporting.index(selectedIndex: value.audioIndex, chosenByViewer: audioChosenByViewer)
        report.subtitleIndex = TrackSelectionReporting.index(selectedIndex: value.subtitleIndex, chosenByViewer: subtitleChosenByViewer)
        return report
    }
    /// One logical receipt per accepted open/changed choice or explicit pick.
    /// A prepare following that pick reuses the receipt rather than logging twice.
    private func observeSubtitleSelection(_ index: Int?, explicit: Bool = false) {
        guard explicit || !observedSubtitleSelection || observedSubtitleIndex != index else { return }
        observedSubtitleSelection = true; observedSubtitleIndex = index; subtitleSelectionCount += 1
        diagnostics.record("subtitle-select")
        let stream = index.map { $0 == -1 ? "off" : String($0) } ?? "unknown"
        Logger(subsystem: "app.pictureshow", category: "Playback").notice("subtitle-select stream=\(stream, privacy: .public) paused=\(self.pauseIntent) positionMs=\(self.currentTicks / 10_000) selectionCount=\(self.subtitleSelectionCount) item=\(self.item.id, privacy: .public) viewing=\(self.viewingID.uuidString, privacy: .public)")
    }
    private func externalTextStream(_ index: Int?, source: MediaSource?) -> MediaStream? {
        source?.mediaStreams.first { stream in
            stream.index == index && stream.type == "Subtitle"
                && (stream.isExternal || stream.isExternalURL || stream.deliveryMethod?.lowercased() == "external")
                // Encode describes the server profile proposal. A known external
                // text file still loads separately while the A/V request uses Off.
                && ["srt", "subrip", "vtt", "webvtt"].contains(stream.codec?.lowercased() ?? "")
        }
    }
    private func selectExternalText(for decision: PlayDecision) {
        externalSubtitles.setContext(itemID: item.id, viewingID: viewingID.uuidString)
        guard let stream = externalTextStream(decision.subtitleIndex, source: decision.source),
              let sourceID = decision.source.id else { externalSubtitles.select(nil); subtitleCue = nil; return }
        do {
            let reference: URL
            if let delivery = stream.deliveryURL, !delivery.isEmpty {
                reference = try ServerURLs.resolve(server: account.server, reference: delivery)
            } else {
                reference = try ServerURLs.endpoint(account.server,
                    path: "Videos/\(ServerURLs.encode(item.id))/\(ServerURLs.encode(sourceID))/Subtitles/\(stream.index)/Stream.vtt",
                    query: [URLQueryItem(name: "StartPositionTicks", value: "0"), URLQueryItem(name: "CopyTimestamps", value: "true")])
            }
            let url = try ServerPlaybackURL.make(decisionURL: reference, server: account.server.url, token: account.accessToken)
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
            let rebased = query.contains { $0.name.lowercased() == "copytimestamps" && $0.value?.lowercased() == "false" }
            let originTicks = query.first { $0.name.lowercased() == "startpositionticks" }?.value.flatMap(Int64.init) ?? 0
            // AVPlayer uses full-media time on both static and VOD HLS paths. Only
            // server-rebased text needs its extraction origin added back.
            let originMs = rebased ? max(0, originTicks / 10_000) : 0
            let format = reference.pathExtension.lowercased() == "vtt" ? "vtt" : (stream.codec ?? "vtt")
            externalSubtitles.select(.init(id: "\(item.id)|\(sourceID)|\(stream.index)|\(originMs)", url: url, format: format, originMs: originMs, streamIndex: stream.index, sourceID: sourceID, selectionCount: subtitleSelectionCount))
        } catch {
            // An unsafe optional subtitle route cannot fail the negotiated video.
            externalSubtitles.select(nil); subtitleCue = nil
            extrasError = "This subtitle address is unavailable. Video playback can continue."
        }
    }
    private func failed() async {
        trackLabels.cancelOpen()
        guard !changingTracks, !closed else { return }
        let failure = player.currentItem?.error as NSError?
        Logger(subsystem: "app.pictureshow", category: "Playback").error("Native playback failure domain=\(failure?.domain ?? "unknown", privacy: .public) code=\(failure?.code ?? 0)")
        if let underlying = failure?.userInfo[NSUnderlyingErrorKey] as? NSError { Logger(subsystem: "app.pictureshow", category: "Playback").error("Underlying domain=\(underlying.domain, privacy: .public) code=\(underlying.code)") }
        let isHEVC = decision.map { AppleHEVCTranscoding.isHEVC($0) } ?? false
        guard ApplePlaybackRecovery.canRetry(fallbackUsed: fallbackUsed, hevcFallbackUsed: hevcFallbackUsed, isHEVCTranscode: isHEVC) else { error = "Playback failed. Go back to details and try again."; await stop(); return }
        // HEVC gets one separate AVC recovery even after an earlier Direct retry.
        // Keep this suppression for all later track changes in the same viewing.
        if isHEVC { hevcFallbackUsed = true }
        fallbackUsed = true
        await changeTracks(audio: decision?.audioIndex, subtitle: decision?.subtitleIndex, forceVideoTranscode: true)
    }
    private func recordNativeSelection(_ avItem: AVPlayerItem?, establishingBaseline: Bool = false) async {
        guard let avItem, player.currentItem === avItem, (timelineReady || establishingBaseline), !closed,
              var value = decision, value.method != .transcode else { return }
        let version = generation
        let intentRevision = selectionIntentRevision
        nativeSelectionRevision += 1
        let revision = nativeSelectionRevision
        var indices: [String: Int] = [:]
        for type in ["Audio", "Subtitle"] {
            if type == "Audio", player.isMuted, value.audioIndex == -1 { continue }
            if type == "Subtitle", externalSubtitles.selected != nil { continue }
            let characteristic: AVMediaCharacteristic = type == "Audio" ? .audible : .legible
            guard let group = try? await avItem.asset.loadMediaSelectionGroup(for: characteristic) else { continue }
            let option = avItem.currentMediaSelection.selectedMediaOption(in: group)
            let candidates = value.source.mediaStreams.filter { stream in
                guard stream.type == type, !stream.isExternal else { return false }
                let language = PlaybackSelection.language(option?.extendedLanguageTag ?? option?.locale?.language.languageCode?.identifier)
                return language == nil || PlaybackSelection.language(stream.language) == language
            }
            let title = option?.displayName.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let titled = candidates.filter { stream in [stream.title, stream.displayTitle].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }.contains(title ?? "") }
            let index = option == nil ? -1 : candidates.count == 1 ? candidates[0].index : titled.count == 1 ? titled[0].index : nil
            if let index { indices[type] = index }
        }
        guard player.currentItem === avItem, !closed, generation == version, nativeSelectionRevision == revision, selectionIntentRevision == intentRevision else { return }
        var changed = false
        for (type, index) in indices {
            if !establishingBaseline, let baseline = nativeSelectionBaseline[type], baseline != index {
                if type == "Audio" { audioChosenByViewer = true }
                else { subtitleChosenByViewer = true; observeSubtitleSelection(index, explicit: true) }
                changed = true
            }
            if type == "Audio" { value.audioIndex = index }
            else {
                value.subtitleIndex = index
                if establishingBaseline { observeSubtitleSelection(index) }
            }
        }
        nativeSelectionBaseline = indices
        decision = value
        if changed { enqueueProgress() }
    }
    func chapterImage(index: Int) async -> UIImage? {
        guard chapters.indices.contains(index), let tag = chapters[index].imageTag,
              let url = client.chapterImageURL(account: account, itemID: item.id, index: index, tag: tag) else { return nil }
        return await image(url)
    }
    func seekPreview(ticks: Int64) async -> UIImage? {
        guard let item = extrasItem, let source = decision?.source.id,
              let info = PlaybackExtras.chooseTrickplay(item: item, mediaSourceID: source, preferredWidth: 320),
              let tile = PlaybackExtras.trickplayTile(info, positionMs: ticks / 10_000),
              let url = client.trickplaySheetURL(account: account, itemID: item.id, mediaSourceID: source, width: info.width, sheet: tile.sheet),
              let sheet = await image(url), let cg = sheet.cgImage,
              let crop = cg.cropping(to: CGRect(x: tile.column * info.width, y: tile.row * info.height, width: info.width, height: info.height)) else { return nil }
        return UIImage(cgImage: crop)
    }
    private func image(_ url: URL) async -> UIImage? {
        if let cached = imageCache[url] { return cached }
        guard let data = try? await client.imageData(account: account, url: url), let image = UIImage(data: data) else { return nil }
        if imageCache.count >= 3 { imageCache.removeAll() }
        imageCache[url] = image; return image
    }
    private func installChapterMarkers() {
        #if os(tvOS)
        let groups = chapters.enumerated().map { index, chapter in
            let title = AVMutableMetadataItem(); title.identifier = .commonIdentifierTitle
            title.value = (chapter.name.isEmpty ? "Chapter \(index + 1)" : chapter.name) as NSString; title.extendedLanguageTag = "und"
            return AVTimedMetadataGroup(items: [title], timeRange: CMTimeRange(start: CMTime(seconds: Double(chapter.startMs) / 1000, preferredTimescale: 600), duration: CMTime(seconds: 1, preferredTimescale: 600)))
        }
        player.currentItem?.navigationMarkerGroups = groups.isEmpty ? [] : [AVNavigationMarkersGroup(title: nil, timedNavigationMarkers: groups)]
        #endif
    }
}

/// Non-published counters avoid triggering the view work they measure. Tests can
/// inject a sink; payloads never contain subtitle text, URLs or credentials.
@MainActor final class PlaybackDiagnostics {
    private(set) var counts: [String: Int] = [:]
    private let sink: (String, Int) -> Void
    private(set) var reportLines: [String] = []
    init(sink: @escaping (String, Int) -> Void = { _, _ in }) { self.sink = sink }
    func report(_ line: String) {
        if reportLines.count >= 64 { reportLines.removeFirst() }
        reportLines.append(line); record("report")
    }
    func record(_ event: String) {
        counts[event, default: 0] += 1
        sink(event, counts[event, default: 0])
    }
}
