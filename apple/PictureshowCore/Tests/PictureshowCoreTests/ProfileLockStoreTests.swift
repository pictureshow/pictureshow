// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

/// AUTH-16..19: the stateful PIN store (the pure rules run from spec/vectors in SpecVectorTests).
@MainActor final class ProfileLockStoreTests: XCTestCase {
    final class Memory: ProfileLockPersistence {
        var saved: [String: ProfileLock] = [:]
        var broken: Set<String> = []
        var failWrites = false
        var failDeletes = false
        func read(_ key: String) -> ProfileLockRead {
            if broken.contains(key) { return .unreadable }
            return saved[key].map(ProfileLockRead.lock) ?? .none
        }
        func write(_ lock: ProfileLock, key: String) throws {
            if failWrites { throw ProfilePinError.storage }
            saved[key] = lock
        }
        func delete(_ key: String) throws {
            if failDeletes { throw ProfilePinError.storage }
            saved[key] = nil
        }
    }

    /// Fast hasher with the real format, so the store tests don't spend 120,000 rounds a try.
    struct Quick: ProfilePinHasher {
        var broken = false
        func create(_ pin: String) throws -> String { try PBKDF2ProfilePinHasher(rounds: 1).create(pin) }
        func matches(_ pin: String, encoded: String) -> Bool? { broken ? nil : PBKDF2ProfilePinHasher(rounds: 1).matches(pin, encoded: encoded) }
    }

    private func store(_ memory: Memory = Memory(), hasher: ProfilePinHasher = Quick()) -> ProfileLockStore {
        ProfileLockStore(persistence: memory, hasher: hasher)
    }

    func testHashIsSaltedAndNeverThePin() throws {
        let hasher = PBKDF2ProfilePinHasher(rounds: 1000)
        let a = try hasher.create("1234"), b = try hasher.create("1234")
        XCTAssertNotEqual(a, b, "a fresh salt each time")
        XCTAssertFalse(a.contains("1234"))
        XCTAssertTrue(a.hasPrefix("pbkdf2-sha256:1000:"))
        XCTAssertEqual(a.split(separator: ":").count, 4)
        XCTAssertEqual(hasher.matches("1234", encoded: a), true)
        XCTAssertEqual(hasher.matches("1235", encoded: a), false)
        XCTAssertEqual(hasher.matches("1234", encoded: "pbkdf2-sha256:1000:zz:00"), false, "a malformed hash never matches")
        XCTAssertEqual(hasher.matches("1234", encoded: ""), false)
        XCTAssertThrowsError(try hasher.create("12a4"))
    }

    /// RFC 7914 §11 PBKDF2-HMAC-SHA256 vector (P="passwd", S="salt", c=1), so the derivation is the standard one.
    func testDerivationMatchesTheStandardVector() {
        let out = PBKDF2ProfilePinHasher.derive("passwd", salt: Array("salt".utf8), rounds: 1)
        XCTAssertEqual(out.map(PBKDF2ProfilePinHasher.hex)?.prefix(32), "55ac046e56e3089fec1691c22544b605")
    }

    func testSetThenRightPinUnlocksAndWrongPinsCountToTheHardLimit() async throws {
        let memory = Memory(), pins = store(memory)
        try await pins.set("a", pin: "2468", askOnStart: true)
        XCTAssertNotNil(memory.saved["a"])
        XCTAssertFalse(memory.saved["a"]!.hash.contains("2468"))
        XCTAssertEqual(pins.unlocked, "a", "setting a PIN opens the viewer")
        // A new process: the unlock lived only in memory.
        let restarted = store(memory)
        restarted.load(keys: ["a"])
        XCTAssertNil(restarted.unlocked, "a restart never carries an unlock")
        var now: Int64 = 10_000
        for n in 1...4 { let r = await restarted.verify("a", pin: "0000", now: now); XCTAssertEqual(r, .wrong, "try \(n)"); now += 1 }
        do { let r = await restarted.verify("a", pin: "0000", now: now); XCTAssertEqual(r, .wait, "fifth wrong waits 30 s") }
        do { let r = await restarted.verify("a", pin: "2468", now: now + 29_000); XCTAssertEqual(r, .wait, "a right PIN during the wait is refused") }
        XCTAssertEqual(memory.saved["a"]?.failures, 5, "and not counted")
        for wait: Int64 in [30_000, 60_000, 300_000, 900_000, 3_600_000] { now += wait; _ = await restarted.verify("a", pin: "0000", now: now) }
        XCTAssertEqual(memory.saved["a"]?.failures, 10)
        XCTAssertTrue(restarted.passwordOnly("a"))
        do { let r = await restarted.verify("a", pin: "2468", now: now + 99_999_999); XCTAssertEqual(r, .lockedOut, "past the hard limit the right PIN is refused") }
        XCTAssertNil(restarted.unlocked)
    }

