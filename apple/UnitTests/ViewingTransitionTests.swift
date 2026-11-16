// SPDX-License-Identifier: MPL-2.0
import XCTest
import AVFoundation
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

private final class ViewingProtocol: URLProtocol {
    static var requests: [URLRequest] = []
    static var failPlayed = false
    static var holdPlayed = false
    static var heldPlayed: [ViewingProtocol] = []
    static var firstPlayed = false
    static var nextEpisodeName = "Next"
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.append(request)
        let path = request.url!.path
        if path.hasSuffix("UserPlayedItems/first"), Self.holdPlayed { Self.heldPlayed.append(self); return }
        var status = 200
        var body: Any = [:]
        if path.hasSuffix("PlaybackInfo") {
            let source = path.contains("/first/") ? "first-source" : "next-source"
            body = ["MediaSources": [["Id": source, "SupportsDirectPlay": true, "MediaStreams": [["Index":0,"Type":"Video","Codec":"h264"],["Index":1,"Type":"Audio","Codec":"aac"]]]], "PlaySessionId": source + "-session"]
        } else if path.hasSuffix("/Items/first") || path.hasSuffix("/Items/next") {
            let isFirst = path.hasSuffix("first")
            body = ["Id":isFirst ? "first" : "next", "Name":isFirst ? "First" : Self.nextEpisodeName, "Type":"Episode", "Chapters":[], "UserData":["Played":isFirst && Self.firstPlayed,"PlaybackPositionTicks":0,"PlayCount":isFirst && Self.firstPlayed ? 1 : 0,"IsFavorite":false]]
        } else if path.hasSuffix("/Items") {
            body = ["Items":[["Id":"first","Name":"First","Type":"Episode","UserData":["Played":Self.firstPlayed,"PlaybackPositionTicks":0,"PlayCount":Self.firstPlayed ? 1 : 0,"IsFavorite":false]]]]
        } else if path.contains("MediaSegments") { body = ["Items":[]] }
        else if path.hasSuffix("/Episodes") {
            body = ["Items":[["Id":"first","Name":"First","Type":"Episode","SeriesId":"series"],["Id":"next","Name":Self.nextEpisodeName,"Type":"Episode","SeriesId":"series"]]]
        } else if path.hasSuffix("UserPlayedItems/first") {
            if Self.failPlayed { status = 503 }
            if request.httpMethod == "POST" { Self.firstPlayed = true }
            if request.httpMethod == "DELETE" { Self.firstPlayed = false }
            body = ["Played":Self.firstPlayed,"PlaybackPositionTicks":0,"PlayCount":Self.firstPlayed ? 1 : 0,"IsFavorite":false]
        }
        let data = try! JSONSerialization.data(withJSONObject: body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
    func releasePlayedResponse() {
        Self.holdPlayed = false
        let data = try! JSONSerialization.data(withJSONObject: ["Played":Self.firstPlayed,"PlaybackPositionTicks":0,"PlayCount":Self.firstPlayed ? 1 : 0,"IsFavorite":false])
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type":"application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
}

/// Keep decoder/network activity outside this HTTP lifecycle test. Native decoding is
/// covered by simulator acceptance; it must not race a synthetic physical-EOS event.
private final class InertViewingPlayer: AVPlayer {
    override func replaceCurrentItem(with item: AVPlayerItem?) { }
}

@MainActor final class ViewingTransitionTests: XCTestCase {
    private var session: PlaybackSession!
    override func tearDown() { ViewingProtocol.requests = []; ViewingProtocol.failPlayed = false; ViewingProtocol.holdPlayed = false; ViewingProtocol.heldPlayed = []; ViewingProtocol.firstPlayed = false; ViewingProtocol.nextEpisodeName = "Next" }
    private func setupViewing() async throws {
        ViewingProtocol.requests = []
        ViewingProtocol.firstPlayed = false
        ViewingProtocol.holdPlayed = false
        ViewingProtocol.heldPlayed = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ViewingProtocol.self]
        let client = JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: configuration))
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
        var item = MediaItem(id: "first", name: "First", type: "Episode")
        item.seriesId = "series"
        item.mediaSources = [MediaSource(id: "first-source", supportsDirectPlay: true)]
        session = PlaybackSession(client: client, account: account, item: item, player: InertViewingPlayer())
        try await session.prepare(startTicks: 0, audio: nil, subtitle: -1)
        for _ in 0..<100 {
            if session.nextItem != nil { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The real next-episode HTTP lookup did not publish its result")
    }
    private func waitForDetail(_ detail: DetailState) async throws {
        for _ in 0..<100 {
            if detail.fresh && !detail.reading { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("The fixture detail read did not publish a fresh item: \(detail.readError ?? detail.readFailure?.message ?? "no transport error")")
    }
    /// The next episode has no embedded sources in the list DTO. Its negotiation must discover
    /// its own source rather than binding the prior episode's source ID or caption metadata.
    func testContinuationNegotiatesTheNextEpisodesOwnSource() async throws {
        #if os(tvOS)
        ViewingProtocol.nextEpisodeName = "Another Chance (1): A deliberately long episode title that must stay in transport metadata"
        #endif
        try await setupViewing()
        #if os(tvOS)
        let next = try XCTUnwrap(session.nextItem)
        XCTAssertGreaterThan(next.name.count, 16, "The real next-episode lookup must supply a long title for both native actions")
        let coordinator = NativePlayer.Coordinator(session: session, close: {})
        coordinator.updateContinuation(next, countdown: 10)
        let contextNext = try XCTUnwrap(coordinator.continuationContextActions.first { $0.identifier.rawValue == "playNext" })
        let contextCancel = try XCTUnwrap(coordinator.continuationContextActions.first { $0.identifier.rawValue == "cancelNext" })
        let transportNext = try XCTUnwrap(coordinator.continuationTransportActions.first { $0.identifier.rawValue == "playNextTransport" })
        let transportCancel = try XCTUnwrap(coordinator.continuationTransportActions.first { $0.identifier.rawValue == "cancelNextTransport" })
        for countdown in [10, 8] {
            coordinator.updateContinuation(next, countdown: countdown)
            XCTAssertEqual(coordinator.continuationContextActions.count, 2)
            XCTAssertEqual(coordinator.continuationTransportActions.count, 2)
            XCTAssertTrue(contextNext === coordinator.continuationContextActions.first { $0.identifier.rawValue == "playNext" }, "Countdown updates must retain the focused Next action")
            XCTAssertTrue(contextCancel === coordinator.continuationContextActions.first { $0.identifier.rawValue == "cancelNext" }, "Countdown updates must retain the Cancel action")
            XCTAssertTrue(transportNext === coordinator.continuationTransportActions.first { $0.identifier.rawValue == "playNextTransport" })
            XCTAssertTrue(transportCancel === coordinator.continuationTransportActions.first { $0.identifier.rawValue == "cancelNextTransport" })
            XCTAssertGreaterThan(contextNext.title.count, next.name.count, "The contextual action must include the episode identity and action")
            XCTAssertEqual(contextNext.title, "Next (\(countdown)): \(next.name)", "The contextual action must expose the episode and current countdown")
            XCTAssertTrue(contextNext.title.contains(next.name))
            XCTAssertEqual(contextCancel.title, "Cancel", "Cancel must remain a short stable label across countdown ticks")
            XCTAssertEqual(transportNext.title, "Play next: \(next.name) (\(countdown))", "Transport metadata must retain the complete next-episode title")
            XCTAssertEqual(transportCancel.title, "Cancel", "Paused transport must retain a visible text action")
        }
        var following = next
        following.id = "following"
        coordinator.updateContinuation(following, countdown: 10)
        XCTAssertEqual(coordinator.continuationContextActions.count, 2)
        XCTAssertEqual(coordinator.continuationTransportActions.count, 2)
        let followingContextNext = try XCTUnwrap(coordinator.continuationContextActions.first { $0.identifier.rawValue == "playNext" })
        let followingContextCancel = try XCTUnwrap(coordinator.continuationContextActions.first { $0.identifier.rawValue == "cancelNext" })
        let followingTransportNext = try XCTUnwrap(coordinator.continuationTransportActions.first { $0.identifier.rawValue == "playNextTransport" })
        let followingTransportCancel = try XCTUnwrap(coordinator.continuationTransportActions.first { $0.identifier.rawValue == "cancelNextTransport" })
        XCTAssertFalse(contextNext === followingContextNext, "A different episode must receive a newly bound Next action")
        XCTAssertFalse(contextCancel === followingContextCancel)
        XCTAssertFalse(transportNext === followingTransportNext)
        XCTAssertFalse(transportCancel === followingTransportCancel)
        #endif
        await session.advance()
        XCTAssertEqual(session.item.id, "next")
        XCTAssertEqual(session.decision?.source.id, "next-source")
        XCTAssertNil(session.error)
        let requests = ViewingProtocol.requests.filter { $0.url!.path.contains("/next/PlaybackInfo") }
        XCTAssertEqual(requests.count, 1)
        let body = try requestBody(XCTUnwrap(requests.first))
        XCTAssertNil(body["MediaSourceId"], "A previous source ID would bind negotiation to the wrong item")
        await session.stop()
    }
    func testRC30PlaybackSpeedPersistsAcrossReplacementAndResetsForNextViewing() async throws {
        try await setupViewing()
        XCTAssertTrue(session.selectPlaybackSpeed(1.5))
        XCTAssertEqual(session.playbackRate, 1.5)
        XCTAssertEqual(session.player.defaultRate, 1.5)

        await session.changeTracks(audio: 2, subtitle: -1)
        XCTAssertEqual(session.playbackRate, 1.5, "Track replacement remains within the same viewing")
        XCTAssertEqual(session.player.defaultRate, 1.5)

        await session.advance()
        XCTAssertEqual(session.item.id, "next")
        XCTAssertEqual(session.playbackRate, 1, "A new episode starts at normal speed")
        XCTAssertEqual(session.player.defaultRate, 1)
        await session.stop()
    }
    func testRC30EncodedOutputRejectsNonNormalSpeedAndReturnsToOne() async throws {
        try await setupViewing()
        XCTAssertTrue(session.selectPlaybackSpeed(1.75))
        XCTAssertFalse(session.selectPlaybackSpeed(1.5, encodedOutput: true))
        XCTAssertEqual(session.playbackRate, 1)
        XCTAssertEqual(session.player.defaultRate, 1)
        await session.stop()
    }
    func testRC29AcceptedUnplayedChoiceAfterNaturalEndWinsAfterCompletionSave() async throws {
        try await setupViewing()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ViewingProtocol.self]
        let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
        let model = AppModel(client: JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: configuration)), account: account)
        model.playbackSaving = true
        let detail = DetailState()
        detail.refresh(id: "first", model: model)
        try await waitForDetail(detail)
        XCTAssertEqual(detail.item?.isWatched, false)

        var completionOwner: UUID?
        session.onCompletionChoiceRevision = { model.playedChoiceRevision(for: $0) }
        session.onCompletionBegan = { itemID, revision in
            let token = model.beginPlayedProjection(itemID, rawPlayed: false, choiceRevision: revision)
            completionOwner = token
            return token
        }
        session.onCompletionMayApply = { itemID, owner in model.completionMayApply(itemID: itemID, owner: owner) }
        session.onCompletionFinished = { itemID, owner, success in model.finishPlayedProjection(itemID, owner: owner, success: success) }
        session.onCompletionReportFinished = { itemID, owner, success in model.finishPlayedReport(itemID, owner: owner, success: success) }

        ViewingProtocol.holdPlayed = true
        let ending = Task { await session.physicalEnd(readerRequiresManual: true) }
        for _ in 0..<100 { if !ViewingProtocol.heldPlayed.isEmpty { break }; try await Task.sleep(for: .milliseconds(10)) }
        let held = try XCTUnwrap(ViewingProtocol.heldPlayed.first)
        XCTAssertNotNil(completionOwner, "Physical end captures the report owner before sending Played=true")

        // The server has accepted the automatic completion, but its HTTP response is
        // still in flight. The detail action is explicitly Unplayed and waits behind
        // AppModel's playback-save barrier.
        ViewingProtocol.firstPlayed = true
        detail.refresh(id: "first", model: model, restart: true)
        try await waitForDetail(detail)
        XCTAssertEqual(detail.item?.isWatched, true)
        XCTAssertTrue(detail.offer(.watched(false), model: model))
        let owner = try XCTUnwrap(completionOwner)
        XCTAssertFalse(model.completionMayApply(itemID: "first", owner: owner))

        held.releasePlayedResponse()
        await ending.value
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("UserPlayedItems/first") && $0.httpMethod == "POST" }.count, 1)
        XCTAssertFalse(model.completionMayApply(itemID: "first", owner: owner))

        model.playbackSaving = false
        detail.drain(model: model)
        for _ in 0..<100 {
            let deleteArrived = ViewingProtocol.requests.contains { $0.url!.path.hasSuffix("UserPlayedItems/first") && $0.httpMethod == "DELETE" }
            if deleteArrived && !ViewingProtocol.firstPlayed && detail.item?.isWatched == false { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(ViewingProtocol.requests.contains { $0.url!.path.hasSuffix("UserPlayedItems/first") && $0.httpMethod == "DELETE" }, "The accepted Unplayed choice must follow the already-sent completion report")
        XCTAssertFalse(ViewingProtocol.firstPlayed)
        XCTAssertEqual(detail.item?.isWatched, false)
        detail.leave()
        await session.stop()
    }
    /// Screen-reader users choose when to continue, while a real physical end
    /// still completes the finished episode. Manual Next must not complete twice.
    func testReaderPhysicalEndCompletesOnceBeforeManualContinuation() async throws {
        try await setupViewing()
        await session.physicalEnd(readerRequiresManual: true)
        XCTAssertEqual(session.item.id, "first")
        XCTAssertTrue(session.nextCardVisible)
        XCTAssertNil(session.nextCountdown, "A manual card has no running countdown")
        XCTAssertTrue(session.isPaused)
        XCTAssertNil(session.reportError)
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("UserPlayedItems/first") && $0.httpMethod == "POST" }.count, 1)
        XCTAssertFalse(ViewingProtocol.requests.contains { $0.url!.path.contains("/next/PlaybackInfo") })
        await session.advance()
        XCTAssertEqual(session.item.id, "next")
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("UserPlayedItems/first") && $0.httpMethod == "POST" }.count, 1)
        await session.stop()
    }
    /// A failed watched-state write cannot be erased by a successful Stop or silently open the
    /// next episode. The final-detail Retry repeats the same desired Played=true intent.
    func testFailedCompletionBlocksNextAndRemainsRetryableAfterStop() async throws {
        try await setupViewing()
        ViewingProtocol.failPlayed = true
        await session.advance()
        XCTAssertEqual(session.item.id, "first")
        XCTAssertNotNil(session.reportError)
        XCTAssertNotNil(session.error)
        XCTAssertFalse(ViewingProtocol.requests.contains { $0.url!.path.contains("/next/PlaybackInfo") })
        await session.stop()
        XCTAssertNotNil(session.reportError)
        ViewingProtocol.failPlayed = false
        await session.retrySaving()
        XCTAssertNil(session.reportError)
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("UserPlayedItems/first") }.count, 2)
    }
    /// A slow asset can become ready after the twelve-second waiting hint. The hint
    /// must clear, and initial/new readiness notifications must still emit one Start.
    func testDelayedReadinessRestoresMethodAndStartsOnlyOnce() async throws {
        try await setupViewing()
        XCTAssertTrue(session.showStartupWaitHint(version: 0))
        XCTAssertTrue(session.status.contains("Still waiting"))
        session.ready(version: -1) // A stale decoder must not consume the startup guard.
        session.ready(version: 0)
        session.ready(version: 0)
        for _ in 0..<100 {
            if ViewingProtocol.requests.contains(where: { $0.url!.path.hasSuffix("Sessions/Playing") }) { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertEqual(try XCTUnwrap(session.decision).method, .directPlay,
                       "Readiness and late deadlines must preserve the negotiated Direct play decision")
        XCTAssertEqual(session.status, "", "Ready compatible playback clears the startup hint without delivery-method copy")
        XCTAssertFalse(session.waitingForVideo)
        XCTAssertFalse(session.showStartupWaitHint(version: 0), "A late deadline must not overwrite ready playback")
        XCTAssertEqual(try XCTUnwrap(session.decision).method, .directPlay,
                       "Readiness and late deadlines must preserve the negotiated Direct play decision")
        XCTAssertEqual(session.status, "", "Ready compatible playback clears the startup hint without delivery-method copy")
        XCTAssertEqual(ViewingProtocol.requests.filter { $0.url!.path.hasSuffix("Sessions/Playing") }.count, 1)
        await session.stop()
    }
    func testReadingChunksRetainLicenseFormattingAndJSONIndentation() {
        let text = "License\n=======\n\n1. Definitions\n--------------\n\n1.1. Café and 👩🏽‍💻.\n\n" + String(repeating: "A clause with words and  spaces. ", count: 80) + "\n\n{\n  \"Profile\": [\n    1, 2\n  ]\n}\n"
        let blocks = ReadingChunks.blocks(text, maxCharacters: 80)
        XCTAssertGreaterThan(blocks.count, 2)
        XCTAssertEqual(blocks.joined(), text, "Reading pagination must not lose paragraphs, whitespace, Unicode or JSON indentation")
        XCTAssertTrue(blocks.contains { $0.contains("License\n=======\n\n") })
        XCTAssertTrue(ReadingChunks.blocks("").isEmpty)
        let longWord = String(repeating: "x", count: 900)
        XCTAssertEqual(ReadingChunks.blocks(longWord), [longWord], "Do not split an unbroken identifier or loop forever")
    }
    private func requestBody(_ request: URLRequest) throws -> [String:Any] {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable { let count = stream.read(&buffer, maxLength: buffer.count); if count <= 0 { break }; data.append(buffer, count: count) }
        }
        return try JSONSerialization.jsonObject(with: data) as! [String:Any]
    }
}
