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

    func testStoreRoundTripsTheSnapshot() {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("accounts-\(UUID().uuidString).json")
        let store = AccountStore(service: "app.pictureshow.tests.\(UUID().uuidString)", fileURL: file)
        let book = AccountBook.signIn(account(home, "u1", token: "secret-token"), into: AccountSnapshot())
        store.save(book)
        XCTAssertEqual(store.load(), book)
    }
}
