// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import Foundation
import XCTest
@testable import PictureshowCore

extension OfflineVectorRunner {
    /// Assertions live in the test target; state continuation consumes actual reducer outputs only.
    static func run(suite: String, operation: String, id: String, state initial: Any, steps: [[String: Any]]) throws -> Int {
        try SpecCaseManifest.validate(id: id, suite: suite, operation: operation)
        guard operations[suite]?.contains(operation) == true, !steps.isEmpty,
              initial is [String: Any] || (suite == "offline-outbox" && operation == "outbox" && initial is NSNull) else {
            throw OfflineError.invalidArgument("Malformed offline trace: \(id)")
        }
        var state = initial
        for (index, step) in steps.enumerated() {
            guard Set(step.keys) == ["event", "expected"], let event = step["event"] as? [String: Any],
                  let expected = step["expected"] as? [String: Any], !expected.isEmpty else {
                throw OfflineError.invalidArgument("Malformed offline step: \(id)/\(index)")
            }
            let actual = try reduce(suite: suite, operation: operation, state: state, event: event)
            guard OfflineVectorTests.projection(expected, matches: actual) else {
                throw OfflineError.invalidArgument("\(id)/\(index) expected \(OfflineVectorTests.render(expected)); actual \(OfflineVectorTests.render(actual))")
            }
            state = try nextState(operation: operation, output: actual, previous: state)
        }
        return steps.count
    }
}

