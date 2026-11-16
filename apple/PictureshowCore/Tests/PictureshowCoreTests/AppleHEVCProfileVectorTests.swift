// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class AppleHEVCProfileVectorTests: XCTestCase {
    func testNativeProfileVectorsPreserveDirectAndAVCFallback() throws {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "apple-hevc-profiles", withExtension: "json", subdirectory: "Fixtures"))
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        XCTAssertEqual(envelope["schemaVersion"] as? Int, 1)
        XCTAssertEqual(envelope["platform"] as? String, "AVFoundation")
        let cases = try XCTUnwrap(envelope["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 8)
        var ids = Set<String>()
        for fixture in cases {
            let id = try XCTUnwrap(fixture["id"] as? String); XCTAssertTrue(ids.insert(id).inserted)
            let input = try XCTUnwrap(fixture["input"] as? [String: Any])
            let caps = try input["capabilities"].map { try JSONDecoder().decode(DeviceCapabilities.self, from: JSONSerialization.data(withJSONObject: $0)) }
            let actual = AppleProfile.json(supportsHEVC: input["supportsHEVC"] as? Bool ?? false,
                                           hevcTranscodeCapabilities: caps, forceAVCTranscode: input["forceAVCTranscode"] as? Bool ?? false)
            let expected = try XCTUnwrap(fixture["expected"] as? [String: Any])
            let actualBytes = try JSONSerialization.data(withJSONObject: actual, options: [.sortedKeys])
            let expectedBytes = try JSONSerialization.data(withJSONObject: expected, options: [.sortedKeys])
            XCTAssertEqual(String(decoding: actualBytes, as: UTF8.self), String(decoding: expectedBytes, as: UTF8.self), id)
        }
        print("Native AVFoundation profile vectors: \(cases.count) executed, no platform skips")
    }
}
