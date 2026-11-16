// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit
import PictureshowCore

enum DetailCommand: Equatable {
    case play(Bool), watched(Bool), favorite(Bool)
    // A cold page resolves toggles only after its authoritative read.
    case toggleWatched, toggleFavorite
    var focusKey: String {
        switch self { case .play(let resume): return resume ? "play" : "startOver"; case .watched, .toggleWatched: return "watched"; case .favorite, .toggleFavorite: return "favorite" }
    }
    var feedbackTarget: String {
        switch focusKey { case "play": return "primary"; case "startOver": return "from-start"; case "watched": return "played"; default: return "favorite" }
    }
}

@MainActor final class DetailState: ObservableObject {
    @Published var item: MediaItem?
    @Published var header: MediaItem?
    @Published var seasons: [MediaItem] = []
    @Published var episodes: [MediaItem] = []
    @Published var selectedSeason: String?
    @Published private(set) var loadingSelectedSeason = false
    @Published private(set) var retainedSeasonID: String?
    private var pendingFeedback = DetailPendingFeedback()
    @Published var target: MediaItem?
    @Published var reading = false
    @Published var readPresentationID = UUID()
    @Published var fresh = false
    @Published var readError: String?
    @Published var writeError: String?
    @Published var writing = false
    @Published var notice: String?
    @Published var focusRequest: String?
    @Published var pending: [DetailCommand] = []
    @Published var readFailure: DetailFailurePresentation?
    @Published var writeFailure: DetailFailurePresentation?
    @Published var recoveryVisible = false
    @Published var retrying = false
    @Published var cue: AccessibilityCue?
    @Published var activeCommand: DetailCommand?
    @Published private(set) var requestCueKey: String?
    private var writeAttempt: Int64 = 0
    private var retryingWrite = false
    private var failedWrite: DetailCommand?
    private var epoch = UUID()
    private var readTask: Task<Void, Never>?
    private var drainTask: Task<Void, Never>?
    private var queuedAt: Date?
    private var departed = false
    private var deadline: Date?
    private var publishedRefreshID: UUID?
    private var requestedRefreshID: UUID?
    private var initialReadStarted = false
    private var readLifecycle = DetailReadLifecycle()
    func appear() { departed = false }
    func startInitialRead(id: String, model: AppModel) {
        guard !initialReadStarted else { return }
        initialReadStarted = true
        refresh(id: id, model: model)
    }
    func resume(id: String, model: AppModel) {
        appear()
        let refreshOnResume = readLifecycle.resumed()
        detailDiagnostic("event=resume refresh=\(refreshOnResume)")
        if refreshOnResume { refresh(id: id, model: model, restart: true) }
    }
    func leave() {
        detailDiagnostic("event=readLeave generation=\(epoch) reading=\(reading) uptime=\(ProcessInfo.processInfo.systemUptime)")
        departed = true; epoch = UUID(); readTask?.cancel(); readTask = nil
        reading = false; retrying = false; retryingWrite = false; retainedSeasonID = nil; loadingSelectedSeason = false
        pending = []; queuedAt = nil; cue = nil; requestCueKey = nil; pendingFeedback = DetailPendingFeedback()
    }
    var empty: Bool {
        DetailAvailability.showEmptyState(itemType: item?.type, loading: reading, readSucceeded: fresh, readFailed: readError != nil, episodeCount: episodes.count, seasonCount: seasons.count)
    }
    var recoveryTarget: String { target != nil ? "play" : empty ? "favorite" : "watched" }
    func beginRead(deliberate: Bool) {
        readPresentationID = UUID()
        reading = true; fresh = false; readError = nil
        if deliberate { retrying = true; readFailure = nil; cue = AccessibilityCue(message: "Loading item. Please wait.") }
        else if header == nil { focusRequest = "play" }
        // Automatic transport generations retain the same error episode and Retry slot.
    }
    func publishReadFailure(_ message: String) {
        reading = false; retrying = false; fresh = false; recoveryVisible = true; loadingSelectedSeason = false
        let key = DetailFeedback.failureKey(readRecovery: true, retrying: false, writeAttempt: nil)
        let previous = readFailure
        readFailure = DetailFeedback.presentFailure(previous: previous, key: key, message: message)
        readError = readFailure?.message ?? message
        if previous == nil {
            let expired = !pending.isEmpty ? " The previous request expired and will not run." : ""
            cue = AccessibilityCue(message: "Loading failed. \(readFailure?.message ?? message) Retry is available.\(expired)", urgent: false)
        }
        focusRequest = "detailRetry"; pending = []; queuedAt = nil; requestCueKey = nil
    }
    func publishRead(_ result: DetailPublication, publication: UUID) {
        let initial = header == nil
        item = result.item; if header == nil { header = result.item }
        let seasonRemoved = retainedSeasonID.map { retained in !result.seasons.contains(where: { $0.id == retained }) } ?? false
        seasons = result.seasons; episodes = result.episodes; selectedSeason = result.season; target = result.target
        if seasonRemoved { retainedSeasonID = nil; focusRequest = recoveryTarget }
        reading = false; fresh = true; readError = nil; deadline = nil; publishedRefreshID = publication; cue = nil; loadingSelectedSeason = false
        if recoveryVisible { focusRequest = recoveryTarget }
        else if initial && result.item.type != "Person" { focusRequest = recoveryTarget }
    }
    func finishRecoveryHandoff() { recoveryVisible = false; retrying = false; readFailure = nil }
    func beginWriteAttempt() { writeAttempt += 1; writeError = nil; writeFailure = nil }
    func publishWriteFailure(_ message: String) {
        let key = DetailFeedback.failureKey(readRecovery: false, retrying: false, writeAttempt: writeAttempt)
        let previous = writeFailure
        writeFailure = DetailFeedback.presentFailure(previous: previous, key: key, message: message)
        writeError = writeFailure?.message ?? message
        if previous == nil { cue = AccessibilityCue(message: "Change failed. \(writeFailure?.message ?? message) Retry change is available.") }
    }
    func requested(_ command: DetailCommand) -> Bool { (pending + [activeCommand].compactMap { $0 }).contains { $0.focusKey == command.focusKey } }
    func requestedLabel(_ command: DetailCommand) -> String? {
        requested(command) ? DetailFeedback.requestedActionLabel(target: command.feedbackTarget, newlyAccepted: false) : nil
    }
    func requestFocusChanged(_ key: String?) {
        if !DetailFeedback.requestCueRemainsActive(active: requestCueKey != nil, readerLeftAction: key != requestCueKey) { requestCueKey = nil }
    }
    func reconcileRequestCue() {
        let requested = (pending + [activeCommand].compactMap { $0 }).contains { $0.focusKey == requestCueKey }
        if !DetailFeedback.requestCueRemainsActive(active: requestCueKey != nil, readerLeftAction: false, requested: requested) { requestCueKey = nil }
    }
    func acceptRequestCue(_ command: DetailCommand) {
        requestCueKey = command.focusKey
        cue = AccessibilityCue(message: DetailFeedback.requestedActionLabel(target: command.feedbackTarget, newlyAccepted: true), urgent: false)
    }
    @discardableResult func enqueue(_ command: DetailCommand, now: Date = Date()) -> Bool {
        guard !departed, !requested(command) else { return false }
        // Playback is a single intent even if the viewer tries the peer start button.
        if case .play = command, (pending + [activeCommand].compactMap { $0 }).contains(where: { if case .play = $0 { return true }; return false }) { return false }
        guard pending.count < 32 else { notice = "Too many waiting actions. Try again after the item finishes loading."; return false }
        if queuedAt == nil { queuedAt = now }; pending.append(command)
        return true
    }
    @discardableResult func expirePending(now: Date = Date()) -> Bool {
        guard let queuedAt, now.timeIntervalSince(queuedAt) > 30 else { return false }
        pending = []; self.queuedAt = nil; requestCueKey = nil; notice = "Waiting action expired. Choose it again."
        return true
    }
    func refresh(id: String, model: AppModel, deliberate: Bool = false, restart: Bool = false) {
        guard let account = model.account else { return }
        if reading && (deliberate || !restart) { return }
        let read = model.detailReadStarted(itemID: id)
        refresh(id: id, account: account, client: model.client, publication: model.refreshID, deliberate: deliberate, restart: restart,
                isCurrent: { model.detailReadIsCurrent(read) },
                onFailure: { model.handle($0, account: account) },
                onAppliedRead: { item in
                    model.detailReadApplied(item, read: read)
                    for episode in self.episodes { model.settlePlayedProjection(episode, reporting: model.playbackSaving) }
                    self.publishedRefreshID = model.refreshID
                }, onSuccess: { self.drain(model: model) })
    }
    func refreshIfNeeded(id: String, model: AppModel) {
        guard !fresh || publishedRefreshID != model.refreshID else { return }
        guard !reading || requestedRefreshID != model.refreshID else { return }
        deadline = nil
        refresh(id: id, model: model, restart: true)
    }
    /// The same authoritative read path is exercised by hosted HTTP fixtures without
    /// constructing account storage, a player or a second app model.
    func refresh(id: String, account: Account, client: JellyfinClient, publication: UUID, deliberate: Bool = false, restart: Bool = false,
                 isCurrent: @escaping @MainActor () -> Bool = { true },
                 onFailure: @escaping @MainActor (Error) -> Void = { _ in },
                 onAppliedRead: @escaping @MainActor (MediaItem) -> Void = { _ in },
                 onSuccess: @escaping @MainActor () -> Void = {}) {
        if reading && (deliberate || !restart) { return }
        if deliberate { deadline = Date().addingTimeInterval(30) }
        else if deadline == nil { deadline = Date().addingTimeInterval(30) }
        guard let deadline else { return }
        readTask?.cancel(); epoch = UUID(); let version = epoch
        requestedRefreshID = publication
        beginRead(deliberate: deliberate)
        detailDiagnostic("event=readBegin generation=\(version) deliberate=\(deliberate) remaining=\(max(0, deadline.timeIntervalSinceNow)) uptime=\(ProcessInfo.processInfo.systemUptime)")
        readTask = Task {
            defer { if epoch == version { reading = false } }
            do {
                let result = try await withThrowingTaskGroup(of: DetailPublication.self) { group in
                    group.addTask {
                        let item = try await client.item(account: account, id: id)
                        var seasons: [MediaItem] = []; var episodes: [MediaItem] = []; var target: MediaItem?
                        var season = await self.selectedSeason
                        if item.type == "Series" {
                            let next = try await client.nextUp(account: account, seriesID: item.id)
                            seasons = try await client.seasons(account: account, seriesID: item.id)
                            if !seasons.contains(where: { $0.id == season }) { season = next.first?.seasonId ?? seasons.first?.id }
                            if let season { episodes = try await client.episodes(account: account, seriesID: item.id, seasonID: season) }
                            target = next.first(where: { $0.isPlayable }) ?? episodes.first(where: { !$0.isWatched && $0.isPlayable }) ?? episodes.first(where: \.isPlayable)
                            // A series can begin in a different season from the visible shelf.
                            if target == nil { for candidate in seasons where candidate.id != season { let other = try await client.episodes(account: account, seriesID: item.id, seasonID: candidate.id); if let found = other.first(where: { !$0.isWatched && $0.isPlayable }) ?? other.first(where: \.isPlayable) { target = found; break } } }
                        } else if item.type == "Season", let series = item.seriesId {
                            episodes = try await client.episodes(account: account, seriesID: series, seasonID: item.id)
                            target = episodes.first(where: { !$0.isWatched && $0.isPlayable }) ?? episodes.first(where: \.isPlayable)
                        } else if item.isPlayable { target = item }
                        return DetailPublication(item: item, seasons: seasons, episodes: episodes, season: season, target: target)
                    }
                    group.addTask {
                        let wait = max(0, deadline.timeIntervalSinceNow)
                        try await Task.sleep(for: .seconds(wait))
                        await detailDiagnostic("event=readTimerFired generation=\(version) uptime=\(ProcessInfo.processInfo.systemUptime)")
                        throw DetailTimeout()
                    }
                    defer {
                        detailDiagnostic("event=readGroupCancel generation=\(version) uptime=\(ProcessInfo.processInfo.systemUptime)")
                        group.cancelAll()
                    }
                    guard let first = try await group.next() else { throw CancellationError() }; return first
                }
                detailDiagnostic("event=readGroupReturned generation=\(version) uptime=\(ProcessInfo.processInfo.systemUptime)")
                guard version == epoch, !departed, isCurrent() else { return }
                publishRead(result, publication: publication)
                onAppliedRead(result.item)
                onSuccess()
            } catch is CancellationError {
                detailDiagnostic("event=readCancelled generation=\(version) current=\(version == epoch) departed=\(departed) uptime=\(ProcessInfo.processInfo.systemUptime)")
            }
            catch {
                detailDiagnostic("event=readCaught generation=\(version) current=\(version == epoch) departed=\(departed) deadline=\(error is DetailTimeout) uptime=\(ProcessInfo.processInfo.systemUptime)")
                guard version == epoch, !departed, isCurrent() else { return }
                onFailure(error); publishReadFailure(error.localizedDescription)
            }
        }
    }
    func chooseSeason(_ id: String, model: AppModel) {
        guard seasons.contains(where: { $0.id == id }), !model.playbackSaving, !writing else { return }
        guard id != selectedSeason else { return }
        retainedSeasonID = id; selectedSeason = id
        // The Seasons subtree stays mounted; the old season's episode tiles do
        // not belong under this new selection while its dependent read is held.
        episodes = []; loadingSelectedSeason = true
        if let item { deadline = nil; refresh(id: item.id, model: model, restart: true) }
    }
    func seasonFocusChanged(_ key: String?) {
        guard let retainedSeasonID else { return }
        if key != "season-\(retainedSeasonID)" && key != "seasonStart" && key != "seasonEnd" { self.retainedSeasonID = nil }
    }
    func updatePendingFeedback(waiting: Bool, graceExpired: Bool, focused: String?, resumed: Bool, cancelled: Bool) {
        guard let target = pendingFeedback.update(waiting: waiting, graceExpired: graceExpired, focused: focused, resumed: resumed, cancelled: cancelled) else { return }
        let action: String
        switch target { case "play": action = "Play"; case "startOver": action = "Play from start"; case "watched": action = "Watched"; default: action = "Favorite" }
        cue = AccessibilityCue(message: "\(action) when ready. Please wait.", urgent: false)
        pendingFeedback.submitted()
    }
    @discardableResult func offer(_ command: DetailCommand, model: AppModel) -> Bool {
        guard readError == nil, enqueue(command) else { return false }
        if case .watched = command, let item { model.acceptedPlayedChoice(itemID: item.id) }
        if case .toggleWatched = command, let item { model.acceptedPlayedChoice(itemID: item.id) }
        drain(model: model)
        return true
    }
    func drain(model: AppModel) {
        let mayRetryMutation = retryingWrite && item != nil
        guard drainTask == nil, (mayRetryMutation || (fresh && publishedRefreshID == model.refreshID && !reading)), !model.playbackSaving, !departed, let account = model.account else { return }
        guard !pending.isEmpty else { return }
        if expirePending() { return }
        let version = epoch
        drainTask = Task {
            defer { drainTask = nil; activeCommand = nil; reconcileRequestCue(); writing = false; if !pending.isEmpty { drain(model: model) } }
            while !pending.isEmpty, version == epoch, !departed, (fresh || retryingWrite), !model.playbackSaving {
                if expirePending() { return }
                let command = pending.removeFirst(); activeCommand = command; writing = true; notice = nil
                beginWriteAttempt()
                do {
                    switch command {
                    case .play(let resume):
                        guard let target else { notice = "No episodes are available."; continue }
                        await model.play(target, resume: resume, audio: nil, subtitle: nil)
                    case .watched, .toggleWatched:
                        guard var item else { continue }
                        let desired: Bool
                        if case .watched(let value) = command { desired = value } else { desired = !item.isWatched }
                        if item.userData.played != desired { item.userData = try await model.client.played(account: account, itemID: item.id, value: desired) }
                        else { notice = "Nothing changed. This item is already \(desired ? "watched" : "unwatched")." }
                        guard version == epoch, !departed else { return }; self.item = item
                    case .favorite, .toggleFavorite:
                        guard var item else { continue }
                        let desired: Bool
                        if case .favorite(let value) = command { desired = value } else { desired = !item.userData.isFavorite }
                        if item.userData.isFavorite != desired { item.userData = try await model.client.favorite(account: account, itemID: item.id, value: desired) }
                        else { notice = "Nothing changed. This item is already \(desired ? "a favorite" : "not a favorite")." }
                        guard version == epoch, !departed else { return }; self.item = item
                    }
                    guard version == epoch, !departed else { return }
                    writeError = nil; writeFailure = nil; failedWrite = nil; retryingWrite = false
                    if case .play = command { } else { model.catalogChanged(); await model.loadHomeIfNeeded(); deadline = nil; refresh(id: item?.id ?? "", model: model, restart: true); return }
                } catch {
                    guard version == epoch, !departed else { return }; model.handle(error, account: account); publishWriteFailure(error.localizedDescription); failedWrite = command; retryingWrite = false; pending = []; return
                }
            }
            queuedAt = nil
        }
    }
    func retryWrite(model: AppModel) {
        guard let failedWrite, !writing, !retryingWrite, enqueue(failedWrite) else { return }
        // Mutation recovery uses the last authoritative item even if a later read
        // failed. Its desired value belongs to the failed write, not the new label.
        retryingWrite = true; drain(model: model)
    }
}
struct DetailPublication: Sendable { let item: MediaItem; let seasons: [MediaItem]; let episodes: [MediaItem]; let season: String?; let target: MediaItem? }
private struct DetailTimeout: LocalizedError { var errorDescription: String? { "The item took too long to load. Choose Retry to try again." } }

