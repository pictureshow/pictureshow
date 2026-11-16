// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class CatalogPresentationTests: XCTestCase {
    func testMissingEpisodeNeverOffersPlayOrRestartEvenWithSavedProgress() {
        for type in ["Episode", "Movie", "Video"] {
            var item = MediaItem(id: "missing", type: type, userData: ItemUserData(playbackPositionTicks: 99))
            item.locationType = "Virtual"
            XCTAssertFalse(CatalogPresentation.offersPlayback(item))
            XCTAssertFalse(CatalogPresentation.offersRestart(item))
            XCTAssertNotNil(CatalogPresentation.missingLabel(item))
            item.locationType = "FileSystem"
            XCTAssertTrue(CatalogPresentation.offersPlayback(item))
            XCTAssertTrue(CatalogPresentation.offersRestart(item))
        }
    }

    func testSeasonControlNamesAndResumesFirstUnfinishedAvailableEpisode() throws {
        let season = MediaItem(id: "season", type: "Season")
        var missing = MediaItem(id: "e1", type: "Episode")
        missing.locationType = "Virtual"
        let watched = MediaItem(id: "e2", type: "Episode", userData: ItemUserData(played: true))
        var unfinished = MediaItem(id: "e3", name: "Episode 3", type: "Episode", userData: ItemUserData(playbackPositionTicks: 99))
        unfinished.parentIndexNumber = 2; unfinished.indexNumber = 3
        let target = try XCTUnwrap(Catalog.detailPlayItem(item: season, nextUp: [], episodes: [missing, watched, unfinished]))
        XCTAssertEqual(target.id, "e3")
        XCTAssertEqual(CatalogPresentation.playLabel(container: season, target: target), "Resume S2:E3 · Episode 3")
        XCTAssertNil(Catalog.detailPlayItem(item: season, nextUp: [], episodes: [missing]))
    }

    func testHomeCacheSurvivesReloadAndKeepsViewersSeparate() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "pictureshow.catalog.tests.\(UUID().uuidString)"))
        let layouts = ["server|a": HomePreferences(mergeWatching: false), "server|b": HomePreferences()]
        HomeLayoutCache.save(layouts, defaults: defaults)
        HomeLayoutCache.savePending(["server|a"], defaults: defaults)
        XCTAssertEqual(HomeLayoutCache.pending(defaults: defaults), ["server|a"])
        XCTAssertEqual(HomeLayoutCache.load(defaults: defaults), layouts)
        XCTAssertTrue(HomePreferences().mergeWatching)
        defaults.removeObject(forKey: "home-layouts.v1")
        defaults.removeObject(forKey: "home-layouts.pending.v1")
    }

    func testExpiryAndVoluntarySignOutRemainDistinctAfterStorageRoundTrip() throws {
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!), userId: "u", username: "User", accessToken: "token", deviceID: "d")
        let book = AccountBook.signIn(account, into: AccountSnapshot())
        let expired = AccountBook.expire(account.key, from: book)
        let signedOut = AccountBook.signOut(account.key, from: book)
        XCTAssertFalse(expired.accounts[0].signedOut)
        XCTAssertTrue(signedOut.accounts[0].signedOut)
        XCTAssertEqual(expired.accounts[0].accessToken, "")
        XCTAssertEqual(try JSONDecoder().decode(AccountSnapshot.self, from: JSONEncoder().encode(signedOut)), signedOut)
        XCTAssertFalse(AccountBook.signIn(account, into: signedOut).accounts[0].signedOut)
    }
}
