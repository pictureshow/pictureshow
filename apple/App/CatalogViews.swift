// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var readLifecycle = DetailReadLifecycle()
    @State private var visible = false
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
        .onAppear {
            visible = true
            let refresh = readLifecycle.resumed()
            Task { if refresh { await model.loadHome() } else { await model.loadHomeIfNeeded() } }
        }
        .onDisappear { visible = false }
        .onChange(of: scenePhase) { _, phase in
            if visible && phase == .active { Task { await model.loadHome() } }
        }
        .onChange(of: model.catalogRevision) { _, _ in
            if visible { Task { await model.loadHomeIfNeeded() } }
        }
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
    var pageSectionHeading = false
    var open: (MediaItem) -> Void
    @FocusState private var focused: String?
    @State private var opener: String?
    @Environment(\.accessibilityVoiceOverEnabled) private var reader
    @AccessibilityFocusState private var readerFocused: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).modifier(CatalogShelfHeading(pageSection: pageSectionHeading)).accessibilityAddTraits(.isHeader).accessibilityFocused($readerFocused, equals: "shelfHeading").padding(.horizontal, horizontalInset)
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(alignment: .top, spacing: 20) {
                        ForEach(items) { item in
                            if boundaryVisible(for: item.id, start: true) { boundary(start: true, item: item) }
                            Button { opener = item.id; remember(item.id); open(item) } label: { CatalogCard(item: item, wide: wide) }
                                .buttonStyle(PosterButtonStyle()).focused($focused, equals: item.id).id(item.id)
                                .accessibilityFocused($readerFocused, equals: item.id)
                                .accessibilityLabel(item.accessibleTitle).accessibilityIdentifier("item-\(item.id)")
                                #if os(tvOS)
                                .onMoveCommand { direction in
                                    if direction == .left && item.id == items.first?.id { focused = item.id }
                                    else if direction == .right && item.id == items.last?.id { focused = item.id }
                                }
                                #endif
                                .accessibilityScrollAction { edge in
                                    #if os(tvOS)
                                    if edge == .leading && item.id == items.first?.id { readerFocused = "shelfStart" }
                                    else if edge == .trailing && item.id == items.last?.id { readerFocused = "shelfEnd" }
                                    else if edge == .top { readerFocused = "shelfHeading" }
                                    #endif
                                }
                            if boundaryVisible(for: item.id, start: false) { boundary(start: false, item: item) }
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
                    .onChange(of: readerFocused) { _, value in
                        if let value, items.contains(where: { $0.id == value }) { focused = value; proxy.scrollTo(value, anchor: .center) }
                    }
            }
        }
    }
    private func boundaryVisible(for id: String, start: Bool) -> Bool {
        #if os(tvOS)
        let boundary = start ? "shelfStart" : "shelfEnd"
        return reader && id == (start ? items.first?.id : items.last?.id) && (readerFocused == id || readerFocused == boundary)
        #else
        return false
        #endif
    }
    private func boundary(start: Bool, item: MediaItem) -> some View {
        Text("\(start ? "Start" : "End") of \(title)")
            .font(.caption).foregroundStyle(Theme.muted).fixedSize(horizontal: false, vertical: true)
            .frame(width: 90).accessibilityElement(children: .ignore)
            .accessibilityLabel("\(start ? "Start" : "End") of \(title)")
            .accessibilityFocused($readerFocused, equals: start ? "shelfStart" : "shelfEnd")
            .accessibilityIdentifier(start ? "shelfStart" : "shelfEnd")
            .accessibilityScrollAction { edge in
                if (start && edge == .trailing) || (!start && edge == .leading) { readerFocused = item.id }
                else if edge == .top { readerFocused = "shelfHeading" }
            }
    }
    private var memoryKey: String { "catalog-opener:" + (model.account?.localKey ?? "") + ":" + title }
    private func remember(_ id: String) { UserDefaults.standard.set(id, forKey: memoryKey) }
}

private struct CatalogShelfHeading: ViewModifier {
    let pageSection: Bool
    @ViewBuilder func body(content: Content) -> some View {
        #if os(tvOS)
        if pageSection { content.font(.title2.bold()) }
        else { content.modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)) }
        #else
        content.modifier(TokenFont(DesignTokens.titleSize, weight: .semibold))
        #endif
    }
}

