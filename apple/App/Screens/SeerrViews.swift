// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import UIKit
import PictureshowCore

// Seerr requests (SEERR-01..07), translated from the Android reference (SeerrViewModel, SeerrScreens, TouchSeerrScreens).
// Nothing here appears until the viewer's account has connected Seerr in Settings.

/// A Seerr title page to open; its ids are TMDB's and never reach Jellyfin.
struct SeerrTitleRoute: Hashable {
    let type: String
    let id: Int
}

struct SeerrRequestRow: Identifiable {
    let request: SeerrRequest
    let title: SeerrTitle?
    var id: Int { request.id }
}

/// One screen's view of the account's Seerr connection: hub, title, connect, or search's "Not in your library".
@MainActor final class SeerrModel: ObservableObject {
    @Published var trending: [SeerrTitle] = []
    @Published var trendingLoading = false
    @Published var trendingError: SeerrError?
    @Published var results: [SeerrTitle] = []
    @Published var searching = false
    @Published var searched: String?
    @Published var searchError: SeerrError?
    @Published var requests: [SeerrRequestRow] = []
    @Published var requestsLoading = false
    @Published var requestsLoaded = false
    @Published var requestsError: SeerrError?
    @Published var title: SeerrTitle?
    @Published var titleLoading = false
    @Published var titleError: SeerrError?
    @Published var chosen: Set<Int> = []
    @Published var sending = false
    @Published var sendError: SeerrError?
    @Published var sent = false
    @Published var connecting = false
    @Published var connectError: SeerrError?

    private func client(_ app: AppModel) throws -> SeerrClient {
        guard let key = app.account?.key, let client = app.seerr.client(key) else { throw SeerrError.expired }
        return client
    }

    private func attempt<T>(_ app: AppModel, _ work: (SeerrClient) async throws -> T) async -> Result<T, SeerrError> {
        do { return .success(try await work(try client(app))) }
        catch let error as SeerrError { return .failure(error) }
        catch { return .failure(.server) }
    }

    func loadTrending(_ app: AppModel, force: Bool = false) async {
        if trendingLoading || (!force && !trending.isEmpty) { return }
        trendingLoading = true
        trendingError = nil
        // Two pages: about 40 titles, enough for a movies row and a series row.
        let result = await attempt(app) { seerr in
            var titles: [SeerrTitle] = []
            for page in 1...2 { titles += try await seerr.trending(page: page).results }
            return titles
        }
        trendingLoading = false
        switch result {
        case .success(let titles):
            var seen = Set<String>()
            trending = titles.filter { ["movie", "tv"].contains($0.mediaType) && seen.insert("\($0.mediaType)-\($0.id)").inserted }
        case .failure(let error): trendingError = error
        }
    }

    /// Debounced like the library search; under 2 characters clears the results. Call from `.task(id:)`.
    func search(_ app: AppModel, query: String) async {
        let term = seerrNormalizeSearch(query)
        guard term.count >= 2, app.seerr.config(app.account?.key) != nil else {
            results = []; searching = false; searched = nil; searchError = nil
            return
        }
        try? await Task.sleep(for: .milliseconds(350))
        guard !Task.isCancelled else { return }
        searching = true
        searchError = nil
        let result = await attempt(app) { try await $0.search(term).results.filter { ["movie", "tv"].contains($0.mediaType) } }
        guard !Task.isCancelled else { return }
        searching = false
        searched = term
        switch result {
        case .success(let titles): results = titles
        case .failure(let error): results = []; searchError = error
        }
    }

    func loadRequests(_ app: AppModel) async {
        if requestsLoading { return }
        requestsLoading = true
        requestsError = nil
        let result = await attempt(app) { seerr -> [SeerrRequestRow] in
            let requests = try await seerr.requests(take: 30).results
            // A request carries only TMDB ids; each row's title and poster come from the title's own page.
            return await withTaskGroup(of: (Int, SeerrTitle?).self) { group in
                for (index, request) in requests.enumerated() {
                    group.addTask { (index, try? await seerr.detail(type: request.type == "tv" ? "tv" : "movie", id: request.media.tmdbId)) }
                }
                var titles: [Int: SeerrTitle] = [:]
                for await (index, title) in group { titles[index] = title }
                return requests.enumerated().map { SeerrRequestRow(request: $0.element, title: titles[$0.offset]) }
            }
        }
        requestsLoading = false
        requestsLoaded = true
        switch result {
        case .success(let rows): requests = rows
        case .failure(let error): requestsError = error
        }
    }

