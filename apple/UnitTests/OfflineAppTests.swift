// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

final class OfflineAppTests: XCTestCase {
    private func entry(phase: OfflinePhase = .ready, failure: String? = nil, tracks: [OfflineTrack] = []) -> OfflineEntry {
        OfflineEntry(id: "i1:original", itemId: "i1", kind: .episode, preset: .original, runtimeTicks: 600_000_000,
                     labels: OfflineLabels(title: "A Spring Crossing", seriesName: "Open Frames", seasonNumber: 2, episodeNumber: 3),
                     resources: [OfflineResource(id: "media", role: .media, path: "media.mp4", bytes: 2_000_000, sha256: "x", complete: true)],
                     tracks: tracks, job: OfflineJob(phase: phase, failure: failure))
    }

    func testTaskTagRoundTripsAndRejectsForeignDescriptions() {
        let tag = TaskTag(viewerDir: "v-abc", viewer: "https://s.example.test|u1", entryID: "i1:video720", resourceID: "media", attempt: 7)
        let back = TaskTag(tag.description)
        XCTAssertEqual(back?.viewer, "https://s.example.test|u1")
        XCTAssertEqual(back?.entryID, "i1:video720")
        XCTAssertEqual(back?.attempt, 7)
        XCTAssertNil(TaskTag("not a tag"))
        XCTAssertNil(TaskTag(nil))
    }

    func testViewerDirectoryHasNothingPersonalInIt() {
        let dir = OfflineFiles.viewerDirectory(accountKey: "https://media.example.test|someone")
        XCTAssertTrue(dir.hasPrefix("v-"))
        XCTAssertFalse(dir.contains("example"))
        XCTAssertFalse(dir.contains("someone"))
        XCTAssertEqual(dir, OfflineFiles.viewerDirectory(accountKey: "https://media.example.test|someone"))
    }

    /// OFF-03b: percent only with a known total; unknown length shows received bytes.
    func testProgressNeverInventsATotal() {
        let running = entry(phase: .transferring)
        XCTAssertEqual(DownloadWords.state(running, DownloadProgress(received: 500, total: 1000), wifiOnly: true).hasPrefix("Downloading · 50%"), true)
        let unknown = DownloadWords.state(running, DownloadProgress(received: 5_000_000, total: nil), wifiOnly: true)
        XCTAssertFalse(unknown.contains("%"))
        XCTAssertTrue(unknown.contains("MB"))
        XCTAssertEqual(DownloadWords.state(running, nil, wifiOnly: true), "Downloading")
    }

    /// OFF-08: after our own write is read back, the server's answer becomes the base. Otherwise the next local change
    /// sees the position we wrote as someone else's and keeps it as a conflict (observed: completion left the old position).
    func testAcknowledgedWriteBecomesTheNextBase() throws {
        let base = OfflineUserData()
        var entry = try XCTUnwrap(reduceOfflineOutbox(nil, OfflineOutboxEvent(op: "viewing", server: "s", viewer: "v", itemId: "i",
                                                                              positionTicks: 217_670_000, sequence: 1, base: base)).entry)
        var result = try reduceOfflineOutbox(entry, OfflineOutboxEvent(op: "reconnect", server: "s", viewer: "v"))
        let empty = OfflineUserData()
        result = try reduceOfflineOutbox(result.entry, OfflineOutboxEvent(op: "server-state", current: empty))
        XCTAssertEqual(result.effect, "write")
        entry = try XCTUnwrap(result.entry)
        result = try reduceOfflineOutbox(entry, OfflineOutboxEvent(op: "write-result", status: 200))
        var written = OfflineUserData(); written.positionTicks = 217_670_000
        let readback = OfflineOutboxEvent(op: "readback", current: written)
        result = try reduceOfflineOutbox(result.entry, readback)
        XCTAssertNil(result.entry)
        let next = try XCTUnwrap(Downloads.serverStateAfter(result, readback))
        XCTAssertEqual(next.positionTicks, 217_670_000)
        // The completion that follows, judged against that base, writes rather than conflicting.
        let done = try XCTUnwrap(reduceOfflineOutbox(nil, OfflineOutboxEvent(op: "completed", server: "s", viewer: "v", itemId: "i", sequence: 2, base: next)).entry)
        let judged = try reduceOfflineOutbox(done, OfflineOutboxEvent(op: "server-state", current: written))
        XCTAssertEqual(judged.effect, "write")
        XCTAssertNil(judged.entry?.conflict)
    }

    /// Sizes read as numbers: an empty budget says "0 KB", never "Zero KB".
    func testSizesAreAlwaysNumeric() {
        XCTAssertEqual(DownloadWords.bytes(0), "0 KB")
        XCTAssertTrue(DownloadWords.bytes(3_700_000).hasSuffix("MB"))
    }

