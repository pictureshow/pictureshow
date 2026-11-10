// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class RemoteVectorTests: XCTestCase {
    func testAllRemoteVectors() throws {
        let expectedCounts = ["remote-wire": (77, 78), "remote-targets": (17, 17), "remote-receive": (51, 81), "remote-reconnect": (35, 127), "remote-queue": (15, 56)]
        var ids = Set<String>(), failures: [String] = [], totalCases = 0, totalSteps = 0
        for suite in expectedCounts.keys.sorted() {
            let doc = try RemoteVectorRunner.document(suite)
            try RemoteVectorRunner.fields(doc, allowed: ["schemaVersion", "suite", "description", "cases"], required: ["schemaVersion", "suite", "cases"])
            XCTAssertEqual(doc["schemaVersion"] as? Int, 1); XCTAssertEqual(doc["suite"] as? String, suite)
            let cases = try XCTUnwrap(doc["cases"] as? [[String: Any]])
            XCTAssertEqual(cases.count, expectedCounts[suite]!.0)
            var count = 0
            for c in cases {
                try RemoteVectorRunner.fields(c, allowed: ["id", "operation", "description", "state", "steps"], required: ["id", "operation", "state", "steps"])
                let id = try XCTUnwrap(c["id"] as? String), op = try XCTUnwrap(c["operation"] as? String)
                XCTAssertTrue(ids.insert(id).inserted, "duplicate vector ID \(id)")
                let state = try XCTUnwrap(c["state"] as? [String: Any]), steps = try XCTUnwrap(c["steps"] as? [[String: Any]])
                count += steps.count; totalCases += 1
                do { totalSteps += try RemoteVectorRunner.run(suite: suite, operation: op, id: id, state: state, steps: steps) }
                catch { failures.append("\(id): \(error.localizedDescription)") }
            }
            XCTAssertEqual(count, expectedCounts[suite]!.1)
        }
        print("Remote vectors: \(totalCases) cases, \(totalSteps) passing steps, \(failures.count) failed cases; zero skips")
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
        if failures.isEmpty { XCTAssertEqual(totalSteps, 359) }
    }
    func testStrictRemoteBoundary() throws {
        let valid: [String: Any] = ["server": "s", "viewer": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"]
        XCTAssertThrowsError(try RemoteReducers.reduce(suite: "remote-unknown", operation: "receive", state: valid, event: ["op": "tick"]))
        XCTAssertThrowsError(try RemoteReducers.reduce(suite: "remote-receive", operation: "unknown", state: valid, event: ["op": "tick"]))
        for event: [String: Any] in [["op": "unknown"], ["op": "tick", "unknown": true], ["op": "tick", "nowMs": 9007199254740993], ["op": "tick", "nowMs": "01"], ["op": "tick", "nowMs": "9223372036854775808"], ["op": "message", "message": ["kind": "play", "unknown": 1]]] {
            XCTAssertThrowsError(try RemoteReducers.reduce(suite: "remote-receive", operation: "receive", state: valid, event: event))
        }
        XCTAssertThrowsError(try RemoteVectorRunner.run(suite: "remote-receive", operation: "receive", id: "unknown", state: valid, steps: [["event": ["op": "tick"], "expected": ["connected": true]]]))
        XCTAssertThrowsError(try RemoteVectorRunner.run(suite: "remote-receive", operation: "receive", id: "remote-receive-play-now-opens-as-target", state: valid, steps: [["event": ["op": "tick"], "expected": ["connected": false]]]))
    }
    func testWireParserRejectsNumericCoercionsAndRetainsLargeTicks() throws {
        for number in ["1.0", "1e0", "\"1\"", "9223372036854775808"] {
            let parsed = try RemoteReducers.parseMessage("{\"MessageType\":\"Playstate\",\"Data\":{\"Command\":\"Seek\",\"SeekPositionTicks\":\(number)}}")
            XCTAssertEqual(parsed["kind"] as? String, "malformed")
        }
        for malformed in ["{1:2}", "{\"MessageType\":\"GeneralCommand\",\"Data\":{\"Name\":\"Mute\",\"Arguments\":{1:2}}}", "{\"MessageType\":\"Playstate\",}"] {
            let rejected = try RemoteReducers.parseMessage(malformed)
            XCTAssertEqual(rejected["reason"] as? String, "not-json")
        }
        let nullArgument = try RemoteReducers.parseMessage("{\"MessageType\":\"GeneralCommand\",\"Data\":{\"Name\":\"SendString\",\"Arguments\":{\"String\":null}}}")
        XCTAssertEqual(nullArgument["reason"] as? String, "bad-arguments")
        let parsed = try RemoteReducers.parseMessage("{\"MessageType\":\"Playstate\",\"Data\":{\"Command\":\"Seek\",\"SeekPositionTicks\":9007199254740993}}")
        XCTAssertEqual(parsed["ticks"] as? String, "9007199254740993")
    }
}