    func openTitle(_ app: AppModel, type: String, id: Int) async {
        guard ["movie", "tv"].contains(type), id > 0 else { titleError = .server; return }
        // "Requested" belongs to the title it was sent for; reopening that title after the request keeps it.
        let same = title.map { $0.mediaType == type && $0.id == id } ?? false
        titleLoading = true
        titleError = nil
        sendError = nil
        sent = sent && same
        let result = await attempt(app) { try await $0.detail(type: type, id: id) }
        titleLoading = false
        switch result {
        case .success(let loaded):
            title = loaded
            // A series starts with every season that can still be requested chosen, so one press asks for all.
            if !sent { chosen = Set(seerrSelectableSeasons(loaded)) }
        case .failure(let error): titleError = error
        }
    }

    func toggleSeason(_ season: Int) {
        guard let title, seerrSelectableSeasons(title).contains(season) else { return }
        if chosen.contains(season) { chosen.remove(season) } else { chosen.insert(season) }
        sendError = nil
    }

    func toggleAll() {
        guard let title else { return }
        let all = Set(seerrSelectableSeasons(title))
        chosen = chosen.isSuperset(of: all) ? [] : all
        sendError = nil
    }

    /// Sends nothing until something can be requested: a series needs a chosen season, a movie must be requestable.
    func request(_ app: AppModel) async {
        guard let title, !sending else { return }
        let seasons = title.mediaType == "tv" ? seerrSelectableSeasons(title).filter(chosen.contains) : []
        if title.mediaType == "tv" && seasons.isEmpty { return }
        if title.mediaType == "movie" && !seerrMovieRequestable(title) { return }
        sending = true
        sendError = nil
        let result = await attempt(app) { try await $0.request(title, seasons: seasons) }
        sending = false
        switch result {
        case .success: sent = true
        case .failure(let error): sent = false; sendError = error
        }
        // The page then shows the new state: requested seasons stop being selectable.
        if sent || sendError == .alreadyRequested {
            let error = sendError
            await openTitle(app, type: title.mediaType, id: title.id)
            // Reloading clears messages; "already requested" still explains why nothing new was sent.
            sendError = sendError ?? error
        }
    }

    func connect(_ app: AppModel, url: String, apiKey: String?, username: String, password: String) async {
        guard !connecting, let account = app.account else { return }
        connecting = true
        connectError = nil
        do {
            _ = try await app.seerr.connect(account: account, url: url, apiKey: apiKey, username: username, password: password) {
                app.accounts.contains { $0.key == account.key && !$0.signedOut }
            }
        } catch let error as SeerrError {
            connectError = error
        } catch {
            connectError = .server
        }
        connecting = false
    }
}

enum SeerrText {
    static func error(_ error: SeerrError) -> String {
        switch error {
        case .unreachable: return "Can't reach Seerr. Check the address and that this device is on the same network, then try again."
        case .password: return "Seerr didn't accept that Jellyfin username and password."
        case .apiKey: return "Seerr didn't accept that API key."
        case .expired: return "Your Seerr sign-in has ended. Connect again in Settings."
        case .permission: return "Your Seerr account doesn't have permission to request. Ask whoever runs Seerr."
        case .quota: return "You've reached your request limit on Seerr. Try again later."
        case .alreadyRequested: return "This was already requested."
        case .blocked: return "Seerr has blocked requests for this title."
        case .notLinked: return "Your Jellyfin account isn't linked in Seerr, so requests can't be made as you. Ask whoever runs Seerr to import your Jellyfin user, or sign in with Jellyfin instead."
        case .server: return "Seerr answered with an error. Try again later."
        case .address: return "That doesn't look like a Seerr address. Use the address you open Seerr with in a browser, including http:// if it doesn't use https."
        case .storage: return "The Seerr sign-in couldn't be saved securely on this device. Try again."
        }
    }

    /// Nil when there's nothing worth saying (Seerr knows nothing of the title).
    static func status(_ status: SeerrStatus) -> String? {
        switch status {
        case .unknown, .deleted: return nil
        case .pending: return "Pending approval"
        case .approved: return "Approved"
        case .processing: return "Downloading"
        case .partial: return "Partly available"
        case .available, .completed: return "Available"
        case .declined: return "Declined"
        case .failed: return "Failed"
        case .blocked: return "Blocked"
        }
    }

    static func kind(_ title: SeerrTitle) -> String { title.mediaType == "tv" ? "Series" : "Movie" }
    static func line(_ title: SeerrTitle) -> String { [title.year, kind(title)].filter { !$0.isEmpty }.joined(separator: " · ") }
}

// MARK: - Posters

