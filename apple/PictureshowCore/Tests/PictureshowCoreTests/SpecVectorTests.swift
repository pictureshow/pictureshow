// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class SpecVectorTests: XCTestCase {
    private let server = Server(url: URL(string: "https://media.example.test/jellyfin")!)
    private var account: Account { Account(server: server, userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "account-device") }
    private func decoded<T: Decodable>(_ type: T.Type, _ value: Any) throws -> T {
        try JSONDecoder().decode(type, from: JSONSerialization.data(withJSONObject: value))
    }
    private func errorName(_ error: Error) -> String {
        switch error as? ClientError {
        case .serverRefused: return "SERVER_REFUSED"
        case .sourceNotFound: return "SOURCE_NOT_FOUND"
        case .noCompatibleSource: return "NO_COMPATIBLE_SOURCE"
        case .unsafeStreamURL: return "UNSAFE_STREAM_URL"
        default: return String(describing: error)
        }
    }
    /// Queries are compared by decoded protocol values: RFC percent-space and form '+' are equivalent here.
    private func assertURL(_ actual: URL?, _ expected: Any?, _ id: String) {
        guard let expected = expected as? String else { XCTAssertNil(actual, id); return }
        guard let actual, let a = URLComponents(url: actual, resolvingAgainstBaseURL: false), let b = URLComponents(string: expected) else { XCTFail(id); return }
        XCTAssertEqual(a.scheme, b.scheme, id); XCTAssertEqual(a.host, b.host, id); XCTAssertEqual(a.port, b.port, id); XCTAssertEqual(a.percentEncodedPath, b.percentEncodedPath, id); XCTAssertEqual(a.fragment, b.fragment, id)
        func query(_ c: URLComponents) -> [String] {
            (c.percentEncodedQuery ?? "").components(separatedBy: "&").filter { !$0.isEmpty }.map { $0.replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? $0 }.sorted()
        }
        XCTAssertEqual(query(a), query(b), id)
    }
    /// Runs the implemented slice against shared fixtures, without copying fixtures into Apple sources.
    func testSharedVectorsForImplementedSlice() throws {
        let spec: URL
        if let directory = ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"] { spec = URL(fileURLWithPath: directory) }
        else {
            var root = URL(fileURLWithPath: #filePath)
            for _ in 0..<5 { root.deleteLastPathComponent() }
            spec = root.appendingPathComponent("spec")
        }
        guard FileManager.default.fileExists(atPath: spec.appendingPathComponent("vectors/application.json").path) else {
            throw XCTSkip("Shared spec absent from this checkout (2026-10-31: awaiting spec branch integration). Set PICTURESHOW_SPEC_DIR to run shared fixtures.")
        }
        var matched = 0; var unsupported: [String:Int] = [:]
        for name in ["application", "network", "playback"] {
            let file = spec.appendingPathComponent("vectors/\(name).json")
            guard FileManager.default.fileExists(atPath: file.path) else { continue }
            let suite = try JSONSerialization.jsonObject(with: Data(contentsOf: file)) as! [String:Any]
            XCTAssertEqual(suite["schemaVersion"] as? Int, 1)
            for vector in suite["cases"] as! [[String:Any]] {
                let operation = vector["operation"] as! String
                let input = vector["input"] as! [String:Any]
                let id = vector["id"] as! String
                let expected = vector["expected"]
                switch operation {
                case "resume-start":
                    let result = PlaybackSelection.startPositionMillis(ticks: (input["ticks"] as! NSNumber).int64Value, resume: input["resume"] as! Bool, explicitPositionMs: (input["explicitPositionMs"] as? NSNumber)?.int64Value)
                    XCTAssertEqual(result, (expected as! NSNumber).int64Value, id)
                case "default-stream":
                    let streams = try decoded([MediaStream].self, input["streams"]!)
                    XCTAssertEqual(PlaybackSelection.defaultStream(streams: streams, type: input["type"] as! String, serverIndex: (input["serverIndex"] as? NSNumber)?.intValue, preferredLanguage: input["preferredLanguage"] as! String), (expected as! NSNumber).intValue, id)
                case "address":
                    let target = expected as! [String:Any]
                    if target["error"] != nil { XCTAssertThrowsError(try ServerURLs.candidates(input["value"] as! String), id) }
                    else { XCTAssertEqual(try ServerURLs.candidates(input["value"] as! String).map(\.absoluteString), target["urls"] as! [String], id) }
                case "authorization":
                    XCTAssertEqual(AuthorizationHeader.build(deviceID: input["deviceId"] as? String ?? "install", deviceName: input["deviceName"] as? String ?? "Fixture TV", version: input["version"] as? String ?? "0.1.0", token: input["token"] as? String), expected as? String, id)
                case "sameOrigin":
                    let actual = (input["url"] as? String).flatMap(URL.init(string:)).map { ServerURLs.isSameOrigin(server.url, $0) } ?? false
                    XCTAssertEqual(actual, expected as! Bool, id)
                case "resolve":
                    XCTAssertEqual(try ServerURLs.resolve(server: server, reference: input["url"] as! String).absoluteString, expected as? String, id)
                case "stripApiKey":
                    XCTAssertEqual(ServerURLs.strippingCredentials(URL(string: input["url"] as! String)!).absoluteString, expected as? String, id)
                case "image":
                    let item = try decoded(MediaItem.self, input["item"]!)
                    let result = JellyfinClient(deviceID: "install").imageURL(account: account, item: item, maxWidth: input["width"] as! Int, maxHeight: input["height"] as? Int, wide: input["wide"] as? Bool ?? false)
                    assertURL(result, expected, id)
                case "decision":
                    let target = expected as! [String:Any]
                    do {
                        let decision = try PlayDecisions.decide(account: account, itemID: input["itemId"] as! String, response: decoded(PlaybackInfo.self, input["response"]!), mediaSourceID: input["mediaSourceId"] as? String, deviceName: "Fixture TV")
                        XCTAssertNil(target["unplayable"], id)
                        XCTAssertEqual(decision.method.rawValue, target["method"] as? String, id)
                        XCTAssertEqual(decision.source.id, target["mediaSourceId"] as? String, id)
                        XCTAssertEqual(decision.url.absoluteString, target["url"] as? String, id)
                        XCTAssertEqual(decision.headers, target["headers"] as! [String:String], id)
                        XCTAssertEqual(decision.playSessionId, target["playSessionId"] as? String, id)
                        XCTAssertEqual(decision.transcodeReasons, target["transcodeReasons"] as! [String], id)
                    } catch { XCTAssertEqual(errorName(error), target["unplayable"] as? String, id) }
                case "ticks": XCTAssertEqual(Ticks.from(nanoseconds: (input["nanoseconds"] as! NSNumber).int64Value), (expected as! NSNumber).int64Value, id)
                case "end-position", "caption-language", "caption-index":
                    // These are separate application policies, not aliases for the core's
                    // resume entry, language normalization, or server-owned default stream.
                    // Do not claim partial normalization assertions as executing a full vector.
                    unsupported["\(name).\(operation)", default: 0] += 1; continue
                default: unsupported["\(name).\(operation)", default: 0] += 1; continue
                }
                matched += 1
            }
        }
        XCTAssertGreaterThan(matched, 0)
        print("Shared vectors: \(matched) executed. Outside slice: \(unsupported.sorted(by: { $0.key < $1.key }))")
        let reasons = [
            "end-position": "Final-report completion/bounding policy is owned by the platform playback lifecycle; no matching PictureshowCore helper is ported.",
            "caption-language": "System-caption enablement and server user-configuration precedence are not ported. language(_:) only normalizes a language tag.",
            "caption-index": "Accessibility caption ranking (hearing-impaired/forced/language) is not ported. defaultStream preserves the server choice and has different semantics."
        ]
        for operation in reasons.keys.sorted() where unsupported["application.\(operation)"] != nil {
            print("Excluded application.\(operation): \(reasons[operation]!)")
        }
    }
}
