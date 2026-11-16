// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import PictureshowCore

/// Account-scoped fallback: a server outage never resets an acknowledged layout.
enum HomeLayoutCache {
    private static let key = "home-layouts.v1"
    static func load(defaults: UserDefaults = .standard) -> [String: HomePreferences] {
        guard let data = defaults.data(forKey: key) else { return [:] }
        return (try? JSONDecoder().decode([String: HomePreferences].self, from: data)) ?? [:]
    }
    static func pending(defaults: UserDefaults = .standard) -> Set<String> {
        Set(defaults.stringArray(forKey: "home-layouts.pending.v1") ?? [])
    }
    static func savePending(_ pending: Set<String>, defaults: UserDefaults = .standard) {
        defaults.set(Array(pending), forKey: "home-layouts.pending.v1")
    }
    static func save(_ layouts: [String: HomePreferences], defaults: UserDefaults = .standard) {
        if let data = try? JSONEncoder().encode(layouts) { defaults.set(data, forKey: key) }
    }
}

extension AppModel {
    var homeLayout: HomePreferences { account.flatMap { homeLayouts[$0.key] } ?? HomePreferences() }

    func loadHomeLayout(account: Account, client: JellyfinClient) async {
        guard !homeLayoutSaving, !homeLayoutPending.contains(account.key) else { return }
        let generation = homeLayoutGeneration
        do {
            let document = try await client.homePreferencesDocument(account: account)
            guard generation == homeLayoutGeneration, !homeLayoutSaving, !homeLayoutPending.contains(account.key), accounts.contains(where: { $0 == account }) else { return }
            homeLayouts[account.key] = Catalog.readHomePreferences(document: document) ?? homeLayouts[account.key] ?? HomePreferences()
            HomeLayoutCache.save(homeLayouts)
            homeLayoutError = nil
        } catch ClientError.invalidCredentials { expire(account) }
        catch is CancellationError {}
        catch { if self.account == account { homeLayoutError = error.localizedDescription } }
    }

    func setSeparateNextUp(_ separate: Bool) async {
        guard let account, !homeLayoutSaving else { return }
        homeLayoutGeneration += 1
        var updated = homeLayout
        updated.mergeWatching = !separate
        homeLayouts[account.key] = updated
        homeLayoutPending.insert(account.key)
        HomeLayoutCache.save(homeLayouts)
        HomeLayoutCache.savePending(homeLayoutPending)
        await retryHomeLayout()
    }

    func retryHomeLayout() async {
        guard let account, let client, !homeLayoutSaving else { return }
        guard homeLayoutPending.contains(account.key) else {
            await loadHomeLayout(account: account, client: client)
            return
        }
        homeLayoutGeneration += 1
        let generation = homeLayoutGeneration
        homeLayoutSaving = true
        homeLayoutError = nil
        defer { homeLayoutSaving = false }
        let desired = homeLayout
        do {
            // Merge just this switch into the freshest server layout; keep unrelated fields and client data.
            let before = try await client.homePreferencesDocument(account: account)
            guard generation == homeLayoutGeneration, accounts.contains(where: { $0 == account }) else { return }
            var updated = Catalog.readHomePreferences(document: before) ?? desired
            updated.mergeWatching = desired.mergeWatching
            let document = try Catalog.homePreferencesDocument(before: before, preferences: updated)
            try await client.saveHomePreferencesDocument(account: account, document: document)
            guard generation == homeLayoutGeneration, accounts.contains(where: { $0 == account }) else { return }
            homeLayouts[account.key] = updated
            homeLayoutPending.remove(account.key)
            HomeLayoutCache.save(homeLayouts)
            HomeLayoutCache.savePending(homeLayoutPending)
            catalogChanged()
        } catch ClientError.invalidCredentials { expire(account) }
        catch { if self.account == account { homeLayoutError = error.localizedDescription } }
    }

    /// Container playback uses fresh episodes and the same missing/unfinished policy as the page.
    func playContainer(_ item: MediaItem, account: Account, fromStart: Bool, stream: Bool) async {
        let client = client(for: account)
        do {
            let next = item.type == "Series" ? try await client.nextUp(account: account, seriesID: item.id) : []
            let episodes: [MediaItem]
            if item.type == "Season" {
                episodes = try await client.episodes(account: account, seriesID: item.seriesId ?? item.id, seasonID: item.id)
            } else if next.contains(where: { !$0.isMissing }) {
                episodes = []
            } else {
                episodes = try await client.seriesEpisodes(account: account, seriesID: item.id)
            }
            guard self.account == account, let target = Catalog.detailPlayItem(item: item, nextUp: next, episodes: episodes) else { return }
            play(target, fromStart: fromStart, stream: stream)
        } catch ClientError.invalidCredentials { expire(account) }
        catch { /* The caller remains on the catalog; no missing item becomes a player request. */ }
    }
}
