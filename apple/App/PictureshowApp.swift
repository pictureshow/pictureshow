// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore
import Security
import VideoToolbox
import CryptoKit

@main struct PictureshowApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene { WindowGroup { RootView().environmentObject(model).preferredColorScheme(.dark).tint(Theme.accent).controlSize(.large).minimumTouchListRows() } }
}

enum Theme {
    static let background = DesignTokens.background
    static let surface = DesignTokens.surface
    static let muted = DesignTokens.muted
    static let accent = DesignTokens.accent
    #if os(tvOS)
    static let gutter: CGFloat = 48
    static let cardWidth: CGFloat = 288
    static let wideCardWidth: CGFloat = 480
    #else
    static let gutter: CGFloat = 20
    static let cardWidth: CGFloat = 136
    static let wideCardWidth: CGFloat = 244
    #endif
}

extension Account {
    var localKey: String { server.url.absoluteString + "|" + userId }
}

struct AccountSnapshot: Codable {
    var accounts: [Account] = []
    var selected: String?
    var servers: [Server] = []
}

@MainActor final class AppModel: ObservableObject {
    @Published var account: Account?
    @Published var snapshot = AccountSnapshot()
    @Published var storageError: String?
    @Published var signInServer: Server?
    @Published var signInUsername = ""
    @Published var showSignIn = false
    @Published var sessionEnded = false
    @Published var rows: [HomeRow] = []
    @Published var homeError: String?
    @Published var loading = false
    @Published var player: PlaybackSession?
    private var activePlayback: PlaybackSession?
    private var dismissingPlayback: PlaybackSession?
    private var pendingPlaybackSave: PlaybackSession?
    @Published var playbackError: String?
    @Published var preparing = false
    @Published var playbackSaving = false
    @Published var playbackSaveError: String?
    @Published var refreshID = UUID()
    @Published var externalDetailID: String?
    let preferences = AppPreferences()
    let signInFlow = SignInFlow()
    private var accountGeneration = 0
    private var homeGeneration = 0
    private var migrationTask: Task<Void, Never>?
    private var authenticatedClient: JellyfinClient
    private let installationID: String
    var client: JellyfinClient { authenticatedClient }
    init() {
        let defaults = UserDefaults.standard
        let deviceID = defaults.string(forKey: "deviceID") ?? UUID().uuidString
        defaults.set(deviceID, forKey: "deviceID"); installationID = deviceID
        authenticatedClient = JellyfinClient(deviceID: deviceID, supportsHEVC: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC))
        restore()
    }
    func restore() {
        do {
            snapshot = try AccountVault.loadSnapshot()
            account = snapshot.accounts.first { $0.localKey == snapshot.selected && !$0.accessToken.isEmpty }
            if let account { authenticatedClient = makeClient(account.deviceID) }
            else if let expired = snapshot.accounts.first(where: { $0.localKey == snapshot.selected && $0.accessToken.isEmpty }) { beginSignIn(server: expired.server, username: expired.username); sessionEnded = true }
            storageError = nil
        } catch { storageError = error.localizedDescription }
    }
    func makeClient(_ id: String) -> JellyfinClient { JellyfinClient(deviceID: id, supportsHEVC: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)) }
    /// Every new authentication gets a fresh device identity. Saved viewers retain theirs.
    func beginSignIn(server: Server? = nil, username: String = "") {
        authenticatedClient = makeClient(UUID().uuidString)
        signInFlow.clear(); signInServer = server; signInUsername = username; showSignIn = true; sessionEnded = false
    }
    func signedIn(_ incoming: Account) throws {
        var changed = snapshot
        changed.accounts.removeAll { $0.localKey == incoming.localKey }; changed.accounts.append(incoming)
        changed.servers.removeAll { $0.url == incoming.server.url }; changed.servers.append(incoming.server)
        changed.selected = incoming.localKey
        try AccountVault.saveSnapshot(changed)
        snapshot = changed; accountGeneration += 1; pendingPlaybackSave = nil; playbackSaving = false; playbackSaveError = nil; account = incoming
        authenticatedClient = makeClient(incoming.deviceID)
        signInFlow.clear(); rows = []; homeError = nil; showSignIn = false; signInServer = nil; signInUsername = ""; sessionEnded = false
    }
    func select(_ saved: Account) async {
        if saved.accessToken.isEmpty { beginSignIn(server: saved.server, username: saved.username); return }
        do { var changed = snapshot; changed.selected = saved.localKey; try AccountVault.saveSnapshot(changed); snapshot = changed }
        catch { storageError = error.localizedDescription; return }
        accountGeneration += 1; homeGeneration += 1; pendingPlaybackSave = nil; playbackSaving = false; playbackSaveError = nil; account = saved; authenticatedClient = makeClient(saved.deviceID)
        rows = []; homeError = nil; showSignIn = false; refreshID = UUID(); await loadHome()
    }
    func signOut() async {
        guard let oldAccount = account else { return }
        do {
            var changed = snapshot; changed.accounts.removeAll { $0.localKey == oldAccount.localKey }; changed.selected = nil
            try AccountVault.saveSnapshot(changed); snapshot = changed
        } catch { storageError = error.localizedDescription; return }
        let oldClient = client
        accountGeneration += 1; homeGeneration += 1
        pendingPlaybackSave = nil; playbackSaving = false; playbackSaveError = nil
        account = nil; rows = []; homeError = nil; loading = false; sessionEnded = false; showSignIn = false
        try? await oldClient.logout(account: oldAccount)
    }
    func remove(account removed: Account? = nil, server: Server? = nil) throws {
        var changed = snapshot
        let forgotten = changed.accounts.filter { candidate in removed.map { candidate.localKey == $0.localKey } ?? (candidate.server.url == server?.url) }
        changed.accounts.removeAll { candidate in forgotten.contains { $0.localKey == candidate.localKey } }
        if let server { changed.servers.removeAll { $0.url == server.url } }
        if forgotten.contains(where: { $0.localKey == changed.selected }) { changed.selected = nil }
        try AccountVault.saveSnapshot(changed); snapshot = changed
        if forgotten.contains(where: { $0.localKey == account?.localKey }) { accountGeneration += 1; account = nil; rows = [] }
        for old in forgotten {
            SearchHistory.remove(account: old)
            Task { try? await makeClient(old.deviceID).logout(account: old) }
        }
    }
    func handle(_ error: Error, account failed: Account) {
        guard case ClientError.invalidCredentials = error, let current = account,
              current.localKey == failed.localKey, current.accessToken == failed.accessToken else { return }
        var changed = snapshot
        if let index = changed.accounts.firstIndex(where: { $0.localKey == failed.localKey && $0.accessToken == failed.accessToken }) { changed.accounts[index].accessToken = "" }
        changed.selected = failed.localKey
        do { try AccountVault.saveSnapshot(changed); snapshot = changed }
        catch { storageError = error.localizedDescription; return }
        accountGeneration += 1; homeGeneration += 1; account = nil; rows = []
        beginSignIn(server: failed.server, username: failed.username); sessionEnded = true
    }
    /// Older builds used an installation-wide identity. Move only that viewer to a new
    /// authorized session; a failed/disabled Quick Connect never destroys its working token.
    private func migrateLegacyIdentityIfNeeded(_ saved: Account) {
        guard migrationTask == nil, (saved.deviceID == installationID || snapshot.accounts.filter({ $0.deviceID == saved.deviceID }).count > 1) else { return }
        let key = "identity-retry-" + SHA256.hash(data: Data(saved.localKey.utf8)).map { String(format: "%02x", $0) }.joined()
        guard UserDefaults.standard.double(forKey: key) <= Date().timeIntervalSince1970 else { return }
        UserDefaults.standard.set(Date().addingTimeInterval(1800).timeIntervalSince1970, forKey: key)
        let generation = accountGeneration, freshClient = makeClient(UUID().uuidString), oldClient = client
        migrationTask = Task {
            defer {
                migrationTask = nil
                if let current = account, current.localKey != saved.localKey { migrateLegacyIdentityIfNeeded(current) }
            }
            do {
                let renewed = try await withThrowingTaskGroup(of: Account.self) { group in
                    group.addTask {
                        let attempt = try await freshClient.initiateQuickConnect(server: saved.server)
                        try await oldClient.authorizeQuickConnect(account: saved, code: attempt.code)
                        let result = try await freshClient.authenticateQuickConnect(server: saved.server, secret: attempt.secret)
                        if Task.isCancelled { try? await freshClient.logout(account: result); throw CancellationError() }
                        return result
                    }
                    group.addTask { try await Task.sleep(for: .seconds(3)); throw CancellationError() }
                    defer { group.cancelAll() }
                    guard let result = try await group.next() else { throw CancellationError() }; return result
                }
                guard generation == accountGeneration, account?.accessToken == saved.accessToken,
                      renewed.userId == saved.userId else { try? await freshClient.logout(account: renewed); return }
                var changed = snapshot
                guard let index = changed.accounts.firstIndex(where: { $0.localKey == saved.localKey }) else { try? await freshClient.logout(account: renewed); return }
                changed.accounts[index] = renewed
                do { try AccountVault.saveSnapshot(changed) }
                catch { try? await freshClient.logout(account: renewed); return }
                snapshot = changed; account = renewed; authenticatedClient = freshClient
                // Do not log out the old shared device: another saved viewer may still use it.
            } catch { /* Keep the accepted credential; the persisted deadline permits a later retry. */ }
        }
    }
    func loadHome() async {
        guard let account else { return }
        migrateLegacyIdentityIfNeeded(account)
        let accountVersion = accountGeneration; homeGeneration += 1; let requestVersion = homeGeneration
        loading = rows.isEmpty; homeError = nil
        defer { if accountGeneration == accountVersion && homeGeneration == requestVersion { loading = false } }
        do {
            try await client.validate(account: account)
            let content = try await client.homeContent(account: account)
            guard accountGeneration == accountVersion, homeGeneration == requestVersion else { return }
            rows = content.rows; homeError = content.warnings.isEmpty ? nil : content.warnings.joined(separator: "\n")
        } catch {
            guard accountGeneration == accountVersion, homeGeneration == requestVersion else { return }
            handle(error, account: account); homeError = error.localizedDescription
        }
    }
    func play(_ item: MediaItem, resume: Bool, audio: Int?, subtitle: Int?) async {
        guard let account, !preparing, !playbackSaving, player == nil, activePlayback == nil, dismissingPlayback == nil else { return }
        let accountVersion = accountGeneration; preparing = true; playbackError = nil
        defer { preparing = false }
        do {
            let session = PlaybackSession(client: client, account: account, item: item, preferences: preferences.value)
            try await session.prepare(startTicks: resume ? item.userData.playbackPositionTicks : 0, audio: audio, subtitle: subtitle)
            guard accountGeneration == accountVersion, !Task.isCancelled else { await session.stop(); return }
            activePlayback = session; player = session
        } catch is CancellationError { } catch { if accountGeneration == accountVersion { handle(error, account: account); playbackError = error.localizedDescription } }
    }
    func playerClosed(_ session: PlaybackSession) async {
        guard activePlayback?.id == session.id else { return }
        session.beginClosing(); activePlayback = nil; playbackSaving = true; playbackSaveError = nil
        if player?.id == session.id { dismissingPlayback = session; player = nil }
        refreshID = UUID(); let accountVersion = accountGeneration
        Task {
            await session.stop()
            guard accountGeneration == accountVersion else { return }
            playbackSaveError = session.reportError; pendingPlaybackSave = session.reportError == nil ? nil : session; playbackSaving = false; refreshID = UUID()
            await loadHome()
        }
    }
    func retryPlaybackSave() async {
        guard let session = pendingPlaybackSave, !playbackSaving else { return }
        let version = accountGeneration; playbackSaving = true
        await session.retrySaving()
        guard version == accountGeneration else { return }
        playbackSaveError = session.reportError
        if session.reportError == nil { pendingPlaybackSave = nil }
        playbackSaving = false; refreshID = UUID(); await loadHome()
    }
    func openPlaybackURL(_ url: URL) async {
        guard url.scheme == "pictureshow" else { return }
        guard let input = ExternalPlaybackRequest(url: url) else { playbackError = "Could not open this playback request."; return }
        guard let account else { return }
        let version = accountGeneration
        do {
            var item = try await client.item(account: account, id: input.itemID)
            guard version == accountGeneration, item.id.lowercased() == input.itemID, ["Movie", "Episode"].contains(item.type), !item.isFolder else { throw ClientError.invalidResponse }
            if let position = input.positionMs { item.userData.playbackPositionTicks = position * 10_000 }
            externalDetailID = item.id
            await play(item, resume: true, audio: nil, subtitle: nil)
        } catch { handle(error, account: account); playbackError = "This item is unavailable for the current account." }
    }
    func presentationDismissed() async {
        if dismissingPlayback != nil { dismissingPlayback = nil; return }
        if let activePlayback { await playerClosed(activePlayback) }
    }
}

