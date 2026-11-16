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

/// The Keychain operations the store needs, behind a seam so tests can make them fail.
protocol KeychainBackend {
    func read(service: String) -> (Data?, OSStatus)
    func write(_ data: Data, service: String) -> OSStatus
    func delete(service: String)
}

struct SystemKeychain: KeychainBackend {
    private func query(_ service: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: "snapshot"]
    }

    func read(service: String) -> (Data?, OSStatus) {
        var q = query(service)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(q as CFDictionary, &result)
        return (status == errSecSuccess ? result as? Data : nil, status)
    }

    func write(_ data: Data, service: String) -> OSStatus {
        let attributes: [String: Any] = [kSecValueData as String: data,
                                         kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        let status = SecItemUpdate(query(service) as CFDictionary, attributes as CFDictionary)
        guard status == errSecItemNotFound else { return status }
        var add = query(service)
        add.merge(attributes) { $1 }
        return SecItemAdd(add as CFDictionary, nil)
    }

    func delete(service: String) { SecItemDelete(query(service) as CFDictionary) }
}

enum AccountStoreError: LocalizedError, Equatable {
    case keychain(OSStatus)
    var errorDescription: String? {
        switch self {
        case .keychain(let status): return "Your sign-in couldn't be saved securely on this device (Keychain error \(status)). You're signed in for now; try again, or you'll need to sign in next time."
        }
    }
}

/// Persists the snapshot (tokens included) in the Keychain, and nowhere else on a real device.
/// Unsigned simulator builds have no Keychain entitlement, so only there (or when the Keychain reports
/// exactly `errSecMissingEntitlement`) the snapshot falls back to a protected file (iOS) or defaults (tvOS).
/// Any other Keychain failure fails closed: nothing is written and the caller gets the error.
struct AccountStore {
    var service = "app.pictureshow.accounts"
    var fileURL: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("accounts.json")
    }()
    var keychain: KeychainBackend = SystemKeychain()
    var defaults: UserDefaults = .standard
    var isSimulator: Bool = {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }()

    private func fallbackAllowed(_ status: OSStatus) -> Bool { isSimulator || status == errSecMissingEntitlement }

    func load() -> AccountSnapshot {
        let (data, status) = keychain.read(service: service)
        let stored = data ?? (fallbackAllowed(status) ? fallbackRead() : nil)
        if let stored, let snapshot = try? JSONDecoder().decode(AccountSnapshot.self, from: stored) { return snapshot }
        return AccountSnapshot()
    }

    func save(_ snapshot: AccountSnapshot) throws {
        let data = try JSONEncoder().encode(snapshot)
        let status = keychain.write(data, service: service)
        if status == errSecSuccess {
            // A leftover fallback copy could bring back a token the viewer signed out of.
            deleteFallback()
            return
        }
        guard fallbackAllowed(status) else {
            deleteFallback()
            throw AccountStoreError.keychain(status)
        }
        #if os(tvOS)
        // tvOS gives apps no durable file storage outside caches; defaults survive.
        defaults.set(data, forKey: service)
        #else
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #endif
    }

    func deleteFallback() {
        try? FileManager.default.removeItem(at: fileURL)
        defaults.removeObject(forKey: service)
    }

    private func fallbackRead() -> Data? {
        #if os(tvOS)
        return defaults.data(forKey: service)
        #else
        return try? Data(contentsOf: fileURL)
        #endif
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
    @MainActor static var systemName: String {
        #if os(tvOS)
        return "tvOS"
        #else
        return UIDevice.current.userInterfaceIdiom == .pad ? "iPadOS" : "iOS"
        #endif
    }
    /// Troubleshooting page title, as the spec names it per device class.
    @MainActor static var thisDevice: String { "This \(name)" }
}
