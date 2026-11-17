// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class PlayerExtrasAppTests: XCTestCase {
    /// PLAY-17/PLAY-30: what a viewing does at its physical end. With the countdown Off the card waits for Play now
    /// (it used to close the player); Hide and a last episode close; a due "still watching?" asks.
    func testNaturalEndWaitsOnTheCardWhenTheCountdownIsOff() {
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: false, automatic: false, stillWatchingDue: false), .waitForPlayNow)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: false, automatic: false, stillWatchingDue: true), .waitForPlayNow)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: false, automatic: true, stillWatchingDue: false), .playNext)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: false, automatic: true, stillWatchingDue: true), .askStillWatching)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: true, automatic: true, stillWatchingDue: false), .close)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: true, cardDismissed: true, automatic: false, stillWatchingDue: false), .close)
        XCTAssertEqual(NaturalEndAction.decide(hasNext: false, cardDismissed: false, automatic: true, stillWatchingDue: false), .close)
    }

    /// SUB-DL-01: a title page offers Find subtitles only for a playable video on the server.
    func testFindSubtitlesIsOfferedForVideosWithAFileOnly() {
        for type in ["Movie", "Episode", "Video", "MusicVideo"] {
            var item = MediaItem(id: "v", type: type)
            XCTAssertFalse(SubtitleDownloadOffer.offered(item), "\(type) with no file")
            item.mediaSources = [MediaSource(id: "src")]
            XCTAssertTrue(SubtitleDownloadOffer.offered(item), type)
            item.locationType = "Virtual"
            XCTAssertFalse(SubtitleDownloadOffer.offered(item), "missing \(type)")
        }
        for type in ["Series", "Season", "Audio", "TvChannel", "BoxSet"] {
            var item = MediaItem(id: "x", type: type)
            item.mediaSources = [MediaSource(id: "src")]
            XCTAssertFalse(SubtitleDownloadOffer.offered(item), type)
        }
    }
}
