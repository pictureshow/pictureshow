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

    /// PLAY-17 (Android DetailFinishedLineTest): the page open under the player shows the line, a later visit doesn't,
    /// leaving that page ends it, and a new viewing ends an earlier one.
    func testFinishedLineBelongsToThePageTheViewingReturnsTo() {
        var ledger = FinishedViewings()
        let film = MediaItem(id: "film", name: "Invented film", type: "Movie")
        let under = ledger.serial
        XCTAssertNil(ledger.line(detailID: "film", arrival: under))
        ledger.finished(film)
        XCTAssertEqual(ledger.line(detailID: "film", arrival: under), "Finished. You watched Invented film to the end.")
        XCTAssertNil(ledger.line(detailID: "film", arrival: ledger.serial), "a page opened after the end is a later visit")
        ledger.clear(detailID: "other")
        XCTAssertNotNil(ledger.line(detailID: "film", arrival: under), "an unrelated page leaving keeps the line")
        ledger.clear(detailID: "film")
        XCTAssertNil(ledger.line(detailID: "film", arrival: under))

        var finale = MediaItem(id: "finale", name: "  Invented finale ", type: "Episode")
        finale.seriesId = "series"; finale.seasonId = "season"
        ledger.finished(finale)
        XCTAssertEqual(ledger.line(detailID: "series", arrival: under), "Finished. You watched Invented finale to the end.")
        XCTAssertNotNil(ledger.line(detailID: "season", arrival: under))
        XCTAssertNil(ledger.line(detailID: "film", arrival: under))
        ledger.clear()
        XCTAssertNil(ledger.line(detailID: "series", arrival: under), "a new viewing ends an earlier line")
    }

    func testFinishedPagePlaysAgainButASeriesKeepsNamingItsEpisode() {
        let film = MediaItem(id: "film", type: "Movie", userData: ItemUserData(playbackPositionTicks: 99))
        XCTAssertEqual(CatalogPresentation.playLabel(container: film, target: film), "Resume")
        XCTAssertEqual(CatalogPresentation.playLabel(container: film, target: film, finished: true), "Play again")
        let series = MediaItem(id: "series", type: "Series")
        var first = MediaItem(id: "e1", name: "Pilot", type: "Episode")
        first.parentIndexNumber = 1; first.indexNumber = 1
        XCTAssertEqual(CatalogPresentation.playLabel(container: series, target: first, finished: true), "Play S1:E1 · Pilot")
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
