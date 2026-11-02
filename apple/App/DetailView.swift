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
        reading = false; retrying = false; retryingWrite = false
        pending = []; queuedAt = nil; cue = nil; requestCueKey = nil
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
        reading = false; retrying = false; fresh = false; recoveryVisible = true
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
        seasons = result.seasons; episodes = result.episodes; selectedSeason = result.season; target = result.target
        reading = false; fresh = true; readError = nil; deadline = nil; publishedRefreshID = publication; cue = nil
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
    func chooseSeason(_ id: String, model: AppModel) { selectedSeason = id; if let item { deadline = nil; refresh(id: item.id, model: model, restart: true) } }
    @discardableResult func offer(_ command: DetailCommand, model: AppModel) -> Bool {
        guard readError == nil, enqueue(command) else { return false }
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
    let id: String
    var open: (MediaItem) -> Void
    @StateObject private var state = DetailState()
    @FocusState private var focused: String?
    @State private var opener = "play"
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
    #if os(tvOS)
    @ScaledMetric(relativeTo: .body) private var recoveryLaneHeight: CGFloat = 160
    #endif
    var body: some View {
        ScrollViewReader { proxy in
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
                            ScrollView(.horizontal) { HStack(spacing: 18) { ForEach(state.seasons) { season in Button { guard !guarded, state.readError == nil else { return }; opener = "season-\(season.id)"; state.chooseSeason(season.id, model: model) } label: { Text(season.name).minimumTouchTarget() }.secondaryActionStyle().tint(state.fresh && !state.reading && state.selectedSeason == season.id ? Theme.accent : Theme.muted).detailActionState(value: state.selectedSeason == season.id ? "Selected" : "", unavailable: state.readError != nil, held: guarded, label: season.name).accessibilityIdentifier("season-\(season.id)").focused($focused, equals: "season-\(season.id)").id("season-\(season.id)") } }.padding(.vertical, 8) }
                        }
                        if ["Series", "Season"].contains(item.type) {
                            if state.empty { Text("No episodes are available.").foregroundStyle(Theme.muted) }
                            else { CatalogShelf(title: "Episodes", items: state.episodes.map { model.presented($0) }, wide: true, horizontalInset: 0, open: open) }
                        }
                        if !item.people.isEmpty {
                            Text("Cast and crew").font(.title2.bold()).accessibilityAddTraits(.isHeader).accessibilityHidden(metadataHidden)
                            ForEach(item.people) { person in Button { var target = MediaItem(id: person.id, name: person.name, type: "Person"); target.overview = nil; open(target) } label: { VStack(alignment: .leading) { Text(person.name); Text(person.role ?? person.type ?? "").font(.caption).foregroundStyle(Theme.secondaryButtonText) }.minimumTouchTarget() }.secondaryActionStyle().accessibilityLabel([person.name, person.role ?? person.type].compactMap { $0 }.joined(separator: ", ")) }
                        }
                        if header != item, let overview = item.overview, overview != header.overview { Text("More details").font(.title2).accessibilityAddTraits(.isHeader).accessibilityHidden(metadataHidden); Text(overview).accessibilityHidden(metadataHidden) }
                    } else if coldPending {
                        // Publish the destination's real guarded slots before metadata
                        // arrives. Their intents use this state's existing bounded queue.
                        actionRow(MediaItem(id: id, name: "", type: "Movie"), horizontal: coldActionsHorizontal)
                        ProgressView("Loading item…").accessibilityHidden(true)
                    } else if state.reading { ProgressView("Loading item…") }
                    if let warning = model.playbackSaveError { Text(warning).foregroundStyle(.orange); Button { Task { await model.retryPlaybackSave() } } label: { Text(model.playbackSaving ? "Saving progress…" : "Retry saving progress").minimumTouchTarget() }.disabled(model.playbackSaving).secondaryActionStyle().accessibilityIdentifier("retryProgress") }
                    if let notice = state.notice { Text(notice).foregroundStyle(Theme.muted) }
                }.padding(Theme.gutter)
                    .accessibilityHidden(!coldPending && DetailFeedback.arrivalAwaitsAction(loading: state.reading, contentRevealed: contentRevealed, returningFromPlayback: returnedFromPlayback))
            }
            .overlay(alignment: .top) {
                if state.header != nil && (model.playbackSaving || state.reading || state.writing) {
                    Text(model.playbackSaving ? (state.fresh ? "Item loaded. Saving progress…" : "Saving progress…") : state.writing ? "Saving change…" : state.item?.type == "Series" || state.item?.type == "Season" ? "Loading episodes…" : "Loading item…")
                        .padding(12).background(Theme.surface.opacity(0.95), in: Capsule()).padding(.top, 8).accessibilityIdentifier("detailStatus").allowsHitTesting(false)
                }
            }
            #if os(iOS)
            .safeAreaInset(edge: .bottom, spacing: 0) { recoveryPanel }
            #else
            // The bottom lane is reserved from the first frame, including healthy
            // states. Recovery stays outside the content and does not resize its
            // viewport; native focus may still scroll to the selected action.
            .safeAreaInset(edge: .bottom, spacing: 0) {
                ZStack {
                    Color.clear.allowsHitTesting(false)
                    recoveryPanel
                }
                .frame(maxWidth: .infinity, minHeight: recoveryLaneHeight, maxHeight: recoveryLaneHeight, alignment: .center)
                .background(Theme.background)
            }
            #endif
            .background(Theme.background.ignoresSafeArea()).pictureshowNavigationTitle(state.header?.name ?? "Details", opaqueTVTitle: true)
            .announceCue(state.cue)
            .announceStatus(state.notice)
            .accessibilityHidden(model.player != nil)
            .task(id: id) { state.startInitialRead(id: id, model: model) }
            .onAppear { visible = true; state.appear(); resumeIfActive(); focused = opener; returnedFromPlayback = contentRevealed }
            .onDisappear { visible = false; resumed = false; accessibilityFocused = nil; pendingPresentation = false; state.leave() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { resumeIfActive() } else { resumed = false; pendingPresentation = false; accessibilityFocused = nil; state.cue = nil }
            }
            .task(id: PendingPresentation(generation: state.readPresentationID, waiting: guarded, active: visible && resumed && scenePhase == .active && model.player == nil)) {
                pendingPresentation = false
                guard guarded, visible, resumed, scenePhase == .active, model.player == nil else { return }
                do { try await Task.sleep(for: .milliseconds(1_500)) } catch { return }
                guard !Task.isCancelled else { return }
                pendingPresentation = true
                detailDiagnostic("event=heldLabelsPublished")
            }
            .onChange(of: readySnapshot, initial: true) { _, snapshot in
                if let message = readiness.update(held: snapshot.held, focused: snapshot.focused, resumed: snapshot.active, usable: snapshot.usable, presented: pendingPresentation, cancelled: requestCancelledReady || state.readError != nil || state.writeError != nil || !state.pending.isEmpty || state.activeCommand != nil) {
                    let cue = AccessibilityCue(message: message, urgent: false)
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
            .onChange(of: focused) { _, focus in state.requestFocusChanged(focus); if let focus { opener = focus; if state.item != nil, focus != "detailRetry", !handoffInFlight { handoffFinished = true }; proxy.scrollTo(focus, anchor: .center) } }
            .onChange(of: accessibilityFocused) { _, focus in
                // VoiceOver can leave the action for page text without changing
                // keyboard/remote focus. Nil selection ends acceptance as well.
                if UIAccessibility.isVoiceOverRunning { state.requestFocusChanged(focus) }
                if let focus { opener = focus; if state.item != nil, focus != "detailRetry", !handoffInFlight { handoffFinished = true }; proxy.scrollTo(focus, anchor: .center) } }
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
    private func hero(_ header: MediaItem, current item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Artwork(item: header, wide: true).frame(maxWidth: .infinity).frame(height: heroHeight).clipShape(RoundedRectangle(cornerRadius: 18))
            Text(header.name).modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("detailTitle").accessibilityFocused($accessibilityFocused, equals: "heading").accessibilityHidden(metadataHidden)
            Text(metadata(header)).foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden)
            // Published action slots stay in one order while final reports and fresh reads run.
            #if os(tvOS)
            actionRow(item, horizontal: true)
            #else
            actionRow(item, horizontal: false)
            #endif
            if let target = state.target, target.id != item.id { Text("Next: \(target.accessibleTitle)").foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden) }
            if let overview = header.overview, !overview.isEmpty { Text(overview).modifier(TokenFont(DesignTokens.bodySize)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true).accessibilityHidden(metadataHidden) }
            if !header.genres.isEmpty { Text(header.genres.joined(separator: " · ")).foregroundStyle(Theme.muted).accessibilityHidden(metadataHidden) }
        }
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
        let layout = horizontal ? AnyLayout(HStackLayout(spacing: 16)) : AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
        let watchedCommand: DetailCommand = .watched(!item.isWatched)
        let favoriteCommand: DetailCommand = .favorite(!item.userData.isFavorite)
        let watchedRequest = state.requestedLabel(watchedCommand)
        let favoriteRequest = state.requestedLabel(favoriteCommand)
        let playRequest = state.requestedLabel(.play(true))
        let watchedLabel = state.readError != nil ? DetailFeedback.unavailableActionLabel(target: "played") : watchedRequest != nil ? watchedRequest! : item.isWatched ? "Mark unwatched" : "Mark watched"
        let favoriteLabel = state.readError != nil ? DetailFeedback.unavailableActionLabel(target: "favorite") : favoriteRequest != nil ? favoriteRequest! : item.userData.isFavorite ? "Remove favorite" : "Add favorite"
        let playLabel = playRequest != nil ? playRequest! : state.requested(.play(false)) ? "Start already requested" : unavailablePlay ? DetailFeedback.unavailableActionLabel(target: "primary") : primaryLabel(item)
        layout {
            if item.isPlayable || ["Series", "Season"].contains(item.type) {
                Button { opener = "play"; offer(.play(true), key: "play") } label: {
                    Label(playLabel, systemImage: "play.fill").minimumTouchTarget()
                }.buttonStyle(ProminentActionStyle())
                    .detailActionState(value: "", unavailable: unavailablePlay, held: guarded, label: semanticLabel(plain: "Play", pending: "Play when ready", actual: playLabel, unavailable: unavailablePlay), requestStatus: playRequest, acceptsHeldRequest: true)
                    .accessibilityFocused($accessibilityFocused, equals: "play").focused($focused, equals: "play").id("play").accessibilityIdentifier("play")
            }
            if !stableActions && item.isPlayable && item.userData.playbackPositionTicks > 0 { startOverButton }
            Button { opener = "watched"; offer(watchedCommand, key: "watched") } label: {
                Label(watchedLabel, systemImage: state.readError == nil && item.isWatched ? "checkmark.circle.fill" : "checkmark.circle").minimumTouchTarget()
            }.secondaryActionStyle()
                .detailActionState(value: actionValue(item.isWatched ? "Watched" : "Unwatched", unavailable: state.empty), unavailable: state.empty || state.readError != nil, held: guarded, label: semanticLabel(plain: item.isWatched ? "Mark unwatched" : "Mark watched", pending: item.isWatched ? "Mark unwatched when ready" : "Mark watched when ready", actual: watchedLabel, unavailable: state.empty || state.readError != nil), requestStatus: watchedRequest, acceptsHeldRequest: true)
                .accessibilityFocused($accessibilityFocused, equals: "watched").focused($focused, equals: "watched").id("watched").accessibilityIdentifier("watched")
            Button { opener = "favorite"; offer(favoriteCommand, key: "favorite") } label: {
                Label(favoriteLabel, systemImage: state.readError == nil && item.userData.isFavorite ? "heart.fill" : "heart").minimumTouchTarget()
            }.secondaryActionStyle()
                .detailActionState(value: actionValue(item.userData.isFavorite ? "Favorite" : "Not favorite"), unavailable: state.readError != nil, held: guarded, label: semanticLabel(plain: item.userData.isFavorite ? "Remove favorite" : "Add favorite", pending: item.userData.isFavorite ? "Remove favorite when ready" : "Add favorite when ready", actual: favoriteLabel, unavailable: state.readError != nil), requestStatus: favoriteRequest, acceptsHeldRequest: true)
                .accessibilityFocused($accessibilityFocused, equals: "favorite").focused($focused, equals: "favorite").id("favorite").accessibilityIdentifier("favorite")
            if (stableActions || coldPending) && item.isPlayable { startOverButton }
        }
    }
    private var startOverButton: some View {
        let request = state.requestedLabel(.play(false))
        let label = request != nil ? request! : state.requested(.play(true)) ? "Play already requested" : unavailablePlay ? DetailFeedback.unavailableActionLabel(target: "from-start") : "Play from start"
        return Button { opener = "startOver"; offer(.play(false), key: "startOver") } label: {
            Text(label).minimumTouchTarget()
        }.secondaryActionStyle().focused($focused, equals: "startOver").id("startOver").accessibilityIdentifier("startOver")
            .detailActionState(value: "", unavailable: unavailablePlay, held: guarded, label: semanticLabel(plain: "Play from start", pending: "Play from start when ready", actual: label, unavailable: unavailablePlay), requestStatus: request, acceptsHeldRequest: true).accessibilityFocused($accessibilityFocused, equals: "startOver")
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
                } label: { Text(state.reading || state.fresh ? "Retrying…" : "Retry").minimumTouchTarget() }
                .buttonStyle(ProminentActionStyle()).focused($focused, equals: "detailRetry").accessibilityFocused($accessibilityFocused, equals: "detailRetry")
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
                Button { state.retryWrite(model: model) } label: { Text("Retry change").minimumTouchTarget() }.secondaryActionStyle().accessibilityIdentifier("detailWriteRetry")
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