struct DetailView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @ScaledMetric(relativeTo: .body) private var personScale: CGFloat = 1
    let id: String
    var open: (MediaItem) -> Void
    @StateObject private var state = DetailState()
    @FocusState private var focused: String?
    @State private var opener = "play"
    @State private var remoteItem: MediaItem?
    @State private var remoteOperation = "play-now"
    @State private var remoteFromStart = false
    @State private var localRemotePlayback: (MediaItem, Bool)?
    @AccessibilityFocusState private var accessibilityFocused: String?
    @State private var contentRevealed = false
    @State private var returnedFromPlayback = false
    @State private var readiness = DetailReadyFeedback()
    @State private var pendingPresentation = false
    @State private var requestCancelledReady = false
    @State private var visible = false
    @State private var resumed = false
    @State private var handoffFinished = false
    @State private var handoffInFlight = false
    @State private var stableActions = false
    @State private var didPublishAction = false
    #if os(iOS)
    @State private var scrollOffset: CGFloat = 0
    @State private var displayTitleBottom: CGFloat = .infinity
    @State private var liveTitleBottom: CGFloat = .infinity
    private var compactTitleVisible: Bool {
        if #available(iOS 18.0, *) { return scrollOffset > displayTitleBottom + Theme.gutter }
        return liveTitleBottom < 0
    }
    #endif
    #if os(tvOS)
    @ScaledMetric(relativeTo: .body) private var recoveryLaneHeight: CGFloat = 160
    #endif
    var body: some View {
        detailBody
            .sheet(item: $remoteItem, onDismiss: restoreRemoteOpener) { item in
                if let account = model.account {
                    RemotePlayView(item: item, account: account, client: model.client, operation: remoteOperation, fromStart: remoteFromStart, onThisDevice: {
                        guard model.account?.localKey == account.localKey else { return }
                        localRemotePlayback = (item, !remoteFromStart); remoteItem = nil
                    }, attachedTarget: model.attachedRemote(for: account), onAttached: { target in
                        model.attachRemote(target, for: account)
                    }).id(account.localKey)
                }
            }
            .onChange(of: model.account?.localKey) { _, _ in remoteItem = nil; localRemotePlayback = nil }
    }
    private func restoreRemoteOpener() {
        focused = "playOnDevice"; accessibilityFocused = "playOnDevice"
        if let (item, resume) = localRemotePlayback {
            localRemotePlayback = nil
            Task { await model.play(item, resume: resume, audio: nil, subtitle: nil) }
        }
    }
    private func showRemote(_ item: MediaItem, operation: String = "play-now", fromStart: Bool = false) {
        opener = "playOnDevice"; remoteOperation = operation; remoteFromStart = fromStart; remoteItem = item
    }
    private var detailBody: some View {
        ScrollViewReader { proxy in
            detailFocusContent(proxy: proxy)
            #if os(tvOS)
            .onPlayPauseCommand {
                guard (coldPending || state.item.map { $0.isPlayable || ["Series", "Season"].contains($0.type) } == true), state.readError == nil else { return }
                opener = "play"
                offer(.play(true), key: "play")
            }
            #else
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Theme.background, for: .navigationBar).toolbarBackground(.visible, for: .navigationBar)
            #endif
        }
    }
    // Opaque view boundaries keep each result-builder/modifier expression small
    // without introducing new state, identities or type-erased containers.
    private var detailScrollContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                if let item = state.item, item.type == "Person" {
                    Text(item.name).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                    if let overview = item.overview { Text(overview).selectableReading() }
                    PersonFilmography(id: item.id, open: open)
                } else if let header = state.header, let item = state.item {
                    hero(header, current: model.presented(item))
                    if item.type == "Series" {
                        Text("Seasons").font(.title2.bold()).accessibilityAddTraits(.isHeader).accessibilityHidden(metadataHidden)
                        seasonShelf
                    }
                    if ["Series", "Season"].contains(item.type) { episodeSection }
                    if !item.people.isEmpty {
                        Text("Cast and crew").font(.title2.bold()).accessibilityAddTraits(.isHeader).accessibilityHidden(metadataHidden)
                        peopleShelf(item.people)
                    }
                    if header != item, let overview = item.overview, overview != header.overview { Text("More details").font(.title2).accessibilityAddTraits(.isHeader).accessibilityHidden(metadataHidden); Text(overview).accessibilityHidden(metadataHidden) }
                } else if coldPending {
                    // Publish the destination's real guarded slots before metadata
                    // arrives. Their intents use this state's existing bounded queue.
                    actionRow(MediaItem(id: id, name: "", type: "Movie"), horizontal: coldActionsHorizontal)
                    ProgressView("Loading item…").accessibilityHidden(true)
                } else if state.reading { ProgressView("Loading item…") }
                if let warning = model.playbackSaveError { Text(warning).foregroundStyle(.orange); Button { Task { await model.retryPlaybackSave() } } label: { Text(model.playbackSaving ? "Saving progress…" : "Retry saving progress") }.disabled(model.playbackSaving).catalogActionStyle().accessibilityIdentifier("retryProgress") }
                if let notice = state.notice { Text(notice).foregroundStyle(Theme.muted) }
            }
            .coordinateSpace(name: "detailContent")
            .padding(Theme.gutter)
            #if os(tvOS)
            // Recovery owns this trailing content margin, never a shorter
            // UIScrollView viewport or an opaque empty healthy-state inset.
            .padding(.bottom, recoveryLaneHeight)
            #endif
                .accessibilityHidden(!retainsSeasonSelection && !coldPending && DetailFeedback.arrivalAwaitsAction(loading: state.reading, contentRevealed: contentRevealed, returningFromPlayback: returnedFromPlayback))
        }
    }
    private func detailSurface(proxy: ScrollViewProxy) -> some View {
        detailScrollContent
        #if os(iOS)
        .coordinateSpace(name: "detailScroll")
        .modifier(NativeScrollOffset(offset: $scrollOffset))
        .onPreferenceChange(DetailTitleContentBottom.self) { displayTitleBottom = $0 }
        .onPreferenceChange(DetailTitleViewportBottom.self) { liveTitleBottom = $0 }
        #endif
        .overlay(alignment: .top) {
            if state.header != nil && !state.loadingSelectedSeason && (model.playbackSaving || state.reading || state.writing) {
                Text(model.playbackSaving ? (state.fresh ? "Item loaded. Saving progress…" : "Saving progress…") : state.writing ? "Saving change…" : state.item?.type == "Series" || state.item?.type == "Season" ? "Loading episodes…" : "Loading item…")
                    .padding(12).background(Theme.surface.opacity(0.95), in: Capsule()).padding(.top, 8).accessibilityIdentifier("detailStatus").allowsHitTesting(false)
            }
        }
        #if os(iOS)
        .safeAreaInset(edge: .bottom, spacing: 0) { recoveryPanel }
        #else
        // The viewport stays full height. A permanent trailing content
        // margin supplies scroll room; only a real recovery paints a lane.
        .overlay(alignment: .bottom) {
            if recoveryPresented {
                recoveryPanel
                    .frame(maxWidth: .infinity, minHeight: recoveryLaneHeight, maxHeight: recoveryLaneHeight, alignment: .center)
                    .background(Theme.background)
            }
        }
        .onChange(of: recoveryPresented) { _, presented in
            if presented, let selection = UIAccessibility.isVoiceOverRunning ? accessibilityFocused : focused, selection != "detailRetry" {
                // Keep the same action/reader location, but reveal it above
                // the overlay without changing any published row geometry.
                proxy.scrollTo(selection, anchor: .top)
            }
        }
        #endif
        .background {
            if usesBackdropHero, let header = state.header, state.item?.type != "Person" {
                DetailHeroBackdrop(item: header).ignoresSafeArea()
            } else {
                Theme.background.ignoresSafeArea()
            }
        }
        #if os(tvOS)
        .toolbar(.hidden, for: .navigationBar)
        #else
        .navigationTitle(compactTitleVisible ? state.header?.name ?? "Details" : "")
        #endif
    }
    private func detailLifecycleContent(proxy: ScrollViewProxy) -> some View {
        detailSurface(proxy: proxy)
        .announceCue(state.cue)
        .announceStatus(state.notice)
        .accessibilityHidden(model.player != nil)
        .task(id: id) { state.startInitialRead(id: id, model: model) }
        .onAppear { visible = true; state.appear(); resumeIfActive(); focused = opener; returnedFromPlayback = contentRevealed }
        .onDisappear { visible = false; resumed = false; focused = nil; accessibilityFocused = nil; pendingPresentation = false; state.leave() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { resumeIfActive() } else { resumed = false; pendingPresentation = false; accessibilityFocused = nil; state.cue = nil }
        }
        .task(id: pendingSnapshot) { await publishHeldLabels() }
        .onChange(of: readySnapshot, initial: true) { _, snapshot in
            state.updatePendingFeedback(waiting: guarded, graceExpired: pendingPresentation, focused: snapshot.focused, resumed: snapshot.active, cancelled: requestCancelledReady || state.readError != nil || state.writeError != nil || !state.pending.isEmpty || state.activeCommand != nil)
            if readiness.update(held: snapshot.held, focused: snapshot.focused, resumed: snapshot.active, usable: snapshot.usable, presented: pendingPresentation, cancelled: requestCancelledReady || state.readError != nil || state.writeError != nil || !state.pending.isEmpty || state.activeCommand != nil) != nil {
                let cue = AccessibilityCue(message: DetailFeedback.readyCue(actionLabel: readyActionLabel(snapshot.focused)), urgent: false)
                detailDiagnostic("event=readyGenerated cue=\(cue.id) selected=\(snapshot.focused ?? "none") active=\(snapshot.active)")
                state.cue = cue
            }
        }
        .onChange(of: model.refreshID) { _, _ in state.refreshIfNeeded(id: id, model: model) }
        .onChange(of: model.playbackSaving) { _, saving in stableActions = true; if !saving { state.refreshIfNeeded(id: id, model: model) } }
        .onChange(of: state.reading) { _, value in
            if value && state.header == nil { stableActions = true }
            if !value { contentRevealed = state.item != nil; state.drain(model: model) }
        }
    }
    private func detailFocusContent(proxy: ScrollViewProxy) -> some View {
        detailLifecycleContent(proxy: proxy)
        .onChange(of: state.focusRequest) { _, request in
            guard let request else { return }
            let ownsSelection = focused == "detailRetry" || accessibilityFocused == "detailRetry" || (focused == nil && accessibilityFocused == nil) || !didPublishAction
            handoffInFlight = true
            if request == "detailRetry" || ownsSelection {
                // VoiceOver's current heading/action remains the reader's location on failure.
                // The owned failure cue names Retry without moving or replaying focus speech.
                if request != "detailRetry" || !UIAccessibility.isVoiceOverRunning {
                    proxy.scrollTo(request, anchor: .center); focused = request; accessibilityFocused = request
                }
                if request != "detailRetry" { didPublishAction = true }
            }
            state.focusRequest = nil
            // Let the target be committed before removing the recovery surface. A
            // lifecycle interruption still reveals the usable page on the next turn.
            Task { @MainActor in
                // Keep both targets attached across the focus update, including
                // a render pass on slower devices. Do not let an older handoff
                // dismiss a newer failed Retry.
                try? await Task.sleep(for: .milliseconds(50))
                if state.fresh, !state.reading {
                    state.finishRecoveryHandoff()
                    handoffFinished = true
                }
                handoffInFlight = false
            }
        }
        .onChange(of: state.pending) { _, pending in if !pending.isEmpty { requestCancelledReady = true }; state.reconcileRequestCue() }
        .onChange(of: guarded) { _, held in if !held { requestCancelledReady = false } }
        .onChange(of: state.activeCommand) { _, _ in state.reconcileRequestCue() }
        .onChange(of: focused) { _, focus in state.requestFocusChanged(focus); if !UIAccessibility.isVoiceOverRunning { state.seasonFocusChanged(focus) }; if let focus { opener = focus; if state.item != nil, focus != "detailRetry", !handoffInFlight { handoffFinished = true }; proxy.scrollTo(focus, anchor: retainedSelectionAnchor) } }
        .onChange(of: accessibilityFocused) { _, focus in
            // VoiceOver can leave the action for page text without changing
            // keyboard/remote focus. Nil selection ends acceptance as well.
            if UIAccessibility.isVoiceOverRunning { state.requestFocusChanged(focus); state.seasonFocusChanged(focus) }
            if let focus { opener = focus; if state.item != nil, focus != "detailRetry", !handoffInFlight { handoffFinished = true }; proxy.scrollTo(focus, anchor: retainedSelectionAnchor) } }
    }
    private var pendingSnapshot: PendingPresentation {
        PendingPresentation(generation: state.readPresentationID, waiting: guarded, active: visible && resumed && scenePhase == .active && model.player == nil)
    }
    @MainActor private func publishHeldLabels() async {
        pendingPresentation = false
        guard guarded, visible, resumed, scenePhase == .active, model.player == nil else { return }
        do { try await Task.sleep(for: .milliseconds(1_500)) } catch { return }
        guard !Task.isCancelled else { return }
        pendingPresentation = true
        detailDiagnostic("event=heldLabelsPublished")
    }
    private var recoveryPresented: Bool { state.recoveryVisible || state.writeFailure != nil }
    private var retainedSelectionAnchor: UnitPoint {
        #if os(tvOS)
        recoveryPresented ? .top : .center
        #else
        .center
        #endif
    }
    @ViewBuilder private var episodeSection: some View {
        #if os(tvOS)
        if state.loadingSelectedSeason {
            VStack(alignment: .leading, spacing: 16) {
                Text("Episodes").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                ProgressView("Loading episodes…").accessibilityIdentifier("episodesLoading")
            }
        } else if state.empty {
            VStack(alignment: .leading, spacing: 16) {
                Text("Episodes").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Text("No episodes are available.").foregroundStyle(Theme.muted)
            }
        }
        else { CatalogShelf(title: "Episodes", items: state.episodes.map { model.presented($0) }, wide: true, horizontalInset: 0, pageSectionHeading: true, open: open) }
        #else
        VStack(alignment: .leading, spacing: 16) {
            Text("Episodes").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if state.loadingSelectedSeason { ProgressView("Loading episodes…").accessibilityIdentifier("episodesLoading") }
            else if state.empty { Text("No episodes are available.").foregroundStyle(Theme.muted) }
            else {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 280), spacing: 16, alignment: .top)], alignment: .leading, spacing: 24) {
                    ForEach(state.episodes) { raw in
                        let episode = model.presented(raw)
                        VStack(alignment: .leading, spacing: 9) {
                            Button { open(episode) } label: {
                                #if os(tvOS)
                                CatalogCard(item: episode, wide: true, fillsAvailableWidth: true, artworkOnly: true)
                                #else
                                CatalogCard(item: episode, wide: true, fillsAvailableWidth: true)
                                #endif
                            }
                            .buttonStyle(PosterButtonStyle()).accessibilityLabel(episode.accessibleTitle).accessibilityIdentifier("item-\(episode.id)")
                            #if os(tvOS)
                            CatalogCard(item: episode, wide: true, fillsAvailableWidth: true, metadataOnly: true).accessibilityHidden(true)
                            #endif
                        }
                    }
                }
            }
        }
        #endif
    }
    private var retainsSeasonSelection: Bool {
        let key = UIAccessibility.isVoiceOverRunning ? accessibilityFocused : focused
        let selectedID = state.retainedSeasonID ?? key.flatMap { $0.hasPrefix("season-") ? String($0.dropFirst(7)) : nil } ?? ""
        return DetailAvailability.retainsSeasonFocus(selectedSection: key?.hasPrefix("season-") == true || key == "seasonStart" || key == "seasonEnd" ? "seasons" : "", restored: contentRevealed, semanticsRevealed: !metadataHidden, selectedId: selectedID, seasonIds: state.seasons.map(\.id))
    }
    private var seasonShelf: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 18) {
                    ForEach(state.seasons) { season in
                        if seasonBoundaryVisible(season.id, start: true) { seasonBoundary(start: true, season: season) }
                        Button {
                            guard state.readError == nil else { return }
                            opener = "season-\(season.id)"; state.chooseSeason(season.id, model: model)
                        } label: {
                            #if os(tvOS)
                            TelevisionChoiceLabel(title: season.name, selected: state.selectedSeason == season.id)
                                .padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 48).contentShape(Rectangle())
                            #else
                            Text(season.name).padding(.horizontal, 12).padding(.vertical, 8).frame(minHeight: 48).contentShape(Rectangle())
                            #endif
                        }
                        .modifier(SeasonChoiceStyle(selected: state.selectedSeason == season.id))
                        .detailActionState(value: state.selectedSeason == season.id ? "Selected" : "", unavailable: state.readError != nil, held: guarded && !retainsSeasonSelection, label: season.name)
                        .accessibilityIdentifier("season-\(season.id)").focused($focused, equals: "season-\(season.id)")
                        .accessibilityFocused($accessibilityFocused, equals: "season-\(season.id)").id("season-\(season.id)")
                        .accessibilityAddTraits(state.selectedSeason == season.id ? [.isSelected] : [])
                        #if os(tvOS)
                        .onMoveCommand { direction in
                            if direction == .left && season.id == state.seasons.first?.id { focused = "season-\(season.id)" }
                            else if direction == .right && season.id == state.seasons.last?.id { focused = "season-\(season.id)" }
                        }
                        #endif
                        .accessibilityScrollAction { edge in
                            #if os(tvOS)
                            if edge == .leading && season.id == state.seasons.first?.id { accessibilityFocused = "seasonStart" }
                            else if edge == .trailing && season.id == state.seasons.last?.id { accessibilityFocused = "seasonEnd" }
                            #endif
                        }
                        if seasonBoundaryVisible(season.id, start: false) { seasonBoundary(start: false, season: season) }
                    }
                }.padding(.vertical, 12)
            }.televisionFocusSection()
                .onChange(of: accessibilityFocused) { _, key in
                    if let key, key.hasPrefix("season-") { proxy.scrollTo(key, anchor: .center) }
                }
        }
    }
    private func seasonBoundaryVisible(_ id: String, start: Bool) -> Bool {
        #if os(tvOS)
        return UIAccessibility.isVoiceOverRunning && id == (start ? state.seasons.first?.id : state.seasons.last?.id) && (accessibilityFocused == "season-\(id)" || accessibilityFocused == (start ? "seasonStart" : "seasonEnd"))
        #else
        return false
        #endif
    }
    private func seasonBoundary(start: Bool, season: MediaItem) -> some View {
        Text(start ? "Start of Seasons" : "End of Seasons").font(.caption).foregroundStyle(Theme.muted)
            .accessibilityElement(children: .ignore).accessibilityFocused($accessibilityFocused, equals: start ? "seasonStart" : "seasonEnd")
            .accessibilityIdentifier(start ? "seasonStart" : "seasonEnd")
            .accessibilityScrollAction { edge in
                if (start && edge == .trailing) || (!start && edge == .leading) { accessibilityFocused = "season-\(season.id)" }
            }
    }
    private func readyActionLabel(_ key: String?) -> String {
        switch key {
        case "play": return state.item.map(primaryLabel) ?? "Play"
        case "startOver": return "Play from start"
        case "watched": return state.item?.isWatched == true ? "Mark unwatched" : "Mark watched"
        default: return state.item?.userData.isFavorite == true ? "Remove favorite" : "Add favorite"
        }
    }
    private var usesBackdropHero: Bool {
        #if os(tvOS)
        true
        #else
        UIDevice.current.userInterfaceIdiom == .pad && horizontalSizeClass == .regular
        #endif
    }
    @ViewBuilder private func hero(_ header: MediaItem, current item: MediaItem) -> some View {
        if usesBackdropHero {
            VStack(alignment: .leading, spacing: 24) {
                heroIntroduction(header, current: item)
                    .frame(maxWidth: .infinity, minHeight: backdropIntroductionHeight, alignment: .bottomLeading)
                heroSummary(header, current: item).frame(maxWidth: 760, alignment: .leading)
            }
        } else {
            // Keep the phone and compact-window hierarchy: artwork, title,
            // metadata, primary playback, start-over and secondary actions.
            VStack(alignment: .leading, spacing: 20) {
                Artwork(item: header, wide: true).frame(maxWidth: .infinity).frame(height: heroHeight).clipShape(RoundedRectangle(cornerRadius: 18))
                heroIntroduction(header, current: item)
                heroSummary(header, current: item)
            }
        }
    }
    private var backdropIntroductionHeight: CGFloat {
        #if os(tvOS)
        620
        #else
        560
        #endif
    }
    private func heroIntroduction(_ header: MediaItem, current item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Text(header.name).modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("detailTitle").accessibilityFocused($accessibilityFocused, equals: "heading").accessibilityHidden(metadataHidden)
                #if os(tvOS)
                .id("heading")
                #else
                .background {
                    GeometryReader { geometry in
                        Color.clear
                            .preference(key: DetailTitleContentBottom.self, value: geometry.frame(in: .named("detailContent")).maxY)
                            .preference(key: DetailTitleViewportBottom.self, value: geometry.frame(in: .named("detailScroll")).maxY)
                    }
                }
                #endif
            Text(metadata(header)).foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden)
            // Published action slots stay in one order while final reports and fresh reads run.
            #if os(tvOS)
            actionRow(item, horizontal: true)
            #else
            actionRow(item, horizontal: false)
            #endif
        }
    }
    private func heroSummary(_ header: MediaItem, current item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            if let target = state.target, target.id != item.id { Text("Next: \(target.accessibleTitle)").foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden) }
            if let overview = header.overview, !overview.isEmpty { Text(overview).modifier(TokenFont(DesignTokens.bodySize)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true).accessibilityHidden(metadataHidden) }
            if !header.genres.isEmpty { Text(header.genres.joined(separator: " · ")).foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden) }
        }
    }
    private func peopleShelf(_ people: [PersonCredit]) -> some View {
        ScrollView(.horizontal) {
            HStack(alignment: .top, spacing: personShelfSpacing) {
                ForEach(people) { person in
                    VStack(spacing: 12) {
                        Button {
                            var target = MediaItem(id: person.id, name: person.name, type: "Person")
                            target.overview = nil
                            open(target)
                        } label: {
                            #if os(tvOS)
                            DetailPersonPortrait(name: person.name, diameter: 132 * personScale)
                                .frame(width: personTileWidth, height: 164 * personScale)
                            #else
                            VStack(spacing: 12) {
                                DetailPersonPortrait(name: person.name, diameter: 88 * personScale)
                                DetailPersonMetadata(person: person)
                            }.frame(width: personTileWidth)
                            #endif
                        }
                        .buttonStyle(PosterButtonStyle())
                        .accessibilityLabel([person.name, person.detailDisplayRole].compactMap { $0 }.joined(separator: ", "))
                        .accessibilityIdentifier("person-\(person.id)")
                        #if os(tvOS)
                        // Like catalog posters, metadata stays outside the
                        // focused native card's light platter.
                        DetailPersonMetadata(person: person).frame(width: personTileWidth).accessibilityHidden(true)
                        #endif
                    }.frame(width: personTileWidth)
                }
            }.padding(.vertical, 16)
        }
        .scrollIndicators(.hidden)
        .televisionFocusSection()
        .accessibilityIdentifier("detailPeopleShelf")
    }
    private var personTileWidth: CGFloat {
        #if os(tvOS)
        200 * personScale
        #else
        120 * personScale
        #endif
    }
    private var personShelfSpacing: CGFloat {
        #if os(tvOS)
        44
        #else
        20
        #endif
    }
    private func resumeIfActive() {
        guard visible, scenePhase == .active, !resumed else { return }
        resumed = true
        state.resume(id: id, model: model)
    }
    private var coldPending: Bool {
        DetailFeedback.needsPendingControls(loading: state.reading, firstArrival: !contentRevealed && state.header == nil, canQueue: true, returningFromPlayback: returnedFromPlayback)
    }
    private var coldActionsHorizontal: Bool {
        #if os(tvOS)
        true
        #else
        false
        #endif
    }
    private var metadataHidden: Bool {
        DetailFeedback.metadataAwaitsHandoff(hasActions: state.item != nil && state.item?.type != "Person", handoffFinished: handoffFinished, returningFromPlayback: returnedFromPlayback, failed: state.readError != nil || state.recoveryVisible)
    }
    private struct PendingPresentation: Equatable { let generation: UUID; let waiting: Bool; let active: Bool }
    private struct ReadySnapshot: Equatable {
        let held: Bool
        let focused: String?
        let active: Bool
        let usable: Bool
    }
    private var readySnapshot: ReadySnapshot {
        // Reader selection is authoritative while VoiceOver is active; input focus
        // remains authoritative for ordinary remote/keyboard operation.
        let key = UIAccessibility.isVoiceOverRunning ? accessibilityFocused : focused
        var keys: [String] = coldPending ? ["play", "startOver", "watched", "favorite"] : []
        if let item = state.item, item.type != "Person" {
            keys = ["watched", "favorite"]
            if item.isPlayable || ["Series", "Season"].contains(item.type) { keys.append("play") }
            if item.isPlayable && (stableActions || item.userData.playbackPositionTicks > 0) { keys.append("startOver") }
        }
        let selected = keys.contains(key ?? "") ? key : nil
        let usable: Bool
        switch selected {
        case "play", "startOver": usable = !guarded && !unavailablePlay
        case "watched": usable = !guarded && !state.empty && state.readError == nil
        case "favorite": usable = !guarded && state.readError == nil
        default: usable = false
        }
        return ReadySnapshot(held: pendingPresentation && (!state.fresh || state.reading || model.playbackSaving), focused: selected, active: visible && resumed && scenePhase == .active && model.player == nil && state.readError == nil && state.writeError == nil && state.pending.isEmpty && state.activeCommand == nil, usable: usable)
    }
    @ViewBuilder private func actionRow(_ item: MediaItem, horizontal: Bool) -> some View {
        #if os(tvOS)
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 16) { playActions(item); remotePlayAction; mutationActions(item) }
            VStack(alignment: .leading, spacing: 16) { playActions(item); remotePlayAction; mutationActions(item) }
        }
        #else
        VStack(alignment: .leading, spacing: 12) {
            // Primary playback stays full width. Secondary actions use equal
            // columns at ordinary sizes and one stable list at very large type.
            playActions(item)
            if dynamicTypeSize >= .xxxLarge {
                VStack(alignment: .leading, spacing: 12) { remotePlayAction; mutationActions(item) }
            } else {
                HStack(alignment: .top, spacing: 12) { remotePlayAction; mutationActions(item) }
                    .labelStyle(DetailSecondaryLabelStyle(singleLine: !guarded && state.readError == nil && state.pending.isEmpty && state.activeCommand == nil))
            }
        }
        .frame(maxWidth: UIDevice.current.userInterfaceIdiom == .pad ? 420 : .infinity, alignment: .leading)
        .frame(maxWidth: .infinity, alignment: .leading)
        #endif
    }
    @ViewBuilder private func playActions(_ item: MediaItem) -> some View {
        let playRequest = state.requestedLabel(.play(true))
        let playLabel = playRequest ?? (unavailablePlay ? DetailFeedback.unavailableActionLabel(target: "primary") : primaryLabel(item))
        if item.isPlayable || ["Series", "Season"].contains(item.type) {
            Button { opener = "play"; offer(.play(true), key: "play") } label: {
                Label(playLabel, systemImage: playRequest != nil ? "checkmark" : "play.fill").modifier(DetailActionWidth())
            }.catalogActionStyle(prominent: true).modifier(DetailActionWidth())
                .detailActionState(value: "", unavailable: unavailablePlay, held: guarded, label: semanticLabel(plain: "Play", pending: "Play when ready", actual: playLabel, unavailable: unavailablePlay), requestStatus: playRequest, acceptsHeldRequest: true)
                .disabled(state.requested(.play(false)))
                .accessibilityFocused($accessibilityFocused, equals: "play").focused($focused, equals: "play").id("play").accessibilityIdentifier("play")
        }
        if item.isPlayable && (stableActions || coldPending || item.userData.playbackPositionTicks > 0) { startOverButton }
    }
    @ViewBuilder private var remotePlayAction: some View {
        if let target = state.target {
            Menu {
                Button("Play on TV") { showRemote(target) }.accessibilityIdentifier("remote-mode-play-now")
                Button("Play next on TV") { showRemote(target, operation: "play-next") }.accessibilityIdentifier("remote-mode-play-next")
                Button("Add to TV queue") { showRemote(target, operation: "add-to-queue") }.accessibilityIdentifier("remote-mode-add-to-queue")
                Button("Play on TV from start") { showRemote(target, fromStart: true) }.accessibilityIdentifier("remote-mode-from-start")
            } label: {
                #if os(tvOS)
                TelevisionActionInk { Label("Play on TV", systemImage: "tv") }
                    .modifier(DetailActionWidth())
                #else
                Label(dynamicTypeSize < .xxxLarge ? "TV" : "Play on TV", systemImage: "tv").modifier(DetailActionWidth())
                #endif
            }
                #if os(tvOS)
                // Menu owns its native activation; a primitive Button style
                // would replace its label/ink and leave an empty filled target.
                .buttonStyle(.bordered).tint(Optional<Color>.none)
                #else
                .catalogActionStyle()
                #endif
                .modifier(DetailActionWidth()).disabled(guarded || unavailablePlay)
                .accessibilityLabel("Play on TV")
                .focused($focused, equals: "playOnDevice").accessibilityFocused($accessibilityFocused, equals: "playOnDevice")
                .id("playOnDevice").accessibilityIdentifier("playOnDevice")
        }
    }
    @ViewBuilder private func mutationActions(_ item: MediaItem) -> some View {
        let watchedCommand: DetailCommand = .watched(!item.isWatched)
        let favoriteCommand: DetailCommand = .favorite(!item.userData.isFavorite)
        let watchedRequest = state.requestedLabel(watchedCommand)
        let favoriteRequest = state.requestedLabel(favoriteCommand)
        let watchedLabel = state.readError != nil ? DetailFeedback.unavailableActionLabel(target: "played") : watchedRequest ?? (item.isWatched ? "Mark unwatched" : "Mark watched")
        let favoriteLabel = state.readError != nil ? DetailFeedback.unavailableActionLabel(target: "favorite") : favoriteRequest ?? (item.userData.isFavorite ? "Remove favorite" : "Add favorite")
        Button { opener = "watched"; offer(watchedCommand, key: "watched") } label: {
            Label(touchActionTitle("Watched", full: watchedLabel, requested: watchedRequest != nil), systemImage: state.readError == nil && item.isWatched ? "checkmark.circle.fill" : "checkmark.circle").modifier(DetailActionWidth())
        }.catalogActionStyle().modifier(DetailActionWidth())
            .detailActionState(value: actionValue(item.isWatched ? "Watched" : "Unwatched", unavailable: state.empty), unavailable: state.empty || state.readError != nil, held: guarded, label: semanticLabel(plain: item.isWatched ? "Mark unwatched" : "Mark watched", pending: item.isWatched ? "Mark unwatched when ready" : "Mark watched when ready", actual: watchedLabel, unavailable: state.empty || state.readError != nil), requestStatus: watchedRequest, acceptsHeldRequest: true)
            .accessibilityFocused($accessibilityFocused, equals: "watched").focused($focused, equals: "watched").id("watched").accessibilityIdentifier("watched")
        Button { opener = "favorite"; offer(favoriteCommand, key: "favorite") } label: {
            Label(touchActionTitle("Favorite", full: favoriteLabel, requested: favoriteRequest != nil), systemImage: state.readError == nil && item.userData.isFavorite ? "heart.fill" : "heart").modifier(DetailActionWidth())
        }.catalogActionStyle().modifier(DetailActionWidth())
            .detailActionState(value: actionValue(item.userData.isFavorite ? "Favorite" : "Not favorite"), unavailable: state.readError != nil, held: guarded, label: semanticLabel(plain: item.userData.isFavorite ? "Remove favorite" : "Add favorite", pending: item.userData.isFavorite ? "Remove favorite when ready" : "Add favorite when ready", actual: favoriteLabel, unavailable: state.readError != nil), requestStatus: favoriteRequest, acceptsHeldRequest: true)
            .accessibilityFocused($accessibilityFocused, equals: "favorite").focused($focused, equals: "favorite").id("favorite").accessibilityIdentifier("favorite")
    }
    private func touchActionTitle(_ short: String, full: String, requested: Bool) -> String {
        #if os(tvOS)
        full
        #else
        !guarded && !requested && state.readError == nil && dynamicTypeSize < .xxxLarge ? short : full
        #endif
    }
    private var startOverButton: some View {
        let request = state.requestedLabel(.play(false))
        let label = request != nil ? request! : state.requested(.play(true)) ? "Play already requested" : unavailablePlay ? DetailFeedback.unavailableActionLabel(target: "from-start") : "Play from start"
        return Button { opener = "startOver"; offer(.play(false), key: "startOver") } label: {
            Label(label, systemImage: request != nil ? "checkmark" : "backward.end.fill").modifier(DetailActionWidth())
        }.catalogActionStyle().modifier(DetailActionWidth())
            .detailActionState(value: "", unavailable: unavailablePlay, held: guarded, label: semanticLabel(plain: "Play from start", pending: "Play from start when ready", actual: label, unavailable: unavailablePlay), requestStatus: request, acceptsHeldRequest: true)
            .disabled(state.requested(.play(true)))
            .focused($focused, equals: "startOver").id("startOver").accessibilityIdentifier("startOver")
            .accessibilityFocused($accessibilityFocused, equals: "startOver")
    }
    private func semanticLabel(plain: String, pending: String, actual: String, unavailable: Bool) -> String {
        if unavailable { return actual }
        if guarded { return pendingPresentation ? pending : plain }
        return actual
    }
    private var guarded: Bool { !state.fresh || state.reading || model.playbackSaving || state.writing }
    private var unavailablePlay: Bool { state.readError != nil || (state.fresh && state.target == nil) }
    private func actionValue(_ current: String?, unavailable: Bool = false) -> String {
        DetailFeedback.actionDescription(currentState: current, available: !guarded && !unavailable && state.readError == nil) ?? ""
    }
    private func offer(_ command: DetailCommand, key: String) {
        if state.readError != nil || (["play", "startOver"].contains(key) && unavailablePlay) || (key == "watched" && state.empty) { return }
        let waiting = guarded
        let accepted = state.offer(command, model: model)
        if DetailFeedback.requestIsUrgent(newlyAccepted: accepted && waiting, requested: state.requested(command), available: true) {
            state.acceptRequestCue(command)
        }
    }
    @ViewBuilder private var recoveryPanel: some View {
        #if os(tvOS)
        let layout = AnyLayout(HStackLayout(alignment: .center, spacing: 24))
        #else
        let layout = AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
        #endif
        if state.recoveryVisible {
            layout {
                Button {
                    guard !state.reading else { return }
                    state.refresh(id: id, model: model, deliberate: true)
                } label: { Text(state.reading || state.fresh ? "Retrying…" : "Retry") }
                .catalogActionStyle(prominent: true).focused($focused, equals: "detailRetry").accessibilityFocused($accessibilityFocused, equals: "detailRetry")
                .id("detailRetry").accessibilityIdentifier("detailRetry")
                .accessibilityValue(state.reading || state.fresh ? "Loading item. Please wait." : "Retry. Loading failed. \(state.readFailure?.message ?? state.readError ?? "")")
                #if os(tvOS)
                if let reason = recoveryReason {
                    ScrollView(.vertical) {
                        Text(reason)
                            .foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading).selectableReading().readingFocusable()
                    }.frame(maxWidth: .infinity, maxHeight: max(1, recoveryLaneHeight - 40))
                }
                #else
                if let reason = recoveryReason {
                    Text(reason).foregroundStyle(.orange).selectableReading()
                }
                #endif
            }.modifier(DetailRecoverySurface())
        } else if let failure = state.writeFailure {
            layout {
                #if os(tvOS)
                ScrollView(.vertical) {
                    Text("Change failed. \(failure.message)").foregroundStyle(.orange)
                        .fixedSize(horizontal: false, vertical: true).frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("detailWriteError").selectableReading().readingFocusable()
                }.frame(maxWidth: .infinity, maxHeight: max(1, recoveryLaneHeight - 40))
                #else
                Text("Change failed. \(failure.message)").foregroundStyle(.orange).selectableReading().accessibilityIdentifier("detailWriteError")
                #endif
                Button { state.retryWrite(model: model) } label: { Text("Retry change") }.catalogActionStyle().accessibilityIdentifier("detailWriteRetry")
            }.modifier(DetailRecoverySurface()).accessibilityHidden(!visible || scenePhase != .active)
        }
    }
    private var recoveryReason: String? {
        if state.reading || state.retrying || (state.recoveryVisible && state.fresh) { return DetailFeedback.readBandText(retrying: true, reason: "") }
        return (state.readFailure?.message ?? state.readError).map { DetailFeedback.readBandText(retrying: false, reason: $0) }
    }
    private func primaryLabel(_ item: MediaItem) -> String {
        let target = state.target ?? item
        return target.userData.playbackPositionTicks > 0 ? Catalog.resumeLabel(target.userData.playbackPositionTicks) : "Play"
    }
    private var heroHeight: CGFloat {
        #if os(tvOS)
        300
        #else
        220
        #endif
    }
    private func metadata(_ item: MediaItem) -> String { [item.type == "Episode" ? item.seriesName : nil, item.type == "Episode" ? item.episodeLabel : nil, item.productionYear.map(String.init), item.officialRating, Catalog.runtimeLabel(item.runTimeTicks)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ") }
}

/// A full-bleed scene behind wide detail layouts; content, not a fixed
/// centre-cropped strip, determines the page's foreground height.
private struct DetailHeroBackdrop: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    @State private var image: UIImage?
    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Theme.background
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: geometry.size.width, height: max(620, geometry.size.width * 9 / 16), alignment: .topTrailing)
                        .clipped()
                        .overlay {
                            LinearGradient(stops: [
                                .init(color: .clear, location: 0.55),
                                .init(color: Theme.background, location: 1)
                            ], startPoint: .top, endPoint: .bottom)
                        }
                        .frame(maxHeight: .infinity, alignment: .top)
                }
                LinearGradient(stops: [
                    .init(color: Theme.background.opacity(0.98), location: 0),
                    .init(color: Theme.background.opacity(0.94), location: 0.45),
                    .init(color: Theme.background.opacity(0.5), location: 0.7),
                    .init(color: Theme.background.opacity(0.15), location: 1)
                ], startPoint: .leading, endPoint: .trailing)
                LinearGradient(stops: [
                    .init(color: Theme.background.opacity(0.05), location: 0),
                    .init(color: Theme.background.opacity(0.5), location: 0.6),
                    .init(color: Theme.background, location: 1)
                ], startPoint: .top, endPoint: .bottom)
            }
        }
        .allowsHitTesting(false).accessibilityHidden(true)
        .task(id: item.id) {
            image = nil
            guard let account = model.account,
                  let url = model.client.imageURL(account: account, item: item, maxWidth: 1920, wide: true) else { return }
            let loaded = await ArtworkCache.shared.image(url: url, account: account, client: model.client)
            guard !Task.isCancelled else { return }
            image = loaded
        }
    }
}

