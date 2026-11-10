// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

extension MusicVectorRunner {
    /// Tests own the expected projections. Queue continuation feeds only `queue` into the next step.
    static func run(suite: String, operation: String, id: String, state initial: [String: Any], steps: [[String: Any]]) throws -> Int {
        guard id.hasPrefix(suite + "."), id.count > suite.count + 1, operations[suite]?.contains(operation) == true,
              ["queue", "session", "report", "playlist"].contains(operation), !steps.isEmpty else { throw MusicInput.failure("malformed music trace identity or operation") }
        var state = initial
        for (index, step) in steps.enumerated() {
            try MusicInput.fields(step, ["event", "expected"], required: ["event", "expected"])
            let event = try MusicInput.object(step["event"]), expected = try MusicInput.object(step["expected"])
            guard !expected.isEmpty else { throw MusicInput.failure("empty expected projection") }
            let actual = try execute(suite: suite, operation: operation, input: ["state": state, "event": event], context: [:])
            guard MusicVectorTests.projection(expected, matches: actual) else {
                throw MusicInput.failure("\(id)/\(index): expected \(MusicVectorTests.render(expected)), got \(MusicVectorTests.render(actual))")
            }
            let output = try MusicInput.object(actual)
            state = operation == "queue" ? try MusicInput.object(output["queue"]) : output
        }
        return steps.count
    }
}

final class MusicVectorTests: XCTestCase {
    func testAllSixMusicSuites() throws {
        let root: URL
        if let path = ProcessInfo.processInfo.environment["PICTURESHOW_SPEC_DIR"] { root = URL(fileURLWithPath: path) }
        else { var p = URL(fileURLWithPath: #filePath); for _ in 0..<5 { p.deleteLastPathComponent() }; root = p.appendingPathComponent("spec") }
        var ids = Set<String>(), caseCount = 0, stepCount = 0
        let counts = ["music-catalog": 30, "music-queue": 26, "music-session": 16, "music-reports": 9, "music-quality": 20, "music-playlists": 14]
        for suite in MusicVectorRunner.operations.keys.sorted() {
            let d = try MusicInput.object(JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("vectors/\(suite).json"))))
            XCTAssertEqual(try MusicInput.int(d, "schemaVersion"), 1)
            XCTAssertEqual(try MusicInput.string(d, "suite"), suite)
            let cases = try MusicInput.objects(d, "cases")
            XCTAssertEqual(cases.count, counts[suite], "Fixture inventory changed; review coverage deliberately")
            for c in cases {
                let id = try MusicInput.string(c, "id"), operation = try MusicInput.string(c, "operation")
                guard ids.insert(id).inserted, id.hasPrefix(suite + "."), id.count > suite.count + 1, MusicVectorRunner.operations[suite]?.contains(operation) == true else { throw MusicInput.failure("unknown/duplicate music fixture identity or operation \(id)") }
                if let steps = c["steps"] {
                    guard c["input"] == nil, c["expected"] == nil, ["queue", "session", "report", "playlist"].contains(operation) else { throw MusicInput.failure("mixed trace envelope") }
                    let rawSteps = try MusicInput.objects(["steps": steps], "steps")
                    stepCount += try MusicVectorRunner.run(suite: suite, operation: operation, id: id, state: MusicInput.object(c["state"]), steps: rawSteps)
                } else {
                    guard c["state"] == nil, let expected = c["expected"] else { throw MusicInput.failure("missing one-shot expected") }
                    let result = try MusicVectorRunner.execute(suite: suite, operation: operation, input: MusicInput.object(c["input"]), context: d["context"] as? [String: Any] ?? [:])
                    XCTAssertTrue(Self.projection(expected, matches: result), "\(id): expected \(Self.render(expected)), actual \(Self.render(result))")
                    if c["exact"] != nil { XCTAssertEqual(Self.render(expected), Self.render(result), id) }
                }
                caseCount += 1
            }
        }
        XCTAssertEqual(caseCount, 115)
        XCTAssertEqual(stepCount, 101)
    }