#if os(tvOS)
/// Visible selection stays independent of native remote focus. The reserved
/// glyph slot keeps neighbouring labels still as the selected choice changes.
struct TelevisionChoiceLabel: View {
    let title: String
    let selected: Bool
    @Environment(\.isFocused) private var focused
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark").opacity(selected ? 1 : 0).accessibilityHidden(true)
            Text(title)
        }
        .fontWeight(.semibold)
        .foregroundStyle(focused || selected ? Theme.background : Color.primary)
    }
}
struct TelevisionChoiceStyle: ViewModifier {
    let selected: Bool
    func body(content: Content) -> some View {
        content.buttonStyle(.bordered).tint(selected ? Theme.accent : Color.gray)
    }
}
#endif

struct CatalogCard: View {
    let item: MediaItem
    var wide = false
    var fillsAvailableWidth = false
    @ScaledMetric private var fontScale: CGFloat = 1
    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            artwork.clipShape(RoundedRectangle(cornerRadius: 12))
                .overlay(alignment: .bottom) { if item.progress > 0 { ProgressView(value: item.progress).tint(Theme.accent).padding(8) } }
            Text(item.name).modifier(TokenFont(DesignTokens.labelSize, weight: .medium)).lineLimit(fontScale > CGFloat(GridNavigation.unboundedTitleFontScale) ? nil : 2).fixedSize(horizontal: false, vertical: true).multilineTextAlignment(.leading)
            Text(item.cardSubtitle).modifier(TokenFont(DesignTokens.captionSize)).foregroundStyle(Theme.muted).lineLimit(2)
            HStack { if item.isWatched { Label("Watched", systemImage: "checkmark.circle.fill") }; if item.userData.isFavorite { Image(systemName: "heart.fill") }; if let count = item.userData.unplayedItemCount, count > 0 { Text("\(count) unplayed") } }.font(.caption).foregroundStyle(Theme.accent)
        }
        .frame(width: fillsAvailableWidth ? nil : wide ? Theme.wideCardWidth : Theme.cardWidth, alignment: .leading)
        .frame(maxWidth: fillsAvailableWidth ? .infinity : nil, alignment: .leading)
        .accessibilityElement(children: .ignore)
    }
    @ViewBuilder private var artwork: some View {
        if fillsAvailableWidth {
            Artwork(item: item, wide: wide).aspectRatio(wide ? 16.0 / 9 : 2.0 / 3, contentMode: .fit)
        } else {
            Artwork(item: item, wide: wide).frame(width: wide ? Theme.wideCardWidth : Theme.cardWidth, height: wide ? Theme.wideCardWidth * 9 / 16 : Theme.cardWidth * 1.5)
        }
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
            posterLabel.contentShape(Rectangle())
                .background(focused ? Theme.surface : Color.clear, in: RoundedRectangle(cornerRadius: 16))
                .overlay { RoundedRectangle(cornerRadius: 16).stroke(focused ? Theme.accent : .clear, lineWidth: 3) }
                .scaleEffect(reader || reducedMotion ? 1 : configuration.isPressed ? 0.97 : focused ? 1.035 : 1)
                .animation(reducedMotion ? nil : .easeOut(duration: 0.15), value: focused)
        }
        @ViewBuilder private var posterLabel: some View {
            #if os(tvOS)
            configuration.label.padding(5)
            #else
            configuration.label
            #endif
        }
    }
}

// A single sizing layer: native bordered styles add padding outside a label's
// minimum height. These touch actions instead include their 48 pt floor here.
#if os(iOS)
struct CatalogTouchActionStyle: ButtonStyle {
    var prominent = false
    @Environment(\.isEnabled) private var enabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 16).padding(.vertical, 10)
            .frame(minWidth: 48, minHeight: 48)
            .foregroundStyle(prominent ? Theme.background : Theme.secondaryButtonText)
            .background(prominent ? Theme.accent : Theme.surface, in: Capsule())
            .contentShape(Capsule())
            .opacity(enabled ? (configuration.isPressed ? 0.7 : 1) : 0.45)
    }
}

/// Modern scroll observation stays live even when layout preferences are cached.
/// Callers retain their geometry-preference fallback on iOS 17.
struct NativeScrollOffset: ViewModifier {
    @Binding var offset: CGFloat
    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: CGFloat.self) { geometry in
                geometry.contentOffset.y + geometry.contentInsets.top
            } action: { _, value in offset = value }
        } else { content }
    }
}
#endif