private struct DetailPersonPortrait: View {
    let name: String
    let diameter: CGFloat
    private var initials: String {
        let words = name.split(whereSeparator: { $0.isWhitespace })
        return [words.first?.first, words.count > 1 ? words.last?.first : nil]
            .compactMap { $0 }.map(String.init).joined().uppercased()
    }
    var body: some View {
        Text(initials.isEmpty ? "?" : initials)
            .font(.system(size: diameter * 0.34, weight: .semibold))
            .foregroundStyle(Theme.muted)
            .frame(width: diameter, height: diameter)
            .background(Theme.surface, in: Circle())
            .accessibilityHidden(true)
    }
}
private extension PersonCredit {
    var detailDisplayRole: String? {
        [role, type].compactMap { $0?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
    }
}
private struct DetailPersonMetadata: View {
    let person: PersonCredit
    var body: some View {
        VStack(spacing: 6) {
            Text(person.name).font(.body.weight(.medium)).foregroundStyle(.primary)
                .lineLimit(2, reservesSpace: true)
            Text(person.detailDisplayRole ?? "").font(.caption).foregroundStyle(Theme.muted)
                .lineLimit(2, reservesSpace: true)
        }.multilineTextAlignment(.center)
    }
}


private struct DetailRecoverySurface: ViewModifier {
    func body(content: Content) -> some View {
        #if os(tvOS)
        content.padding(12).frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.surface).accessibilityElement(children: .contain).accessibilityIdentifier("detailRecoverySurface").padding(.horizontal, Theme.gutter).padding(.vertical, 8)
        #else
        content.padding(12).frame(maxWidth: 440, alignment: .leading).background(Theme.surface).accessibilityElement(children: .contain).accessibilityIdentifier("detailRecoverySurface").padding(8)
        #endif
    }
}

