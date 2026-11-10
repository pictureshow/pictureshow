// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

private final class CatalogFixtureProtocol: URLProtocol {
    struct Reply { var status = 200; var body: [String: Any]; var hold = false }
    static var handler: (URLRequest) -> Reply = { _ in Reply(body: ["Items": []]) }
    private static let lock = NSLock()
    private static var waiting: [(CatalogFixtureProtocol, Reply)] = []
    static var waitingCount: Int { lock.lock(); defer { lock.unlock() }; return waiting.count }
    static func release() {
        lock.lock(); let requests = waiting; waiting = []; lock.unlock()
        requests.forEach { $0.0.respond($0.1) }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let reply = Self.handler(request)
        if reply.hold { Self.lock.lock(); Self.waiting.append((self, reply)); Self.lock.unlock() }
        else { respond(reply) }
    }
    private func respond(_ reply: Reply) {
        let data = try! JSONSerialization.data(withJSONObject: reply.body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class CatalogIntegrationTests: XCTestCase {
    private let account = Account(server: Server(url: URL(string: "https://media.example.test")!), userId: "viewer", username: "Viewer", accessToken: "fixture-token", deviceID: "fixture-device")
    private func model() -> AppModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [CatalogFixtureProtocol.self]
        return AppModel(client: JellyfinClient(deviceID: "fixture-device", session: URLSession(configuration: configuration)), account: account)
    }
    private static func page(_ ids: [String], total: Int) -> [String: Any] {
        ["Items": ids.map { ["Id": $0, "Name": $0, "Type": "Movie"] }, "TotalRecordCount": total]
    }
    override func tearDown() { CatalogFixtureProtocol.release() }

    func testSortGenerationCannotReceiveOldPageAndRetainsRequestedDirection() async throws {
        var sortRequests: [(String?, String?)] = []
        CatalogFixtureProtocol.handler = { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
            guard request.url?.path == "/Items", query.contains(where: { $0.name.lowercased() == "parentid" && $0.value == "library" }) else {
                return .init(body: ["Items": []])
            }
            sortRequests.append((query.first(where: { $0.name.lowercased() == "sortby" })?.value, query.first(where: { $0.name.lowercased() == "sortorder" })?.value))
            let sort = query.first(where: { $0.name.lowercased() == "sortby" })?.value
            if sort == "SortName" { return .init(body: Self.page(["old"], total: 1), hold: true) }
            XCTAssertEqual(sort, "DateCreated")
            XCTAssertEqual(query.first(where: { $0.name.lowercased() == "sortorder" })?.value, "Descending")
            return .init(body: Self.page(["new"], total: 1))
        }
        let model = model(); let page = PagedCatalog()
        let old = Task { await page.load(model: model, parent: "library", reset: true) }
        for _ in 0..<100 { if CatalogFixtureProtocol.waitingCount > 0 { break }; try await Task.sleep(for: .milliseconds(10)) }
        XCTAssertEqual(CatalogFixtureProtocol.waitingCount, 1)
        await page.load(model: model, parent: "library", sort: "DateCreated", descending: true, reset: true)
        XCTAssertEqual(page.items.map(\.id), ["new"])
        CatalogFixtureProtocol.release(); await old.value
        XCTAssertEqual(page.items.map(\.id), ["new"], "The superseded sort response must never contaminate this generation")
        XCTAssertEqual(page.next, 1); XCTAssertFalse(page.loading)
        XCTAssertEqual(sortRequests.map { $0.0 }, ["SortName", "DateCreated"])
        XCTAssertEqual(sortRequests.map { $0.1 }, ["Ascending", "Descending"])
    }
    func testPagingRetainsCardsOnFailureAndRetriesTheSameOffsetWithoutDuplicateIDs() async {
        var failSecondPage = true
        var offsets: [Int] = []
        CatalogFixtureProtocol.handler = { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
            guard request.url?.path == "/Items", query.contains(where: { $0.name.lowercased() == "parentid" && $0.value == "library" }) else {
                return .init(body: ["Items": []])
            }
            let start = Int(query.first(where: { $0.name.lowercased() == "startindex" })?.value ?? "0")!
            offsets.append(start)
            if start == 0 { return .init(body: Self.page(["a", "b"], total: 4)) }
            if failSecondPage { return .init(status: 503, body: [:]) }
            return .init(body: Self.page(["b", "c"], total: 4))
        }
        let model = model(); let page = PagedCatalog()
        await page.load(model: model, parent: "library", reset: true)
        await page.load(model: model, parent: "library")
        XCTAssertEqual(page.items.map(\.id), ["a", "b"])
        XCTAssertNotNil(page.error); XCTAssertEqual(page.next, 2)
        failSecondPage = false
        await page.load(model: model, parent: "library")
        XCTAssertEqual(offsets, [0, 2, 2]); XCTAssertNil(page.error)
        XCTAssertEqual(page.items.map(\.id), ["a", "b", "c"])
        XCTAssertEqual(page.next, 4, "Offset counts the server page, not the deduplicated visible cards")
    }
    func testNativeGridMoveHoldsColumnAndReleasesOnceForTheSameCardAndGeneration() {
        let navigation = CatalogGridNavigation(); let generation = UUID()
        XCTAssertEqual(navigation.move(index: 3, id: "d", columns: 4, count: 6, down: true, loading: true, failed: false, generation: generation), .hold)
        XCTAssertEqual(navigation.heldIndex, 3)
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d", "e", "f", "g", "h"], columns: 4, generation: generation, focused: "d", failed: false), .card(7))
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d", "e", "f", "g", "h"], columns: 4, generation: generation, focused: "d", failed: false), .stay)
        XCTAssertNil(navigation.heldIndex)
    }
    func testGridHoldCapFallbackAndFailureRetryUseTheCoreLandingAndCancelForNewSelection() {
        let navigation = CatalogGridNavigation(); let generation = UUID()
        _ = navigation.move(index: 3, id: "d", columns: 4, count: 6, down: true, loading: true, failed: false, generation: generation)
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d", "e", "f"], columns: 4, generation: generation, focused: "d", failed: false), .card(5), "A 1.5-second or empty-page release lands on the later short row")
        _ = navigation.move(index: 5, id: "f", columns: 4, count: 6, down: true, loading: true, failed: false, generation: generation)
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d", "e", "f"], columns: 4, generation: generation, focused: "f", failed: true), .pageRetry)
        navigation.beginRetry(index: 5)
        XCTAssertEqual(navigation.settleRetry(count: 10, columns: 4, failed: false), .card(9))
        navigation.beginRetry(index: 5)
        XCTAssertEqual(navigation.settleRetry(count: 6, columns: 4, failed: true), .stay)
        _ = navigation.move(index: 5, id: "f", columns: 4, count: 6, down: true, loading: true, failed: false, generation: generation)
        navigation.selectionChanged("a")
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d", "e", "f", "g", "h"], columns: 4, generation: generation, focused: "f", failed: false), .stay)
    }
    func testNewSortGenerationCannotReleaseAnOldNativeGridHold() {
        let navigation = CatalogGridNavigation()
        _ = navigation.move(index: 1, id: "b", columns: 2, count: 2, down: true, loading: true, failed: false, generation: UUID())
        XCTAssertEqual(navigation.release(ids: ["a", "b", "c", "d"], columns: 2, generation: UUID(), focused: "b", failed: false), .stay)
        XCTAssertNil(navigation.heldIndex)
    }
    func testSelectedSeasonKeepsItsNativeIdentityThroughDependentReadsAndRejectsMissingIDs() {
        CatalogFixtureProtocol.handler = { _ in .init(body: [:], hold: true) }
        let model = model(); let state = DetailState()
        let series = MediaItem(id: "series", name: "Series", type: "Series")
        let one = MediaItem(id: "one", name: "Season 1", type: "Season")
        let two = MediaItem(id: "two", name: "Season 2", type: "Season")
        state.item = series; state.header = series; state.seasons = [one, two]; state.fresh = true
        state.selectedSeason = "one"
        state.episodes = [MediaItem(id: "old-episode", name: "Old season episode", type: "Episode")]
        state.chooseSeason("missing", model: model)
        XCTAssertFalse(state.reading); XCTAssertNil(state.retainedSeasonID)
        state.chooseSeason("two", model: model)
        XCTAssertTrue(state.reading); XCTAssertEqual(state.retainedSeasonID, "two"); XCTAssertEqual(state.selectedSeason, "two")
        XCTAssertTrue(state.episodes.isEmpty, "Old season tiles must disappear as soon as a different season is selected")
        XCTAssertTrue(state.loadingSelectedSeason, "The Episodes section owns the dependent-read presentation")
        XCTAssertEqual(state.seasons.map(\.id), ["one", "two"], "Dependent reads must retain the existing season subtree")
        state.publishRead(DetailPublication(item: series, seasons: [one, two], episodes: [], season: "two", target: nil), publication: UUID())
        XCTAssertEqual(state.retainedSeasonID, "two"); XCTAssertNil(state.focusRequest)
        XCTAssertFalse(state.loadingSelectedSeason)
        state.seasonFocusChanged("season-two"); XCTAssertEqual(state.retainedSeasonID, "two")
        state.publishRead(DetailPublication(item: series, seasons: [one], episodes: [], season: "one", target: one), publication: UUID())
        XCTAssertNil(state.retainedSeasonID); XCTAssertEqual(state.focusRequest, "play", "Removed membership must release to a useful real action")
        state.leave()
    }
    func testHeldDetailFocusGetsOnePendingCueAndFastArrivalStaysQuiet() {
        let state = DetailState()
        state.updatePendingFeedback(waiting: true, graceExpired: false, focused: "play", resumed: true, cancelled: false)
        XCTAssertNil(state.cue)
        state.updatePendingFeedback(waiting: false, graceExpired: false, focused: "play", resumed: true, cancelled: false)
        XCTAssertNil(state.cue)
        state.updatePendingFeedback(waiting: true, graceExpired: false, focused: "favorite", resumed: true, cancelled: false)
        state.updatePendingFeedback(waiting: true, graceExpired: true, focused: "favorite", resumed: true, cancelled: false)
        XCTAssertEqual(state.cue?.message, "Favorite when ready. Please wait.")
        let cue = state.cue?.id
        state.updatePendingFeedback(waiting: true, graceExpired: true, focused: "favorite", resumed: true, cancelled: false)
        XCTAssertEqual(state.cue?.id, cue, "Observer reevaluation must not replay a sustained hold")
        state.updatePendingFeedback(waiting: true, graceExpired: true, focused: "play", resumed: true, cancelled: false)
        XCTAssertEqual(state.cue?.id, cue, "Moving onto a different pending action cannot inherit the old focus grace")
    }
    func testConversionPreferenceMigratesOnAndRoundTripsOffWithoutDroppingOtherPreferences() throws {
        let previous = Data(#"{"bitrateMbps":20,"passthrough":false,"subtitleSize":"Large","segmentModes":{"Intro":"Automatic"}}"#.utf8)
        var value = try JSONDecoder().decode(PlaybackPreferences.self, from: previous)
        XCTAssertTrue(value.showConversionNotice)
        value.showConversionNotice = false
        let restored = try JSONDecoder().decode(PlaybackPreferences.self, from: JSONEncoder().encode(value))
        XCTAssertFalse(restored.showConversionNotice); XCTAssertEqual(restored.bitrateMbps, 20)
        XCTAssertEqual(restored.subtitleSize, .large); XCTAssertEqual(restored.segmentModes[.intro], .automatic)
        XCTAssertFalse(restored.passthrough)
    }
}
