// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Foundation
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// Holds real label work off the UI thread; no elapsed-time performance threshold.
private final class MenuWorkerGate: @unchecked Sendable {
    let entered = DispatchSemaphore(value: 0)
    let release = DispatchSemaphore(value: 0)
    private let lock = NSLock()
    private var mainThread = false
    private var builds = 0
    var ranOnMain: Bool { lock.lock(); defer { lock.unlock() }; return mainThread }
    var buildCount: Int { lock.lock(); defer { lock.unlock() }; return builds }
    func build(_ streams: [MediaStream], generation: Int, hold: Bool) -> PreparedTrackMenu {
        lock.lock(); mainThread = mainThread || Thread.isMainThread; builds += 1; lock.unlock()
        if hold {
            entered.signal()
            _ = release.wait(timeout: .now() + 10)
        }
        return TrackLabelCache.build(streams, generation: generation)
    }
}

@MainActor final class PreparedTrackMenuTests: XCTestCase {
    private func streams(count: Int = 45, title: String = "Commentary") throws -> [MediaStream] {
        try (0..<count).map { index in
            let object: [String: Any] = ["Index": index + 1, "Type": index == 0 ? "Audio" : "Subtitle",
                                        "Title": title, "Language": "en", "Codec": index == 0 ? "aac" : "srt"]
            return try JSONDecoder().decode(MediaStream.self, from: JSONSerialization.data(withJSONObject: object))
        }
    }
    private func entered(_ gate: MenuWorkerGate) async {
        let reached = await Task.detached { gate.entered.wait(timeout: .now() + 5) == .success }.value
        XCTAssertTrue(reached, "The metadata worker must start without an open request")
    }
    func testViewerLabelsMatchCoreWhileKeepingSourceIndexesInternal() throws {
        let metadata: [[String: Any]] = [
            ["Index": 12, "Type": "Audio", "Language": "en", "Codec": "aac", "Channels": 2, "IsDefault": true],
            ["Index": 37, "Type": "Subtitle", "Language": "en", "Codec": "srt", "IsDefault": true],
            ["Index": 0, "Type": "Video", "Codec": "h264"]
        ]
        let streams = try JSONDecoder().decode([MediaStream].self, from: JSONSerialization.data(withJSONObject: metadata))
        let menu = TrackLabelCache.build(streams, generation: 9)
        XCTAssertEqual(menu.generation, 9)
        XCTAssertEqual(menu.audio, [TrackLabel(id: 12, title: "English · AAC stereo", menuTitle: "English · AAC stereo")])
        XCTAssertEqual(menu.subtitles, [TrackLabel(id: 37, title: "English · SRT", menuTitle: "English · SRT")])
        XCTAssertFalse((menu.audio + menu.subtitles).contains { $0.title.contains("Stream ") || $0.menuTitle.contains("Stream ") })
        XCTAssertEqual(menu.audio.map(\.title), PlaybackTrackLabels.labels(streams.filter { $0.type == "Audio" }))
        XCTAssertEqual(menu.subtitles.map(\.title), PlaybackTrackLabels.labels(streams.filter { $0.type == "Subtitle" }))
    }
    func testBothMenusStartAtMetadataArrivalOffMainAndEarlyOpenGetsOneCompleteMenu() async throws {
        let gate = MenuWorkerGate()
        defer { gate.release.signal() }
        let cache = TrackLabelCache { gate.build($0, generation: $1, hold: true) }
        cache.update(try streams(), sourceID: "source-a")
        await entered(gate)
        XCTAssertNil(cache.prepared)
        XCTAssertFalse(gate.ranOnMain)
        var opened: [TrackMenuPresentation] = []
        cache.requestOpen { if let value = $0 { opened.append(value) } }
        XCTAssertTrue(cache.waitingToOpen); XCTAssertNil(cache.openMenu)
        // This main-actor change executes while the worker is held.
        var responsive = false; responsive = true
        XCTAssertTrue(responsive)
        gate.release.signal()
        await cache.worker?.value
        let menu = try XCTUnwrap(cache.prepared)
        XCTAssertEqual(menu.audio.count, 1); XCTAssertEqual(menu.subtitles.count, 44)
        XCTAssertEqual(opened.count, 1); XCTAssertEqual(opened.first?.menu, menu)
        XCTAssertFalse(cache.waitingToOpen)
        XCTAssertEqual(cache.labelsBuilt, 45)
        let revision = cache.cacheRevision
        for _ in 0..<8 {
            // Clock/focus/selection do not form the metadata key. Repeated metadata
            // publications must also retain this very generation and both menus.
            cache.update(try streams(), sourceID: "source-a")
            cache.cancelOpen(); cache.requestOpen()
        }
        XCTAssertEqual(cache.prepared, menu)
        XCTAssertEqual(cache.cacheRevision, revision)
        XCTAssertEqual(cache.labelsBuilt, 45); XCTAssertEqual(gate.buildCount, 1)
    }
    func testOldSlowWorkerCannotPublishIntoReplacementWithIdenticalStreams() async throws {
        let gate = MenuWorkerGate()
        defer { gate.release.signal() }
        let cache = TrackLabelCache { streams, revision in gate.build(streams, generation: revision, hold: revision == 1) }
        let values = try streams()
        cache.update(values, sourceID: "source-old")
        let oldWorker = cache.worker
        await entered(gate)
        var callbacks = 0
        cache.requestOpen { if $0 != nil { callbacks += 1 } }
        cache.update(values, sourceID: "source-new")
        await cache.worker?.value
        let current = try XCTUnwrap(cache.prepared)
        XCTAssertEqual(current.generation, 2)
        XCTAssertNil(cache.openMenu); XCTAssertFalse(cache.waitingToOpen)
        gate.release.signal(); await oldWorker?.value
        XCTAssertEqual(cache.prepared, current)
        XCTAssertEqual(callbacks, 0, "Source replacement cancels the old pending open")
        XCTAssertFalse(gate.ranOnMain)
    }
    func testMetadataChangeWithSameSourceInvalidatesOpenRowsAndStalePick() async throws {
        let cache = TrackLabelCache()
        cache.update(try streams(title: "First"), sourceID: "source")
        await cache.worker?.value
        cache.requestOpen()
        let old = try XCTUnwrap(cache.openMenu)
        XCTAssertTrue(cache.accepts(old))
        cache.update(try streams(title: "Replacement"), sourceID: "source")
        XCTAssertFalse(cache.accepts(old)); XCTAssertNil(cache.openMenu)
        // Captured rows survive for native panel handoff, with their old descriptions.
        XCTAssertTrue(old.menu.subtitles.allSatisfy { $0.title.contains("First") })
        await cache.worker?.value
        XCTAssertTrue(try XCTUnwrap(cache.prepared).subtitles.allSatisfy { $0.title.contains("Replacement") })
        XCTAssertNil(cache.openMenu, "Completion may not reopen a replaced panel")
    }
    func testBeforeFirstSourceOpenFollowsFirstRealSourceUnlessCancelled() async throws {
        for cancel in [false, true] {
            let cache = TrackLabelCache()
            cache.requestOpen()
            XCTAssertNil(cache.openMenu); XCTAssertTrue(cache.waitingToOpen)
            if cancel { cache.cancelOpen() }
            cache.update(try streams(count: 2), sourceID: "first-source")
            await cache.worker?.value
            XCTAssertEqual(cache.openMenu != nil, !cancel)
            XCTAssertFalse(cache.waitingToOpen)
        }
    }
    func testEveryPendingCancellationPreventsDelayedUnsolicitedOpen() async throws {
        // Session/controller adapters call the same cancellation operation for each
        // event. Native lifecycle/input delivery remains in the named runtime plan.
        for event in ["Back", "stop", "background", "error", "navigation"] {
            let gate = MenuWorkerGate()
            defer { gate.release.signal() }
            let cache = TrackLabelCache { gate.build($0, generation: $1, hold: true) }
            cache.update(try streams(count: 2), sourceID: "source")
            await entered(gate)
            var results: [Bool] = []
            cache.requestOpen { results.append($0 != nil) }
            cache.cancelOpen()
            gate.release.signal(); await cache.worker?.value
            XCTAssertEqual(results, [false], event)
            XCTAssertNil(cache.openMenu, event); XCTAssertFalse(cache.waitingToOpen, event)
            XCTAssertNotNil(cache.prepared, "Cancellation of an open does not discard reusable metadata")
        }
    }
    func testOpenPresentationTokenRefusesCallbacksAfterBackAndReopen() async throws {
        let cache = TrackLabelCache()
        cache.update(try streams(count: 2), sourceID: "source")
        await cache.worker?.value
        cache.requestOpen()
        let old = try XCTUnwrap(cache.openMenu)
        cache.cancelOpen(); cache.requestOpen()
        let current = try XCTUnwrap(cache.openMenu)
        XCTAssertNotEqual(old.id, current.id)
        XCTAssertFalse(cache.accepts(old)); XCTAssertTrue(cache.accepts(current))
        XCTAssertEqual(old.menu, current.menu, "Reopening reuses descriptions without rebuilding")
    }
}
