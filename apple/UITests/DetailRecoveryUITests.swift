// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Network

/// Run on a fresh owned simulator clone. The fixture is finite and local to this
/// runner; the app uses its ordinary sign-in, mutation, deadline and Retry paths.
final class DetailRecoveryUITests: XCTestCase {
    func testHeldRetryPublishesTruthfulFailureThenTransfersToFreshAction() throws {
        continueAfterFailure = false
        let fixture = try DetailRecoveryHTTPFixture()
        defer { fixture.stop() }
        let app = XCUIApplication()
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = fixture.address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-fixture-password"
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = "fixture-title"
        app.launch()
        defer { app.terminate() }
        let connect = app.buttons["connect"]
        XCTAssertTrue(connect.waitForExistence(timeout: 15), "Use a fresh owned clone so the local fixture cannot reuse another account")
        activate(connect, in: app)
        let signIn = app.buttons["signIn"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 15))
        #if os(iOS)
        let done = app.buttons["dismissSignInKeyboard"]
        if done.exists && done.isHittable { done.tap() }
        if !signIn.isHittable { app.swipeUp() }
        #elseif os(tvOS)
        // Menu belongs to the native keyboard only when it is actually open;
        // otherwise the app uses it to leave this sign-in form.
        let keyboard = app.keyboards.firstMatch
        if keyboard.exists {
            XCUIRemote.shared.press(.menu)
            XCTAssertTrue(keyboard.waitForNonExistence(timeout: 5))
        }
        #endif
        activate(signIn, in: app)
        #if os(iOS)
        // The detail launch hook already opens the fixture film after sign-in;
        // the Home-only settings toolbar is absent on this pushed destination.
        let settingsTab = app.tabBars.buttons["Settings"]
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 15), "Synthetic sign-in must reach the authenticated tab shell")
        XCTAssertTrue(settingsTab.isEnabled && settingsTab.isHittable)
        let settingsFrame = settingsTab.frame
        XCTAssertTrue(settingsFrame.width > 0 && settingsFrame.height > 0 && app.frame.contains(settingsFrame))
        XCTAssertEqual(app.staticTexts["detailTitle"].label, "A fixture film with a deliberately long wrapping title for lower-action viewport verification")
        #else
        XCTAssertTrue(app.buttons["openSettings"].waitForExistence(timeout: 15), "Synthetic sign-in must reach authenticated navigation")
        #endif
        // Relaunch through the existing detail hook after normal account persistence.
        app.terminate(); app.launch()
        let play = app.buttons["play"]
        let watched = app.buttons["watched"]
        XCTAssertTrue(play.waitForExistence(timeout: 15))
        XCTAssertTrue(watched.exists)
        capture("Detail recovery 01 authoritative movie", app: app)

        fixture.holdDetailReads()
        activate(watched, in: app)
        XCTAssertTrue(waitUntil(timeout: 10) { fixture.heldReadCount == 1 })
        XCTAssertTrue(play.exists, "The published action node stays present during a refresh")
        capture("Detail recovery 02 retained pending actions", app: app)
        let retry = app.buttons["detailRetry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 40), "A held authoritative read must reach the normal fixed request deadline")
        let reason = "The item took too long to load. Choose Retry to try again."
        XCTAssertTrue(app.staticTexts[reason].exists, "The visible failure reason must match the actual deadline failure")
        XCTAssertTrue((retry.value as? String ?? "").contains("Retry. Loading failed. " + reason))
        XCTAssertTrue(play.exists)
        XCTAssertFalse(play.isEnabled, "The stale Play node remains visible but exposes disabled accessibility semantics")
        XCTAssertTrue((play.value as? String ?? "").contains("Unavailable"))
        #if os(tvOS)
        XCTAssertTrue(retry.hasFocus, "Failure must select the real Retry control")
        #endif
        capture("Detail recovery 03 truthful deadline and disabled action", app: app)

        let priorReads = fixture.totalDetailReads
        activate(retry, in: app)
        XCTAssertTrue(waitUntil(timeout: 10) { fixture.totalDetailReads == priorReads + 1 })
        XCTAssertTrue(retry.exists)
        XCTAssertEqual(retry.label, "Retrying…")
        XCTAssertTrue(retry.isEnabled, "Retain the input-focusable Retry node while its handler rejects repeated activation")
        XCTAssertEqual(retry.value as? String, "Loading item. Please wait.")
        #if os(tvOS)
        XCTAssertTrue(retry.hasFocus)
        XCUIRemote.shared.press(.select)
        #else
        retry.tap()
        #endif
        Thread.sleep(forTimeInterval: 0.3)
        XCTAssertEqual(fixture.totalDetailReads, priorReads + 1, "Repeated Select during an accepted Retry cannot start another read")
        capture("Detail recovery 04 held deliberate retry retains target", app: app)

        fixture.releaseDetailReads()
        XCTAssertTrue(waitUntil(timeout: 15) { !retry.exists && play.exists && play.isEnabled })
        // Capture the actual destination before an assertion can end the test.
        capture("Detail recovery 05 fresh primary after release", app: app)
        XCTAssertTrue((play.value as? String ?? "").contains("Ready"), "The same usable primary exposes its pending-to-ready state")
        XCTAssertFalse(app.staticTexts[reason].exists, "Successful recovery clears the old visible failure")
        #if os(tvOS)
        XCTAssertTrue(play.hasFocus, "Successful Retry must hand selection directly to the real fresh primary")
        #else
        XCTAssertTrue(play.isHittable, "Successful recovery must reveal a usable primary in the viewport")
        #endif
        print("F2_DETAIL_RECOVERY reads=\(fixture.totalDetailReads) retryRetained=true freshPrimaryEnabled=\(play.isEnabled) readyValue=\(play.value as? String ?? "")")
    }

    private func waitUntil(timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat { if condition() { return true }; Thread.sleep(forTimeInterval: 0.1) } while ProcessInfo.processInfo.systemUptime < deadline
        return condition()
    }
    private func activate(_ element: XCUIElement, in app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        for _ in 0..<24 {
            if element.hasFocus { XCUIRemote.shared.press(.select); return }
            let current = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }?.frame
            guard let current else { XCUIRemote.shared.press(.down); continue }
            let target = element.frame
            let dx = target.midX - current.midX, dy = target.midY - current.midY
            // A full-width form action is below either password-column control.
            // Comparing center distances alone cycles Password → Show → Password.
            let sameColumn = current.minX < target.maxX && target.minX < current.maxX
            if abs(dy) > 20 && (sameColumn || abs(dy) > abs(dx)) {
                XCUIRemote.shared.press(dy < 0 ? .up : .down)
            } else {
                XCUIRemote.shared.press(dx < 0 ? .left : .right)
            }
        }
        capture("Detail recovery fixture action could not receive focus", app: app)
        XCTFail("Could not focus the fixture action \(element.identifier)")
        #else
        for _ in 0..<5 where !element.isHittable { app.swipeUp() }
        XCTAssertTrue(element.isHittable); element.tap()
        #endif
    }
    private func capture(_ name: String, app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}

