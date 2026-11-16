// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
@testable import PictureshowCore

final class SyncVectorTests: XCTestCase {
    /// Every active SyncPlay case executes against the Swift core; counts come from the manifest, values from spec/vectors.
    func testAllActiveSyncVectors() throws {
        var failures: [String] = [], executed = 0, assertions = 0
        for suite in SyncVectorRunner.suites.sorted() {
            let doc = try SyncVectorRunner.document(suite)
            let cases = try XCTUnwrap(doc["cases"]?.items)
            let manifestIDs = Set(SpecCaseManifest.contracts.filter { $0.value.hasPrefix(suite + "/") }.map(\.key))
            XCTAssertEqual(Set(cases.compactMap { $0["id"]?.content }), manifestIDs, suite)
            XCTAssertNil(PendingSpecCaseManifest.suites[suite], suite)
            for row in cases {
                let id = row["id"]?.content ?? "?"
                do { assertions += try SyncVectorRunner.run(suite: suite, row: row); executed += 1 }
                catch { failures.append("\(suite) \(id): \(error)") }
            }
        }
        print("SyncPlay vectors: \(executed) cases passed, \(assertions) assertions, \(failures.count) failures across \(SyncVectorRunner.suites.count) suites")
        XCTAssertTrue(failures.isEmpty, failures.joined(separator: "\n"))
        XCTAssertEqual(executed, 406)
    }

    func testRunnerRejectsUnknownOperationsFieldsAndAssertions() throws {
        XCTAssertThrowsError(try SyncVectorRunner.run(suite: "sync-state", id: "sync-state.invented"))
        XCTAssertThrowsError(try SyncVectorRunner.run(suite: "sync-unknown", id: "x"))
        XCTAssertThrowsError(try SyncVectorRunner.apply(SyncMembership(), try SyncJSON.parse("{\"op\":\"future-operation\"}")))
        XCTAssertThrowsError(try SyncVectorRunner.project(try SyncJSON.parse("{\"futureAssertion\":true}"), SyncMembership(), "unknown"))
        XCTAssertThrowsError(try SyncVectorRunner.fields(try SyncJSON.parse("{\"silentSkip\":true}"), ["event", "expected"]))
        XCTAssertThrowsError(try SyncVectorRunner.run(suite: "sync-clock", row: try SyncJSON.parse("{\"id\":\"x\",\"op\":\"future\",\"input\":{},\"expected\":true}")))
    }

    func testTimestampsKeepHundredNanosecondsOffsetsAndCalendarBounds() throws {
        let base = try XCTUnwrap(SyncPlay.timestampTicks("2026-11-10T12:00:00.0000000Z"))
        XCTAssertEqual(SyncPlay.timestampTicks("2026-11-10T12:00:00.0000001Z"), base + 1)
        XCTAssertEqual(SyncPlay.timestampTicks("2026-11-10T14:30:00+02:30"), base)
        XCTAssertEqual(SyncPlay.timestampTicks("2026-11-10T06:30:00-05:30"), base)
        let formatter = ISO8601DateFormatter(); formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for text in ["1900-02-28T23:59:59.999Z", "1969-12-31T23:59:59.999Z", "1970-01-01T00:00:00.000Z", "2000-02-29T00:00:00.001Z", "2026-12-31T23:59:59.999Z", "9999-12-31T23:59:59.999Z", "0001-01-01T00:00:00.000Z"] {
            let ticks = try XCTUnwrap(SyncPlay.timestampTicks(text), text)
            let millis = try XCTUnwrap(SyncPlay.timestampMillis(text))
            XCTAssertEqual(ticks / 10_000 - (ticks < 0 && ticks % 10_000 != 0 ? 1 : 0), millis, text)
            XCTAssertEqual(SyncPlay.utcTimestamp(millis), text, text)
            if let date = formatter.date(from: text), !text.hasPrefix("0001") {
                XCTAssertEqual(Int64((date.timeIntervalSince1970 * 1000).rounded()), millis, text)
            }
        }
        for bad in ["2026-02-29T00:00:00Z", "2026-11-31T00:00:00Z", "2026-11-10T24:00:00Z", "not-a-time", "２026-11-10T12:00:00Z"] {
            XCTAssertNil(SyncPlay.timestampTicks(bad), bad)
        }
    }

