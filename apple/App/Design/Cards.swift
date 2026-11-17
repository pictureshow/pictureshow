// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Where a card leads when chosen.
enum Route: Hashable {
    case item(MediaItem)
    case library(MediaItem)
    case person(id: String, name: String)
    /// PHOTO-01: a photo opens full screen among its folder's photos, in the grid's order.
    case photo(MediaItem, sortBy: String, descending: Bool)
    /// LIVE-01: the Live TV destination (Channels, Guide, Recordings, Schedules), never an item grid.
    case liveTV
    /// MUS-01, MUS-02: a music library or an album, artist, genre, playlist or song page.
    case music(MusicRoute)
}

extension MediaItem: @retroactive Hashable {
    public func hash(into hasher: inout Hasher) { hasher.combine(id) }
}

extension MediaItem {
    var route: Route {
        if isLiveTvLibrary { return .liveTV }
        if let music = musicRoute { return .music(music) }
        if type == "CollectionFolder" || type == "UserView" || (collectionType != nil && type != "BoxSet") { return .library(self) }
        if type == "Person" { return .person(id: id, name: name) }
        if type == "Photo" { return .photo(self, sortBy: "SortName", descending: false) }
        if type == "Folder" || type == "PhotoAlbum" { return .library(self) }
        return .item(self)
    }

    /// The route from a library grid: a photo keeps the grid's sort (a random grid falls back to name order).
    func route(in query: LibraryQuery) -> Route {
        guard type == "Photo", query.sort != .random else { return route }
        return .photo(self, sortBy: query.sort == .name ? "SortName" : "\(query.sort.rawValue),SortName", descending: query.descending)
    }

    /// Second line under a card title.
    var cardSubtitle: String? {
        switch type {
        case "Episode":
            let label = Format.episode(self)
            return [seriesName, label.nilIfBlank].compactMap { $0 }.joined(separator: " · ").nilIfBlank
        case "Series":
            return productionYear.map(String.init)
        case "Season":
            return seriesName
        default:
            return productionYear.map(String.init)
        }
    }

    var symbol: String {
        switch type {
        case "Series", "Season", "Episode": return "tv"
        case "BoxSet": return "square.stack"
        case "Person": return "person"
        case "CollectionFolder", "UserView": return collectionType == "tvshows" ? "tv" : collectionType == "music" ? "music.note" : "film"
        default: return "film"
        }
    }
}

/// Small overlay showing watched state or how many episodes are left.
struct WatchBadge: View {
    let item: MediaItem
    var body: some View {
        if let unplayed = item.userData.unplayedItemCount, unplayed > 0, ["Series", "Season", "BoxSet"].contains(item.type) {
            Text("\(unplayed)")
                .font(.caption.weight(.bold)).monospacedDigit()
                .padding(.horizontal, 7).padding(.vertical, 3)
                .foregroundStyle(Theme.onAccent)
                .background(Capsule().fill(Theme.accent))
                .accessibilityLabel("\(unplayed) unwatched")
        } else if item.isWatched {
            Image(systemName: "checkmark")
                .font(.caption.weight(.bold))
                .foregroundStyle(Theme.onAccent)
                .frame(width: 24, height: 24)
                .background(Circle().fill(Theme.accent))
                .accessibilityLabel("Watched")
        }
    }
}

/// A poster card: 2:3 artwork with title and year beneath.
struct PosterCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var width: CGFloat = Theme.posterWidth
    var showsText = true
    var homeSubtitle: String?
    var isHome = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            artwork
            if showsText {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.name).font(.subheadline.weight(.semibold)).lineLimit(1)
                    if let subtitle = isHome ? homeSubtitle : item.cardSubtitle { Text(subtitle).font(.caption).foregroundStyle(Theme.muted).lineLimit(isHome ? 2 : 1) }
                }
                .frame(width: width, alignment: .leading)
            }
        }
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var artwork: some View {
        let image = ArtworkImage(url: url, placeholderSymbol: item.symbol)
            .frame(width: width, height: width * 1.5)
            .overlay(alignment: .topTrailing) { WatchBadge(item: item).padding(8) }
            .overlay(alignment: .bottom) {
                if item.progress > 0, item.isResumable { ProgressStrip(value: item.progress).padding(8) }
            }
        #if os(tvOS)
        image
        #else
        image.clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).strokeBorder(.white.opacity(0.06)))
        #endif
    }

    private var url: URL? {
        guard let account = app.account, let client = app.client else { return nil }
        return client.artworkURL(account: account, item: item, kind: .poster, width: Int(width * 3))
    }
}

