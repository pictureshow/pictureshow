// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    var open: (MediaItem) -> Void
    var showSearch: (() -> Void)?
    var showSettings: (() -> Void)?
    @State private var lastShelf: String?
    private var shelfMemoryKey: String { "home-shelf:" + (model.account?.localKey ?? "") }
    private var preferredShelf: String? {
        let remembered = lastShelf ?? UserDefaults.standard.string(forKey: shelfMemoryKey)
        return model.rows.first(where: { $0.id == remembered && !$0.items.isEmpty })?.id
            ?? model.rows.first(where: { !$0.items.isEmpty })?.id
    }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 32) {
                if let account = model.account { Text("\(account.username) · \(account.server.name)").foregroundStyle(Theme.muted).padding(.horizontal, Theme.gutter) }
                if model.loading { ProgressView("Loading your library…").padding(.horizontal, Theme.gutter) }
                if let error = model.homeError { ErrorNotice(error: error) { Task { await model.loadHome() } }.padding(.horizontal, Theme.gutter) }
                if !model.loading && model.rows.isEmpty && model.homeError == nil { Text("Your server has no videos yet.").foregroundStyle(Theme.muted).padding(.horizontal, Theme.gutter) }
                ForEach(model.rows) { row in
                    CatalogShelf(title: row.title, items: row.items, wide: row.wide || row.library,
                                 initiallyFocused: row.id == preferredShelf, restoresFocus: row.id == preferredShelf) { item in
                        lastShelf = row.id
                        UserDefaults.standard.set(row.id, forKey: shelfMemoryKey)
                        open(item)
                    }
                }
                HStack { NavigationLink(value: AppRoute.accounts) { Text("Switch user").minimumTouchTarget() }.accessibilityIdentifier("openAccounts"); Button { Task { await model.signOut() } } label: { Text("Sign out").minimumTouchTarget() }.accessibilityIdentifier("signOut") }.secondaryActionStyle().padding(Theme.gutter)
            }.padding(.vertical, 24)
        }
        .background(Theme.background.ignoresSafeArea()).pictureshowNavigationTitle("Pictureshow")
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .primaryAction) {
                if let showSearch { Button(action: showSearch) { Label("Search", systemImage: "magnifyingglass").minimumTouchTarget() }.accessibilityIdentifier("openSearch") }
                else { NavigationLink(value: AppRoute.search) { Label("Search", systemImage: "magnifyingglass").minimumTouchTarget() }.accessibilityIdentifier("openSearch") }
                if let showSettings { Button(action: showSettings) { Label("Settings", systemImage: "gearshape").minimumTouchTarget() }.accessibilityIdentifier("openSettings") }
                else { NavigationLink(value: AppRoute.settings) { Label("Settings", systemImage: "gearshape").minimumTouchTarget() }.accessibilityIdentifier("openSettings") }
            }
        }
        .refreshable { await model.loadHome() }
    }
}

struct ErrorNotice: View {
    let error: String
    var retry: () -> Void
    var body: some View { VStack(alignment: .leading, spacing: 12) { Text(error).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true); Button(action: retry) { Text("Retry").minimumTouchTarget() }.secondaryActionStyle().accessibilityIdentifier("retry") } }
}

struct CatalogShelf: View {
    @EnvironmentObject private var model: AppModel
    let title: String
    let items: [MediaItem]
    var wide = false
    var horizontalInset: CGFloat = Theme.gutter
    var initiallyFocused = false
    var restoresFocus = true
    var open: (MediaItem) -> Void
    @FocusState private var focused: String?
    @State private var opener: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader).padding(.horizontal, horizontalInset)
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 20) {
                        ForEach(items) { item in
                            Button { opener = item.id; remember(item.id); open(item) } label: { CatalogCard(item: item, wide: wide) }
                                .buttonStyle(PosterButtonStyle()).focused($focused, equals: item.id).id(item.id)
                                .accessibilityLabel(item.accessibleTitle).accessibilityIdentifier("item-\(item.id)")
                        }
                    }.padding(.horizontal, horizontalInset).padding(.vertical, 14)
                }.scrollClipDisabled().televisionFocusSection()
                    .onAppear {
                        if opener == nil { opener = UserDefaults.standard.string(forKey: memoryKey) }
                        if restoresFocus, let opener, items.contains(where: { $0.id == opener }) { proxy.scrollTo(opener, anchor: .center); focused = opener }
                        #if os(tvOS)
                        if focused == nil && initiallyFocused { focused = items.first?.id }
                        #endif
                    }
                    .onChange(of: focused) { _, value in if let value { withAnimation { proxy.scrollTo(value, anchor: .center) } } }
            }
        }
    }
    private var memoryKey: String { "catalog-opener:" + (model.account?.localKey ?? "") + ":" + title }
    private func remember(_ id: String) { UserDefaults.standard.set(id, forKey: memoryKey) }
}

