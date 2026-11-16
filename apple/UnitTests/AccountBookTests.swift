// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class AccountBookTests: XCTestCase {
    private let home = Server(url: URL(string: "https://media.example.test")!, id: "a", name: "Home")
    private let cabin = Server(url: URL(string: "https://cabin.example.test")!, id: "b", name: "Cabin")

    private func account(_ server: Server, _ user: String, token: String = "t") -> Account {
        Account(server: server, userId: user, username: user, accessToken: token, deviceID: "d-\(user)")
    }

    func testSameUserIdOnTwoServersStaysTwoAccounts() {
        var book = AccountSnapshot()
        book = AccountBook.signIn(account(home, "u1"), into: book)
        book = AccountBook.signIn(account(cabin, "u1"), into: book)
        XCTAssertEqual(book.accounts.count, 2)
        XCTAssertEqual(AccountBook.selected(in: book)?.server.url, cabin.url)
    }

    func testSigningInAgainReplacesTheTokenInsteadOfDuplicating() {
        var book = AccountBook.signIn(account(home, "u1", token: "old"), into: AccountSnapshot())
        book = AccountBook.signIn(account(home, "u1", token: "new"), into: book)
        XCTAssertEqual(book.accounts.map(\.accessToken), ["new"])
        XCTAssertEqual(book.servers.count, 1)
    }

    func testSignOutForgetsTokenButKeepsServerAndOtherViewers() {
        var book = AccountBook.signIn(account(home, "u1"), into: AccountSnapshot())
        book = AccountBook.signIn(account(home, "u2"), into: book)
        book = AccountBook.signOut(account(home, "u2").key, from: book)
        XCTAssertNil(book.selected, "the signed-out viewer must not stay selected")
        XCTAssertEqual(book.accounts.map(\.userId), ["u1"])
        XCTAssertEqual(book.servers.map(\.url), [home.url])
    }

    func testForgettingAServerRemovesItsAccountsOnly() {
        var book = AccountBook.signIn(account(home, "u1"), into: AccountSnapshot())
        book = AccountBook.signIn(account(cabin, "u2"), into: book)
        book = AccountBook.forget(server: cabin.url, from: book)
        XCTAssertEqual(book.accounts.map(\.userId), ["u1"])
        XCTAssertNil(book.selected)
        XCTAssertEqual(book.servers.map(\.url), [home.url])
    }

    private final class FakeKeychain: KeychainBackend {
        var status: OSStatus
        var stored: Data?
        init(status: OSStatus) { self.status = status }
        func read(service: String) -> (Data?, OSStatus) { status == errSecSuccess ? (stored, stored == nil ? errSecItemNotFound : errSecSuccess) : (nil, status) }
        func write(_ data: Data, service: String) -> OSStatus { if status == errSecSuccess { stored = data }; return status }
        func delete(service: String) { stored = nil }
    }

    private func store(_ keychain: KeychainBackend, simulator: Bool) -> (AccountStore, URL, UserDefaults) {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json")
        let suite = "app.pictureshow.tests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return (AccountStore(service: suite, fileURL: file, keychain: keychain, defaults: defaults, isSimulator: simulator), file, defaults)
    }

    func testStoreRoundTripsTheSnapshotThroughTheKeychain() throws {
        let (store, file, _) = store(FakeKeychain(status: errSecSuccess), simulator: false)
        let book = AccountBook.signIn(account(home, "u1", token: "secret-token"), into: AccountSnapshot())
        try store.save(book)
        XCTAssertEqual(store.load(), book)
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testKeychainFailureOnADeviceFailsClosedAndWritesNoToken() throws {
        let (store, file, defaults) = store(FakeKeychain(status: errSecInteractionNotAllowed), simulator: false)
        let book = AccountBook.signIn(account(home, "u1", token: "secret-token"), into: AccountSnapshot())
        XCTAssertThrowsError(try store.save(book)) { XCTAssertEqual($0 as? AccountStoreError, .keychain(errSecInteractionNotAllowed)) }
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path), "the token must not land in a plain file")
        XCTAssertNil(defaults.data(forKey: store.service), "the token must not land in defaults")
        XCTAssertEqual(store.load(), AccountSnapshot())
    }

    func testSuccessfulKeychainSaveDeletesALeftoverFallbackCopy() throws {
        let keychain = FakeKeychain(status: errSecMissingEntitlement)
        let (store, file, defaults) = store(keychain, simulator: false)
        try store.save(AccountBook.signIn(account(home, "u1", token: "old-token"), into: AccountSnapshot()))
        keychain.status = errSecSuccess
        try store.save(AccountSnapshot())
        XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
        XCTAssertNil(defaults.data(forKey: store.service))
        XCTAssertEqual(store.load(), AccountSnapshot(), "a signed-out token must not come back")
    }
}
