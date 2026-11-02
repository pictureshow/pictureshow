// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Network
#if os(iOS)
import UIKit
#endif

/// Run on a fresh owned simulator clone. The fixture is finite and local to this
/// runner; the app uses its ordinary sign-in, mutation, deadline and Retry paths.
final class DetailRecoveryUITests: XCTestCase {
    func testHeldRetryPublishesTruthfulFailureThenTransfersToFreshAction() throws {
        continueAfterFailure = false
        #if os(iOS)
        let requestedOrientation: UIDeviceOrientation?
        if let value = ProcessInfo.processInfo.environment["PICTURESHOW_DETAIL_TEST_ORIENTATION"] {
            guard UIDevice.current.userInterfaceIdiom == .pad else {
                XCTFail("An explicit detail orientation case requires an iPad destination"); return
            }
            switch value {
            case "portrait": requestedOrientation = .portrait
            case "landscape": requestedOrientation = .landscapeLeft
            default: XCTFail("Detail orientation must be portrait or landscape"); return
            }
        } else { requestedOrientation = nil }
        let originalOrientation = requestedOrientation.map { _ in XCUIDevice.shared.orientation }
        defer { if let originalOrientation { XCUIDevice.shared.orientation = originalOrientation } }
        if let requestedOrientation { XCUIDevice.shared.orientation = requestedOrientation }
        #endif
        let fixture = try DetailRecoveryHTTPFixture()
        defer { fixture.stop() }
        let app = XCUIApplication()
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = fixture.address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-fixture-password"
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = "fixture-title"
        app.launchEnvironment["PICTURESHOW_DETAIL_DIAGNOSTICS"] = "1"
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
        // Regular-width iPad uses TouchShell's side rail; iPhone uses tabs.
        // The Home-only settings toolbar is absent on this pushed destination.
        let settingsTab = UIDevice.current.userInterfaceIdiom == .pad
            ? app.buttons["Settings"] : app.tabBars.buttons["Settings"]
        let shellReady = settingsTab.waitForExistence(timeout: 15)
        if !shellReady || !settingsTab.isEnabled || !settingsTab.isHittable {
            capture("Detail recovery 00 authenticated shell failure", app: app)
        }
        XCTAssertTrue(shellReady, "Synthetic sign-in must reach the authenticated adaptive shell")
        XCTAssertTrue(settingsTab.isEnabled && settingsTab.isHittable)
        let settingsFrame = settingsTab.frame
        XCTAssertTrue(settingsFrame.width > 0 && settingsFrame.height > 0 && app.frame.contains(settingsFrame))
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 15))
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
        XCTAssertEqual(play.label, "Play")
        XCTAssertEqual(play.value as? String ?? "", "")
        #if os(tvOS)
        XCTAssertTrue(retry.hasFocus, "Failure must select the real Retry control")
        #endif
        capture("Detail recovery 03 truthful deadline and disabled action", app: app)
        #if os(iOS)
        assertRecoveryDoesNotCoverTitle(in: app, retry: retry, reason: reason)
        #endif

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
        #if os(iOS)
        assertRecoveryDoesNotCoverTitle(in: app, retry: retry, reason: "Previous attempt: " + reason)
        #endif

        fixture.releaseDetailReads()
        XCTAssertTrue(waitUntil(timeout: 15) { !retry.exists && play.exists && play.isEnabled })
        // Capture the actual destination before an assertion can end the test.
        capture("Detail recovery 05 fresh primary after release", app: app)
        XCTAssertEqual(play.value as? String ?? "", "", "Ready is an event and never a persistent action value")
        XCTAssertFalse(app.staticTexts[reason].exists, "Successful recovery clears the old visible failure")
        #if os(tvOS)
        XCTAssertTrue(play.hasFocus, "Successful Retry must hand selection directly to the real fresh primary")
        #else
        XCTAssertTrue(play.isHittable, "Successful recovery must reveal a usable primary in the viewport")
        #endif
        #if os(iOS)
        if let requestedOrientation {
            XCTAssertEqual(XCUIDevice.shared.orientation, requestedOrientation, "The explicit iPad orientation must remain active through fresh recovery")
            let window = app.windows.firstMatch
            XCTAssertTrue(window.exists)
            let frame = window.frame
            XCTAssertGreaterThan(frame.width, 0)
            XCTAssertGreaterThan(frame.height, 0)
            if requestedOrientation == .portrait {
                XCTAssertGreaterThan(frame.height, frame.width, "The final iPad window must actually be portrait")
            } else {
                XCTAssertGreaterThan(frame.width, frame.height, "The final iPad window must actually be landscape")
            }
            print("F2_DETAIL_ORIENTATION requested=\(requestedOrientation == .portrait ? "portrait" : "landscape") width=\(frame.width) height=\(frame.height)")
        }
        #endif
        print("F2_DETAIL_RECOVERY reads=\(fixture.totalDetailReads) retryRetained=true freshPrimaryEnabled=\(play.isEnabled) readyValue=\(play.value as? String ?? "")")
    }

    func testFirstAppearanceKeepsOneReadAndHeldActionRetainsNativeFocusThenEmitsOneReady() throws {
        continueAfterFailure = false
        let fixture = try DetailRecoveryHTTPFixture()
        defer { fixture.stop() }
        let app = try launchFixtureDetail(fixture)
        defer { app.terminate() }
        app.terminate()
        fixture.holdDetailReads()
        let before = fixture.totalDetailReads
        app.launch()
        XCTAssertTrue(waitUntil(timeout: 15) { fixture.heldReadCount == 1 })
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertEqual(fixture.totalDetailReads, before + 1, "The first appearance must observe the arrival read without restarting it")
        fixture.releaseDetailReads()
        let play = app.buttons["play"], watched = app.buttons["watched"], favorite = app.buttons["favorite"]
        XCTAssertTrue(play.waitForExistence(timeout: 15) && play.isEnabled)
        XCTAssertTrue(watched.exists && watched.isEnabled)
        XCTAssertFalse((play.value as? String ?? "").contains("Ready"))
        let freshReads = fixture.totalDetailReads
        fixture.holdDetailReads()
        activate(watched, in: app)
        XCTAssertTrue(waitUntil(timeout: 10) { fixture.heldReadCount == 1 })
        XCTAssertEqual(fixture.totalDetailReads, freshReads + 1)
        let held = try app.snapshot()
        func nodes(_ node: XCUIElementSnapshot) -> [XCUIElementSnapshot] { [node] + node.children.flatMap(nodes) }
        let heldNodes = nodes(held)
        for (identifier, name) in [("play", "Play"), ("watched", "Watched"), ("favorite", "Favorite")] {
            let node = try XCTUnwrap(heldNodes.first { $0.identifier == identifier && $0.elementType == .button })
            XCTAssertFalse(node.isEnabled, "Held action must expose native disabled semantics")
            XCTAssertEqual(node.label, name)
            XCTAssertEqual(node.value as? String ?? "", "")
            #if os(tvOS)
            if identifier == "watched" { XCTAssertTrue(node.hasFocus, "The same input control must retain remote focus while held") }
            #endif
        }
        capture("F3 detail held plain disabled actions", app: app)
        print("F3_DETAIL_PHASE same-focused-held reads=\(fixture.totalDetailReads)")
        fixture.releaseDetailReads()
        XCTAssertTrue(waitUntil(timeout: 15) { watched.exists && watched.isEnabled })
        #if os(tvOS)
        XCTAssertTrue(watched.hasFocus)
        focus(favorite, in: app)
        XCTAssertTrue(favorite.hasFocus)
        focus(watched, in: app)
        XCTAssertTrue(watched.hasFocus)
        #endif
        XCTAssertEqual(watched.value as? String, "Watched")
        XCTAssertFalse((watched.value as? String ?? "").contains("Ready"))
        Thread.sleep(forTimeInterval: 1.2)
        XCTAssertEqual(fixture.totalDetailReads, freshReads + 1)
        capture("F3 detail settled same control and later traversal", app: app)
        print("F3_DETAIL_PHASE settled-and-traversed reads=\(fixture.totalDetailReads) initialReadDelta=1 heldReadDelta=1")
    }

    func testSeriesReadFailureKeepsNeutralUnknownActionsAndSeasonSelection() throws {
        continueAfterFailure = false
        let fixture = try DetailRecoveryHTTPFixture(kind: .series)
        defer { fixture.stop() }
        let app = try launchFixtureDetail(fixture)
        defer { app.terminate() }
        let season = app.buttons["season-fixture-season"]
        XCTAssertTrue(season.waitForExistence(timeout: 15))
        XCTAssertEqual(season.value as? String, "Selected")
        fixture.holdDetailReads()
        activate(app.buttons["watched"], in: app)
        XCTAssertTrue(waitUntil(timeout: 10) { fixture.heldReadCount == 1 })
        let retry = app.buttons["detailRetry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 40))
        for name in ["watched", "favorite"] {
            let node = app.buttons[name]
            XCTAssertFalse(node.isEnabled)
            XCTAssertEqual(node.label, name == "watched" ? "Watched" : "Favorite")
            XCTAssertEqual(node.value as? String ?? "", "")
        }
        XCTAssertFalse(season.isEnabled)
        XCTAssertEqual(season.label, "Season 1")
        XCTAssertEqual(season.value as? String ?? "", "", "An unknown retained season cannot claim Selected")
        let reason = "The item took too long to load. Choose Retry to try again."
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label == %@", reason)).count, 1)
        XCTAssertEqual(retry.value as? String, "Retry. Loading failed. " + reason)
        capture("F3 series neutral retained unknown actions and season", app: app)
        fixture.releaseDetailReads()
        activate(retry, in: app)
        XCTAssertTrue(waitUntil(timeout: 15) { !retry.exists && season.isEnabled })
        XCTAssertEqual(season.value as? String, "Selected")
        XCTAssertEqual(app.buttons["watched"].value as? String, "Watched")
        print("F3_DETAIL_SERIES neutralUnknown=true singleIdleReason=true restoredSelection=true")
    }

    private func launchFixtureDetail(_ fixture: DetailRecoveryHTTPFixture) throws -> XCUIApplication {
        let app = XCUIApplication()
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = fixture.address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-fixture-password"
        app.launchEnvironment["PICTURESHOW_TEST_DETAIL_ID"] = "fixture-title"
        app.launchEnvironment["PICTURESHOW_DETAIL_DIAGNOSTICS"] = "1"
        app.launch()
        XCTAssertTrue(app.buttons["connect"].waitForExistence(timeout: 15), "Use a fresh owned clone")
        activate(app.buttons["connect"], in: app)
        let signIn = app.buttons["signIn"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 15))
        #if os(tvOS)
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #else
        if app.buttons["dismissSignInKeyboard"].exists { app.buttons["dismissSignInKeyboard"].tap() }
        if !signIn.isHittable { app.swipeUp() }
        #endif
        activate(signIn, in: app)
        #if os(tvOS)
        XCTAssertTrue(app.buttons["openSettings"].waitForExistence(timeout: 15), "Synthetic sign-in must reach authenticated navigation")
        #else
        let settings = UIDevice.current.userInterfaceIdiom == .pad
            ? app.buttons["Settings"] : app.tabBars.buttons["Settings"]
        XCTAssertTrue(settings.waitForExistence(timeout: 15), "Synthetic sign-in must reach the authenticated adaptive shell")
        #endif
        // The persisted-account launch consumes the detail hook consistently on both platforms.
        app.terminate(); app.launch()
        XCTAssertTrue(app.buttons["play"].waitForExistence(timeout: 20))
        return app
    }

    #if os(tvOS)
    private func focus(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<24 {
            if element.hasFocus { return }
            let current = app.descendants(matching: .any).allElementsBoundByIndex.first { $0.hasFocus }?.frame
            guard let current else { XCUIRemote.shared.press(.down); continue }
            let target = element.frame
            let dx = target.midX - current.midX, dy = target.midY - current.midY
            let sameColumn = current.minX < target.maxX && target.minX < current.maxX
            if abs(dy) > 20 && (sameColumn || abs(dy) > abs(dx)) { XCUIRemote.shared.press(dy < 0 ? .up : .down) }
            else { XCUIRemote.shared.press(dx < 0 ? .left : .right) }
        }
        XCTFail("Could not focus fixture control without activation")
    }
    #endif

    #if os(iOS)
    private func assertRecoveryDoesNotCoverTitle(in app: XCUIApplication, retry: XCUIElement, reason: String) {
        let titleFrame = app.staticTexts["detailTitle"].frame
        let reasonText = app.staticTexts[reason]
        XCTAssertTrue(reasonText.exists, "A held Retry must label the retained failure as the previous attempt")
        let recoveryFrame = retry.frame.union(reasonText.frame)
        XCTAssertTrue(titleFrame.width > 0 && titleFrame.height > 0)
        XCTAssertTrue(app.frame.contains(titleFrame), "The complete long title must remain in the visible viewport")
        XCTAssertGreaterThanOrEqual(titleFrame.minY, app.navigationBars.firstMatch.frame.maxY)
        XCTAssertFalse(titleFrame.intersects(recoveryFrame), "Recovery controls and their background cannot cover the long title")
    }
    #endif

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
        #if os(iOS)
        // App-element captures can crop rotated iPad windows. Capture the actual
        // display so landscape evidence includes the entire status bar and edge.
        let screenshot = UIDevice.current.userInterfaceIdiom == .pad
            ? XCUIScreen.main.screenshot() : app.screenshot()
        #else
        let screenshot = app.screenshot()
        #endif
        let attachment = XCTAttachment(screenshot: screenshot)
        attachment.name = name; attachment.lifetime = .keepAlways; add(attachment)
    }
}

