// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import AVKit
import PictureshowCore
import PictureshowTransport
import OSLog
import MediaAccessibility

/// A viewing owns reports. Decoder replacements own encoders, never another viewing Start/Stop.
@MainActor final class PlaybackSession: ObservableObject, Identifiable {
    let id = UUID()
    let player: AVPlayer
    @Published private(set) var item: MediaItem
    @Published private(set) var decision: PlayDecision?
    @Published private(set) var status = "Preparing playback…"
    @Published private(set) var error: String?
    @Published private(set) var reportError: String?
    @Published private(set) var positionTicks: Int64 = 0
    @Published private(set) var changingTracks = false
    @Published private(set) var terminal = false
    @Published private(set) var isPaused = false
    @Published private(set) var waitingForVideo = true
    @Published private(set) var chapters: [Chapter] = []
    @Published private(set) var skipSegment: MediaSegment?
    @Published private(set) var nextItem: MediaItem?
    @Published private(set) var nextCountdown: Int?
    @Published private(set) var extrasError: String?
    @Published var panelOpen = false
    @Published var controlsVisible = false
    @Published private(set) var subtitleDelayMs = 0
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
    private var failedStart: (PlayDecision, Int64, Bool)?
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
    private var closed = false
    private var generation = 0
    private var closeTask: Task<Void, Never>?
    private var pauseIntent = false
    private var pendingSeekTicks: Int64?
    private var segments: [MediaSegment] = []
    private var extrasItem: MediaItem?
    private var imageCache: [URL: UIImage] = [:]
    private var skipped = Set<String>()
    private var continuationCancelled = false
    private var nextLookupFailed = false
    private var nextLookupPending = true
    private var switching = false
    private var completionCommitted = false
    private var completedTicks: Int64?
    private var lastReportTime = Date.distantPast
    private var lastTickTime = Date()
    private var countdownElapsed: Double = 0
    private var lastSeekTime = Date.distantPast
    private var stalledSince: Date?
    private var lastObservedTicks: Int64 = 0

