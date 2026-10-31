// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

private enum DetailCommand: Equatable {
    case play(Bool), watched(Bool), favorite(Bool)
}

@MainActor private final class DetailState: ObservableObject {
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
    private var failedWrite: DetailCommand?
    private var epoch = UUID()
    private var readTask: Task<Void, Never>?
    private var drainTask: Task<Void, Never>?
    private var queuedAt: Date?
    private var departed = false
    private var deadline: Date?
    private var publishedRefreshID: UUID?
    private var activeCommand: DetailCommand?
    func appear() { departed = false }
    func leave() { departed = true; epoch = UUID(); readTask?.cancel(); pending = []; queuedAt = nil }
    func refresh(id: String, model: AppModel, deliberate: Bool = false, restart: Bool = false) {
        guard let account = model.account else { return }
        if reading && !deliberate && !restart { return }
        if deliberate { deadline = Date().addingTimeInterval(30) }
        else if deadline == nil { deadline = Date().addingTimeInterval(30) }
        guard let deadline else { return }
        readTask?.cancel(); epoch = UUID(); let version = epoch; let publication = model.refreshID
        reading = true; fresh = false; readError = nil
        readTask = Task {
            defer { if epoch == version { reading = false } }
            do {
                let result = try await withThrowingTaskGroup(of: DetailPublication.self) { group in
                    group.addTask {
                        let item = try await model.client.item(account: account, id: id)
                        var seasons: [MediaItem] = []; var episodes: [MediaItem] = []; var target: MediaItem?
                        var season = await self.selectedSeason
                        if item.type == "Series" {
                            let next = try await model.client.nextUp(account: account, seriesID: item.id)
                            seasons = try await model.client.seasons(account: account, seriesID: item.id)
                            if !seasons.contains(where: { $0.id == season }) { season = next.first?.seasonId ?? seasons.first?.id }
                            if let season { episodes = try await model.client.episodes(account: account, seriesID: item.id, seasonID: season) }
                            target = next.first(where: { $0.isPlayable }) ?? episodes.first(where: { !$0.isWatched && $0.isPlayable }) ?? episodes.first(where: \.isPlayable)
                            // A series can begin in a different season from the visible shelf.
                            if target == nil { for candidate in seasons where candidate.id != season { let other = try await model.client.episodes(account: account, seriesID: item.id, seasonID: candidate.id); if let found = other.first(where: { !$0.isWatched && $0.isPlayable }) ?? other.first(where: \.isPlayable) { target = found; break } } }
                        } else if item.type == "Season", let series = item.seriesId {
                            episodes = try await model.client.episodes(account: account, seriesID: series, seasonID: item.id)
                            target = episodes.first(where: { !$0.isWatched && $0.isPlayable }) ?? episodes.first(where: \.isPlayable)
                        } else if item.isPlayable { target = item }
                        return DetailPublication(item: item, seasons: seasons, episodes: episodes, season: season, target: target)
                    }
                    group.addTask { let wait = max(0, deadline.timeIntervalSinceNow); try await Task.sleep(for: .seconds(wait)); throw DetailTimeout() }
                    defer { group.cancelAll() }
                    guard let first = try await group.next() else { throw CancellationError() }; return first
                }
                guard version == epoch, !departed, model.account?.localKey == account.localKey else { return }
                item = result.item; if header == nil { header = result.item }
                seasons = result.seasons; episodes = result.episodes; selectedSeason = result.season; target = result.target
                fresh = true; readError = nil; self.deadline = nil; publishedRefreshID = publication
                if deliberate { focusRequest = result.target != nil ? "play" : "favorite" }
                drain(model: model)
            } catch is CancellationError { }
            catch {
                guard version == epoch, !departed else { return }
                model.handle(error, account: account); readError = error.localizedDescription; fresh = false; focusRequest = "detailRetry"
                pending = []; queuedAt = nil
            }
        }
    }
    func chooseSeason(_ id: String, model: AppModel) { selectedSeason = id; if let item { deadline = nil; refresh(id: item.id, model: model, restart: true) } }
    func offer(_ command: DetailCommand, model: AppModel) {
        guard !departed else { return }
        if pending.contains(command) || activeCommand == command { return }
        guard pending.count < 32 else { notice = "Too many waiting actions. Try again after the item finishes loading."; return }
        if queuedAt == nil { queuedAt = Date() }; pending.append(command); notice = "Action will run when the item and progress are ready."
        drain(model: model)
    }
    func drain(model: AppModel) {
        guard drainTask == nil, fresh, publishedRefreshID == model.refreshID, !reading, !model.playbackSaving, !departed, let account = model.account else { return }
        guard !pending.isEmpty else { return }
        if let queuedAt, Date().timeIntervalSince(queuedAt) > 30 { pending = []; self.queuedAt = nil; notice = "Waiting action expired. Choose it again."; return }
        let version = epoch
        drainTask = Task {
            defer { drainTask = nil; activeCommand = nil; writing = false; if !pending.isEmpty { drain(model: model) } }
            while !pending.isEmpty, version == epoch, !departed, fresh, !model.playbackSaving {
                let command = pending.removeFirst(); activeCommand = command; writing = true
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
                    writeError = nil; failedWrite = nil; notice = nil
                    if case .play = command { } else { await model.loadHome(); deadline = nil; refresh(id: item?.id ?? "", model: model, restart: true); return }
                } catch {
                    guard version == epoch, !departed else { return }; model.handle(error, account: account); writeError = error.localizedDescription; failedWrite = command; pending = []; return
                }
            }
            queuedAt = nil
        }
    }
    func retryWrite(model: AppModel) { if let failedWrite { writeError = nil; offer(failedWrite, model: model) } }
}
private struct DetailPublication: Sendable { let item: MediaItem; let seasons: [MediaItem]; let episodes: [MediaItem]; let season: String?; let target: MediaItem? }
private struct DetailTimeout: LocalizedError { var errorDescription: String? { "The item took too long to load. Choose Retry to try again." } }

