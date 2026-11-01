// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class DetailPolicyTests: XCTestCase {
    func testExternalSubtitlesOnlyLoadWhileSelected() {
        XCTAssertTrue(ExternalSubtitleLoading.shouldLoad(selectedIndex:4,streamIndex:4,released:false))
        XCTAssertFalse(ExternalSubtitleLoading.shouldLoad(selectedIndex:5,streamIndex:4,released:false))
        XCTAssertFalse(ExternalSubtitleLoading.shouldLoad(selectedIndex:nil,streamIndex:4,released:false))
        XCTAssertFalse(ExternalSubtitleLoading.shouldLoad(selectedIndex:-1,streamIndex:-1,released:false))
        XCTAssertFalse(ExternalSubtitleLoading.shouldLoad(selectedIndex:4,streamIndex:4,released:true))
    }

    func testRetryDelayCapsBeforeMultiplication() {
        XCTAssertEqual(ExternalSubtitleLoading.retryDelayMs(errorCount:Int.max),10_000)
        XCTAssertEqual(ExternalSubtitleLoading.retryDelayMs(errorCount:Int.min),1_000)
        XCTAssertEqual(ExternalSubtitleLoading.retryDelayMs(errorCount:4),4_000)
    }

    func testFailureEpisodeKeepsVisibleAndSpokenReasonTogether() {
        let initial=DetailFeedback.presentFailure(previous:nil,key:"read",message:"Timeout.")
        XCTAssertEqual(DetailFeedback.presentFailure(previous:initial,key:"read",message:"Server unavailable."),initial)
        XCTAssertNil(DetailFeedback.presentFailure(previous:initial,key:nil,message:"Late text"))
        XCTAssertEqual(DetailFeedback.presentFailure(previous:initial,key:"write:2",message:"Try again.")?.message,"Try again.")
    }

    func testSharedRunnerRejectsUnknownOperationAndMissingRequiredInputs() async {
        let runner=SpecVectorTests()
        do { _ = try await runner.execute("future-operation",input:[:],context:[:]);XCTFail("Unknown operation was accepted") } catch {}
        do { _ = try await runner.execute("detail-empty",input:["itemType":"Season"],context:[:]);XCTFail("Missing required state became a default") } catch {}
        do { _ = try await runner.execute("external-subtitle-loading",input:["errorCount":1,"fullTimeline":1,"serverStartMs":0],context:[:]);XCTFail("Integer was accepted as boolean") } catch {}
    }
}
