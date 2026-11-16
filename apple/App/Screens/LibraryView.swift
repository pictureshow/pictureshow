// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// The Libraries tab: every library the viewer can see.
struct LibrariesView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var libraries = Loader<[MediaItem]>()

    var body: some View {
        Group {
            switch libraries.phase {
            case .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load your libraries", message: message, actionTitle: "Try again") { Task { await reload() } }
            case .loaded(let items):
                ScrollView {
                    SyncStayBanner(sync: app.syncPlay).padding(.top, 12)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: platformValue(tv: 420, touch: 300)), spacing: Theme.cardSpacing)], spacing: platformValue(tv: 56, touch: 16)) {
                        ForEach(items) { library in LibraryTile(library: library, width: platformValue(tv: 420, touch: nil)) }
                    }
                    .padding(Theme.gutter)
                }
            }
        }
        .pageBackground()
        .navigationTitle("Libraries")
        .task(id: app.catalogRevision) { await reload() }
    }

    private func reload() async { await libraries.load(app: app) { account, client in try await client.views(account: account) } }
}

/// A library's grid with sort and filter, paging in as the viewer scrolls.
@MainActor final class LibraryGridModel: ObservableObject {
    @Published var query: LibraryQuery
    @Published private(set) var items: [MediaItem] = []
    @Published private(set) var total: Int?
    @Published private(set) var loading = false
    @Published private(set) var error: String?
    @Published private(set) var genres: [String] = []
    private var generation = 0

    init(library: MediaItem) {
        query = LibraryQuery(parentID: library.id, collectionType: library.collectionType ?? (library.type == "BoxSet" ? "boxsetitems" : nil))
        if let saved = UserDefaults.standard.string(forKey: "librarySort.\(library.id)"), let sort = LibraryQuery.Sort(rawValue: saved) {
            query.sort = sort
            query.descending = UserDefaults.standard.bool(forKey: "librarySortDescending.\(library.id)")
        }
    }

    var hasMore: Bool { total.map { items.count < $0 } ?? true }

    func reset(app: AppModel) async {
        generation += 1
        items = []; total = nil; error = nil
        UserDefaults.standard.set(query.sort.rawValue, forKey: "librarySort.\(query.parentID)")
        UserDefaults.standard.set(query.descending, forKey: "librarySortDescending.\(query.parentID)")
        await loadMore(app: app)
    }

    func loadMore(app: AppModel) async {
        guard !loading, hasMore, let account = app.account, let client = app.client else { return }
        loading = true
        let mine = generation
        defer { if mine == generation { loading = false } }
        do {
            let page = try await client.libraryPage(account: account, query: query, startIndex: items.count, limit: 100)
            guard mine == generation else { return }
            items += page.items
            total = page.totalRecordCount
        } catch ClientError.invalidCredentials {
            app.expire(account)
        } catch is CancellationError {
        } catch {
            if mine == generation { self.error = error.localizedDescription; total = items.count }
        }
    }

    func loadGenres(app: AppModel) async {
        guard genres.isEmpty, let account = app.account, let client = app.client else { return }
        genres = (try? await client.genres(account: account, parentID: query.parentID)) ?? []
    }
}

struct LibraryView: View {
    @EnvironmentObject private var app: AppModel
    let library: MediaItem
    @StateObject private var model: LibraryGridModel

    init(library: MediaItem) {
        self.library = library
        _model = StateObject(wrappedValue: LibraryGridModel(library: library))
    }

    private var wide: Bool { ["homevideos", "musicvideos"].contains(library.collectionType ?? "") }

