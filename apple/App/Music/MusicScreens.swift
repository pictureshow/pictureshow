// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// A music page: a library, album, artist, genre, playlist or one song (MUS-01, MUS-02).
struct MusicRoute: Hashable {
    var id: String
    var name: String
    /// "library", "MusicAlbum", "MusicArtist", "MusicGenre", "Playlist" or "Audio".
    var kind: String
    /// The music library it was opened from, which artist and genre pages stay inside.
    var library: String?
}

extension MediaItem {
    /// Music opens in the music screens rather than the video grid or detail page. A video playlist stays where it was.
    var musicRoute: MusicRoute? {
        guard MusicItem.isMusic(type: type, collectionType: collectionType, mediaType: mediaType) else { return nil }
        if collectionType == "music" { return MusicRoute(id: id, name: name, kind: "library", library: id) }
        return MusicRoute(id: id, name: name, kind: type, library: type == "MusicGenre" ? parentId : nil)
    }
}

extension MusicItem {
    func route(library: String?) -> MusicRoute { MusicRoute(id: id, name: name, kind: type, library: library) }
    func card(_ server: URL) -> MusicCard { MusicCard(self, server: server) }
}

extension MusicSection {
    var title: String {
        switch self {
        case .albums: "Albums"
        case .albumArtists: "Album artists"
        case .artists: "Artists"
        case .songs: "Songs"
        case .genres: "Genres"
        case .playlists: "Playlists"
        case .favorites: "Favorites"
        }
    }
}

/// One paged music list, with the shared list state (READY, LOADING, EMPTY, INACCESSIBLE, EXPIRED, FAILED).
@MainActor final class MusicListModel: ObservableObject {
    @Published private(set) var items: [MusicItem] = []
    @Published private(set) var loading = false
    @Published private(set) var errorKind: String?
    @Published private(set) var status: Int?
    @Published private(set) var errorText: String?
    private var page = MusicPageState()
    private var generation = 0
    var state: String { MusicCatalog.listState(loading: loading || (!page.ended && errorKind == nil && items.isEmpty), items: items.count, errorKind: errorKind, status: status) }
    var hasMore: Bool { !page.ended && errorKind == nil }

    func reset() { generation += 1; items = []; page = MusicPageState(); errorKind = nil; status = nil; loading = false }

    func loadMore(app: AppModel, _ request: @escaping (Int) -> MusicRequest) async {
        guard !loading, hasMore, let account = app.account, let client = app.client else { return }
        loading = true
        let mine = generation
        defer { if mine == generation { loading = false } }
        do {
            let answer = try await client.musicPage(account: account, request: request(page.offset))
            guard mine == generation else { return }
            let known = Set(items.map(\.id))
            page.merge(ids: answer.items.map(\.id), total: answer.total)
            items += answer.items.filter { !known.contains($0.id) }
        } catch is CancellationError {
        } catch ClientError.invalidCredentials {
            errorKind = "EXPIRED"; app.expire(account)
        } catch ClientError.server(let code) {
            errorKind = "SERVER"; status = code; errorText = ClientError.server(code).localizedDescription
        } catch {
            errorKind = "NETWORK"; errorText = error.localizedDescription
        }
    }
}

/// A music library: albums, album artists, artists, songs, genres, playlists and favorites, with Shuffle all.
struct MusicLibraryView: View {
    @EnvironmentObject private var app: AppModel
    let route: MusicRoute
    @SceneStorage("music.section") private var sectionName = MusicSection.albums.rawValue
    @StateObject private var list = MusicListModel()
    @State private var shuffling = false

    private var section: MusicSection { MusicSection(rawValue: sectionName) ?? .albums }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: platformValue(tv: 36, touch: 16)) {
                #if os(tvOS)
                // tvOS pins a navigation title over scrolled content, so on TV the page carries its own title,
                // as the video library grid does.
                Text(route.name).font(.title2.weight(.bold)).accessibilityAddTraits(.isHeader)
                #endif
                sectionPicker
                Button { Task { await shuffleAll() } } label: { Label("Shuffle all", systemImage: "shuffle") }
                    .primaryAction().disabled(shuffling)
                    .accessibilityIdentifier("music:shuffle-all")
                MusicListBody(list: list, section: section, library: route.id)
            }
            .padding(Theme.gutter)
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(route.name)
        #endif
        .task(id: sectionName) { list.reset(); await list.loadMore(app: app, request) }
    }

    private func request(_ start: Int) -> MusicRequest { MusicCatalog.section(section, library: route.id, start: start) }

    @ViewBuilder private var sectionPicker: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: platformValue(tv: 20, touch: 8)) {
                ForEach(MusicSection.allCases, id: \.self) { choice in
                    Button(choice.title) { sectionName = choice.rawValue }
                        #if os(tvOS)
                        .buttonStyle(TVActionStyle(prominent: choice == section))
                        #else
                        .buttonStyle(MusicChipStyle(selected: choice == section))
                        #endif
                        .accessibilityAddTraits(choice == section ? .isSelected : [])
                }
            }
            .padding(.vertical, platformValue(tv: 20, touch: 2))
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func shuffleAll() async {
        guard let account = app.account, let client = app.client else { return }
        shuffling = true
        defer { shuffling = false }
        do {
            let songs = try await client.musicPage(account: account, request: MusicCatalog.randomSongs(library: route.id)).items.filter(\.isAvailable)
            guard !songs.isEmpty else { app.music.message = "No songs here can play."; return }
            app.music.request(MusicPlay(owner: account.key, tracks: songs.map { $0.card(account.server.url) }, start: nil,
                                        context: "shuffle:\(route.id)", contextName: "Shuffle all", shuffle: true))
        } catch ClientError.invalidCredentials { app.expire(account) }
        catch { app.music.message = "Couldn't load this. Try again." }
    }
}