struct PersonFilmography: View {
    @EnvironmentObject private var model: AppModel
    let id: String
    var open: (MediaItem) -> Void
    @StateObject private var page = PagedCatalog()
    var body: some View {
        VStack(alignment: .leading, spacing: 24) {
            Text("Movies and shows").font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let error = page.error { ErrorNotice(error: error) { Task { await page.load(model: model, parent: id, person: true) } } }
            if page.items.isEmpty && !page.loading && page.error == nil { Text("No linked titles are available.") }
            ForEach(page.items) { item in Button { open(item) } label: { HStack { Artwork(item: item).frame(width: 80, height: 110); VStack(alignment: .leading) { Text(item.name); Text(item.cardSubtitle).foregroundStyle(Theme.muted) } } }.buttonStyle(PosterButtonStyle()).accessibilityLabel(item.accessibleTitle).accessibilityIdentifier("item-\(item.id)").onAppear { if item.id == page.items.last?.id, page.next < page.total { Task { await page.load(model: model, parent: id, person: true) } } } }
            if page.loading { ProgressView("Loading filmography…") }
            if page.next < page.total && !page.loading { Button { Task { await page.load(model: model, parent: id, person: true) } } label: { Text("Load more").minimumTouchTarget() } }
        }.task(id: id) { await page.load(model: model, parent: id, person: true, reset: true) }
    }
}