struct DetailView: View {
    @EnvironmentObject private var model: AppModel
    let id: String
    var open: (MediaItem) -> Void
    @StateObject private var state = DetailState()
    @FocusState private var focused: String?
    @State private var opener = "play"
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
                            ScrollView(.horizontal) { HStack(spacing: 18) { ForEach(state.seasons) { season in Button { opener = "season-\(season.id)"; state.chooseSeason(season.id, model: model) } label: { Text(season.name).minimumTouchTarget() }.buttonStyle(.bordered).tint(state.selectedSeason == season.id ? Theme.accent : Theme.muted).accessibilityValue(state.selectedSeason == season.id ? "Selected" : "").accessibilityIdentifier("season-\(season.id)").focused($focused, equals: "season-\(season.id)").id("season-\(season.id)") } }.padding(.vertical, 8) }
                        }
                        if ["Series", "Season"].contains(item.type) {
                            if state.episodes.isEmpty && state.fresh { Text("No episodes are available.").foregroundStyle(Theme.muted) }
                            else { CatalogShelf(title: "Episodes", items: state.episodes, wide: true, horizontalInset: 0, open: open) }
                        }
                        if !item.people.isEmpty {
                            Text("Cast and crew").font(.title2.bold()).accessibilityAddTraits(.isHeader)
                            ForEach(item.people) { person in Button { var target = MediaItem(id: person.id, name: person.name, type: "Person"); target.overview = nil; open(target) } label: { VStack(alignment: .leading) { Text(person.name); Text(person.role ?? person.type ?? "").font(.caption).foregroundStyle(Theme.muted) }.minimumTouchTarget() }.buttonStyle(.bordered).accessibilityLabel([person.name, person.role ?? person.type].compactMap { $0 }.joined(separator: ", ")) }
                        }
                        if header != item, let overview = item.overview, overview != header.overview { Text("More details").font(.title2).accessibilityAddTraits(.isHeader); Text(overview) }
                    } else if state.reading { ProgressView("Loading item…") }
                    if let error = state.readError { Text(error).foregroundStyle(.orange); Button { state.refresh(id: id, model: model, deliberate: true) } label: { Text(state.reading ? "Retrying…" : "Retry").minimumTouchTarget() }.disabled(state.reading).buttonStyle(ProminentActionStyle()).focused($focused, equals: "detailRetry").id("detailRetry").accessibilityIdentifier("detailRetry") }
                    if let error = state.writeError { Text(error).foregroundStyle(.orange); Button { state.retryWrite(model: model) } label: { Text("Retry change").minimumTouchTarget() }.buttonStyle(.bordered) }
                    if let warning = model.playbackSaveError { Text(warning).foregroundStyle(.orange); Button { Task { await model.retryPlaybackSave() } } label: { Text(model.playbackSaving ? "Saving progress…" : "Retry saving progress").minimumTouchTarget() }.disabled(model.playbackSaving).buttonStyle(.bordered).accessibilityIdentifier("retryProgress") }
                    if let notice = state.notice { Text(notice).foregroundStyle(Theme.muted) }
                }.padding(Theme.gutter)
            }
            .overlay(alignment: .top) {
                if state.header != nil && (model.playbackSaving || state.reading) {
                    Text(model.playbackSaving ? (state.fresh ? "Item loaded. Saving progress…" : "Saving progress…") : state.item?.type == "Series" || state.item?.type == "Season" ? "Loading episodes…" : "Loading item…")
                        .padding(12).background(Theme.surface.opacity(0.95), in: Capsule()).padding(.top, 8).accessibilityIdentifier("detailStatus").allowsHitTesting(false)
                }
            }
            .background(Theme.background.ignoresSafeArea()).navigationTitle(state.header?.name ?? "Details")
            .announceStatus(state.readError ?? state.writeError ?? model.playbackSaveError ?? state.notice)
            .task(id: id) { state.appear(); state.refresh(id: id, model: model) }
            .onAppear { state.appear(); state.refresh(id: id, model: model); focused = opener }
            .onDisappear { state.leave() }
            .onChange(of: model.refreshID) { _, _ in state.refresh(id: id, model: model) }
            .onChange(of: model.playbackSaving) { _, saving in if !saving { state.refresh(id: id, model: model, restart: true) } }
            .onChange(of: state.reading) { _, value in if !value { state.drain(model: model) } }
            .onChange(of: state.focusRequest) { _, request in if let request { if request == "detailRetry" || focused == "detailRetry" || focused == nil { proxy.scrollTo(request, anchor: .center); focused = request }; state.focusRequest = nil } }
            .onChange(of: focused) { _, focus in if let focus { opener = focus; withAnimation { proxy.scrollTo(focus, anchor: .center) } } }
            #if os(tvOS)
            .onPlayPauseCommand {
                guard let item = state.item,
                      item.isPlayable || ["Series", "Season"].contains(item.type),
                      state.readError == nil else { return }
                opener = "play"
                state.offer(.play(true), model: model)
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
                Button { opener = "play"; state.offer(.play(true), model: model) } label: { Label(primaryLabel(item), systemImage: "play.fill").minimumTouchTarget() }.buttonStyle(ProminentActionStyle()).disabled(state.fresh && state.target == nil).focused($focused, equals: "play").id("play").accessibilityIdentifier("play")
            }
            Button { opener = "watched"; state.offer(.watched(!item.isWatched), model: model) } label: { Label(item.isWatched ? "Mark unwatched" : "Mark watched", systemImage: item.isWatched ? "checkmark.circle.fill" : "checkmark.circle").minimumTouchTarget() }.buttonStyle(.bordered).disabled(item.isEmptyContainer).focused($focused, equals: "watched").id("watched").accessibilityValue(item.isWatched ? "Watched" : "Unwatched").accessibilityIdentifier("watched")
            Button { opener = "favorite"; state.offer(.favorite(!item.userData.isFavorite), model: model) } label: { Label(item.userData.isFavorite ? "Remove favorite" : "Add favorite", systemImage: item.userData.isFavorite ? "heart.fill" : "heart").minimumTouchTarget() }.buttonStyle(.bordered).focused($focused, equals: "favorite").id("favorite").accessibilityValue(item.userData.isFavorite ? "Favorite" : "Not favorite").accessibilityIdentifier("favorite")
            if item.isPlayable { Button { opener = "startOver"; state.offer(.play(false), model: model) } label: { Text("Play from start").minimumTouchTarget() }.buttonStyle(.bordered).focused($focused, equals: "startOver").id("startOver").accessibilityIdentifier("startOver") }
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
