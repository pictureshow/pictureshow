// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import AVFoundation
import CryptoKit
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class AppleHEVCQualificationTests: XCTestCase {
    func testBundledReferenceAndActualNativeQualificationFailClosedOrPublishMeasuredBound() async throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: "hevc-main8-1080p30", withExtension: "mp4"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(data.count, 616822)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), "49c05630450bf2f3b6ed25e8e377bdd0a1ab669e1e862f3361cbaa8856d4bb8e")
        let asset = AVURLAsset(url: url), tracks = try await asset.loadTracks(withMediaType: .video)
        XCTAssertEqual(tracks.count, 1)
        let track = try XCTUnwrap(tracks.first)
        let duration = try await asset.load(.duration), rate = try await track.load(.nominalFrameRate)
        XCTAssertEqual(CMTimeGetSeconds(duration), 2); XCTAssertEqual(rate, 30)
        let formats = try await track.load(.formatDescriptions)
        let format = try XCTUnwrap(formats.first)
        XCTAssertEqual(formats.count, 1); XCTAssertEqual(CMFormatDescriptionGetMediaSubType(format), kCMVideoCodecType_HEVC)
        let size = CMVideoFormatDescriptionGetDimensions(format)
        XCTAssertEqual(size.width, 1920); XCTAssertEqual(size.height, 1080)
        let atoms = try XCTUnwrap(CMFormatDescriptionGetExtension(format, extensionKey: kCMFormatDescriptionExtension_SampleDescriptionExtensionAtoms) as? [String: Any])
        let record = try XCTUnwrap(atoms["hvcC"] as? Data)
        XCTAssertTrue(AppleHEVCQualificationPolicy.matchesReference(configurationRecord: record, width: Int(size.width), height: Int(size.height)))
        let measurement = await AppleHEVCQualification.measureReference()
        if let measurement {
            let capabilities = try XCTUnwrap(AppleHEVCQualificationPolicy.capabilities(measured: measurement, displayWidth: 1920, displayHeight: 1080, displayRefreshRate: 60))
            XCTAssertTrue(ReferenceDeviceProfile.prefersHevcTranscode(capabilities))
            XCTAssertEqual(measurement.decodedFrameCount, 60); XCTAssertEqual(measurement.decodeErrorCount, 0)
            XCTAssertEqual(measurement.droppedFrameCount, 0); XCTAssertTrue(measurement.hardwareDecoder)
            print("F3_HEVC_QUALIFICATION measuredHardware=true decoded=\(measurement.decodedFrameCount) elapsedSeconds=\(measurement.elapsedSeconds) referenceHDQualified=true maximaClaim=false")
        } else {
            let profile = AppleProfile.json(supportsHEVC: true, hevcTranscodeCapabilities: nil)
            let targets = try XCTUnwrap(profile["TranscodingProfiles"] as? [[String: Any]])
            XCTAssertEqual(targets.count, 1); XCTAssertEqual(targets.first?["VideoCodec"] as? String, "h264")
            print("F3_HEVC_QUALIFICATION measuredHardware=false result=unavailableOrBusy fallbackAVC=true maximaClaim=false")
        }
    }
}