/// An HTTP boundary fixture, rather than a production UI state override. All
/// values are invented and the listener dies with this one test case.
private final class DetailRecoveryHTTPFixture {
    enum Kind { case movie, series }
    private let kind: Kind
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
    init(kind: Kind = .movie) throws {
        self.kind = kind
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
            } else if self.kind == .series && path.hasSuffix("/Shows/fixture-title/Seasons") {
                body = ["Items": [["Id": "fixture-season", "Name": "Season 1", "Type": "Season", "SeriesId": "fixture-title", "IndexNumber": 1]]]
            } else if self.kind == .series && (path.hasSuffix("/Shows/fixture-title/Episodes") || path.hasSuffix("/Shows/NextUp")) {
                body = ["Items": [["Id": "fixture-episode", "Name": "Fixture episode", "Type": "Episode", "SeriesId": "fixture-title", "SeasonId": "fixture-season", "ParentIndexNumber": 1, "IndexNumber": 1, "UserData": self.userData()]]]
            } else if path.hasSuffix("/Items") { body = ["Items": [self.movie()]] }
            else { body = ["Items": []] }
            self.respond(connection, body: body)
        }
    }
    private func userData() -> [String: Any] {
        lock.lock(); defer { lock.unlock() }; return ["Played": played, "IsFavorite": false, "PlaybackPositionTicks": 0]
    }
    private func movie() -> [String: Any] {
        ["Id": "fixture-title", "Name": "A fixture film with a deliberately long wrapping title for lower-action viewport verification", "Type": kind == .series ? "Series" : "Movie", "ProductionYear": 2026, "Overview": "An invented film used to verify the ordinary detail recovery path.", "UserData": userData()]
    }
    private func respond(_ connection: NWConnection, body: Any) {
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.fragmentsAllowed]) else { connection.cancel(); return }
        var response = Data("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n".utf8)
        response.append(data)
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}