private struct DetailActionWidth: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        #if os(iOS)
        content.frame(maxWidth: .infinity, alignment: .center)
        #else
        content
        #endif
    }
}
private struct DetailSecondaryLabelStyle: LabelStyle {
    var singleLine = true
    func makeBody(configuration: Configuration) -> some View {
        VStack(spacing: 8) {
            configuration.icon.font(.title3)
            configuration.title.multilineTextAlignment(.center)
                .lineLimit(singleLine ? 1 : nil).minimumScaleFactor(singleLine ? 0.8 : 1)
                .fixedSize(horizontal: false, vertical: true)
        }.frame(maxWidth: .infinity, minHeight: 64, alignment: .top)
    }
}
private struct SeasonChoiceStyle: ViewModifier {
    let selected: Bool
    @ViewBuilder func body(content: Content) -> some View {
        #if os(iOS)
        content.buttonStyle(.plain).foregroundStyle(selected ? Theme.background : .primary)
            .background(selected ? Theme.accent : Theme.surface, in: RoundedRectangle(cornerRadius: 10))
        #else
        content.modifier(TelevisionChoiceStyle(selected: selected))
        #endif
    }
}

#if os(iOS)
private struct DetailTitleContentBottom: PreferenceKey {
    static var defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = min(value, nextValue()) }
}
private struct DetailTitleViewportBottom: PreferenceKey {
    static var defaultValue: CGFloat = .infinity
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = min(value, nextValue()) }
}
#endif
