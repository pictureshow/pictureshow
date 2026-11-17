// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import CommonCrypto
import Observation
import Security

/// A viewer's PIN on this device (AUTH-16..19), the way Plex profile PINs work. Jellyfin keeps the real limits (ratings,
/// tags, libraries, schedules) and enforces them on every request; the PIN only stops someone on this device opening a
/// viewer they shouldn't. Nothing about it is sent to the server.
public struct ProfileLock: Codable, Equatable, Sendable {
    /// `pbkdf2-sha256:<rounds>:<salt hex>:<hash hex>`, never the PIN.
    public var hash: String
    /// Also ask when Pictureshow opens on this viewer, not only when someone switches to it.
    public var askOnStart: Bool
    /// Wrong guesses since the last right one. A wait ending doesn't reset it; only the right PIN or a new PIN does.
    public var failures: Int
    /// Monotonic clock (ms since boot, never the wall clock) at the last wrong guess.
    public var failedAt: Int64

    public init(hash: String, askOnStart: Bool = true, failures: Int = 0, failedAt: Int64 = 0) {
        self.hash = hash; self.askOnStart = askOnStart; self.failures = failures; self.failedAt = failedAt
    }
}

public enum ProfileUnlockReason: String, Sendable { case START, SWITCH }

/// What can happen to a password proof (AUTH-19).
public enum ProfilePinProofEvent: String, Sendable {
    /// A PIN was set, changed, turned off, or its opening question changed.
    case CHANGE_MADE
    /// Another viewer became current after the proven one had been.
    case VIEWER_CHANGED
    /// Five minutes away (AUTH-18).
    case RELOCKED
    /// The PIN settings were left, so the next visit is a new one.
    case SETTINGS_LEFT
    /// The proven viewer became current: the sign-in landing.
    case SIGN_IN_LANDED
    /// The new-PIN screen was cancelled without a change.
    case SETUP_CANCELLED
}

/// LOCKED_OUT: the hard limit was reached and only the server password opens this viewer now.
/// UNAVAILABLE: the saved PIN couldn't be read or checked on this device, so it stays locked (fail closed).
public enum ProfilePinResult: Equatable, Sendable { case unlocked, wrong, wait, lockedOut, unavailable }

/// The pure rules, shared with Android's ProfileLock.kt through `spec/vectors/application.json` (`profile-pin-*`).
public enum ProfilePin {
    public static let minLength = 4
    public static let maxLength = 8
    /// Wrong guesses allowed before the first wait.
    public static let freeTries = 4
    /// At this many wrong guesses in a row the PIN stops working; only the server password opens the viewer.
    public static let hardLimit = 10
    /// Time away after which coming back to Pictureshow counts as opening it again (AUTH-18).
    public static let relockAfterMs: Int64 = 5 * 60_000

    /// 4 to 8 ASCII digits; other scripts' digits are refused.
    public static func valid(_ pin: String) -> Bool {
        (minLength...maxLength).contains(pin.unicodeScalars.count) && pin.unicodeScalars.allSatisfy { ("0"..."9").contains($0) }
    }

    /// Switching to a viewer always asks; opening the app asks unless the viewer turned that off or was already
    /// unlocked in this run.
    public static func needed(_ lock: ProfileLock?, reason: ProfileUnlockReason, unlocked: Bool) -> Bool {
        guard let lock else { return false }
        if reason == .SWITCH { return true }
        return lock.askOnStart && !unlocked
    }

    /// None for the first four wrong guesses, then 30 s, 1 min, 5 min, 15 min and 1 h.
    public static func wait(failures: Int) -> Int64 {
        switch failures {
        case ...freeTries: return 0
        case 5: return 30_000
        case 6: return 60_000
        case 7: return 300_000
        case 8: return 900_000
        default: return 3_600_000
        }
    }

    public static func lockedOut(_ lock: ProfileLock) -> Bool { lock.failures >= hardLimit }

    /// Milliseconds left before another guess is taken. A `now` below `failedAt` means the device restarted and the
    /// clock began again, so only time since that start counts: a restart begins the wait again rather than ending it.
    public static func cooldown(_ lock: ProfileLock, now: Int64) -> Int64 {
        if lockedOut(lock) { return Int64.max }
        let wait = wait(failures: lock.failures)
        if wait == 0 { return 0 }
        let served = now >= lock.failedAt ? now - lock.failedAt : max(now, 0)
        return min(max(wait - served, 0), wait)
    }