struct CatalogCard: View {
    let item: MediaItem
    var wide = false
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            Artwork(item: item, wide: wide).frame(width: wide ? Theme.wideCardWidth : Theme.cardWidth, height: wide ? Theme.wideCardWidth * 9 / 16 : Theme.cardWidth * 1.5).clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .bottom) { if item.progress > 0 { ProgressView(value: item.progress).tint(Theme.accent).padding(8) } }
            Text(item.name).modifier(TokenFont(DesignTokens.labelSize, weight: .medium)).lineLimit(2).multilineTextAlignment(.leading)
            Text(item.cardSubtitle).modifier(TokenFont(DesignTokens.captionSize)).foregroundStyle(Theme.muted).lineLimit(2)
            HStack { if item.isWatched { Label("Watched", systemImage: "checkmark.circle.fill") }; if item.userData.isFavorite { Image(systemName: "heart.fill") }; if let count = item.userData.unplayedItemCount, count > 0 { Text("\(count) unplayed") } }.font(.caption).foregroundStyle(Theme.accent)
        }.frame(width: wide ? Theme.wideCardWidth : Theme.cardWidth, alignment: .leading).accessibilityElement(children: .ignore)
    }
}

struct PosterButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { PosterButton(configuration: configuration) }
    private struct PosterButton: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        @Environment(\.accessibilityReduceMotion) private var reducedMotion
        @Environment(\.accessibilityVoiceOverEnabled) private var reader
        var body: some View {
            configuration.label.padding(5).contentShape(Rectangle())
                .background(focused ? Theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                .overlay { RoundedRectangle(cornerRadius: 16).stroke(focused ? Theme.accent : .clear, lineWidth: 3) }
                .scaleEffect(reader || reducedMotion ? 1 : configuration.isPressed ? 0.97 : focused ? 1.035 : 1)
                .animation(reducedMotion ? nil : .easeOut(duration: 0.15), value: focused)
        }
    }
}
struct Artwork: View {
    @EnvironmentObject private var model: AppModel
    let item: MediaItem
    var wide = false
    @State private var image: UIImage?
    var body: some View {
        // Artwork fills the proposed cell/hero bounds without contributing an image's
        // natural aspect width to a vertical ScrollView's content measurement.
        Theme.surface.overlay {
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


extension MediaItem {
    var isPlayable: Bool { ["Movie", "Episode", "Video"].contains(type) }
    var isCatalogContainer: Bool { ["CollectionFolder", "Folder", "BoxSet", "Playlist"].contains(type) || (isFolder && !["Series", "Season"].contains(type)) }
    var cardSubtitle: String { type == "Episode" ? [seriesName, episodeLabel].compactMap { $0 }.joined(separator: " · ") : productionYear.map(String.init) ?? "" }
    var accessibleTitle: String {
        [type == "Episode" ? seriesName : nil, type == "Episode" ? episodeLabel : nil, name, productionYear.map(String.init), isWatched ? "Watched" : nil, userData.isFavorite ? "Favorite" : nil, progress > 0 ? "\(Int(progress * 100)) percent watched" : nil, userData.unplayedItemCount.flatMap { $0 > 0 ? "\($0) unplayed" : nil }].compactMap { $0 }.joined(separator: ", ")
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

struct LibrariesView: View {
    @EnvironmentObject private var model: AppModel
    var open: (MediaItem) -> Void
    var body: some View {
        ScrollView { VStack(alignment: .leading) { if let row = model.rows.first(where: { $0.library }) { CatalogShelf(title: "Your libraries", items: row.items, wide: true, open: open) } else if model.loading { ProgressView("Loading libraries…") } else { Text("No libraries available."); Button { Task { await model.loadHome() } } label: { Text("Refresh").minimumTouchTarget() } } }.padding(.vertical, 24) }.pictureshowNavigationTitle("Libraries").background(Theme.background.ignoresSafeArea())
    }
}

@MainActor final class PagedCatalog: ObservableObject {
    @Published var items: [MediaItem] = []
    @Published var total = 0
    @Published var loading = false
    @Published var error: String?
    var generation = UUID()
    var next = 0
    func load(model: AppModel, parent: String, person: Bool = false, sort: String = "SortName", descending: Bool = false, reset: Bool = false) async {
        guard let account = model.account else { return }
        if reset { generation = UUID(); items = []; next = 0; total = 0; loading = false }
        guard !loading else { return }
        let request = generation; let start = next; loading = true; error = nil
        defer { if request == generation { loading = false } }
        do {
            let result: ItemPage
            if person { result = try await model.client.personItems(account: account, personID: parent, startIndex: start, limit: 60) }
            else { result = try await model.client.browsePage(account: account, parentID: parent, startIndex: start, limit: 60, sortBy: sort, descending: descending) }
            guard request == generation, model.account?.localKey == account.localKey else { return }
            var ids = Set(items.map(\.id)); items += result.items.filter { ids.insert($0.id).inserted }; total = result.totalRecordCount
            next = start + result.items.count
        } catch is CancellationError { } catch { guard request == generation else { return }; model.handle(error, account: account); self.error = error.localizedDescription }
    }
}

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel
    let id: String
    let name: String
    var person = false
    var open: (MediaItem) -> Void
    @StateObject private var page = PagedCatalog()
    @State private var sort = "SortName"
    @State private var descending = false
    @FocusState private var focused: String?
    @State private var opener: String?
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    if !person { HStack {
                        Picker("Sort by", selection: $sort) { Text("Title").tag("SortName"); Text("Date added").tag("DateCreated"); Text("Release date").tag("PremiereDate") }.accessibilityIdentifier("librarySort")
                        Button { descending.toggle() } label: { Label(descending ? "Descending" : "Ascending", systemImage: descending ? "arrow.down" : "arrow.up").minimumTouchTarget() }.secondaryActionStyle().accessibilityIdentifier("sortDirection")
                    } }
                    if let error = page.error { ErrorNotice(error: error) { Task { await load() } } }
                    if page.items.isEmpty && !page.loading && page.error == nil { Text("No titles are available.").foregroundStyle(Theme.muted); Button { Task { await load(reset: true) } } label: { Text("Refresh").minimumTouchTarget() } }
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.cardWidth + 12), spacing: 22, alignment: .top)], alignment: .leading, spacing: 28) {
                        ForEach(page.items) { item in
                            Button { opener = item.id; UserDefaults.standard.set(item.id, forKey: memoryKey); open(item) } label: { CatalogCard(item: item) }.buttonStyle(PosterButtonStyle()).focused($focused, equals: item.id).id(item.id).accessibilityLabel(item.accessibleTitle).accessibilityIdentifier("item-\(item.id)")
                                .onAppear { if item.id == page.items.suffix(8).first?.id, page.next < page.total { Task { await load() } } }
                        }
                    }.televisionFocusSection()
                    if page.loading { ProgressView("Loading titles…") }
                    if page.next < page.total && !page.loading { Button { Task { await load() } } label: { Text("Load more").minimumTouchTarget() } }
                }.padding(Theme.gutter)
            }
                #if os(tvOS)
                // A default preference lets the engine enter content on first use;
                // it does not override focus the viewer has deliberately moved.
                .defaultFocus($focused, opener ?? page.items.first?.id)
                #endif
                .background(Theme.background.ignoresSafeArea()).pictureshowNavigationTitle(name)
                .task(id: id) { if page.items.isEmpty { await load(reset: true) } }
                .onChange(of: sort) { _, _ in Task { await load(reset: true) } }
                .onChange(of: descending) { _, _ in Task { await load(reset: true) } }
                .onAppear { if opener == nil { opener = UserDefaults.standard.string(forKey: memoryKey) }; if let opener, page.items.contains(where: { $0.id == opener }) { proxy.scrollTo(opener, anchor: .center); focused = opener } }
                .onChange(of: page.items.map(\.id)) { _, ids in if focused == nil, let opener, ids.contains(opener) { proxy.scrollTo(opener, anchor: .center); focused = opener } }
                .onChange(of: focused) { _, value in if let value { withAnimation { proxy.scrollTo(value, anchor: .center) } } }
        }
    }
    private var memoryKey: String { "library-opener:" + (model.account?.localKey ?? "") + ":" + id }
    private func load(reset: Bool = false) async {
        await page.load(model: model, parent: id, person: person, sort: sort, descending: descending, reset: reset)
        // A cold restored detail may have originated beyond the first page. Locate that
        // card through ordinary paging, stopping on cancellation or a non-advancing page.
        while !Task.isCancelled, let opener, !page.items.contains(where: { $0.id == opener }), page.next < page.total, page.error == nil {
            let before = page.next
            await page.load(model: model, parent: id, person: person, sort: sort, descending: descending)
            if page.next <= before { break }
        }
    }
}
