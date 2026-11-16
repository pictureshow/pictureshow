// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class RC28PolicyTests: XCTestCase {
    func testReadyRequiresHeldLabelPresentationAndSameSelection() {
        var unpresented = DetailReadyFeedback()
        XCTAssertNil(unpresented.update(held: true, focused: "play", resumed: true))
        XCTAssertNil(unpresented.update(held: false, focused: "play", resumed: true, presented: false))
        var presented = DetailReadyFeedback()
        XCTAssertNil(presented.update(held: true, focused: "play", resumed: true, presented: true))
        XCTAssertNil(presented.update(held: false, focused: "favorite", resumed: true, presented: false))
        XCTAssertNil(presented.update(held: false, focused: "play", resumed: true, presented: false))
    }
    func testReadyRejectedSubmissionCanRetryAndDepartureCancels() {
        var feedback = DetailReadyFeedback()
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true, presented: true))
        XCTAssertEqual(feedback.update(held: false, focused: "play", resumed: true, presented: false, submitted: false), "Ready")
        XCTAssertEqual(feedback.update(held: false, focused: "play", resumed: true, presented: false), "Ready")
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true, presented: false))
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true, presented: true))
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: false, presented: false))
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true, presented: false))
    }
    func testFailedOrConsumedRequestCancelsReady() {
        var feedback = DetailReadyFeedback()
        XCTAssertNil(feedback.update(held: true, focused: "watched", resumed: true, presented: true))
        XCTAssertNil(feedback.update(held: false, focused: "watched", resumed: true, presented: false, cancelled: true))
        XCTAssertNil(feedback.update(held: false, focused: "watched", resumed: true, presented: false))
    }
    func testNativePresentationBeforePublicationIsRetainedOnlyForOwner() {
        var feedback = DetailReadyFeedback()
        XCTAssertNil(feedback.update(held: true, focused: "play", resumed: true, presented: false))
        XCTAssertNil(feedback.update(held: false, focused: "play", resumed: true, usable: false, presented: false, nativePresented: "favorite"))
        XCTAssertEqual(feedback.update(held: false, focused: "play", resumed: true, presented: false, nativePresented: "play"), "Ready")
    }
    func testRequestConsumesOnlyAcceptedNativeSubmission() {
        var feedback = DetailRequestOnceFeedback()
        XCTAssertFalse(feedback.update(request: 1, eligible: false))
        XCTAssertTrue(feedback.update(request: 1, eligible: true, submitted: false))
        XCTAssertTrue(feedback.update(request: 1, eligible: true))
        XCTAssertFalse(feedback.update(request: 1, eligible: true))
        XCTAssertTrue(feedback.update(request: 2, eligible: true))
        XCTAssertFalse(feedback.update(request: 1, eligible: true))
    }
    func testServerDefaultRetentionPrecedesUnknownOrDifferentCaptionLanguage() throws {
        let source = try JSONDecoder().decode(MediaSource.self, from: Data(#"{"DefaultSubtitleStreamIndex":4,"MediaStreams":[{"Type":"Subtitle","Index":3,"Language":"eng","Codec":"subrip"},{"Type":"Subtitle","Index":4,"Language":"ara","Codec":"pgssub"}]}"#.utf8))
        XCTAssertEqual(Captions.index(source: source, preferredLanguage: "en"), 4)
        XCTAssertEqual(Captions.index(source: source, preferredLanguage: "und"), 4)
        var off = source; off.defaultSubtitleStreamIndex = -1
        XCTAssertEqual(Captions.index(source: off, preferredLanguage: "en"), -1)
        var missing = source; missing.defaultSubtitleStreamIndex = 7
        XCTAssertEqual(Captions.index(source: missing, preferredLanguage: "en"), 3)
    }
    func testVisibilityReturnDoesNotClearPauseIntent() {
        XCTAssertFalse(PlaybackVisibility.playWhenReady(requested: true, foregroundRequired: true, foreground: false, pauseLatched: false))
        XCTAssertFalse(PlaybackVisibility.playWhenReady(requested: true, foregroundRequired: true, foreground: true, pauseLatched: true))
        XCTAssertTrue(PlaybackVisibility.playWhenReady(requested: true, foregroundRequired: true, foreground: true, pauseLatched: false))
        XCTAssertTrue(PlaybackVisibility.playWhenReady(requested: true, foregroundRequired: false, foreground: false, pauseLatched: false))
    }
    func testRawReadIsNotPlayedPresentation() {
        let raw = ItemUserData(played: false, isFavorite: true)
        var presented = raw
        presented.played = DetailFeedback.completionPresentedPlayed(played: raw.played, completionIntent: true)
        XCTAssertTrue(presented.played)
        XCTAssertTrue(presented.isFavorite)
        XCTAssertFalse(DetailFeedback.rawReadMatchesAcknowledgement(raw: raw, acknowledged: presented))
        XCTAssertFalse(DetailFeedback.completionIntentRetained(reportFailed: true, reportPending: true, rawReadSucceeded: false))
        XCTAssertTrue(DetailFeedback.completionIntentRetained(reportFailed: false, reportPending: true, rawReadSucceeded: true))
        XCTAssertFalse(DetailFeedback.completionIntentRetained(reportFailed: false, reportPending: false, rawReadSucceeded: true))
    }
    func testCueLifetimeExpiresAtBudgetAndAccessibilityOff() {
        XCTAssertTrue(DetailFeedback.retryCueCanWait(accessibilityEnabled: true, elapsedMillis: 29_999))
        XCTAssertFalse(DetailFeedback.retryCueCanWait(accessibilityEnabled: true, elapsedMillis: 30_000))
        XCTAssertFalse(DetailFeedback.retryCueCanWait(accessibilityEnabled: false, elapsedMillis: 0))
        XCTAssertFalse(DetailFeedback.retryFailureCue(baseline: 1, attempt: 1, loading: false, failed: true, resumed: true))
        XCTAssertTrue(DetailFeedback.retryFailureCue(baseline: 1, attempt: 2, loading: false, failed: true, resumed: true))
    }
}
