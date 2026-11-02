// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

private final class HEVCClientProtocol: URLProtocol {
    static var respond: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.respond!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: [:])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}
private actor QualificationReads {
    var count = 0
    let caps: DeviceCapabilities
    init(_ caps: DeviceCapabilities) { self.caps = caps }
    func read() -> DeviceCapabilities? { count += 1; return caps }
}
final class AppleHEVCClientTests: XCTestCase {
    private var requests: [[String: Any]] = []
    private let account = Account(server: Server(url: URL(string: "https://example.test/base")!), userId: "u", username: "Viewer", accessToken: "invented-token", deviceID: "fixture-device")
    private let bounded = "/videos/i/master.m3u8?VideoCodec=hevc&SegmentContainer=mp4&AllowVideoStreamCopy=false&hevc-profile=main&hevc-rangetype=SDR&hevc-level=120&hevc-videobitdepth=8&MaxWidth=1920&MaxHeight=1080&MaxFramerate=30"
    override func tearDown() { HEVCClientProtocol.respond = nil }
    private func body(_ request: URLRequest) throws -> [String: Any] {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }; var chunk = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let n = stream.read(&chunk, maxLength: chunk.count); if n <= 0 { break }; data.append(chunk, count: n) }
        }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
    private func response(direct: Bool = false, url: String = "/videos/i/master.m3u8?VideoCodec=h264", session: String = "baseline") throws -> Data {
        try JSONSerialization.data(withJSONObject: ["PlaySessionId": session, "MediaSources": [["Id": "source", "SupportsDirectPlay": direct, "TranscodingUrl": url,
             "TranscodingSubProtocol": "hls", "TranscodingContainer": url.contains("VideoCodec=hevc") ? "mp4" : "ts",
             "DefaultAudioStreamIndex": 2, "DefaultSubtitleStreamIndex": -1,
             "MediaStreams": [["Index": 2, "Type": "Audio", "Codec": "aac"]]]]])
    }
    private func setupClient() throws -> (JellyfinClient, QualificationReads) {
        let fixture = try XCTUnwrap(Bundle.module.url(forResource: "apple-hevc-profiles", withExtension: "json", subdirectory: "Fixtures"))
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: fixture)) as? [String: Any])
        let cases = try XCTUnwrap(envelope["cases"] as? [[String: Any]])
        let input = try XCTUnwrap(cases.first { $0["id"] as? String == "measured-main-hd-prefers-fmp4-keeps-avc" }?["input"] as? [String: Any])
        let caps = try JSONDecoder().decode(DeviceCapabilities.self, from: JSONSerialization.data(withJSONObject: input["capabilities"]!))
        let reads = QualificationReads(caps), config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [HEVCClientProtocol.self]
        let client = JellyfinClient(deviceID: "install", session: URLSession(configuration: config), supportsHEVC: true,
                                    hevcTranscodeCapabilities: { await reads.read() })
        return (client, reads)
    }
    func testDirectUsesAcceptedProfileAndNeverWaitsForQualification() async throws {
        let (client, reads) = try setupClient()
        HEVCClientProtocol.respond = { request in
            let body = try self.body(request); self.requests.append(body)
            let actual = try JSONSerialization.data(withJSONObject: body["DeviceProfile"]!, options: [.sortedKeys])
            let baseline = try JSONSerialization.data(withJSONObject: AppleProfile.json(supportsHEVC: true), options: [.sortedKeys])
            XCTAssertEqual(actual, baseline)
            return (200, try self.response(direct: true))
        }
        let result = try await client.prepare(account: account, itemID: "i", audioIndex: 2, subtitleIndex: -1, mediaSourceID: "source")
        XCTAssertEqual(result.method, .directPlay); XCTAssertEqual(requests.count, 1)
        let count = await reads.count; XCTAssertEqual(count, 0)
    }
    func testQualifiedHLSBindsSecondRequestAndKeepsOneNegotiatedIdentity() async throws {
        let (client, reads) = try setupClient()
        HEVCClientProtocol.respond = { request in
            let body = try self.body(request); self.requests.append(body)
            XCTAssertEqual(request.httpMethod, "POST"); XCTAssertTrue(request.url!.path.hasSuffix("PlaybackInfo"))
            if self.requests.count == 1 { return (200, try self.response()) }
            XCTAssertEqual(body["MediaSourceId"] as? String, "source")
            XCTAssertEqual(body["AudioStreamIndex"] as? Int, 2); XCTAssertEqual(body["SubtitleStreamIndex"] as? Int, -1)
            XCTAssertEqual(body["EnableDirectPlay"] as? Bool, false); XCTAssertEqual(body["AllowVideoStreamCopy"] as? Bool, false)
            let profile = try XCTUnwrap(body["DeviceProfile"] as? [String: Any])
            let targets = try XCTUnwrap(profile["TranscodingProfiles"] as? [[String: Any]])
            XCTAssertEqual(targets.map { $0["VideoCodec"] as? String }, ["hevc", "h264"])
            XCTAssertEqual(targets.map { $0["Container"] as? String }, ["mp4", "ts"])
            return (200, try self.response(url: self.bounded, session: "preferred"))
        }
        let result = try await client.prepare(account: account, itemID: "i", startTicks: 300_000_000, audioIndex: 2, subtitleIndex: -1, mediaSourceID: "source")
        XCTAssertEqual(requests.count, 2); XCTAssertEqual(result.playSessionId, "preferred")
        XCTAssertEqual(result.source.id, "source"); XCTAssertEqual(result.startTicks, 300_000_000)
        XCTAssertTrue(AppleHEVCTranscoding.isBounded(result))
        let count = await reads.count; XCTAssertEqual(count, 1)
    }
    func testUnboundedHEVCProposalRetainsBaselineAVCWithoutOpeningIt() async throws {
        let (client, _) = try setupClient()
        HEVCClientProtocol.respond = { request in
            self.requests.append(try self.body(request))
            return (200, try self.response(url: self.requests.count == 1 ? "/videos/i/master.m3u8?VideoCodec=h264" : self.bounded.replacingOccurrences(of: "MaxFramerate=30", with: "MaxFramerate=60"), session: self.requests.count == 1 ? "baseline" : "unbounded"))
        }
        let result = try await client.prepare(account: account, itemID: "i", subtitleIndex: -1, mediaSourceID: "source")
        XCTAssertEqual(requests.count, 2); XCTAssertEqual(result.playSessionId, "baseline")
        XCTAssertFalse(AppleHEVCTranscoding.isHEVC(result))
    }
    func testOpaqueAVCFallbackFromAcceptedProfileRemainsUsable() async throws {
        let (client, reads) = try setupClient()
        HEVCClientProtocol.respond = { request in
            self.requests.append(try self.body(request))
            return (200, try self.response(url: "/videos/i/master.m3u8"))
        }
        let result = try await client.prepare(account: account, itemID: "i", forceTranscode: true, mediaSourceID: "source")
        XCTAssertEqual(result.method, .transcode); XCTAssertEqual(result.playSessionId, "baseline")
        XCTAssertFalse(AppleHEVCTranscoding.isHEVC(result)); XCTAssertEqual(requests.count, 1)
        let count = await reads.count; XCTAssertEqual(count, 0)
        let query = URLComponents(url: result.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        XCTAssertEqual(query.first { $0.name == "AllowVideoStreamCopy" }?.value, "false")
    }
    func testForcedAVCRejectsContradictoryOrAmbiguousReturnedCodec() async throws {
        let (client, reads) = try setupClient()
        for url in [bounded, "/videos/i/master.m3u8?VideoCodec=h264&vIdEoCoDeC=hevc"] {
            HEVCClientProtocol.respond = { request in
                self.requests.append(try self.body(request))
                return (200, try self.response(url: url))
            }
            do {
                _ = try await client.prepare(account: account, itemID: "i", forceTranscode: true, mediaSourceID: "source")
                XCTFail("An explicit contradictory or ambiguous codec cannot be an AVC recovery")
            } catch { XCTAssertEqual(error as? ClientError, .noCompatibleSource) }
        }
        let count = await reads.count; XCTAssertEqual(count, 0)
        XCTAssertEqual(requests.count, 2)
    }
    func testForcedRecoveryCannotOfferHEVCOrReadQualification() async throws {
        let (client, reads) = try setupClient()
        HEVCClientProtocol.respond = { request in
            let body = try self.body(request); self.requests.append(body)
            let profile = try XCTUnwrap(body["DeviceProfile"] as? [String: Any])
            let targets = try XCTUnwrap(profile["TranscodingProfiles"] as? [[String: Any]])
            XCTAssertEqual(targets.count, 1); XCTAssertEqual(targets.first?["VideoCodec"] as? String, "h264")
            XCTAssertEqual(body["EnableDirectPlay"] as? Bool, false)
            return (200, try self.response())
        }
        let result = try await client.prepare(account: account, itemID: "i", forceTranscode: true, mediaSourceID: "source")
        XCTAssertFalse(AppleHEVCTranscoding.isHEVC(result)); XCTAssertEqual(requests.count, 1)
        let count = await reads.count; XCTAssertEqual(count, 0)
    }
}
