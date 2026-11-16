// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest

/// Unported shared cases, reported as a skip, never as passes. The list is derived from spec/vectors minus
/// SpecCaseManifest, so it follows main without edits.
final class PendingSpecVectorTests: XCTestCase {
    func testPendingSuitesAreReportedNotRun() throws {
        let suites = PendingSpecCaseManifest.suites.sorted { $0.key < $1.key }
        guard !suites.isEmpty else { return }
        let lines = suites.map { "\($0.key): \($0.value.count) pending. \($0.value.reason)" }
        throw XCTSkip("\(PendingSpecCaseManifest.contracts.count) shared cases in \(suites.count) suites await Apple ports:\n" + lines.joined(separator: "\n"))
    }
}