/// One atomic secure snapshot keeps selection and viewer removals consistent across process death.
enum AccountVault {
    static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.pictureshow.account", kSecAttrAccount as String: "active"] }
    private static var snapshotQuery: [String: Any] { var q = query; q[kSecAttrAccount as String] = "accounts-v1"; return q }
    #if targetEnvironment(simulator)
    private static var simulatorURL: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("simulator-account.json") }
    private static var snapshotURL: URL { simulatorURL.deletingLastPathComponent().appendingPathComponent("simulator-accounts-v1.json") }
    #endif
    static func loadQuick() -> Data? {
        var q = snapshotQuery; q[kSecAttrAccount as String] = "quick-v1"
        #if targetEnvironment(simulator)
        return try? Data(contentsOf: snapshotURL.deletingLastPathComponent().appendingPathComponent("quick-v1.json"))
        #else
        return try? read(q)
        #endif
    }
    static func saveQuick(_ data: Data?) {
        var q = snapshotQuery; q[kSecAttrAccount as String] = "quick-v1"
        #if targetEnvironment(simulator)
        let url = snapshotURL.deletingLastPathComponent().appendingPathComponent("quick-v1.json")
        if let data { try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true); try? data.write(to: url, options: [.atomic, .completeFileProtectionUnlessOpen]) } else { try? FileManager.default.removeItem(at: url) }
        #else
        if let data { try? write(data, query: q) } else { SecItemDelete(q as CFDictionary) }
        #endif
    }
    static func loadSnapshot(simulatorDirectory: URL? = nil) throws -> AccountSnapshot {
        #if targetEnvironment(simulator)
        // Unsigned simulator builds have no Keychain entitlement. A missing file
        // means first launch, not an invitation to query an inaccessible Keychain.
        let snapshotFile = simulatorDirectory?.appendingPathComponent(snapshotURL.lastPathComponent) ?? snapshotURL
        let legacyFile = simulatorDirectory?.appendingPathComponent(simulatorURL.lastPathComponent) ?? simulatorURL
        if FileManager.default.fileExists(atPath: snapshotFile.path) { return try JSONDecoder().decode(AccountSnapshot.self, from: Data(contentsOf: snapshotFile)) }
        if FileManager.default.fileExists(atPath: legacyFile.path) { let legacy = try JSONDecoder().decode(Account.self, from: Data(contentsOf: legacyFile)); return AccountSnapshot(accounts: [legacy], selected: legacy.localKey, servers: [legacy.server]) }
        #else
        if let data = try read(snapshotQuery) { return try JSONDecoder().decode(AccountSnapshot.self, from: data) }
        if let data = try read(query) { let legacy = try JSONDecoder().decode(Account.self, from: data); return AccountSnapshot(accounts: [legacy], selected: legacy.localKey, servers: [legacy.server]) }
        #endif
        return AccountSnapshot()
    }
    static func saveSnapshot(_ value: AccountSnapshot) throws {
        let data = try JSONEncoder().encode(value)
        #if targetEnvironment(simulator)
        try FileManager.default.createDirectory(at: snapshotURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: snapshotURL, options: [.atomic, .completeFileProtectionUnlessOpen])
        // Retain the old simulator path for development harnesses, without treating it as authoritative.
        if let account = value.accounts.first(where: { $0.localKey == value.selected }) { try save(account) } else { remove() }
        #else
        try write(data, query: snapshotQuery)
        SecItemDelete(query as CFDictionary)
        #endif
    }
    private static func read(_ q: [String: Any]) throws -> Data? {
        var q = q; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?; let status = SecItemCopyMatching(q as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw StorageError.failed }
        return data
    }
    static func load() -> Account? {
        #if targetEnvironment(simulator)
        if let data = try? Data(contentsOf: simulatorURL) { return try? JSONDecoder().decode(Account.self, from: data) }
        #endif
        guard let data = try? read(query) else { return nil }
        return try? JSONDecoder().decode(Account.self, from: data)
    }
    static func save(_ account: Account) throws {
        let data = try JSONEncoder().encode(account)
        #if targetEnvironment(simulator)
        try FileManager.default.createDirectory(at: simulatorURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: simulatorURL, options: [.atomic, .completeFileProtectionUnlessOpen])
        #else
        try write(data, query: query)
        #endif
    }
    private static func write(_ data: Data, query: [String: Any]) throws {
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw StorageError.failed }
        } else if status != errSecSuccess { throw StorageError.failed }
    }
    static func remove() {
        SecItemDelete(query as CFDictionary)
        #if targetEnvironment(simulator)
        try? FileManager.default.removeItem(at: simulatorURL)
        #endif
    }
    enum StorageError: LocalizedError { case failed; var errorDescription: String? { "Could not read or save your sign-ins securely. Try again." } }
}