    /// Fail closed: a saved PIN that can't be read keeps the viewer locked; only the password opens it.
    func testUnreadablePinLocksAndOnlyThePasswordOpens() async throws {
        let memory = Memory(); memory.broken = ["a"]
        let pins = store(memory)
        pins.load(keys: ["a", "b"])
        XCTAssertTrue(pins.hasPin("a"))
        XCTAssertTrue(pins.gateNeeded("a"), "opening on it asks, whatever was chosen before")
        XCTAssertTrue(pins.switchNeedsPin(to: "a", current: "b"))
        do { let r = await pins.verify("a", pin: "1234", now: 1); XCTAssertEqual(r, .unavailable) }
        XCTAssertNil(pins.unlocked)
        XCTAssertTrue(pins.changeNeedsPin("a"))
        pins.provenByPassword("a")
        XCTAssertFalse(pins.gateNeeded("a"))
        try await pins.set("a", pin: "1357", askOnStart: true)
        XCTAssertFalse(pins.unreadable.contains("a"), "a new PIN replaces the unreadable one")
        do { let r = await pins.verify("a", pin: "1357", now: 2); XCTAssertEqual(r, .unlocked) }
    }

    /// Fail closed: a PIN the platform couldn't check never unlocks.
    func testHashFailureNeverUnlocks() async throws {
        let memory = Memory(); memory.saved["a"] = ProfileLock(hash: try PBKDF2ProfilePinHasher(rounds: 1).create("1234"))
        let pins = store(memory, hasher: Quick(broken: true))
        pins.load(keys: ["a"])
        do { let r = await pins.verify("a", pin: "1234", now: 1); XCTAssertEqual(r, .unavailable) }
        XCTAssertNil(pins.unlocked)
    }

    /// A wrong try whose count can't be saved still counts in memory, so a failing store can't hand out guesses.
    func testFailedSaveStillCountsTheTry() async throws {
        let memory = Memory(), pins = store(memory)
        try await pins.set("a", pin: "1234", askOnStart: true)
        memory.failWrites = true
        for t: Int64 in 1...5 { _ = await pins.verify("a", pin: "9999", now: t) }
        XCTAssertEqual(pins.lock("a")?.failures, 5)
        XCTAssertTrue(pins.lastStorageFailure)
        do { let r = await pins.verify("a", pin: "1234", now: 6); XCTAssertEqual(r, .wait) }
    }

    func testANewPinThatCantBeSavedChangesNothing() async throws {
        let memory = Memory(), pins = store(memory)
        memory.failWrites = true
        do { try await pins.set("a", pin: "1234", askOnStart: true); XCTFail("should throw") } catch { XCTAssertEqual(error as? ProfilePinError, .storage) }
        XCTAssertNil(pins.lock("a"))
        memory.failWrites = false
        try await pins.set("a", pin: "1234", askOnStart: true)
        memory.failDeletes = true
        pins.provenByPassword("a")
        XCTAssertThrowsError(try pins.remove("a"))
        XCTAssertNotNil(pins.lock("a"), "a PIN that couldn't be deleted stays on")
    }

    /// AUTH-18: five minutes away drops every unlock, change permission and password proof; less keeps them.
    func testRelockAfterFiveMinutesAway() async throws {
        let pins = store()
        try await pins.set("a", pin: "1234", askOnStart: true)
        XCTAssertFalse(pins.gateNeeded("a"))
        pins.backgrounded(now: 1_000_000)
        XCTAssertFalse(pins.foregrounded(now: 1_299_999))
        XCTAssertEqual(pins.unlocked, "a")
        XCTAssertFalse(pins.gateNeeded("a"))
        pins.provenByPassword("a")
        XCTAssertFalse(pins.changeNeedsPin("a"))
        pins.backgrounded(now: 2_000_000)
        XCTAssertTrue(pins.foregrounded(now: 2_300_000))
        XCTAssertNil(pins.unlocked)
        XCTAssertTrue(pins.gateNeeded("a"), "asked like a fresh start")
        XCTAssertTrue(pins.changeNeedsPin("a"), "the password proof went with it")
        XCTAssertFalse(pins.foregrounded(now: 9_000_000), "coming back without having left doesn't lock")
        pins.backgrounded(now: 10_000_000)
        pins.backgrounded(now: 10_240_000)
        XCTAssertEqual(pins.backgroundedAt, 10_000_000, "already away keeps the earlier start")
    }

    func testRelockFollowsAskOnStartAndARestartLocks() async throws {
        let pins = store()
        try await pins.set("a", pin: "1234", askOnStart: false)
        pins.backgrounded(now: 5_000_000)
        XCTAssertTrue(pins.foregrounded(now: 20_000), "a clock below the saved time is a restart")
        XCTAssertFalse(pins.gateNeeded("a"), "opening choice off: the viewer on screen stays open")
        XCTAssertTrue(pins.changeNeedsPin("a"), "but changes still need the current PIN")
    }