    var body: some View {
        ScrollView {
            #if os(tvOS)
            HStack(alignment: .firstTextBaseline) {
                Text(library.name).font(.title2.weight(.bold))
                if let total = model.total { Text("\(total) titles").font(.callout).foregroundStyle(Theme.muted) }
                Spacer()
                controls
            }
            .padding(.horizontal, Theme.gutter)
            .focusSection()
            #endif
            if !model.query.filters.isEmpty || !model.query.genres.isEmpty { activeFilters }
            LazyVGrid(columns: columns, alignment: .leading, spacing: platformValue(tv: 56, touch: 22)) {
                ForEach(model.items) { item in
                    gridCard(item)
                        .onAppear { if item.id == model.items.last?.id { Task { await model.loadMore(app: app) } } }
                }
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, platformValue(tv: 30, touch: 12))
            if model.loading { ProgressView().padding(30) }
            if model.items.isEmpty, !model.loading {
                if let error = model.error {
                    MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load this library", message: error, actionTitle: "Try again") { Task { await model.reset(app: app) } }
                } else if model.total == 0 {
                    MessageView(symbol: "line.3.horizontal.decrease.circle", title: "No matches", message: "Nothing in \(library.name) matches these filters.", actionTitle: "Clear filters") {
                        model.query.filters = []; model.query.genres = []
                    }
                }
            }
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(library.name)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) { controls }
        }
        #endif
        .task { if model.items.isEmpty { await model.reset(app: app) } }
        .onChange(of: model.query) { Task { await model.reset(app: app) } }
        .onChange(of: app.catalogRevision) { Task { await model.reset(app: app) } }
    }

    private var columns: [GridItem] {
        [GridItem(.adaptive(minimum: wide ? platformValue(tv: 380, touch: 220) : platformValue(tv: 248, touch: 108), maximum: wide ? 520 : platformValue(tv: 260, touch: 180)), spacing: platformValue(tv: 48, touch: 14), alignment: .top)]
    }

    @ViewBuilder private func gridCard(_ item: MediaItem) -> some View {
        #if os(tvOS)
        ItemCard(item: item, wide: wide, route: item.route(in: model.query))
        #else
        NavigationLink(value: item.route(in: model.query)) { GridPoster(item: item, wide: wide) }
            .buttonStyle(.plain)
            .contextMenu { ItemActions(item: item) }
        #endif
    }

    private var controls: some View {
        HStack(spacing: 18) {
            Menu {
                Picker("Sort by", selection: Binding(get: { model.query.sort }, set: { sort in
                    model.query.descending = sort.defaultDescending; model.query.sort = sort
                })) {
                    ForEach(LibraryQuery.Sort.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                if model.query.sort != .random {
                    Picker("Order", selection: $model.query.descending) {
                        Text(model.query.sort == .name ? "A to Z" : "Oldest first").tag(false)
                        Text(model.query.sort == .name ? "Z to A" : "Newest first").tag(true)
                    }
                }
            } label: {
                Label("Sort: \(model.query.sort.title)", systemImage: "arrow.up.arrow.down")
            }
            Menu {
                ForEach(LibraryQuery.Filter.allCases, id: \.self) { filter in
                    Toggle(filter.title, isOn: Binding(get: { model.query.filters.contains(filter) }, set: { on in
                        if on { model.query.filters.insert(filter) } else { model.query.filters.remove(filter) }
                    }))
                }
                if !model.genres.isEmpty {
                    Menu("Genres") {
                        ForEach(model.genres, id: \.self) { genre in
                            Toggle(genre, isOn: Binding(get: { model.query.genres.contains(genre) }, set: { on in
                                if on { model.query.genres.insert(genre) } else { model.query.genres.remove(genre) }
                            }))
                        }
                    }
                }
                if !model.query.filters.isEmpty || !model.query.genres.isEmpty {
                    Button("Clear filters", role: .destructive) { model.query.filters = []; model.query.genres = [] }
                }
            } label: {
                Label("Filter", systemImage: model.query.filters.isEmpty && model.query.genres.isEmpty ? "line.3.horizontal.decrease.circle" : "line.3.horizontal.decrease.circle.fill")
            }
            .task { await model.loadGenres(app: app) }
        }
    }

    private var activeFilters: some View {
        let names = LibraryQuery.Filter.allCases.filter(model.query.filters.contains).map(\.title) + model.query.genres.sorted()
        return Text("Showing: " + names.joined(separator: ", "))
            .font(.footnote).foregroundStyle(Theme.muted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Theme.gutter).padding(.top, 8)
    }
}

/// iPhone/iPad grid cell that fills its column width.
struct GridPoster: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var wide = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Color.clear
                .aspectRatio(wide ? 16 / 9 : 2 / 3, contentMode: .fit)
                .overlay {
                    ArtworkImage(url: app.account.flatMap { app.client?.artworkURL(account: $0, item: item, kind: wide ? .wide : .poster, width: 480) }, placeholderSymbol: item.symbol)
                }
                .overlay(alignment: .topTrailing) { WatchBadge(item: item).padding(6) }
                .overlay(alignment: .bottom) { if item.progress > 0, !item.isWatched { ProgressStrip(value: item.progress).padding(6) } }
                .clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
            Text(item.name).font(.footnote.weight(.semibold)).lineLimit(1)
            if let subtitle = item.cardSubtitle { Text(subtitle).font(.caption2).foregroundStyle(Theme.muted).lineLimit(1) }
        }
        .accessibilityElement(children: .combine)
    }
}
