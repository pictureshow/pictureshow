// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
@testable import Pictureshow_iOS

private final class DetailFixtureProtocol: URLProtocol {
    static var itemType = "Movie"
    static var failItems = false
    static var failEpisodes = false
    static var itemReads = 0
    static var episodeReads = 0
    static var held: [DetailFixtureProtocol] = []
    static var holdItems = false
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url!.path.hasSuffix("/Items/title") {
            Self.itemReads += 1
            if Self.holdItems { Self.held.append(self); return }
        }
        respond()
    }
    func respond() {
        let path = request.url!.path
        var status = 200
        var body: [String: Any] = ["Items": []]
        if path.hasSuffix("/Items/title") {
            if Self.failItems { status = 503 }
            body = ["Id": "title", "Name": "Fixture title", "Type": Self.itemType, "SeriesId": "series", "UserData": ["Played": false, "IsFavorite": false]]
        } else if path.hasSuffix("/Items") {
            body = ["Items": [["Id": "title", "Name": "Fixture title", "Type": Self.itemType, "UserData": ["Played": false, "IsFavorite": false]]]]
        } else if path.hasSuffix("/Episodes") {
            Self.episodeReads += 1
            if Self.failEpisodes { status = 503 }
        }
        let data = try! JSONSerialization.data(withJSONObject: body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class DetailFeedbackTests: XCTestCase {
    override func tearDown() {
        DetailFixtureProtocol.itemType = "Movie"; DetailFixtureProtocol.failItems = false; DetailFixtureProtocol.failEpisodes = false
        DetailFixtureProtocol.holdItems = false; DetailFixtureProtocol.itemReads = 0; DetailFixtureProtocol.episodeReads = 0; DetailFixtureProtocol.held = []
    }
    private let account = Account(server: Server(url: URL(string: "https://media.example.test/jellyfin")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
    private func client() -> JellyfinClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [DetailFixtureProtocol.self]
        return JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: configuration))
    }
    private func settled(_ state: DetailState) async throws {
        for _ in 0..<100 { if !state.reading { return }; try await Task.sleep(for: .milliseconds(10)) }
        XCTFail("The fixture detail read did not finish")
    }
    func testFailedRetryKeepsRealRecoveryUntilFreshTargetHasBeenPublished() async throws {
        let state = DetailState(); let client = client()
        DetailFixtureProtocol.failItems = true
        state.refresh(id: "title", account: account, client: client, publication: UUID())
        try await settled(state)
        XCTAssertTrue(state.recoveryVisible); XCTAssertEqual(state.focusRequest, "detailRetry")
        let firstCue = state.cue?.id
        let reason = try XCTUnwrap(state.readFailure?.message)
        // Transport refinement must retain the same truthful visible/spoken episode.
        state.publishReadFailure("A later transport detail")
        XCTAssertEqual(state.readFailure?.message, reason); XCTAssertEqual(state.cue?.id, firstCue)
        state.refresh(id: "title", account: account, client: client, publication: UUID(), deliberate: true)
        XCTAssertTrue(state.recoveryVisible); XCTAssertTrue(state.retrying)
        try await settled(state)
        XCTAssertNotEqual(state.cue?.id, firstCue, "Each deliberate failed Retry publishes even an identical failure")
        DetailFixtureProtocol.failItems = false
        state.refresh(id: "title", account: account, client: client, publication: UUID(), deliberate: true)
        try await settled(state)
        XCTAssertTrue(state.fresh); XCTAssertNil(state.readError)
        XCTAssertTrue(state.recoveryVisible, "Do not remove Retry before the fresh target can take selection")
        XCTAssertEqual(state.focusRequest, "play")
        state.finishRecoveryHandoff()
        XCTAssertFalse(state.recoveryVisible); XCTAssertNil(state.readFailure)
    }
    func testHeldRetryRejectsRepeatedSelectAndBackIgnoresLatePublication() async throws {
        let state = DetailState(); let client = client()
        state.publishReadFailure("Read failed")
        DetailFixtureProtocol.holdItems = true
        state.refresh(id: "title", account: account, client: client, publication: UUID(), deliberate: true)
        state.refresh(id: "title", account: account, client: client, publication: UUID(), deliberate: true)
        for _ in 0..<100 { if !DetailFixtureProtocol.held.isEmpty { break }; try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(DetailFixtureProtocol.itemReads, 1)
        XCTAssertTrue(state.enqueue(.favorite(true)))
        state.leave()
        for request in DetailFixtureProtocol.held { request.respond() }
        await Task.yield()
        XCTAssertNil(state.item); XCTAssertFalse(state.fresh); XCTAssertFalse(state.reading); XCTAssertTrue(state.pending.isEmpty)
        XCTAssertFalse(state.enqueue(.play(true)), "A departed entry cannot capture another command")
    }
    func testDependentEpisodeFailureDoesNotClaimEmptyAndRetryRecoversToFavorite() async throws {
        let state = DetailState(); let client = client()
        DetailFixtureProtocol.itemType = "Season"; DetailFixtureProtocol.failEpisodes = true
        state.refresh(id: "title", account: account, client: client, publication: UUID())
        try await settled(state)
        XCTAssertFalse(state.fresh); XCTAssertFalse(state.empty); XCTAssertNotNil(state.readFailure)
        XCTAssertEqual(DetailFixtureProtocol.episodeReads, 1, "The failed dependent request must actually have been reached")
        DetailFixtureProtocol.failEpisodes = false
        state.refresh(id: "title", account: account, client: client, publication: UUID(), deliberate: true)
        XCTAssertFalse(state.empty)
        try await settled(state)
        XCTAssertTrue(state.empty); XCTAssertNil(state.target); XCTAssertEqual(state.focusRequest, "favorite")
    }
    func testRequestedDesiredValueSurvivesPublicationAndPlaybackPeersDeduplicate() {
        let state = DetailState()
        XCTAssertTrue(state.enqueue(.watched(true)))
        XCTAssertFalse(state.enqueue(.watched(true)))
        XCTAssertTrue(state.enqueue(.favorite(true)))
        XCTAssertTrue(state.enqueue(.play(true)))
        XCTAssertFalse(state.enqueue(.play(false)), "A peer button cannot queue a second playback")
        var updated = MediaItem(id: "title", name: "Fixture", type: "Movie")
        updated.userData.played = true
        state.publishRead(DetailPublication(item: updated, seasons: [], episodes: [], season: nil, target: updated), publication: UUID())
        XCTAssertEqual(state.pending, [.watched(true), .favorite(true), .play(true)], "Fresh natural-end data must not invert captured desired values")
    }
    func testExpiredAcceptedIntentCannotReplayOrPoisonANewRequest() {
        let state = DetailState(); let accepted = Date(timeIntervalSince1970: 100)
        XCTAssertTrue(state.enqueue(.watched(true), now: accepted))
        XCTAssertFalse(state.expirePending(now: accepted.addingTimeInterval(30)))
        XCTAssertTrue(state.expirePending(now: accepted.addingTimeInterval(30.01)))
        XCTAssertTrue(state.pending.isEmpty); XCTAssertEqual(state.notice, "Waiting action expired. Choose it again.")
        XCTAssertTrue(state.enqueue(.watched(false), now: accepted.addingTimeInterval(31)))
        XCTAssertFalse(state.expirePending(now: accepted.addingTimeInterval(32)))
        XCTAssertEqual(state.pending, [.watched(false)])
    }
    func testWriteFailureStaysDistinctFromSuccessfulReadAndFreezesUntilNextAttempt() {
        let state = DetailState()
        state.beginWriteAttempt(); state.publishWriteFailure("Original reason")
        let cue = state.cue?.id
        state.publishWriteFailure("Transport refinement")
        XCTAssertEqual(state.writeFailure?.message, "Original reason"); XCTAssertEqual(state.cue?.id, cue)
        let movie = MediaItem(id: "title", name: "Fixture", type: "Movie")
        state.publishRead(DetailPublication(item: movie, seasons: [], episodes: [], season: nil, target: movie), publication: UUID())
        XCTAssertEqual(state.writeFailure?.message, "Original reason")
        state.beginWriteAttempt(); state.publishWriteFailure("Original reason")
        XCTAssertNotEqual(state.cue?.id, cue)
        XCTAssertEqual(state.writeFailure?.key, "write:2")
    }
}