    /// A right PIN clears the count. A guess during a wait, or after the hard limit, isn't checked or counted.
    public static func attempt(_ lock: ProfileLock, now: Int64, correct: Bool) -> ProfileLock {
        if cooldown(lock, now: now) > 0 { return lock }
        var next = lock
        if correct { next.failures = 0; next.failedAt = 0 } else { next.failures += 1; next.failedAt = now }
        return next
    }

    /// Whether coming back locks again (AUTH-18): five minutes away, or a clock below the saved time (a restart).
    public static func relock(backgroundedAt: Int64?, now: Int64) -> Bool {
        guard let backgroundedAt else { return false }
        if now < backgroundedAt { return true }
        return now - backgroundedAt >= relockAfterMs
    }

    /// Whether Settings may change, turn off or stop asking for a viewer's PIN (AUTH-19). The first PIN is free; after
    /// that the current PIN has to be typed, or the viewer signed in with its password in this run.
    /// Whether a password proof survives `event`. It is one-time like a typed current PIN: the change it was for uses
    /// it up, and so do leaving the PIN settings, leaving that viewer and locking again, so a parent's sign-in can't
    /// leave a viewer whose PIN anyone may then turn off.
    public static func proofKept(_ event: ProfilePinProofEvent) -> Bool {
        switch event {
        case .CHANGE_MADE, .VIEWER_CHANGED, .RELOCKED, .SETTINGS_LEFT: return false
        case .SIGN_IN_LANDED, .SETUP_CANCELLED: return true
        }
    }

    public static func changeAllowed(hasPin: Bool, pinConfirmed: Bool, passwordProven: Bool) -> Bool {
        !hasPin || pinConfirmed || passwordProven
    }

    /// Milliseconds since boot, counting time asleep and never the wall clock (Android `elapsedRealtime`).
    /// Darwin's CLOCK_MONOTONIC keeps running while the device sleeps and starts again from zero at boot.
    public static func monotonicNow() -> Int64 {
        Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC) / 1_000_000)
    }
}

/// Makes and checks the saved form of a PIN. `matches` answers nil when it couldn't check at all.
public protocol ProfilePinHasher: Sendable {
    func create(_ pin: String) throws -> String
    func matches(_ pin: String, encoded: String) -> Bool?
}

public enum ProfilePinError: Error, Equatable { case invalidPin, hashing, storage, notUnlocked, currentPinNeeded }

/// PBKDF2-HMAC-SHA256 with a fresh 128-bit salt and a constant-time comparison, in the same text form as Android's,
/// with the round count saved so it can rise later. A 4-digit PIN has only 10,000 values, so the guess limit, not the
/// hash, is what protects it in the app; the hash keeps the PIN itself off the device's storage.
public struct PBKDF2ProfilePinHasher: ProfilePinHasher {
    public let rounds: UInt32
    public init(rounds: UInt32 = 120_000) { self.rounds = rounds }

    public func create(_ pin: String) throws -> String {
        guard ProfilePin.valid(pin) else { throw ProfilePinError.invalidPin }
        var salt = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, salt.count, &salt) == errSecSuccess,
              let derived = Self.derive(pin, salt: salt, rounds: rounds) else { throw ProfilePinError.hashing }
        return "pbkdf2-sha256:\(rounds):\(Self.hex(salt)):\(Self.hex(derived))"
    }

    /// False for a wrong PIN or a malformed saved form; nil only when the platform couldn't run the hash.
    public func matches(_ pin: String, encoded: String) -> Bool? {
        guard ProfilePin.valid(pin) else { return false }
        let parts = encoded.split(separator: ":", omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 4, parts[0] == "pbkdf2-sha256", let saved = UInt32(parts[1]), (1...10_000_000).contains(saved),
              let salt = Self.unhex(parts[2]), let expected = Self.unhex(parts[3]), salt.count == 16, expected.count == 32 else { return false }
        guard let derived = Self.derive(pin, salt: salt, rounds: saved) else { return nil }
        var difference: UInt8 = 0
        for (a, b) in zip(expected, derived) { difference |= a ^ b }
        return difference == 0
    }

    static func derive(_ pin: String, salt: [UInt8], rounds: UInt32) -> [UInt8]? {
        var out = [UInt8](repeating: 0, count: 32)
        let password = Array(pin.utf8)
        let status = password.withUnsafeBufferPointer { p in
            p.withMemoryRebound(to: Int8.self) { chars in
                CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), chars.baseAddress, chars.count, salt, salt.count,
                                     CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), rounds, &out, out.count)
            }
        }
        return status == kCCSuccess ? out : nil
    }

    static func hex(_ bytes: [UInt8]) -> String { bytes.map { String(format: "%02x", $0) }.joined() }

    static func unhex(_ text: String) -> [UInt8]? {
        let chars = Array(text.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var out: [UInt8] = []
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let byte = UInt8(String(decoding: chars[i..<i + 2], as: UTF8.self), radix: 16) else { return nil }
            out.append(byte); i += 2
        }
        return out
    }
}

