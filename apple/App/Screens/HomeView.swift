// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Loads something from the viewer's server, keeping the last good value while refreshing.
@MainActor final class Loader<Value>: ObservableObject {
    enum Phase { case loading, loaded(Value), failed(String) }
    @Published private(set) var phase: Phase = .loading
    var value: Value? { if case .loaded(let v) = phase { return v }; return nil }

    func load(app: AppModel, _ work: @escaping (Account, JellyfinClient) async throws -> Value) async {
        guard let account = app.account, let client = app.client else { return }
        if value == nil { phase = .loading }
        do {
            let result = try await work(account, client)
            phase = .loaded(result)
        } catch is CancellationError {
        } catch ClientError.invalidCredentials {
            app.expire(account)
        } catch {
            if value == nil { phase = .failed(error.localizedDescription) }
        }
    }
}

struct HomeView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var home = Loader<HomeContent>()

    var body: some View {
        Group {
            switch home.phase {
            case .loading: ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            case .failed(let message):
                #if os(iOS)
                if app.downloads.hasReady(app.account) {
                    // Saved films come first when the server is out of reach: Watch downloads, then Retry.
                    VStack(spacing: 12) {
                        MessageView(symbol: "wifi.exclamationmark", title: "Could not reach the server. Your downloads still play.",
                                    actionTitle: "Watch downloads") { app.showingDownloads = true }
                            .fixedSize(horizontal: false, vertical: true)
                        Button("Try again") { Task { await reload() } }.secondaryAction()
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load your home screen", message: message, actionTitle: "Try again") { Task { await reload() } }
                }
                #else
                MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load your home screen", message: message, actionTitle: "Try again") { Task { await reload() } }
                #endif
            case .loaded(let content):
                if content.rows.isEmpty {
                    MessageView(symbol: "film.stack", title: "Nothing here yet", message: "Your libraries are empty, or this account can't see any of them.")
                } else {
                    rows(content)
                }
            }
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle("Home")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { AccountMenu() } }
        #endif
        .task(id: app.catalogRevision) { await reload() }
        .refreshable { await reload() }
    }

    private func reload() async {
        await home.load(app: app) { account, client in
            await app.loadHomeLayout(account: account, client: client)
            let content = try await client.homeContent(account: account)
            let duplicates = Catalog.homeDuplicateIds(rows: content.rows)
            let libraries = content.rows.filter(\.library).flatMap(\.items)
            var names: [String: String] = [:]
            for item in content.rows.filter({ !$0.library }).flatMap(\.items) where duplicates.contains(item.id) && names[item.id] == nil {
                if let ancestors = try? await client.ancestors(account: account, itemID: item.id),
                   let library = ancestors.first(where: { ancestor in libraries.contains { $0.id == ancestor.id } }) {
                    names[item.id] = library.name
                }
            }
            let rows = content.rows.map { row in
                HomeRow(id: row.id, title: row.title, items: row.items, library: row.library, wide: row.wide,
                        captions: row.captions, libraryNames: names.filter { id, _ in row.items.contains { $0.id == id } })
            }
            return HomeContent(rows: rows, warnings: content.warnings)
        }
    }

    private func rows(_ content: HomeContent) -> some View {
        let named = content.rows.reduce(into: [String: String]()) { $0.merge($1.libraryNames) { first, _ in first } }
        let rows = Catalog.customizedHome(rows: content.rows, preferences: app.homeLayout).map { row in
            HomeRow(id: row.id, title: row.title, items: row.items, library: row.library, wide: row.wide,
                    captions: row.captions, libraryNames: named)
        }
        return ScrollView(.vertical) {
            LazyVStack(alignment: .leading, spacing: Theme.shelfSpacing) {
                if let featured = featured(content) { HomeHero(item: featured) }
                ForEach(rows) { row in
                    if row.library {
                        LibraryShelf(libraries: row.items)
                    } else {
                        Shelf(title: row.title, items: row.items, wide: row.wide, captions: row.captions, libraryNames: row.libraryNames, isHome: true)
                    }
                }
            }
            .padding(.bottom, 40)
        }
        #if os(tvOS)
        .ignoresSafeArea(edges: .top)
        #endif
    }

    /// The hero shows what the viewer is most likely to want: the thing they were watching, else the newest arrival.
    private func featured(_ content: HomeContent) -> MediaItem? {
        content.rows.first { $0.id == "resume" }?.items.first
            ?? content.rows.first { $0.id == "nextup" }?.items.first
            ?? content.rows.first { $0.id.hasPrefix("latest:") }?.items.first
    }
}

