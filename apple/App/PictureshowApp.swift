// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import PictureshowCore
import Security
import VideoToolbox

@main
struct PictureshowApp: App {
    @StateObject private var model = AppModel()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
                .preferredColorScheme(.dark).tint(Theme.accent)
        }
    }
}

enum Theme {
    static let background = DesignTokens.background
    static let surface = DesignTokens.surface
    static let muted = DesignTokens.muted
    static let accent = DesignTokens.accent
    #if os(tvOS)
    static let gutter: CGFloat = 48
    static let cardWidth: CGFloat = 144
    static let wideCardWidth: CGFloat = 240
    #else
    static let gutter: CGFloat = 20
    static let cardWidth: CGFloat = 136
    static let wideCardWidth: CGFloat = 244
    #endif
}

@MainActor final class AppModel: ObservableObject {
    @Published var account: Account?
    @Published var rows: [HomeRow] = []
    @Published var homeError: String?
    @Published var loading = false
    @Published var player: PlaybackSession?
    private var activePlayback: PlaybackSession?
    private var dismissingPlayback: PlaybackSession?
    @Published var playbackError: String?
    @Published var preparing = false
    @Published var refreshID = UUID()
    private var accountGeneration = 0
    private var homeGeneration = 0
    let client: JellyfinClient
    init() {
        let defaults = UserDefaults.standard
        let deviceID = defaults.string(forKey: "deviceID") ?? UUID().uuidString
        defaults.set(deviceID, forKey: "deviceID")
        client = JellyfinClient(deviceID: deviceID, supportsHEVC: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC))
        account = AccountVault.load()
    }
    func signedIn(_ account: Account) throws {
        try AccountVault.save(account)
        accountGeneration += 1
        self.account = account
    }
    func signOut() async {
        let oldAccount = account
        accountGeneration += 1
        homeGeneration += 1
        AccountVault.remove()
        account = nil; rows = []; homeError = nil; loading = false
        if let oldAccount { try? await client.logout(account: oldAccount) }
    }
    func loadHome() async {
        guard let account else { return }
        let accountVersion = accountGeneration
        homeGeneration += 1
        let requestVersion = homeGeneration
        loading = rows.isEmpty; homeError = nil
        defer { if accountGeneration == accountVersion && homeGeneration == requestVersion { loading = false } }
        do {
            let content = try await client.homeContent(account: account)
            guard accountGeneration == accountVersion, homeGeneration == requestVersion else { return }
            rows = content.rows
            homeError = content.warnings.isEmpty ? nil : content.warnings.joined(separator: "\n")
        } catch {
            if accountGeneration == accountVersion && homeGeneration == requestVersion { homeError = error.localizedDescription }
        }
    }
    func play(_ item: MediaItem, resume: Bool, audio: Int?, subtitle: Int?) async {
        guard let account, !preparing, player == nil, activePlayback == nil, dismissingPlayback == nil else { return }
        let accountVersion = accountGeneration
        preparing = true; playbackError = nil
        defer { preparing = false }
        do {
            let session = PlaybackSession(client: client, account: account, item: item)
            try await session.prepare(startTicks: resume ? item.userData.playbackPositionTicks : 0, audio: audio, subtitle: subtitle)
            guard accountGeneration == accountVersion, !Task.isCancelled else { await session.stop(); return }
            activePlayback = session
            player = session
        } catch is CancellationError { } catch { if accountGeneration == accountVersion { playbackError = error.localizedDescription } }
    }
    func playerClosed(_ session: PlaybackSession) async {
        guard activePlayback?.id == session.id else { return }
        session.beginClosing()
        activePlayback = nil
        if player?.id == session.id { dismissingPlayback = session; player = nil }
        refreshID = UUID()
        let accountVersion = accountGeneration
        // Server reporting can finish after the player is gone, even on a slow connection.
        Task {
            await session.stop()
            guard accountGeneration == accountVersion else { return }
            refreshID = UUID()
            await loadHome()
            if let warning = session.reportError { homeError = warning }
        }
    }
    func presentationDismissed() async {
        if dismissingPlayback != nil { dismissingPlayback = nil; return }
        if let activePlayback { await playerClosed(activePlayback) }
    }

}

/// The token is stored with the account in the device Keychain, never UserDefaults.
enum AccountVault {
    static var query: [String: Any] { [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "app.pictureshow.account", kSecAttrAccount as String: "active"] }
    #if targetEnvironment(simulator)
    // Unsigned simulator builds have no Keychain access-group entitlement. Keep their
    // test credentials in this app's protected sandbox; device builds use only Keychain.
    private static var simulatorURL: URL { FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("simulator-account.json") }
    #endif
    static func load() -> Account? {
        #if targetEnvironment(simulator)
        if let data = try? Data(contentsOf: simulatorURL), let account = try? JSONDecoder().decode(Account.self, from: data) { return account }
        #endif
        var q = query; q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data else { return nil }
        return try? JSONDecoder().decode(Account.self, from: data)
    }
    static func save(_ account: Account) throws {
        let data = try JSONEncoder().encode(account)
        #if targetEnvironment(simulator)
        try FileManager.default.createDirectory(at: simulatorURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: simulatorURL, options: [.atomic, .completeFileProtectionUnlessOpen])
        #else
        let update = [kSecValueData as String: data]
        let status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var q = query; q[kSecValueData as String] = data; q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(q as CFDictionary, nil) == errSecSuccess else { throw StorageError.failed }
        } else if status != errSecSuccess { throw StorageError.failed }
        #endif
    }
    static func remove() {
        SecItemDelete(query as CFDictionary)
        #if targetEnvironment(simulator)
        try? FileManager.default.removeItem(at: simulatorURL)
        #endif
    }
    enum StorageError: LocalizedError { case failed; var errorDescription: String? { "Could not save your sign-in securely. Try again." } }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @State private var path: [String] = []
    var body: some View {
        Group {
            if model.account != nil {
                NavigationStack(path: $path) {
                    HomeView(open: { path.append($0.id) })
                        .navigationDestination(for: String.self) { id in DetailView(id: id, open: { path.append($0.id) }) }
                }
                .task { await model.loadHome() }
                .onChange(of: path) { _, value in if value.isEmpty { Task { await model.loadHome() } } }
            } else { SignInView() }
        }
        .background(Theme.background.ignoresSafeArea())
        #if DEBUG
        .onAppear {
            if model.account != nil, path.isEmpty,
               let item = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_DETAIL_ID"] { path = [item] }
        }
        #endif
        .fullScreenCover(item: $model.player, onDismiss: { Task { await model.presentationDismissed() } }) { session in
            PlayerScreen(session: session) { await model.playerClosed(session) }
        }
        .alert("Playback unavailable", isPresented: Binding(get: { model.playbackError != nil }, set: { if !$0 { model.playbackError = nil } })) {
            Button("OK") { model.playbackError = nil }
        } message: { Text(model.playbackError ?? "") }
    }
}