/// TMDB posters, fetched with a session that carries no credentials, cookies or Jellyfin headers.
actor SeerrImages {
    static let shared = SeerrImages()
    private let memory = NSCache<NSURL, UIImage>()
    private let session: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        configuration.urlCredentialStorage = nil
        return URLSession(configuration: configuration)
    }()

    func image(_ url: URL) async -> UIImage? {
        // Only TMDB's image host, which `seerrPosterURL` alone produces.
        guard url.host == "image.tmdb.org", url.scheme == "https" else { return nil }
        if let image = memory.object(forKey: url as NSURL) { return image }
        guard let (data, response) = try? await session.data(from: url), (response as? HTTPURLResponse)?.statusCode == 200,
              let image = UIImage(data: data)?.preparingForDisplay() else { return nil }
        memory.setObject(image, forKey: url as NSURL)
        return image
    }
}

struct SeerrPoster: View {
    let title: SeerrTitle?
    let width: CGFloat
    @State private var image: UIImage?

    var body: some View {
        Color.clear
            .overlay {
                if let image {
                    Image(uiImage: image).resizable().aspectRatio(contentMode: .fill).transition(.opacity)
                } else {
                    ZStack {
                        Theme.placeholder
                        Image(systemName: title?.mediaType == "tv" ? "tv" : "film").font(.system(size: 28, weight: .light)).foregroundStyle(Theme.muted.opacity(0.6))
                    }
                }
            }
            .frame(width: width, height: width * 1.5)
            .clipped()
            .accessibilityHidden(true)
            .task(id: title?.posterPath) {
                guard let url = title?.poster else { image = nil; return }
                let loaded = await SeerrImages.shared.image(url)
                guard !Task.isCancelled else { return }
                withAnimation(.easeOut(duration: 0.2)) { image = loaded }
            }
    }
}

/// A Seerr title as one of the app's poster cards: title, year · Movie/Series, then what Seerr says about it.
struct SeerrCard: View {
    let title: SeerrTitle
    var width: CGFloat = Theme.posterWidth

