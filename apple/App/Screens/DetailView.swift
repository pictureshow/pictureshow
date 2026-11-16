// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Everything the detail page shows, loaded together so the page appears whole.
struct DetailContent {
    var item: MediaItem
    /// For a series (or a season/episode opened on its series): its seasons.
    var seasons: [MediaItem] = []
    /// For a series: the next episode to watch.
    var nextUp: MediaItem?
    /// Children of a collection.
    var children: [MediaItem] = []
    var similar: [MediaItem] = []
}

struct DetailView: View {
    @EnvironmentObject private var app: AppModel
    let seed: MediaItem
    @StateObject private var content = Loader<DetailContent>()

    var body: some View {
        Group {
            if let value = content.value {
                DetailPage(content: value)
            } else if case .failed(let message) = content.phase {
                MessageView(symbol: "exclamationmark.triangle", title: "Couldn't open \(seed.name)", message: message, actionTitle: "Try again") { Task { await reload() } }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .pageBackground()
        #if os(iOS)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task(id: app.catalogRevision) { await reload() }
    }

    private func reload() async {
        let seed = seed
        await content.load(app: app) { account, client in
            let item = try await client.item(account: account, id: seed.id)
            var result = DetailContent(item: item)
            switch item.type {
            case "Series":
                async let seasons = client.seasons(account: account, seriesID: item.id)
                async let next = try? client.nextUp(account: account, seriesID: item.id)
                result.seasons = try await seasons
                result.nextUp = await next?.first
            case "Season":
                if let series = item.seriesId { result.seasons = (try? await client.seasons(account: account, seriesID: series)) ?? [item] }
                else { result.seasons = [item] }
            case "BoxSet":
                result.children = try await client.browsePage(account: account, parentID: item.id, limit: 200, sortBy: "PremiereDate,SortName").items
            default: break
            }
            if ["Movie", "Series"].contains(item.type) {
                result.similar = (try? await client.similar(account: account, itemID: item.id)) ?? []
            }
            return result
        }
    }
}

struct DetailPage: View {
    @EnvironmentObject private var app: AppModel
    let content: DetailContent
    private var item: MediaItem { content.item }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.shelfSpacing) {
                header
                if item.type == "Season" || item.type == "Series" {
                    SeasonBrowser(series: item, seasons: content.seasons, initialSeasonID: item.type == "Season" ? item.id : (content.nextUp?.seasonId))
                }
                if !content.children.isEmpty { Shelf(title: "In this collection", items: content.children) }
                if !item.people.isEmpty { CastShelf(people: item.people) }
                if !content.similar.isEmpty { Shelf(title: "More like this", items: content.similar) }
                if !item.mediaSources.isEmpty { MediaInfoSection(item: item) }
            }
            .padding(.bottom, 60)
        }
        #if os(tvOS)
        .ignoresSafeArea(edges: .top)
        #endif
    }

    private var playable: Bool { ["Movie", "Episode", "Video", "MusicVideo"].contains(item.type) }

    /// What the main button plays: the item itself, or for a series the next episode.
    private var playTarget: MediaItem? { playable ? item : content.nextUp }

    private var header: some View {
        ZStack(alignment: .bottomLeading) {
            ArtworkImage(url: backdrop, placeholderSymbol: item.symbol)
                .frame(maxWidth: .infinity)
                .frame(height: platformValue(tv: 820, touch: 360))
                .overlay(LinearGradient(stops: [.init(color: .clear, location: 0.2), .init(color: Theme.background.opacity(0.75), location: 0.62), .init(color: Theme.background, location: 1)], startPoint: .top, endPoint: .bottom))
                .overlay(LinearGradient(colors: [Theme.background.opacity(0.9), .clear], startPoint: .leading, endPoint: .center))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: platformValue(tv: 18, touch: 12)) {
                if item.type == "Episode", let series = item.seriesName {
                    Text(series).font(.headline).foregroundStyle(Theme.accent)
                }
                TitleLogo(item: item.type == "Episode" ? nil : item, fallback: item.name)
                metadataLine
                if let overview = item.overview?.nilIfBlank {
                    Text(overview)
                        .font(platformValue(tv: .callout, touch: .subheadline))
                        .foregroundStyle(.secondary)
                        .lineLimit(platformValue(tv: 4, touch: 6))
                        .frame(maxWidth: platformValue(tv: 1100, touch: 640), alignment: .leading)
                }
                actions.padding(.top, 6)
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.bottom, 12)
        }
    }

    private var backdrop: URL? {
        guard let account = app.account, let client = app.client else { return nil }
        return client.artworkURL(account: account, item: item, kind: .backdrop, width: platformValue(tv: 1920, touch: 1280))
            ?? client.artworkURL(account: account, item: item, kind: .wide, width: 1280)
    }

    private var metadataLine: some View {
        let parts: [String] = [
            item.type == "Episode" ? Format.episode(item).nilIfBlank : nil,
            item.productionYear.map(String.init),
            item.officialRating,
            Format.runtime(item.runTimeTicks),
            item.communityRating.map { String(format: "★ %.1f", $0) },
            item.genres.prefix(3).joined(separator: ", ").nilIfBlank,
        ].compactMap { $0 }
        return HStack(spacing: 10) {
            Text(parts.joined(separator: "  ·  ")).font(.subheadline).foregroundStyle(Theme.muted)
            if item.isWatched { Label("Watched", systemImage: "checkmark.circle.fill").font(.subheadline).foregroundStyle(Theme.accent) }
        }
    }

    private var actions: some View {
        HStack(spacing: 14) {
            if let target = playTarget {
                Button { app.play(target) } label: {
                    Label(playLabel(target), systemImage: "play.fill")
                }
                .primaryAction()
                if playable, item.userData.playbackPositionTicks > 0 {
                    Button { app.play(item, fromStart: true) } label: { Label("From the beginning", systemImage: "backward.end.fill") }
                        .secondaryAction()
                }
            }
            Button { Task { await toggleWatched() } } label: {
                Label(item.isWatched ? "Watched" : "Mark watched", systemImage: item.isWatched ? "checkmark.circle.fill" : "checkmark.circle")
            }
            .secondaryAction()
            .accessibilityLabel(item.isWatched ? "Mark as unwatched" : "Mark as watched")
            Button { Task { await toggleFavorite() } } label: {
                Image(systemName: item.userData.isFavorite ? "heart.fill" : "heart")
            }
            .secondaryAction()
            .accessibilityLabel(item.userData.isFavorite ? "Remove from favorites" : "Add to favorites")
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func playLabel(_ target: MediaItem) -> String {
        let resume = target.userData.playbackPositionTicks > 0
        if target.id != item.id {
            let label = Format.episode(target)
            return (resume ? "Resume " : "Play ") + (label.isEmpty ? target.name : label)
        }
        if resume, let left = Format.remaining(target) { return "Resume · \(left)" }
        return resume ? "Resume" : "Play"
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

/// Season picker and that season's episodes.
struct SeasonBrowser: View {
    @EnvironmentObject private var app: AppModel
    let series: MediaItem
    let seasons: [MediaItem]
    let initialSeasonID: String?
    @State private var selected: String?
    @State private var episodes: [MediaItem] = []
    @State private var loading = false

    var body: some View {
        if seasons.isEmpty { EmptyView() } else {
            VStack(alignment: .leading, spacing: 16) {
                if seasons.count > 1 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) {
                            ForEach(seasons) { season in
                                Button { selected = season.id } label: {
                                    Text(season.name).font(.callout.weight(.semibold))
                                        #if !os(tvOS)
                                        .padding(.horizontal, 16).padding(.vertical, 8)
                                        .background(Capsule().fill(season.id == current ? Theme.accent : Theme.surface))
                                        .foregroundStyle(season.id == current ? Theme.onAccent : .primary)
                                        #endif
                                }
                                #if os(tvOS)
                                .buttonStyle(.bordered)
                                .tint(season.id == current ? Theme.accent : nil)
                                #else
                                .buttonStyle(.plain)
                                #endif
                                .accessibilityAddTraits(season.id == current ? .isSelected : [])
                            }
                        }
                        .padding(.horizontal, Theme.gutter)
                        #if os(tvOS)
                        .padding(.vertical, 12)
                        #endif
                    }
                    .scrollClipDisabled()
                    #if os(tvOS)
                    .focusSection()
                    #endif
                } else if let only = seasons.first {
                    SectionHeader(title: only.name).padding(.horizontal, Theme.gutter)
                }
                if loading && episodes.isEmpty {
                    ProgressView().padding(.horizontal, Theme.gutter)
                } else {
                    episodeList
                }
            }
            .task(id: current) { await load() }
        }
    }

    private var current: String? { selected ?? initialSeasonID.flatMap { id in seasons.first { $0.id == id }?.id } ?? seasons.first?.id }

    @ViewBuilder private var episodeList: some View {
        #if os(tvOS)
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(alignment: .top, spacing: Theme.cardSpacing) {
                ForEach(episodes) { episode in
                    VStack(alignment: .leading, spacing: 14) {
                        Button { app.play(episode) } label: { WideArtworkOnly(item: episode, width: Theme.wideWidth) }
                            .buttonStyle(.card)
                            .contextMenu { ItemActions(item: episode) ; NavigationLink(value: Route.item(episode)) { Label("Details", systemImage: "info.circle") } }
                        CardCaption(item: episode, wide: true, width: Theme.wideWidth, compactTitle: true)
                        if let overview = episode.overview?.nilIfBlank {
                            Text(overview).font(.caption2).foregroundStyle(Theme.muted).lineLimit(3).frame(width: Theme.wideWidth, alignment: .leading)
                        }
                    }
                }
            }
            .padding(.horizontal, Theme.gutter).padding(.vertical, 24)
        }
        .scrollClipDisabled()
        .focusSection()
        #else
        LazyVStack(alignment: .leading, spacing: 14) {
            ForEach(episodes) { episode in EpisodeRow(episode: episode) }
        }
        .padding(.horizontal, Theme.gutter)
        #endif
    }

    private func load() async {
        guard let season = current, let account = app.account, let client = app.client else { return }
        let seriesID = series.type == "Series" ? series.id : (series.seriesId ?? series.id)
        loading = true
        defer { loading = false }
        if let list = try? await client.episodes(account: account, seriesID: seriesID, seasonID: season), !Task.isCancelled {
            episodes = list
        }
    }
}

