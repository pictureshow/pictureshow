// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class AppleHEVCPolicyTests: XCTestCase {
    // Real authored qualification asset's hvcC; independently retained as test data.
    private let recordHex = "01016000000090000000000078f000fcfdf8f800000f03a00001001840010c01ffff016000000300900000030000030078928090a10001002e420101016000000300900000030000030078a003c08010e5964a924caf016a02020208000003000800000300f040a2000100074401c172b42240"
    private var record: Data {
        let chars = Array(recordHex); return Data(stride(from: 0, to: chars.count, by: 2).map { UInt8(String(chars[$0...$0+1]), radix: 16)! })
    }
    private func measured(record: Data? = nil, width: Int = 1920, height: Int = 1080,
                          source: Int = 60, decoded: Int = 60, dropped: Int = 0, errors: Int = 0,
                          elapsed: Double = 0.5, duration: Double = 2, rate: Double = 30,
                          hardware: Bool = true, primaries: String? = "ITU_R_709_2",
                          transfer: String? = "ITU_R_709_2", matrix: String? = "ITU_R_709_2") -> AppleHEVCDecodeMeasurement {
        .init(measuredConfigurationRecord: record ?? self.record, width: width, height: height,
              sourceFrameCount: source, decodedFrameCount: decoded, droppedFrameCount: dropped,
              decodeErrorCount: errors, elapsedSeconds: elapsed, sourceDurationSeconds: duration,
              sourceFrameRate: rate, hardwareDecoder: hardware, colorPrimaries: primaries,
              transferFunction: transfer, yCbCrMatrix: matrix)
    }
    private func qualify(_ m: AppleHEVCDecodeMeasurement, width: Int = 1920, height: Int = 1080, refresh: Double = 30) -> DeviceCapabilities? {
        AppleHEVCQualificationPolicy.capabilities(measured: m, displayWidth: width, displayHeight: height, displayRefreshRate: refresh)
    }
    func testCompleteHardwareDecodePublishesOnlyTheMeasuredMainLevel4Bound() throws {
        let caps = try XCTUnwrap(qualify(measured()))
        XCTAssertTrue(ReferenceDeviceProfile.prefersHevcTranscode(caps))
        XCTAssertEqual(caps.videoDecoders.count, 1)
        let decoder = try XCTUnwrap(caps.videoDecoders.first)
        XCTAssertEqual(decoder.codec, "HEVC"); XCTAssertEqual(decoder.profiles, ["Main"])
        XCTAssertEqual(decoder.maxLevel, .init(major: 4)); XCTAssertEqual(decoder.maxBitDepth, 8)
        XCTAssertEqual(decoder.maxWidth, 1920); XCTAssertEqual(decoder.maxHeight, 1080)
        XCTAssertEqual(decoder.maxFrameRate, 30); XCTAssertEqual(decoder.maxFrameRateAtMaxSize, 30)
        XCTAssertEqual(decoder.hardware, true)
        XCTAssertEqual(decoder.profileCapabilities?.count, 1)
        XCTAssertEqual(decoder.profileCapabilities?.first?.profile, "Main")
        XCTAssertEqual(decoder.profileCapabilities?.first?.maxLevel, .init(major: 4))
        XCTAssertEqual(decoder.profileCapabilities?.first?.maxBitDepth, 8)
    }
    func testIncompleteSoftwareSlowOrUnknownObservationsCannotQualify() {
        let failures: [(String, AppleHEVCDecodeMeasurement)] = [
            ("software", measured(hardware: false)), ("missing output", measured(decoded: 59)),
            ("extra output", measured(decoded: 61)), ("wrong source count", measured(source: 59)),
            ("dropped output", measured(dropped: 1)), ("decode error", measured(errors: 1)),
            ("slow", measured(elapsed: 2.001)), ("unknown elapsed", measured(elapsed: .nan)),
            ("infinite elapsed", measured(elapsed: .infinity)), ("zero elapsed", measured(elapsed: 0)),
            ("negative elapsed", measured(elapsed: -1)), ("wrong width", measured(width: 1280)),
            ("wrong height", measured(height: 720)), ("unknown primaries", measured(primaries: nil)),
            ("HDR transfer", measured(transfer: "SMPTE_ST_2084_PQ")), ("unknown matrix", measured(matrix: nil)),
            ("short timeline", measured(duration: 1)), ("unknown duration", measured(duration: .nan)),
            ("wrong authored rate", measured(rate: 60)), ("unknown rate", measured(rate: .nan))
        ]
        for (name, value) in failures { XCTAssertNil(qualify(value), name) }
    }
    func testConfigurationRecordMustBeActualMain8Level4SDR420() {
        for (name, offset, replacement) in [("version", 0, UInt8(2)), ("Main10", 1, 2),
                                           ("high tier", 1, 0x21), ("wrong level", 12, 123),
                                           ("wrong chroma", 16, 0xfe), ("luma10", 17, 0xfa), ("chroma10", 18, 0xfa)] {
            var changed = record; changed[offset] = replacement
            XCTAssertNil(qualify(measured(record: changed)), name)
        }
        XCTAssertNil(qualify(measured(record: Data())))
        XCTAssertNil(qualify(measured(record: record.prefix(22))))
        XCTAssertNil(qualify(measured(record: record.dropLast())))
    }
    func testSharedEligibilityStillRejectsUncoveredDisplayAndLevel() throws {
        let base = try XCTUnwrap(qualify(measured()))
        XCTAssertNil(qualify(measured(), width: 3840, height: 2160, refresh: 60))
        XCTAssertNil(qualify(measured(), width: 0))
        XCTAssertNil(qualify(measured(), refresh: .nan))
        var insufficient = base; insufficient.videoDecoders[0].hardware = false
        XCTAssertFalse(ReferenceDeviceProfile.prefersHevcTranscode(insufficient))
        insufficient = base; insufficient.videoDecoders[0].profileCapabilities?[0].maxLevel = .init(major: 3, minor: 1)
        XCTAssertFalse(ReferenceDeviceProfile.prefersHevcTranscode(insufficient))
        insufficient = base; insufficient.videoDecoders[0].maxFrameRate = 29
        XCTAssertFalse(ReferenceDeviceProfile.prefersHevcTranscode(insufficient))
        insufficient = base; insufficient.videoDecoders[0].profiles = ["Main 10"]; insufficient.videoDecoders[0].profileCapabilities = nil
        XCTAssertFalse(ReferenceDeviceProfile.prefersHevcTranscode(insufficient))
    }
    func testHEVCRecoveryRetainsOneAVCAttemptWithoutRepeatingIt() {
        XCTAssertTrue(ApplePlaybackRecovery.canRetry(fallbackUsed: false, hevcFallbackUsed: false, isHEVCTranscode: false))
        XCTAssertFalse(ApplePlaybackRecovery.canRetry(fallbackUsed: true, hevcFallbackUsed: false, isHEVCTranscode: false))
        XCTAssertTrue(ApplePlaybackRecovery.canRetry(fallbackUsed: true, hevcFallbackUsed: false, isHEVCTranscode: true))
        XCTAssertFalse(ApplePlaybackRecovery.canRetry(fallbackUsed: false, hevcFallbackUsed: true, isHEVCTranscode: true))
        XCTAssertFalse(ApplePlaybackRecovery.canRetry(fallbackUsed: true, hevcFallbackUsed: true, isHEVCTranscode: true))
    }
}

