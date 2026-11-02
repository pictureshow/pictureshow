// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class SeekAndReportingPolicyTests: XCTestCase {
    func testPendingTargetAccumulatesUntilThePlayerClockResumes() {
        var pending: Int64? = nil
        for expected in [Int64(90_000), 100_000, 110_000] {
            pending = SeekSteps.target(positionMs: 80_000, pendingMs: pending, deltaMs: 10_000, durationMs: 180_000)
            XCTAssertEqual(pending, expected)
        }
        XCTAssertEqual(SeekSteps.target(positionMs: 112_000, pendingMs: nil, deltaMs: -10_000, durationMs: 180_000), 102_000)
    }

    func testOutOfBoundsPendingTargetIsClampedBeforeTheNextStep() {
        // Applying the delta before clamping would lose the viewer's reverse step.
        XCTAssertEqual(SeekSteps.target(positionMs: 50_000, pendingMs: 200_000, deltaMs: -10_000, durationMs: 180_000), 170_000)
        XCTAssertEqual(SeekSteps.target(positionMs: 50_000, pendingMs: -20_000, deltaMs: 10_000, durationMs: 180_000), 10_000)
        XCTAssertEqual(SeekSteps.target(positionMs: 50_000, pendingMs: 90_000, deltaMs: 10_000, durationMs: -1), 0)
    }

    func testReportIndicesAndSelectionOriginsRemainIndependent() {
        // Index zero and explicit Off survive reports even when their origins are automatic.
        XCTAssertEqual(PlaybackReportFields.describe(kind: "progress", positionTicks: 12_349_999, audio: 0, subtitle: -1, audioViewer: false, subtitleViewer: false),
                       "kind=progress positionMs=1234 audio=0 subtitle=-1 audioOrigin=automatic subtitleOrigin=automatic")
        // Stop removes indices while retaining each viewing's selection origin.
        XCTAssertEqual(PlaybackReportFields.describe(kind: "stop", positionTicks: 12_349_999, audio: 0, subtitle: -1, audioViewer: true, subtitleViewer: false),
                       "kind=stop positionMs=1234 audio=omitted subtitle=omitted audioOrigin=viewer subtitleOrigin=automatic")
    }
}
