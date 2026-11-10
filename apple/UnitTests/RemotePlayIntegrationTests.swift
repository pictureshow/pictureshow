// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import PictureshowCore
#if os(tvOS)
@testable import Pictureshow_tvOS
#else
@testable import Pictureshow_iOS
#endif

private final class RemotePlayFixtureProtocol: URLProtocol {
    struct Reply { var status = 200; var body: Any = []; var hold = false }
    static var handler: (URLRequest) -> Reply = { _ in Reply() }
    private static let lock = NSLock()
    private static var waiting: [(RemotePlayFixtureProtocol, Reply)] = []
    static var waitingCount: Int { lock.lock(); defer { lock.unlock() }; return waiting.count }
    static func release() {
        lock.lock(); let replies = waiting; waiting = []; lock.unlock()
        replies.forEach { $0.0.respond($0.1) }
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let reply = Self.handler(request)
        if reply.hold { Self.lock.lock(); Self.waiting.append((self, reply)); Self.lock.unlock() }
        else { respond(reply) }
    }
    private func respond(_ reply: Reply) {
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        if reply.status != 204 { client?.urlProtocol(self, didLoad: try! JSONSerialization.data(withJSONObject: reply.body)) }
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() { }
}

@MainActor final class RemotePlayIntegrationTests: XCTestCase {
    private let viewer = "11111111111111111111111111111111"
    private let oldItem = "22222222222222222222222222222222"
    private let requestedItem = "33333333333333333333333333333333"
    private func sessions(item: String, paused: Bool = false, session: String = "tv-session") -> [[String: Any]] {
        [["Id": session, "DeviceId": "tv-device", "DeviceName": "Living room", "Client": "Jellyfin TV", "UserId": viewer, "UserName": "Viewer", "SupportsRemoteControl": true, "PlayableMediaTypes": ["Video"], "SupportedCommands": [], "NowPlayingItem": ["Id": item, "Name": "Authorized title", "RunTimeTicks": 600000000], "PlayState": ["PositionTicks": 0, "IsPaused": paused, "CanSeek": true]]]
    }
    private func model(clock: @escaping () -> Int64) -> RemotePlayModel {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RemotePlayFixtureProtocol.self]
        let account = Account(server: Server(url: URL(string: "https://media.example.test")!, id: "fixture-server"), userId: viewer, username: "Viewer", accessToken: "fixture-token", deviceID: "phone-device")
        return RemotePlayModel(account: account, client: JellyfinClient(deviceID: account.deviceID, session: URLSession(configuration: configuration)), clock: clock, automaticallyObserve: false)
    }
    override func tearDown() { RemotePlayFixtureProtocol.release() }
    private func waitForHeldRequest() async throws {
        for _ in 0..<100 {
            if RemotePlayFixtureProtocol.waitingCount > 0 { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Expected a held session observation")
    }

    func testHeldNineSecondObservationConfirmsRequestedTitleButNotHTTPOrOldTitle() async throws {
        var now: Int64 = 0, writes = 0
        var currentItem = oldItem, hold = false
        RemotePlayFixtureProtocol.handler = { request in
            if request.httpMethod == "POST" {
                writes += 1
                XCTAssertEqual(request.url?.path, "/Sessions/tv-session/Playing")
                let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
                XCTAssertEqual(query.first { $0.name == "playCommand" }?.value, "PlayNow")
                XCTAssertEqual(query.first { $0.name == "itemIds" }?.value, self.requestedItem)
                XCTAssertNil(query.first { $0.name == "startPositionTicks" }, "Receiver owns resume unless an explicit position is supplied")
                XCTAssertNotNil(request.value(forHTTPHeaderField: "Authorization"))
                return .init(status: 204)
            }
            XCTAssertEqual(request.url?.path, "/Sessions")
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertEqual(query.first { $0.name == "controllableByUserId" }?.value, self.viewer)
            XCTAssertFalse(query.contains { $0.name == "activeWithinSeconds" })
            return .init(body: self.sessions(item: currentItem), hold: hold)
        }
        let model = model(clock: { now }); await model.refresh()
        let target = try XCTUnwrap(model.targets.first)
        await model.begin(on: target, itemID: requestedItem)
        XCTAssertEqual(model.result, "sent", "HTTP 204 cannot confirm playback")
        now = 6_000; await model.refresh()
        XCTAssertEqual(model.result, "sent", "Old title must not confirm the new request")
        currentItem = requestedItem; hold = true
        let observation = Task { await model.refresh() }
        try await waitForHeldRequest()
        now = 9_000; RemotePlayFixtureProtocol.release(); await observation.value
        XCTAssertEqual(model.result, "confirmed")
        XCTAssertEqual(writes, 1)
        model.close()
    }

    func testTwelveSecondUnknownAndRetryOnlyObservesWithoutSendingAgain() async throws {
        var now: Int64 = 0, writes = 0
        var currentItem = oldItem, hold = false
        RemotePlayFixtureProtocol.handler = { request in
            if request.httpMethod == "POST" { writes += 1; return .init(status: 204) }
            return .init(body: self.sessions(item: currentItem), hold: hold)
        }
        let model = model(clock: { now }); await model.refresh()
        await model.begin(on: try XCTUnwrap(model.targets.first), itemID: requestedItem)
        now = 11_999; model.tick(); XCTAssertEqual(model.result, "sent")
        currentItem = requestedItem; hold = true
        let late = Task { await model.refresh() }; try await waitForHeldRequest()
        now = 12_000; model.tick(); XCTAssertEqual(model.result, "unknown")
        RemotePlayFixtureProtocol.release(); await late.value
        XCTAssertEqual(model.result, "unknown", "A delayed read cannot revive the expired command")
        hold = false; await model.refresh()
        XCTAssertEqual(model.result, "unknown")
        XCTAssertEqual(writes, 1, "Retry must only observe; an uncertain write is never replayed")
        model.close()
    }

    func testOrdinaryTransportStillExpiresAtFiveSecondsThroughNativeAdapter() async throws {
        var now: Int64 = 0
        RemotePlayFixtureProtocol.handler = { request in
            request.httpMethod == "POST" ? .init(status: 204) : .init(body: self.sessions(item: self.oldItem))
        }
        let model = model(clock: { now }); await model.refresh()
        await model.begin(on: try XCTUnwrap(model.targets.first), itemID: requestedItem, operation: "pause")
        now = 4_999; model.tick(); XCTAssertEqual(model.result, "sent")
        now = 5_000; model.tick(); XCTAssertEqual(model.result, "unknown")
        model.close()
    }

    func testReplacementSessionCannotConfirmAndClosedChooserIgnoresLateResponse() async throws {
        var now: Int64 = 0, session = "tv-session", hold = false
        RemotePlayFixtureProtocol.handler = { request in
            request.httpMethod == "POST" ? .init(status: 204) : .init(body: self.sessions(item: self.requestedItem, session: session), hold: hold)
        }
        let model = model(clock: { now }); await model.refresh()
        await model.begin(on: try XCTUnwrap(model.targets.first), itemID: requestedItem)
        session = "replacement-session"; now = 1_000
        await model.refresh()
        XCTAssertEqual(model.phase, "target-restarted")
        XCTAssertNotEqual(model.result, "confirmed")
        hold = true
        let read = Task { await model.refresh() }; try await waitForHeldRequest()
        model.close(); RemotePlayFixtureProtocol.release(); await read.value
        XCTAssertEqual(model.phase, "detached"); XCTAssertNil(model.result)
    }

    func testQueueChoicesUseCorrectWireAndNeverClaimObservedQueueConfirmation() async throws {
        for (operation, expectedCommand) in [("play-next", "PlayNext"), ("add-to-queue", "PlayLast")] {
            var now: Int64 = 0, writes = 0
            RemotePlayFixtureProtocol.handler = { request in
                if request.httpMethod == "POST" {
                    writes += 1
                    XCTAssertEqual(request.url?.path, "/Sessions/tv-session/Playing")
                    let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
                    XCTAssertEqual(query.first { $0.name == "playCommand" }?.value, expectedCommand)
                    XCTAssertEqual(query.first { $0.name == "itemIds" }?.value, self.requestedItem)
                    XCTAssertNil(query.first { $0.name == "startPositionTicks" })
                    return .init(status: 204)
                }
                return .init(body: self.sessions(item: self.requestedItem))
            }
            let model = model(clock: { now }); await model.refresh()
            let target = try XCTUnwrap(model.targets.first)
            await model.begin(on: target, itemID: requestedItem, operation: operation)
            XCTAssertEqual(model.receiptStatus, 204); XCTAssertEqual(model.result, "sent")
            XCTAssertTrue(model.message.contains("Queue request sent")); XCTAssertFalse(model.waiting)
            now = 1_000; await model.refresh()
            XCTAssertEqual(model.result, "sent", "Matching current playback cannot prove queue contents")
            now = 5_000; model.tick(); XCTAssertEqual(model.result, "unknown")
            await model.refresh()
            await model.begin(on: target, itemID: requestedItem, operation: operation)
            XCTAssertEqual(writes, 1, "Refresh and duplicate taps cannot replay a queue mutation")
            model.close()
        }
    }

    func testPlayFromStartSendsExplicitZeroAndStillRequiresPlaybackObservation() async throws {
        var writes = 0
        RemotePlayFixtureProtocol.handler = { request in
            if request.httpMethod == "POST" {
                writes += 1
                let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
                XCTAssertEqual(query.first { $0.name == "playCommand" }?.value, "PlayNow")
                XCTAssertEqual(query.first { $0.name == "startPositionTicks" }?.value, "0")
                return .init(status: 204)
            }
            return .init(body: self.sessions(item: self.oldItem))
        }
        let model = model(clock: { 0 }); await model.refresh()
        await model.begin(on: try XCTUnwrap(model.targets.first), itemID: requestedItem, fromStart: true)
        XCTAssertEqual(model.result, "sent"); XCTAssertEqual(writes, 1)
        await model.refresh(); XCTAssertEqual(model.result, "sent")
        model.close()
    }

    func testQueueHTTPFailuresKeepReceiptHonestAndNeverRetryTheWrite() async throws {
        for status in [401, 403, 404, 503] {
            var writes = 0
            RemotePlayFixtureProtocol.handler = { request in
                if request.httpMethod == "POST" { writes += 1; return .init(status: status, body: [:]) }
                return .init(body: self.sessions(item: self.oldItem))
            }
            let model = model(clock: { 0 }); await model.refresh()
            await model.begin(on: try XCTUnwrap(model.targets.first), itemID: requestedItem, operation: "add-to-queue")
            XCTAssertEqual(model.receiptStatus, status)
            XCTAssertFalse(model.message.contains("Queue request sent"))
            XCTAssertNotEqual(model.result, "confirmed")
            if status == 401 { XCTAssertEqual(model.phase, "signed-out") }
            if status == 403 { XCTAssertEqual(model.phase, "denied") }
            if status == 404 { XCTAssertEqual(model.phase, "target-gone") }
            if status == 503 { XCTAssertEqual(model.result, "unknown") }
            await model.refresh(); XCTAssertEqual(writes, 1)
            model.close()
        }
    }

    func testAttachedQueueTargetIsFreshlyValidatedAndSentOnlyOnce() async throws {
        for operation in ["play-next", "add-to-queue"] {
            var reads = 0, writes = 0
            RemotePlayFixtureProtocol.handler = { request in
                if request.httpMethod == "POST" {
                    writes += 1
                    XCTAssertGreaterThanOrEqual(reads, 2, "A cached attachment cannot authorize a write without a fresh read")
                    let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems!
                    XCTAssertEqual(query.first { $0.name == "playCommand" }?.value, operation == "play-next" ? "PlayNext" : "PlayLast")
                    return .init(status: 204)
                }
                reads += 1
                return .init(body: self.sessions(item: self.oldItem))
            }
            let model = model(clock: { 0 }); await model.refresh()
            let retained = try XCTUnwrap(model.targets.first)
            await model.beginAttached(retained, itemID: requestedItem, operation: operation)
            XCTAssertEqual(model.selectedName, retained.name); XCTAssertEqual(model.receiptStatus, 204)
            XCTAssertEqual(writes, 1)
            await model.refresh()
            await model.beginAttached(retained, itemID: requestedItem, operation: operation)
            XCTAssertEqual(writes, 1, "A remount or Refresh must not repeat the automatic attached-target command")
            model.close()
        }
    }

    func testAttachedTargetMissingRestartedChangedOrUnauthorizedNeverWrites() async throws {
        for failure in ["gone", "restarted", "device", "viewer", "unauthorized", "forbidden"] {
            var mode = "valid", writes = 0
            RemotePlayFixtureProtocol.handler = { request in
                if request.httpMethod == "POST" { writes += 1; return .init(status: 204) }
                if mode == "unauthorized" { return .init(status: 401, body: [:]) }
                if mode == "forbidden" { return .init(status: 403, body: [:]) }
                if mode == "gone" { return .init(body: []) }
                var rows = self.sessions(item: self.oldItem, session: mode == "restarted" ? "new-session" : "tv-session")
                if mode == "device" { rows[0]["DeviceId"] = "other-device" }
                if mode == "viewer" { rows[0]["UserId"] = "44444444444444444444444444444444" }
                return .init(body: rows)
            }
            let model = model(clock: { 0 }); await model.refresh()
            let retained = try XCTUnwrap(model.targets.first)
            mode = failure
            await model.beginAttached(retained, itemID: requestedItem, operation: "add-to-queue")
            XCTAssertEqual(writes, 0, failure)
            XCTAssertNil(model.selectedName, "The device chooser remains available after a failed automatic attachment")
            XCTAssertNotEqual(model.phase, "attached", failure)
            mode = "valid"
            await model.refresh()
            await model.beginAttached(retained, itemID: requestedItem, operation: "add-to-queue")
            XCTAssertEqual(writes, 0, "Refresh or remount after recovery must still require a deliberate target selection")
            model.close()
        }
    }

    func testRememberedTargetSelectionIsScopedToServerAndViewerAndRejectsStaleAccountCallback() async throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [RemotePlayFixtureProtocol.self]
        let client = JellyfinClient(deviceID: "phone-device", session: URLSession(configuration: configuration))
        let first = Account(server: Server(url: URL(string: "https://media.example.test")!), userId: viewer, username: "Viewer", accessToken: "fixture-token", deviceID: "phone-device")
        var otherViewer = first; otherViewer.userId = "44444444444444444444444444444444"
        var otherServer = first; otherServer.server = Server(url: URL(string: "https://other.example.test")!)
        RemotePlayFixtureProtocol.handler = { _ in .init(body: self.sessions(item: self.oldItem)) }
        let targets = try await client.remotePlaybackTargets(account: first)
        let target = try XCTUnwrap(targets.first)
        let app = AppModel(client: client, account: first)
        app.attachRemote(target, for: first)
        XCTAssertEqual(app.attachedRemote(for: first)?.id, target.id)
        XCTAssertNil(app.attachedRemote(for: otherViewer)); XCTAssertNil(app.attachedRemote(for: otherServer))
        app.account = otherViewer
        app.attachRemote(target, for: otherServer)
        XCTAssertNil(app.attachedRemote(for: otherServer), "A callback from a different account cannot attach to the active viewer")
        app.attachRemote(target, for: otherViewer)
        XCTAssertEqual(app.attachedRemote(for: otherViewer)?.id, target.id)
        app.account = nil
        app.attachRemote(target, for: otherServer)
        XCTAssertNil(app.attachedRemote(for: otherServer))
    }
}
