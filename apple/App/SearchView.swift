// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore
import CryptoKit

private func normalizedSearch(_ input: String) -> String { String(input.trimmingCharacters(in: .whitespacesAndNewlines).prefix(200)).trimmingCharacters(in: .whitespacesAndNewlines) }

enum SearchHistory {
    private static func url(account: Account) -> URL {
        let key = SHA256.hash(data: Data(account.localKey.utf8)).map { String(format: "%02x", $0) }.joined()
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("search-history").appendingPathComponent(key + ".json")
    }
    static func read(account: Account) throws -> [String] { let url = url(account: account); if !FileManager.default.fileExists(atPath: url.path) { return [] }; return try JSONDecoder().decode([String].self, from: Data(contentsOf: url)) }
    static func save(_ values: [String], account: Account) throws { let url = url(account: account); try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true); try JSONEncoder().encode(values).write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen]) }
    static func remove(account: Account) { try? FileManager.default.removeItem(at: url(account: account)) }
}

@MainActor private final class SearchState: ObservableObject {
    @Published var query = ""
    @Published var groups: [SearchKind:[MediaItem]] = [:]
    @Published var errors: [String] = []
    @Published var loading = false
    @Published var recents: [String] = []
    @Published var storageError: String?
    private var generation = UUID()
    func loadHistory(_ account: Account) { do { recents = try SearchHistory.read(account: account); storageError = nil } catch { storageError = "Recent searches could not be loaded. Search is still available." } }
    func remember(_ account: Account) { let term = normalizedSearch(query); guard term.count >= 2 else { return }; var values = recents.filter { $0.caseInsensitiveCompare(term) != .orderedSame }; values.insert(term, at: 0); values = Array(values.prefix(10)); do { try SearchHistory.save(values, account: account); recents = values; storageError = nil } catch { storageError = "This search could not be saved to recent searches." } }
    func clearHistory(_ account: Account) { do { try SearchHistory.save([], account: account); recents = []; storageError = nil } catch { storageError = "Recent searches could not be cleared. Try again." } }
    func search(model: AppModel, delay: Bool = true) async {
        generation = UUID(); let expected = generation; let term = normalizedSearch(query)
        guard term.count >= 2, let account = model.account else { groups = [:]; errors = []; loading = false; return }
        loading = true; errors = []
        defer { if generation == expected { loading = false } }
        do {
            if delay { try await Task.sleep(for: .milliseconds(300)) }
            let result = await withTaskGroup(of: (SearchKind, [MediaItem]?, String?).self, returning: [(SearchKind,[MediaItem]?,String?)].self) { group in
                for kind in SearchKind.allCases { group.addTask { do { return (kind, try await model.client.search(account: account, query: term, kind: kind).items, nil) } catch { await model.handle(error, account: account); return (kind, nil, error.localizedDescription) } } }
                var output: [(SearchKind,[MediaItem]?,String?)] = []; for await result in group { output.append(result) }; return output
            }
            guard !Task.isCancelled, generation == expected, term == normalizedSearch(query), model.account?.localKey == account.localKey else { return }
            groups = Dictionary(uniqueKeysWithValues: result.compactMap { kind, items, _ in items.map { (kind,$0) } })
            errors = SearchKind.allCases.compactMap { kind in result.first(where: { $0.0 == kind })?.2.map { "\(kind.title): \($0)" } }
        } catch is CancellationError { } catch { if generation == expected { errors = [error.localizedDescription] } }
    }
}

struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    var open: (MediaItem) -> Void
    @StateObject private var state = SearchState()
    @FocusState private var fieldFocused: Bool
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack {
                    TextField(text: $state.query, prompt: Text("Search movies, shows and people").foregroundStyle(Theme.muted)) { Text("Search") }.autocorrectionDisabled().submitLabel(.search).focused($fieldFocused).accessibilityLabel("Search").accessibilityIdentifier("searchField")
                        #if os(iOS)
                        .textInputAutocapitalization(.never).textFieldStyle(.plain)
                        .padding(12).frame(minHeight: 48).background(Theme.surface, in: RoundedRectangle(cornerRadius: 8))
                        .overlay { RoundedRectangle(cornerRadius: 8).stroke(Theme.muted.opacity(0.6), lineWidth: 1) }
                        #endif
                        .onSubmit { fieldFocused = false; if let account = model.account { state.remember(account) } }
                    if !state.query.isEmpty { Button { state.query = ""; fieldFocused = true } label: { Label("Clear", systemImage: "xmark.circle.fill").minimumTouchTarget() }.accessibilityIdentifier("clearSearch") }
                }.padding(.horizontal, Theme.gutter)
                if let error = state.storageError { Text(error).foregroundStyle(.orange).padding(.horizontal, Theme.gutter) }
                if normalizedSearch(state.query).count < 2 {
                    Text("Enter at least 2 characters to search.").foregroundStyle(Theme.muted).padding(.horizontal, Theme.gutter)
                    if !state.recents.isEmpty { Text("Recent searches").font(.title2).accessibilityAddTraits(.isHeader).padding(.horizontal, Theme.gutter); ForEach(state.recents, id: \.self) { term in Button { state.query = term; fieldFocused = false } label: { Text(term).minimumTouchTarget() }.buttonStyle(.bordered).padding(.horizontal, Theme.gutter) }; Button { if let account = model.account { state.clearHistory(account) } } label: { Text("Clear recent searches").minimumTouchTarget() }.padding(.horizontal, Theme.gutter) }
                } else {
                    if state.loading { ProgressView("Searching…").padding(.horizontal, Theme.gutter) }
                    if !state.errors.isEmpty { ErrorNotice(error: state.errors.joined(separator: "\n")) { Task { await state.search(model: model, delay: false) } }.padding(.horizontal, Theme.gutter) }
                    if !state.loading && state.errors.isEmpty && state.groups.values.allSatisfy(\.isEmpty) { Text("No results for “\(normalizedSearch(state.query))”.").padding(.horizontal, Theme.gutter) }
                    ForEach(SearchKind.allCases, id: \.rawValue) { kind in if let items = state.groups[kind], !items.isEmpty { CatalogShelf(title: kind.title, items: items, wide: kind == .episodes, open: { item in if let account = model.account { state.remember(account) }; open(item) }) } }
                }
            }.padding(.vertical, 24)
        }.pictureshowNavigationTitle("Search").background(Theme.background.ignoresSafeArea())
            .task(id: state.query) { await state.search(model: model) }
            .task { if let account = model.account { state.loadHistory(account) } }
    }
}