    var body: some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 14) {
            NavigationLink(value: SeerrTitleRoute(type: title.mediaType, id: title.id)) { SeerrPoster(title: title, width: width) }
                .buttonStyle(.card)
                .accessibilityLabel(accessibility)
            caption
        }
        #else
        NavigationLink(value: SeerrTitleRoute(type: title.mediaType, id: title.id)) {
            VStack(alignment: .leading, spacing: 8) {
                SeerrPoster(title: title, width: width)
                    .clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).strokeBorder(.white.opacity(0.06)))
                caption
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
        .accessibilityAddTraits(.isButton)
        #endif
    }

    private var caption: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title.label).font(platformValue(tv: Font.caption.weight(.semibold), touch: Font.subheadline.weight(.semibold))).lineLimit(1)
            Text(SeerrText.line(title)).font(platformValue(tv: Font.caption2, touch: Font.caption)).foregroundStyle(Theme.muted).lineLimit(1)
            // Always two lines tall, so a row of cards lines up whether or not Seerr knows a title.
            Text(SeerrText.status(title.status) ?? " ").font(platformValue(tv: Font.caption2, touch: Font.caption).weight(.semibold))
                .foregroundStyle(Theme.accent).lineLimit(1)
        }
        .frame(width: width, alignment: .leading)
    }

    private var accessibility: String {
        [title.label, SeerrText.line(title), SeerrText.status(title.status)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

/// A titled horizontal row of Seerr cards, laid out like the library's shelves.
struct SeerrShelf: View {
    let title: String
    let titles: [SeerrTitle]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            SectionHeader(title: title).padding(.horizontal, Theme.gutter)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: Theme.cardSpacing) {
                    ForEach(titles, id: \.cardID) { SeerrCard(title: $0) }
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

extension SeerrTitle {
    var cardID: String { "\(mediaType)-\(id)" }
}

// MARK: - Requests hub (SEERR-05)

enum SeerrHubTab: String, CaseIterable, Identifiable {
    case trending = "Trending", search = "Search", mine = "My requests"
    var id: String { rawValue }
}

/// Trending, Search and My requests: the Requests tab while the account is connected.
struct SeerrHubView: View {
    @EnvironmentObject private var app: AppModel
    @StateObject private var model = SeerrModel()
    @State private var tab: SeerrHubTab = .trending
    @State private var query = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Theme.shelfSpacing) {
                #if os(tvOS)
                // tvOS pins a navigation title while the page scrolls under it, so the TV title is content.
                Text("Requests").font(.title2.weight(.bold)).accessibilityAddTraits(.isHeader).padding(.horizontal, Theme.gutter)
                #endif
                Picker("Show", selection: $tab) {
                    ForEach(SeerrHubTab.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: platformValue(tv: 900, touch: 520))
                .padding(.horizontal, Theme.gutter)
                #if os(tvOS)
                .focusSection()
                #endif
                switch tab {
                case .trending: trending
                case .search: search
                case .mine: mine
                }
            }
            .padding(.vertical, 20)
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle("Requests")
        #endif
        .task { await model.loadTrending(app) }
        .task(id: tab) { if tab == .mine { await model.loadRequests(app) } }
        .task(id: query) { await model.search(app, query: query) }
        .refreshable {
            switch tab {
            case .trending: await model.loadTrending(app, force: true)
            case .mine: await model.loadRequests(app)
            case .search: await model.search(app, query: query)
            }
        }
    }

    @ViewBuilder private var trending: some View {
        if let error = model.trendingError, model.trending.isEmpty {
            MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load Trending", message: SeerrText.error(error), actionTitle: "Retry") {
                Task { await model.loadTrending(app, force: true) }
            }
        } else if model.trending.isEmpty {
            ProgressView("Loading…").frame(maxWidth: .infinity).padding(.top, 60)
        } else {
            let movies = model.trending.filter { $0.mediaType == "movie" }, series = model.trending.filter { $0.mediaType == "tv" }
            if !movies.isEmpty { SeerrShelf(title: "Trending movies", titles: movies) }
            if !series.isEmpty { SeerrShelf(title: "Trending series", titles: series) }
        }
    }

    @ViewBuilder private var search: some View {
        TextField("Search Seerr", text: $query, prompt: Text("Movies and series, in your library or not"))
            #if os(iOS)
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            #endif
            .autocorrectionDisabled()
            .submitLabel(.search)
            .frame(maxWidth: platformValue(tv: 900, touch: 520))
            .padding(.horizontal, Theme.gutter)
        let term = seerrNormalizeSearch(query)
        if term.count < 2 {
            Text(term.isEmpty ? "" : "Enter at least 2 characters.").font(.callout).foregroundStyle(Theme.muted).padding(.horizontal, Theme.gutter)
        } else if let error = model.searchError {
            MessageView(symbol: "wifi.exclamationmark", title: "Search didn't work", message: SeerrText.error(error))
        } else if model.searched == term && model.results.isEmpty && !model.searching {
            MessageView(symbol: "magnifyingglass", title: "No results", message: "Seerr found nothing for “\(term)”.")
        } else if model.results.isEmpty {
            ProgressView().frame(maxWidth: .infinity).padding(.top, 40)
        } else {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: Theme.posterWidth, maximum: Theme.posterWidth), spacing: Theme.cardSpacing, alignment: .top)],
                      alignment: .leading, spacing: platformValue(tv: 40, touch: 20)) {
                ForEach(model.results, id: \.cardID) { SeerrCard(title: $0) }
            }
            .padding(.horizontal, Theme.gutter)
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }

    @ViewBuilder private var mine: some View {
        if let error = model.requestsError, model.requests.isEmpty {
            MessageView(symbol: "wifi.exclamationmark", title: "Couldn't load your requests", message: SeerrText.error(error), actionTitle: "Retry") {
                Task { await model.loadRequests(app) }
            }
        } else if !model.requestsLoaded {
            ProgressView("Loading…").frame(maxWidth: .infinity).padding(.top, 60)
        } else if model.requests.isEmpty {
            MessageView(symbol: "tray", title: "No requests yet", message: "You haven't requested anything yet.")
        } else {
            LazyVStack(alignment: .leading, spacing: platformValue(tv: 24, touch: 12)) {
                ForEach(model.requests) { SeerrRequestRowView(row: $0) }
            }
            .padding(.horizontal, Theme.gutter)
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }
}

/// One of the viewer's requests: poster, title, what was asked for and where it stands.
struct SeerrRequestRowView: View {
    let row: SeerrRequestRow