#if !os(tvOS)
struct MusicChipStyle: ButtonStyle {
    var selected: Bool
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14).padding(.vertical, 9)
            .frame(minHeight: 44)
            .foregroundStyle(selected ? Theme.onAccent : .primary)
            .background(Capsule().fill(selected ? Theme.accent : Theme.surface))
            .opacity(configuration.isPressed ? 0.7 : 1)
            .contentShape(Capsule())
    }
}
#endif

/// The rows of a section: cards for albums, artists, genres and playlists; track rows for songs.
struct MusicListBody: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var list: MusicListModel
    let section: MusicSection
    let library: String?

    var body: some View {
        switch list.state {
        case "LOADING": ProgressView("Loading \(section.title.lowercased())…").frame(maxWidth: .infinity).padding(40)
        case "EMPTY": MusicNotice(text: "No \(section.title.lowercased()) here yet.")
        case "INACCESSIBLE": MusicNotice(text: "This account can't open this music.")
        case "EXPIRED": MusicNotice(text: "Your session has expired. Sign in again.")
        case "FAILED":
            VStack(spacing: 12) {
                MusicNotice(text: list.errorText ?? "Couldn't load this.")
                Button("Try again") { list.reset(); Task { await list.loadMore(app: app, request) } }.secondaryAction()
            }.frame(maxWidth: .infinity)
        default: rows
        }
    }

    private func request(_ start: Int) -> MusicRequest { MusicCatalog.section(section, library: library ?? "", start: start) }

    @ViewBuilder private var rows: some View {
        let songs = list.items.filter(\.isTrack)
        let cards = list.items.filter { !$0.isTrack }
        if !cards.isEmpty {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: platformValue(tv: 260, touch: 150)), spacing: Theme.cardSpacing, alignment: .top)],
                      alignment: .leading, spacing: platformValue(tv: 48, touch: 18)) {
                ForEach(cards) { item in
                    MusicCardLink(item: item, library: library)
                        .onAppear { if item.id == cards.last?.id { Task { await list.loadMore(app: app, request) } } }
                }
            }
        }
        if !songs.isEmpty {
            if !cards.isEmpty { SectionHeader(title: "Songs").padding(.top, 8) }
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(songs.enumerated()), id: \.element.id) { index, song in
                    MusicTrackRow(item: song, number: nil, showsArt: true, library: library) {
                        guard let account = app.account else { return }
                        let playable = songs.filter(\.isAvailable)
                        app.music.request(MusicPlay(owner: account.key, tracks: playable.map { $0.card(account.server.url) },
                                                    start: playable.firstIndex { $0.id == song.id }, context: "\(section.rawValue):\(library ?? "")", contextName: section.title))
                    }
                    .onAppear { if song.id == songs.last?.id { Task { await list.loadMore(app: app, request) } } }
                }
            }
        }
        if list.loading, !list.items.isEmpty { ProgressView().frame(maxWidth: .infinity).padding() }
    }
}

struct MusicNotice: View {
    let text: String
    var body: some View {
        Text(text).font(.body).foregroundStyle(Theme.muted).multilineTextAlignment(.center)
            .frame(maxWidth: .infinity).padding(platformValue(tv: 60, touch: 32))
    }
}

/// Square artwork for a music item (album, artist, genre, playlist or song).
struct MusicArtwork: View {
    @EnvironmentObject private var app: AppModel
    let url: URL?
    var symbol = "music.note"
    var body: some View {
        Color.clear.aspectRatio(1, contentMode: .fit)
            .overlay { ArtworkImage(url: url, placeholderSymbol: symbol) }
            .clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
    }
}

extension MusicItem {
    var symbol: String {
        switch type {
        case "MusicArtist": return "music.mic"
        case "MusicGenre": return "guitars"
        case "Playlist": return "music.note.list"
        case "MusicAlbum": return "square.stack"
        default: return "music.note"
        }
    }
}

