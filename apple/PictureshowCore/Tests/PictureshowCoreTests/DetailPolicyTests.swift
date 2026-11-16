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

    func testNewerCompletionReadMustMatchAndSucceed() {
        XCTAssertTrue(DetailFeedback.readCanReplaceCompletion(completionItem: "film", completionRead: 10, item: "film", read: 11, succeeded: true))
        XCTAssertFalse(DetailFeedback.readCanReplaceCompletion(completionItem: "film", completionRead: 10, item: "film", read: 10, succeeded: true))
        XCTAssertFalse(DetailFeedback.readCanReplaceCompletion(completionItem: "film", completionRead: 10, item: "episode", read: 11, succeeded: true))
        XCTAssertFalse(DetailFeedback.readCanReplaceCompletion(completionItem: "film", completionRead: 10, item: "film", read: 11, succeeded: false))
    }
    func testColdPendingAndCurrentRetryReasonAreIndependentOfPlaybackReturn() {
        XCTAssertTrue(DetailFeedback.needsPendingControls(loading: true, firstArrival: true, canQueue: true))
        XCTAssertFalse(DetailFeedback.needsPendingControls(loading: true, firstArrival: true, canQueue: true, returningFromPlayback: true))
        XCTAssertEqual(DetailFeedback.readBandText(retrying: true, reason: "Old timeout"), "Loading item. Please wait.")
        XCTAssertFalse(DetailFeedback.readHasFailure(readFailed: true, reason: nil, mutationReady: false))
        XCTAssertTrue(DetailFeedback.readHasFailure(readFailed: false, reason: "503", mutationReady: false))
    }
    func testRequestRediscoveryNeverRearmsAcceptance() {
        XCTAssertEqual(DetailFeedback.requestedActionLabel(target: "primary", newlyAccepted: false), "Play already requested")
        XCTAssertFalse(DetailFeedback.requestCueRemainsActive(active: true, readerLeftAction: true))
        XCTAssertFalse(DetailFeedback.requestCueRemainsActive(active: false, readerLeftAction: false))
        XCTAssertFalse(DetailFeedback.requestCueRemainsActive(active: true, readerLeftAction: false, requested: false))
    }
    func testOnlyConfirmedLeafSkipsARealDataRevision() {
        XCTAssertFalse(DetailFeedback.catalogRevisionNeedsRefresh(previous: 4, current: 4))
        XCTAssertTrue(DetailFeedback.catalogRevisionNeedsRefresh(previous: nil, current: 4))
        XCTAssertFalse(DetailFeedback.sessionNeedsRead(newRevision: false, refreshed: false, container: true, alreadyConfirmed: false))
        XCTAssertFalse(DetailFeedback.sessionNeedsRead(newRevision: true, refreshed: true, container: false, alreadyConfirmed: true))
        XCTAssertTrue(DetailFeedback.sessionNeedsRead(newRevision: true, refreshed: true, container: true, alreadyConfirmed: false))
        XCTAssertTrue(DetailFeedback.hasConfirmedLeafRead(readSucceeded: true, sameUserData: true, container: false))
        XCTAssertFalse(DetailFeedback.hasConfirmedLeafRead(readSucceeded: true, sameUserData: true, container: true))
        XCTAssertTrue(DetailFeedback.readRequiresDependents(type: "Season"))
        XCTAssertFalse(DetailFeedback.readRequiresDependents(type: "BoxSet"))
    }

    func testSharedRunnerRejectsUnknownOperationAndMissingRequiredInputs() async {
        let runner=SpecVectorTests()
        do { _ = try await runner.execute("future-operation",input:[:],context:[:]);XCTFail("Unknown operation was accepted") } catch {}
        do { _ = try await runner.execute("detail-empty",input:["itemType":"Season"],context:[:]);XCTFail("Missing required state became a default") } catch {}
        do { _ = try await runner.execute("external-subtitle-loading",input:["errorCount":1,"fullTimeline":1,"serverStartMs":0],context:[:]);XCTFail("Integer was accepted as boolean") } catch {}
    }
}
