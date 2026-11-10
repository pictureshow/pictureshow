// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import Network
import UIKit

/// Run this finite native flow on an owned fresh clone. All catalog data is
/// synthetic and served over ordinary Jellyfin HTTP requests on loopback.
final class CatalogPresentationUITests: XCTestCase {
    func testNativeCatalogSortPagingSeasonRetentionAndConversionPreference() throws {
        continueAfterFailure = false
        #if os(iOS)
        XCUIDevice.shared.orientation = .portrait
        #endif
        let fixture = try CatalogPresentationHTTPFixture()
        defer { fixture.stop() }
        let app = XCUIApplication()
        let projectedLargeType = ProcessInfo.processInfo.environment["PICTURESHOW_TEST_DYNAMIC_TYPE"] == "accessibility3"
        if projectedLargeType { app.launchEnvironment["PICTURESHOW_TEST_DYNAMIC_TYPE"] = "accessibility3" }

        app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.extraExtraExtraLarge.rawValue]
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = fixture.address
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = "Catalog fixture viewer"
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = "synthetic-catalog-password"
        app.launch()
        defer { app.terminate() }
        XCTAssertTrue(app.buttons["connect"].waitForExistence(timeout: 15), "This flow requires a fresh owned clone")
        activate(app.buttons["connect"], app: app)
        let signIn = app.buttons["signIn"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 15))
        #if os(tvOS)
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #else
        let done = app.buttons["dismissSignInKeyboard"]
        if done.exists && done.isHittable { done.tap() }
        #endif
        activate(signIn, app: app)
        let library = app.buttons["item-catalog-library"]
        XCTAssertTrue(library.waitForExistence(timeout: 20))
        fixture.holdNextPage(true)
        activate(library, app: app)
        XCTAssertTrue(app.staticTexts["libraryHeading"].waitForExistence(timeout: 15))
        XCTAssertEqual(app.staticTexts["libraryHeading"].label, "Fixture library")
        let seriesCard = app.buttons["item-catalog-series"]
        XCTAssertTrue(seriesCard.waitForExistence(timeout: 15))
        #if os(iOS)
        let firstCards = [seriesCard] + (1...5).map { app.buttons["item-catalog-\($0)"] }
        let firstRow = firstCards.filter { $0.exists && abs($0.frame.minY - seriesCard.frame.minY) < 8 }.sorted { $0.frame.minX < $1.frame.minX }
        let lastInRow = try XCTUnwrap(firstRow.last)
        XCTAssertEqual(lastInRow.frame.maxX, app.frame.maxX - 20, accuracy: 2, "Flexible columns must fill the grid through the trailing page gutter")
        XCTAssertEqual(seriesCard.frame.width, lastInRow.frame.width, accuracy: 2)
        XCTAssertEqual(seriesCard.frame.minX, app.frame.minX + 20, accuracy: 2)
        let sortField = app.buttons["librarySort"]
        let direction = app.buttons["sortDirection"]
        XCTAssertTrue(sortField.exists)
        assertSingleLineTouchHeight(sortField)
        assertSingleLineTouchHeight(direction)
        XCTAssertEqual(sortField.frame.height, direction.frame.height, accuracy: 2)
        XCTAssertEqual(sortField.frame.width, direction.frame.width, accuracy: 2, "Both halves of sort retain stable equal-width targets")
        #endif
        capture("132 catalog grid large font", app: app)
        #if os(iOS)
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCUIDevice.shared.orientation = .landscapeLeft
            XCTAssertTrue(waitUntil { app.windows.firstMatch.frame.width > app.windows.firstMatch.frame.height })
            XCTAssertEqual(app.staticTexts["libraryHeading"].label, "Fixture library")
            XCTAssertTrue(seriesCard.exists)
            XCTAssertEqual(fixture.lastSort, "SortName", "Resizing keeps the current catalog sort generation")
            capture("132 iPad landscape catalog retains library and sort", app: app)
            XCUIDevice.shared.orientation = .portrait
            XCTAssertTrue(waitUntil { app.windows.firstMatch.frame.height > app.windows.firstMatch.frame.width })
        }
        #endif
        XCTAssertTrue(waitUntil { fixture.heldPages > 0 }, "Near-end paging must issue a real second-page HTTP request")
        #if os(tvOS)
        // Determine the composed first row from actual native card geometry. The
        // target is deliberately not column zero, so a naive next-row search fails.
        let second = app.buttons["item-catalog-1"]
        focus(second, app: app)
        XCTAssertTrue(second.hasFocus)
        let firstCards = [seriesCard] + (1...5).map { app.buttons["item-catalog-\($0)"] }
        let secondFrame = second.frame
        let columns = firstCards.filter {
            let frame = $0.frame
            return abs(frame.minY - secondFrame.minY) < max(8, max(frame.height, secondFrame.height) * 0.025)
        }.count
        XCTAssertGreaterThan(columns, 0)
        if projectedLargeType {
            XCTAssertLessThan(columns, 5, "Native large text must reduce the default five-column composition")
        } else {
            XCTAssertEqual(columns, 5, "The default 1920-point TV window composes five columns")
        }
        // Six titles are loaded before this held page. Start on the last
        // loaded row in a real column, including under a larger native font.
        let heldColumn = min(1, columns - 1)
        let heldIndex = heldColumn + ((5 - heldColumn) / columns) * columns
        let heldCard = app.buttons["item-catalog-\(heldIndex)"]
        focus(heldCard, app: app)
        XCTAssertTrue(heldCard.hasFocus)
        XCUIRemote.shared.press(.down)
        XCTAssertTrue(heldCard.hasFocus, "Down must hold its column while the page is unavailable")
        fixture.releasePages()
        let nextRow = app.buttons["item-catalog-\(heldIndex + columns)"]
        XCTAssertTrue(waitUntil { nextRow.exists && nextRow.hasFocus }, "The next row must keep the actual held column")
        assertTVLandingVisible(nextRow, app: app)
        capture("132 catalog column preserved after held page", app: app)
        #else
        fixture.releasePages()
        XCTAssertTrue(waitUntil { fixture.pageRequests >= 2 })
        #endif

        // Sort changes travel through native controls and start a fresh HTTP generation.
        fixture.failNextPages(2)
        #if os(tvOS)
        let added = app.buttons["sort-DateCreated"]
        activate(added, app: app)
        #else
        let sort = app.descendants(matching: .any).matching(identifier: "librarySort").firstMatch
        XCTAssertTrue(sort.exists)
        activate(sort, app: app)
        let added = app.buttons["Date added"]
        XCTAssertTrue(added.waitForExistence(timeout: 5)); added.tap()
        #endif
        XCTAssertTrue(waitUntil { fixture.lastSort == "DateCreated" })
        let retry = app.buttons["pageRetry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 15), "A failed ordinary page request must expose the native Retry action")
        #if os(tvOS)
        let lastLoaded = app.buttons["item-catalog-5"]
        focus(lastLoaded, app: app)
        XCUIRemote.shared.press(.down)
        XCTAssertTrue(waitUntil { retry.hasFocus }, "Down past the failed row must land on Retry")
        #endif
        #if os(iOS)
        reveal(retry, app: app)
        assertSingleLineTouchHeight(retry)
        XCTAssertLessThan(app.staticTexts["libraryHeading"].frame.maxY, app.navigationBars.firstMatch.frame.maxY, "The pinned-context capture must actually be past the scrolling header")
        XCTAssertFalse(app.descendants(matching: .any).matching(identifier: "librarySortStrip").firstMatch.exists, "The visual context strip must stay silent to VoiceOver")
        capture("132 catalog pinned library sort context", app: app)
        #endif
        #if os(tvOS)
        XCTAssertLessThan(app.staticTexts["libraryHeading"].frame.maxY, 0, "The TV context strip capture must be past the full scrolling header")
        capture("132 catalog pinned library sort context", app: app)
        #endif
        capture("132 catalog page failure Retry focused", app: app)
        let failures = fixture.failedPageResponses
        activate(retry, app: app)
        XCTAssertTrue(waitUntil { fixture.failedPageResponses == failures + 1 && !(retry.value as? String ?? "").contains("Loading") })
        XCTAssertTrue(app.staticTexts["pageError"].exists, "Repeated page failure must keep its explanation and Retry")
        #if os(tvOS)
        XCTAssertTrue(retry.hasFocus, "A repeated failure must not strand native focus")
        #endif
        #if os(iOS)
        reveal(retry, app: app)
        #endif
        capture("132 catalog Retry repeated failure retained", app: app)
        fixture.holdNextPage(true)
        activate(retry, app: app)
        XCTAssertTrue(waitUntil { fixture.heldPages > 0 })
        XCTAssertTrue((retry.value as? String ?? "").contains("Loading"), "Retry publishes its pending value while the page is held")
        #if os(tvOS)
        XCTAssertTrue(retry.hasFocus, "The Retry opener remains mounted and focused throughout its held request")
        #endif
        #if os(iOS)
        reveal(retry, app: app)
        #endif
        capture("132 catalog Retry held request retains focus", app: app)
        fixture.releasePages()
        XCTAssertTrue(app.staticTexts["pageError"].waitForNonExistence(timeout: 10))
        #if os(tvOS)
        let retryLanding = app.buttons["item-catalog-\(5 + columns)"]
        XCTAssertTrue(waitUntil { retryLanding.exists && retryLanding.hasFocus }, "Successful Retry must land on the fresh row in the original column")
        assertTVLandingVisible(retryLanding, app: app)
        XCTAssertTrue(retry.waitForNonExistence(timeout: 5), "The Retry opener disappears only after a real fresh card owns focus")
        #endif
        capture("132 catalog Retry success preserves column", app: app)
        activate(app.buttons["sortDirection"], app: app)
        XCTAssertTrue(waitUntil { fixture.lastDirection == "Descending" })
        #if os(tvOS)
        if projectedLargeType {
            let measuredHeading = app.staticTexts["libraryHeading"].frame
            print("LEG132_EXPLICIT_LARGE_TYPE heading=\(measuredHeading) columns=\(columns) environment=accessibility3 osSettingChanged=false")
            XCTAssertGreaterThan(measuredHeading.height, 54, "Explicit large-type environment must measurably enlarge the native token heading above its53pt baseline")
        }
        #endif
        capture("132 catalog date added descending", app: app)
        activate(seriesCard, app: app)
        let seasonOne = app.buttons["season-catalog-season-1"]
        let seasonTwo = app.buttons["season-catalog-season-2"]
        XCTAssertTrue(seasonOne.waitForExistence(timeout: 15)); XCTAssertTrue(seasonTwo.exists)
        #if os(iOS)
        let primary = app.buttons["play"]
        let watched = app.buttons["watched"]
        let favorite = app.buttons["favorite"]
        XCTAssertEqual(primary.frame.width, watched.frame.width, accuracy: 2, "Large-type detail actions use full-width rows")
        XCTAssertEqual(primary.frame.width, favorite.frame.width, accuracy: 2)
        XCTAssertGreaterThanOrEqual(favorite.frame.minY, watched.frame.maxY, "Mutation actions cannot form orphan wrapping rows")
        [primary, watched, favorite, app.buttons["playOnDevice"]].forEach(assertSingleLineTouchHeight)
        let detailHeading = app.staticTexts["detailTitle"]
        reveal(detailHeading, app: app)
        XCTAssertFalse(app.navigationBars.staticTexts[detailHeading.label].exists, "The display heading must not be duplicated by a truncated inline title")
        #endif
        capture("132 series seasons and episodes", app: app)
        fixture.holdSeasonTwo(true)
        activate(seasonTwo, app: app)
        XCTAssertTrue(waitUntil { fixture.heldEpisodeReads > 0 })
        XCTAssertTrue(seasonOne.exists && seasonTwo.exists, "The season subtree must remain mounted through its dependent read")
        XCTAssertFalse(app.buttons["item-catalog-episode-1"].exists, "The previous season's episode tile must not appear under Season 2")
        let episodeLoading = app.descendants(matching: .any).matching(identifier: "episodesLoading").firstMatch
        XCTAssertTrue(episodeLoading.exists, "Loading belongs inside the Episodes section")
        XCTAssertFalse(app.staticTexts["detailStatus"].exists, "Season selection must not float a loading toast over the detail")
        #if os(tvOS)
        XCTAssertTrue(seasonTwo.hasFocus, "The selected season must keep native focus while episodes load")
        #endif
        XCTAssertTrue((seasonTwo.value as? String ?? "").contains("Selected") || seasonTwo.isSelected)
        capture("132 selected season retained during episode read", app: app)
        fixture.releaseSeasonTwo()
        XCTAssertTrue(app.buttons["item-catalog-episode-2"].waitForExistence(timeout: 15))
        #if os(tvOS)
        XCTAssertTrue(seasonTwo.hasFocus, "Fresh episode publication must not refocus the page title or Play")
        #endif
        capture("132 selected season fresh episodes", app: app)
        #if os(iOS)
        reveal(app.buttons["item-catalog-episode-2"], app: app)
        let title = app.staticTexts["detailTitle"]
        if title.frame.maxY < app.navigationBars.firstMatch.frame.maxY {
            XCTAssertTrue(app.navigationBars.staticTexts[title.label].waitForExistence(timeout: 5), "The compact title returns after the display heading scrolls off")
            capture("132 detail compact title after header scroll", app: app)
        }
        #endif
        back(app)
        back(app)
        #if os(tvOS)
        activate(app.buttons["openSettings"], app: app)
        #else
        let settings = UIDevice.current.userInterfaceIdiom == .pad ? app.buttons["Settings"] : app.tabBars.buttons["Settings"]
        activate(settings, app: app)
        #endif
        let toggle = app.switches["conversionNoticeToggle"]
        #if os(tvOS)
        let conversion = app.descendants(matching: .any).matching(identifier: "conversionNoticeToggle").firstMatch
        XCTAssertTrue(conversion.waitForExistence(timeout: 15))
        let priorValue = conversion.value as? String
        activate(conversion, app: app)
        XCTAssertNotEqual(conversion.value as? String, priorValue, "The native value action must store its new value")
        #else
        XCTAssertTrue(toggle.waitForExistence(timeout: 15)); XCTAssertEqual(toggle.value as? String, "1")
        capture("132 settings native conversion switch on", app: app)
        // The labelled Form row must toggle from its text area as well as the thumb.
        toggle.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
        XCTAssertTrue(waitUntil { toggle.value as? String == "0" })
        #endif
        let outputFooter = app.descendants(matching: .any).matching(identifier: "audioOutputExplanation").firstMatch
        XCTAssertTrue(outputFooter.exists, "Audio output is explained as a footer rather than a fake setting")
        XCTAssertEqual(app.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] %@", "AVKit")).count, 0)
        capture("132 settings conversion notice off", app: app)
        #if os(iOS)
        let diagnostics = app.buttons["openDiagnostics"]
        for _ in 0..<10 {
            if diagnostics.exists && diagnostics.isHittable { break }
            app.swipeUp()
        }
        activate(diagnostics, app: app)
        let share = app.buttons["shareDiagnostics"]
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        capture("132 This device capability report share action", app: app)
        activate(share, app: app)
        let copy = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Copy")).firstMatch
        XCTAssertTrue(copy.waitForExistence(timeout: 10), "The native share picker must receive the capability report")
        capture("132 native diagnostics share picker", app: app)
        let close = app.buttons["Close"].firstMatch
        if close.exists { close.tap() }
        else { app.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.1)).tap() }
        XCTAssertTrue(share.waitForExistence(timeout: 10))
        back(app)
        XCTAssertTrue(app.buttons["openDiagnostics"].waitForExistence(timeout: 10))
        #endif
    }
    private func waitUntil(timeout: TimeInterval = 10, _ condition: () -> Bool) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat { if condition() { return true }; Thread.sleep(forTimeInterval: 0.1) } while ProcessInfo.processInfo.systemUptime < deadline
        return condition()
    }
    private func back(_ app: XCUIApplication) {
        #if os(tvOS)
        XCUIRemote.shared.press(.menu)
        #else
        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.waitForExistence(timeout: 5)); back.tap()
        #endif
    }
    #if os(tvOS)
    private func assertTVLandingVisible(_ card: XCUIElement, app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        let viewport = app.scrollViews.firstMatch.frame.intersection(app.frame)
        let frame = card.frame
        XCTAssertGreaterThan(viewport.height, 0, file: file, line: line)
        if frame.height <= viewport.height {
            XCTAssertTrue(app.frame.contains(frame), "A fitting focused card must be completely on screen", file: file, line: line)
        } else {
            // Native focus grows the poster around its centre. Accept that
            // small visual outset while requiring the tall card's top to land
            // at the viewport top, rather than centring inaccessible content.
            let focusOutset = max(12, frame.height * 0.025)
            XCTAssertEqual(frame.minY, viewport.minY, accuracy: focusOutset, "A card taller than the viewport must display from its top", file: file, line: line)
            XCTAssertGreaterThan(frame.intersection(viewport).height, viewport.height * 0.8, file: file, line: line)
            XCTAssertGreaterThanOrEqual(frame.minX, app.frame.minX, file: file, line: line)
            XCTAssertLessThanOrEqual(frame.maxX, app.frame.maxX, file: file, line: line)
            print("LEG132_OVERSIZED_CARD_TOP frame=\(frame) viewport=\(viewport) nativeFocusOutset=\(focusOutset)")
        }
    }

    private func focus(_ element: XCUIElement, app: XCUIApplication) {
        var previousFrame: CGRect?
        var previousDirection: XCUIRemote.Button?
        func focused(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? {
            node.children.compactMap(focused).first ?? (node.hasFocus ? node : nil)
        }
        for _ in 0..<30 {
            if element.hasFocus { return }
            let current = (try? app.snapshot()).flatMap(focused)?.frame
            guard let current else { XCUIRemote.shared.press(.down); continue }
            let target = element.frame
            // Native Form rows own focus around their contained switch/button.
            // The row must visibly contain this exact target before Select.
            if current.contains(CGPoint(x: target.midX, y: target.midY)), current.height < max(1, target.height) * 3 { return }
            let dx = target.midX - current.midX, dy = target.midY - current.midY
            let overlaps = current.minX < target.maxX && target.minX < current.maxX
            let vertical: XCUIRemote.Button = dy < 0 ? .up : .down
            var direction: XCUIRemote.Button = abs(dy) > 20 && (overlaps || abs(dy) > abs(dx)) ? vertical : (dx < 0 ? .left : .right)
            if previousFrame == current, previousDirection == direction, abs(dy) > 20 { direction = vertical }
            previousFrame = current; previousDirection = direction
            XCUIRemote.shared.press(direction)
        }
        capture("132 failed native catalog focus", app: app)
        XCTFail("Could not focus native control \(element.identifier)")
    }
    #endif
    private func activate(_ element: XCUIElement, app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 15))
        #if os(tvOS)
        focus(element, app: app); XCUIRemote.shared.press(.select)
        #else
        reveal(element, app: app)
        element.tap()
        #endif
    }
    #if os(iOS)
    private func assertSingleLineTouchHeight(_ element: XCUIElement) {
        XCTAssertGreaterThanOrEqual(element.frame.height, 48, "\(element.identifier) must keep its true 48 pt touch target")
        XCTAssertLessThanOrEqual(element.frame.height, 52, "\(element.identifier) must not add native border padding around a 48 pt label")
    }
    /// A target may exist in an offscreen lazy row. Captures need the complete
    /// rendered action below navigation chrome, not just an existing AX node.
    private func reveal(_ element: XCUIElement, app: XCUIApplication) {
        XCTAssertTrue(element.waitForExistence(timeout: 15))
        for _ in 0..<16 {
            let window = app.windows.firstMatch.frame
            let target = element.frame
            let containers = (app.scrollViews.allElementsBoundByIndex + app.collectionViews.allElementsBoundByIndex)
                .filter { $0.isHittable && $0.frame.intersects(window) }
            let containing = containers.filter { $0.descendants(matching: .any).matching(identifier: element.identifier).firstMatch.exists }
            let scroll = containing.last ?? containers.last ?? app
            var viewport = scroll.frame.intersection(window)
            let navigation = app.navigationBars.firstMatch
            if navigation.exists { viewport.origin.y = max(viewport.minY, navigation.frame.maxY); viewport.size.height = max(0, scroll.frame.intersection(window).maxY - viewport.minY) }
            let tabs = app.tabBars.firstMatch
            if tabs.exists { viewport.size.height = max(0, min(viewport.maxY, tabs.frame.minY) - viewport.minY) }
            if element.isHittable && !target.isEmpty && (viewport.contains(target) || (target.height > viewport.height && target.intersects(viewport))) { return }
            let towardTop = target.isEmpty ? ["sortDirection", "librarySort"].contains(element.identifier) : target.midY < viewport.midY
            print("LEG132_CATALOG_REVEAL target=\(element.identifier) frame=\(target) towardTop=\(towardTop)")
            if towardTop { scroll.swipeDown() } else { scroll.swipeUp() }
        }
        capture("132 catalog reveal target unavailable", app: app)
        XCTFail("Could not reveal native control \(element.identifier)")
    }
    #endif
    private func capture(_ name: String, app: XCUIApplication) {
        let dump = XCTAttachment(string: app.debugDescription)
        dump.name = name + " accessibility"; dump.lifetime = .keepAlways; add(dump)
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        screenshot.name = name; screenshot.lifetime = .keepAlways; add(screenshot)
    }
}

