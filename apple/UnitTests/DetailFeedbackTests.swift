// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import UIKit
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

private final class DetailFixtureProtocol: URLProtocol {
    static var itemType = "Movie"
    static var itemPlayed = false
    static var playedWrites: [String] = []
    static var failItems = false
    static var failEpisodes = false
    static var itemReads = 0
    static var episodeReads = 0
    static var homeReads = 0
    static var itemFailureStatus = 503
    static var held: [DetailFixtureProtocol] = []
    static var holdItems = false
    private static let playedHoldLock = NSLock()
    private static var holdNextPlayed = false
    private static var heldPlayed: DetailFixtureProtocol?
    static func holdNextPlayedResponse() {
        playedHoldLock.lock(); defer { playedHoldLock.unlock() }
        holdNextPlayed = true
    }
    static var hasHeldPlayedResponse: Bool {
        playedHoldLock.lock(); defer { playedHoldLock.unlock() }
        return heldPlayed != nil
    }
    static func releasePlayedResponse() {
        playedHoldLock.lock()
        let request = heldPlayed
        heldPlayed = nil; holdNextPlayed = false
        playedHoldLock.unlock()
        request?.respond()
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url!.path.hasSuffix("/Users/Me") { Self.homeReads += 1 }
        if request.url!.path.hasSuffix("/UserPlayedItems/title") {
            Self.playedHoldLock.lock()
            Self.playedWrites.append(request.httpMethod ?? "")
            if Self.holdNextPlayed {
                Self.holdNextPlayed = false; Self.heldPlayed = self
                Self.playedHoldLock.unlock()
                return
            }
            Self.playedHoldLock.unlock()
        }
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
            if Self.failItems { status = Self.itemFailureStatus }
            body = ["Id": "title", "Name": "Fixture title", "Type": Self.itemType, "SeriesId": "series", "UserData": ["Played": Self.itemPlayed, "IsFavorite": false]]
        } else if path.hasSuffix("/UserPlayedItems/title") {
            if request.httpMethod == "POST" { Self.itemPlayed = true }
            if request.httpMethod == "DELETE" { Self.itemPlayed = false }
            body = ["Played": Self.itemPlayed, "IsFavorite": false]
        } else if path.hasSuffix("/Items") {
            body = ["Items": [["Id": "title", "Name": "Fixture title", "Type": Self.itemType, "UserData": ["Played": Self.itemPlayed, "IsFavorite": false]]]]
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
    func testF5CompletionFailureWithdrawsOnlyItsOwnPlayedProjection() {
        let model = AppModel(client: client(), account: account)
        var raw = MediaItem(id: "title", name: "Title", type: "Movie")
        raw.userData.isFavorite = true
        let old = model.beginPlayedProjection(raw.id, rawPlayed: false)
        XCTAssertTrue(model.presented(raw).isWatched)
        let newest = model.beginPlayedProjection(raw.id, rawPlayed: false)
        model.finishPlayedProjection(raw.id, owner: old, success: false)
        XCTAssertTrue(model.presented(raw).isWatched, "Old failure cannot withdraw a newer owner")
        model.finishPlayedProjection(raw.id, owner: newest, success: false)
        XCTAssertFalse(model.presented(raw).isWatched)
        XCTAssertTrue(model.presented(raw).userData.isFavorite)
    }
    func testF5CompletionActiveOwnerSurvivesRawReadThenFailedStopWithdraws() {
        let model = AppModel(client: client(), account: account)
        let raw = MediaItem(id: "title", name: "Title", type: "Episode")
        let owner = model.beginPlayedProjection(raw.id, rawPlayed: false)
        model.settlePlayedProjection(raw, reporting: false)
        XCTAssertTrue(model.presented(raw).isWatched, "A raw read cannot release active completion")
        model.finishPlayedProjection(raw.id, owner: owner, success: true)
        model.settlePlayedProjection(raw, reporting: true)
        XCTAssertTrue(model.presented(raw).isWatched, "Pending reports retain the projection")
        model.withdrawPlayedProjection(raw.id, owner: owner)
        XCTAssertFalse(model.presented(raw).isWatched, "A failed Stop restores the raw played state")
        var latest = raw; latest.userData.played = true
        model.finishPlayedReport(raw.id, owner: owner, success: true)
        model.settlePlayedProjection(latest, reporting: false)
        XCTAssertTrue(model.presented(latest).isWatched)
        XCTAssertFalse(model.presented(raw).isWatched, "A settled projection does not override later raw reads")
    }
    func testRC29AcceptedUnplayedChoiceSupersedesCompletionAndDrainsAfterSaveBarrier() async throws {
        DetailFixtureProtocol.itemPlayed = false
        let model = AppModel(client: client(), account: account)
        model.playbackSaving = true
        let detail = DetailState()
        detail.refresh(id: "title", model: model)
        try await settled(detail)
        let raw = try XCTUnwrap(detail.item)
        let owner = model.beginPlayedProjection(raw.id, rawPlayed: raw.userData.played)
        XCTAssertTrue(model.completionMayApply(itemID: raw.id, owner: owner))

        DetailFixtureProtocol.itemPlayed = true
        detail.refresh(id: "title", model: model, restart: true)
        try await settled(detail)
        XCTAssertEqual(detail.item?.isWatched, true, "The server has accepted natural completion while its save response is pending")
        XCTAssertTrue(detail.offer(.watched(false), model: model))
        XCTAssertFalse(model.completionMayApply(itemID: raw.id, owner: owner), "The accepted Unplayed choice is newer than the natural completion report")
        model.finishPlayedProjection(raw.id, owner: owner, success: true)
        XCTAssertTrue(model.presented(raw).isWatched, "The stale completion projection returns to authoritative state while the accepted choice waits")

        DetailFixtureProtocol.holdNextPlayedResponse()
        defer { DetailFixtureProtocol.releasePlayedResponse() }
        model.playbackSaving = false
        detail.drain(model: model)
        var releasedPlayedResponse = false
        for _ in 0..<100 {
            if !releasedPlayedResponse, DetailFixtureProtocol.hasHeldPlayedResponse {
                XCTAssertEqual(DetailFixtureProtocol.playedWrites, ["DELETE"], "The queued user choice runs after the completion save barrier")
                XCTAssertTrue(detail.writing)
                XCTAssertEqual(detail.activeCommand, .watched(false))
                XCTAssertEqual(detail.item?.isWatched, true, "Transport arrival cannot publish Unplayed before its response completes")
                releasedPlayedResponse = true
                DetailFixtureProtocol.releasePlayedResponse()
            }
            // Request arrival precedes server mutation, response decoding and the
            // MainActor publication. Share the original budget across both phases.
            if releasedPlayedResponse, detail.item?.isWatched == false { break }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTAssertTrue(releasedPlayedResponse, "The Unplayed mutation must reach the controlled response boundary")
        XCTAssertEqual(DetailFixtureProtocol.playedWrites, ["DELETE"], "The queued user choice runs after the completion save barrier")
        XCTAssertNil(detail.writeError)
        XCTAssertEqual(detail.item?.isWatched, false)
        detail.leave()
    }
    func testF5RawReadBeforeStopCannotSettleSuccessfulPlayedOwner() {
        let model = AppModel(client: client(), account: account)
        let raw = MediaItem(id: "title", name: "Title", type: "Episode")
        let owner = model.beginPlayedProjection(raw.id, rawPlayed: false)
        model.finishPlayedProjection(raw.id, owner: owner, success: true)
        model.settlePlayedProjection(raw, reporting: false)
        XCTAssertTrue(model.presented(raw).isWatched, "An open viewing still owns its pending Stop")
        model.finishPlayedReport(raw.id, owner: owner, success: false)
        XCTAssertFalse(model.presented(raw).isWatched, "Failed Stop must find the retained owner")
    }
    func testF5SuccessfulStopRetryRestoresIntentUntilARealRawReadSettles() {
        let model = AppModel(client: client(), account: account)
        let raw = MediaItem(id: "title", name: "Title", type: "Episode")
        let token = model.beginPlayedProjection(raw.id, rawPlayed: false)
        model.finishPlayedProjection(raw.id, owner: token, success: true)
        model.finishPlayedReport(raw.id, owner: token, success: false)
        XCTAssertFalse(model.presented(raw).isWatched)
        model.finishPlayedReport(raw.id, owner: token, success: true)
        XCTAssertTrue(model.presented(raw).isWatched, "A failed post-retry read must not withdraw the acknowledged completion")
        model.settlePlayedProjection(raw, reporting: false)
        XCTAssertFalse(model.presented(raw).isWatched, "Only the successful raw read settles it")
    }
    func testF5FailureExpiresHeldRequestWithOneRetryCue() {
        let state = DetailState()
        XCTAssertTrue(state.enqueue(.play(true)))
        XCTAssertFalse(state.enqueue(.play(true)))
        state.publishReadFailure("The server refused the read.")
        XCTAssertTrue(state.pending.isEmpty)
        XCTAssertTrue(state.cue?.message.contains("The previous request expired and will not run") == true)
        XCTAssertTrue(state.cue?.message.contains("Retry is available") == true)
        let owner = state.cue?.id
        state.publishReadFailure("A transport refinement")
        XCTAssertEqual(state.cue?.id, owner)
    }
    func testF5SpeechQueuesAfterFocusAndCoalescesWithoutReplay() {
        var posts: [(UUID, NSAttributedString)] = []
        let delivery = DetailCueDelivery(enabled: { true }, post: { posts.append(($0.id, $1)) })
        let ready = AccessibilityCue(message: "Ready")
        delivery.submit(ready); delivery.submit(ready)
        let failure = AccessibilityCue(message: "Loading failed. Retry is available.")
        delivery.submit(AccessibilityCue(message: "An obsolete cue")); delivery.submit(failure)
        XCTAssertEqual(posts.count, 1)
        XCTAssertEqual(posts[0].1.attribute(.accessibilitySpeechQueueAnnouncement, at: 0, effectiveRange: nil) as? Bool, true)
        delivery.finished(message: "A foreign announcement")
        XCTAssertEqual(posts.count, 1)
        delivery.finished(message: "Ready")
        XCTAssertEqual(posts.map { $0.0 }, [ready.id, failure.id])
        delivery.submit(failure); XCTAssertEqual(posts.count, 2)
    }
    func testF5SpeechCancelsDepartureDisabledAndExpiredPendingCue() {
        var clock: TimeInterval = 0; var enabled = true; var posts = 0
        let delivery = DetailCueDelivery(enabled: { enabled }, now: { clock }, post: { _, _ in posts += 1 })
        delivery.submit(AccessibilityCue(message: "First")); delivery.submit(AccessibilityCue(message: "Expired"))
        clock = 31; delivery.finished(); XCTAssertEqual(posts, 1)
        delivery.submit(AccessibilityCue(message: "Second")); delivery.submit(AccessibilityCue(message: "Departed"))
        delivery.cancel(); delivery.finished(); XCTAssertEqual(posts, 2)
        delivery.resume(); enabled = false; delivery.submit(AccessibilityCue(message: "Off")); XCTAssertEqual(posts, 2)
    }
    override func tearDown() {
        DetailFixtureProtocol.releasePlayedResponse()
        DetailFixtureProtocol.itemType = "Movie"; DetailFixtureProtocol.itemPlayed = false; DetailFixtureProtocol.playedWrites = []; DetailFixtureProtocol.failItems = false; DetailFixtureProtocol.failEpisodes = false
        DetailFixtureProtocol.holdItems = false; DetailFixtureProtocol.itemReads = 0; DetailFixtureProtocol.episodeReads = 0; DetailFixtureProtocol.held = []; DetailFixtureProtocol.homeReads = 0; DetailFixtureProtocol.itemFailureStatus = 503
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
    func testRetryClearsPriorReasonSynchronouslyAndPublishesOnlyCurrentWork() {
        let state = DetailState()
        state.publishReadFailure("Original timeout")
        state.beginRead(deliberate: true)
        XCTAssertTrue(state.reading && state.retrying && state.recoveryVisible)
        XCTAssertFalse(state.fresh)
        XCTAssertNil(state.readFailure)
        XCTAssertNil(state.readError, "Retry cannot retain the predecessor reason in the selectable band")
        XCTAssertEqual(state.cue?.message, "Loading item. Please wait.")
        state.publishReadFailure("Server unavailable (503).")
        XCTAssertEqual(state.readFailure?.message, "Server unavailable (503).")
        XCTAssertEqual(state.readError, "Server unavailable (503).")
        state.beginRead(deliberate: false)
        XCTAssertNil(state.readError, "A superseding pending read owns no stale failure flag")
    }
    func testColdRequestKeepsUnknownToggleIntentAndAcceptanceEndsOnReaderDeparture() {
        let state = DetailState()
        state.beginRead(deliberate: false)
        XCTAssertEqual(state.focusRequest, "play", "Cold arrival publishes a guarded action before the held item read")
        XCTAssertNil(state.item)
        XCTAssertTrue(state.enqueue(.toggleWatched))
        state.acceptRequestCue(.toggleWatched)
        XCTAssertEqual(state.requestedLabel(.toggleWatched), "Watched already requested")
        let cue = state.cue?.id
        state.requestFocusChanged(nil) // Reader selects text; input focus need not change.
        XCTAssertEqual(state.requestedLabel(.toggleWatched), "Watched already requested")
        state.requestFocusChanged("watched")
        XCTAssertEqual(state.requestedLabel(.toggleWatched), "Watched already requested", "Returning cannot rearm acceptance")
        XCTAssertEqual(state.cue?.id, cue, "Rediscovery must not announce a second acceptance")
        XCTAssertFalse(state.enqueue(.watched(false)), "An unknown and a resolved intent share one action destination")
        state.pending = []
        state.reconcileRequestCue()
        XCTAssertNil(state.requestCueKey)
        XCTAssertTrue(state.enqueue(.toggleWatched))
        state.acceptRequestCue(.toggleWatched)
        XCTAssertNotEqual(state.cue?.id, cue, "A later accepted request must have its own delivery identity")
    }
    func testAppliedFullReadOwnsFreshStateBeforeCompletionCallback() async throws {
        let state = DetailState(); let client = client()
        var applied = 0
        state.refresh(id: "title", account: account, client: client, publication: UUID(), onAppliedRead: { item in
            applied += 1
            XCTAssertTrue(state.fresh)
            XCTAssertFalse(state.reading)
            XCTAssertEqual(state.item, item, "Completion must not publish before the destination owns the complete result")
        })
        try await settled(state)
        XCTAssertEqual(applied, 1)
        DetailFixtureProtocol.failItems = true
        state.refresh(id: "title", account: account, client: client, publication: UUID())
        try await settled(state)
        XCTAssertEqual(applied, 1, "Failed reads cannot supply completion confirmation")
    }
    func testContainerDependentFailureCannotPublishCompletionSnapshot() async throws {
        let state = DetailState(); let client = client()
        DetailFixtureProtocol.itemType = "Season"; DetailFixtureProtocol.failEpisodes = true
        var applied = false
        state.refresh(id: "title", account: account, client: client, publication: UUID(), onAppliedRead: { _ in applied = true })
        try await settled(state)
        XCTAssertFalse(applied, "A successful parent item alone is not a full Series/Season read")
        XCTAssertEqual(DetailFixtureProtocol.episodeReads, 1)
    }
    func testNewerFullDetailReadReleasesSavingWithoutJoiningOlderReadOrReloadingHome() async throws {
        let model = AppModel(client: client(), account: account)
        // Use one injected client for every production request path.
        let state = DetailState()
        await model.loadHomeIfNeeded()
        let homeReads = DetailFixtureProtocol.homeReads
        XCTAssertEqual(homeReads, 1)
        let tooEarly = model.detailReadStarted(itemID: "title")
        DetailFixtureProtocol.holdItems = true
        let completion = Task { await model.refreshAfterPlayback(account: account, itemID: "title") }
        for _ in 0..<100 { if !DetailFixtureProtocol.held.isEmpty { break }; try await Task.sleep(for: .milliseconds(10)) }
        let original = try XCTUnwrap(DetailFixtureProtocol.held.first)
        XCTAssertTrue(model.playbackSaving)
        let movie = MediaItem(id: "title", name: "Fixture", type: "Movie")
        model.detailReadApplied(movie, read: tooEarly)
        XCTAssertTrue(model.playbackSaving, "A read begun before the completion check cannot release it")
        let unrelated = model.detailReadStarted(itemID: "other")
        model.detailReadApplied(MediaItem(id: "other", name: "Other", type: "Movie"), read: unrelated)
        XCTAssertTrue(model.playbackSaving)
        DetailFixtureProtocol.holdItems = false; DetailFixtureProtocol.failItems = true
        state.refresh(id: "title", model: model)
        try await settled(state)
        XCTAssertTrue(model.playbackSaving, "A failed newer GET cannot acknowledge completion")
        DetailFixtureProtocol.failItems = false
        state.refresh(id: "title", model: model, deliberate: true)
        try await settled(state)
        XCTAssertTrue(state.fresh)
        XCTAssertFalse(model.playbackSaving, "The successful full same-item read must release the obsolete check")
        await completion.value
        let reads = DetailFixtureProtocol.itemReads
        state.refreshIfNeeded(id: "title", model: model)
        await model.loadHomeIfNeeded()
        await model.loadHomeIfNeeded()
        XCTAssertEqual(DetailFixtureProtocol.itemReads, reads, "The applied result must consume completion publication without a third detail GET")
        XCTAssertEqual(DetailFixtureProtocol.homeReads, homeReads + 1, "One real data revision refreshes Home once; pending/saving bookkeeping does not")
        // A canceled older transport has no authority to expire this viewer.
        DetailFixtureProtocol.failItems = true; DetailFixtureProtocol.itemFailureStatus = 401
        original.respond()
        try await Task.sleep(for: .milliseconds(50))
        XCTAssertEqual(model.account?.localKey, account.localKey)
        XCTAssertNil(model.playbackSaveError)
    }
    func testFullReadFromAnOldAccountCannotReleaseCurrentCompletion() async throws {
        let model = AppModel(client: client(), account: account)
        let oldRead = model.detailReadStarted(itemID: "title")
        let other = Account(server: account.server, userId: "other", username: "Other", accessToken: "other-token", deviceID: "other-device")
        model.account = other
        DetailFixtureProtocol.holdItems = true
        let completion = Task { await model.refreshAfterPlayback(account: other, itemID: "title") }
        for _ in 0..<100 { if !DetailFixtureProtocol.held.isEmpty { break }; try await Task.sleep(for: .milliseconds(10)) }
        model.detailReadApplied(MediaItem(id: "title", name: "Fixture", type: "Movie"), read: oldRead)
        XCTAssertTrue(model.playbackSaving)
        DetailFixtureProtocol.holdItems = false
        for request in DetailFixtureProtocol.held { request.respond() }
        await completion.value
        XCTAssertFalse(model.playbackSaving)
        XCTAssertEqual(model.account?.localKey, other.localKey)
    }
    func testHomeInitialReadIsSharedAndBookkeepingDoesNotInvalidateData() async {
        let model = AppModel(client: client(), account: account)
        async let first: Void = model.loadHomeIfNeeded()
        async let second: Void = model.loadHomeIfNeeded()
        _ = await (first, second)
        XCTAssertEqual(DetailFixtureProtocol.homeReads, 1, "Initial owner and first appearance share a request")
        model.playbackSaving = true; model.refreshID = UUID()
        await model.loadHomeIfNeeded()
        model.playbackSaving = false; model.refreshID = UUID()
        await model.loadHomeIfNeeded()
        XCTAssertEqual(DetailFixtureProtocol.homeReads, 1)
        model.catalogChanged()
        await model.loadHomeIfNeeded()
        await model.loadHomeIfNeeded()
        XCTAssertEqual(DetailFixtureProtocol.homeReads, 2, "A real revision still reloads exactly once")
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