/// A 16:9 card for Continue watching, Next up and episodes.
struct WideCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var width: CGFloat = Theme.wideWidth
    /// Episodes inside their own season don't need the series name repeated.
    var compactTitle = false
    var homeSubtitle: String?
    var isHome = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            artwork
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
                if let subtitle { Text(subtitle).font(.caption).foregroundStyle(Theme.muted).lineLimit(isHome ? 2 : 1) }
            }
            .frame(width: width, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }

    private var title: String {
        if !isHome, item.type == "Episode", !compactTitle, let series = item.seriesName { return series }
        return item.name
    }

    private var subtitle: String? {
        if isHome { return homeSubtitle }
        if item.type == "Episode" {
            let label = Format.episode(item)
            let detail = compactTitle ? [label.nilIfBlank, Format.runtime(item.runTimeTicks)] : [label.nilIfBlank, item.name]
            return detail.compactMap { $0 }.joined(separator: " · ").nilIfBlank
        }
        return Format.remaining(item) ?? item.productionYear.map(String.init)
    }

    @ViewBuilder private var artwork: some View {
        let image = ArtworkImage(url: url, placeholderSymbol: item.symbol)
            .frame(width: width, height: width * 9 / 16)
            .overlay(alignment: .topTrailing) { WatchBadge(item: item).padding(8) }
            .overlay(alignment: .bottom) {
                if item.progress > 0, item.isResumable { ProgressStrip(value: item.progress).padding(10) }
            }
        #if os(tvOS)
        image
        #else
        image.clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).strokeBorder(.white.opacity(0.06)))
        #endif
    }

    private var url: URL? {
        guard let account = app.account, let client = app.client else { return nil }
        return client.artworkURL(account: account, item: item, kind: .wide, width: Int(width * 3))
    }
}

/// A focusable/tappable card that opens a route. On tvOS the card style supplies the lift, shadow and parallax
/// that Apple TV viewers expect; the title sits outside the button so it doesn't scale with the art.
struct CardLink<Label: View, Below: View>: View {
    let route: Route
    @ViewBuilder var label: () -> Label
    @ViewBuilder var below: () -> Below

    var body: some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            NavigationLink(value: route) { label() }
                .buttonStyle(.card)
            below()
        }
        #else
        NavigationLink(value: route) {
            VStack(alignment: .leading, spacing: 8) { label(); below() }
        }
        .buttonStyle(.plain)
        #endif
    }
}

/// Card for any item, choosing poster or wide art.
struct ItemCard: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var wide = false
    var width: CGFloat?
    var compactTitle = false
    var homeSubtitle: String?
    var isHome = false
    var route: Route?

    var body: some View {
        #if os(tvOS)
        CardLink(route: route ?? item.route) {
            if wide { WideArtworkOnly(item: item, width: width ?? Theme.wideWidth) } else { PosterArtworkOnly(item: item, width: width ?? Theme.posterWidth) }
        } below: {
            CardCaption(item: item, wide: wide, width: width ?? (wide ? Theme.wideWidth : Theme.posterWidth), compactTitle: compactTitle, homeSubtitle: homeSubtitle, isHome: isHome)
        }
        .contextMenu { ItemActions(item: item) }
        #else
        NavigationLink(value: route ?? item.route) {
            if wide { WideCard(item: item, width: width ?? Theme.wideWidth, compactTitle: compactTitle, homeSubtitle: homeSubtitle, isHome: isHome) } else { PosterCard(item: item, width: width ?? Theme.posterWidth, homeSubtitle: homeSubtitle, isHome: isHome) }
        }
        .buttonStyle(.plain)
        .contextMenu { ItemActions(item: item) }
        #endif
    }
}

