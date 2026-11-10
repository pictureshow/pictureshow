// SPDX-License-Identifier: MPL-2.0
import XCTest
@testable import PictureshowCore

final class CommandConfirmationTests: XCTestCase {
    private let owner: [String: Any] = ["server": "s", "viewer": "v", "device": "d",
                                        "session": "p", "generation": "1", "kind": "local"]

    private func reduce(_ state: [String: Any], _ event: [String: Any]) throws -> [String: Any] {
        try ControlReducers.reduce(suite: "session-command", operation: "command", state: state,
                                   event: event.merging(["owner": owner]) { _, new in new })
    }

    private func sent(_ operation: String) throws -> [String: Any] {
        let state: [String: Any] = ["owner": owner, "capabilities": ["play", "play-now", "pause", "stop", "seek"],
                                    "observed": ["entry": "a", "ticks": "0", "paused": true]]
        var intent: [String: Any] = ["op": "intent", "id": "1", "value": operation, "nowMs": "10"]
        if operation == "play-now" { intent["entry"] = "b" }
        else if operation.hasPrefix("seek-") { intent["entry"] = "30" }
        let result = try reduce(state, intent)
        XCTAssertEqual(result["effect"] as? String, "dispatch", operation)
        return result
    }

    func testPlayNowGetsTwelveSecondsWhileEveryTransportCommandKeepsFive() throws {
        // Exercise both expiry paths at either side of their exact boundary. Extending
        // every command, or updating only the timeout event, must fail this test.
        for operation in ["play-now", "play", "pause", "stop", "seek-absolute", "seek-relative"] {
            let state = try sent(operation)
            let deadline = operation == "play-now" ? 12_010 : 5_010
            var observation: [String: Any] = ["op": "observe", "id": "1", "value": "0",
                                               "paused": operation == "pause" || operation == "stop"]
            if operation == "play-now" { observation["entry"] = "b" }
            if operation == "stop" { observation["stopped"] = true }
            if operation.hasPrefix("seek-") { observation["value"] = "30" }
            for now in [deadline - 1, deadline] {
                let expired = now == deadline
                let timeout = try reduce(state, ["op": "timeout", "id": "1", "nowMs": String(now)])
                XCTAssertEqual((timeout["pending"] as? [String: Any])?["result"] as? String,
                               expired ? "unknown" : "sent", "\(operation) timeout at \(now)")
                XCTAssertEqual(timeout["effect"] as? String, expired ? "refresh" : "none", operation)
                XCTAssertEqual(timeout["fresh"] as? Bool, !expired, operation)

                observation["nowMs"] = String(now)
                let observed = try reduce(state, observation)
                XCTAssertEqual((observed["pending"] as? [String: Any])?["result"] as? String,
                               expired ? "unknown" : "confirmed", "\(operation) observation at \(now)")
                XCTAssertEqual(observed["effect"] as? String, expired ? "refresh" : "none", operation)
                XCTAssertEqual(observed["fresh"] as? Bool, !expired, operation)
            }
        }
    }

    func testPlayNowWaitsThroughAnOldTitleReportAndConfirmsTheRequestedTitle() throws {
        var state = try sent("play-now")
        state = try reduce(state, ["op": "timeout", "id": "1", "nowMs": "5010"])
        XCTAssertEqual((state["pending"] as? [String: Any])?["result"] as? String, "sent")
        state = try reduce(state, ["op": "observe", "id": "1", "nowMs": "6000",
                                   "entry": "a", "paused": false, "value": "100000000"])
        XCTAssertEqual((state["pending"] as? [String: Any])?["result"] as? String, "sent")
        XCTAssertEqual(state["fresh"] as? Bool, true)
        state = try reduce(state, ["op": "observe", "id": "1", "nowMs": "9000",
                                   "entry": "b", "paused": false, "value": "0"])
        XCTAssertEqual((state["pending"] as? [String: Any])?["result"] as? String, "confirmed")
        XCTAssertEqual(state["effect"] as? String, "none")
    }
}