final class OfflineVectorTests: XCTestCase {
    static let counts = ["offline-budget": 40, "offline-detail-source": 20, "offline-fmp4": 43, "offline-job": 283,
                         "offline-manifest": 98, "offline-next": 14, "offline-outbox": 83, "offline-plan": 58,
                         "offline-reconnect": 16, "offline-resume": 78]
    func testBudget40Cases() throws { try run("offline-budget") }
    func testDetailSource20Cases() throws { try run("offline-detail-source") }
    func testFmp443Cases() throws { try run("offline-fmp4") }
    func testJob283Cases() throws { try run("offline-job") }
    func testManifest98Cases() throws { try run("offline-manifest") }
    func testNext14Cases() throws { try run("offline-next") }
    func testOutbox83Cases() throws { try run("offline-outbox") }
    func testPlan58Cases() throws { try run("offline-plan") }
    func testReconnect16Cases() throws { try run("offline-reconnect") }
    func testResume78Cases() throws { try run("offline-resume") }
    private func run(_ suite: String) throws {
        let root: URL
        if let path = ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"] { root = URL(fileURLWithPath: path) }
        else { var p = URL(fileURLWithPath: #filePath); for _ in 0..<5 { p.deleteLastPathComponent() }; root = p.appendingPathComponent("spec") }
        let doc = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("vectors/\(suite).json"))) as? [String: Any])
        XCTAssertEqual(doc["schemaVersion"] as? Int, 1)
        XCTAssertEqual(doc["suite"] as? String, suite)
        let cases = try XCTUnwrap(doc["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, Self.counts[suite], "Fixture inventory changed; review the adapter deliberately")
        var ids = Set<String>(), steps = 0
        for c in cases {
            let id = try XCTUnwrap(c["id"] as? String), operation = try XCTUnwrap(c["operation"] as? String)
            XCTAssertTrue(ids.insert(id).inserted, "Duplicate fixture: \(id)")
            XCTAssertNil(c["input"]); XCTAssertNil(c["expected"])
            let state = try XCTUnwrap(c["state"]), trace = try XCTUnwrap(c["steps"] as? [[String: Any]])
            // Keep running after a failed case so a porting mismatch cannot conceal the rest of the cohort.
            do { steps += try OfflineVectorRunner.run(suite: suite, operation: operation, id: id, state: state, steps: trace) }
            catch { XCTFail("\(id): \(error)") }
        }
        print("Offline vectors: \(suite), \(cases.count) cases, \(steps) steps")
        XCTAssertEqual(ids, Set(SpecCaseManifest.contracts.filter { $0.value.hasPrefix(suite + "/") }.keys))
    }
    static func projection(_ expected: Any, matches actual: Any?) -> Bool {
        guard let actual else { return expected is NSNull }
        if let e = expected as? [String: Any] {
            guard let a = actual as? [String: Any] else { return false }
            if e.isEmpty { return a.isEmpty }
            return e.allSatisfy { key, value in
                if key == "reservations" { return render(value) == a[key].map(render) }
                return projection(value, matches: a[key])
            }
        }
        if let e = expected as? [Any] {
            guard let a = actual as? [Any], a.count == e.count else { return false }
            return zip(e, a).allSatisfy { projection($0.0, matches: $0.1) }
        }
        return render(expected) == render(actual)
    }
    static func render(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed]) else { return "<invalid JSON>" }
        return String(decoding: data, as: UTF8.self)
    }
    func testAdapterRejectsUnknownOperationsMalformedTracesAndFalseProjections() throws {
        XCTAssertThrowsError(try OfflineVectorRunner.reduce(suite: "offline-other", operation: "job", state: [:], event: [:]))
        XCTAssertThrowsError(try OfflineVectorRunner.reduce(suite: "offline-job", operation: "unknown", state: [:], event: [:]))
        XCTAssertThrowsError(try reduceOfflineJob(OfflineJob(), OfflineJobEvent(op: "unknown")))
        XCTAssertThrowsError(try reduceOfflineOutbox(nil, OfflineOutboxEvent(op: "unknown")))
        XCTAssertThrowsError(try OfflineVectorRunner.run(suite: "offline-job", operation: "job", id: "invented", state: [:], steps: []))
        let id = "offline-budget.default-cap-49"
        XCTAssertEqual(try OfflineVectorRunner.run(suite: "offline-budget", operation: "default-cap", id: id, state: [:], steps: [["event": ["quotaBytes": 49], "expected": ["cap": 9]]]), 1)
        XCTAssertThrowsError(try OfflineVectorRunner.run(suite: "offline-budget", operation: "default-cap", id: id, state: [:], steps: [["event": [:], "expected": [:]]]))
        XCTAssertThrowsError(try OfflineVectorRunner.run(suite: "offline-budget", operation: "default-cap", id: id, state: [:], steps: [["event": ["quotaBytes": 100], "expected": ["cap": 999]]]))
        XCTAssertFalse(Self.projection(["reservations": ["a": 1]], matches: ["reservations": ["a": 1, "b": 2]]))
        XCTAssertFalse(Self.projection([:], matches: ["a": 1]))
        XCTAssertFalse(Self.projection([1], matches: [1, 2]))
    }
    func testNegativeBytesAreRefusedWithoutChangingBudget() throws {
        let budget = OfflineBudget(capBytes: 100)
        XCTAssertThrowsError(try budget.reserve(job: "a", bytes: -1, freeBytes: nil))
        XCTAssertThrowsError(try budget.commit(job: "a", bytes: -1))
        XCTAssertThrowsError(try budget.discard(job: "a", committedBytes: -1))
        XCTAssertEqual(budget.usedBytes, 0)
    }
    func testBudgetInvariantAcrossDeterministicReservationTraces() throws {
        var random: UInt64 = 87
        func pick(_ limit: UInt64) -> Int64 {
            random = random &* 6364136223846793005 &+ 1442695040888963407
            return Int64((random >> 32) % limit)
        }
        let jobs = ["a", "b", "c"]
        for _ in 0..<200 {
            var budget = OfflineBudget(capBytes: pick(999) + 1)
            for _ in 0..<200 {
                let job = jobs[Int(pick(3))]
                switch pick(5) {
                case 0, 1: budget = try budget.reserve(job: job, bytes: pick(200), freeBytes: nil).budget
                case 2: budget = try budget.commit(job: job, bytes: pick(200)).budget
                case 3: budget = budget.release(job: job).budget
                default: budget = budget.setCap(capBytes: budget.usedBytes + pick(1000), jobNewestFirst: jobs).budget
                }
                XCTAssertLessThanOrEqual(budget.usedBytes + budget.reservations.values.reduce(0, +), try XCTUnwrap(budget.capBytes))
                XCTAssertTrue(budget.reservations.values.allSatisfy { $0 > 0 })
            }
        }
    }
    func testPersistedOutboxRequiresCanonicalInt64Strings() throws {
        let decoder = JSONDecoder()
        for text in ["01", "-0", "+1", "9223372036854775808"] {
            XCTAssertThrowsError(try decoder.decode(OfflineUserData.self, from: Data("{\"positionTicks\":\"\(text)\"}".utf8)))
        }
        XCTAssertThrowsError(try decoder.decode(OfflineUserData.self, from: Data("{\"positionTicks\":1}".utf8)))
        for value in [Int64.min, Int64.max, 0] {
            let original = OfflineUserData(positionTicks: value)
            XCTAssertEqual(try decoder.decode(OfflineUserData.self, from: JSONEncoder().encode(original)), original)
        }
    }
    func testIndexReferenceLimitAndDefaultPlaceholder() throws {
        for (count, expected) in [(65535, 65535), (65536, 32768), (40000, 40000)] {
            let fragments = (0..<count).map { Fmp4Fragment(moofOffset: Int64($0), totalSize: 1, durationInTimescale: 1, earliestTime: Int64($0)) }
            let size = count == 40000 ? OFFLINE_INDEX_PLACEHOLDER_BYTES : 1024 * 1024
            let bytes = try fmp4IndexBoxes(track: Fmp4Track(id: 1, timescale: 1000, trexDefaultDuration: 0), fragments: fragments, maxBytes: size)
            XCTAssertEqual(bytes.count, size)
            let header = try XCTUnwrap(fmp4BoxHeader(bytes: bytes, length: bytes.count)), at = header.type == "free" ? Int(header.size) : 0
            XCTAssertEqual(String(decoding: bytes[at + 4..<at + 8], as: UTF8.self), "sidx")
            XCTAssertEqual(Int(bytes[at + 38]) * 256 + Int(bytes[at + 39]), expected)
        }
    }
}
