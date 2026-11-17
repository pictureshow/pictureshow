// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore

/// Search across the viewer's libraries, grouped by kind, as they type.
struct SearchView: View {
    @EnvironmentObject private var app: AppModel
    @State private var query = ""
    @State private var results: [SearchKind: [MediaItem]] = [:]
    @State private var searching = false
    @State private var failed: String?

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: Theme.shelfSpacing) {
                if query.nilIfBlank == nil {
                    MessageView(symbol: "magnifyingglass", title: "Search your library", message: "Find movies, shows, episodes, collections, people and music.")
                        .padding(.top, 60)
                } else if let failed {
                    MessageView(symbol: "wifi.exclamationmark", title: "Search didn't work", message: failed)
                } else if !searching && results.values.allSatisfy(\.isEmpty) {
                    MessageView(symbol: "magnifyingglass", title: "No results", message: "Nothing matches “\(query)”.")
                        .padding(.top, 60)
                    SeerrOffersShelf(query: query, library: [])
                } else {
                    ForEach(SearchKind.allCases, id: \.self) { kind in
                        if let items = results[kind], !items.isEmpty {
                            if kind == .people {
                                CastShelf(people: items.map { PersonCredit(id: $0.id, name: $0.name) }).environmentObject(app)
                            } else {
                                // MUS-01: music groups use square covers and open the music screens.
                                Shelf(title: kind.title, items: items, wide: kind == .episodes, square: kind.isMusic)
                            }
                        }
                    }
                    // SEERR-07: what Seerr can add, after what the library already has.
                    SeerrOffersShelf(query: query, library: (results[.movies] ?? []) + (results[.shows] ?? []))
                }
            }
            .padding(.vertical, 20)
            // The last row's captions sit below its focused cards; keep them clear of the mini player on TV.
            .padding(.bottom, platformValue(tv: 80, touch: 0))
        }
        .pageBackground()
        .navigationTitle("Search")
        .searchable(text: $query, prompt: "Movies, shows, people, music")
        .task(id: query) { await search() }
    }

    private func search() async {
        guard let text = query.nilIfBlank, let account = app.account, let client = app.client else { results = [:]; failed = nil; return }
        // Debounce typing so each keystroke doesn't fire five requests.
        try? await Task.sleep(for: .milliseconds(300))
        guard !Task.isCancelled else { return }
        searching = true
        defer { searching = false }
        var found: [SearchKind: [MediaItem]] = [:]
        var lastError: Error?
        await withTaskGroup(of: (SearchKind, Result<[MediaItem], Error>).self) { group in
            for kind in SearchKind.allCases {
                group.addTask {
                    do { return (kind, .success(try await client.search(account: account, query: text, kind: kind).items)) }
                    catch { return (kind, .failure(error)) }
                }
            }
            for await (kind, result) in group {
                switch result {
                case .success(let items): found[kind] = items
                case .failure(let error): lastError = error
                }
            }
        }
        guard !Task.isCancelled else { return }
        results = found
        failed = found.isEmpty ? lastError?.localizedDescription : nil
    }
}