    var body: some View {
        let type = row.request.type == "tv" ? "tv" : "movie"
        NavigationLink(value: SeerrTitleRoute(type: type, id: row.request.media.tmdbId)) {
            HStack(alignment: .center, spacing: platformValue(tv: 28, touch: 14)) {
                SeerrPoster(title: row.title ?? SeerrTitle(id: row.request.media.tmdbId, mediaType: type), width: platformValue(tv: 90, touch: 56))
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                VStack(alignment: .leading, spacing: 4) {
                    Text(row.title?.label ?? (type == "tv" ? "Series" : "Movie")).font(.headline).lineLimit(1)
                    Text(detail).font(.subheadline).foregroundStyle(Theme.muted).lineLimit(1)
                }
                Spacer(minLength: 12)
                Text(SeerrText.status(seerrRequestDisplay(row.request)) ?? "Requested")
                    .font(.subheadline.weight(.semibold)).foregroundStyle(Theme.accent)
            }
            .padding(platformValue(tv: 20, touch: 10))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous).fill(Theme.surface.opacity(0.6)))
            .contentShape(Rectangle())
        }
        #if os(tvOS)
        .buttonStyle(.card)
        #else
        .buttonStyle(.plain)
        #endif
    }

    private var detail: String {
        let seasons = row.request.seasons.map(\.seasonNumber).sorted()
        var parts: [String] = []
        if let title = row.title { parts.append(SeerrText.line(title)) } else { parts.append(row.request.type == "tv" ? "Series" : "Movie") }
        if !seasons.isEmpty { parts.append(seasons.count == 1 ? "Season \(seasons[0])" : "Seasons " + seasons.map(String.init).joined(separator: ", ")) }
        return parts.filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

// MARK: - Title page (SEERR-06)

struct SeerrTitleView: View {
    @EnvironmentObject private var app: AppModel
    let route: SeerrTitleRoute
    @StateObject private var model = SeerrModel()
    #if os(iOS)
    @Environment(\.horizontalSizeClass) private var sizeClass
    private var sideBySide: Bool { sizeClass == .regular }
    #else
    private let sideBySide = true
    /// Focus starts on Request, as on Android TV, not on the first season row; with nothing to request, on Back.
    @FocusState private var requestFocused: Bool
    @FocusState private var backFocused: Bool
    @Environment(\.dismiss) private var dismiss
    #endif

    var body: some View {
        Group {
            if let title = model.title {
                page(title)
            } else if let error = model.titleError {
                MessageView(symbol: "wifi.exclamationmark", title: "Couldn't open this title", message: SeerrText.error(error), actionTitle: "Retry") {
                    Task { await model.openTitle(app, type: route.type, id: route.id) }
                }
            } else {
                ProgressView("Loading…").frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .pageBackground()
        #if os(iOS)
        .navigationTitle(model.title?.label ?? "")
        .navigationBarTitleDisplayMode(.inline)
        #endif
        .task { await model.openTitle(app, type: route.type, id: route.id) }
    }

    private func page(_ title: SeerrTitle) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: platformValue(tv: 40, touch: 20)) {
                if sideBySide {
                    HStack(alignment: .top, spacing: platformValue(tv: 60, touch: 24)) { poster(title); heading(title) }
                } else {
                    VStack(alignment: .leading, spacing: 16) { poster(title); heading(title) }
                }
                if title.mediaType == "tv" { seasons(title) }
            }
            .padding(.horizontal, Theme.gutter)
            .padding(.vertical, platformValue(tv: 40, touch: 20))
        }
        #if os(tvOS)
        .task(id: "\(title.cardID)|\(offersRequest(title))") {
            // Once the page has laid out, so the focus engine doesn't settle on the first row after us.
            try? await Task.sleep(for: .milliseconds(150))
            if offersRequest(title) { requestFocused = true } else { backFocused = true }
        }
        #endif
    }

    private func poster(_ title: SeerrTitle) -> some View {
        SeerrPoster(title: title, width: platformValue(tv: 300, touch: sideBySide ? 200 : 140))
            .clipShape(RoundedRectangle(cornerRadius: Theme.corner, style: .continuous))
    }

    private func heading(_ title: SeerrTitle) -> some View {
        VStack(alignment: .leading, spacing: platformValue(tv: 18, touch: 10)) {
            Text(title.label).font(Font.title2.weight(.bold)).fixedSize(horizontal: false, vertical: true)
            Text([SeerrText.line(title), SeerrText.status(title.status)].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.subheadline).foregroundStyle(Theme.muted)
            if let overview = title.overview, !overview.isEmpty {
                Text(overview).font(platformValue(tv: Font.callout, touch: Font.body)).foregroundStyle(.secondary)
                    .lineLimit(platformValue(tv: 6, touch: 8)).frame(maxWidth: 900, alignment: .leading)
            }
            action(title)
        }
    }

    @ViewBuilder private func action(_ title: SeerrTitle) -> some View {
        let user = app.seerr.config(app.account?.key)?.user
        VStack(alignment: .leading, spacing: 10) {
            if let user, !user.canRequest(title.mediaType) {
                note("Your Seerr account can't request this kind of title.")
            } else if title.mediaType == "movie" {
                if seerrMovieRequestable(title) {
                    Button { Task { await model.request(app) } } label: {
                        Label(model.sending ? "Requesting…" : "Request", systemImage: "plus")
                    }
                    .primaryAction()
                    .disabled(model.sending)
                    #if os(tvOS)
                    .focused($requestFocused)
                    #endif
                } else if !model.sent {
                    note("This is already requested or available.")
                }
            } else if seerrSelectableSeasons(title).isEmpty {
                // Right after our own request the confirmation below says it; this would repeat it.
                if !model.sent { note("Every season is already requested or available.") }
            } else {
                let count = seerrSelectableSeasons(title).filter(model.chosen.contains).count
                Button {
                    // On Apple TV the button stays focusable with nothing chosen and does nothing (SEERR-06).
                    if count > 0 { Task { await model.request(app) } }
                } label: {
                    Label(model.sending ? "Requesting…" : count == 0 ? "Choose the seasons to request." : "Request \(count) season\(count == 1 ? "" : "s")", systemImage: "plus")
                }
                .primaryAction()
                #if os(iOS)
                .disabled(model.sending || count == 0)
                #else
                .focused($requestFocused)
                #endif
            }
            if model.sent {
                Label("Requested. You'll find it in My requests.", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.accent)
            }
            if let error = model.sendError { note(SeerrText.error(error), warning: true) }
            #if os(tvOS)
            // As on Android TV: with nothing left to request the page would have nothing to focus, and Menu from the
            // tab bar leaves the app instead of going back.
            Button("Back") { dismiss() }
                .secondaryAction()
                .focused($backFocused)
            #endif
        }
        .padding(.top, 6)
    }

    /// Whether the page shows a Request button (a requestable movie, or a series with a season left to pick).
    private func offersRequest(_ title: SeerrTitle) -> Bool {
        if let user = app.seerr.config(app.account?.key)?.user, !user.canRequest(title.mediaType) { return false }
        return title.mediaType == "movie" ? seerrMovieRequestable(title) : !seerrSelectableSeasons(title).isEmpty
    }

    private func note(_ text: String, warning: Bool = false) -> some View {
        Text(text).font(.callout).foregroundStyle(warning ? Theme.warning : Theme.muted).fixedSize(horizontal: false, vertical: true)
    }

    @ViewBuilder private func seasons(_ title: SeerrTitle) -> some View {
        let taken = seerrTakenSeasons(title)
        let selectable = seerrSelectableSeasons(title)
        let shown = title.seasons.filter { $0.seasonNumber > 0 && $0.episodeCount > 0 }.sorted { $0.seasonNumber < $1.seasonNumber }
        if !shown.isEmpty {
            VStack(alignment: .leading, spacing: platformValue(tv: 12, touch: 6)) {
                SectionHeader(title: "Seasons")
                if !selectable.isEmpty {
                    seasonRow(label: "All seasons", detail: nil, on: model.chosen.isSuperset(of: Set(selectable)), enabled: !model.sending) { model.toggleAll() }
                }
                ForEach(shown, id: \.seasonNumber) { season in
                    let reason = taken[season.seasonNumber].flatMap(SeerrText.status)
                    let episodes = "\(season.episodeCount) episode\(season.episodeCount == 1 ? "" : "s")"
                    seasonRow(label: "Season \(season.seasonNumber)", detail: [episodes, reason].compactMap { $0 }.joined(separator: " · "),
                              on: model.chosen.contains(season.seasonNumber) && selectable.contains(season.seasonNumber),
                              enabled: selectable.contains(season.seasonNumber) && !model.sending) { model.toggleSeason(season.seasonNumber) }
                }
            }
            #if os(tvOS)
            .focusSection()
            #endif
        }
    }

    private func seasonRow(label: String, detail: String?, on: Bool, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: on ? "checkmark.circle.fill" : "circle")
                    // On Apple TV the mark takes the row's own colour, which flips to dark on the white focused row.
                    .foregroundStyle(platformValue(tv: AnyShapeStyle(.foreground), touch: AnyShapeStyle(on ? Theme.accent : Theme.muted)))
                    .font(Font.title3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.body.weight(.semibold))
                    if let detail, !detail.isEmpty { Text(detail).font(.caption).foregroundStyle(.secondary) }
                }
                Spacer(minLength: 0)
            }
            .padding(.vertical, platformValue(tv: 6, touch: 8))
            .frame(maxWidth: platformValue(tv: CGFloat(900), touch: CGFloat.infinity), alignment: .leading)
            .contentShape(Rectangle())
        }
        #if os(iOS)
        .buttonStyle(.plain)
        #else
        .buttonStyle(SeerrSeasonRowStyle())
        #endif
        .disabled(!enabled)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

