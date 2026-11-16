// SPDX-License-Identifier: MPL-2.0
import Foundation
import PictureshowCore

/// Strict fixture boundary: expected projections only compare reducer outputs and never become reducer state.
enum RemoteVectorRunner {
    static let operations: [String: Set<String>] = ["remote-wire": ["request", "parse", "capabilities", "advertised", "relay-rights", "socket-url", "targets-path", "cap-note"], "remote-targets": ["targets", "track-selected"], "remote-receive": ["receive"], "remote-reconnect": ["controller"], "remote-queue": ["queue"]]
    static func execute(suite: String, operation: String, input: [String: Any], context: [String: Any]) throws -> Any {
        guard operations[suite]?.contains(operation) == true else { throw error("unknown remote suite/operation \(suite)/\(operation)") }
        try fields(input, allowed: ["state", "event"], required: ["state", "event"])
        guard context.isEmpty, let state = input["state"] as? [String: Any], let event = input["event"] as? [String: Any] else { throw error("expected state/event objects and empty context") }
        return try RemoteReducers.reduce(suite: suite, operation: operation, state: state, event: event)
    }
    static func specRoot() -> URL {
        if let override = ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"] { return URL(fileURLWithPath: override) }
        var root = URL(fileURLWithPath: #filePath); for _ in 0..<5 { root.deleteLastPathComponent() }; return root.appendingPathComponent("spec")
    }
    static func document(_ suite: String) throws -> [String: Any] {
        guard operations[suite] != nil else { throw error("unknown remote suite \(suite)") }
        guard let d = try JSONSerialization.jsonObject(with: Data(contentsOf: specRoot().appendingPathComponent("vectors/\(suite).json"))) as? [String: Any] else { throw error("malformed suite") }
        return d
    }
    static func run(suite: String, operation: String, id: String, state initial: [String: Any], steps: [[String: Any]]) throws -> Int {
        guard operations[suite]?.contains(operation) == true else { throw error("unknown remote suite/operation \(suite)/\(operation)") }
        let doc = try document(suite)
        guard let cases = doc["cases"] as? [[String: Any]], cases.contains(where: { $0["id"] as? String == id && $0["operation"] as? String == operation }) else { throw error("unknown remote case ID \(id)") }
        guard !steps.isEmpty else { throw error("empty trace \(id)") }
        var state = initial
        for (index, step) in steps.enumerated() {
            try fields(step, allowed: ["event", "expected"], required: ["event", "expected"])
            guard let event = step["event"] as? [String: Any], let expected = step["expected"] as? [String: Any], !expected.isEmpty else { throw error("malformed step \(id)/\(index)") }
            let actual = try RemoteReducers.reduce(suite: suite, operation: operation, state: state, event: event)
            try assertProjection(expected, actual, path: "\(id)/\(index)")
            // Reducer traces chain actual states; wire/target helpers retain their input context.
            if ["receive", "controller", "queue"].contains(operation) { state = actual }
        }
        return steps.count
    }
    static func fields(_ d: [String: Any], allowed: Set<String>, required: Set<String> = []) throws {
        guard Set(d.keys).isSubset(of: allowed), required.isSubset(of: Set(d.keys)) else { throw error("unknown or missing fields: \(d.keys.sorted())") }
    }
    static func assertProjection(_ expected: Any, _ actual: Any?, path: String) throws {
        guard let actual else { throw error("\(path): missing actual value") }
        if let expected = expected as? [String: Any] {
            guard let actual = actual as? [String: Any] else { throw error("\(path): expected object") }
            for (key, value) in expected { try assertProjection(value, actual[key], path: "\(path).\(key)") }; return
        }
        if let expected = expected as? [Any] {
            guard let actual = actual as? [Any], expected.count == actual.count else { throw error("\(path): expected \(render(expected)), got \(render(actual))") }
            for index in expected.indices { try assertProjection(expected[index], actual[index], path: "\(path)[\(index)]") }; return
        }
        guard render(expected) == render(actual) else { throw error("\(path): expected \(render(expected)), got \(render(actual))") }
    }
    static func render(_ value: Any) -> String { (try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])).flatMap { String(data: $0, encoding: .utf8) } ?? String(describing: value) }
    static func error(_ message: String) -> NSError { NSError(domain: "RemoteVectorRunner", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}
