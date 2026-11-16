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

    func testSignOutKeepsTheCardWithoutItsTokenAndRemoveForgetsIt() {
        var book = AccountBook.signIn(account(home, "u1"), into: AccountSnapshot())
        book = AccountBook.signIn(account(home, "u2"), into: book)
        book = AccountBook.signOut(account(home, "u2").key, from: book)
        XCTAssertNil(book.selected, "the signed-out viewer must not stay selected")
        XCTAssertNil(AccountBook.selected(in: book))
        XCTAssertEqual(book.accounts.map(\.userId), ["u1", "u2"])
        XCTAssertEqual(book.accounts.map(\.accessToken), ["t", ""])
        XCTAssertEqual(book.servers.map(\.url), [home.url])
        book = AccountBook.remove(account(home, "u2").key, from: book)
        XCTAssertEqual(book.accounts.map(\.userId), ["u1"])
    }

    /// Runs spec/vectors/account-sign-out.json (AUTH-10) against AccountBook and the real store's
    /// persistence. Apple keeps no per-viewer search history, home preferences, pending writes or
    /// retired credentials, so those Android stores have nothing to compare here.
    func testAccountSignOutVectors() throws {
        var spec = URL(fileURLWithPath: #filePath)
        for _ in 0..<3 { spec.deleteLastPathComponent() }
        let file = spec.appendingPathComponent("spec/vectors/account-sign-out.json")
        let document = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: file)) as? [String: Any])
        let cases = try XCTUnwrap(document["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 7)
        for vector in cases {
            let id = try XCTUnwrap(vector["id"] as? String)
            let input = try XCTUnwrap(vector["input"] as? [String: Any])
            let expected = try XCTUnwrap(vector["expected"] as? [String: Any])
            func viewer(_ name: String, token: String? = nil) -> Account {
                Account(server: home, userId: name, username: name, accessToken: token ?? "token-\(name)", deviceID: "d-\(name)")
            }
            let (store, _, _) = store(FakeKeychain(status: errSecSuccess), simulator: false)
            var book = AccountSnapshot()
            for name in try XCTUnwrap(input["accounts"] as? [String]) { book = AccountBook.signIn(viewer(name), into: book) }
            book.selected = (input["current"] as? String).map { viewer($0).key }
            for step in try XCTUnwrap(input["steps"] as? [[String: Any]]) {
                let name = step["account"] as? String ?? ""
                switch step["action"] as? String {
                case "sign-out": book = AccountBook.signOut(viewer(name).key, from: book)
                case "remove": book = AccountBook.remove(viewer(name).key, from: book)
                case "sign-in": book = AccountBook.signIn(viewer(name, token: "fresh-\(name)"), into: book)
                case "reload": try store.save(book); book = store.load()
                default: XCTFail("\(id): unknown action \(step)")
                }
            }
            let accounts = try XCTUnwrap(expected["accounts"] as? [[String: Any]])
            XCTAssertEqual(book.accounts.map(\.userId), accounts.map { $0["id"] as? String ?? "" }, id)
            XCTAssertEqual(book.accounts.map(\.accessToken), accounts.map { $0["token"] as? String ?? "" }, id)
            XCTAssertEqual(book.accounts.map { !$0.isSignedIn }, accounts.map { $0["expired"] as? Bool ?? false }, id)
            XCTAssertEqual(AccountBook.selected(in: book)?.userId, expected["current"] as? String, id)
        }
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