extension View {
    @ViewBuilder func catalogActionStyle(prominent: Bool = false) -> some View {
        #if os(iOS)
        buttonStyle(CatalogTouchActionStyle(prominent: prominent))
        #else
        if prominent { buttonStyle(ProminentActionStyle()) }
        else { secondaryActionStyle() }
        #endif
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

/// Presentation adapter for the shared grid policy. It owns only the lifetime of a
/// native move; row/column and landing decisions remain in PictureshowCore.
@MainActor final class CatalogGridNavigation: ObservableObject {
    @Published private(set) var heldIndex: Int?
    @Published private(set) var retryIndex: Int?
    private(set) var heldID: String?
    private var heldGeneration: UUID?
    func move(index: Int, id: String, columns: Int, count: Int, down: Bool, loading: Bool, failed: Bool, generation: UUID) -> GridVerticalMove {
        if !down { cancel() }
        let result = GridNavigation.verticalMove(index: index, columns: columns, count: count, down: down, loadingMore: loading, pageFailed: failed)
        if result == .hold {
            if heldID != id || heldGeneration != generation { heldIndex = index; heldID = id; heldGeneration = generation }
        } else { cancelHold() }
        return result
    }
    func release(ids: [String], columns: Int, generation: UUID, focused: String?, failed: Bool) -> GridVerticalMove {
        guard let index = heldIndex, heldID == focused, heldGeneration == generation, ids.indices.contains(index), ids[index] == heldID else { cancelHold(); return .stay }
        cancelHold()
        return GridNavigation.holdRelease(index: index, columns: columns, count: ids.count, pageFailed: failed)
    }
    func beginRetry(index: Int?) { cancelHold(); retryIndex = index }
    func settleRetry(count: Int, columns: Int, failed: Bool) -> GridVerticalMove {
        guard let index = retryIndex else { return .stay }
        retryIndex = nil
        guard !failed, count > 0 else { return .stay }
        if case .card(let card) = GridNavigation.holdRelease(index: min(index, count - 1), columns: columns, count: count) { return .card(card) }
        return .card(min(index, count - 1))
    }
    func selectionChanged(_ id: String?) { if id != heldID { cancelHold() }; if id != "pageRetry" { retryIndex = nil } }
    func cancelHold() { heldIndex = nil; heldID = nil; heldGeneration = nil }
    func cancel() { cancelHold(); retryIndex = nil }
}

private struct CatalogHeadPosition: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

#if os(tvOS)
private struct CatalogCardHeights: PreferenceKey {
    static var defaultValue: [String: CGFloat] = [:]
    static func reduce(value: inout [String: CGFloat], nextValue: () -> [String: CGFloat]) {
        value.merge(nextValue(), uniquingKeysWith: { _, new in new })
    }
}
#endif

struct LibraryView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityVoiceOverEnabled) private var reader
    let id: String
    let name: String
    var person = false
    var open: (MediaItem) -> Void
    @StateObject private var page = PagedCatalog()
    @StateObject private var navigation = CatalogGridNavigation()
    @State private var sort = "SortName"
    @State private var descending = false
    @FocusState private var focused: String?
    @AccessibilityFocusState private var readerFocused: String?
    @State private var opener: String?
    @State private var headBottom: CGFloat = 0
    #if os(tvOS)
    @ScaledMetric(relativeTo: .body) private var minimumCardWidth: CGFloat = Theme.cardWidth
    @State private var cardHeights: [String: CGFloat] = [:]
    @State private var viewportHeight: CGFloat = 0
    #endif
    #if os(iOS)
    @State private var headerHeight: CGFloat = 0
    @State private var scrollOffset: CGFloat = 0
    #endif
    @State private var lastCardIndex: Int?
    @State private var retryPresented = false
    @State private var holdToken = UUID()
    @State private var landingTask: Task<Void, Never>?
    var body: some View {
        GeometryReader { geometry in
            let columns = max(1, Int((geometry.size.width - Theme.gutter * 2 + gridSpacing) / (gridMinimumWidth + posterInset + gridSpacing)))
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 24) {
                        header.background {
                            GeometryReader { frame in
                                Color.clear.preference(key: CatalogHeadPosition.self, value: frame.frame(in: .named("catalogScroll")).maxY)
                                    #if os(iOS)
                                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }
                                    #endif
                            }
                        }
                        if page.items.isEmpty && !page.loading && page.error == nil {
                            Text("No titles are available.").foregroundStyle(Theme.muted)
                            Button { reload() } label: { Text("Refresh").minimumTouchTarget() }
                        }
                        LazyVGrid(columns: Array(repeating: GridItem(.flexible(minimum: 0), spacing: gridSpacing, alignment: .top), count: columns), alignment: .leading, spacing: 28) {
                            ForEach(Array(page.items.enumerated()), id: \.element.id) { index, item in
                                gridCard(item, index: index, columns: columns, proxy: proxy)
                            }
                        }.televisionFocusSection()
                        if let error = page.error {
                            Text(error).foregroundStyle(DesignTokens.warning).fixedSize(horizontal: false, vertical: true).readingFocusable().accessibilityIdentifier("pageError")
                        }
                        if page.error != nil || retryPresented {
                            Button {
                                guard !page.loading else { return }
                                retryPresented = true
                                navigation.beginRetry(index: lastCardIndex ?? 0)
                                Task { await load() }
                            } label: { Text(page.loading ? "Retrying…" : "Retry") }
                            .catalogActionStyle().focused($focused, equals: "pageRetry").accessibilityFocused($readerFocused, equals: "pageRetry")
                            .accessibilityLabel(page.error.map { "Retry. \($0)" } ?? "Retry")
                            .accessibilityValue(page.loading ? "Loading titles. Please wait." : "")
                            .accessibilityIdentifier("pageRetry").id("pageRetry")
                        }
                        if page.loading { ProgressView("Loading titles…") }
                        if page.next < page.total && !page.loading && page.error == nil {
                            Button { Task { await load() } } label: { Text("Load more").minimumTouchTarget() }
                        }
                    }.padding(.horizontal, Theme.gutter).padding(.vertical, 32)
                }
                .coordinateSpace(name: "catalogScroll")
                .onPreferenceChange(CatalogHeadPosition.self) { headBottom = $0 }
                #if os(tvOS)
                .onAppear { viewportHeight = geometry.size.height }
                .onChange(of: geometry.size.height) { _, height in viewportHeight = height }
                .onPreferenceChange(CatalogCardHeights.self) { heights in
                    let previous = selection.flatMap { cardHeights[$0] }
                    cardHeights = heights
                    if let selection, let height = heights[selection], previous != height {
                        // A newly composed or resized cell must use its actual
                        // layout height, including after native text-size changes.
                        proxy.scrollTo(selection, anchor: landingAnchor(for: selection))
                    }
                }
                .overlay(alignment: .top) {
                    if headBottom < 0 {
                        Text(person ? name : "\(name) · \(GridNavigation.stripDetail(sortBy: sort, descending: descending))")
                            .font(.caption).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Theme.gutter).padding(.vertical, 12)
                            .background(Theme.background).allowsHitTesting(false)
                            .accessibilityIdentifier("librarySortStrip")
                            // Replace the inherited Text elements with none;
                            // this context repeats already readable controls.
                            .accessibilityRepresentation { EmptyView() }
                            .accessibilityHidden(true)
                    }
                }
                #else
                .modifier(NativeScrollOffset(offset: $scrollOffset))
                .safeAreaInset(edge: .top, spacing: 0) {
                    if headerScrolledAway {
                        Text(person ? name : "\(name) · \(GridNavigation.stripDetail(sortBy: sort, descending: descending))")
                            .font(.caption).lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, Theme.gutter).padding(.vertical, 10)
                            .background(Theme.background).allowsHitTesting(false)
                            .accessibilityIdentifier("librarySortStrip")
                            .accessibilityRepresentation { EmptyView() }
                            .accessibilityHidden(true)
                    }
                }
                .toolbarBackground(Theme.background, for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                #endif
                .background(Theme.background.ignoresSafeArea())
                #if os(tvOS)
                .navigationTitle(name).toolbar(.hidden, for: .navigationBar)
                .defaultFocus($focused, opener ?? page.items.first?.id)
                #else
                .navigationTitle("")
                #endif
                .task(id: id) { if page.items.isEmpty { await load(reset: true) } }
                .onChange(of: sort) { _, _ in reload() }
                .onChange(of: descending) { _, _ in reload() }
                .onAppear {
                    if opener == nil { opener = UserDefaults.standard.string(forKey: memoryKey) }
                    if let opener, page.items.contains(where: { $0.id == opener }) { land(opener, proxy: proxy) }
                }
                .onChange(of: page.items.map(\.id)) { _, ids in
                    if focused == nil, let opener, ids.contains(opener) { land(opener, proxy: proxy) }
                }
                .onChange(of: page.loading) { _, loading in
                    if !loading {
                        apply(navigation.release(ids: page.items.map(\.id), columns: columns, generation: page.generation, focused: selection, failed: page.error != nil), proxy: proxy)
                        let result = navigation.settleRetry(count: page.items.count, columns: columns, failed: page.error != nil)
                        if result != .stay { apply(result, proxy: proxy) }
                    }
                }
                .onChange(of: focused) { _, value in
                    if !reader { selectionChanged(value, proxy: proxy) }
                }
                .onChange(of: readerFocused) { _, value in
                    if reader { selectionChanged(value, proxy: proxy) }
                }
                .onChange(of: dynamicTypeSize) { _, _ in
                    navigation.cancel()
                    #if os(tvOS)
                    cardHeights = [:]
                    #endif
                    if let selection { proxy.scrollTo(selection, anchor: .top) }
                }
                .task(id: holdToken) {
                    guard navigation.heldIndex != nil else { return }
                    do { try await Task.sleep(for: .milliseconds(GridNavigation.holdCapMillis)) } catch { return }
                    guard !Task.isCancelled else { return }
                    apply(navigation.release(ids: page.items.map(\.id), columns: columns, generation: page.generation, focused: selection, failed: page.error != nil), proxy: proxy)
                }
                .onDisappear { navigation.cancel(); landingTask?.cancel() }
            }
            #if DEBUG && os(tvOS)
            .overlay {
                if ProcessInfo.processInfo.environment["PICTURESHOW_CATALOG_GEOMETRY_PROBE"] == "1" {
                    // An inert AX test rectangle for the actual layout viewport;
                    // native ScrollView AX frames can include system safe areas.
                    Color.clear.frame(width: geometry.size.width, height: geometry.size.height)
                        .allowsHitTesting(false).focusable(false)
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Catalog viewport test probe")
                        .accessibilityIdentifier("catalogViewport")
                }
            }
            #endif
        }
    }
    private var selection: String? { reader ? readerFocused : focused }
    private var gridMinimumWidth: CGFloat {
        #if os(tvOS)
        // Follow the same system metric as the token text. The explicit column
        // count sent to the core still matches the composed native grid exactly.
        minimumCardWidth
        #else
        Theme.cardWidth
        #endif
    }
    private func landingAnchor(for id: String) -> UnitPoint {
        #if os(tvOS)
        let focusScale: CGFloat = reader ? 1 : 1.035
        if let height = cardHeights[id], viewportHeight > 0, height * focusScale > viewportHeight { return .top }
        #endif
        return .center
    }
    private var posterInset: CGFloat {
        #if os(tvOS)
        10
        #else
        0
        #endif
    }
    #if os(iOS)
    private var headerScrolledAway: Bool {
        if #available(iOS 18.0, *) { return headerHeight > 0 && scrollOffset > headerHeight + 32 }
        return headBottom < 0
    }
    #endif
    private var gridSpacing: CGFloat {
        #if os(tvOS)
        22
        #else
        16
        #endif
    }
    private var header: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(name).modifier(TokenFont(DesignTokens.titleSize, weight: .semibold)).accessibilityAddTraits(.isHeader).accessibilityIdentifier("libraryHeading")
            Text("\(page.total) titles").foregroundStyle(Theme.muted).accessibilityIdentifier("libraryCount")
            if !person {
                #if os(tvOS)
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 16) { sortButtons }
                    VStack(alignment: .leading, spacing: 16) { sortButtons }
                }
                #else
                HStack(spacing: 8) {
                    Menu {
                        Picker("Sort by", selection: $sort) { Text("Name").tag("SortName"); Text("Date added").tag("DateCreated"); Text("Release date").tag("PremiereDate") }
                    } label: {
                        HStack(spacing: 6) {
                            Text(GridNavigation.stripDetail(sortBy: sort, descending: false).components(separatedBy: ",")[0])
                            Image(systemName: "chevron.down").font(.caption)
                        }.frame(maxWidth: .infinity)
                    }
                    .catalogActionStyle().accessibilityLabel("Sort by")
                    .accessibilityValue(GridNavigation.stripDetail(sortBy: sort, descending: false).components(separatedBy: ",")[0])
                    .accessibilityIdentifier("librarySort")
                    directionButton
                }
                #endif
            }
        }.id("gridHeader")
    }
    @ViewBuilder private var sortButtons: some View {
        ForEach(["SortName", "DateCreated", "PremiereDate"], id: \.self) { value in
            Button { navigation.cancel(); sort = value } label: {
                #if os(tvOS)
                TelevisionChoiceLabel(title: GridNavigation.stripDetail(sortBy: value, descending: false).components(separatedBy: ",")[0], selected: sort == value)
                #else
                Text(GridNavigation.stripDetail(sortBy: value, descending: false).components(separatedBy: ",")[0]).minimumTouchTarget()
                #endif
            }
                #if os(tvOS)
                .modifier(TelevisionChoiceStyle(selected: sort == value))
                #else
                .secondaryActionStyle()
                #endif
                .focused($focused, equals: "sort-\(value)").accessibilityFocused($readerFocused, equals: "sort-\(value)")
                .accessibilityAddTraits(sort == value ? [.isSelected] : [])
                .accessibilityIdentifier("sort-\(value)")
        }
        directionButton
    }
    private var directionButton: some View {
        Button { navigation.cancel(); descending.toggle() } label: {
            Label(descending ? "Descending" : "Ascending", systemImage: descending ? "arrow.down" : "arrow.up")
                #if os(iOS)
                .frame(maxWidth: .infinity)
                #endif
        }
            .catalogActionStyle().accessibilityIdentifier("sortDirection").focused($focused, equals: "sortDirection").accessibilityFocused($readerFocused, equals: "sortDirection")
    }
    private func gridCard(_ item: MediaItem, index: Int, columns: Int, proxy: ScrollViewProxy) -> some View {
        Button { opener = item.id; UserDefaults.standard.set(item.id, forKey: memoryKey); open(item) } label: { CatalogCard(item: item, fillsAvailableWidth: true) }
            .buttonStyle(PosterButtonStyle()).focused($focused, equals: item.id).accessibilityFocused($readerFocused, equals: item.id)
            .id(item.id).accessibilityLabel(item.accessibleTitle).accessibilityIdentifier("item-\(item.id)")
            .onAppear { if index >= page.items.count - 8, page.next < page.total, page.error == nil { Task { await load() } } }
            #if os(tvOS)
            .background {
                GeometryReader { geometry in
                    Color.clear.preference(key: CatalogCardHeights.self, value: [item.id: geometry.size.height])
                }
            }
            .onMoveCommand { direction in
                switch direction {
                case .up: move(index: index, columns: columns, down: false, proxy: proxy)
                case .down: move(index: index, columns: columns, down: true, proxy: proxy)
                default: navigation.cancel()
                }
            }
            #endif
            .accessibilityScrollAction { edge in
                #if os(tvOS)
                if edge == .top { move(index: index, columns: columns, down: false, proxy: proxy) }
                else if edge == .bottom { move(index: index, columns: columns, down: true, proxy: proxy) }
                else { navigation.cancel() }
                #endif
            }
    }
    private func move(index: Int, columns: Int, down: Bool, proxy: ScrollViewProxy) {
        landingTask?.cancel()
        let previous = navigation.heldIndex
        apply(navigation.move(index: index, id: page.items[index].id, columns: columns, count: page.items.count, down: down, loading: page.loading, failed: page.error != nil, generation: page.generation), proxy: proxy)
        if previous == nil && navigation.heldIndex != nil { holdToken = UUID() }
    }
    private func apply(_ move: GridVerticalMove, proxy: ScrollViewProxy) {
        switch move {
        case .card(let index): if page.items.indices.contains(index) { land(page.items[index].id, proxy: proxy) }
        case .above: proxy.scrollTo("gridHeader", anchor: .top); focused = "sort-\(sort)"; if reader { readerFocused = "sort-\(sort)" }
        case .pageRetry: retryPresented = true; land("pageRetry", proxy: proxy)
        case .hold, .stay: break
        }
    }
    private func land(_ id: String, proxy: ScrollViewProxy) {
        landingTask?.cancel()
        proxy.scrollTo(id, anchor: landingAnchor(for: id))
        // Lazy rows attach after scrolling. Keep the old Retry alive until a
        // real new card owns native selection, and let a newer key replace this move.
        landingTask = Task { @MainActor in
            do { try await Task.sleep(for: .milliseconds(32)) } catch { return }
            guard !Task.isCancelled else { return }
            focused = id
            if reader { readerFocused = id }
        }
    }
    private func selectionChanged(_ value: String?, proxy: ScrollViewProxy) {
        navigation.selectionChanged(value)
        if let index = page.items.firstIndex(where: { $0.id == value }) { lastCardIndex = index; retryPresented = false }
        if let value { proxy.scrollTo(value, anchor: landingAnchor(for: value)) }
    }
    private func reload() {
        navigation.cancel(); landingTask?.cancel(); opener = nil; retryPresented = false; focused = nil; readerFocused = nil
        Task { await load(reset: true) }
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