#if os(tvOS)
/// A season row on Apple TV: the system bordered style fills with the app tint and hides the words, so rows are a
/// translucent panel, white with dark text when focused, like the app's action buttons.
private struct SeerrSeasonRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View { RowBody(configuration: configuration) }

    private struct RowBody: View {
        let configuration: ButtonStyleConfiguration
        @Environment(\.isFocused) private var focused
        @Environment(\.isEnabled) private var enabled
        var body: some View {
            configuration.label
                .padding(.horizontal, 24).padding(.vertical, 6)
                .foregroundStyle(focused ? Color.black : Color.white.opacity(enabled ? 1 : 0.55))
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(focused ? Color.white : Color.white.opacity(0.08)))
                .scaleEffect(configuration.isPressed ? 0.98 : (focused ? 1.03 : 1))
                .shadow(color: .black.opacity(focused ? 0.4 : 0), radius: 16, y: 8)
                .animation(.easeOut(duration: 0.15), value: focused)
        }
    }
}
#endif

// MARK: - Connect (SEERR-01)

struct SeerrConnectView: View {
    @EnvironmentObject private var app: AppModel
    @Environment(\.dismiss) private var dismiss
    /// tvOS: Connect returns to Settings, which puts focus back on its "Requests (Seerr)" row.
    var onConnected: () -> Void = {}
    @StateObject private var model = SeerrModel()
    @State private var address = ""
    @State private var useKey = false
    @State private var username = ""
    @State private var password = ""
    @State private var apiKey = ""