    init(client: JellyfinClient, account: Account, item: MediaItem, preferences: PlaybackPreferences = .init(), player: AVPlayer = AVPlayer()) {
        self.client = client; self.account = account; self.item = item; self.preferences = preferences; self.player = player
        player.allowsExternalPlayback = true
        player.appliesMediaSelectionCriteriaAutomatically = false
    }
    func prepare(startTicks: Int64, audio: Int?, subtitle: Int?, forceHLS: Bool = false, forceVideoTranscode: Bool = false) async throws {
        guard !closed else { throw CancellationError() }
        let version = generation
        waitingForVideo = true; timelineReady = false; stopped = false; error = nil
        status = "Preparing playback…"
        try AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try AVAudioSession.sharedInstance().setActive(true)
        var selectedSubtitle = subtitle
        var captionLanguage: String?
        if subtitle == nil, MACaptionAppearanceGetDisplayType(.user) == .alwaysOn,
           let configuration = try? await client.configuration(account: account),
           let language = Captions.language(enabled: true, configuration: configuration, deviceLanguage: Locale.preferredLanguages.first ?? "") {
            captionLanguage = language
            if let source = decision?.source ?? item.mediaSources.first { selectedSubtitle = Captions.index(source: source, preferredLanguage: language) }
        }
        var next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks,
            audioIndex: audio, subtitleIndex: selectedSubtitle, forceTranscode: forceVideoTranscode,
            mediaSourceID: decision?.source.id ?? item.mediaSources.first?.id, preferences: preferences, forceHLS: forceHLS || audio != nil)
        guard !closed, generation == version else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        // Episode/list responses need not contain streams. Apply captions to the fresh
        // negotiated source, never to the previous episode's metadata.
        if let captionLanguage, let preferred = Captions.index(source: next.source, preferredLanguage: captionLanguage), next.subtitleIndex != preferred {
            let sourceID = next.source.id
            next = try await client.prepare(account: account, itemID: item.id, startTicks: startTicks,
                audioIndex: audio, subtitleIndex: preferred, forceTranscode: forceVideoTranscode,
                mediaSourceID: sourceID, preferences: preferences, forceHLS: forceHLS || audio != nil)
        }
        guard !closed, generation == version else { try? await client.stopEncoding(account: account, decision: next); throw CancellationError() }
        let url: URL
        do { url = try ServerPlaybackURL.make(decisionURL: next.url, server: account.server.url,
                                             token: account.accessToken, requiredHeaders: next.headers) }
        catch { try? await client.stopEncoding(account: account, decision: next); throw error }
        decision = next; positionTicks = startTicks
        status = next.method == .directPlay ? "Direct play" : next.method == .directStream ? "Direct stream" : "Server HLS"
        player.isMuted = audio == -1
        let avItem = AVPlayerItem(url: url)
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
        statusObservation = avItem.observe(\.status, options: [.new]) { [weak self] observed, _ in
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
                    if observed.timeControlStatus != .paused || !ended { self.pauseIntent = observed.timeControlStatus == .paused }
                    self.isPaused = self.pauseIntent
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
        player.replaceCurrentItem(with: avItem)
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
            guard let self, !self.timelineReady, self.generation == version, !self.closed else { return }
            self.status = "Still waiting for playback. You can go back and try again."
            do { try await Task.sleep(for: .seconds(33)) } catch { return }
            guard !self.timelineReady, self.generation == version, !self.closed else { return }
            self.error = "Playback took too long. Go back to details and try again."
            await self.stop()
        }
        if extrasTask == nil { loadExtras() }
    }
    private func ready(version: Int) {
        guard !timelineReady, startTask == nil, !closed, let decision else { return }
        startTask = Task { [weak self] in
            guard let self else { return }
            let target = self.pendingSeekTicks ?? decision.startTicks
            if target > 0 {
                let success = await self.player.seek(to: CMTime(seconds: Ticks.seconds(target), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
                guard success else { self.error = "The saved position could not be loaded. Try again."; self.waitingForVideo = false; return }
            }
            guard !Task.isCancelled, !self.closed, self.generation == version else { return }
            if decision.subtitleIndex == -1, decision.method != .transcode,
               let group = try? await self.player.currentItem?.asset.loadMediaSelectionGroup(for: .legible) { self.player.currentItem?.select(nil, in: group) }
            self.pendingSeekTicks = nil; self.timelineReady = true; self.waitingForVideo = false
            self.timeoutTask?.cancel(); self.timeoutTask = nil
            self.isPaused = self.pauseIntent
            if self.pauseIntent { self.player.pause() } else { self.player.play() }
            self.lastTickTime = Date(); self.lastObservedTicks = target; self.stalledSince = nil
            if !self.started {
                self.started = true
                self.enqueueReport(decision: decision, kind: "start", ticks: target, paused: self.pauseIntent)
            } else { self.enqueueProgress() }
            await self.recordNativeSelection(self.player.currentItem)
            self.installChapterMarkers()
        }
    }
    func togglePlayback() {
        guard !closed else { return }
        pauseIntent.toggle(); isPaused = pauseIntent
        if timelineReady { if pauseIntent { player.pause() } else { player.play() } }
        enqueueProgress()
    }
    func nativeSeekBegan(_ seconds: Double) { lastSeekTime = Date(); countdownElapsed = 0 }
    func backgrounded() { pauseIntent = true; isPaused = true; player.pause(); enqueueProgress() }
    func seek(by seconds: Double) { seek(toTicks: Ticks.adding(pendingSeekTicks ?? currentTicks, Ticks.from(seconds: seconds))) }
    func seek(toTicks ticks: Int64) {
        guard !closed else { return }
        let upper = durationSeconds.map(Ticks.from(seconds:)) ?? Int64.max
        let target = min(upper, max(0, ticks)); pendingSeekTicks = target; lastSeekTime = Date()
        countdownElapsed = 0
        guard timelineReady else { positionTicks = target; return }
        player.seek(to: CMTime(seconds: Ticks.seconds(target), preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero) { [weak self] success in
            Task { @MainActor in
                guard let self, !self.closed, success else { return }
                self.pendingSeekTicks = nil; self.positionTicks = target
                // A media Play/Pause received during the asynchronous seek is newer than
                // the intent at seek start. Apply that current intent on completion.
                if self.pauseIntent { self.player.pause() } else { self.player.play() }
                self.isPaused = self.pauseIntent
                self.enqueueProgress()
            }
        }
    }
    var durationSeconds: Double? {
        let duration = player.currentItem?.duration.seconds ?? .nan
        return duration.isFinite && duration > 0 && duration <= 86400 ? duration : nil
    }
    var subtitleTimingAvailable: Bool { false }
    var subtitleTimingExplanation: String {
        decision?.method == .transcode && (decision?.subtitleIndex ?? -1) >= 0
        ? "These subtitles are burned into the video by the server. Timing and appearance cannot be changed."
        : "AVKit uses the system caption renderer. Per-viewing subtitle timing is not exposed by Apple's public player API."
    }
    private var currentTicks: Int64 {
        guard timelineReady else { return pendingSeekTicks ?? positionTicks }
        let seconds = player.currentTime().seconds
        return seconds.isFinite ? max(0, Ticks.from(seconds: seconds)) : positionTicks
    }
    private func tick() {
        guard timelineReady, !closed else { return }
        positionTicks = currentTicks
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
            if stalledSince != nil, error == nil, let decision {
                status = decision.method == .directPlay ? "Direct play" : decision.method == .directStream ? "Direct stream" : "Server HLS"
            }
            stalledSince = nil
        }
        lastObservedTicks = positionTicks
        if now.timeIntervalSince(lastReportTime) >= 5 { enqueueProgress(); lastReportTime = now }
        let modes = preferences.segmentModes
        skipSegment = PlaybackExtras.activeSegment(segments, positionMs: positionTicks / 10_000, modes: modes)
        if let segment = skipSegment, modes[segment.type] == .automatic, !pauseIntent, !waiting,
           !skipped.contains(segment.id) { skipped.insert(segment.id); seek(toTicks: segment.endTicks); skipSegment = nil }
        guard let duration = durationSeconds, nextItem != nil, !continuationCancelled,
              let trigger = PlaybackExtras.nextTrigger(durationMs: Int64(duration * 1000), segments: segments),
              positionTicks / 10_000 >= trigger else { nextCountdown = nil; return }
        let credits = PlaybackExtras.creditsStart(durationMs: Int64(duration * 1000), segments: segments) != nil
        // tvOS reports native transport visibility. iOS has no equivalent public
        // AVKit callback, so keep its early credits action manual rather than
        // interrupt a viewer who may be using the native scrubber. Physical EOS
        // still uses the ordinary guarded continuation path on both platforms.
        #if os(tvOS)
        let permitsEarlyContinuation = true
        #else
        let permitsEarlyContinuation = false
        #endif
        if credits && permitsEarlyContinuation && !pauseIntent && !waiting && !panelOpen && !controlsVisible && now.timeIntervalSince(lastSeekTime) > 2 && !UIAccessibility.isVoiceOverRunning {
            countdownElapsed += elapsed
            nextCountdown = max(0, 10 - Int(countdownElapsed))
            if countdownElapsed >= 10 { Task { await advance() } }
        } else { nextCountdown = credits ? max(0, 10 - Int(countdownElapsed)) : max(0, Int(duration - Ticks.seconds(positionTicks))) }
    }
    func skip() { guard let segment = skipSegment else { return }; skipped.insert(segment.id); seek(toTicks: segment.endTicks); skipSegment = nil }
    func cancelContinuation() { continuationCancelled = true; nextCountdown = nil }
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
        guard !closed, !switching, !pauseIntent, pendingSeekTicks == nil else { return }
        let manualContinuation = readerRequiresManual ?? UIAccessibility.isVoiceOverRunning
        if item.type == "Episode", !continuationCancelled {
            if nextLookupPending { status = "Finding next episode…"; await extrasTask?.value }
            if nextLookupFailed { status = "Finding next episode…"; do { nextItem = try await client.nextEpisode(account: account, item: item); nextLookupFailed = false } catch { extrasError = "Could not find the next episode." } }
            if nextItem != nil, !manualContinuation { await advance(); return }
            if nextItem != nil {
                // Reader mode changes continuation, not whether a genuine EOS
                // completed this item. Retain completion errors for detail Retry.
                _ = await completeIfNeeded()
                nextCountdown = 0; pauseIntent = true; isPaused = true; return
            }
        }
        _ = await completeIfNeeded(); await stop()
    }
    private func completeIfNeeded() async -> Bool {
        if completionCommitted { return true }
        if let completionTask { return await completionTask.value }
        let itemID = item.id
        if let duration = durationSeconds { positionTicks = PlaybackEndPosition.completed(currentTicks / 10_000, durationMs: Int64(duration * 1000)) * 10_000; completedTicks = positionTicks }
        let task = Task { [self] in
            do {
                _ = try await client.played(account: account, itemID: itemID, value: true)
                completionCommitted = true; failedPlayedID = nil; return true
            } catch { failedPlayedID = itemID; reportError = "The server could not mark this title watched. Retry saving progress from details."; return false }
        }
        completionTask = task
        let result = await task.value; completionTask = nil; return result
    }
    func advance() async {
        guard !switching, !closed, let next = nextItem else { return }
        switching = true
        guard await completeIfNeeded(), !closed else {
            error = "Completion could not be saved. Go back to details and retry saving progress before continuing."
            pauseIntent = true; isPaused = true; player.pause(); switching = false; return
        }
        await finishViewing()
        guard !closed, reportError == nil else {
            if !closed { error = "Progress could not be saved. Go back to details and retry saving progress before continuing." }
            switching = false; return
        }
        item = next; decision = nil; completedTicks = nil; viewingID = UUID(); started = false; stopped = false; completionCommitted = false; fallbackUsed = false
        continuationCancelled = false; countdownElapsed = 0; nextCountdown = nil; nextItem = nil; nextLookupPending = true; nextLookupFailed = false
        segments = []; chapters = []; skipped = []; extrasTask = nil; pauseIntent = false
        do { try await prepare(startTicks: 0, audio: nil, subtitle: nil) } catch { self.error = "Could not start the next episode. Go back and try again." }
        switching = false
    }
    private func enqueueReport(decision: PlayDecision, kind: String, ticks: Int64, paused: Bool) {
        if kind == "progress", reportsQueued >= 2 { return }
        reportsQueued += 1
        #if DEBUG
        Logger(subsystem: "app.pictureshow", category: "Reports").notice("Queued report kind=\(kind, privacy: .public) viewing=\(self.viewingID.uuidString, privacy: .public) ticks=\(ticks) paused=\(paused)")
        #endif
        let previous = reportTail
        reportTail = Task { [self] in
            await previous?.value
            defer { reportsQueued -= 1 }
            do {
                if kind != "start", let (first, position, paused) = failedStart {
                    try await client.reportStart(account: account, decision: first, positionTicks: position, paused: paused)
                    failedStart = nil
                }
                if kind == "start" { try await client.reportStart(account: account, decision: decision, positionTicks: ticks, paused: paused) }
                else if kind == "stop" { try await client.reportStopped(account: account, decision: decision, positionTicks: ticks, failed: error != nil) }
                else { try await client.reportProgress(account: account, decision: decision, positionTicks: ticks, paused: paused) }
                #if DEBUG
                Logger(subsystem: "app.pictureshow", category: "Reports").notice("Accepted report kind=\(kind, privacy: .public) viewing=\(self.viewingID.uuidString, privacy: .public) ticks=\(ticks)")
                #endif
                if kind == "start" { failedStart = nil }
                if kind == "progress" { failedProgress = nil }
                if kind == "stop" { failedStop = nil; failedProgress = nil }
                clearReportErrorIfRecovered()
            } catch {
                if kind == "start" { failedStart = (decision, ticks, paused) }
                if kind == "progress" { failedProgress = (decision, ticks, paused) }
                if kind == "stop" { failedStop = (decision, ticks, self.error != nil) }
                reportError = "The server could not save progress. Resume may be unavailable. Check your connection."
            }
        }
    }
    private func enqueueProgress() { if started, !stopped, let decision { enqueueReport(decision: decision, kind: "progress", ticks: currentTicks, paused: pauseIntent) } }
    func beginClosing() {
        guard !closed else { return }; closed = true; generation += 1; player.pause()
        closeTask = Task { [self] in await finishViewing(); terminal = true }
    }
    func stop() async { beginClosing(); await closeTask?.value }
    private func releaseStream() async {
        positionTicks = completedTicks ?? currentTicks; generation += 1; timelineReady = false
        timeoutTask?.cancel(); timeoutTask = nil
        startTask?.cancel(); await startTask?.value; startTask = nil
        statusObservation = nil; controlObservation = nil
        clockTask?.cancel(); clockTask = nil
        if let endedObservation { NotificationCenter.default.removeObserver(endedObservation); self.endedObservation = nil }
        if let selectionObservation { NotificationCenter.default.removeObserver(selectionObservation); self.selectionObservation = nil }
        player.pause(); player.replaceCurrentItem(with: nil)
        if let decision {
            do { try await client.stopEncoding(account: account, decision: decision) }
            catch { failedEncodings.append(decision); reportError = "The server could not stop an encoding. Retry saving progress from details." }
        }
    }
    func retrySaving() async {
        await reportTail?.value
        if let failedPlayedID {
            do { _ = try await client.played(account: account, itemID: failedPlayedID, value: true); self.failedPlayedID = nil; completionCommitted = true }
            catch { reportError = "The server could not mark this title watched. Check your connection and retry." }
        }
        if let (decision, ticks, paused) = failedStart {
            do { try await client.reportStart(account: account, decision: decision, positionTicks: ticks, paused: paused); failedStart = nil }
            catch { reportError = "The server could not record playback. Check your connection and retry."; return }
        }
        if failedStop == nil, failedProgress != nil, let finalReport { failedStop = finalReport }
        if let (decision, ticks, failed) = failedStop {
            do { try await client.reportStopped(account: account, decision: decision, positionTicks: ticks, failed: failed); failedStop = nil; failedProgress = nil }
            catch { reportError = "The server could not save the final position. Check your connection and retry." }
        }
        let pending = failedEncodings; failedEncodings = []
        for decision in pending {
            do { try await client.stopEncoding(account: account, decision: decision) }
            catch { failedEncodings.append(decision) }
        }
        clearReportErrorIfRecovered()
        if !failedEncodings.isEmpty { reportError = "The server could not stop an encoding. Check your connection and retry." }
    }
    private func clearReportErrorIfRecovered() {
        if failedStart == nil && failedProgress == nil && failedStop == nil && failedPlayedID == nil && failedEncodings.isEmpty { reportError = nil }
    }
    private func finishViewing() async {
        guard !stopped else { return }; positionTicks = completedTicks ?? currentTicks; stopped = true
        extrasTask?.cancel(); timeoutTask?.cancel()
        let last = decision
        await releaseStream()
        if started, let last { finalReport = (last, positionTicks, error != nil); enqueueReport(decision: last, kind: "stop", ticks: positionTicks, paused: true) }
        await reportTail?.value; status = "Stopped"; waitingForVideo = false
    }
    func changeTracks(audio: Int?, subtitle: Int?, forceVideoTranscode: Bool = false) async {
        guard !changingTracks, !closed else { return }; changingTracks = true
        let position = currentTicks
        enqueueProgress(); await reportTail?.value; await releaseStream()
        guard !closed else { changingTracks = false; return }
        pendingSeekTicks = position
        do { try await prepare(startTicks: position, audio: audio, subtitle: subtitle, forceHLS: true, forceVideoTranscode: forceVideoTranscode) }
        catch { self.error = "Could not apply the selected tracks. Go back and try again." }
        changingTracks = false
    }
    private func failed() async {
        guard !changingTracks, !closed else { return }
        let failure = player.currentItem?.error as NSError?
        Logger(subsystem: "app.pictureshow", category: "Playback").error("Native playback failure domain=\(failure?.domain ?? "unknown", privacy: .public) code=\(failure?.code ?? 0)")
        if let underlying = failure?.userInfo[NSUnderlyingErrorKey] as? NSError { Logger(subsystem: "app.pictureshow", category: "Playback").error("Underlying domain=\(underlying.domain, privacy: .public) code=\(underlying.code)") }
        guard !fallbackUsed else { error = "Playback failed. Go back to details and try again."; await stop(); return }
        fallbackUsed = true
        await changeTracks(audio: decision?.audioIndex, subtitle: decision?.subtitleIndex, forceVideoTranscode: true)
    }
    private func recordNativeSelection(_ avItem: AVPlayerItem?) async {
        guard let avItem, player.currentItem === avItem, timelineReady, !closed, var value = decision, value.method != .transcode else { return }
        for type in ["Audio", "Subtitle"] {
            if type == "Audio", player.isMuted, value.audioIndex == -1 { continue }
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
            if type == "Audio" { value.audioIndex = index } else { value.subtitleIndex = index }
        }
        guard player.currentItem === avItem, !closed else { return }; decision = value
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
