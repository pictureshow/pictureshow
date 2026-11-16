// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import Security
import SwiftUI
import PictureshowCore

/// AUTH-16: each viewer's PIN lives in its own Keychain item (this device only, after first unlock), as a salted
/// PBKDF2 hash with the try count; never in a file or in defaults. One item per viewer keeps a broken entry to that
/// viewer: a saved PIN that can't be read, or a Keychain that refuses to answer, reads as `.unreadable` and the viewer
/// stays locked until its password opens it (fail closed). An unsigned build without the Keychain entitlement keeps
/// PINs in memory only, until the app quits.
final class ProfileLockKeychain: ProfileLockPersistence {
    let keychain: KeychainBackend
    private var memory: [String: ProfileLock] = [:]

    init(keychain: KeychainBackend = SystemKeychain()) { self.keychain = keychain }

    static func service(_ key: String) -> String { "app.pictureshow.pin|" + key }

    func read(_ key: String) -> ProfileLockRead {
        let (data, status) = keychain.read(service: Self.service(key))
        if let data {
            guard let lock = try? JSONDecoder().decode(ProfileLock.self, from: data), !lock.hash.isEmpty else { return .unreadable }
            return .lock(lock)
        }
        switch status {
        case errSecItemNotFound: return .none
        case errSecMissingEntitlement: return memory[key].map(ProfileLockRead.lock) ?? .none
        default: return .unreadable
        }
    }

    func write(_ lock: ProfileLock, key: String) throws {
        let data = try JSONEncoder().encode(lock)
        let status = keychain.write(data, service: Self.service(key))
        if status == errSecMissingEntitlement { memory[key] = lock; return }
        guard status == errSecSuccess else { throw ProfilePinError.storage }
    }

    /// Gone only once the Keychain says it is gone.
    func delete(_ key: String) throws {
        memory[key] = nil
        keychain.delete(service: Self.service(key))
        let (data, status) = keychain.read(service: Self.service(key))
        guard data == nil, status == errSecItemNotFound || status == errSecMissingEntitlement else { throw ProfilePinError.storage }
    }
}

extension AppModel {
    /// AUTH-18: no Pictureshow screen visible (`background`) starts the time away; any visible phase ends it. Five
    /// minutes or more away (or a restart in between) locks again like a fresh start, and a viewing on screen closes
    /// so nothing plays behind the lock. Returns whether it locked.
    @discardableResult func sceneMoved(to phase: ScenePhase, now: Int64 = ProfilePin.monotonicNow()) -> Bool {
        if phase == .background {
            pins.backgrounded(now: now)
            return false
        }
        guard pins.backgroundedAt != nil else { return false }
        pins.retryUnreadable()
        guard pins.foregrounded(now: now) else { return false }
        if let account, pins.needed(account.key, reason: .START) { player = nil }
        return true
    }

    /// Whether the app-wide lock covers the current viewer instead of the app.
    func pinGateNeeded(_ account: Account) -> Bool { pins.gateNeeded(account.key) }
}