final class AppleHEVCOutputBoundaryTests: XCTestCase {
    private let query = ["VideoCodec": "hevc", "SegmentContainer": "mp4", "AllowVideoStreamCopy": "false",
                         "hevc-profile": "main", "hevc-rangetype": "SDR", "hevc-level": "120",
                         "hevc-videobitdepth": "8", "MaxWidth": "1920", "MaxHeight": "1080", "MaxFramerate": "30"]
    private func decision(_ parameters: [String: String], container: String = "mp4", proto: String = "hls", extra: URLQueryItem? = nil) throws -> PlayDecision {
        var url = URLComponents(); url.path = "/videos/i/master.m3u8"
        url.queryItems = parameters.sorted { $0.key < $1.key }.map { .init(name: $0.key, value: $0.value) } + (extra.map { [$0] } ?? [])
        let data = try JSONSerialization.data(withJSONObject: ["Id": "s", "TranscodingUrl": url.string!, "TranscodingContainer": container, "TranscodingSubProtocol": proto])
        let source = try JSONDecoder().decode(MediaSource.self, from: data)
        let account = Account(server: Server(url: URL(string: "https://example.test")!), userId: "u", username: "Fixture", accessToken: "invented-token", deviceID: "fixture-device")
        return try PlayDecisions.decide(account: account, itemID: "i", response: .init(mediaSources: [source]))
    }
    func testHEVCURLRequiresExplicitBoundedFMP4Output() throws {
        XCTAssertTrue(AppleHEVCTranscoding.isBounded(try decision(query)))
        for (key, value) in [("SegmentContainer", "ts"), ("AllowVideoStreamCopy", "true"), ("hevc-profile", "main10"),
                             ("hevc-rangetype", "HDR10"), ("hevc-level", "153"), ("hevc-videobitdepth", "10"),
                             ("MaxWidth", "3840"), ("MaxHeight", "2160"), ("MaxFramerate", "60"), ("MaxFramerate", "NaN")] {
            var changed = query; changed[key] = value
            XCTAssertFalse(AppleHEVCTranscoding.isBounded(try decision(changed)), key + "=" + value)
        }
        for key in query.keys {
            var missing = query; missing.removeValue(forKey: key)
            XCTAssertFalse(AppleHEVCTranscoding.isBounded(try decision(missing)), "Missing " + key)
        }
        XCTAssertFalse(AppleHEVCTranscoding.isBounded(try decision(query, container: "ts")))
        XCTAssertFalse(AppleHEVCTranscoding.isBounded(try decision(query, proto: "dash")))
        XCTAssertFalse(AppleHEVCTranscoding.isBounded(try decision(query, extra: .init(name: "mAxWiDtH", value: "3840"))))
    }
}