/// What reading one viewer's saved PIN gave.
public enum ProfileLockRead: Equatable, Sendable {
    case none
    case lock(ProfileLock)
    /// Something is saved (or the store refused to say), but it couldn't be read: the viewer stays locked.
    case unreadable
}

/// Where PINs are kept, one entry per viewer key. The app keeps them in the Keychain.
public protocol ProfileLockPersistence {
    func read(_ key: String) -> ProfileLockRead
    func write(_ lock: ProfileLock, key: String) throws
    func delete(_ key: String) throws
}

/// Saved PINs by viewer key, and which viewer is unlocked in this run. The unlock, the one-change permission, the
/// password proof and the time away live only here, in process memory, so a restored process never carries them.
///
/// Fail closed: a PIN that can't be read keeps its viewer locked until the password (or Quick Connect) opens it; a
/// PIN that can't be checked never unlocks; a wrong try that can't be saved still counts for as long as the app runs.
@MainActor @Observable public final class ProfileLockStore {
    public private(set) var locks: [String: ProfileLock] = [:]
    /// Viewers whose saved PIN couldn't be read. They act like a PIN that only the password opens.
    public private(set) var unreadable: Set<String> = []
    public private(set) var unlocked: String?
    /// Set when a try's count couldn't be saved; the count still holds in memory.
    public private(set) var lastStorageFailure: Bool = false

    @ObservationIgnored private let persistence: ProfileLockPersistence
    @ObservationIgnored private let hasher: ProfilePinHasher
    @ObservationIgnored private var opened: String?
    @ObservationIgnored private var openedSeen = false
    /// The viewer the gate last let in. Unlocking someone else doesn't lock it again; only leaving it does.
    @ObservationIgnored private var admitted: String?
    /// A right current PIN typed in Settings, good for one change (AUTH-19).
    @ObservationIgnored private var changeGrant: String?
    /// The viewer that signed in with its password in this run, which may change its PIN without the old one.
    @ObservationIgnored private var passwordKey: String?
    /// Whether the proven viewer has become current yet (the sign-in lands a moment after the proof).
    @ObservationIgnored private var passwordLanded = false
    /// Monotonic time Pictureshow left the screen, while it is away (AUTH-18).
    @ObservationIgnored public private(set) var backgroundedAt: Int64?

    public init(persistence: ProfileLockPersistence, hasher: ProfilePinHasher = PBKDF2ProfilePinHasher()) {
        self.persistence = persistence
        self.hasher = hasher
    }

    /// Reads the saved PINs of these viewers.
    public func load(keys: [String]) {
        var next: [String: ProfileLock] = [:]
        var broken: Set<String> = []
        for key in keys {
            switch persistence.read(key) {
            case .none: break
            case .lock(let lock): next[key] = lock
            case .unreadable: broken.insert(key)
            }
        }
        locks = next
        unreadable = broken
        if let unlocked, !keys.contains(unlocked) { self.unlocked = nil }
    }

    /// Tries the unreadable ones again (a Keychain that was unavailable before the first unlock may answer now).
    public func retryUnreadable() {
        for key in unreadable {
            switch persistence.read(key) {
            case .none: unreadable.remove(key)
            case .lock(let lock): unreadable.remove(key); locks[key] = lock
            case .unreadable: break
            }
        }
    }

    public func lock(_ key: String) -> ProfileLock? { locks[key] }

    /// A PIN is saved, or something is saved that couldn't be read.
    public func hasPin(_ key: String) -> Bool { locks[key] != nil || unreadable.contains(key) }

    /// Only the password opens this viewer now: ten wrong tries, or a PIN that couldn't be read.
    public func passwordOnly(_ key: String) -> Bool {
        unreadable.contains(key) || locks[key].map(ProfilePin.lockedOut) == true
    }