    func testImpossibleWireNumbersNeverBecomeCommands() {
        for value in ["9223372036854775808", "-1", "1.5", "true", "null", "{}"] {
            let text = "{\"MessageType\":\"SyncPlayCommand\",\"Data\":{\"GroupId\":\"g\",\"PlaylistItemId\":\"e\",\"When\":\"2026-11-10T12:00:00Z\",\"EmittedAt\":\"2026-11-10T12:00:00Z\",\"Command\":\"Seek\",\"PositionTicks\":\(value)}}"
            XCTAssertEqual(SyncPlay.parseMessage(text), .invalid(groupId: "g", messageId: nil), value)
        }
        XCTAssertNil(SyncPlay.parseMessage("{\"MessageType\":\"Playstate\",\"Data\":{}}"))
        XCTAssertNil(SyncPlay.parseMessage("not json"))
    }

    func testRollingCalibrationRejectsInvalidSamplesAndAnchorsMonotonically() throws {
        var c = SyncClockCalibration()
        for i in Int64(1)...6 { c = c.adding(SyncClockSample(t0Millis: 1000, t1Millis: 1100, t2Millis: 1100, t3Millis: 1000 + i * 2)) }
        XCTAssertEqual(c.samples.count, 5)
        XCTAssertEqual(c.best?.networkRttMillis, 4)
        XCTAssertEqual(c.adding(SyncClockSample(t0Millis: 1000, t1Millis: 0, t2Millis: 0, t3Millis: 3000)), c)
        XCTAssertNil(SyncClockCalibration().anchor(wallMillis: 1000, monotonicMillis: 0))
        let a = try XCTUnwrap(c.anchor(wallMillis: 1000, monotonicMillis: 100))
        XCTAssertNotNil(a.serverNowTicks(wallMillis: 1200, monotonicMillis: 200))
        XCTAssertNil(a.serverNowTicks(wallMillis: 1201, monotonicMillis: 200))
        XCTAssertNil(a.serverNowTicks(wallMillis: 999, monotonicMillis: 400))
        XCTAssertFalse(a.needsRefresh(monotonicMillis: 30099))
        XCTAssertTrue(a.needsRefresh(monotonicMillis: 30100))
        XCTAssertNil(SyncTiming.millisecondsToTicks(.max))
        XCTAssertNil(SyncTiming.target(positionTicks: 0, whenUtcMillis: .max, serverNowTicks: 0, playing: true, durationTicks: 100))
        XCTAssertEqual(SyncTiming.target(positionTicks: -1, whenUtcMillis: 0, serverNowTicks: 0, playing: false, durationTicks: 100)?.positionTicks, 0)
        XCTAssertEqual(SyncTiming.stoppedDrift(), SyncDriftState())
    }

    func testRequestsMatchTheServerShapes() throws {
        XCTAssertEqual(try SyncRequest.create(name: "Friday").body?.rendered, "{\"GroupName\":\"Friday\"}")
        XCTAssertThrowsError(try SyncRequest.create(name: " "))
        XCTAssertEqual(try SyncRequest.transport("Seek", positionTicks: 50).body?.rendered, "{\"PositionTicks\":50}")
        XCTAssertNil(try SyncRequest.transport("Pause").body)
        XCTAssertThrowsError(try SyncRequest.transport("Seek"))
        XCTAssertEqual(try SyncRequest.readiness(ready: true, whenMs: 0, positionTicks: 5, isPlaying: false, playlistItemId: "e").body?.rendered,
                       "{\"When\":\"1970-01-01T00:00:00.000Z\",\"PositionTicks\":5,\"IsPlaying\":false,\"PlaylistItemId\":\"e\"}")
        XCTAssertEqual(try SyncRequest.newQueue(items: ["a", "b"], currentIndex: 1).body?.rendered, "{\"PlayingQueue\":[\"a\",\"b\"],\"PlayingItemPosition\":1,\"StartPositionTicks\":0}")
        XCTAssertEqual(SyncRequest.ignoreWait(true).path, "SyncPlay/SetIgnoreWait")
    }
}
