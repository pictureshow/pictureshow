// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Security
import UIKit
import PictureshowCore

/// Everything the app remembers about sign-ins: the servers it has reached, the viewers signed in on them,
/// and which viewer is active. Tokens never leave this store except inside an `Account`.
struct AccountSnapshot: Codable, Equatable {
    var servers: [Server] = []
    var accounts: [Account] = []
    var selected: String?
}

extension Account {
    /// One viewer on one server. Two servers can share a user id, so the key carries both.
    var key: String { server.url.absoluteString + "|" + userId }
}

/// Persists the snapshot in the Keychain. Unsigned simulator builds have no Keychain entitlement
/// (`errSecMissingEntitlement`), so they fall back to a complete-protection file in Application Support.
struct AccountStore {
    var service = "app.pictureshow.accounts"
    var fileURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("accounts.json")
    }()

    func load() -> AccountSnapshot {
        if let data = keychainRead() ?? fallbackRead(),
           let snapshot = try? JSONDecoder().decode(AccountSnapshot.self, from: data) { return snapshot }
        return AccountSnapshot()
    }

    func save(_ snapshot: AccountSnapshot) {
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        if keychainWrite(data) {
            try? FileManager.default.removeItem(at: fileURL)
            UserDefaults.standard.removeObject(forKey: service)
            return
        }
        #if os(tvOS)
        // tvOS gives apps no durable file storage outside caches; defaults survive.
        UserDefaults.standard.set(data, forKey: service)
        #else
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #endif
    }

    private func fallbackRead() -> Data? {
        #if os(tvOS)
        return UserDefaults.standard.data(forKey: service)
        #else
        return try? Data(contentsOf: fileURL)
        #endif
    }

    private var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "snapshot"]
    }

    private func keychainRead() -> Data? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    private func keychainWrite(_ data: Data) -> Bool {
        let attributes: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = query
        add.merge(attributes) { $1 }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }
}

/// Pure snapshot edits, kept out of the view model so they can be tested without a server.
enum AccountBook {
    static func signIn(_ account: Account, into snapshot: AccountSnapshot) -> AccountSnapshot {
        var next = snapshot
        next.accounts.removeAll { $0.key == account.key }
        next.accounts.insert(account, at: 0)
        next.servers.removeAll { $0.url == account.server.url }
        next.servers.insert(account.server, at: 0)
        next.selected = account.key
        return next
    }

    static func remember(_ server: Server, in snapshot: AccountSnapshot) -> AccountSnapshot {
        var next = snapshot
        next.servers.removeAll { $0.url == server.url }
        next.servers.insert(server, at: 0)
        return next
    }

    /// Signing out forgets the token. The server stays remembered so signing back in is one step.
    static func signOut(_ key: String, from snapshot: AccountSnapshot) -> AccountSnapshot {
        var next = snapshot
        next.accounts.removeAll { $0.key == key }
        if next.selected == key { next.selected = nil }
        return next
    }

    static func forget(server url: URL, from snapshot: AccountSnapshot) -> AccountSnapshot {
        var next = snapshot
        next.servers.removeAll { $0.url == url }
        next.accounts.removeAll { $0.server.url == url }
        if let selected = next.selected, !next.accounts.contains(where: { $0.key == selected }) { next.selected = nil }
        return next
    }

    static func selected(in snapshot: AccountSnapshot) -> Account? {
        snapshot.accounts.first { $0.key == snapshot.selected }
    }
}

/// A device id per viewer: Jellyfin keys sessions and Quick Connect approvals by device id,
/// so two viewers on one install must not share one, or signing one out ends the other's session.
enum DeviceIdentity {
    static func make() -> String { UUID().uuidString.lowercased() }
    @MainActor static var name: String {
        #if os(tvOS)
        return "Apple TV"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "iPhone"
        #endif
    }
}
