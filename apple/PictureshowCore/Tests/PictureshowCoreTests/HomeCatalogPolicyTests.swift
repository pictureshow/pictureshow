// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class HomeCatalogPolicyTests: XCTestCase {
    func testLegacyAccountAndHomeDocumentsDecodeWithoutNewFields() throws {
        let json = #"{"server":{"url":"https://example.invalid","id":"s","name":"Server","version":"12"},"userId":"u","username":"Viewer","accessToken":"token","deviceID":"d"}"#
        let account = try JSONDecoder().decode(Account.self, from: Data(json.utf8))
        XCTAssertFalse(account.signedOut)
        XCTAssertEqual(try JSONDecoder().decode(HomePreferences.self, from: Data("{}".utf8)).mergeWatching, true)
        var p = HomePreferences(mergeWatching: false); p.landing = "libraries"
        let before: [String: JSONValue] = ["CustomPrefs": .object(["other-client": .string("keep")]), "future": .integer(42)]
        let after = try Catalog.homePreferencesDocument(before: before, preferences: p)
        XCTAssertEqual(Catalog.readHomePreferences(document: after), p)
        XCTAssertEqual(after["future"], .integer(42))
        if case .object(let custom) = after["CustomPrefs"] { XCTAssertEqual(custom["other-client"], .string("keep")) }
        else { XCTFail("Lost custom preferences") }
    }
    func testMissingDatesAndCaseInsensitiveVirtualValues() {
        var missing = MediaItem(id: "virtual", type: "Episode"); missing.locationType = "vIrTuAl"
        for date in [nil, "2027-00-15", "2027-13-01", "2027-01-00", "2027-01-32", "bad"] {
            missing.premiereDate = date
            XCTAssertEqual(Catalog.missingItemLabel(item: missing, today: "2026-11-16"), "Not in your library")
        }
        XCTAssertNil(Catalog.detailPlayItem(item: missing, nextUp: [], episodes: []))
        let present = MediaItem(id: "present", type: "Episode")
        var season = MediaItem(id: "season", type: "Season"); season.locationType = nil
        XCTAssertEqual(Catalog.detailPlayItem(item: season, nextUp: [missing], episodes: [missing, present])?.id, "present")
    }
    func testLocalCommitFailurePreventsRemoteWrite() async {
        var remoteWrites = 0
        let before = HomePreferences(), next = HomePreferences(mergeWatching: false)
        let result = await HomePreferenceCommit.change(current: before, next: next, saveLocal: { _, _ in throw ClientError.invalidResponse }) { remoteWrites += 1 }
        XCTAssertEqual(remoteWrites, 0)
        XCTAssertEqual(result.preferences, before)
        XCTAssertTrue(result.warned)
        XCTAssertFalse(result.pending)
    }
}