/// iPhone/iPad episode row: thumbnail, number and title, synopsis; tap plays, the info button opens details.
struct EpisodeRow: View {
    @EnvironmentObject private var app: AppModel
    let episode: MediaItem
    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Button { app.play(episode) } label: {
                ArtworkImage(url: app.account.flatMap { app.client?.artworkURL(account: $0, item: episode, kind: .wide, width: 480) }, placeholderSymbol: "tv")
                    .frame(width: 168, height: 94.5)
                    .overlay { Image(systemName: "play.fill").font(.title3).foregroundStyle(.white).shadow(radius: 4) }
                    .overlay(alignment: .bottom) { if episode.progress > 0, !episode.isWatched { ProgressStrip(value: episode.progress).padding(6) } }
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Play \(Format.episode(episode)) \(episode.name)")
            NavigationLink(value: Route.item(episode)) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(episode.indexNumber.map { "\($0). " } ?? "").font(.subheadline.weight(.semibold)).foregroundStyle(Theme.muted)
                        + Text(episode.name).font(.subheadline.weight(.semibold))
                        if episode.isWatched { Image(systemName: "checkmark.circle.fill").font(.caption).foregroundStyle(Theme.accent).accessibilityLabel("Watched") }
                    }
                    .lineLimit(2)
                    if let detail = [Format.runtime(episode.runTimeTicks), Format.remaining(episode)].compactMap({ $0 }).first {
                        Text(detail).font(.caption).foregroundStyle(Theme.muted)
                    }
                    if let overview = episode.overview?.nilIfBlank {
                        Text(overview).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .contextMenu { ItemActions(item: episode) }
    }
}

