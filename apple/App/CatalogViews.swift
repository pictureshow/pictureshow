// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    var open: (MediaItem) -> Void
    @FocusState private var focus: String?
    @State private var lastFocus: String?
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 32) {
                if model.loading { ProgressView("Loading your library…") }
                if let error = model.homeError {
                    Text(error).foregroundStyle(.orange)
                    Button("Retry") { Task { await model.loadHome() } }
                }
                if !model.loading && model.rows.isEmpty && model.homeError == nil { Text("Your server has no videos yet.").foregroundStyle(Theme.muted) }
                ForEach(model.rows) { row in
                    VStack(alignment: .leading, spacing: 16) {
                        Text(row.title).modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader).padding(.horizontal, Theme.gutter)
                        ScrollView(.horizontal) {
                            LazyHStack(alignment: .top, spacing: 20) {
                                ForEach(row.items) { item in
                                    let key = "\(row.id):\(item.id)"
                                    Button {
                                        lastFocus = key
                                        open(item)
                                    } label: { CatalogCard(item: item, wide: row.wide || row.library) }
                                    .buttonStyle(PosterButtonStyle())
                                    .focused($focus, equals: key)
                                    .accessibilityLabel(item.accessibleTitle)
                                    .accessibilityValue(item.progress > 0 ? "\(Int(item.progress * 100)) percent watched" : "")
                                    .accessibilityIdentifier("item-\(item.id)")
                                }
                            }.padding(.horizontal, Theme.gutter).padding(.vertical, 14)
                        }
                        .scrollClipDisabled()
                        .televisionFocusSection()
                    }
                }
            }.padding(.vertical, 24)
        }
        .background(Theme.background.ignoresSafeArea())
        .navigationTitle("Pictureshow")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Sign out", systemImage: "person.crop.circle.badge.xmark") { Task { await model.signOut() } }.accessibilityIdentifier("signOut")
            }
        }
        .onAppear { focus = lastFocus }
        .onChange(of: model.rows.map(\.id)) { _, _ in
            if focus == nil, let row = model.rows.first, let item = row.items.first {
                focus = "\(row.id):\(item.id)"
            }
        }
    }
}

struct CatalogCard: View {
    let item: MediaItem
    var wide = false
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Artwork(item: item, wide: wide)
                .frame(width: wide ? Theme.wideCardWidth : Theme.cardWidth, height: wide ? Theme.wideCardWidth * 9 / 16 : Theme.cardWidth * 1.5)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .bottom) { if item.progress > 0 { ProgressView(value: item.progress).tint(Theme.accent).padding(.horizontal, 8).padding(.bottom, 8) } }
            Text(item.name).modifier(TokenFont(DesignTokens.labelSize, weight: .medium)).lineLimit(2).multilineTextAlignment(.leading)
            if item.type == "Episode" { Text([item.seriesName, item.episodeLabel].compactMap { $0 }.joined(separator: " · ")).modifier(TokenFont(DesignTokens.captionSize)).foregroundStyle(Theme.muted).lineLimit(1) }
        }
        .frame(width: wide ? Theme.wideCardWidth : Theme.cardWidth, alignment: .leading)
        .accessibilityElement(children: .ignore)
    }
}

struct PosterButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        PosterButton(configuration: configuration)
    }
    private struct PosterButton: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        var body: some View {
            configuration.label.padding(5).contentShape(Rectangle())
                .background(focused ? Theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                .overlay { RoundedRectangle(cornerRadius: 16).stroke(focused ? Theme.accent : .clear, lineWidth: 3) }
                .scaleEffect(configuration.isPressed ? 0.97 : focused ? 1.035 : 1)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}

struct Artwork: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    var wide = false
    @State private var image: UIImage?
    var body: some View {
        ZStack {
            Theme.surface
            if let image { Image(uiImage: image).resizable().scaledToFill() }
            else { Image(systemName: item.type == "Series" ? "tv" : item.isFolder ? "square.stack" : "film").font(.largeTitle).foregroundStyle(Theme.muted) }
        }
        .clipped().allowsHitTesting(false).accessibilityHidden(true)
        .task(id: item.id) {
            guard let account = model.account, let url = model.client.imageURL(account: account, item: item, maxWidth: 720, wide: wide) else { return }
            image = await ArtworkCache.shared.image(url: url, account: account, client: model.client)
        }
    }
}