/// An HTTP boundary fixture, rather than a production UI state override. All
/// values are invented and the listener dies with this one test case.
private final class DetailRecoveryHTTPFixture {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pictureshow.detail-recovery-fixture")
    private let lock = NSLock()
    private var holding = false
    private var held: [NWConnection] = []
    private var connections: [NWConnection] = []
    private var reads = 0
    private var played = false
    var address: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    var totalDetailReads: Int { lock.lock(); defer { lock.unlock() }; return reads }
    var heldReadCount: Int { lock.lock(); defer { lock.unlock() }; return held.count }
    init() throws {
        listener = try NWListener(using: .tcp, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in if case .ready = state { ready.signal() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.connections.append(connection); self.lock.unlock()
            connection.start(queue: self.queue); self.receive(connection, buffered: Data())
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success else { listener.cancel(); throw NSError(domain: "DetailRecoveryFixture", code: 1) }
    }
    func holdDetailReads() { lock.lock(); holding = true; lock.unlock() }
    func releaseDetailReads() {
        lock.lock(); holding = false; let requests = held; held = []; lock.unlock()
        for connection in requests { respond(connection, body: movie()) }
    }
    func stop() {
        listener.cancel(); lock.lock(); let open = connections; connections = []; held = []; lock.unlock()
        for connection in open { connection.cancel() }
    }
    private func receive(_ connection: NWConnection, buffered: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16_384) { [weak self] data, _, complete, error in
            guard let self else { connection.cancel(); return }
            var bytes = buffered; if let data { bytes.append(data) }
            guard bytes.range(of: Data("\r\n\r\n".utf8)) != nil else {
                if complete || error != nil { connection.cancel() }
                else { self.receive(connection, buffered: bytes) }; return
            }
            let line = String(decoding: bytes, as: UTF8.self).components(separatedBy: "\r\n")[0].split(separator: " ")
            guard line.count >= 2, let url = URL(string: "http://fixture" + line[1]) else { connection.cancel(); return }
            let path = url.path
            if path.hasSuffix("/Items/fixture-title") {
                self.lock.lock(); self.reads += 1; let hold = self.holding
                if hold { self.held.append(connection) }; self.lock.unlock()
                if !hold { self.respond(connection, body: self.movie()) }; return
            }
            let body: Any
            if path.hasSuffix("/System/Info/Public") { body = ["ProductName": "Jellyfin Server", "Version": "12.0.0", "Id": "fixture", "ServerName": "Detail recovery fixture"] }
            else if path.hasSuffix("/Users/AuthenticateByName") { body = ["AccessToken": "synthetic-fixture-token", "User": ["Id": "fixture-viewer", "Name": "Fixture viewer"]] }
            else if path.hasSuffix("/QuickConnect/Enabled") { body = false }
            else if path.hasSuffix("/Users/Public") { body = [] as [String] }
            else if path.hasSuffix("/UserPlayedItems/fixture-title") {
                self.lock.lock(); self.played = line[0] == "POST"; self.lock.unlock(); body = self.userData()
            } else if path.hasSuffix("/Items") { body = ["Items": [self.movie()]] }
            else { body = ["Items": []] }
            self.respond(connection, body: body)
        }
    }
    private func userData() -> [String: Any] {
        lock.lock(); defer { lock.unlock() }; return ["Played": played, "IsFavorite": false, "PlaybackPositionTicks": 0]
    }
    private func movie() -> [String: Any] {
        ["Id": "fixture-title", "Name": "A fixture film with a deliberately long wrapping title for lower-action viewport verification", "Type": "Movie", "ProductionYear": 2026, "Overview": "An invented film used to verify the ordinary detail recovery path.", "UserData": userData()]
    }
    private func respond(_ connection: NWConnection, body: Any) {
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.fragmentsAllowed]) else { connection.cancel(); return }
        var response = Data("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n".utf8)
        response.append(data)
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}