struct MusicCardLink: View {
    @EnvironmentObject private var app: AppModel
    let item: MusicItem
    let library: String?
    var body: some View {
        let url = app.account.flatMap { item.track.imageURL(server: $0.server.url, width: 480) }
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 12) {
            NavigationLink(value: Route.music(item.route(library: library))) { MusicArtwork(url: url, symbol: item.symbol) }
                .buttonStyle(.card)
            caption
        }
        #else
        NavigationLink(value: Route.music(item.route(library: library))) {
            VStack(alignment: .leading, spacing: 6) { MusicArtwork(url: url, symbol: item.symbol); caption }
        }
        .buttonStyle(.plain)
        #endif
    }
    private var caption: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.name).font(.footnote.weight(.semibold)).lineLimit(2)
            if let subtitle = item.subtitle { Text(subtitle).font(.caption2).foregroundStyle(Theme.muted).lineLimit(1) }
        }
        .accessibilityElement(children: .combine)
    }
}

/// One song: number or art, title, artist line and length. Unavailable songs stay readable but can't play.
struct MusicTrackRow: View {
    @EnvironmentObject private var app: AppModel
    let item: MusicItem
    var number: Int?
    var showsArt = false
    var albumCredit: String? = nil
    var library: String?
    var playlistActions: [(String, () -> Void)] = []
    var onPick: () -> Void
    @State private var picking = false

    var body: some View {
        Button(action: onPick) { content }
            .buttonStyle(MusicRowStyle())
            .disabled(!item.isAvailable)
            .contextMenu { menu }
            .accessibilityIdentifier("music:track:\(item.id)")
            .accessibilityHint(item.isAvailable ? "Plays from this song" : "")
            .sheet(isPresented: $picking) { PlaylistPicker(tracks: [item]).environmentObject(app) }
    }

    private var content: some View {
        HStack(spacing: platformValue(tv: 24, touch: 12)) {
            if showsArt {
                MusicArtwork(url: app.account.flatMap { item.track.imageURL(server: $0.server.url, width: 160) })
                    .frame(width: platformValue(tv: 80, touch: 44))
            } else {
                Text(number.map(String.init) ?? "").font(.callout.monospacedDigit()).foregroundStyle(.secondary)
                    .frame(width: platformValue(tv: 48, touch: 28), alignment: .trailing)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).font(platformValue(tv: .headline, touch: .body)).lineLimit(1)
                let line = [item.track.artistLine(albumCredit: albumCredit), item.isAvailable ? nil : "Not available"].compactMap { $0 }.joined(separator: " · ")
                if !line.isEmpty { Text(line).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
            }
            Spacer(minLength: 8)
            NowPlayingMark(music: app.music, id: item.id)
            if let length = item.runTimeTicks.map({ Format.clock(Double($0) / 10_000_000) }) { Text(length).font(.caption.monospacedDigit()).foregroundStyle(.secondary) }
        }
        .padding(.vertical, platformValue(tv: 14, touch: 8))
        .frame(minHeight: platformValue(tv: 0, touch: 44))
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var menu: some View {
        if item.isAvailable, let account = app.account {
            Button("Play next", systemImage: "text.insert") { app.music.playNext(owner: account.key, [item.card(account.server.url)]) }
            Button("Add to queue", systemImage: "text.append") { app.music.append(owner: account.key, [item.card(account.server.url)]) }
            Button("Add to playlist", systemImage: "music.note.list") { picking = true }
        }
        if let album = item.track.albumID {
            NavigationLink(value: Route.music(MusicRoute(id: album, name: item.album ?? "Album", kind: "MusicAlbum", library: library))) {
                Label("Go to album", systemImage: "square.stack")
            }
        }
        ForEach(Array(playlistActions.enumerated()), id: \.offset) { _, action in Button(action.0, action: action.1) }
    }
}

struct MusicRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { MusicRowBody(configuration: configuration) }
}
struct MusicRowBody: View {
    static let focusedSecondary = Color.black.opacity(0.68)
    let configuration: ButtonStyleConfiguration
    @Environment(\.isFocused) private var focused
    @Environment(\.isEnabled) private var enabled
    var body: some View {
        configuration.label
            .padding(.horizontal, platformValue(tv: 20, touch: 4))
            // Two levels, so a row's `.secondary` line follows the row: on a focused (white) row it is black at
            // 0.68, about 6:1 on white, where the system secondary label would be pale grey on white.
            .foregroundStyle(focused ? AnyShapeStyle(Color.black) : AnyShapeStyle(.primary),
                             focused ? AnyShapeStyle(Self.focusedSecondary) : AnyShapeStyle(Theme.muted))
            .opacity(enabled ? 1 : 0.55)
            .background(RoundedRectangle(cornerRadius: Theme.corner).fill(focused ? Color.white : (configuration.isPressed ? Theme.surface : .clear)))
            .scaleEffect(focused ? 1.02 : 1)
            .animation(.easeOut(duration: 0.12), value: focused)
    }
}