private final class CatalogPresentationHTTPFixture {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pictureshow.catalog-presentation-fixture")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var pageHold = false
    private var episodeHold = false
    private var pages: [(NWConnection, Any)] = []
    private var episodes: [(NWConnection, Any)] = []
    private var pageCount = 0
    private var sort = ""
    private var direction = ""
    private var failuresRemaining = 0
    private var failedResponses = 0
    var address: String { "http://127.0.0.1:\(listener.port!.rawValue)" }
    var heldPages: Int { lock.lock(); defer { lock.unlock() }; return pages.count }
    var heldEpisodeReads: Int { lock.lock(); defer { lock.unlock() }; return episodes.count }
    var pageRequests: Int { lock.lock(); defer { lock.unlock() }; return pageCount }
    var lastSort: String { lock.lock(); defer { lock.unlock() }; return sort }
    var lastDirection: String { lock.lock(); defer { lock.unlock() }; return direction }
    var failedPageResponses: Int { lock.lock(); defer { lock.unlock() }; return failedResponses }
    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { state in if case .ready = state { ready.signal() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.connections.append(connection); self.lock.unlock()
            connection.start(queue: self.queue); self.receive(connection, bytes: Data())
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success else { throw NSError(domain: "CatalogFixture", code: 1) }
    }
    func holdNextPage(_ hold: Bool) { lock.lock(); pageHold = hold; lock.unlock() }
    func failNextPages(_ count: Int) { lock.lock(); failuresRemaining = count; lock.unlock() }
    func holdSeasonTwo(_ hold: Bool) { lock.lock(); episodeHold = hold; lock.unlock() }
    func releasePages() {
        lock.lock(); pageHold = false; let responses = pages; pages = []; lock.unlock()
        for (connection, body) in responses { respond(connection, body: body) }
    }
    func releaseSeasonTwo() {
        lock.lock(); episodeHold = false; let responses = episodes; episodes = []; lock.unlock()
        for (connection, body) in responses { respond(connection, body: body) }
    }
    func stop() {
        listener.cancel(); lock.lock(); let open = connections; connections = []; pages = []; episodes = []; lock.unlock()
        for connection in open { connection.cancel() }
    }
    private func receive(_ connection: NWConnection, bytes: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 32_768) { [weak self] chunk, _, complete, error in
            guard let self else { connection.cancel(); return }
            var bytes = bytes; if let chunk { bytes.append(chunk) }
            guard let separator = bytes.range(of: Data("\r\n\r\n".utf8)) else {
                if complete || error != nil { connection.cancel() } else { self.receive(connection, bytes: bytes) }; return
            }
            let lines = String(decoding: bytes[..<separator.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
            let words = (lines.first ?? "").split(separator: " ")
            guard words.count >= 2, let url = URL(string: "http://fixture" + words[1]) else { connection.cancel(); return }
            self.route(connection, url: url)
        }
    }
    private func route(_ connection: NWConnection, url: URL) {
        let query = Dictionary((URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []).map { ($0.name.lowercased(), $0.value ?? "") }, uniquingKeysWith: { _, value in value })
        let body: Any
        switch url.path {
        case "/System/Info/Public": body = ["ProductName": "Jellyfin Server", "Version": "12.0.0", "Id": "catalog-fixture", "ServerName": "Catalog fixture"]
        case "/Users/AuthenticateByName": body = ["AccessToken": "synthetic-catalog-token", "User": ["Id": "catalog-viewer", "Name": "Catalog fixture viewer"]]
        case "/QuickConnect/Enabled": body = false
        case "/Users/Public": body = [] as [String]
        case "/Users/Me", "/Users/catalog-viewer": body = ["Id": "catalog-viewer", "Name": "Catalog fixture viewer", "Configuration": ["SubtitleMode": "Default"]]
        case "/UserViews": body = ["Items": [["Id": "catalog-library", "Name": "Fixture library", "Type": "CollectionFolder", "IsFolder": true, "CollectionType": "tvshows"]]]
        case "/Items/Latest": body = [series()]
        case "/Items/catalog-series": body = series()
        case "/Shows/NextUp": body = ["Items": [episode(1)]]
        case "/Shows/catalog-series/Seasons": body = ["Items": [season(1), season(2)]]
        case "/Shows/catalog-series/Episodes":
            let number = query["seasonid"] == "catalog-season-2" ? 2 : 1
            body = ["Items": [episode(number)]]
            lock.lock(); let hold = episodeHold && number == 2
            if hold { episodes.append((connection, body)) }; lock.unlock()
            if hold { return }
        case "/Items":
            if let ids = query["ids"] {
                body = ["Items": ids.split(separator: ",").map { String($0) == "catalog-series" ? series() : movie(String($0)) }]
            } else {
                let start = Int(query["startindex"] ?? "0") ?? 0
                let all = [series()] + (1...11).map { movie("catalog-\($0)") }
                body = ["Items": Array(all.dropFirst(start).prefix(6)), "TotalRecordCount": all.count]
                lock.lock(); pageCount += 1; sort = query["sortby"] ?? ""; direction = query["sortorder"] ?? ""
                let fail = start > 0 && failuresRemaining > 0
                if fail { failuresRemaining -= 1; failedResponses += 1 }
                let hold = pageHold && start > 0
                if hold && !fail { pages.append((connection, body)) }; lock.unlock()
                if fail { respond(connection, body: ["Error": "Synthetic page unavailable"], status: "503 Service Unavailable"); return }
                if hold { return }
            }
        default: body = ["Items": [], "TotalRecordCount": 0]
        }
        respond(connection, body: body)
    }
    private func series() -> [String: Any] {
        ["Id": "catalog-series", "Name": "The fixture series with a long readable catalog title", "Type": "Series", "ProductionYear": 2026, "Overview": "Synthetic catalog data for native navigation and season retention.", "UserData": ["Played": false, "IsFavorite": false], "ChildCount": 2]
    }
    private func movie(_ id: String) -> [String: Any] {
        ["Id": id, "Name": "Fixture title \(id) with a long wrapping label", "Type": "Movie", "ProductionYear": 2026, "UserData": ["Played": false, "IsFavorite": false]]
    }
    private func season(_ number: Int) -> [String: Any] { ["Id": "catalog-season-\(number)", "Name": "Season \(number)", "Type": "Season", "SeriesId": "catalog-series", "IndexNumber": number] }
    private func episode(_ number: Int) -> [String: Any] { ["Id": "catalog-episode-\(number)", "Name": "Episode \(number)", "Type": "Episode", "SeriesId": "catalog-series", "SeasonId": "catalog-season-\(number)", "ParentIndexNumber": number, "IndexNumber": 1, "UserData": ["Played": false, "IsFavorite": false]] }
    private func respond(_ connection: NWConnection, body: Any, status: String = "200 OK") {
        guard let data = try? JSONSerialization.data(withJSONObject: body, options: [.fragmentsAllowed]) else { connection.cancel(); return }
        var response = Data("HTTP/1.1 \(status)\r\nContent-Type: application/json\r\nContent-Length: \(data.count)\r\nConnection: close\r\n\r\n".utf8)
        response.append(data)
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }
}
