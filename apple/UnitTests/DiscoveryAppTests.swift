// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

@MainActor final class DiscoveryAppTests: XCTestCase {
    private func defaults() -> UserDefaults {
        let name = "discovery-tests-\(UUID().uuidString)"
        let store = UserDefaults(suiteName: name)!
        store.removePersistentDomain(forName: name)
        return store
    }

    func testViewingSettingsDefaultAndPersist() {
        let store = defaults()
        let first = ViewingSettings(defaults: store)
        XCTAssertEqual(first.countdownSeconds, 10)
        XCTAssertEqual(first.stillWatching, .threeEpisodes)
        XCTAssertEqual(first.slideshowSeconds, 5)
        XCTAssertEqual(first.screensaverMinutes, 5)
        XCTAssertNil(first.screensaverMaxAge)
        first.countdownSeconds = 20; first.stillWatching = .eightEpisodes; first.slideshowSeconds = 10
        first.screensaverMinutes = 0; first.screensaverMaxAge = 13
        let second = ViewingSettings(defaults: store)
        XCTAssertEqual(second.countdownSeconds, 20)
        XCTAssertEqual(second.stillWatching, .eightEpisodes)
        XCTAssertEqual(second.slideshowSeconds, 10)
        XCTAssertEqual(second.screensaverMinutes, 0)
        XCTAssertEqual(second.screensaverMaxAge, 13)
        second.screensaverMaxAge = nil
        XCTAssertNil(ViewingSettings(defaults: store).screensaverMaxAge)
    }

    func testViewingSettingsRejectValuesOutsideTheChoices() {
        let store = defaults()
        store.set(7, forKey: "viewing.countdown"); store.set("Sometimes", forKey: "viewing.stillWatching")
        store.set(4, forKey: "viewing.slideshow"); store.set(3, forKey: "viewing.screensaver"); store.set(12, forKey: "viewing.screensaverAge")
        let settings = ViewingSettings(defaults: store)
        XCTAssertEqual(settings.countdownSeconds, 10)
        XCTAssertEqual(settings.stillWatching, .threeEpisodes)
        XCTAssertEqual(settings.slideshowSeconds, 5)
        XCTAssertEqual(settings.screensaverMinutes, 5)
        XCTAssertNil(settings.screensaverMaxAge)
    }

    /// PLAY-30: the ending episode counts, plus each that started by itself since the last input; any input resets.
    func testStillWatchingCountsAutomaticStartsAndResetsOnInput() {
        let input = ViewerInput()
        let now = Date()
        input.reset(lastInput: now)
        XCTAssertFalse(input.stillWatchingDue(policy: .threeEpisodes, now: now))
        input.startedByItself()
        XCTAssertFalse(input.stillWatchingDue(policy: .threeEpisodes, now: now))
        input.startedByItself()
        XCTAssertTrue(input.stillWatchingDue(policy: .threeEpisodes, now: now))
        XCTAssertFalse(input.stillWatchingDue(policy: .off, now: now))
        input.record()
        XCTAssertFalse(input.stillWatchingDue(policy: .threeEpisodes))
        input.reset(lastInput: now.addingTimeInterval(-90 * 60))
        XCTAssertTrue(input.stillWatchingDue(policy: .threeEpisodes, now: now))
        XCTAssertFalse(input.stillWatchingDue(policy: .fiveEpisodes, now: now))
    }

    func testStillWatchingDetailNamesTheTime() {
        XCTAssertEqual(ViewingSettings.stillWatchingTitle(.off), "Never")
        XCTAssertEqual(ViewingSettings.stillWatchingTitle(.fiveEpisodes), "After 5 episodes")
        XCTAssertTrue(ViewingSettings.stillWatchingDetail(.twoEpisodes).contains("1 hour "))
        XCTAssertTrue(ViewingSettings.stillWatchingDetail(.fiveEpisodes).contains("2½ hours"))
        XCTAssertTrue(ViewingSettings.stillWatchingDetail(.eightEpisodes).contains("4 hours"))
    }

    /// CAT-30: an online trailer goes to YouTube when it is a YouTube video; elsewhere to its own address (touch only).
    func testRemoteTrailerTargets() {
        let youTube = Discovery.trailerChoices(local: [], remote: [RemoteTrailer(url: "https://youtu.be/HOfdboHvshg")]).first!
        let other = Discovery.trailerChoices(local: [], remote: [RemoteTrailer(url: "https://trailers.example.test/a.mp4")]).first!
        #if os(tvOS)
        XCTAssertEqual(TrailersShelf.openTargets(youTube).first?.scheme, "youtube")
        XCTAssertTrue(TrailersShelf.openTargets(other).isEmpty)
        #else
        XCTAssertEqual(TrailersShelf.openTargets(youTube), [URL(string: "https://www.youtube.com/watch?v=HOfdboHvshg")!])
        XCTAssertEqual(TrailersShelf.openTargets(other), [URL(string: "https://trailers.example.test/a.mp4")!])
        #endif
    }

    /// PHOTO-01: a photo opens the viewer, with the grid's sort; a random grid falls back to name order.
    func testPhotoRouteKeepsGridOrder() {
        var photo = MediaItem(id: "p", name: "Shot", type: "Photo")
        photo.parentId = "f"
        XCTAssertEqual(photo.route, .photo(photo, sortBy: "SortName", descending: false))
        var query = LibraryQuery(parentID: "lib", collectionType: "homevideos")
        query.sort = .dateAdded; query.descending = true
        XCTAssertEqual(photo.route(in: query), .photo(photo, sortBy: "DateCreated,SortName", descending: true))
        query.sort = .random
        XCTAssertEqual(photo.route(in: query), .photo(photo, sortBy: "SortName", descending: false))
        XCTAssertEqual(MediaItem(id: "a", name: "Album", type: "PhotoAlbum").route, .library(MediaItem(id: "a", name: "Album", type: "PhotoAlbum")))
        XCTAssertEqual(MediaItem(id: "m", name: "Film", type: "Movie").route(in: query), .item(MediaItem(id: "m", name: "Film", type: "Movie")))
    }

    func testExtraSubtitleNamesKindAndLength() {
        var extra = MediaItem(id: "x", name: "Making of", type: "Video", runTimeTicks: 4_320_900_000)
        extra.extraType = "BehindTheScenes"
        XCTAssertEqual(ExtrasShelf.subtitle(extra), "Behind the scenes · 7m 12s")
        extra.runTimeTicks = 310_000_000
        XCTAssertEqual(ExtrasShelf.subtitle(extra), "Behind the scenes · 31 s")
    }
}