// MARK: Detail pages

@MainActor final class MusicDetailModel: ObservableObject {
    @Published private(set) var header: MusicItem?
    @Published private(set) var tracks: [MusicItem] = []
    @Published private(set) var albums: [MusicItem] = []
    @Published private(set) var loading = true
    @Published private(set) var error: String?
    @Published private(set) var listState = "LOADING"
    @Published private(set) var canEdit = false
    @Published private(set) var edit: [String: Any] = [:]
    @Published var notice: String?
    @Published private(set) var busy = false
    let route: MusicRoute

    init(route: MusicRoute) { self.route = route }

    var isPlaylist: Bool { route.kind == "Playlist" }
    /// Albums and playlists download whole; an artist is a moving target with no fixed list to keep, so it has no Download (MUS-02).
    var downloadKind: OfflineCollectionKind? {
        guard Downloads.supported else { return nil }
        return route.kind == "MusicAlbum" ? .album : route.kind == "Playlist" ? .playlist : nil
    }
    var playable: [MusicItem] { tracks.filter(\.isAvailable) }
    var discs: [MusicDisc] { route.kind == "MusicAlbum" ? MusicCatalog.discs(tracks.map(\.track)) : [] }
    var editPhase: String { edit["phase"] as? String ?? "idle" }

    func load(app: AppModel) async {
        guard let account = app.account, let client = app.client else { return }
        loading = true; error = nil
        defer { loading = false }
        do {
            header = try await client.musicItems(account: account, ids: [route.id], fields: "ChildCount,ProductionYear,Genres,Overview").first
            switch route.kind {
            case "MusicAlbum":
                let rows = try await client.musicAll(account: account) { MusicCatalog.albumTracks(self.route.id, start: $0) }
                let byID = Dictionary(rows.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                tracks = MusicCatalog.order(rows.map(\.track)).compactMap { byID[$0.id] }
            case "MusicArtist":
                var found = try await client.musicAll(account: account) { MusicCatalog.artist(self.route.id, role: .albumArtist, library: self.route.library, start: $0) }
                if found.isEmpty { found = try await client.musicAll(account: account) { MusicCatalog.artist(self.route.id, role: .artist, library: self.route.library, start: $0) } }
                albums = found
                tracks = try await client.musicAll(account: account) { MusicCatalog.artist(self.route.id, role: .artist, library: self.route.library, tracks: true, start: $0) }
            case "MusicGenre":
                let library = route.library ?? ""
                albums = try await client.musicAll(account: account) { MusicCatalog.genre(self.route.id, library: library, start: $0) }
                tracks = try await client.musicAll(account: account) { MusicCatalog.genre(self.route.id, library: library, tracks: true, start: $0) }
            case "Playlist":
                tracks = try await client.musicAll(account: account) { MusicCatalog.playlistItems(self.route.id, start: $0) }
                canEdit = await client.playlistCanEdit(account: account, playlist: route.id)
                reducePlaylist(["op": "loaded", "entries": tracks.compactMap(\.playlistItemID)])
                if !canEdit { reducePlaylist(["op": "read-only"]) }
            default:
                tracks = header.map { [$0] } ?? []
            }
            listState = MusicCatalog.listState(loading: false, items: tracks.count + albums.count, errorKind: nil, status: nil)
        } catch is CancellationError {
        } catch ClientError.invalidCredentials {
            listState = "EXPIRED"; app.expire(account)
        } catch ClientError.server(let code) {
            listState = MusicCatalog.listState(loading: false, items: 0, errorKind: "SERVER", status: code)
            error = ClientError.server(code).localizedDescription
        } catch {
            listState = "FAILED"; self.error = error.localizedDescription
        }
    }

    func play(app: AppModel, from track: MusicItem? = nil, shuffle: Bool = false) {
        guard let account = app.account else { return }
        let list = playable
        guard !list.isEmpty else { notice = "No songs here can play."; return }
        app.music.request(MusicPlay(owner: account.key, tracks: list.map { $0.card(account.server.url) },
                                    start: track.flatMap { t in list.firstIndex { $0.id == t.id && $0.playlistItemID == t.playlistItemID } },
                                    context: "\(route.kind):\(route.id)", contextName: header?.name ?? route.name, shuffle: shuffle))
    }

    func instantMix(app: AppModel) async {
        guard let account = app.account, let client = app.client else { return }
        busy = true; defer { busy = false }
        do {
            let mix = try await client.musicPage(account: account, request: MusicCatalog.instantMix(route.id)).items.filter(\.isAvailable)
            guard !mix.isEmpty else { notice = "The server has no mix for this yet."; return }
            app.music.request(MusicPlay(owner: account.key, tracks: mix.map { $0.card(account.server.url) }, start: nil,
                                        context: "mix:\(route.id)", contextName: "Instant mix of \(header?.name ?? route.name)"))
        } catch ClientError.invalidCredentials { app.expire(account) }
        catch { notice = "Couldn't load this. Try again." }
    }

    func toggleFavorite(app: AppModel) async {
        guard let account = app.account, let client = app.client, let header else { return }
        do {
            let data = try await client.favorite(account: account, itemID: header.id, value: !header.isFavorite)
            self.header?.isFavorite = data.isFavorite
        } catch ClientError.invalidCredentials { app.expire(account) }
        catch { notice = "That change didn't save. Try again." }
    }

    // Playlist edits (MUS-09): the shared playlist reducer decides what is sent, and every edit is read back.

    private func reducePlaylist(_ event: [String: Any]) {
        guard let next = try? MusicReducers.playlist(state: edit, event: event) else { return }
        edit = next
        if let message = next["message"] as? String { notice = message }
    }

    func editPlaylist(app: AppModel, _ name: String, _ write: @escaping (Account, JellyfinClient) async -> PlaylistWrite) async {
        guard let account = app.account, let client = app.client else { return }
        reducePlaylist(["op": "submit", "edit": name])
        switch edit["effect"] as? String {
        case "send":
            let result = await write(account, client)
            switch result.outcome {
            case "ok": reducePlaylist(["op": "sent"])
            case "denied": reducePlaylist(["op": "denied"])
            case "failed": reducePlaylist(["op": "failed"])
            default: reducePlaylist(["op": "ambiguous"])
            }
            if edit["effect"] as? String == "read-back" { await readBack(app: app) }
        case "read-back": await readBack(app: app)
        default: break
        }
    }

    func readBack(app: AppModel) async {
        guard let account = app.account, let client = app.client else { return }
        do {
            let rows = try await client.musicAll(account: account) { MusicCatalog.playlistItems(self.route.id, start: $0) }
            tracks = rows
            reducePlaylist(["op": "read", "entries": rows.compactMap(\.playlistItemID)])
        } catch {
            reducePlaylist(["op": "read-failed"])
        }
    }

    func remove(app: AppModel, _ track: MusicItem) async {
        guard let entry = track.playlistItemID else { return }
        await editPlaylist(app: app, "remove") { account, client in await client.removeFromPlaylist(account: account, playlist: self.route.id, entries: [entry]) }
    }

    func move(app: AppModel, _ track: MusicItem, to index: Int) async {
        guard let entry = track.playlistItemID else { return }
        await editPlaylist(app: app, "move") { account, client in await client.movePlaylistEntry(account: account, playlist: self.route.id, entry: entry, index: index) }
    }

    func rename(app: AppModel, to name: String) async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let account = app.account, let client = app.client else { return }
        guard canEdit else { notice = "You can't edit this playlist."; return }
        let result = await client.renamePlaylist(account: account, playlist: route.id, name: trimmed)
        switch result.outcome {
        case "ok": header?.name = trimmed; notice = "Renamed to \(trimmed)."
        case "denied": notice = "The server didn't allow that change."
        case "failed": notice = "That change didn't save. Try again."
        default: notice = "The server didn't answer, so check Playlists before trying again."
        }
    }
}