/// Large backdrop with the featured title and its main actions.
struct HomeHero: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    @State private var full: MediaItem?

    var body: some View {
        let shown = full ?? item
        ZStack(alignment: .bottomLeading) {
            ArtworkImage(url: backdropURL(shown), placeholderSymbol: shown.symbol)
                .frame(maxWidth: .infinity)
                .frame(height: platformValue(tv: 720, touch: 420))
                .overlay(
                    LinearGradient(stops: [.init(color: .clear, location: 0.25), .init(color: Theme.background.opacity(0.7), location: 0.65), .init(color: Theme.background, location: 1)], startPoint: .top, endPoint: .bottom)
                )
                .overlay(
                    LinearGradient(colors: [Theme.background.opacity(0.85), .clear], startPoint: .leading, endPoint: .center)
                )
                .accessibilityHidden(true)
                #if os(tvOS)
                .ignoresSafeArea(edges: .horizontal) // full-bleed art past the TV safe area
                #endif
            VStack(alignment: .leading, spacing: platformValue(tv: 18, touch: 10)) {
                Text(eyebrow(shown)).font(.caption.weight(.bold)).textCase(.uppercase).foregroundStyle(Theme.accent).tracking(1.2)
                TitleLogo(item: shown.type == "Episode" ? nil : shown, fallback: shown.type == "Episode" ? (shown.seriesName ?? shown.name) : shown.name)
                if shown.type == "Episode" {
                    Text([Format.episode(shown).nilIfBlank, shown.name].compactMap { $0 }.joined(separator: " · ")).font(.headline)
                }
                if let overview = shown.overview?.nilIfBlank {
                    Text(overview).font(platformValue(tv: .callout, touch: .subheadline)).foregroundStyle(Theme.muted).lineLimit(platformValue(tv: 3, touch: 2)).frame(maxWidth: platformValue(tv: 900, touch: 560), alignment: .leading)
                }
                HStack(spacing: 14) {
                    if CatalogPresentation.offersPlayback(shown) { Button { app.play(shown) } label: {
                        Label(shown.userData.playbackPositionTicks > 0 ? "Resume" : "Play", systemImage: "play.fill")
                    }
                    .primaryAction() }
                    NavigationLink(value: shown.route) { Label("Details", systemImage: "info.circle") }
                        .secondaryAction()
                }
                .padding(.top, 6)
                #if os(tvOS)
                .focusSection()
                #endif
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.bottom, platformValue(tv: 40, touch: 20))
            .frame(maxWidth: .infinity, alignment: .leading)
            .heroScrim()
        }
        .task(id: item.id) {
            guard let account = app.account, let client = app.client else { return }
            full = try? await client.item(account: account, id: item.id)
        }
    }

    private func eyebrow(_ item: MediaItem) -> String {
        if item.userData.playbackPositionTicks > 0 { return Format.remaining(item).map { "Continue watching · \($0)" } ?? "Continue watching" }
        if item.type == "Episode" { return "Up next" }
        return "New"
    }

    private func backdropURL(_ item: MediaItem) -> URL? {
        guard let account = app.account, let client = app.client else { return nil }
        return client.artworkURL(account: account, item: item, kind: .backdrop, width: platformValue(tv: 1920, touch: 1280))
            ?? client.artworkURL(account: account, item: item, kind: .wide, width: 1280)
    }
}