    var body: some View {
        Form {
            if let config = app.seerr.config(app.account?.key) {
                connected(config)
            } else {
                form
            }
        }
        #if os(iOS)
        .navigationTitle(pageTitle)
        .scrollContentBackground(.hidden)
        .pageBackground()
        #endif
        .onAppear { if username.isEmpty { username = app.account?.username ?? "" } }
    }

    private var pageTitle: String { app.seerr.config(app.account?.key) == nil ? "Connect to Seerr" : "Requests (Seerr)" }

    /// A section header that, on tvOS, also carries the page title: tvOS pins a navigation title while a Form scrolls
    /// under it, so (as in Settings, Libraries and Requests) the TV title is content and scrolls away with the page.
    @ViewBuilder private func header(_ text: String) -> some View {
        #if os(tvOS)
        VStack(alignment: .leading, spacing: 28) {
            Text(pageTitle).font(.title2.weight(.bold)).foregroundStyle(.primary).textCase(nil)
                .accessibilityAddTraits(.isHeader)
            Text(text)
        }
        #else
        Text(text)
        #endif
    }

    @ViewBuilder private var form: some View {
        Section {
            TextField("Seerr address", text: $address, prompt: Text("requests.example.com or seerr.home.example:5055"))
                #if os(iOS)
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                #endif
                .autocorrectionDisabled()
        } header: { header("Address") } footer: {
            Text("Jellyseerr and Overseerr let you ask for movies and series your server doesn't have yet. Enter the address you use to open it in a browser.")
        }
        Section {
            #if os(tvOS)
            // A Form picker on tvOS pushes a separate choice page; on Apple TV that page came up blank, so the TV
            // offers both ways in place, as two choice rows like Android TV's.
            methodRow("Sign in with Jellyfin", key: false)
            methodRow("Use an API key", key: true)
            #else
            Picker("Sign in with", selection: $useKey) {
                Text("Sign in with Jellyfin").tag(false)
                Text("Use an API key").tag(true)
            }
            #endif
            if useKey {
                SecureField("API key", text: $apiKey)
            } else {
                TextField("Jellyfin username", text: $username)
                    .textContentType(.username)
                    #if os(iOS)
                    .textInputAutocapitalization(.never)
                    #endif
                    .autocorrectionDisabled()
                SecureField("Jellyfin password", text: $password).textContentType(.password)
            }
        } footer: {
            Text(useKey ? "In Seerr: Settings, General, API Key. Requests are still made as you: your Jellyfin account must be imported into Seerr."
                 : "Saved in this device's Keychain so Pictureshow can renew the Seerr sign-in when it lapses.")
        }
        Section {
            Button {
                Task {
                    await model.connect(app, url: address, apiKey: useKey ? apiKey : nil, username: username, password: password)
                    #if os(tvOS)
                    // Connecting adds the Requests tab, which rebuilds the TV's top bar; with the Connect button gone,
                    // focus fell to that bar. Back on Settings, the row now says who is connected and holds focus.
                    if app.seerr.config(app.account?.key) != nil { onConnected(); dismiss() }
                    #endif
                }
            } label: {
                HStack {
                    Text(model.connecting ? "Connecting…" : "Connect")
                    if model.connecting { Spacer(); ProgressView() }
                }
            }
            .disabled(model.connecting || address.trimmingCharacters(in: .whitespaces).isEmpty || (useKey ? apiKey.isEmpty : username.isEmpty || password.isEmpty))
            if let error = model.connectError {
                Text(SeerrText.error(error)).font(.footnote).foregroundStyle(Theme.warning)
            }
        }
    }

