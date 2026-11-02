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
}