    func testFailedRowsUsePlainWordsNeverCodes() {
        for reason in ["needs-password", "http-404", "short", "verify", "transfer", nil, "something-new"] {
            let words = DownloadWords.state(entry(phase: .failed, failure: reason), nil, wifiOnly: true)
            XCTAssertFalse(words.contains("-"), "raw code in \(words)")
            XCTAssertFalse(words.isEmpty)
        }
    }

    func testWaitingSaysWhichNetwork() {
        let waiting = entry(phase: .waitingNetwork)
        XCTAssertEqual(DownloadWords.state(waiting, nil, wifiOnly: true), "Waiting for Wi-Fi")
        XCTAssertEqual(DownloadWords.state(waiting, nil, wifiOnly: false), "Waiting for a connection")
    }

    func testNotDownloadedTracksAreSummarisedOncePerLanguage() {
        let tracks = [
            OfflineTrack(serverIndex: 1, type: "audio", label: "English", language: "eng"),
            OfflineTrack(serverIndex: 2, type: "audio", label: "Spanish", language: "spa", available: false),
            OfflineTrack(serverIndex: 3, type: "subtitle", label: "Spanish", language: "spa", available: false),
            OfflineTrack(serverIndex: 4, type: "subtitle", label: "Spanish SDH", language: "spa", available: false),
            OfflineTrack(serverIndex: 5, type: "subtitle", label: "Track 5", available: false),
        ]
        let summary = entry(tracks: tracks).notDownloadedSummary ?? ""
        XCTAssertTrue(summary.hasPrefix("Not downloaded: "))
        XCTAssertEqual(summary.components(separatedBy: "Spanish").count - 1, 2, summary) // once for audio, once for subtitles
        XCTAssertTrue(summary.contains("1 other"), summary)
        XCTAssertNil(entry(tracks: [tracks[0]]).notDownloadedSummary)
    }

    func testOriginalIsOfferedOnlyWhenThisDeviceCanPlayIt() throws {
        func source(_ container: String, video: String, audio: String) throws -> MediaSource {
            let json: [String: Any] = ["Id": "s", "Container": container, "MediaStreams": [
                ["Index": 0, "Type": "Video", "Codec": video], ["Index": 1, "Type": "Audio", "Codec": audio]]]
            return try JSONDecoder().decode(MediaSource.self, from: JSONSerialization.data(withJSONObject: json))
        }
        XCTAssertTrue(OriginalSupport.playsHere(try source("mp4", video: "h264", audio: "aac")))
        XCTAssertTrue(OriginalSupport.playsHere(try source("mov,mp4,m4a", video: "hevc", audio: "eac3")))
        XCTAssertFalse(OriginalSupport.playsHere(try source("mkv", video: "h264", audio: "aac")))
        XCTAssertFalse(OriginalSupport.playsHere(try source("mp4", video: "av1", audio: "aac")))
        XCTAssertFalse(OriginalSupport.playsHere(try source("mp4", video: "h264", audio: "dts")))
        XCTAssertFalse(OriginalSupport.playsHere(nil))
    }

    func testOfflineItemCarriesWhatTheNextEpisodeCardShows() {
        let item = entry().offlineItem
        XCTAssertEqual(item.type, "Episode")
        XCTAssertEqual(item.seriesName, "Open Frames")
        XCTAssertEqual(item.parentIndexNumber, 2)
        XCTAssertEqual(item.indexNumber, 3)
        XCTAssertEqual(item.runTimeTicks, 600_000_000)
    }

    func testLocalTrackRowsListOnlyWhatIsOnThisDevice() {
        let tracks = [
            OfflineTrack(serverIndex: 1, type: "audio", label: "English", language: "eng", isDefault: true),
            OfflineTrack(serverIndex: 2, type: "audio", label: "Spanish", language: "spa", available: false),
            OfflineTrack(serverIndex: 3, type: "subtitle", label: "English", language: "eng", resourceId: "sub-3"),
            OfflineTrack(serverIndex: 4, type: "subtitle", label: "French", language: "fre", available: false),
        ]
        let media = LocalMedia(accountKey: "k", entry: entry(tracks: tracks), url: URL(fileURLWithPath: "/tmp/x.mp4"),
                               subtitles: [LocalSubtitle(serverIndex: 3, label: "English", language: "eng", url: URL(fileURLWithPath: "/tmp/x.vtt"))])
        XCTAssertEqual(LocalTracks.streams(media, type: "audio").map(\.index), [1])
        XCTAssertEqual(LocalTracks.streams(media, type: "subtitle").map(\.index), [3])
        XCTAssertEqual(LocalTracks.streams(media, type: "audio").first?.type, "Audio")
    }
}