    func testMalformedOperationsAndInt64FailClosed() throws {
        let invalid: [(String, String, [String: Any])] = [
            ("music-unknown", "ceiling", ["quality": "auto", "metered": true]),
            ("music-quality", "bogus", [:]), ("music-session", "report", ["state": [:], "event": ["op": "stop"]]),
            ("music-session", "session", ["state": [:], "event": ["op": "unknown"]]),
            ("music-session", "session", ["state": [:], "event": ["op": "duck", "typo": true]]),
            ("music-reports", "report", ["state": [:], "event": ["op": "progress", "ticks": 9]]),
            ("music-quality", "profile", ["decoders": ["UNKNOWN"], "ceiling": 128000]),
            ("music-catalog", "section", ["section": "UNKNOWN", "library": "lib"]),
            ("music-quality", "ceiling", ["quality": "auto", "metered": "true"])
        ]
        for (suite, op, input) in invalid { XCTAssertThrowsError(try MusicVectorRunner.execute(suite: suite, operation: op, input: input, context: [:])) }
        for value: Any in ["01", "-0", "+1", "1.0", "9223372036854775808", "-9223372036854775809", 1, true] {
            XCTAssertThrowsError(try MusicVectorRunner.execute(suite: "music-queue", operation: "permutation", input: ["ids": ["a", "b"], "seed": value], context: [:]))
        }
        XCTAssertThrowsError(try MusicVectorRunner.run(suite: "music-session", operation: "session", id: "foreign.fixture", state: [:], steps: [["event": ["op": "stop"], "expected": ["effect": "none"]]]))
        XCTAssertThrowsError(try MusicVectorRunner.run(suite: "music-session", operation: "session", id: "music-session.bad-projection", state: [:], steps: [["event": ["op": "viewer-play"], "expected": ["effect": "pause"]]]))
    }

    func testLargeInt64StopPositionAndDuplicateEntries() throws {
        let huge = "9007199254740993"
        let loaded = try MusicReducers.report(state: ["serial": huge], event: ["op": "load", "entry": "one", "media": "song"])
        XCTAssertEqual(loaded["serial"] as? String, "9007199254740994")
        let started = try MusicReducers.report(state: loaded, event: ["op": "playing"])
        let stop = try MusicReducers.report(state: started, event: ["op": "stop", "ticks": huge])
        XCTAssertEqual((stop["effects"] as? [[String: Any]])?.first?["ticks"] as? String, huge)
        XCTAssertTrue(stop["viewing"] is NSNull)
        let entries = try MusicVectorRunner.execute(suite: "music-queue", operation: "entries", input: ["media": ["x", "x"], "first": huge], context: [:]) as! [[String: String]]
        XCTAssertEqual(entries.map { $0["id"]! }, ["m9007199254740993", "m9007199254740994"])
        let min = MusicQueue.permutation(["a", "b"], seed: Int64.min)
        XCTAssertEqual(Set(min), Set(["a", "b"]))
    }

    func testReferenceProfileCannotInventVideoAndPrivateWritesStayPrivate() throws {
        let profile = MusicQuality.referenceDeviceProfile(decoders: Set(ReferenceDeviceProfile.audioOrder), ceiling: 320_000_000)
        let conversions = try MusicInput.objects(profile, "TranscodingProfiles")
        XCTAssertEqual(conversions.count, 1)
        XCTAssertEqual(conversions[0]["VideoCodec"] as? String, "")
        XCTAssertEqual(conversions[0]["Type"] as? String, "Audio")
        XCTAssertEqual(MusicReducers.createPlaylistBody(name: "Mine", viewer: "v", items: [])["IsPublic"] as? Bool, false)
        XCTAssertEqual(Set(MusicReducers.renamePlaylistBody(name: "New").keys), ["Name"])
    }

