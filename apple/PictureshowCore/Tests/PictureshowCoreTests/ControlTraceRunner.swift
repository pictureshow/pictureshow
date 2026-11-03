// SPDX-License-Identifier: MPL-2.0
import Foundation
import PictureshowCore

/// Strict vector trace runner. Reducer behavior lives in PictureshowCore, so the
/// checked state transitions are the same mechanisms available to app adapters.
enum ControlTraceRunner {
    static func run(suite: String, operation: String, id: String, state initial: [String: Any], steps: [[String: Any]]) throws -> Int {
        var state = initial
        for (index, step) in steps.enumerated() {
            try fields(step, allowed: ["event", "expected"], required: ["event", "expected"], at: "\(id).step[\(index)]")
            guard let event = step["event"] as? [String: Any], let expected = step["expected"] as? [String: Any], !expected.isEmpty else {
                throw failure("\(id).step[\(index)]: malformed or empty expected projection")
            }
            let actual = try ControlReducers.reduce(suite: suite, operation: operation, state: state, event: event)
            guard projection(expected, matches: actual) else {
                throw failure("\(id).step[\(index)] expected projection \(render(expected)), got \(render(projected(actual, by: expected)))")
            }
            if operation == "queue" {
                guard let next = actual["queue"] as? [String: Any] else { throw failure("\(id).step[\(index)]: reducer omitted queue state") }
                state = next
            } else { state = actual }
        }
        return steps.count
    }

    private static func fields(_ value: [String: Any], allowed: Set<String>, required: Set<String>, at path: String) throws {
        let unknown = Set(value.keys).subtracting(allowed)
        guard unknown.isEmpty else { throw failure("\(path): unknown fields \(unknown.sorted())") }
        let missing = required.subtracting(value.keys)
        guard missing.isEmpty else { throw failure("\(path): missing fields \(missing.sorted())") }
    }
    private static func projection(_ expected: Any, matches actual: Any?) -> Bool {
        guard let actual else { return false }
        if let exp = expected as? [String: Any] {
            guard let got = actual as? [String: Any] else { return false }
            return exp.allSatisfy { key, value in projection(value, matches: got[key]) }
        }
        if let exp = expected as? [Any] {
            guard let got = actual as? [Any], exp.count == got.count else { return false }
            return zip(exp, got).allSatisfy { projection($0.0, matches: $0.1) }
        }
        return render(expected) == render(actual)
    }
    private static func projected(_ actual: Any, by expected: Any) -> Any {
        if let exp = expected as? [String: Any], let got = actual as? [String: Any] {
            return Dictionary(uniqueKeysWithValues: exp.keys.sorted().compactMap { key in got[key].map { (key, projected($0, by: exp[key]!)) } })
        }
        if let exp = expected as? [Any], let got = actual as? [Any] { return zip(exp, got).map { projected($0.1, by: $0.0) } }
        return actual
    }
    private static func render(_ value: Any) -> String {
        guard JSONSerialization.isValidJSONObject(value) || value is NSNull || value is String || value is NSNumber else { return String(describing: value) }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]), let text = String(data: data, encoding: .utf8) else { return String(describing: value) }
        return text
    }
    private static func failure(_ message: String) -> NSError { NSError(domain: "ControlTraceRunner", code: 1, userInfo: [NSLocalizedDescriptionKey: message]) }
}