/// tvOS: card art without captions, so the caption can live outside the focus platter.
struct PosterArtworkOnly: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    let width: CGFloat
    var body: some View {
        ArtworkImage(url: app.account.flatMap { a in app.client?.artworkURL(account: a, item: item, kind: .poster, width: Int(width * 2)) }, placeholderSymbol: item.symbol)
            .frame(width: width, height: width * 1.5)
            .overlay(alignment: .topTrailing) { WatchBadge(item: item).padding(10) }
            .overlay(alignment: .bottom) { if item.progress > 0, item.isResumable { ProgressStrip(value: item.progress).padding(12) } }
    }
}

struct WideArtworkOnly: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    let width: CGFloat
    var body: some View {
        ArtworkImage(url: app.account.flatMap { a in app.client?.artworkURL(account: a, item: item, kind: .wide, width: Int(width * 2)) }, placeholderSymbol: item.symbol)
            .frame(width: width, height: width * 9 / 16)
            .overlay(alignment: .topTrailing) { WatchBadge(item: item).padding(10) }
            .overlay(alignment: .bottom) { if item.progress > 0, item.isResumable { ProgressStrip(value: item.progress).padding(14) } }
    }
}

struct CardCaption: View {
    let item: MediaItem
    let wide: Bool
    let width: CGFloat
    var compactTitle = false
    var homeSubtitle: String?
    var isHome = false
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption.weight(.semibold)).lineLimit(1)
            if let subtitle { Text(subtitle).font(.caption2).foregroundStyle(Theme.muted).lineLimit(isHome ? 2 : 1) }
        }
        .frame(width: width, alignment: .leading)
    }
    private var title: String { (wide && item.type == "Episode" && !compactTitle && !isHome) ? (item.seriesName ?? item.name) : item.name }
    private var subtitle: String? {
        if isHome { return homeSubtitle }
        if wide && item.type == "Episode" {
            let label = Format.episode(item)
            return (compactTitle ? [label.nilIfBlank, Format.runtime(item.runTimeTicks)] : [label.nilIfBlank, item.name]).compactMap { $0 }.joined(separator: " · ").nilIfBlank
        }
        return wide ? (Format.remaining(item) ?? item.cardSubtitle) : item.cardSubtitle
    }
}

/// Long-press / context actions shared by every card.
struct ItemActions: View {
    @EnvironmentObject private var app: AppModel
    let item: MediaItem
    var body: some View {
        if CatalogPresentation.offersPlayback(item) {
            Button { app.play(item) } label: { Label(item.userData.playbackPositionTicks > 0 ? "Resume" : "Play", systemImage: "play.fill") }
            if CatalogPresentation.offersRestart(item) {
                Button { app.play(item, fromStart: true) } label: { Label("Play from beginning", systemImage: "backward.end.fill") }
            }
        }
        Button { Task { await toggleWatched() } } label: {
            Label(item.isWatched ? "Mark as unwatched" : "Mark as watched", systemImage: item.isWatched ? "eye.slash" : "checkmark.circle")
        }
        Button { Task { await toggleFavorite() } } label: {
            Label(item.userData.isFavorite ? "Remove from favorites" : "Add to favorites", systemImage: item.userData.isFavorite ? "heart.slash" : "heart")
        }
    }

    private func toggleWatched() async {
        guard let account = app.account, let client = app.client else { return }
        _ = try? await client.played(account: account, itemID: item.id, value: !item.isWatched)
        app.catalogChanged()
    }

    private func toggleFavorite() async {
        guard let account = app.account, let client = app.client else { return }
        _ = try? await client.favorite(account: account, itemID: item.id, value: !item.userData.isFavorite)
        app.catalogChanged()
    }
}

/// A titled horizontal row of cards.
struct Shelf: View {
    let title: String
    let items: [MediaItem]
    var wide = false
    var compactTitle = false
    var captions: [String: String] = [:]
    var libraryNames: [String: String] = [:]
    var isHome = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: title).padding(.horizontal, Theme.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                // Plain HStack: inside a lazy vertical stack, a LazyHStack measured 0 tall before it had
                // realised any card, so on Apple TV whole rows (Next up, Libraries) never appeared.
                HStack(alignment: .top, spacing: Theme.cardSpacing) {
                    ForEach(items) { item in ItemCard(item: item, wide: wide, compactTitle: compactTitle, homeSubtitle: Catalog.homeCardSubtitle(item: item, caption: captions[item.id], library: libraryNames[item.id]), isHome: isHome) }
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
