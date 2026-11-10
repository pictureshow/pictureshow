// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class CatchupPolicyTests: XCTestCase {
    func testManifestRejectsUnknownIDsAndMismatchedContracts() throws {
        XCTAssertEqual(SpecCaseManifest.contracts.count, 1442)
        XCTAssertThrowsError(try SpecCaseManifest.validate(id: "music-session.invented",suite: "music-session",operation: "session"))
        let id = try XCTUnwrap(SpecCaseManifest.contracts.keys.first)
        XCTAssertThrowsError(try SpecCaseManifest.validate(id: id,suite: "unknown-suite",operation: "unknown-operation"))
    }
    func testSeasonRetentionRequiresTheSameMountedCard() {
        XCTAssertTrue(DetailAvailability.retainsSeasonFocus(selectedSection: "seasons", restored: true, semanticsRevealed: true, selectedId: "specials", seasonIds: ["specials", "s1"]))
        XCTAssertFalse(DetailAvailability.retainsSeasonFocus(selectedSection: "seasons", restored: true, semanticsRevealed: true, selectedId: "specials", seasonIds: ["replacement"]))
        XCTAssertFalse(DetailAvailability.retainsSeasonFocus(selectedSection: "seasons", restored: true, semanticsRevealed: false, selectedId: "specials", seasonIds: ["specials"]))
    }
    func testEveryHeldGridMoveEndsEvenAfterFailureOrAnEmptyPage() {
        for count in 1...24 {
            for index in 0..<count {
                XCTAssertNotEqual(GridNavigation.holdRelease(index: index, columns: 5, count: count), .hold)
                XCTAssertNotEqual(GridNavigation.holdRelease(index: index, columns: 5, count: count, pageFailed: true), .hold)
            }
        }
        XCTAssertEqual(GridNavigation.verticalMove(index: 4, columns: 5, count: 8, down: true, loadingMore: true), .hold)
        XCTAssertEqual(GridNavigation.holdRelease(index: 4, columns: 5, count: 8), .card(7))
        XCTAssertEqual(GridNavigation.holdRelease(index: 7, columns: 5, count: 8, pageFailed: true), .pageRetry)
        XCTAssertEqual(GridNavigation.holdRelease(index: 0, columns: Int.max, count: 1), .stay)
    }
    func testCopyingVideoSuppressesDeviceNoticeButBurningCaptionsStillExplainsIt() {
        let source = MediaSource(id: "source")
        XCTAssertNil(ConversionNotice.of(method: "Transcode", url: "https://media.example.test/video?VideoCodec=copy", source: source, reasons: ["VideoCodecNotSupported"]))
        let notice = ConversionNotice.of(method: "Transcode", url: "https://media.example.test/video?VideoCodec=h264", source: source, reasons: ["VideoCodecNotSupported"], device: "this TV")
        XCTAssertEqual(notice?.fix, "A 1080p H.264 copy on the server would play without conversion.")
        XCTAssertEqual(notice?.reason, "This video format cannot play on this TV.")
        XCTAssertNil(ConversionNotice.of(method: "Transcode", url: "https://media.example.test/video", source: source, reasons: ["ContainerBitrateExceedsLimit"]))
    }
    func testEveryVideoNoticeExplainsOngoingConversionAndRetainsItsSpecificRemedy() throws {
        let cases = [
            ("VideoCodecNotSupported", "A 1080p H.264 copy"),
            ("VideoRangeTypeNotSupported", "An SDR copy"),
            ("VideoProfileNotSupported", "An 8-bit 1080p H.264 copy"),
            ("VideoResolutionNotSupported", "A 1080p copy"),
            ("VideoFramerateNotSupported", "A 30 fps copy"),
            ("InterlacedVideoNotSupported", "A deinterlaced copy"),
            ("AnamorphicVideoNotSupported", "A square-pixel copy"),
            ("VideoRotationNotSupported", "An upright copy")
        ]
        for (code, copy) in cases {
            let notice = try XCTUnwrap(ConversionNotice.of(method: "Transcode", url: "https://media.example.test/video?VideoCodec=h264", source: MediaSource(id: "source"), reasons: [code], device: "this phone"))
            XCTAssertEqual(notice.title, "Converting this video for this phone", code)
            XCTAssertTrue(notice.reason.contains("this phone"), code)
            let visibleBody = "\(notice.reason) \(notice.fix) Don’t show again this viewing"
            XCTAssertLessThanOrEqual(visibleBody.split(separator: " ").count, 30, code)
            XCTAssertEqual(notice.fix, "\(copy) on the server would play without conversion.")
            XCTAssertFalse(notice.reason.contains("won't play"), code)
        }
    }
    func testNewOperationsRejectMalformedPolicyInputs() async throws {
        let runner = SpecVectorTests()
        for (operation, input) in [
            ("conversion-notice", ["method":"bad", "url":"https://media.example.test", "reasons":[], "device":"this TV"] as [String:Any]),
            ("grid-vertical-outcome", ["index":0,"columns":4,"count":3,"down":"true","loadingMore":false]),
            ("grid-vertical-unknown", [:])
        ] {
            do { _ = try await runner.execute(operation,input:input,context:[:]); XCTFail("Accepted malformed \(operation)") } catch { }
        }
    }
    func testQueueRejectsUnknownAutomaticEntryAndMalformedInt64() throws {
        let state: [String:Any] = ["server":"s","viewer":"v","entries":[["id":"a","media":"m"]],"order":["a"],"current":"a","automatic":["missing"]]
        XCTAssertThrowsError(try ControlReducers.reduce(suite:"queue-edit",operation:"queue",state:state,event:["op":"pause"]))
        for value in ["01","9223372036854775808","-0"] {
            XCTAssertThrowsError(try ControlReducers.reduce(suite:"queue-edit",operation:"queue",state:["server":"s","viewer":"v"],event:["op":"pause","revision":value]))
        }
    }
}
