// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import MediaPlayer
import SwiftUI
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

/// The order every collaborator saw things in, so a test can say "saved before the output changed".
@MainActor private final class Journal { var lines: [String] = [] }

@MainActor private final class FakeEngine: MusicEngine {
    weak var events: MusicEngineEvents?
    let journal: Journal
    private(set) var items: [MusicEngineItem] = []
    private(set) var stopped = false
    var isPlaying = false
    var buffering = false
    var seconds: Double = 0
    var duration: Double? = 180
    var repeatsOne = false
    init(_ journal: Journal) { self.journal = journal }

    var held: [String] { items.map(\.entry) }
    var playing: String? { items.first?.entry }

    func replace(_ items: [MusicEngineItem], at seconds: Double) {
        journal.lines.append("engine.replace \(items.map(\.entry).joined(separator: ","))")
        self.items = items; self.seconds = seconds
        if let first = items.first { events?.engineAdvanced(to: first.entry, natural: false) }
    }
    func removeFirst(_ count: Int) { items.removeFirst(min(count, max(0, items.count - 1))) }
    func removeLast(_ count: Int) { items.removeLast(min(count, max(0, items.count - 1))) }
    func append(_ items: [MusicEngineItem]) { journal.lines.append("engine.append \(items.map(\.entry).joined(separator: ","))"); self.items += items }
    func setPlaying(_ on: Bool) { if isPlaying != on { isPlaying = on; events?.engineChanged() } }
    func seek(to seconds: Double) { self.seconds = seconds }
    func stop() { journal.lines.append("engine.stop"); stopped = true; isPlaying = false; items = []; events = nil }

    /// The current track plays to its end, as AVQueuePlayer moves on by itself.
    func finishCurrent() {
        seconds = duration ?? 0
        guard !items.isEmpty else { return }
        items.removeFirst()
        if let next = items.first { seconds = 0; events?.engineAdvanced(to: next.entry, natural: true) } else { events?.engineEnded() }
    }
    func fail(_ failure: MusicEngineFailure) { events?.engineFailed(entry: playing, failure) }
}

@MainActor private final class FakeGateway: MusicGateway {
    let journal: Journal
    var prepared: [String] = []
    var reports: [(kind: String, media: String, ticks: Int64, failed: Bool)] = []
    var refuse: Error?
    var accessible: Set<String>?
    init(_ journal: Journal) { self.journal = journal }

    func prepare(_ account: Account, media: String, ceiling: Int) async throws -> PlayDecision {
        if let refuse { throw refuse }
        prepared.append(media)
        return PlayDecision(itemID: media, url: URL(string: "https://media.example.test/Audio/\(media)/stream")!, method: .directPlay,
                            source: MediaSource(id: media, container: "flac", supportsDirectPlay: true))
    }
    func report(_ account: Account, kind: String, decision: PlayDecision, ticks: Int64, paused: Bool, failed: Bool) async {
        journal.lines.append("report \(kind) \(decision.itemID)")
        reports.append((kind, decision.itemID, ticks, failed))
    }
    func accessible(_ account: Account, ids: [String]) async throws -> Set<String> { accessible ?? Set(ids) }
}

@MainActor private final class FakeStore: MusicQueueStore {
    let journal: Journal
    var saved: [String: MusicSnapshot] = [:]
    var positions: [String: (String?, Int64)] = [:]
    var failSaves = false
    init(_ journal: Journal) { self.journal = journal }
    func save(_ key: String, _ snapshot: MusicSnapshot) -> Bool {
        journal.lines.append("store.save")
        guard !failSaves else { return false }
        saved[key] = snapshot; return true
    }
    func savePosition(_ key: String, entry: String?, ticks: Int64) { positions[key] = (entry, ticks) }
    func load(_ key: String) -> MusicSnapshot? {
        guard var s = saved[key] else { return nil }
        if let (entry, ticks) = positions[key], let entry, s.queue["current"] as? String == entry { s.queue["positionTicks"] = String(ticks) }
        return s
    }
    func delete(_ key: String) { saved[key] = nil }
    func retain(_ keys: Set<String>) { saved = saved.filter { keys.contains($0.key) } }
}