struct ExternalPlaybackRequest {
    let itemID: String
    let positionMs: Int64?
    init?(url: URL) {
        guard let c = URLComponents(url: url, resolvingAgainstBaseURL: false), c.scheme == "pictureshow", c.host == "play", c.user == nil, c.password == nil, c.port == nil, c.fragment == nil else { return nil }
        let parts = c.percentEncodedPath.split(separator: "/", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].isEmpty, let id = String(parts[1]).removingPercentEncoding,
              id.count == 32, id.unicodeScalars.allSatisfy({ CharacterSet(charactersIn: "0123456789abcdefABCDEF").contains($0) }) else { return nil }
        let query = c.queryItems ?? []; guard query.allSatisfy({ $0.name == "positionMs" }), query.count <= 1 else { return nil }
        if let value = query.first {
            guard let text = value.value, !text.isEmpty, text.utf8.allSatisfy({ (48...57).contains($0) }), let number = Int64(text), number <= Int64.max / 10_000 else { return nil }; positionMs = number
        } else { positionMs = nil }
        itemID = id.lowercased()
    }
}

enum AppRoute: Hashable, Codable {
    case detail(String), library(String, String), search, settings, accounts, information(String)
}

struct NavigationSnapshot: Codable { var viewer: String; var tab: Int; var paths: [[AppRoute]] }
enum NavigationMemory {
    static func read(viewer: String) -> NavigationSnapshot? {
        guard let data = UserDefaults.standard.data(forKey: "navigation-v1"), let record = try? JSONDecoder().decode(NavigationSnapshot.self, from: data), record.viewer == viewer else { return nil }; return record
    }
    static func save(viewer: String, tab: Int, paths: [[AppRoute]]) { if let data = try? JSONEncoder().encode(NavigationSnapshot(viewer: viewer, tab: tab, paths: paths)) { UserDefaults.standard.set(data, forKey: "navigation-v1") } }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var path: [AppRoute] = []
    var body: some View {
        Group {
            if let error = model.storageError {
                VStack(spacing: 24) { Text("Sign-in storage unavailable").font(.title); Text(error); Button { model.restore() } label: { Text("Retry").minimumTouchTarget() } }
                    .padding(Theme.gutter)
            } else if model.showSignIn { SignInView()
            } else if model.account != nil {
                #if os(iOS)
                TouchShell().id(model.account?.localKey)
                #else
                NavigationStack(path: $path) {
                    HomeView(open: open)
                        .navigationDestination(for: AppRoute.self) { route in destination(route, open: open) }
                }
                .id(model.account?.localKey)
                .task { if path.isEmpty, let viewer = model.account?.localKey, let saved = NavigationMemory.read(viewer: viewer) { path = saved.paths.first ?? [] }; await model.loadHome() }
                .onChange(of: path) { _, value in if let viewer = model.account?.localKey { NavigationMemory.save(viewer: viewer, tab: 0, paths: [value]) } }
                .onChange(of: path) { _, value in if value.isEmpty { Task { await model.loadHome() } } }
                #endif
            } else if model.showSignIn || model.snapshot.accounts.isEmpty { SignInView() }
            else { NavigationStack { AccountsView() } }
        }
        .background(Theme.background.ignoresSafeArea())
        .onOpenURL { url in Task { await model.openPlaybackURL(url) } }
        .onChange(of: model.externalDetailID) { _, id in if let id { path = [.detail(id)] } }
        #if DEBUG
        .onAppear {
            if model.account != nil, path.isEmpty, let item = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_DETAIL_ID"] { path = [.detail(item)] }
        }
        #endif
        .fullScreenCover(item: $model.player, onDismiss: { Task { await model.presentationDismissed() } }) { session in PlayerScreen(session: session) { await model.playerClosed(session) } }
        .alert("Playback unavailable", isPresented: Binding(get: { model.playbackError != nil }, set: { if !$0 { model.playbackError = nil } })) { Button("OK") { model.playbackError = nil } } message: { Text(model.playbackError ?? "") }
    }
    private func open(_ item: MediaItem) { path.append(item.isCatalogContainer ? .library(item.id, item.name) : .detail(item.id)) }
}

