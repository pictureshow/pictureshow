// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Security
import PictureshowCore

/// What one account keeps for Seerr: the connection (address and Seerr user) and its secret.
struct SeerrSaved: Codable, Equatable {
    var config: SeerrConfig
    var secret: SeerrSecret
}

/// Each Pictureshow account's Seerr connection (SEERR-01, SEERR-02).
///
/// The whole connection, secret included, lives in one Keychain item per account (this-device-only, after first
/// unlock), keyed by the account; never in a file or in defaults. When the Keychain refuses for any reason but a
/// missing entitlement, connecting fails closed and nothing is kept. An unsigned simulator build has no Keychain
/// entitlement at all; only then the connection is held in memory until the app quits, and still never written.
@MainActor final class SeerrConnections: ObservableObject {
    /// The non-secret half for every account that connected, keyed by `Account.key`.
    @Published private(set) var configs: [String: SeerrConfig] = [:]

    var keychain: KeychainBackend
    var transport: SeerrTransport
    /// Connections that couldn't reach the Keychain (missing entitlement only); gone when the app quits.
    private var memory: [String: SeerrSaved] = [:]
    private var saved: [String: SeerrSaved] = [:]
    private var clients: [String: SeerrClient] = [:]
    /// Bumped whenever an account's connection is removed, so a sign-in or renewal still in flight can't bring it back.
    private var generations: [String: Int] = [:]

    init(keychain: KeychainBackend = SystemKeychain(), transport: @escaping SeerrTransport = SeerrHTTP.live) {
        self.keychain = keychain
        self.transport = transport
    }

    nonisolated static func service(_ key: String) -> String { "app.pictureshow.seerr|" + key }

    /// Reads the saved connections of these accounts. A corrupt or unreadable entry reads as not connected.
    func load(keys: [String]) {
        var next: [String: SeerrSaved] = [:]
        for key in keys {
            let (data, status) = keychain.read(service: Self.service(key))
            if let data, let value = try? JSONDecoder().decode(SeerrSaved.self, from: data) { next[key] = value }
            else if status == errSecMissingEntitlement, let value = memory[key] { next[key] = value }
        }
        saved = next
        configs = next.mapValues(\.config)
        clients = clients.filter { next[$0.key]?.config == $0.value.config }
    }

    func config(_ key: String?) -> SeerrConfig? { key.flatMap { configs[$0] } }

    /// Signs in (Jellyfin name and password) or links the API key to the viewer's own Seerr user, checks who Seerr
    /// says that is, and only then saves the connection for `account`. `stillSignedIn` is asked after Seerr answers:
    /// a sign-in that finishes after the account signed out is dropped.
    func connect(account: Account, url: String, apiKey: String?, username: String, password: String,
                 stillSignedIn: () -> Bool) async throws -> SeerrConfig {
        let key = account.key, generation = generations[key, default: 0]
        let result = try await SeerrClient.connect(url: url, apiKey: apiKey, username: username, password: password,
                                                   jellyfinUserId: account.userId, transport: transport)
        guard stillSignedIn(), generations[key, default: 0] == generation else { throw SeerrError.expired }
        clients[key] = nil
        try store(SeerrSaved(config: result.config, secret: result.secret), key: key)
        return result.config
    }

    /// Disconnect, or the account signed out or was forgotten: only this account's connection goes.
    func remove(_ key: String) {
        generations[key, default: 0] += 1
        keychain.delete(service: Self.service(key))
        memory[key] = nil
        saved[key] = nil
        clients[key] = nil
        configs[key] = nil
    }

    /// The client for this account's saved connection, or nil when it hasn't connected Seerr.
    func client(_ key: String) -> SeerrClient? {
        if let client = clients[key] { return client }
        guard let entry = saved[key] else { return nil }
        let generation = generations[key, default: 0]
        let client = SeerrClient(config: entry.config, secret: entry.secret, transport: transport) { [weak self] used, renewed in
            await self?.renewed(key, generation: generation, used: used, renewed: renewed)
        }
        clients[key] = client
        return client
    }

    /// A renewal that finishes after Disconnect or sign-out, or after another renewal, can't bring an old one back.
    private func renewed(_ key: String, generation: Int, used: SeerrSecret, renewed: SeerrSecret) {
        guard generations[key, default: 0] == generation, var entry = saved[key], entry.secret == used else { return }
        entry.secret = renewed
        try? store(entry, key: key)
    }

    private func store(_ entry: SeerrSaved, key: String) throws {
        guard let data = try? JSONEncoder().encode(entry) else { throw SeerrError.storage }
        let status = keychain.write(data, service: Self.service(key))
        if status == errSecMissingEntitlement {
            memory[key] = entry
        } else if status != errSecSuccess {
            throw SeerrError.storage
        }
        saved[key] = entry
        configs[key] = entry.config
    }
}