    /// The lock the rules see: an unreadable PIN asks on start and on switch, whatever was chosen before.
    private func effectiveLock(_ key: String) -> ProfileLock? {
        locks[key] ?? (unreadable.contains(key) ? ProfileLock(hash: "", askOnStart: true, failures: ProfilePin.hardLimit) : nil)
    }

    public func needed(_ key: String, reason: ProfileUnlockReason) -> Bool {
        ProfilePin.needed(effectiveLock(key), reason: reason, unlocked: unlocked == key)
    }

    /// The chooser asks before switching to a viewer with a PIN, unless that viewer is the open one already.
    public func switchNeedsPin(to key: String, current: String?) -> Bool {
        hasPin(key) && !(key == current && unlocked == key)
    }

    /// Pictureshow left the screen. `now` is the monotonic clock.
    public func backgrounded(now: Int64) { backgroundedAt = now }

    /// Pictureshow is in use again without coming to the front (a picture-in-picture window playing): the time away
    /// stops counting until it is left again.
    public func inUse() { backgroundedAt = nil }

    /// Pictureshow is back on screen. After five minutes away it counts as opening it again: every unlock, change
    /// permission and password proof is dropped, and the viewer on screen is asked like at a fresh start. Returns
    /// whether it locked.
    @discardableResult public func foregrounded(now: Int64) -> Bool {
        let relock = ProfilePin.relock(backgroundedAt: backgroundedAt, now: now)
        backgroundedAt = nil
        if relock {
            admitted = nil; changeGrant = nil; proofEvent(.RELOCKED)
            openedSeen = false; opened = nil
            unlocked = nil
        }
        return relock
    }

    /// Ends a one-change permission unused: the new-PIN screen was cancelled or left (AUTH-19). A password proof
    /// survives a cancelled new-PIN screen.
    public func dropChangeGrant() { changeGrant = nil }

    /// Leaving the PIN settings ends both permissions: a typed PIN and a password proof (AUTH-19).
    public func leftPinSettings() {
        changeGrant = nil
        proofEvent(.SETTINGS_LEFT)
    }

    private func proofEvent(_ event: ProfilePinProofEvent) {
        if !ProfilePin.proofKept(event) { passwordKey = nil; passwordLanded = false }
    }

    /// A password or Quick Connect sign-in: the viewer is open and may make one PIN change without the old PIN, until
    /// that change, another viewer becoming current, or a relock.
    public func provenByPassword(_ key: String) {
        passwordKey = key
        passwordLanded = false
        unlocked = key
    }

    /// Whether Settings has to ask for the current PIN before changing this viewer's PIN or its opening choice.
    public func changeNeedsPin(_ key: String) -> Bool {
        !ProfilePin.changeAllowed(hasPin: hasPin(key), pinConfirmed: changeGrant == key, passwordProven: passwordKey == key)
    }

    /// The app-wide check, run whenever the current viewer changes. The viewer Pictureshow opened on follows its
    /// "ask when Pictureshow opens" choice; any other viewer that becomes current, by whatever route, asks like a switch
    /// unless it was unlocked on the way in. Only bookkeeping that nothing observes changes here, so a view may call it.
    public func gateNeeded(_ key: String?) -> Bool {
        if !openedSeen { opened = key; openedSeen = true }
        // A change permission belongs to the viewer it was typed for; becoming anyone else ends it.
        if changeGrant != nil && changeGrant != key { changeGrant = nil }
        if let proven = passwordKey {
            if key == proven { passwordLanded = true } else if passwordLanded { proofEvent(.VIEWER_CHANGED) }
        }
        if key == nil || unlocked == key { admitted = key; return false }
        // Unlocking another viewer happens just before it becomes current, so the open one stays open through that
        // moment instead of flashing its own lock screen.
        if key == admitted { return false }
        admitted = nil
        guard let key else { return false }
        return ProfilePin.needed(effectiveLock(key), reason: key == opened ? .START : .SWITCH, unlocked: false)
    }

    /// The viewer past the gate is the open one, so leaving it locks it again behind its PIN.
    public func markUnlocked(_ key: String?) {
        if changeGrant != key { changeGrant = nil }
        unlocked = key
    }

    /// An unlock try. `now` is the monotonic clock. The hash runs off the main actor.
    public func verify(_ key: String, pin: String, now: Int64) async -> ProfilePinResult {
        await attempt(key, pin: pin, now: now)
    }