@ViewBuilder func destination(_ route: AppRoute, open: @escaping (MediaItem) -> Void) -> some View {
    switch route {
    case .detail(let id): DetailView(id: id, open: open)
    case .library(let id, let name): LibraryView(id: id, name: name, open: open)
    case .search: SearchView(open: open)
    case .settings: SettingsView()
    case .accounts: AccountsView()
    case .information(let kind): InformationView(kind: kind)
    }
}

#if os(iOS)
struct TouchShell: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.horizontalSizeClass) private var width
    @ScaledMetric(relativeTo: .body) private var railWidth: CGFloat = 220
    @State private var tab = 0
    @State private var paths: [[AppRoute]] = [[], [], [], []]
    private let names = ["Home", "Libraries", "Search", "Settings"]
    private let icons = ["house", "square.grid.2x2", "magnifyingglass", "gearshape"]
    var body: some View {
        Group {
            if width == .regular {
                HStack(spacing: 0) {
                    VStack(spacing: 24) {
                        ForEach(0..<4) { index in
                            Button { tab = index } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: icons[index]).frame(minWidth: 24).accessibilityHidden(true)
                                    Text(names[index]).lineLimit(1).fixedSize(horizontal: true, vertical: false)
                                }.frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.vertical, 8).padding(.horizontal, 4).minimumTouchTarget()
                            }.buttonStyle(.bordered).tint(tab == index ? Theme.accent : Theme.muted)
                                .accessibilityLabel(names[index]).accessibilityValue(tab == index ? "Selected" : "")
                        }
                        Spacer()
                    }.padding(16).frame(width: railWidth).background(Theme.surface)
                    stack(tab)
                }
            } else {
                TabView(selection: $tab) { ForEach(0..<4) { index in stack(index).tabItem { Label(names[index], systemImage: icons[index]) }.tag(index) } }
            }
        }
        .task {
            if let viewer = model.account?.localKey, let saved = NavigationMemory.read(viewer: viewer), saved.paths.count == 4 { paths = saved.paths; tab = min(3,max(0,saved.tab)) }
            await model.loadHome()
        }
        .onChange(of: model.externalDetailID) { _, id in if let id { tab = 0; paths[0] = [.detail(id)] } }
        .onChange(of: paths) { _, paths in if let viewer = model.account?.localKey { NavigationMemory.save(viewer: viewer, tab: tab, paths: paths) } }
        .onChange(of: tab) { _, tab in if let viewer = model.account?.localKey { NavigationMemory.save(viewer: viewer, tab: tab, paths: paths) } }
        #if DEBUG
        .onAppear { if let id = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_DETAIL_ID"], paths[0].isEmpty { paths[0] = [.detail(id)] } }
        #endif
    }
    private func stack(_ index: Int) -> some View {
        NavigationStack(path: $paths[index]) {
            Group {
                switch index {
                case 1: LibrariesView(open: { open($0, tab: index) })
                case 2: SearchView(open: { open($0, tab: index) })
                case 3: SettingsView()
                default: HomeView(open: { open($0, tab: index) }, showSearch: { tab = 2 }, showSettings: { tab = 3 })
                }
            }
            .navigationDestination(for: AppRoute.self) { route in destination(route, open: { open($0, tab: index) }) }
        }
    }
    private func open(_ item: MediaItem, tab index: Int) { paths[index].append(item.isCatalogContainer ? .library(item.id, item.name) : .detail(item.id)) }
}
#endif