struct MusicDetailView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var model: MusicDetailModel
    @State private var picking = false
    @State private var renaming = false
    @State private var newName = ""
    #if os(iOS)
    @StateObject private var download = MusicDownloadController()
    #endif

    init(route: MusicRoute) { _model = StateObject(wrappedValue: MusicDetailModel(route: route)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: platformValue(tv: 40, touch: 20)) {
                header
                #if os(iOS)
                if let kind = model.downloadKind { MusicDownloadStatusLine(downloads: app.downloads, collectionID: model.route.id, kind: kind) }
                #endif
                actions
                #if os(iOS)
                // Its own row, so it's in view on a phone rather than at the far end of the scrolling actions.
                if model.downloadKind != nil {
                    // Side by side when both fit on one line each, else one above the other (a phone's "Remove download").
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 10) { MusicDownloadButtons(downloads: app.downloads, model: model, controller: download).fixedSize() }
                        VStack(alignment: .leading, spacing: 10) { MusicDownloadButtons(downloads: app.downloads, model: model, controller: download).fixedSize() }
                    }
                }
                #endif
                if let notice = model.notice {
                    Text(notice).font(.callout).foregroundStyle(Theme.muted).accessibilityIdentifier("music:notice")
                }
                content
            }
            .padding(Theme.gutter)
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(model.header?.name ?? model.route.name)
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await model.load(app: app) }
        .sheet(isPresented: $picking) { PlaylistPicker(tracks: model.playable).environmentObject(app) }
        #if os(iOS)
        .modifier(MusicDownloadAlerts(controller: download, name: model.header?.name ?? model.route.name))
        #endif
        .alert("Rename playlist", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Rename") { Task { await model.rename(app: app, to: newName) } }
            Button("Cancel", role: .cancel) {}
        }
    }

    private var artURL: URL? { app.account.flatMap { a in model.header?.track.imageURL(server: a.server.url, width: 800) } }

    @ViewBuilder private var header: some View {
        let layout = platformValue(tv: AnyLayout(HStackLayout(alignment: .bottom, spacing: 48)), touch: AnyLayout(VStackLayout(alignment: .leading, spacing: 14)))
        layout {
            MusicArtwork(url: artURL, symbol: model.header?.symbol ?? "music.note")
                .frame(width: platformValue(tv: 360, touch: 220))
                .frame(maxWidth: .infinity, alignment: platformValue(tv: .leading, touch: .center))
                .fixedSize(horizontal: platformValue(tv: true, touch: false), vertical: false)
            VStack(alignment: .leading, spacing: 6) {
                Text(model.header?.name ?? model.route.name).font(platformValue(tv: .title2.weight(.bold), touch: .title2.weight(.bold)))
                    .accessibilityAddTraits(.isHeader)
                if let subtitle = facts { Text(subtitle).font(.subheadline).foregroundStyle(Theme.muted) }
            }
        }
    }

    private var facts: String? {
        guard let h = model.header else { return nil }
        let count = model.tracks.count
        let songs = count == 0 ? nil : (count == 1 ? "1 song" : "\(count) songs")
        let total = model.tracks.compactMap(\.runTimeTicks).reduce(0, +)
        let length = total > 0 ? Format.runtime(total) : nil
        return [h.type == "MusicAlbum" ? h.subtitle : nil, h.productionYear.map(String.init), songs, length].compactMap { $0 }.joined(separator: " · ").nilIfBlank
    }

    @ViewBuilder private var actions: some View {
        let account = app.account
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: platformValue(tv: 24, touch: 10)) {
                Button { model.play(app: app) } label: { Label("Play", systemImage: "play.fill") }
                    .primaryAction().disabled(model.playable.isEmpty).accessibilityIdentifier("music:play")
                Button { model.play(app: app, shuffle: true) } label: { Label("Shuffle", systemImage: "shuffle") }
                    .secondaryAction().disabled(model.playable.count < 2)
                Button { if let account { app.music.playNext(owner: account.key, model.playable.map { $0.card(account.server.url) }) } } label: { Label("Play next", systemImage: "text.insert") }
                    .secondaryAction().disabled(model.playable.isEmpty)
                Button { if let account { app.music.append(owner: account.key, model.playable.map { $0.card(account.server.url) }) } } label: { Label("Add to queue", systemImage: "text.append") }
                    .secondaryAction().disabled(model.playable.isEmpty)
                if !model.isPlaylist {
                    Button { picking = true } label: { Label("Add to playlist", systemImage: "music.note.list") }
                        .secondaryAction().disabled(model.playable.isEmpty)
                }
                Button { Task { await model.instantMix(app: app) } } label: { Label("Instant mix", systemImage: "wand.and.stars") }
                    .secondaryAction().disabled(model.busy)
                if model.header != nil, model.route.kind != "MusicGenre" {
                    Button { Task { await model.toggleFavorite(app: app) } } label: {
                        Label(model.header?.isFavorite == true ? "Remove from favorites" : "Add to favorites", systemImage: model.header?.isFavorite == true ? "heart.fill" : "heart")
                    }
                    .secondaryAction()
                }
                if model.isPlaylist, model.canEdit {
                    Button { newName = model.header?.name ?? ""; renaming = true } label: { Label("Rename", systemImage: "pencil") }.secondaryAction()
                }
                if model.isPlaylist, model.editPhase == "unknown" {
                    Button { Task { await model.readBack(app: app) } } label: { Text("Check the playlist") }.secondaryAction()
                }
            }
            .padding(.vertical, platformValue(tv: 24, touch: 2))
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    @ViewBuilder private var content: some View {
        if model.loading && model.tracks.isEmpty && model.albums.isEmpty {
            ProgressView("Loading…").frame(maxWidth: .infinity).padding(40)
        } else {
            switch model.listState {
            case "INACCESSIBLE": MusicNotice(text: "This account can't open this music.")
            case "EXPIRED": MusicNotice(text: "Your session has expired. Sign in again.")
            case "FAILED":
                VStack(spacing: 12) {
                    MusicNotice(text: model.error ?? "Couldn't load this.")
                    Button("Try again") { Task { await model.load(app: app) } }.secondaryAction()
                }.frame(maxWidth: .infinity)
            case "EMPTY": MusicNotice(text: model.route.kind == "MusicAlbum" ? "No playable tracks in this album." : "Nothing here yet.")
            default: lists
            }
        }
    }

    @ViewBuilder private var lists: some View {
        if !model.albums.isEmpty {
            SectionHeader(title: "Albums")
            LazyVGrid(columns: [GridItem(.adaptive(minimum: platformValue(tv: 240, touch: 140)), spacing: Theme.cardSpacing, alignment: .top)],
                      alignment: .leading, spacing: platformValue(tv: 40, touch: 16)) {
                ForEach(model.albums) { MusicCardLink(item: $0, library: model.route.library) }
            }
            #if os(tvOS)
            .focusSection()
            #endif
        }
        if !model.tracks.isEmpty {
            if model.route.kind != "MusicAlbum" && model.route.kind != "Playlist" && model.route.kind != "Audio" { SectionHeader(title: "Songs") }
            let discs = model.discs
            if discs.count > 1 {
                ForEach(discs, id: \.disc) { disc in
                    Text("Disc \(disc.disc ?? 1)").font(.headline).foregroundStyle(Theme.muted).padding(.top, 8).accessibilityAddTraits(.isHeader)
                    trackList(disc.tracks.compactMap { id in model.tracks.first { $0.id == id } })
                }
            } else {
                trackList(model.tracks)
            }
        }
    }

    private func trackList(_ rows: [MusicItem]) -> some View {
        LazyVStack(alignment: .leading, spacing: 0) {
            ForEach(Array(rows.enumerated()), id: \.offset) { index, track in
                MusicTrackRow(item: track, number: model.route.kind == "MusicAlbum" ? track.track.number : index + 1,
                              showsArt: model.route.kind != "MusicAlbum", albumCredit: model.route.kind == "MusicAlbum" ? model.header?.track.albumCredit : nil,
                              library: model.route.library, playlistActions: playlistActions(track, index: index)) {
                    model.play(app: app, from: track)
                }
            }
        }
        #if os(tvOS)
        .focusSection()
        #endif
    }

    private func playlistActions(_ track: MusicItem, index: Int) -> [(String, () -> Void)] {
        guard model.isPlaylist, model.canEdit, track.playlistItemID != nil else { return [] }
        var actions: [(String, () -> Void)] = []
        if index > 0 { actions.append(("Move up", { Task { await model.move(app: app, track, to: index - 1) } })) }
        if index < model.tracks.count - 1 { actions.append(("Move down", { Task { await model.move(app: app, track, to: index + 1) } })) }
        actions.append(("Remove from playlist", { Task { await model.remove(app: app, track) } }))
        return actions
    }
}