/// Cast and crew with headshots.
struct CastShelf: View {
    @EnvironmentObject private var app: AppModel
    let people: [PersonCredit]
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: "Cast & crew").padding(.horizontal, Theme.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(alignment: .top, spacing: platformValue(tv: 40, touch: 16)) {
                    ForEach(people.prefix(30)) { person in
                        NavigationLink(value: Route.person(id: person.id, name: person.name)) {
                            VStack(spacing: 8) {
                                ArtworkImage(url: app.account.flatMap { app.client?.image(account: $0, id: person.id, type: "Primary", tag: nil, width: 300) }, placeholderSymbol: "person")
                                    .frame(width: platformValue(tv: 180, touch: 84), height: platformValue(tv: 180, touch: 84))
                                    .clipShape(Circle())
                                    #if os(tvOS)
                                    .hoverEffect(.highlight)
                                    #endif
                                Text(person.name).font(.caption.weight(.semibold)).lineLimit(1)
                                Text(person.role?.nilIfBlank ?? person.type ?? "").font(.caption2).foregroundStyle(Theme.muted).lineLimit(1)
                            }
                            .frame(width: platformValue(tv: 200, touch: 96))
                        }
                        #if os(tvOS)
                        .buttonStyle(.borderless)
                        #else
                        .buttonStyle(.plain)
                        #endif
                    }
                }
                .padding(.horizontal, Theme.gutter)
                #if os(tvOS)
                .padding(.vertical, 20)
                #endif
            }
            .scrollClipDisabled()
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }
}

