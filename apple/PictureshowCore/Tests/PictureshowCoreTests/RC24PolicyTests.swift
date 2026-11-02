// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class RC24PolicyTests: XCTestCase {
    private func source(_ streams: [[String: Any]], defaultIndex: Int = 1) throws -> MediaSource {
        try JSONDecoder().decode(MediaSource.self, from: JSONSerialization.data(withJSONObject: [
            "DefaultSubtitleStreamIndex": defaultIndex, "MediaStreams": streams
        ]))
    }
    func testTextCaptionRankPrecedesDefaultsButPreservesAccessibilityRanks() throws {
        let image: [String: Any] = ["Type": "Subtitle", "Index": 1, "Language": "eng", "Codec": "pgssub", "IsDefault": true]
        for codec in ["SuBrIp", "ASS", "tx3g", "sami"] {
            let text: [String: Any] = ["Type": "Subtitle", "Index": 2, "Language": "eng", "Codec": codec]
            XCTAssertEqual(Captions.index(source: try source([image, text]), preferredLanguage: "en-US"), 2, codec)
        }
        let flagged: [String: Any] = ["Type": "Subtitle", "Index": 3, "Language": "eng", "Codec": "future", "IsTextSubtitleStream": true]
        XCTAssertEqual(Captions.index(source: try source([image, flagged]), preferredLanguage: "eng"), 3)
        var accessibleImage = image; accessibleImage["IsHearingImpaired"] = true
        XCTAssertEqual(Captions.index(source: try source([accessibleImage, flagged]), preferredLanguage: "en"), 1)
        var forcedText = flagged; forcedText["IsForced"] = true
        XCTAssertEqual(Captions.index(source: try source([image, forcedText]), preferredLanguage: "en"), 1)
        var unknown = flagged; unknown["IsTextSubtitleStream"] = false
        XCTAssertEqual(Captions.index(source: try source([image, unknown]), preferredLanguage: "en"), 1)
    }
    func testReadyAndUnavailableAreNotPersistentActionValues() {
        XCTAssertNil(DetailFeedback.actionDescription(currentState: nil, pending: false, available: true, ready: true, requested: false))
        XCTAssertNil(DetailFeedback.actionDescription(currentState: "Unwatched", pending: false, available: false, ready: false, requested: false))
        XCTAssertEqual(DetailFeedback.actionDescription(currentState: "Watched", pending: false, available: true, ready: true, requested: false), "Watched")
    }
    func testReadyBelongsOnlyToSameFocusedHeldEpisode() {
        var feedback = DetailReadyFeedback()
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true))
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true))
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true, usable: false))
        XCTAssertEqual(feedback.update(held: false, focused: "play", resumed: true), "Ready")
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true), "Periodic changes must not repeat Ready")
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true))
        XCTAssertNil(feedback.update(held: false, focused: "favorite", resumed: true), "Focus movement ends the held episode")
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true), "Returning later cannot revive the old cue")
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true))
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: false), "A suspended page cannot announce Ready")
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true))
        XCTAssertNil(feedback.update(held: true, focused: nil, resumed: true))
        XCTAssertNil(feedback.update(held: false, focused: nil, resumed: true), "No destination means no readiness speech")
    }

    func testFirstAppearanceObservesArrivalReadAndLaterReturnsRefresh() {
        var lifecycle = DetailReadLifecycle()
        XCTAssertFalse(lifecycle.resumed())
        XCTAssertTrue(lifecycle.resumed())
        XCTAssertTrue(lifecycle.resumed())
        XCTAssertEqual(DetailFeedback.heldActionLabel(target: "played"), "Watched")
        XCTAssertEqual(DetailFeedback.unavailableActionLabel(target: "from-start"), "Start unavailable")
    }

    func testTrackReportIntentIsIndependentAndKeepsZeroAndOff() {
        XCTAssertNil(TrackSelectionReporting.index(selectedIndex: 0, chosenByViewer: false))
        XCTAssertNil(TrackSelectionReporting.index(selectedIndex: -1, chosenByViewer: false))
        XCTAssertEqual(TrackSelectionReporting.index(selectedIndex: 0, chosenByViewer: true), 0)
        XCTAssertEqual(TrackSelectionReporting.index(selectedIndex: -1, chosenByViewer: true), -1)
        XCTAssertTrue(TrackSelection.unchanged(currentIndex: 0, pickedIndex: 0))
        XCTAssertTrue(TrackSelection.unchanged(currentIndex: -1, pickedIndex: -1))
        XCTAssertFalse(TrackSelection.unchanged(currentIndex: 0, pickedIndex: -1))
    }

}