/// The music route's destination: a library or a detail page.
struct MusicRouteView: View {
    let route: MusicRoute
    var body: some View {
        if route.kind == "library" { MusicLibraryView(route: route) } else { MusicDetailView(route: route) }
    }
}

// MARK: Add to playlist

/// Picks one of the viewer's playlists, or makes a new private one, for these songs (MUS-09).
struct PlaylistPicker: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    let tracks: [MusicItem]
    @StateObject private var list = MusicListModel()
    @State private var name = ""
    @State private var sending = false
    @State private var notice: String?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    TextField("New playlist name", text: $name).accessibilityIdentifier("music:playlist-name")
                    Button("Create playlist") { Task { await create() } }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || sending)
                } footer: { Text("Only you can see a new playlist.") }
                Section("Playlists") {
                    if list.state == "LOADING" { ProgressView("Loading playlists…") }
                    else if list.state == "EMPTY" { Text("No playlists yet.").foregroundStyle(Theme.muted) }
                    else if list.state == "FAILED" || list.state == "INACCESSIBLE" { Text("Couldn't read that playlist. Try again.").foregroundStyle(Theme.muted) }
                    ForEach(list.items) { playlist in
                        Button(playlist.name) { Task { await add(to: playlist) } }.disabled(sending)
                    }
                }
                if let notice { Text(notice).foregroundStyle(Theme.muted).accessibilityIdentifier("music:playlist-notice") }
            }
            .navigationTitle("Add to playlist")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .task { await list.loadMore(app: app) { MusicCatalog.section(.playlists, library: "", start: $0) } }
        }
    }

    private func create() async {
        guard let account = app.account, let client = app.client else { return }
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        sending = true; defer { sending = false }
        let result = await client.createPlaylist(account: account, name: trimmed, items: tracks.map(\.id))
        switch result.outcome {
        case "ok": app.music.message = "Created \(trimmed)."; dismiss()
        case "denied": notice = "The server didn't allow that change."
        case "failed": notice = "That playlist didn't save. Try again."
        default: notice = "The server didn't answer, so check Playlists before trying again."
        }
    }

    private func add(to playlist: MusicItem) async {
        guard let account = app.account, let client = app.client else { return }
        sending = true; defer { sending = false }
        guard await client.playlistCanEdit(account: account, playlist: playlist.id) else { notice = "You can't edit this playlist."; return }
        let result = await client.addToPlaylist(account: account, playlist: playlist.id, items: tracks.map(\.id))
        switch result.outcome {
        case "ok": app.music.message = "Added to \(playlist.name)."; dismiss()
        case "denied": notice = "The server didn't allow that change."
        case "failed": notice = "That change didn't save. Try again."
        default: notice = "The server didn't answer, so check Playlists before trying again."
        }
    }
}