actor ArtworkCache {
    static let shared = ArtworkCache()
    private struct Key: Hashable { let server: URL; let user: String; let url: URL }
    private var cache: [Key: UIImage] = [:]
    func image(url: URL, account: Account, client: JellyfinClient) async -> UIImage? {
        let key = Key(server: account.server.url, user: account.userId, url: url)
        if let image = cache[key] { return image }
        do {
            let data = try await client.imageData(account: account, url: url)
            guard let image = UIImage(data: data) else { return nil }
            if cache.count > 100 { cache.removeAll() }
            cache[key] = image
            return image
        } catch { return nil }
    }
}

struct DetailView: View {
    @EnvironmentObject private var model: AppModel
    let id: String
    var open: (MediaItem) -> Void
    @State private var item: MediaItem?
    @State private var children: [MediaItem] = []
    @State private var error: String?
    @State private var loading = true
    @State private var audio: Int?
    @State private var subtitle: Int? = -1
    @State private var selectedSeason: String?
    @State private var episodes: [MediaItem] = []
    @State private var childLoading = false
    @FocusState private var focused: String?
    @State private var returnFocus = "play"
    @State private var playTask: Task<Void, Never>?
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if loading { ProgressView("Loading details…") }
                    if let error { Text(error).foregroundStyle(.orange); Button("Retry") { Task { await load() } } }
                    if let item {
                        hero(item)
                        if item.type == "Series" {
                            Text("Seasons").modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader)
                            ScrollView(.horizontal) {
                                HStack(spacing: 18) {
                                    ForEach(children) { season in
                                        Button(season.name) {
                                            selectedSeason = season.id; returnFocus = "season-\(season.id)"
                                            Task { await loadEpisodes(item, seasonID: season.id) }
                                        }
                                        .buttonStyle(.bordered).tint(selectedSeason == season.id ? Theme.accent : Theme.muted)
                                        .accessibilityValue(selectedSeason == season.id ? "Selected" : "")
                                        .accessibilityIdentifier("season-\(season.id)").focused($focused, equals: "season-\(season.id)")
                                    }
                                }.padding(.vertical, 8)
                            }
                            if childLoading { ProgressView("Loading episodes…") }
                            itemList(episodes, title: "Episodes")
                        } else if item.isFolder || item.type == "Season" { itemList(children, title: item.type == "Season" ? "Episodes" : "Titles") }
                    }
                }
                .padding(Theme.gutter)
            }
            .background(Theme.background.ignoresSafeArea())
            .task(id: id) { await load() }
            .onAppear { focused = returnFocus }
            .onDisappear { playTask?.cancel() }
            .onChange(of: model.refreshID) { _, _ in
                Task { await load(refresh: true); proxy.scrollTo(returnFocus, anchor: .center); focused = returnFocus }
            }
        }
        .navigationTitle(item?.name ?? "Details")
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
    }
    private func hero(_ item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 20) {
            Artwork(item: item, wide: true).frame(maxWidth: .infinity).frame(height: heroHeight).clipShape(RoundedRectangle(cornerRadius: 18))
            Text(item.name).modifier(TokenFont(DesignTokens.displaySize, weight: .bold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("detailTitle")
            Text(metadata(item)).foregroundStyle(Theme.muted)
            if let overview = item.overview, !overview.isEmpty { Text(overview).modifier(TokenFont(DesignTokens.bodySize)).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true) }
            if ["Movie", "Episode", "Video"].contains(item.type) {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 16) {
                        Button(item.userData.playbackPositionTicks > 0 ? Catalog.resumeLabel(item.userData.playbackPositionTicks) : "Play", systemImage: "play.fill") {
                            returnFocus = "play"
                            playTask = Task { await model.play(item, resume: true, audio: audio, subtitle: subtitle) }
                        }.buttonStyle(.borderedProminent).disabled(model.preparing).focused($focused, equals: "play").id("play").accessibilityIdentifier("play")
                        if item.userData.playbackPositionTicks > 0 {
                            Button("Start over") { returnFocus = "startOver"; playTask = Task { await model.play(item, resume: false, audio: audio, subtitle: subtitle) } }
                                .buttonStyle(.bordered).disabled(model.preparing).focused($focused, equals: "startOver").id("startOver").accessibilityIdentifier("startOver")
                        }
                        if model.preparing { ProgressView("Preparing…") }
                    }
                    let streams = item.mediaSources.first?.mediaStreams ?? []
                    if !streams.filter({ $0.type == "Audio" }).isEmpty {
                        Picker("Audio", selection: $audio) {
                            Text("Server default").tag(Optional<Int>.none)
                            ForEach(streams.filter { $0.type == "Audio" }) { stream in Text(stream.label).tag(Optional(stream.index)) }
                        }.accessibilityIdentifier("audioChoice")
                    }
                    Picker("Subtitles", selection: $subtitle) {
                        Text("Off").tag(Optional(-1))
                        ForEach(streams.filter { $0.type == "Subtitle" }) { stream in Text(stream.label).tag(Optional(stream.index)) }
                    }.accessibilityIdentifier("subtitleChoice")
                }
            }
        }
    }
    private var heroHeight: CGFloat {
        #if os(tvOS)
        300
        #else
        220
        #endif
    }
    private func metadata(_ item: MediaItem) -> String {
        [item.type == "Episode" ? item.episodeLabel : nil, item.productionYear.map(String.init), item.officialRating, Catalog.runtimeLabel(item.runTimeTicks), item.genres.first].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · ")
    }
    private func itemList(_ items: [MediaItem], title: String) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader)
            if items.isEmpty && !loading && !childLoading { Text("No \(title.lowercased()) available.").foregroundStyle(Theme.muted) }
            ForEach(items) { child in
                Button { returnFocus = "child-\(child.id)"; open(child) } label: {
                    HStack(spacing: 18) {
                        Artwork(item: child, wide: true).frame(width: 120, height: 70).clipShape(RoundedRectangle(cornerRadius: 8))
                        VStack(alignment: .leading, spacing: 6) {
                            Text(child.name).modifier(TokenFont(DesignTokens.labelSize, weight: .medium))
                            if child.type == "Episode" { Text(child.episodeLabel).modifier(TokenFont(DesignTokens.captionSize)).foregroundStyle(Theme.muted) }
                            if child.progress > 0 { ProgressView(value: child.progress) }
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                    }.padding(12).background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
                }.buttonStyle(PosterButtonStyle()).accessibilityLabel(child.accessibleTitle)
                    .accessibilityIdentifier("item-\(child.id)").focused($focused, equals: "child-\(child.id)").id("child-\(child.id)")
            }
        }
    }
    private func load(refresh: Bool = false) async {
        guard let account = model.account else { return }
        if !refresh { loading = true }; error = nil
        defer { loading = false }
        do {
            let loaded = try await model.client.item(account: account, id: id)
            item = loaded
            if loaded.type == "Series" {
                children = try await model.client.seasons(account: account, seriesID: loaded.id)
                if selectedSeason == nil { selectedSeason = children.first?.id }
                if let selectedSeason { await loadEpisodes(loaded, seasonID: selectedSeason) }
            } else if loaded.type == "Season", let series = loaded.seriesId {
                children = try await model.client.episodes(account: account, seriesID: series, seasonID: loaded.id)
            } else if loaded.isFolder { children = try await model.client.browse(account: account, parentID: loaded.id) }
            if loaded.type == "Series" && returnFocus == "play", let first = children.first { returnFocus = "season-\(first.id)" }
            else if loaded.isFolder && returnFocus == "play", let first = children.first { returnFocus = "child-\(first.id)" }
            focused = returnFocus
        } catch { self.error = error.localizedDescription }
    }
    private func loadEpisodes(_ item: MediaItem, seasonID: String) async {
        guard let account = model.account else { return }
        childLoading = true
        defer { childLoading = false }
        do { let result = try await model.client.episodes(account: account, seriesID: item.id, seasonID: seasonID); if selectedSeason == seasonID { episodes = result } }
        catch { self.error = error.localizedDescription }
    }
}

extension MediaItem {
    var accessibleTitle: String {
        [type == "Episode" ? seriesName : nil, type == "Episode" ? episodeLabel : nil, name, isWatched ? "Watched" : nil].compactMap { $0 }.joined(separator: ", ")
    }
}

extension View {
    @ViewBuilder func televisionFocusSection() -> some View {
        #if os(tvOS)
        focusSection()
        #else
        self
        #endif
    }
}