    /// AUTH-19: the first PIN is free; an open viewer alone may not change it; a typed PIN allows exactly one change.
    func testChangeNeedsTheCurrentPinAndTheGrantIsOneShot() async throws {
        let pins = store()
        XCTAssertFalse(pins.changeNeedsPin("a"))
        try await pins.set("a", pin: "1234", askOnStart: true)
        XCTAssertTrue(pins.changeNeedsPin("a"))
        do { try await pins.set("a", pin: "5678", askOnStart: true); XCTFail() } catch { XCTAssertEqual(error as? ProfilePinError, .currentPinNeeded) }
        XCTAssertThrowsError(try pins.setAskOnStart("a", false))
        XCTAssertThrowsError(try pins.remove("a"))
        do { let r = await pins.confirmForChange("a", pin: "0000", now: 1); XCTAssertEqual(r, .wrong) }
        XCTAssertEqual(pins.lock("a")?.failures, 1, "confirm tries count with unlock tries")
        do { let r = await pins.confirmForChange("a", pin: "1234", now: 2); XCTAssertEqual(r, .unlocked) }
        try pins.setAskOnStart("a", false)
        XCTAssertTrue(pins.changeNeedsPin("a"), "one change per typed PIN")
        do { let r = await pins.confirmForChange("a", pin: "1234", now: 3); XCTAssertEqual(r, .unlocked) }
        pins.dropChangeGrant()
        XCTAssertTrue(pins.changeNeedsPin("a"), "a cancelled new-PIN screen ends it unused")
        do { let r = await pins.confirmForChange("a", pin: "1234", now: 4); XCTAssertEqual(r, .unlocked) }
        _ = pins.gateNeeded("b")
        XCTAssertTrue(pins.changeNeedsPin("a"), "becoming another viewer ends it")
    }

    /// AUTH-19: a password sign-in allows one change without the old PIN, not an open window until relock.
    func testPasswordProofAllowsOneChangeOnly() async throws {
        let pins = store()
        try await pins.set("a", pin: "1234", askOnStart: true)
        _ = pins.gateNeeded("a")
        pins.provenByPassword("a")
        XCTAssertFalse(pins.changeNeedsPin("a"), "the Forgot PIN path")
        try pins.setAskOnStart("a", false)
        XCTAssertTrue(pins.changeNeedsPin("a"), "the proof is used up by one change")
        XCTAssertThrowsError(try pins.remove("a")) { XCTAssertEqual($0 as? ProfilePinError, .currentPinNeeded) }
        do { try await pins.set("a", pin: "5678", askOnStart: true); XCTFail("changed without the PIN") } catch { XCTAssertEqual(error as? ProfilePinError, .currentPinNeeded) }

        pins.provenByPassword("a")
        try pins.remove("a")
        try await pins.set("a", pin: "2468", askOnStart: true)
        XCTAssertTrue(pins.changeNeedsPin("a"), "turning off then setting a first PIN doesn't bring the proof back")

        pins.provenByPassword("a")
        _ = pins.gateNeeded("b")
        _ = pins.gateNeeded("a")
        XCTAssertFalse(pins.changeNeedsPin("a"), "a viewer seen before the sign-in lands doesn't end it")
        pins.dropChangeGrant()
        XCTAssertFalse(pins.changeNeedsPin("a"), "a cancelled new-PIN screen keeps it")
        _ = pins.gateNeeded("b")
        pins.markUnlocked("a"); _ = pins.gateNeeded("a")
        XCTAssertTrue(pins.changeNeedsPin("a"), "another viewer becoming current after the landing ends the proof")

        pins.provenByPassword("a"); _ = pins.gateNeeded("a")
        pins.leftPinSettings()
        XCTAssertTrue(pins.changeNeedsPin("a"), "leaving the PIN settings ends the proof")

        pins.provenByPassword("a")
        pins.backgrounded(now: 0)
        pins.foregrounded(now: ProfilePin.relockAfterMs)
        pins.markUnlocked("a")
        XCTAssertTrue(pins.changeNeedsPin("a"), "a relock ends the proof")
    }

    /// AUTH-16: the opened viewer follows its opening choice; any other that becomes current asks like a switch.
    func testGateStartVersusSwitch() async throws {
        let memory = Memory()
        memory.saved["a"] = ProfileLock(hash: try PBKDF2ProfilePinHasher(rounds: 1).create("1111"), askOnStart: false)
        memory.saved["b"] = ProfileLock(hash: try PBKDF2ProfilePinHasher(rounds: 1).create("2222"), askOnStart: false)
        let pins = store(memory)
        pins.load(keys: ["a", "b"])
        XCTAssertFalse(pins.gateNeeded("a"), "opened on a, which doesn't ask on start")
        pins.markUnlocked("a")
        XCTAssertTrue(pins.gateNeeded("b"), "b becoming current asks like a switch")
        do { let r = await pins.verify("b", pin: "2222", now: 1); XCTAssertEqual(r, .unlocked) }
        XCTAssertFalse(pins.gateNeeded("b"))
        XCTAssertFalse(pins.gateNeeded("a"), "back on the opened viewer, its opening choice (off) applies to the gate")
        XCTAssertTrue(pins.switchNeedsPin(to: "a", current: "b"), "but the chooser asks before switching back")
        pins.forget("b")
        XCTAssertNil(pins.lock("b"))
        XCTAssertNil(memory.saved["b"], "removing a viewer drops its PIN")
    }
}
