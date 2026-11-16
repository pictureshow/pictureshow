// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import SwiftUI
import VideoToolbox
import PictureshowCore

/// App-wide state: who is signed in, the clients that talk to their servers, and local preferences.
@MainActor final class AppModel: ObservableObject {
    @Published private(set) var snapshot: AccountSnapshot
    /// Bumped whenever something the viewer did may change what the catalog shows
    /// (a stopped playback, a watched or favourite toggle), so visible screens refetch.
    @Published private(set) var catalogRevision = 0
    @Published var preferences: Preferences { didSet { preferences.save() } }
    /// Set while the viewer is adding another account, so the sign-in flow shows over the app.
    @Published var addingAccount = false
    @Published var player: PlayerRequest?

    private let store: AccountStore
    private var clients: [String: JellyfinClient] = [:]

    init(store: AccountStore = AccountStore()) {
        self.store = store
        snapshot = store.load()
        preferences = Preferences.load()
        Task.detached(priority: .utility) { await AppleHEVCQualification.shared.warmUp() }
    }

    var account: Account? { AccountBook.selected(in: snapshot) }
    var accounts: [Account] { snapshot.accounts }
    var servers: [Server] { snapshot.servers }

    /// One client per viewer, carrying that viewer's device id.
    func client(for account: Account) -> JellyfinClient {
        if let client = clients[account.key] { return client }
        let client = Self.makeClient(deviceID: account.deviceID)
        clients[account.key] = client
        return client
    }

    var client: JellyfinClient? { account.map { client(for: $0) } }

    static func makeClient(deviceID: String) -> JellyfinClient {
        JellyfinClient(deviceID: deviceID, supportsHEVC: VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC),
                       deviceName: DeviceIdentity.name, version: AppInfo.version,
                       hevcTranscodeCapabilities: { await AppleHEVCQualification.shared.capabilities() })
    }

    func signedIn(_ account: Account) {
        clients[account.key] = nil
        update(AccountBook.signIn(account, into: snapshot))
        addingAccount = false
        signingInAgain = nil
    }

    /// A signed-out card the viewer chose: sign-in opens for its server with the username filled.
    @Published var signingInAgain: Account?

    func remember(_ server: Server) { update(AccountBook.remember(server, in: snapshot)) }

    func select(_ account: Account) {
        guard account.isSignedIn else { signingInAgain = account; return }
        var next = snapshot
        next.selected = account.key
        update(next)
    }

    /// Ends the session on the server when it can, but never waits on it: a server that is
    /// unreachable must not keep a viewer signed in on this device.
    func signOut(_ account: Account) {
        guard account.isSignedIn else { return }
        let client = client(for: account)
        Task { try? await client.logout(account: account) }
        clients[account.key] = nil
        update(AccountBook.signOut(account.key, from: snapshot))
    }

    /// Forgets the viewer's card entirely, ending its session first if it still has one.
    func remove(_ account: Account) {
        if account.isSignedIn { signOut(account) }
        clients[account.key] = nil
        update(AccountBook.remove(account.key, from: snapshot))
    }

    func forget(_ server: Server) {
        for account in snapshot.accounts where account.server.url == server.url && account.isSignedIn { signOut(account) }
        update(AccountBook.forget(server: server.url, from: snapshot))
    }

    /// The server rejected a token: drop it, keeping the card, so the viewer signs in again instead of seeing empty screens.
    func expire(_ account: Account) {
        clients[account.key] = nil
        update(AccountBook.signOut(account.key, from: snapshot))
    }

    func catalogChanged() { catalogRevision += 1 }

    func play(_ item: MediaItem, fromStart: Bool = false) {
        guard let account else { return }
        player = PlayerRequest(account: account, item: item, fromStart: fromStart)
    }

    /// Set when the sign-in couldn't be stored securely; the app shows it with a Retry.
    @Published var storageError: String?

    private func update(_ next: AccountSnapshot) {
        snapshot = next
        persist()
    }

    func persist() {
        do { try store.save(snapshot); storageError = nil }
        catch { storageError = error.localizedDescription }
    }
}

struct PlayerRequest: Identifiable {
    let id = UUID()
    let account: Account
    let item: MediaItem
    let fromStart: Bool
}

enum AppInfo {
    static var version: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0" }
    static var build: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1" }
}

enum Appearance: String, Codable, CaseIterable, Identifiable {
    case system, dark, light
    var id: String { rawValue }
    var title: String { switch self { case .system: "System"; case .dark: "Dark"; case .light: "Light" } }
    var scheme: ColorScheme? {
        #if os(tvOS)
        return .dark
        #else
        switch self { case .system: nil; case .dark: .dark; case .light: .light }
        #endif
    }
}

/// Local settings. Playback ones reuse the shared core type so the server request is built the same way
/// on every platform; the rest are presentation choices that stay on this device.
struct Preferences: Codable, Equatable {
    var playback = PlaybackPreferences()
    var autoplayNextEpisode = true
    var appearance: Appearance = .system
    var skipBackSeconds = 10
    var skipForwardSeconds = 10

    private static let key = "preferences.v1"
    static func load() -> Preferences {
        guard let data = UserDefaults.standard.data(forKey: key),
              let value = try? JSONDecoder().decode(Preferences.self, from: data) else { return Preferences() }
        return value
    }
    func save() {
        if let data = try? JSONEncoder().encode(self) { UserDefaults.standard.set(data, forKey: Self.key) }
    }
}
