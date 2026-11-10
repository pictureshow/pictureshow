// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class PlaybackInformationTests: XCTestCase {
    func testCodecAndResolutionNeverInventMissingValues() {
        XCTAssertEqual(PlaybackInformation.codec("h264"), "H.264")
        XCTAssertEqual(PlaybackInformation.codec("aac_5.1"), "AAC_5.1")
        XCTAssertEqual(PlaybackInformation.codec("vp9/encoded"), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec(String(repeating: "a", count: 33)), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec(nil), "Not reported")
        XCTAssertEqual(PlaybackInformation.resolution(width: 1920, height: 1080), "1920 × 1080")
        XCTAssertEqual(PlaybackInformation.resolution(width: 0, height: 1080), "Not reported")
        XCTAssertEqual(PlaybackInformation.resolution(width: 1920, height: nil), "Not reported")
    }

    func testCodecAliasesShareOneNameAndDoNotChangeContainers() {
        for alias in ["h264", "H.264", "avc", "avc1", "AVC3"] {
            XCTAssertEqual(PlaybackInformation.codec(alias), "H.264", alias)
        }
        for alias in ["hevc", "H265", "h.265", "hvc1", "hev1"] {
            XCTAssertEqual(PlaybackInformation.codec(alias), "HEVC", alias)
        }
        for (alias, name) in [("av01", "AV1"), ("vp08", "VP8"), ("vp09", "VP9"), ("mpeg2video", "MPEG-2"), ("wvc1", "VC-1"), ("ac3", "AC-3"), ("ec-3", "E-AC-3")] {
            XCTAssertEqual(PlaybackInformation.codec(alias), name)
        }
        XCTAssertEqual(PlaybackInformation.codec("future_codec"), "FUTURE_CODEC")
        XCTAssertEqual(PlaybackInformation.container("avc1"), "AVC1")
        XCTAssertEqual(PlaybackInformation.container("mkv"), "MKV")
        XCTAssertEqual(PlaybackInformation.container("mp4,mov"), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec("h264/token"), "Not reported")
        XCTAssertEqual(PlaybackInformation.codec("https://media.example.test"), "Not reported")
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