    /// The current PIN typed in Settings before a change (AUTH-19): same count, waits and hard limit as unlocking; a
    /// right one allows one change.
    public func confirmForChange(_ key: String, pin: String, now: Int64) async -> ProfilePinResult {
        let result = await attempt(key, pin: pin, now: now)
        if result == .unlocked { changeGrant = key }
        return result
    }

    private func attempt(_ key: String, pin: String, now: Int64) async -> ProfilePinResult {
        if unreadable.contains(key) { return .unavailable }
        guard let old = locks[key] else { unlocked = key; return .unlocked }
        if ProfilePin.lockedOut(old) { return .lockedOut }
        if ProfilePin.cooldown(old, now: now) > 0 { return .wait }
        let hasher = hasher, encoded = old.hash
        let checked: Bool? = ProfilePin.valid(pin) ? await Task.detached(priority: .userInitiated) { hasher.matches(pin, encoded: encoded) }.value : false
        guard let correct = checked else { return .unavailable }
        // The lock may have moved while the hash ran (another try, a new PIN, a removal): judge against what is saved now.
        guard let current = locks[key], current.hash == encoded else { return locks[key] == nil ? .unavailable : .wrong }
        if ProfilePin.lockedOut(current) { return .lockedOut }
        if ProfilePin.cooldown(current, now: now) > 0 { return .wait }
        let next = ProfilePin.attempt(current, now: now, correct: correct)
        if next != current { save(next, key: key) }
        if correct { unlocked = key; return .unlocked }
        if ProfilePin.lockedOut(next) { return .lockedOut }
        return ProfilePin.cooldown(next, now: now) > 0 ? .wait : .wrong
    }

    /// Memory first, then storage: a write that fails still leaves the try counted for as long as the app runs, so a
    /// failing store can't hand out unlimited guesses.
    private func save(_ lock: ProfileLock, key: String) {
        locks[key] = lock
        do { try persistence.write(lock, key: key); lastStorageFailure = false } catch { lastStorageFailure = true }
    }

    /// Sets or changes the PIN of the unlocked viewer (any viewer may set its first one). Throws, changing nothing,
    /// when the PIN can't be hashed or saved.
    public func set(_ key: String, pin: String, askOnStart: Bool) async throws {
        guard unlocked == key || !hasPin(key) else { throw ProfilePinError.notUnlocked }
        guard !changeNeedsPin(key) else { throw ProfilePinError.currentPinNeeded }
        guard ProfilePin.valid(pin) else { throw ProfilePinError.invalidPin }
        let hasher = hasher
        let hash = try await Task.detached(priority: .userInitiated) { try hasher.create(pin) }.value
        let lock = ProfileLock(hash: hash, askOnStart: askOnStart)
        do { try persistence.write(lock, key: key) } catch { throw ProfilePinError.storage }
        useChangePermission(key)
        locks[key] = lock
        unreadable.remove(key)
        unlocked = key
    }

    public func setAskOnStart(_ key: String, _ askOnStart: Bool) throws {
        guard unlocked == key else { throw ProfilePinError.notUnlocked }
        guard var lock = locks[key] else { return }
        guard !changeNeedsPin(key) else { throw ProfilePinError.currentPinNeeded }
        lock.askOnStart = askOnStart
        do { try persistence.write(lock, key: key) } catch { throw ProfilePinError.storage }
        useChangePermission(key)
        locks[key] = lock
    }

    /// Turns the PIN off. The entry has to be gone from storage before it is gone here.
    public func remove(_ key: String) throws {
        guard unlocked == key else { throw ProfilePinError.notUnlocked }
        guard !changeNeedsPin(key) else { throw ProfilePinError.currentPinNeeded }
        do { try persistence.delete(key) } catch { throw ProfilePinError.storage }
        useChangePermission(key)
        locks[key] = nil
        unreadable.remove(key)
    }

    /// Drops a viewer's PIN without the unlock check, for a viewer removed from this device.
    public func forget(_ key: String) {
        try? persistence.delete(key)
        locks[key] = nil
        unreadable.remove(key)
        if unlocked == key { unlocked = nil }
    }

    /// A change uses up its permission, whether a typed PIN or a password sign-in allowed it: one change each, so a
    /// password proved once doesn't leave the PIN open to whoever picks the device up next (AUTH-19).
    private func useChangePermission(_ key: String) {
        if changeGrant == key { changeGrant = nil }
        if passwordKey == key { proofEvent(.CHANGE_MADE) }
    }
}