/// The speaker beside the song that is playing, kept current by the host itself.
private struct NowPlayingMark: View {
    @ObservedObject var music: MusicHost
    @Environment(\.isFocused) private var focused
    let id: String
    var body: some View {
        if music.currentCard?.id == id {
            // The accent is pale on a focused (white) row, so there the mark takes the row's black.
            Image(systemName: "speaker.wave.2.fill").foregroundStyle(focused ? AnyShapeStyle(Color.black) : AnyShapeStyle(Theme.accent)).accessibilityLabel("Now playing")
        }
    }
}

#if os(iOS)
/// The page's download, in the Downloads screen's words: "Downloaded · 12 tracks · 410 MB", or how far it has got.
private struct MusicDownloadStatusLine: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var downloads: Downloads
    let collectionID: String
    let kind: OfflineCollectionKind

    var body: some View {
        if let account = app.account,
           let status = MusicDownloadWords.status(downloads.manifest(account), collectionID: collectionID,
                                                  progress: { downloads.progress(account, $0) }, networkUp: downloads.networkUp) {
            VStack(alignment: .leading, spacing: 6) {
                Label(status.text, systemImage: status.ready ? "checkmark.circle.fill" : status.error ? "exclamationmark.circle" : "arrow.down.circle")
                    .font(.subheadline).foregroundStyle(status.error ? Color.red : Theme.muted)
                if let fraction = status.fraction { ProgressView(value: fraction) }
            }
            .accessibilityElement(children: .combine)
            .accessibilityIdentifier("music:download-status")
        }
    }
}