/// The music host's wiring: every edit is saved before the output changes, the output holds the shared window, one track
/// is one viewing, and the AUTH-18, MUS-08 and MUS-11 rules hold in the app, not only in the reducers.
/// The lock screen as the host sees it, owned by one test, so nothing else in the process can change it.
@MainActor private final class FakeNowPlaying: NowPlayingSystem {
    var info: [String: Any]?
    /// `.some(nil)` once told nothing plays.
    var playing: Bool??
    var enabled: Set<NowPlayingCommand> = []
    var handlers: [UUID: (command: NowPlayingCommand, run: @MainActor (TimeInterval?) -> Bool)] = [:]
    func setPlaying(_ playing: Bool?) { self.playing = .some(playing) }
    func setEnabled(_ command: NowPlayingCommand, _ enabled: Bool) {
        if enabled { self.enabled.insert(command) } else { self.enabled.remove(command) }
    }
    func addTarget(_ command: NowPlayingCommand, _ handler: @escaping @MainActor (TimeInterval?) -> Bool) -> () -> Void {
        let id = UUID()
        handlers[id] = (command, handler)
        return { [weak self] in self?.handlers[id] = nil }
    }
    /// A press on the lock screen: true when a handler acted.
    func press(_ command: NowPlayingCommand, position: TimeInterval? = nil) -> Bool {
        handlers.values.filter { $0.command == command }.map { $0.run(position) }.contains(true)
    }
}

