// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

enum DetailCommand: Equatable {
    case play(Bool), watched(Bool), favorite(Bool)
}

@MainActor final class DetailState: ObservableObject {
    @Published var item: MediaItem?
    @Published var header: MediaItem?
    @Published var seasons: [MediaItem] = []
    @Published var episodes: [MediaItem] = []
    @Published var selectedSeason: String?
    @Published var target: MediaItem?
    @Published var reading = false
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
    func appear() { departed = false }
    func leave() {
        departed = true; epoch = UUID(); readTask?.cancel(); readTask = nil
        reading = false; retrying = false; retryingWrite = false
        pending = []; queuedAt = nil; cue = nil
    }
    var empty: Bool {
        DetailAvailability.showEmptyState(itemType: item?.type, loading: reading, readSucceeded: fresh, readFailed: readError != nil, episodeCount: episodes.count, seasonCount: seasons.count)
    }
    var recoveryTarget: String { target != nil ? "play" : empty ? "favorite" : "watched" }
    func beginRead(deliberate: Bool) {
        reading = true; fresh = false
        if deliberate { retrying = true; readFailure = nil }
        // Automatic transport generations retain the same error episode and Retry slot.
    }
    func publishReadFailure(_ message: String) {
        reading = false; retrying = false; readError = message; fresh = false; recoveryVisible = true
        let key = DetailFeedback.failureKey(readRecovery: true, retrying: false, writeAttempt: nil)
        let previous = readFailure
        readFailure = DetailFeedback.presentFailure(previous: previous, key: key, message: message)
        if previous == nil { cue = AccessibilityCue(message: "Retry. Loading failed. \(readFailure?.message ?? message)") }
        focusRequest = "detailRetry"; pending = []; queuedAt = nil
    }
    func publishRead(_ result: DetailPublication, publication: UUID) {
        let initial = header == nil
        item = result.item; if header == nil { header = result.item }
        seasons = result.seasons; episodes = result.episodes; selectedSeason = result.season; target = result.target
        reading = false; fresh = true; readError = nil; deadline = nil; publishedRefreshID = publication
        if recoveryVisible { focusRequest = recoveryTarget }
        else if initial && result.item.type != "Person" { focusRequest = recoveryTarget }
    }
    func finishRecoveryHandoff() { recoveryVisible = false; retrying = false; readFailure = nil }
    func beginWriteAttempt() { writeAttempt += 1; writeError = nil; writeFailure = nil }
    func publishWriteFailure(_ message: String) {
        writeError = message
        let key = DetailFeedback.failureKey(readRecovery: false, retrying: false, writeAttempt: writeAttempt)
        let previous = writeFailure
        writeFailure = DetailFeedback.presentFailure(previous: previous, key: key, message: message)
        if previous == nil { cue = AccessibilityCue(message: "Change failed. \(writeFailure?.message ?? message) Retry change is available.") }
    }
    func requested(_ command: DetailCommand) -> Bool { pending.contains(command) || activeCommand == command }
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
        pending = []; self.queuedAt = nil; notice = "Waiting action expired. Choose it again."
        return true
    }
    func refresh(id: String, model: AppModel, deliberate: Bool = false, restart: Bool = false) {
        guard let account = model.account else { return }
        refresh(id: id, account: account, client: model.client, publication: model.refreshID, deliberate: deliberate, restart: restart,
                isCurrent: { model.account?.localKey == account.localKey },
                onFailure: { model.handle($0, account: account) }, onSuccess: { self.drain(model: model) })
    }
    /// The same authoritative read path is exercised by hosted HTTP fixtures without
    /// constructing account storage, a player or a second app model.
    func refresh(id: String, account: Account, client: JellyfinClient, publication: UUID, deliberate: Bool = false, restart: Bool = false,
                 isCurrent: @escaping @MainActor () -> Bool = { true },
                 onFailure: @escaping @MainActor (Error) -> Void = { _ in },
                 onSuccess: @escaping @MainActor () -> Void = {}) {
        if reading && (deliberate || !restart) { return }
        if deliberate { deadline = Date().addingTimeInterval(30) }
        else if deadline == nil { deadline = Date().addingTimeInterval(30) }
        guard let deadline else { return }
        readTask?.cancel(); epoch = UUID(); let version = epoch
        beginRead(deliberate: deliberate)
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
                    group.addTask { let wait = max(0, deadline.timeIntervalSinceNow); try await Task.sleep(for: .seconds(wait)); throw DetailTimeout() }
                    defer { group.cancelAll() }
                    guard let first = try await group.next() else { throw CancellationError() }; return first
                }
                guard version == epoch, !departed, isCurrent() else { return }
                publishRead(result, publication: publication)
                onSuccess()
            } catch is CancellationError { }
            catch {
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
            defer { drainTask = nil; activeCommand = nil; writing = false; if !pending.isEmpty { drain(model: model) } }
            while !pending.isEmpty, version == epoch, !departed, (fresh || retryingWrite), !model.playbackSaving {
                if expirePending() { return }
                let command = pending.removeFirst(); activeCommand = command; writing = true
                beginWriteAttempt()
                do {
                    switch command {
                    case .play(let resume):
                        guard let target else { notice = "No episodes are available."; continue }
                        await model.play(target, resume: resume, audio: nil, subtitle: nil)
                    case .watched(let desired):
                        guard var item else { continue }
                        if item.userData.played != desired { item.userData = try await model.client.played(account: account, itemID: item.id, value: desired) }
                        guard version == epoch, !departed else { return }; self.item = item
                    case .favorite(let desired):
                        guard var item else { continue }
                        if item.userData.isFavorite != desired { item.userData = try await model.client.favorite(account: account, itemID: item.id, value: desired) }
                        guard version == epoch, !departed else { return }; self.item = item
                    }
                    guard version == epoch, !departed else { return }
                    writeError = nil; writeFailure = nil; failedWrite = nil; retryingWrite = false; notice = nil
                    if case .play = command { } else { await model.loadHome(); deadline = nil; refresh(id: item?.id ?? "", model: model, restart: true); return }
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
    let id: String
    var open: (MediaItem) -> Void
    @StateObject private var state = DetailState()
    @FocusState private var focused: String?
    @State private var opener = "play"
    @AccessibilityFocusState private var accessibilityFocused: String?
    @State private var contentRevealed = false
    @State private var returnedFromPlayback = false
    @State private var wasGuarded = false
    @State private var stableActions = false
    @State private var didPublishAction = false
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if let item = state.item, item.type == "Person" {
                        Text(item.name).font(.largeTitle.bold()).accessibilityAddTraits(.isHeader)
                        if let overview = item.overview { Text(overview).selectableReading() }
                        PersonFilmography(id: item.id, open: open)
                    } else if let header = state.header, let item = state.item {
                        hero(header, current: item)
                        if item.type == "Series" {
                            Text("Seasons").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                            ScrollView(.horizontal) { HStack(spacing: 18) { ForEach(state.seasons) { season in Button { opener = "season-\(season.id)"; state.chooseSeason(season.id, model: model) } label: { Text(season.name).minimumTouchTarget() }.secondaryActionStyle().tint(state.selectedSeason == season.id ? Theme.accent : Theme.muted).accessibilityValue(state.selectedSeason == season.id ? "Selected" : "").accessibilityIdentifier("season-\(season.id)").focused($focused, equals: "season-\(season.id)").id("season-\(season.id)") } }.padding(.vertical, 8) }
                        }
                        if ["Series", "Season"].contains(item.type) {
                            if state.empty { Text("No episodes are available.").foregroundStyle(Theme.muted) }
                            else { CatalogShelf(title: "Episodes", items: state.episodes, wide: true, horizontalInset: 0, open: open) }
                        }
                        if !item.people.isEmpty {
                            Text("Cast and crew").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                            ForEach(item.people) { person in Button { var target = MediaItem(id: person.id, name: person.name, type: "Person"); target.overview = nil; open(target) } label: { VStack(alignment: .leading) { Text(person.name); Text(person.role ?? person.type ?? "").font(.caption).foregroundStyle(Theme.secondaryButtonText) }.minimumTouchTarget() }.secondaryActionStyle().accessibilityLabel([person.name, person.role ?? person.type].compactMap { $0 }.joined(separator: ", ")) }
                        }
                        if header != item, let overview = item.overview, overview != header.overview { Text("More details").font(.title2).accessibilityAddTraits(.isHeader); Text(overview) }
                    } else if state.reading { ProgressView("Loading item…") }
                    if let warning = model.playbackSaveError { Text(warning).foregroundStyle(.orange); Button { Task { await model.retryPlaybackSave() } } label: { Text(model.playbackSaving ? "Saving progress…" : "Retry saving progress").minimumTouchTarget() }.disabled(model.playbackSaving).secondaryActionStyle().accessibilityIdentifier("retryProgress") }
                    if let notice = state.notice { Text(notice).foregroundStyle(Theme.muted) }
                }.padding(Theme.gutter)
                    .accessibilityHidden(DetailFeedback.arrivalAwaitsAction(loading: state.reading, contentRevealed: contentRevealed, returningFromPlayback: returnedFromPlayback))
            }
            .overlay(alignment: .top) {
                if state.header != nil && (model.playbackSaving || state.reading) {
                    Text(model.playbackSaving ? (state.fresh ? "Item loaded. Saving progress…" : "Saving progress…") : state.item?.type == "Series" || state.item?.type == "Season" ? "Loading episodes…" : "Loading item…")
                        .padding(12).background(Theme.surface.opacity(0.95), in: Capsule()).padding(.top, 8).accessibilityIdentifier("detailStatus").allowsHitTesting(false)
                }
            }
            .overlay(alignment: .topTrailing) { recoveryPanel }
            .background(Theme.background.ignoresSafeArea()).pictureshowNavigationTitle(state.header?.name ?? "Details")
            .announceCue(state.cue)
            .announceStatus(state.notice)
            .accessibilityHidden(model.player != nil)
            .task(id: id) { state.appear(); state.refresh(id: id, model: model) }
            .onAppear { state.appear(); state.refresh(id: id, model: model); focused = opener; returnedFromPlayback = contentRevealed }
            .onDisappear { state.leave() }
            .onChange(of: model.refreshID) { _, _ in state.refresh(id: id, model: model) }
            .onChange(of: model.playbackSaving) { _, saving in wasGuarded = true; stableActions = true; if !saving { state.refresh(id: id, model: model, restart: true) } }
            .onChange(of: state.reading) { _, value in if !value { contentRevealed = state.item != nil; state.drain(model: model) } else { wasGuarded = true } }
            .onChange(of: state.focusRequest) { _, request in
                guard let request else { return }
                let ownsSelection = focused == "detailRetry" || accessibilityFocused == "detailRetry" || (focused == nil && accessibilityFocused == nil) || !didPublishAction
                if request == "detailRetry" || ownsSelection {
                    proxy.scrollTo(request, anchor: .center); focused = request; accessibilityFocused = request
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
                    if state.fresh, !state.reading { state.finishRecoveryHandoff() }
                }
            }
            .onChange(of: focused) { _, focus in if let focus { opener = focus; proxy.scrollTo(focus, anchor: .center) } }
            .onChange(of: accessibilityFocused) { _, focus in if let focus { opener = focus; proxy.scrollTo(focus, anchor: .center) } }
            #if os(tvOS)
            .onPlayPauseCommand {
                guard let item = state.item,
                      item.isPlayable || ["Series", "Season"].contains(item.type),
                      state.readError == nil else { return }
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
            Text(header.name).modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("detailTitle")
            Text(metadata(header)).foregroundStyle(Theme.muted)
            // Published action slots stay in one order while final reports and fresh reads run.
            #if os(tvOS)
            actionRow(item, horizontal: true)
            #else
            actionRow(item, horizontal: false)
            #endif
            if let target = state.target, target.id != item.id { Text("Next: \(target.accessibleTitle)").foregroundStyle(Theme.muted) }
            if let overview = header.overview, !overview.isEmpty { Text(overview).modifier(TokenFont(DesignTokens.bodySize)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            if !header.genres.isEmpty { Text(header.genres.joined(separator: " · ")).foregroundStyle(Theme.muted) }
        }
    }
    @ViewBuilder private func actionRow(_ item: MediaItem, horizontal: Bool) -> some View {
        let layout = horizontal ? AnyLayout(HStackLayout(spacing: 16)) : AnyLayout(VStackLayout(alignment: .leading, spacing: 16))
        layout {
            if item.isPlayable || ["Series", "Season"].contains(item.type) {
                Button { opener = "play"; offer(.play(true), key: "play") } label: { Label(state.requested(.play(true)) ? "Play requested" : state.requested(.play(false)) ? "Start already requested" : primaryLabel(item), systemImage: "play.fill").minimumTouchTarget() }.buttonStyle(ProminentActionStyle()).detailActionState(value: actionValue(nil, command: .play(true)), unavailable: unavailablePlay).accessibilityFocused($accessibilityFocused, equals: "play").focused($focused, equals: "play").id("play").accessibilityIdentifier("play")
            }
            if !stableActions && item.isPlayable && item.userData.playbackPositionTicks > 0 { startOverButton }
            Button { opener = "watched"; offer(.watched(!item.isWatched), key: "watched") } label: { Label(state.requested(.watched(!item.isWatched)) ? "Watched change requested" : guarded ? (item.isWatched ? "Mark unwatched when ready" : "Mark watched when ready") : item.isWatched ? "Mark unwatched" : "Mark watched", systemImage: item.isWatched ? "checkmark.circle.fill" : "checkmark.circle").minimumTouchTarget() }.secondaryActionStyle().detailActionState(value: actionValue(item.isWatched ? "Watched" : "Unwatched", command: .watched(!item.isWatched)), unavailable: state.empty || state.readError != nil).accessibilityFocused($accessibilityFocused, equals: "watched").focused($focused, equals: "watched").id("watched").accessibilityIdentifier("watched")
            Button { opener = "favorite"; offer(.favorite(!item.userData.isFavorite), key: "favorite") } label: { Label(state.requested(.favorite(!item.userData.isFavorite)) ? "Favorite change requested" : guarded ? (item.userData.isFavorite ? "Remove favorite when ready" : "Add favorite when ready") : item.userData.isFavorite ? "Remove favorite" : "Add favorite", systemImage: item.userData.isFavorite ? "heart.fill" : "heart").minimumTouchTarget() }.secondaryActionStyle().focused($focused, equals: "favorite").id("favorite").detailActionState(value: actionValue(item.userData.isFavorite ? "Favorite" : "Not favorite", command: .favorite(!item.userData.isFavorite)), unavailable: state.readError != nil).accessibilityFocused($accessibilityFocused, equals: "favorite").accessibilityIdentifier("favorite")
            if stableActions && item.isPlayable { startOverButton }
        }
    }
    private var startOverButton: some View {
        Button { opener = "startOver"; offer(.play(false), key: "startOver") } label: {
            Text(state.requested(.play(false)) ? "Start requested" : state.requested(.play(true)) ? "Play already requested" : "Play from start").minimumTouchTarget()
        }.secondaryActionStyle().focused($focused, equals: "startOver").id("startOver").accessibilityIdentifier("startOver")
            .detailActionState(value: actionValue(nil, command: .play(false)), unavailable: state.readError != nil).accessibilityFocused($accessibilityFocused, equals: "startOver")
    }
    private var guarded: Bool { !state.fresh || state.reading || model.playbackSaving || state.writing }
    private var unavailablePlay: Bool { state.readError != nil || (state.fresh && state.target == nil) }
    private func actionValue(_ current: String?, command: DetailCommand) -> String {
        let unavailable = state.readError != nil || (command == .play(true) && unavailablePlay) || (command == .watched(true) && state.empty)
        if unavailable && !model.playbackSaving { return [current, "Unavailable until the item is ready."].compactMap { $0 }.joined(separator: ". ") }
        return DetailFeedback.actionDescription(currentState: current, pending: guarded, available: !unavailable, ready: wasGuarded && !guarded, requested: state.requested(command)) ?? ""
    }
    private func offer(_ command: DetailCommand, key: String) {
        if state.readError != nil || (key == "play" && unavailablePlay) || (key == "watched" && state.empty) { return }
        let waiting = guarded
        let accepted = state.offer(command, model: model)
        if DetailFeedback.requestIsUrgent(newlyAccepted: accepted && waiting && (focused == key || accessibilityFocused == key), requested: state.requested(command), available: true) {
            let message: String
            switch command { case .play(let resume): message = resume ? "Play requested" : "Start requested"; case .watched: message = "Watched change requested"; case .favorite: message = "Favorite change requested" }
            state.cue = AccessibilityCue(message: message)
        }
    }
    @ViewBuilder private var recoveryPanel: some View {
        if state.recoveryVisible {
            VStack(alignment: .leading, spacing: 8) {
                Button {
                    guard !state.reading else { return }
                    state.refresh(id: id, model: model, deliberate: true)
                } label: { Text(state.reading || state.fresh ? "Retrying…" : "Retry").minimumTouchTarget() }
                .buttonStyle(ProminentActionStyle()).focused($focused, equals: "detailRetry").accessibilityFocused($accessibilityFocused, equals: "detailRetry")
                .id("detailRetry").accessibilityIdentifier("detailRetry")
                .accessibilityValue(state.reading || state.fresh ? "Loading item. Please wait." : "Retry. Loading failed. \(state.readFailure?.message ?? state.readError ?? "")")
                if let reason = state.readFailure?.message ?? state.readError {
                    #if os(tvOS)
                    Text(state.reading ? "Previous attempt: \(reason)" : reason).foregroundStyle(.orange).selectableReading()
                    #else
                    Text(reason).foregroundStyle(.orange).selectableReading()
                    #endif
                }
            }.padding(12).frame(maxWidth: 440, alignment: .leading).background(Theme.surface).padding(8)
        } else if let failure = state.writeFailure {
            VStack(alignment: .leading, spacing: 8) {
                Text("Change failed. \(failure.message)").foregroundStyle(.orange).selectableReading().accessibilityIdentifier("detailWriteError")
                Button { state.retryWrite(model: model) } label: { Text("Retry change").minimumTouchTarget() }.secondaryActionStyle().accessibilityIdentifier("detailWriteRetry")
            }.padding(12).frame(maxWidth: 440, alignment: .leading).background(Theme.surface).padding(8)
        }
    }
    private func primaryLabel(_ item: MediaItem) -> String {
        if !state.fresh || model.playbackSaving { return "Play / Resume when ready" }
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