    #if os(tvOS)
    private func methodRow(_ title: String, key: Bool) -> some View {
        Button { useKey = key } label: {
            HStack {
                Image(systemName: useKey == key ? "largecircle.fill.circle" : "circle").accessibilityHidden(true)
                Text(title)
                Spacer()
            }
        }
        .disabled(model.connecting)
        .accessibilityAddTraits(useKey == key ? .isSelected : [])
    }
    #endif

    @ViewBuilder private func connected(_ config: SeerrConfig) -> some View {
        Section {
            LabeledContent("Address", value: config.url)
            LabeledContent("Seerr user", value: config.user.name)
            LabeledContent("Signed in with", value: config.mode == .apiKey ? "API key" : "Jellyfin")
            if !config.user.canRequest("movie") && !config.user.canRequest("tv") {
                Text("This Seerr account can't make requests. Ask whoever runs Seerr to give it the Request permission.")
                    .font(.footnote).foregroundStyle(Theme.warning)
            }
        } header: {
            #if os(tvOS)
            header("Connection")
            #endif
        } footer: {
            Text("Requests now appears beside Home. Search also shows what Seerr can add.")
        }
        Section {
            Button(role: .destructive) {
                if let key = app.account?.key { app.seerr.remove(key) }
            } label: { DestructiveLabel("Disconnect") }
        } footer: {
            Text("Removes the Seerr sign-in from this device for \(app.account?.username ?? "this account"). Your requests stay on Seerr.")
        }
    }
}

/// Settings' "Requests (Seerr)" row: the connected Seerr user or "Not connected".
#if os(tvOS)
/// SEERR on Apple TV: after Connect, focus goes to Settings' "Requests (Seerr)" row. Connecting adds the Requests tab,
/// which rebuilds the top bar and can rebuild Settings, and the row may be off screen (not yet built), so the request
/// lives here: Settings scrolls the row in, and the row takes focus once it exists.
@MainActor final class SeerrRowFocus: ObservableObject {
    static let shared = SeerrRowFocus()
    static let rowID = "settings:seerr-row"
    @Published var wanted = false
}
#endif

struct SeerrSettingsSection: View {
    @EnvironmentObject private var app: AppModel
    #if os(tvOS)
    @FocusState private var rowFocused: Bool
    @ObservedObject private var rowFocus = SeerrRowFocus.shared
    #endif
    var body: some View {
        Section {
            #if os(tvOS)
            NavigationLink { SeerrConnectView { rowFocus.wanted = true } } label: { row }
                .focused($rowFocused)
                .id(SeerrRowFocus.rowID)
                .task(id: rowFocus.wanted) {
                    guard rowFocus.wanted else { return }
                    // The pop, the top bar's rebuild and Settings' scroll each run a system focus pass that can take focus
                    // back, so ask again until the row holds it. A page torn down mid-way leaves the request to its successor.
                    for _ in 0..<8 {
                        try? await Task.sleep(for: .milliseconds(400))
                        guard !Task.isCancelled else { return }
                        if rowFocused { break }
                        rowFocused = true
                    }
                    rowFocus.wanted = false
                }
            #else
            NavigationLink { SeerrConnectView() } label: { row }
            #endif
        } footer: {
            Text("Ask for movies and series that aren't in your library yet, with Jellyseerr or Overseerr.")
        }
    }

    private var row: some View {
        LabeledContent("Requests (Seerr)", value: app.seerr.config(app.account?.key).map { "Connected as \($0.user.name)" } ?? "Not connected")
            .accessibilityIdentifier("settings:seerr")
    }
}

// MARK: - Search's "Not in your library" (SEERR-07)

/// Seerr's movies and series for the same words, minus anything the library search found or Seerr says is available.
/// Absent when the account isn't connected, when Seerr fails, or when nothing is left.
struct SeerrOffersShelf: View {
    @EnvironmentObject private var app: AppModel
    let query: String
    let library: [MediaItem]
    @StateObject private var model = SeerrModel()

    var body: some View {
        let term = seerrNormalizeSearch(query)
        let offers = model.searched == term && model.searchError == nil
            ? seerrSearchOffers(model.results, library: library.map { (name: $0.name, year: $0.productionYear) }) : []
        // A real (1 pt) view even with nothing to show: an empty one never appears, so its search would never run.
        VStack(alignment: .leading, spacing: 0) {
            Color.clear.frame(height: 1)
            if app.seerr.config(app.account?.key) != nil && !offers.isEmpty {
                SeerrShelf(title: "Not in your library", titles: offers)
            }
        }
        .task(id: query) { await model.search(app, query: query) }
    }
}
