// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest

/// Explicit unported work from the main merge. These are skips, never successful policy executions.
final class PendingSpecVectorTests: XCTestCase {
    func testPendingCastQueue11Cases() throws { try pending("cast-queue") }
    func testPendingApplication95Cases() throws { try pending("application") }
    func testPendingLiveDvr22Cases() throws { try pending("live-dvr") }
    func testPendingLiveGuide25Cases() throws { try pending("live-guide") }
    func testPendingLivePresentation366Cases() throws { try pending("live-presentation") }
    func testPendingLiveSource11Cases() throws { try pending("live-source") }
    func testPendingPlayerOverlayLayout8Cases() throws { try pending("player-overlay-layout") }
    func testPendingReference19Cases() throws { try pending("reference") }
    private func pending(_ suite: String) throws {
        let metadata = try XCTUnwrap(PendingSpecCaseManifest.suites[suite])
        throw XCTSkip("\(suite): \(metadata.count) known pending cases. \(metadata.reason)")
    }
    func testPendingFrameRateMatch6Cases() throws { try pending("frame-rate-match") }
    func testPendingPlaybackEngine19Cases() throws { try pending("playback-engine") }
    func testPendingPlaybackSettingsLayout7Cases() throws { try pending("playback-settings-layout") }
    func testPendingPlaybackSettings68Cases() throws { try pending("playback-settings") }
    func testPendingPlaybackStart4Cases() throws { try pending("playback-start") }
    func testPendingPlayerQuality14Cases() throws { try pending("player-quality") }
    func testPendingSettingsPresentation65Cases() throws { try pending("settings-presentation") }
}