/// Download, or Remove/Stop and the way to Downloads (MUS-02).
private struct MusicDownloadButtons: View {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var downloads: Downloads
    @ObservedObject var model: MusicDetailModel
    @ObservedObject var controller: MusicDownloadController

    var body: some View {
        if let account = app.account, let kind = model.downloadKind {
            let name = model.header?.name ?? model.route.name
            if let status = MusicDownloadWords.status(downloads.manifest(account), collectionID: model.route.id) {
                Button { controller.askRemove(id: model.route.id, name: name, ready: status.ready) } label: {
                    Label(status.ready ? "Remove download" : "Stop download", systemImage: status.ready ? "trash" : "xmark.circle")
                }
                .secondaryAction().accessibilityIdentifier("music:download-remove")
                Button { app.showingDownloads = true } label: { Label("Open Downloads", systemImage: "arrow.down.circle") }
                    .secondaryAction().accessibilityIdentifier("music:downloads")
            } else {
                Button {
                    controller.request(downloads: downloads, account: account, kind: kind, id: model.route.id, name: name,
                                       artist: model.isPlaylist ? nil : model.header?.subtitle, order: model.playable.map(\.id))
                } label: {
                    Label(controller.ask == .checking ? "Checking…" : "Download", systemImage: "arrow.down.circle")
                }
                .secondaryAction()
                .disabled(model.playable.isEmpty || model.loading || model.busy || controller.ask == .checking)
                .accessibilityIdentifier("music:download")
            }
        }
    }
}

/// The page's download questions. Cancel comes first in each.
private struct MusicDownloadAlerts: ViewModifier {
    @EnvironmentObject private var app: AppModel
    @ObservedObject var controller: MusicDownloadController
    let name: String

    private var shown: Binding<Bool> {
        Binding(get: { if case .checking = controller.ask { return false }; return controller.ask != nil },
                set: { if !$0, controller.ask != .checking { controller.ask = nil } })
    }

    func body(content: Content) -> some View {
        content.alert(title, isPresented: shown) {
            switch controller.ask {
            case .confirm(_, let wanted):
                Button("Cancel", role: .cancel) { controller.ask = nil }
                Button("Download") { if let account = app.account { controller.start(downloads: app.downloads, account: account, wanted: wanted) } }
            case .remove(_, let confirm):
                Button("Cancel", role: .cancel) { controller.ask = nil }
                Button(confirm, role: .destructive) {
                    controller.ask = nil
                    if let account = app.account, let id = controller.collectionID { app.downloads.deleteCollection(account, id) }
                }
            default:
                Button("OK", role: .cancel) { controller.ask = nil }
            }
        } message: {
            switch controller.ask {
            case .confirm(let text, _), .notice(let text), .remove(let text, _): Text(text)
            default: EmptyView()
            }
        }
    }

    private var title: String {
        switch controller.ask {
        case .confirm: "Download \(name)?"
        case .remove(_, let confirm): confirm == "Remove" ? "Remove download?" : "Stop download?"
        default: name
        }
    }
}
#endif