    func testQueueJumpDedupAndAccountGenerationIsolation() throws {
        let state: [String: Any] = ["server": "one", "viewer": "v", "entries": [["id": "a", "media": "x"], ["id": "b", "media": "x"]], "current": "a", "positionTicks": "9007199254740993", "generation": "2"]
        let event: [String: Any] = ["op": "jump", "entry": "b", "generation": "2", "revision": "0", "operationId": "jump-one"]
        let result = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: state, event: event)
        let q = try MusicInput.object(result["queue"])
        XCTAssertEqual(q["current"] as? String, "b"); XCTAssertEqual(q["server"] as? String, "one"); XCTAssertEqual(q["viewer"] as? String, "v")
        let duplicate = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: event)
        XCTAssertEqual(duplicate["outcome"] as? String, "duplicate")
        XCTAssertEqual(Self.render(duplicate["queue"]!), Self.render(q))
        var stale = event; stale["generation"] = "1"
        XCTAssertEqual(try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: stale)["outcome"] as? String, "stale-generation")
        let repeatResult = try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: ["op": "repeat", "repeat": "one", "generation": "2", "revision": "1"])
        XCTAssertEqual((repeatResult["queue"] as? [String: Any])?["repeat"] as? String, "one")
        XCTAssertThrowsError(try ControlReducers.reduce(suite: "queue-edit", operation: "queue", state: q, event: ["op": "stop"]))
    }

    func testRestoreRetainsSavedRevisionAndRejectsOtherAccount() throws {
        let saved: [String: Any] = ["server": "s", "viewer": "v", "entries": [["id": "a", "media": "song"]], "current": "a", "visited": ["a"], "operations": ["old-jump"], "generation": "9", "revision": "41", "positionTicks": "9007199254740993"]
        let state: [String: Any] = ["committed": ["server": "s", "viewer": "v", "generation": "3", "revision": "6"]]
        let restored = try ControlReducers.reduce(suite: "queue-edit", operation: "store", state: state, event: ["op": "restore", "saved": saved, "accessible": ["a"]])
        let pending = try MusicInput.object(restored["pending"])
        XCTAssertEqual(pending["generation"] as? String, "10"); XCTAssertEqual(pending["revision"] as? String, "41")
        XCTAssertEqual(pending["positionTicks"] as? String, "9007199254740993"); XCTAssertEqual(pending["paused"] as? Bool, true)
        XCTAssertEqual(pending["operations"] as? [String], []); XCTAssertEqual(pending["visited"] as? [String], [])
        let other: [String: Any] = ["committed": ["server": "s", "viewer": "other"]]
        let refused = try ControlReducers.reduce(suite: "queue-edit", operation: "store", state: other, event: ["op": "restore", "saved": saved, "accessible": ["a"]])
        XCTAssertEqual(refused["reason"] as? String, "wrong-partition"); XCTAssertTrue(refused["pending"] is NSNull)
    }

    func testStorePersistsNaturalEndProposal() throws {
        let queue: [String: Any] = ["server": "s", "viewer": "v", "entries": [["id": "a", "media": "song"]], "current": "a", "paused": false]
        let result = try ControlReducers.reduce(suite: "queue-edit", operation: "store", state: ["committed": queue], event: ["op": "edit", "edit": ["op": "end"]])
        XCTAssertEqual(result["effect"] as? String, "persist")
        XCTAssertEqual((result["pending"] as? [String: Any])?["paused"] as? Bool, true)
    }

    static func projection(_ expected: Any, matches actual: Any) -> Bool {
        if let exp = expected as? [String: Any] {
            guard let got = actual as? [String: Any] else { return false }
            return exp.allSatisfy { key, value in projection(value, matches: got[key] ?? NSNull()) }
        }
        // Kotlin compares arrays exactly, including every field of object elements.
        return render(expected) == render(actual)
    }
    static func render(_ value: Any) -> String {
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .sortedKeys]), let text = String(data: data, encoding: .utf8) else { return "INVALID JSON" }
        return text
    }
}