/// A title's logo art when the server has one, otherwise its name in large type.
struct TitleLogo: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem?
    let fallback: String
    var body: some View {
        if let item, let account = app.account, let url = app.client?.artworkURL(account: account, item: item, kind: .logo, width: 800) {
            ArtworkImage(url: url, contentMode: .fit, fallbackText: fallback)
                .frame(maxWidth: platformValue(tv: 640, touch: 300), maxHeight: platformValue(tv: 200, touch: 96), alignment: .leading)
                .accessibilityLabel(fallback)
        } else {
            Text(fallback).font(platformValue(tv: .system(size: 64, weight: .bold), touch: .largeTitle.weight(.bold))).lineLimit(2).minimumScaleFactor(0.7)
        }
    }
}

/// The viewer's libraries as wide tiles.
struct LibraryShelf: View {
    let libraries: [MediaItem]
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: "Libraries").padding(.horizontal, Theme.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: Theme.cardSpacing) {
                    ForEach(libraries) { library in LibraryTile(library: library) }
                }
                .padding(.horizontal, Theme.gutter)
                #if os(tvOS)
                .padding(.vertical, 24)
                #endif
            }
            .scrollClipDisabled()
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }
}

struct LibraryTile: View {
    @State private var hasArt = false
    @EnvironmentObject private var app: AppModel
    let library: MediaItem
    /// Fixed width in a shelf; nil fills the grid column.
    var width: CGFloat? = platformValue(tv: 420, touch: 220)

    var body: some View {
        #if os(tvOS)
        NavigationLink(value: Route.library(library)) { tile }.buttonStyle(.card)
        #else
        NavigationLink(value: Route.library(library)) { tile.clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous)) }.buttonStyle(.plain)
        #endif
    }

    private var tile: some View {
        tileBase
            .overlay {
                ArtworkImage(url: app.account.flatMap { app.client?.artworkURL(account: $0, item: library, kind: .wide, width: Int((width ?? 400) * 2)) }, placeholderSymbol: library.symbol, loaded: $hasArt)
            }
            // The dark scrim keeps a white name readable over a picture; on the plain placeholder it only muddied
            // the tile (grey into black under the light theme), so the name then uses the text colour instead.
            .overlay { if hasArt { LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .center, endPoint: .bottom) } }
            .overlay(alignment: .bottomLeading) {
                Text(library.name).font(.headline).foregroundStyle(hasArt ? Color.white : Color.primary).padding(platformValue(tv: 20, touch: 12))
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(library.name)
            .accessibilityAddTraits(.isButton)
    }

    /// Fixed 16:9 size in a shelf; in a grid, the column width at 16:9.
    @ViewBuilder private var tileBase: some View {
        if let width {
            Color.clear.frame(width: width, height: width * 9 / 16)
        } else {
            Color.clear.aspectRatio(16 / 9, contentMode: .fit).frame(maxWidth: .infinity)
        }
    }
}

/// Account switcher in the iPhone/iPad toolbar.
struct AccountMenu: View {
    @EnvironmentObject private var app: AppModel
    @State private var signOutAccount: Account?
    var body: some View {
        Menu {
            ForEach(app.accounts, id: \.key) { account in
                Button { app.select(account) } label: {
                    if account.key == app.account?.key { Label("\(account.username) · \(account.server.name)", systemImage: "checkmark") }
                    else if !account.isSignedIn { Text("\(account.username) · Signed out · Sign in again") }
                    else { Text("\(account.username) · \(account.server.name)") }
                }
            }
            Divider()
            Button { app.addingAccount = true } label: { Label("Add account", systemImage: "person.badge.plus") }
            if let current = app.account {
                Button(role: .destructive) { signOutAccount = current } label: { Label("Sign out of \(current.username)", systemImage: "rectangle.portrait.and.arrow.right") }
            }
        } label: {
            Image(systemName: "person.crop.circle").font(.title3)
                .accessibilityLabel("Accounts")
        }
        .confirmSignOut(account: $signOutAccount)
    }
}