/// File, video, audio and subtitle facts: what a viewer checks when something won't play well.
struct MediaInfoSection: View {
    let item: MediaItem
    var body: some View {
        let source = item.mediaSources[0]
        let video = source.mediaStreams.first { $0.type == "Video" }
        let audio = source.mediaStreams.filter { $0.type == "Audio" }
        let subtitles = source.mediaStreams.filter { $0.type == "Subtitle" }
        VStack(alignment: .leading, spacing: 10) {
            SectionHeader(title: "Media info")
            Grid(alignment: .leading, horizontalSpacing: 20, verticalSpacing: 6) {
                row("File", [source.container?.uppercased(), source.bitrate.map { String(format: "%.1f Mbps", Double($0) / 1_000_000) }].compactMap { $0 }.joined(separator: " · "))
                if let video { row("Video", video.label) }
                if !audio.isEmpty { row("Audio", audio.map(\.label).joined(separator: "\n")) }
                if !subtitles.isEmpty { row("Subtitles", subtitles.map(\.label).joined(separator: "\n")) }
            }
            .font(.caption)
        }
        .padding(.horizontal, Theme.gutter)
        #if os(tvOS)
        .focusable()
        #endif
    }

    @ViewBuilder private func row(_ title: String, _ value: String) -> some View {
        if !value.isEmpty {
            GridRow(alignment: .top) {
                Text(title).foregroundStyle(Theme.muted)
                Text(value)
            }
        }
    }
}

/// Everything a person appears in.
struct PersonView: View {
    @EnvironmentObject private var app: AppModel
    let personID: String
    let name: String
    @StateObject private var items = Loader<[MediaItem]>()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 20) {
                    ArtworkImage(url: app.account.flatMap { app.client?.image(account: $0, id: personID, type: "Primary", tag: nil, width: 400) }, placeholderSymbol: "person")
                        .frame(width: platformValue(tv: 220, touch: 96), height: platformValue(tv: 220, touch: 96))
                        .clipShape(Circle())
                    Text(name).font(.largeTitle.weight(.bold))
                }
                .padding(.horizontal, Theme.gutter)
                switch items.phase {
                case .loading: ProgressView().padding(.horizontal, Theme.gutter)
                case .failed(let message): MessageView(symbol: "exclamationmark.triangle", title: "Couldn't load", message: message)
                case .loaded(let list):
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: platformValue(tv: 248, touch: 108), maximum: platformValue(tv: 260, touch: 180)), spacing: platformValue(tv: 48, touch: 14), alignment: .top)], alignment: .leading, spacing: platformValue(tv: 56, touch: 22)) {
                        ForEach(list) { item in
                            #if os(tvOS)
                            ItemCard(item: item)
                            #else
                            NavigationLink(value: item.route) { GridPoster(item: item) }.buttonStyle(.plain)
                            #endif
                        }
                    }
                    .padding(.horizontal, Theme.gutter)
                }
            }
            .padding(.vertical, 24)
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(name).navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await items.load(app: app) { account, client in try await client.personItems(account: account, personID: personID, limit: 200).items } }
    }
}
