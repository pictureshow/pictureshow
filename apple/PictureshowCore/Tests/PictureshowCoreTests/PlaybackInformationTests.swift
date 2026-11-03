// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class PlaybackInformationTests: XCTestCase {
    func testCodecAndResolutionNeverInventMissingValues() {
        XCTAssertEqual(PlaybackInformation.codec("h264"), "H264")
        XCTAssertEqual(PlaybackInformation.codec("aac_5.1"), "AAC_5.1")
        XCTAssertEqual(PlaybackInformation.codec("vp9/encoded"), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec(String(repeating: "a", count: 33)), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec(nil), "Not reported")
        XCTAssertEqual(PlaybackInformation.resolution(width: 1920, height: 1080), "1920 × 1080")
        XCTAssertEqual(PlaybackInformation.resolution(width: 0, height: 1080), "Not reported")
        XCTAssertEqual(PlaybackInformation.resolution(width: 1920, height: nil), "Not reported")
    }

    func testBitrateFormattingMatchesThePortablePolicy() {
        XCTAssertEqual(PlaybackInformation.bitrate(999_999), "999 kbps")
        XCTAssertEqual(PlaybackInformation.bitrate(1_000_000), "1.00 Mbps")
        XCTAssertEqual(PlaybackInformation.bitrate(1_005_000), "1.01 Mbps")
        XCTAssertEqual(PlaybackInformation.bitrate(Int64.max), "9223372036854.78 Mbps")
        XCTAssertEqual(PlaybackInformation.bitrate(nil as Int64?), "Not reported")
    }

    func testReasonsAreDeduplicatedAndEmptyTranscodesStayExplained() {
        XCTAssertEqual(PlaybackInformation.reasons([], method: "DirectPlay"), [])
        XCTAssertEqual(
            PlaybackInformation.reasons(["VideoCodecNotSupported", "videocodecnotsupported"], method: "Transcode"),
            ["This device cannot decode this video format. The server converts the video. Try a version with a supported video codec."]
        )
        XCTAssertEqual(
            PlaybackInformation.reasons([], method: "Transcode"),
            ["The server did not report why it is converting this stream."]
        )
    }

    func testMethodDoesNotTreatDuplicateCopyParametersAsRemux() {
        XCTAssertEqual(PlaybackInformation.method("Transcode", url: "https://media.example.test/video?VideoCodec=copy&AudioCodec=copy"), "Direct stream (remux)")
        XCTAssertEqual(PlaybackInformation.method("Transcode", url: "https://media.example.test/video?VideoCodec=copy&VideoCodec=h264&AudioCodec=copy"), "Transcode")
    }

    func testSpeedLabelsAndGuidanceMatchTheViewingPolicy() {
        XCTAssertEqual(PlaybackSpeeds.label(1), "1×")
        XCTAssertEqual(PlaybackSpeeds.label(0.75), "0.75×")
        XCTAssertEqual(PlaybackSpeeds.label(Double.infinity), "inf×")
        XCTAssertEqual(PlaybackSpeeds.warning(isTranscoding: true), PlaybackSpeeds.transcodeNotice)
        XCTAssertNil(PlaybackSpeeds.warning(isTranscoding: false))
        XCTAssertTrue(PlaybackSpeeds.unsupportedOutputNotice.contains("reopen playback"))
    }
}