@MainActor
final class MusicAppTests: XCTestCase {
    private let viewer = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: "viewer-a", username: "A", accessToken: "t", deviceID: "dev")
    private let other = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "srv"), userId: "viewer-b", username: "B", accessToken: "t2", deviceID: "dev")
    private var journal: Journal!
    private var lockScreen: FakeNowPlaying!
    private var engines: [FakeEngine] = []
    private var gateway: FakeGateway!
    private var store: FakeStore!

    override func setUp() async throws {
        journal = Journal(); engines = []
        gateway = FakeGateway(journal); store = FakeStore(journal)
        lockScreen = FakeNowPlaying()
    }

    private func host(admitted: Bool = true) -> MusicHost {
        let host = MusicHost(gateway: gateway, store: store, makeEngine: { [unowned self] in
            let e = FakeEngine(self.journal); self.engines.append(e); return e
        }, nowPlayingSystem: lockScreen)
        host.admitted = { _ in admitted }
        host.route = { "device" }
        host.quality = { "original" }
        return host
    }

    private func tracks(_ n: Int) -> [MusicCard] {
        (1...n).map { MusicCard(id: "song\($0)", title: "Song \($0)", artist: "Artist", album: "Album", durationTicks: 180 * 10_000_000) }
    }

    private func playAlbum(_ host: MusicHost, start: Int? = nil, count: Int = 4) async {
        host.request(MusicPlay(owner: viewer.key, tracks: tracks(count), start: start, context: "MusicAlbum:a1", contextName: "Album"))
        await host.settle()
    }

    func testPlaySavesTheQueueBeforeTheOutputAndHoldsTheSharedWindow() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host, start: 1)
        XCTAssertEqual(host.currentCard?.id, "song2")
        XCTAssertFalse(host.paused)
        let engine = try! XCTUnwrap(engines.first)
        // The window from the shared queue: the current entry and the next two, nothing more.
        XCTAssertEqual(engine.held, MusicQueue.window(order: host.order, current: host.current, repeatMode: "off", failed: []))
        XCTAssertEqual(engine.held.count, 3)
        let save = journal.lines.firstIndex(of: "store.save"), replace = journal.lines.firstIndex { $0.hasPrefix("engine.") }
        XCTAssertNotNil(save); XCTAssertNotNil(replace)
        XCTAssertLessThan(save!, replace!, "the queue is saved before the output changes")
        XCTAssertTrue(engine.isPlaying)
    }

    func testANaturalEndMovesTheQueueOnAndReportsOneViewingPerTrack() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        let engine = engines[0]
        let first = host.current
        engine.finishCurrent()
        await host.settle()
        XCTAssertNotEqual(host.current, first)
        XCTAssertEqual(host.currentCard?.id, "song2")
        let kinds = gateway.reports.map { "\($0.kind) \($0.media)" }
        XCTAssertEqual(kinds.filter { $0 == "start song1" }.count, 1, "\(kinds)")
        XCTAssertEqual(kinds.filter { $0 == "stopped song1" }.count, 1, "\(kinds)")
        XCTAssertEqual(kinds.filter { $0 == "start song2" }.count, 1, "\(kinds)")
        // The finished track is reported at its full length, not at zero.
        XCTAssertEqual(gateway.reports.first { $0.kind == "stopped" && $0.media == "song1" }?.ticks, 180 * 10_000_000)
        // The window slid: the next entry after the old window joined the output.
        XCTAssertTrue(engine.held.contains(host.order[3]), "\(engine.held)")
    }

    func testTheLastTrackEndingStopsTheOutputAndKeepsTheQueue() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host, count: 1)
        engines[0].finishCurrent()
        await host.settle()
        XCTAssertFalse(host.outputActive)
        XCTAssertTrue(host.hasQueue)
        XCTAssertTrue(host.paused)
        XCTAssertEqual(host.positionTicks, 0, "Play starts the finished queue's last track again, not at its end")
    }

    func testOpeningAVideoStopsMusicOnceAndKeepsItsQueue() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        engines[0].seconds = 42
        host.beforeVideo()
        host.beforeVideo()
        await host.settle()
        XCTAssertTrue(engines[0].stopped)
        XCTAssertFalse(host.outputActive)
        XCTAssertEqual(host.order.count, 4)
        XCTAssertTrue(host.paused)
        XCTAssertEqual(host.positionTicks, 42 * 10_000_000)
        XCTAssertEqual(gateway.reports.filter { $0.kind == "stopped" }.count, 1)
    }

    func testALockedProfileCantStartMusicAndLockingStopsIt() async {
        var open = true
        let host = host()
        host.admitted = { _ in open }
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        open = false
        host.lockOut()
        await host.settle()
        XCTAssertFalse(host.outputActive)
        XCTAssertTrue(host.hasQueue, "the queue is kept for after unlock")
        host.play()
        host.toggle()
        await playAlbum(host)
        XCTAssertEqual(engines.count, 1, "no output starts behind the lock")
        XCTAssertTrue(host.paused)
        open = true
        host.play()
        await host.settle()
        XCTAssertEqual(engines.count, 2)
        XCTAssertFalse(host.paused)
    }

    func testAnEditThatCantBeSavedDoesntApply() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        let before = host.order
        store.failSaves = true
        host.remove(host.upNext[0])
        await host.settle()
        XCTAssertEqual(host.order, before)
        XCTAssertTrue(host.canRetrySave)
        store.failSaves = false
        host.retrySave()
        await host.settle()
        XCTAssertEqual(host.order.count, before.count - 1, "Try again applies the edit that waited")
        XCTAssertFalse(host.canRetrySave)
    }

    func testASavedQueueComesBackPausedForItsViewerOnly() async {
        let first = host()
        first.bind(viewer, known: [viewer.key, other.key])
        await playAlbum(first, start: 2)
        engines[0].seconds = 30
        first.pause()
        await first.settle()
        first.bind(nil, known: [viewer.key, other.key])

        let second = host()
        gateway.accessible = ["song1", "song3", "song4"]
        second.bind(viewer, known: [viewer.key, other.key])
        await second.settle()
        XCTAssertEqual(second.currentCard?.id, "song3")
        XCTAssertTrue(second.paused)
        XCTAssertFalse(second.outputActive, "a restored queue never starts sound by itself")
        XCTAssertEqual(second.order.count, 3, "a track the viewer can no longer open is dropped")
        XCTAssertEqual(second.positionTicks, 30 * 10_000_000)

        second.bind(other, known: [viewer.key, other.key])
        await second.settle()
        XCTAssertFalse(second.hasQueue, "another viewer never sees this queue")
        second.request(MusicPlay(owner: viewer.key, tracks: tracks(2), start: nil, context: "x", contextName: nil))
        await second.settle()
        XCTAssertFalse(second.hasQueue, "a play asked for the previous viewer doesn't run for this one")
    }

    func testAViewerRemovedFromTheDeviceLosesTheirSavedQueue() async {
        let host = host()
        host.bind(viewer, known: [viewer.key, other.key])
        await playAlbum(host)
        XCTAssertNotNil(store.saved[viewer.key])
        host.bind(other, known: [other.key])
        XCTAssertNil(store.saved[viewer.key])
    }

    func testARevokedStreamExpiresTheViewerAndKeepsTheQueue() async {
        var expired: [String] = []
        let host = host()
        host.expire = { expired.append($0.key) }
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        engines[0].fail(.revoked)
        await host.settle()
        XCTAssertEqual(expired, [viewer.key])
        XCTAssertFalse(host.outputActive)
        XCTAssertTrue(host.hasQueue)
    }

    func testAnUnplayableTrackIsSkippedAndReportedFailed() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        engines[0].fail(.media)
        await host.settle()
        XCTAssertEqual(host.currentCard?.id, "song2")
        XCTAssertEqual(gateway.reports.first { $0.kind == "stopped" && $0.media == "song1" }?.failed, true)
    }

    func testAddingToAnEmptyQueueDoesntStartSound() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        host.append(owner: viewer.key, tracks(2))
        await host.settle()
        XCTAssertEqual(host.order.count, 2)
        XCTAssertTrue(host.paused)
        XCTAssertTrue(engines.isEmpty)
        XCTAssertEqual(host.message, "Added 2 tracks to the queue.")
    }

    func testPlayingAnotherAlbumAsksBeforeReplacingTheQueue() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        host.request(MusicPlay(owner: viewer.key, tracks: [MusicCard(id: "other", title: "Other")], start: nil, context: "MusicAlbum:a2", contextName: "Other album"))
        await host.settle()
        XCTAssertNotNil(host.prompt)
        XCTAssertEqual(host.order.count, 4)
        host.confirm()
        await host.settle()
        XCTAssertEqual(host.order.count, 1)
        XCTAssertEqual(host.currentCard?.id, "other")
    }

    func testRepeatOneMakesTheOutputRepeatTheTrack() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        host.cycleRepeat(); host.cycleRepeat()
        await host.settle()
        XCTAssertEqual(host.repeatMode, "one")
        XCTAssertTrue(engines[0].repeatsOne)
        XCTAssertEqual(engines[0].held.first, host.current)
    }

    func testLockScreenShowsTheTrackAndClearsOnStop() async {
        let host = host()
        host.bind(viewer, known: [viewer.key])
        await playAlbum(host)
        let info = lockScreen.info
        XCTAssertEqual(info?[MPMediaItemPropertyTitle] as? String, "Song 1")
        XCTAssertEqual(info?[MPMediaItemPropertyArtist] as? String, "Artist")
        XCTAssertEqual(info?[MPMediaItemPropertyPlaybackDuration] as? Double, 180)
        XCTAssertTrue(lockScreen.enabled.contains(.nextTrack))
        XCTAssertTrue(lockScreen.enabled.contains(.togglePlayPause))
        XCTAssertFalse(lockScreen.enabled.contains(.skipForward), "music moves by track on the lock screen")
        // A lock-screen Next takes the same road as the on-screen button.
        XCTAssertTrue(lockScreen.press(.nextTrack))
        await host.settle()
        XCTAssertEqual(lockScreen.info?[MPMediaItemPropertyTitle] as? String, "Song 2")
        host.stop()
        await host.settle()
        XCTAssertNil(lockScreen.info)
        XCTAssertEqual(lockScreen.playing, .some(nil), "stopped, not left paused")
        XCTAssertFalse(lockScreen.enabled.contains(.nextTrack))
        XCTAssertTrue(lockScreen.handlers.isEmpty, "every command target is removed")
        XCTAssertTrue(host.hasQueue)
    }

    func testMusicRoutesOpenMusicScreensAndVideoPlaylistsDont() throws {
        func item(_ json: String) throws -> MediaItem { try JSONDecoder().decode(MediaItem.self, from: Data(json.utf8)) }
        XCTAssertEqual(try item(#"{"Id":"l","Name":"Music","Type":"CollectionFolder","CollectionType":"music"}"#).musicRoute?.kind, "library")
        XCTAssertEqual(try item(#"{"Id":"a","Name":"Album","Type":"MusicAlbum"}"#).musicRoute?.kind, "MusicAlbum")
        XCTAssertEqual(try item(#"{"Id":"p","Name":"Mix","Type":"Playlist","MediaType":"Audio"}"#).musicRoute?.kind, "Playlist")
        XCTAssertNil(try item(#"{"Id":"v","Name":"Films","Type":"Playlist","MediaType":"Video"}"#).musicRoute)
        XCTAssertNil(try item(#"{"Id":"m","Name":"Movie","Type":"Movie"}"#).musicRoute)
    }

    /// MUS-01: search shows music after every video group, and each music result opens a music screen.
    func testSearchAddsMusicGroupsAfterTheVideoGroups() throws {
        XCTAssertEqual(SearchKind.allCases, [.movies, .shows, .episodes, .collections, .people, .albums, .artists, .songs, .playlists])
        XCTAssertEqual(SearchKind.allCases.filter(\.isMusic).map(\.title), ["Albums", "Artists", "Songs", "Playlists"])
        func item(_ json: String) throws -> MediaItem { try JSONDecoder().decode(MediaItem.self, from: Data(json.utf8)) }
        XCTAssertEqual(try item(#"{"Id":"r","Name":"Ensemble","Type":"MusicArtist"}"#).musicRoute?.kind, "MusicArtist")
        XCTAssertEqual(try item(#"{"Id":"s","Name":"Signal","Type":"Audio"}"#).musicRoute?.kind, "Audio")
    }

    /// A focused (white) music row draws its second line dark enough to read: at least 4.5:1 on white.
    func testAFocusedMusicRowKeepsItsSecondLineReadable() {
        let opacity = 0.68 // MusicRowBody.focusedSecondary is black at this opacity over white.
        let channel = 1 - opacity
        let linear = channel <= 0.04045 ? channel / 12.92 : pow((channel + 0.055) / 1.055, 2.4)
        XCTAssertGreaterThanOrEqual(1.05 / (linear + 0.05), 4.5)
        XCTAssertEqual(MusicRowBody.focusedSecondary, Color.black.opacity(opacity))
    }
}

// MARK: Offline music (MUS-10)

/// Downloaded tracks as the host sees them: which are files here, and what their listens wrote down.
@MainActor private final class FakeLocal: MusicLocal {
    var files: Set<String> = []
    var reports: [(media: String, report: String, ticks: Int64)] = []
    func decision(_ account: Account, media: String) -> PlayDecision? {
        guard files.contains(media) else { return nil }
        var source = MediaSource(id: media, container: "flac", supportsDirectPlay: true)
        source.runTimeTicks = 180 * 10_000_000
        return PlayDecision(itemID: media, url: URL(fileURLWithPath: "/offline/\(media).flac"), method: .directPlay, source: source)
    }
    func downloaded(_ account: Account) -> Set<String> { files }
    func art(_ account: Account, media: String) -> URL? { files.contains(media) ? URL(fileURLWithPath: "/offline/\(media).jpg") : nil }
    func report(_ account: Account, media: String, serial: String, report: String, ticks: Int64, runtimeTicks: Int64?) {
        reports.append((media, report, ticks))
    }
}

extension MusicAppTests {
    private func offlineHost(_ files: Set<String>) -> (MusicHost, FakeLocal) {
        let host = host()
        let local = FakeLocal()
        local.files = files
        host.local = local
        host.bind(viewer, known: [viewer.key])
        return (host, local)
    }

    private var noNetwork: Error { URLError(.notConnectedToInternet) }

    /// The negative check on the offline start path: without the local source, this queue can only pause with "Can't reach".
    func testDownloadedTracksPlayWithNoServer() async throws {
        let (host, _) = offlineHost(["song1", "song2", "song3", "song4"])
        gateway.refuse = noNetwork
        await playAlbum(host)
        let engine = try XCTUnwrap(engines.first)
        XCTAssertFalse(host.paused)
        XCTAssertNil(host.message)
        XCTAssertEqual(engine.items.count, 3)
        XCTAssertTrue(engine.items.allSatisfy(\.url.isFileURL), "\(engine.items.map(\.url))")
        XCTAssertTrue(gateway.prepared.isEmpty, "a downloaded track asks the server nothing")
        // Its art is the saved file, wherever the card came from.
        XCTAssertEqual(host.currentCard?.art, URL(fileURLWithPath: "/offline/song1.jpg"))
    }

    func testANotDownloadedTrackIsSkippedOnceWhenADownloadedOneIsAhead() async {
        let (host, _) = offlineHost(["song2"])
        gateway.refuse = noNetwork
        await playAlbum(host)
        XCTAssertEqual(host.currentCard?.id, "song2")
        XCTAssertFalse(host.paused)
        XCTAssertEqual(host.message, "Skipped Song 1: it isn't downloaded, and the server can't be reached.")
        XCTAssertEqual((host.committed["failed"] as? [String])?.count, 1, "marked, so it is skipped once per pass")
    }

    func testNothingDownloadedAheadStaysPausedAndSaysSo() async {
        let (host, _) = offlineHost(["song1"])
        gateway.refuse = noNetwork
        await playAlbum(host, start: 1)
        XCTAssertEqual(host.currentCard?.id, "song2")
        XCTAssertTrue(host.paused)
        XCTAssertEqual(host.message, "Song 2 isn't downloaded, and the server can't be reached. Nothing after it is downloaded.")
    }

    func testAQueueWithNothingDownloadedPausesAsBefore() async {
        let (host, _) = offlineHost([])
        gateway.refuse = noNetwork
        await playAlbum(host)
        XCTAssertTrue(host.paused)
        XCTAssertEqual(host.message, MUSIC_UNREACHABLE_TEXT)
    }

    func testRepeatAllSkipsEachMissingTrackOnceThenStops() async {
        let (host, _) = offlineHost(["song1"])
        gateway.refuse = noNetwork
        await playAlbum(host, start: 1)  // song2 isn't here; nothing ahead without the wrap, so it pauses
        XCTAssertTrue(host.paused)
        host.cycleRepeat()  // Repeat all: song1 is ahead again, after the wrap
        host.play()
        await host.settle()
        XCTAssertEqual(host.currentCard?.id, "song1", "songs 2, 3 and 4 skipped once each on the way round")
        XCTAssertFalse(host.paused)
        engines.last?.finishCurrent()
        await host.settle()
        // The next pass meets the same missing tracks: they stay marked and are passed over, so only the downloaded one plays.
        XCTAssertEqual(host.currentCard?.id, "song1", "\(host.committed)")
        XCTAssertEqual(Set(host.committed["failed"] as? [String] ?? []), ["m1", "m2", "m3"])
        XCTAssertFalse(host.paused)
    }

    func testALateDownloadPlaysFromTheFileNextTimeAndThePlayingTrackIsntSwapped() async throws {
        let (host, local) = offlineHost([])
        await playAlbum(host)
        let engine = try XCTUnwrap(engines.first)
        XCTAssertFalse(engine.items.contains { $0.url.isFileURL })
        local.files = ["song1", "song2"]  // the playing track and the next one finish downloading
        host.downloadsChanged()
        await host.settle()
        XCTAssertEqual(engine.playing, host.current)
        XCTAssertFalse(engine.items[0].url.isFileURL, "no swap in the middle of the track being heard")
        XCTAssertTrue(engine.items[1].url.isFileURL, "the next start uses the file")
        XCTAssertEqual(engine.items.count, 3)
    }

    func testANewTokenLeavesAPlayingDownloadedTrackAlone() async throws {
        let (host, _) = offlineHost(["song1", "song2", "song3", "song4"])
        await playAlbum(host)
        let engine = try XCTUnwrap(engines.first)
        let before = journal.lines.filter { $0.hasPrefix("engine.replace") }.count
        var renewed = viewer
        renewed.accessToken = "t-new"
        host.bind(renewed, known: [viewer.key])
        await host.settle()
        XCTAssertEqual(journal.lines.filter { $0.hasPrefix("engine.replace") }.count, before, "the file plays on; reopening it would cut the sound")
        XCTAssertEqual(engine.playing, host.current)
        XCTAssertFalse(host.paused)
    }

    func testLocalListensAreWrittenDownNotSentLive() async {
        let (host, local) = offlineHost(["song1", "song2", "song3", "song4"])
        await playAlbum(host)
        engines[0].finishCurrent()
        await host.settle()
        XCTAssertTrue(gateway.reports.isEmpty, "\(gateway.reports.map(\.kind))")
        XCTAssertTrue(local.reports.contains { $0.media == "song1" && $0.report == "stopped" && $0.ticks == 180 * 10_000_000 }, "\(local.reports)")
    }

    func testAStreamThatDropsInAQueueWithDownloadsFollowsTheOfflineRule() async {
        let (host, _) = offlineHost(["song3"])
        await playAlbum(host)
        gateway.refuse = noNetwork
        engines[0].fail(.network)
        await host.settle()
        XCTAssertEqual(host.message, "Skipped Song 1: it isn't downloaded, and the server can't be reached.")
        XCTAssertEqual(host.currentCard?.id, "song2")
    }

    // MARK: Downloads' music lines

    private func track(_ id: String, phase: OfflinePhase = .ready, bytes: Int64 = 1_000_000) -> OfflineEntry {
        OfflineEntry(id: offlineEntryId(itemId: id, preset: .original), itemId: id, kind: .audio, container: "flac", preset: .original,
                     runtimeTicks: 180 * 10_000_000, labels: OfflineLabels(title: "Title \(id)", album: "Album", albumId: "a", artist: "Artist"),
                     resources: [OfflineResource(id: "media", role: .media, path: "media.flac", bytes: bytes, complete: phase == .ready)],
                     job: OfflineJob(phase: phase))
    }

    private var sharedManifest: OfflineManifest {
        OfflineManifest(entries: [track("t1"), track("t2"), track("t3")], collections: [
            OfflineCollection(id: "a", kind: .album, title: "Album", artist: "Artist", itemIds: ["t1", "t2"], createdSequence: 4),
            OfflineCollection(id: "p", kind: .playlist, title: "Mix", itemIds: ["t2", "t3"], createdSequence: 5),
        ])
    }

    func testDownloadsShowsOneLinePerAlbumOrPlaylistAndATrackUnderEach() {
        let groups = DownloadGroup.groups(sharedManifest)
        XCTAssertEqual(groups.map(\.id), ["collection:a", "collection:p"])
        XCTAssertEqual(groups[0].tracks.map(\.itemId), ["t1", "t2"])
        XCTAssertEqual(groups[1].tracks.map(\.itemId), ["t2", "t3"])
        XCTAssertTrue(groups.allSatisfy { $0.section == .ready })
    }

    func testPlayFromADownloadsLineUsesThatLinesAlbumOrPlaylist() throws {
        let t2 = track("t2")
        let fromMix = try XCTUnwrap(MusicDownloadWords.play(owner: "k", manifest: sharedManifest, entry: t2, collectionID: "p", art: { _ in nil }))
        XCTAssertEqual(fromMix.tracks.map(\.id), ["t2", "t3"]); XCTAssertEqual(fromMix.start, 0); XCTAssertEqual(fromMix.context, "Playlist:p")
        let fromAlbum = try XCTUnwrap(MusicDownloadWords.play(owner: "k", manifest: sharedManifest, entry: t2, collectionID: "a", art: { _ in nil }))
        XCTAssertEqual(fromAlbum.tracks.map(\.id), ["t1", "t2"]); XCTAssertEqual(fromAlbum.start, 1); XCTAssertEqual(fromAlbum.context, "MusicAlbum:a")
    }

    func testTheAlbumPageSaysDownloadedWithCountAndSize() {
        let status = MusicDownloadWords.status(sharedManifest, collectionID: "a")
        XCTAssertEqual(status?.text, "Downloaded · 2 tracks · \(DownloadWords.bytes(2_000_000))")
        XCTAssertEqual(status?.ready, true)
        XCTAssertNil(MusicDownloadWords.status(sharedManifest, collectionID: "artist"))
    }

    private func audio(_ id: String, container: String = "flac") -> MediaItem {
        var item = MediaItem(id: id, name: id, type: "Audio")
        item.mediaSources = [MediaSource(id: id, container: container, supportsDirectPlay: true)]
        return item
    }

    func testDownloadAsksWithTheCountAndSizeInOriginalQuality() {
        func facts(_ id: String, _ size: Int64) -> OfflineAPI.TrackFacts {
            OfflineAPI.TrackFacts(item: audio(id), size: size, canDownload: true)
        }
        let ask = MusicDownloadWords.question(allowed: true, tracks: [facts("x1", 1_000_000), facts("x2", 2_000_000)], manifest: OfflineManifest(), name: "Album")
        XCTAssertEqual(ask, .confirm("Download 2 tracks (\(DownloadWords.bytes(3_000_000))) in their original quality?", ["x1", "x2"]))
        XCTAssertEqual(MusicDownloadWords.question(allowed: false, tracks: [], manifest: nil, name: "Album"), .notice("Your account can't download from this server."))
    }

    func testAListenCountsAsPlayedFromItsLastSecond() {
        XCTAssertTrue(MusicDownloadWords.listenFinished(ticks: 1_795_000_000, runtimeTicks: 1_800_000_000))
        XCTAssertFalse(MusicDownloadWords.listenFinished(ticks: 1_000_000_000, runtimeTicks: 1_800_000_000))
        XCTAssertFalse(MusicDownloadWords.listenFinished(ticks: 5, runtimeTicks: nil))
    }

    func testATracksDownloadKeepsItsAlbumCoverAndItsOwnKindOfFile() {
        let facts = OfflineAPI.TrackFacts(item: audio("x1"), album: "Album", albumId: "alb", artist: "Artist", size: 10, canDownload: true)
        let entry = Downloads.trackEntry(facts, preset: .original, cellular: false, sequence: 1)
        XCTAssertEqual(entry.kind, .audio)
        XCTAssertEqual(entry.labels.albumId, "alb")
        XCTAssertTrue(entry.resources.contains { $0.role == .artwork })
        // AVFoundation goes by the name: a FLAC saved as .mp4 failed to play ("None of the tracks left in this queue could play").
        XCTAssertEqual(entry.resources.first { $0.role == .media }?.path, "media.flac")
        XCTAssertEqual(OriginalSupport.audioExtension("mp3"), "mp3")
        XCTAssertEqual(OriginalSupport.audioExtension("m4a,m4b,mp4,mov"), "m4a")
        XCTAssertNil(OriginalSupport.audioExtension("ogg"))
    }

    func testAnOriginalThisDeviceCantPlayIsLeftOutAndStreams() {
        let tracks = [OfflineAPI.TrackFacts(item: audio("x1"), size: 1_000_000, canDownload: true),
                      OfflineAPI.TrackFacts(item: audio("x2", container: "ogg"), size: 1_000_000, canDownload: true)]
        XCTAssertEqual(MusicDownloadWords.question(allowed: true, tracks: tracks, manifest: OfflineManifest(), name: "Album"),
                       .confirm("Download 1 track (\(DownloadWords.bytes(1_000_000))) in their original quality? 1 track can't be downloaded and will stream as usual.", ["x1"]))
    }
}
