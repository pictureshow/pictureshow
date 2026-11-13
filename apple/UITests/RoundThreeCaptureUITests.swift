// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import UIKit
import Network
import CryptoKit
#if os(tvOS)
import Vision
#endif

/// Select explicitly on a fresh owned app sandbox. Live art is
/// supplied by private pilot JSON; auth failures use only a loopback HTTP fixture.
final class RoundThreeCaptureUITests: XCTestCase {
    private var app: XCUIApplication!
    private var config: [String: String] = [:]
    private var landscapeAuthentication = false
    private var requestedLargeSettings = false
    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        if let value = ProcessInfo.processInfo.environment["PICTURESHOW_PILOT_JSON"] {
            config = try JSONDecoder().decode([String: String].self, from: Data(value.utf8))
        }
    }
    override func tearDownWithError() throws { app?.terminate() }

    @MainActor func testAuthenticationKeyboardErrorsAndQuickConnect() throws {
        let fixture = try RoundThreeAuthenticationFixture()
        defer { fixture.stop() }
        launch(server: fixture.address, username: "Round three fixture viewer", password: "synthetic-invalid-password")
        #if os(iOS)
        if landscapeAuthentication {
            XCTAssertTrue(wait(10) { self.app.windows.firstMatch.frame.width > self.app.windows.firstMatch.frame.height })
        }
        #endif
        let address = app.textFields["serverAddress"]
        XCTAssertTrue(address.waitForExistence(timeout: 10), "Require the fresh address route, not a remembered account")
        assertViewerCopy()
        record("Round3 authentication server address")
        #if os(iOS)
        let title = app.staticTexts["Pictureshow"].firstMatch
        let closedTitle = title.frame
        let closedAddress = address.frame
        #endif
        activate(address)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        record("Round3 authentication address keyboard")
        #if os(iOS)
        XCTAssertEqual(title.frame.height, closedTitle.height, accuracy: 2, "Opening the address keyboard must not switch typography tokens")
        XCTAssertEqual(title.frame.minX, closedTitle.minX, accuracy: 2, "Opening the address keyboard must not switch the header gutter")
        XCTAssertEqual(title.frame.width, closedTitle.width, accuracy: 2, "Opening the address keyboard must retain the header width")
        XCTAssertEqual(address.frame.minX, closedAddress.minX, accuracy: 2, "Opening the address keyboard must not switch the form gutter")
        XCTAssertEqual(address.frame.width, closedAddress.width, accuracy: 2, "Opening the address keyboard must retain the form width")
        address.typeText("\n") // Native address Go submits Connect once.
        #else
        dismissKeyboard()
        activate(app.buttons["connect"])
        #endif
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 25))
        let username = app.textFields["username"]
        let beforeField = username.frame
        #if os(tvOS)
        dismissKeyboard()
        record("Round3 authentication password form keyboard dismissed")
        #endif
        let password = app.secureTextFields["password"]
        activate(password)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        record("Round3 authentication password keyboard")
        #if os(iOS)
        XCTAssertEqual(title.frame.height, closedTitle.height, accuracy: 2, "The password keyboard must retain the closed address route typography token")
        XCTAssertEqual(username.frame.minX, beforeField.minX, accuracy: 2, "The keyboard must not switch the form gutter")
        XCTAssertEqual(username.frame.width, beforeField.width, accuracy: 2, "Keyboard-safe scrolling must retain form width")
        let submit = app.buttons["signIn"]
        let keyboard = app.keyboards.firstMatch
        XCTAssertTrue(submit.isHittable, "The focused password route must expose its real submit action above the keyboard")
        XCTAssertLessThanOrEqual(submit.frame.maxY, keyboard.frame.minY + 1)
        let show = app.buttons["Show password"]
        let done = app.buttons["dismissSignInKeyboard"]
        if done.exists && !done.frame.isEmpty {
            XCTAssertFalse(done.frame.intersects(show.frame), "Keyboard chrome must not overlap Show password")
            XCTAssertFalse(done.frame.intersects(submit.frame), "Keyboard chrome must not overlap Sign in")
        }
        XCTAssertLessThanOrEqual(submit.frame.height, 64, "Primary auth control should fit the ordinary touch scale")
        // Exercise the native password Go/Return submit, not a launch hook.
        password.typeText("\n")
        #else
        dismissKeyboard()
        activate(app.buttons["signIn"])
        #endif
        XCTAssertTrue(app.staticTexts["signInError"].waitForExistence(timeout: 30))
        XCTAssertFalse(app.staticTexts["signInError"].label.isEmpty)
        XCTAssertEqual(fixture.authenticationRequests, 1, "The error must follow an actual rejected HTTP authentication")
        XCTAssertTrue(app.buttons["signIn"].isEnabled)
        record("Round3 authentication wrong password recoverable error")
        dismissKeyboard()
        activate(app.buttons["quickConnect"])
        let code = app.staticTexts["quickCode"]
        XCTAssertTrue(code.waitForExistence(timeout: 15))
        XCTAssertEqual(fixture.quickInitiations, 1)
        record("Round3 Quick Connect code and instruction")
        #if os(tvOS)
        let heading = app.staticTexts["Quick Connect"].firstMatch
        XCTAssertTrue(heading.exists)
        XCTAssertGreaterThanOrEqual(code.frame.height, heading.frame.height * 1.8, "The sofa-readable code must dominate its heading")
        #endif
        focusOnly(app.buttons["cancelQuick"])
        record("Round3 Quick Connect Use password focused safe area")
        #if os(tvOS)
        let safe = app.frame.insetBy(dx: 60, dy: 60)
        XCTAssertTrue(safe.contains(code.frame), "Native focus scrolling must keep the entire code inside TV title-safe bounds")
        XCTAssertTrue(safe.contains(app.staticTexts["Quick Connect"].firstMatch.frame))
        #endif
        activate(app.buttons["cancelQuick"])
        XCTAssertTrue(code.waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["signIn"].exists)
        record("Round3 Quick Connect cancelled password route retained")
        assertViewerCopy()
    }

    #if os(iOS)
    @MainActor func testPhoneLandscapeAuthenticationKeepsKeyboardGeometryAndGo() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        landscapeAuthentication = true
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { landscapeAuthentication = false; XCUIDevice.shared.orientation = .portrait }
        try testAuthenticationKeyboardErrorsAndQuickConnect()
    }
    #endif

    @MainActor func testUnreachableServerHasRecoverableError() throws {
        let fixture = try RoundThreeAuthenticationFixture()
        let address = fixture.address
        fixture.stop() // Actual connection refusal at this case's own closed listener.
        launch(server: address, username: "Round three fixture viewer", password: "synthetic-invalid-password")
        XCTAssertTrue(app.textFields["serverAddress"].waitForExistence(timeout: 10))
        activate(app.buttons["connect"])
        XCTAssertTrue(app.staticTexts["signInError"].waitForExistence(timeout: 30))
        XCTAssertTrue(app.textFields["serverAddress"].exists && app.buttons["connect"].isEnabled)
        XCTAssertFalse(app.staticTexts["signInError"].label.isEmpty)
        record("Round3 unreachable server error address recoverable")
        assertViewerCopy()
    }

    @MainActor func testNativeDestinationsRealArtworkPeopleAndEmptyStates() throws {
        try launchPilot()
        try signInPilot()
        #if os(tvOS)
        // Restore the same legitimate signed-in app, with no sandbox reset or
        // navigation/refocus input before the initial launch-focus evidence.
        app.terminate(); app.launch()
        #endif
        XCTAssertTrue(app.buttons["item-" + (try required("resumeID"))].firstMatch.waitForExistence(timeout: 20))
        XCTAssertTrue(app.staticTexts["Continue watching"].exists)
        XCTAssertFalse(app.buttons["signOut"].exists, "Account actions belong in Settings, not initial Home")
        XCTAssertFalse(app.buttons["openSearch"].exists, "The duplicate Home toolbar must be removed")
        XCTAssertFalse(app.buttons["openSettings"].exists)
        record("Round3 Home initial focus real artwork")
        #if os(tvOS)
        XCTAssertTrue(nativeDestinationIsVisible("Home"), "Actual launch must restore Home content before navigation input")
        _ = try nativeTab("Home")
        #else
        let homeStates = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", "Home")).allElementsBoundByIndex
            .filter { ($0.elementType == .button || $0.elementType == .cell) && $0.isSelected }
        XCTAssertFalse(homeStates.isEmpty, "Actual launch must restore the selected native Home destination before navigation input")
        #endif
        #if os(tvOS)
        let launchFocus = try XCTUnwrap(app.descendants(matching: .any).allElementsBoundByIndex.first(where: \.hasFocus))
        XCTAssertTrue(launchFocus.frame.intersects(app.frame))
        XCTAssertFalse(["switchUser", "signOut"].contains(launchFocus.identifier))
        for name in ["Home", "Libraries", "Search", "Settings"] {
            revealNativeTabBar()
            let tab = try nativeTab(name)
            focusOnly(tab)
            XCTAssertTrue(ownsFocus(tab), "A real remote must focus the native tab: " + name)
            XCTAssertLessThan(tab.frame.height, app.frame.height * 0.25)
            XCTAssertLessThan(tab.frame.width, app.frame.width * 0.5)
            record("Round3 native TV tab " + name + " focused whole label")
            try assertRenderedWords(name, in: tab)
            // Focusing a native tvOS tab activates its destination. An extra
            // Select after capture can instead activate the newly focused body.
            let changed = wait(10) { self.nativeDestinationIsVisible(name) }
            if !changed { record("Round3 native TV tab " + name + " failed destination actual state") }
            print("ROUND3_NATIVE_TAB_DESTINATION name=\(name) selectedFlag=\(tab.isSelected) actualDestination=\(changed)")
            XCTAssertTrue(changed, "Real native remote focus must show the exact destination")
            leaveNativeTabBarForContent()
        }
        #endif
        try selectTab("Libraries")
        let ids = try required("libraryIDs").split(separator: ",").map(String.init)
        XCTAssertGreaterThanOrEqual(ids.count, 3, "Use the prepared real three-library demo, not a one-tile substitute")
        let cards = ids.map { app.buttons["item-" + $0].firstMatch }
        XCTAssertTrue(cards[0].waitForExistence(timeout: 20))
        XCTAssertFalse(app.staticTexts["Your libraries"].exists, "A destination page must not repeat the Home-shelf subheading")
        #if os(iOS)
        for card in cards {
            XCTAssertTrue(card.exists && app.windows.firstMatch.frame.contains(card.frame), "Every prepared library must fit the initial adaptive grid")
        }
        XCTAssertEqual(cards[0].frame.width, cards[1].frame.width, accuracy: 2)
        #endif
        record("Round3 Libraries adaptive destination grid")
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        let artwork = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        XCTAssertTrue(artwork.waitForExistence(timeout: 20))
        reveal(artwork)
        record("Round3 real artwork library titles")
        activate(artwork)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["play"].exists)
        record("Round3 real artwork detail hero and actions")
        XCTAssertTrue(app.windows.firstMatch.frame.intersects(app.staticTexts["detailTitle"].frame))
        // The full-bleed backdrop is genuinely decorative/AX-hidden. Preserve
        // full native pixels + actual title/action frames for director judgment;
        // never fabricate a hidden image node as hero-geometry evidence.
        try assertPeopleShelf()
        try selectTab("Search")
        let field = app.textFields["searchField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        record("Round3 Search native initial state")
        activate(field)
        field.typeText(try required("searchQuery"))
        XCTAssertTrue(app.buttons["item-" + (try required("artworkItemID"))].firstMatch.waitForExistence(timeout: 20))
        dismissKeyboard()
        settledCapture("Round3 Search settled real artwork results")
        try clearSearch(field)
        activate(field)
        field.typeText("pictureshow-round-three-no-match")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "No results for")).firstMatch.waitForExistence(timeout: 20))
        dismissKeyboard()
        settledCapture("Round3 Search settled no results echoes query")
        try returnToLibrariesRootForEmptyState()
        activate(app.buttons["item-" + (try required("emptyLibraryID"))].firstMatch)
        XCTAssertTrue(app.staticTexts["No titles are available."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Refresh"].exists)
        XCTAssertFalse(node("librarySort").exists)
        #if os(tvOS)
        XCTAssertFalse(node("sort-SortName").exists)
        #endif
        record("Round3 empty library recovery without meaningless sort")
    }

    #if os(tvOS)
    @MainActor func testTVSettingsHeaderAndFooterActualPixelsMeetAA() throws {
        try launchPilot(); try signInPilot(); try selectTab("Settings")
        let bitrate = node("maxBitrate")
        if !ownsFocus(bitrate) { focusOnly(bitrate) }
        record("Round3 Settings content focus before AA")
        XCTAssertTrue(ownsFocus(bitrate), "Measure undimmed Settings after native focus actually leaves the tab bar")
        let header = app.staticTexts["playbackSectionHeader"]
        let footer = app.staticTexts["audioOutputExplanation"]
        XCTAssertTrue(header.waitForExistence(timeout: 10) && footer.exists)
        print("ROUND3_SETTINGS_METRICS headerHeight=\(header.frame.height) footerHeight=\(footer.frame.height) requestedLarge=\(requestedLargeSettings)")
        if requestedLargeSettings {
            let baseline = try XCTUnwrap(Double(try required("baselineSettingsHeaderHeight")))
            XCTAssertGreaterThan(header.frame.height, baseline * 1.05, "A requested app-font projection needs actual rendered type increase before large-layout qualification")
        }
        record("Round3 Settings root explicit header footer ink")
        try assertTextAA(header, expected: "Playback", name: "Round3 Settings Playback header AA")
        try assertTextAA(footer, expected: "Apple TV chooses", name: "Round3 Settings audio footer AA")
        XCTAssertTrue(app.buttons["openAccounts"].exists)
    }
    @MainActor func testTVRequestedLargeSettingsHeaderAndFooterAA() throws {
        requestedLargeSettings = true
        defer { requestedLargeSettings = false }
        app.launchArguments += ["-UIPreferredContentSizeCategoryName", UIContentSizeCategory.accessibilityExtraExtraExtraLarge.rawValue]
        try testTVSettingsHeaderAndFooterActualPixelsMeetAA()
    }
    #endif

    #if os(iOS)
    @MainActor func testIPadLandscapeAndPointerArtworkWithoutActivation() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try launchPilot(); try signInPilot()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(wait(10) { self.app.windows.firstMatch.frame.width > self.app.windows.firstMatch.frame.height })
        try selectTab("Libraries")
        record("Round3 iPad landscape Libraries grid")
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        let poster = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        XCTAssertTrue(poster.waitForExistence(timeout: 20)); reveal(poster)
        let before = app.windows.firstMatch.frame
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).hover()
        record("Round3 iPad actual pointer away from artwork")
        poster.hover()
        record("Round3 iPad actual pointer over artwork no activation")
        XCTAssertFalse(app.staticTexts["detailTitle"].exists)
        XCTAssertEqual(app.windows.firstMatch.frame, before)
        activate(poster)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        record("Round3 iPad landscape real artwork detail")
        try assertPeopleShelf(prefix: "Round3 iPad landscape")
        // Simulator event dispatch is not physical visible-hover qualification.
    }

    @MainActor func testIPadActualOSLargeTextLibrariesAndDetail() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        try assertActualOSLargeTextLibrariesAndDetail(prefix: "Round3 iPad actual OS XXXL")
    }
    @MainActor func testIPhoneActualOSLargeTextLibrariesAndDetail() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone)
        try assertActualOSLargeTextLibrariesAndDetail(prefix: "Round3 iPhone actual OS XXXL")
    }
    @MainActor private func assertActualOSLargeTextLibrariesAndDetail(prefix: String) throws {
        XCTAssertEqual(UIApplication.shared.preferredContentSizeCategory, .accessibilityExtraExtraExtraLarge,
                       "Runner must set/read back actual owned device OS XXXL; a launch projection is not this evidence")
        try launchPilot(); try signInPilot(); try selectTab("Libraries")
        record(prefix + " Libraries grid")
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        let item = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        XCTAssertTrue(item.waitForExistence(timeout: 20)); reveal(item); activate(item)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        let startOver = app.buttons["startOver"]
        let remote = app.buttons["playOnDevice"]
        XCTAssertTrue(startOver.exists && remote.exists)
        XCTAssertEqual(startOver.frame.height, remote.frame.minY - startOver.frame.minY - 12, accuracy: 2,
                       "At OS XXXL, the start-over AX target must fill its actual reserved pill height, leaving only the 12pt action gap")
        if UIDevice.current.userInterfaceIdiom == .phone { try revealActualDetailTop() }
        record(prefix + " detail actions")
        try assertPeopleShelf(prefix: prefix)
    }
    #endif

    @MainActor private func assertPeopleShelf(prefix: String = "Round3") throws {
        let names = try required("personLabels").split(separator: "|").map(String.init)
        XCTAssertGreaterThanOrEqual(names.count, 3, "Actual demo metadata must contain a real multi-person shelf")
        let roles = try required("personRoles").split(separator: "|").map(String.init)
        XCTAssertEqual(roles.count, 3)
        let cards = names.prefix(3).map { name in app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", name)).firstMatch }
        #if os(iOS)
        try revealFullPersonMetadata(cards[0], name: names[0], role: roles[0])
        #else
        reveal(cards[0])
        #endif
        XCTAssertTrue(cards.allSatisfy(\.exists))
        for (card, role) in zip(cards, roles) {
            XCTAssertTrue(card.label.contains(role), "Actual person action must retain its role, including an empty-role type fallback")
        }
        // Compare stationary centres, not focused-card top-edge lift.
        XCTAssertEqual(cards[0].frame.midY, cards[1].frame.midY, accuracy: 3, "People must form a horizontal shelf, not a ragged vertical stack")
        XCTAssertEqual(cards[1].frame.midY, cards[2].frame.midY, accuracy: 3)
        XCTAssertEqual(cards[1].frame.width, cards[2].frame.width, accuracy: 3, "Unfocused person lockups must have equal width")
        record(prefix + " cast and crew horizontal equal lockups")
        let shelf = node("detailPeopleShelf")
        XCTAssertTrue(shelf.exists)
        #if os(tvOS)
        focusOnly(cards[0]); XCUIRemote.shared.press(.right); XCUIRemote.shared.press(.right)
        XCTAssertTrue(ownsFocus(cards[2]), "Two native horizontal moves must reach the third actual person")
        #else
        shelf.swipeLeft()
        try revealFullPersonMetadata(cards[2], name: names[2], role: roles[2])
        XCTAssertTrue(cards[2].isHittable && cards[2].frame.intersects(app.windows.firstMatch.frame))
        #endif
        XCTAssertTrue(app.staticTexts["detailTitle"].exists, "Moving the people shelf cannot activate a different route")
        record(prefix + " cast and crew native horizontal traversal")
    }

    #if os(iOS)
    @MainActor private func actualDetailScroll() throws -> XCUIElement {
        try XCTUnwrap(app.scrollViews.allElementsBoundByIndex.first {
            $0.staticTexts["detailTitle"].exists && $0.descendants(matching: .any).matching(identifier: "detailPeopleShelf").firstMatch.exists
        }, "Require the actual detail scroll container, not a frame override")
    }
    @MainActor private func peopleUsableViewport() throws -> CGRect {
        let window = app.windows.firstMatch.frame
        let scroll = try actualDetailScroll()
        let clips = scroll.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "Vertical scroll bar"))
            .allElementsBoundByIndex.filter { $0.exists && !$0.frame.isEmpty && window.contains($0.frame) }
        // These real native scroll-indicator frames expose the content clip,
        // including the iPad safe-area bottom that has no bottom TabBar node.
        let clip = try XCTUnwrap(clips.first, "Require native detail viewport geometry before claiming visible metadata").frame
        let shelf = node("detailPeopleShelf").frame
        var viewport = window.intersection(scroll.frame)
        let left = max(viewport.minX, shelf.minX), right = min(viewport.maxX, shelf.maxX)
        var top = max(viewport.minY, clip.minY), bottom = min(viewport.maxY, clip.maxY)
        for bar in app.navigationBars.allElementsBoundByIndex where bar.exists && !bar.frame.isEmpty && bar.frame.width > viewport.width * 0.5 {
            if bar.frame.minY <= viewport.midY { top = max(top, bar.frame.maxY) }
        }
        for bar in app.tabBars.allElementsBoundByIndex where bar.exists && !bar.frame.isEmpty && bar.frame.width > viewport.width * 0.5 {
            if bar.frame.minY > viewport.midY { bottom = min(bottom, bar.frame.minY) }
            else { top = max(top, bar.frame.maxY) }
        }
        viewport = CGRect(x: left, y: top, width: right - left, height: bottom - top)
        XCTAssertFalse(viewport.isEmpty, "Native chrome must leave a real people-content viewport")
        return viewport
    }
    @MainActor private func revealFullPersonMetadata(_ card: XCUIElement, name: String, role: String) throws {
        let nameText = card.staticTexts[name].firstMatch, roleText = card.staticTexts[role].firstMatch
        XCTAssertTrue(card.exists && nameText.exists && roleText.exists, "Require the actual name and role StaticTexts, not the button's combined accessibility label")
        for _ in 0..<24 { // Same bounded real-scroll budget as existing reveal.
            let viewport = try peopleUsableViewport()
            let target = card.frame.union(nameText.frame).union(roleText.frame)
            if !target.isEmpty && viewport.contains(target) { break }
            // Reveal the actual complete lockup with native pans. A full
            // horizontal swipe can overshoot a narrow tile; correct that real
            // offset instead of accepting a clipped name or changing its frame.
            let translation: CGVector
            if target.maxY > viewport.maxY { translation = CGVector(dx: 0, dy: -min(target.maxY - viewport.maxY + 8, viewport.height * 0.4)) }
            else if target.minY < viewport.minY { translation = CGVector(dx: 0, dy: min(viewport.minY - target.minY + 8, viewport.height * 0.4)) }
            else if target.minX < viewport.minX { translation = CGVector(dx: min(viewport.minX - target.minX + 8, viewport.width * 0.4), dy: 0) }
            else if target.maxX > viewport.maxX { translation = CGVector(dx: -min(target.maxX - viewport.maxX + 8, viewport.width * 0.4), dy: 0) }
            else { break }
            let window = app.windows.firstMatch
            let origin = window.coordinate(withNormalizedOffset: .zero)
            let startY = translation.dx == 0 ? viewport.midY : min(max(target.midY, viewport.minY + 16), viewport.maxY - 16)
            let start = origin.withOffset(CGVector(dx: viewport.midX - window.frame.minX, dy: startY - window.frame.minY))
            let end = start.withOffset(translation)
            start.press(forDuration: 0.05, thenDragTo: end)
        }
        let viewport = try peopleUsableViewport()
        print("ROUND3_PEOPLE_VISIBLE name=\(name) role=\(role) card=\(card.frame) nameFrame=\(nameText.frame) roleFrame=\(roleText.frame) nativeViewport=\(viewport)")
        let fullyVisible = !card.frame.isEmpty && !nameText.frame.isEmpty && !roleText.frame.isEmpty
            && viewport.contains(card.frame) && viewport.contains(nameText.frame) && viewport.contains(roleText.frame)
        if !fullyVisible { record("Round3 people full metadata visibility failed actual state") }
        XCTAssertTrue(fullyVisible, "The entire person, real name and role must fit inside the native content viewport above chrome before capture")
    }
    @MainActor private func revealActualDetailTop() throws {
        let scroll = try actualDetailScroll()
        func atTop() -> Bool {
            scroll.otherElements.matching(NSPredicate(format: "label BEGINSWITH %@", "Vertical scroll bar"))
                .allElementsBoundByIndex.contains { $0.exists && ($0.value as? String) == "0%" }
        }
        for _ in 0..<8 {
            if atTop() { break }
            scroll.swipeDown()
        }
        let top = wait(10) { atTop() }
        if !top { record("Round3 actual detail top not reached") }
        XCTAssertTrue(top, "A real native scroll must reach the actual 0% top before the phone hero capture")
        print("ROUND3_DETAIL_TOP actualNativeScrollValue=0% titleFrame=\(app.staticTexts["detailTitle"].frame)")
    }
    #endif

    @MainActor private func launch(server: String, username: String, password: String) {
        app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launchEnvironment["PICTURESHOW_TEST_SERVER"] = server
        app.launchEnvironment["PICTURESHOW_TEST_USERNAME"] = username
        app.launchEnvironment["PICTURESHOW_TEST_PASSWORD"] = password
        app.launch()
    }
    @MainActor private func launchPilot() throws { launch(server: try required("server"), username: try required("username"), password: try required("password")) }
    private func required(_ key: String) throws -> String { try XCTUnwrap(config[key], "Missing private Round3 pilot field: " + key) }
    private func node(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    @MainActor private func signInPilot() throws {
        XCTAssertTrue(app.buttons["connect"].waitForExistence(timeout: 10), "Every selected case must start with a genuinely fresh app sandbox")
        activate(app.buttons["connect"])
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 25))
        #if os(iOS)
        let password = app.secureTextFields["password"]
        activate(password)
        XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        password.typeText("\n") // Actual native password Go submits Sign in once.
        #else
        dismissKeyboard(); activate(app.buttons["signIn"])
        #endif
        let resumeID = try required("resumeID")
        let home = wait(30) {
            self.app.staticTexts["Continue watching"].exists && self.app.buttons["item-" + resumeID].firstMatch.exists
        }
        if !home { record("Round3 sign-in failed actual native state") }
        XCTAssertTrue(home, "Actual pilot authentication must reach the native shell")
    }
    #if os(tvOS)
    private func visibleNativeTabBarOwning(_ element: XCUIElement) -> XCUIElement? {
        let window = app.windows.firstMatch.frame, target = element.frame
        guard !target.isEmpty && window.contains(target) else { return nil }
        return app.tabBars.allElementsBoundByIndex.first { bar in
            !bar.frame.isEmpty && window.contains(bar.frame) && bar.frame.contains(target)
                && bar.descendants(matching: .any).matching(NSPredicate(format: "label == %@ AND identifier == %@", element.label, element.identifier))
                    .allElementsBoundByIndex.contains(where: { $0.frame == target && ($0.elementType == .button || $0.elementType == .cell) })
        }
    }
    #endif
    private func nativeTabCandidate(_ name: String) -> XCUIElement? {
        let window = app.windows.firstMatch.frame
        let candidates = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", name)).allElementsBoundByIndex.filter { candidate in
            guard candidate.elementType == .button || candidate.elementType == .cell else { return false }
            #if os(tvOS)
            let bar = visibleNativeTabBarOwning(candidate)
            let eligible = bar != nil
            print("ROUND3_NATIVE_TAB name=\(name) role=\(candidate.elementType.rawValue) frame=\(candidate.frame) hittable=\(candidate.isHittable) focused=\(candidate.hasFocus) selected=\(candidate.isSelected) nativeBar=\(String(describing: bar?.frame)) eligible=\(eligible)")
            return eligible
            #else
            return candidate.isHittable && !candidate.frame.isEmpty && candidate.frame.intersects(window)
            #endif
        }
        // Native sidebar/tab modes can expose hidden duplicates. Prefer actual selection.
        return candidates.first(where: \.isSelected) ?? candidates.first
    }
    @MainActor private func nativeTab(_ name: String) throws -> XCUIElement {
        let candidate = nativeTabCandidate(name)
        if candidate == nil { record("Round3 missing native tab actual state " + name) }
        return try XCTUnwrap(candidate, "No visible native tab: " + name)
    }
    @MainActor private func revealNativeTabBar() {
        #if os(tvOS)
        for _ in 0..<8 {
            if nativeTabCandidate("Home") != nil { return }
            XCUIRemote.shared.press(.up)
        }
        #endif
    }
    @MainActor private func returnToLibrariesRootForEmptyState() throws {
        // Native tabs retain their NavigationStack. This final empty-state
        // setup must pop the previously visited detail/library routes itself.
        revealNativeTabBar()
        let tab = try nativeTab("Libraries")
        #if os(tvOS)
        focusOnly(tab)
        let returned = wait(10) {
            self.nativeDestinationIsVisible("Libraries")
                || (self.app.staticTexts["detailTitle"].exists && self.app.buttons["play"].exists)
                || self.app.navigationBars.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "BackButton", "Back")).firstMatch.exists
        }
        if !returned { record("Round3 Libraries retained destination not reached after native tab focus") }
        XCTAssertTrue(returned, "Native Libraries focus must restore its actual root or retained detail/Back route before Menu")
        #else
        activate(tab)
        XCTAssertTrue(wait(10) { tab.isSelected }, "Return to the actual Libraries tab before popping its retained stack")
        #endif
        let ids = try required("libraryIDs").split(separator: ",").map(String.init)
        XCTAssertGreaterThanOrEqual(ids.count, 3)
        func rootHeadingVisible() -> Bool {
            app.navigationBars.allElementsBoundByIndex.contains { bar in
                let title = bar.staticTexts["Libraries"].firstMatch
                return bar.exists && !bar.frame.isEmpty && title.exists && !title.frame.isEmpty
                    && app.windows.firstMatch.frame.contains(title.frame)
            }
        }
        func rootVisible() -> Bool {
            rootHeadingVisible() && !app.staticTexts["Continue watching"].exists
                && ids.allSatisfy { app.buttons["item-" + $0].firstMatch.exists }
        }
        for _ in 0..<4 {
            if rootVisible() { return }
            if rootHeadingVisible() { break } // A root with loading content needs no Back input.
            let backs = app.navigationBars.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "BackButton", "Back"))
                .allElementsBoundByIndex.filter { $0.exists && !$0.frame.isEmpty && self.app.windows.firstMatch.frame.contains($0.frame) }
            #if os(tvOS)
            let retainedDetail = app.staticTexts["detailTitle"].exists && app.buttons["play"].exists
            if backs.isEmpty && !retainedDetail { record("Round3 Libraries retained stack missing actual Back state") }
            XCTAssertTrue(!backs.isEmpty || retainedDetail, "A real retained detail or native Back control must precede Menu")
            guard !backs.isEmpty || retainedDetail else { return }
            XCUIRemote.shared.press(.menu)
            #else
            let visibleBacks = backs.filter(\.isHittable)
            if visibleBacks.count != 1 { record("Round3 Libraries retained stack missing actual Back state") }
            XCTAssertEqual(visibleBacks.count, 1, "Require the actual unique native Back control before popping")
            let back = try XCTUnwrap(visibleBacks.first)
            back.tap()
            #endif
        }
        let reached = wait(10) { rootVisible() }
        if !reached { record("Round3 Libraries root after native Back failed actual state") }
        XCTAssertTrue(reached, "At most four native Back/Menu inputs must restore the real Libraries heading and all three prepared library cards")
    }
    @MainActor private func selectTab(_ name: String) throws {
        revealNativeTabBar()
        let tab = try nativeTab(name)
        #if os(tvOS)
        focusOnly(tab)
        let changed = wait(10) { self.nativeDestinationIsVisible(name) }
        if !changed { record("Round3 native TV tab " + name + " failed destination actual state") }
        print("ROUND3_NATIVE_TAB_DESTINATION name=\(name) selectedFlag=\(tab.isSelected) actualDestination=\(changed)")
        XCTAssertTrue(changed, "Real native remote focus must show the exact destination")
        leaveNativeTabBarForContent()
        #else
        activate(tab)
        XCTAssertTrue(wait(10) { tab.isSelected }, "Native selected state must match actual destination")
        #endif
        if name == "Settings" { XCTAssertTrue(node("settingsTitle").waitForExistence(timeout: 10)) }
        if name == "Home" { XCTAssertTrue(app.staticTexts["Continue watching"].waitForExistence(timeout: 20)) }
    }
    #if os(tvOS)
    @MainActor private func leaveNativeTabBarForContent() {
        func deepest(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? { node.children.compactMap(deepest).first ?? (node.hasFocus ? node : nil) }
        func contentOwnsFocus() -> Bool {
            guard let owner = (try? app.snapshot()).flatMap(deepest), !owner.frame.isEmpty,
                  app.windows.firstMatch.frame.contains(owner.frame) else { return false }
            return !app.tabBars.allElementsBoundByIndex.contains(where: { !$0.frame.isEmpty && $0.frame.contains(owner.frame) })
        }
        for _ in 0..<8 {
            if contentOwnsFocus() { return }
            XCUIRemote.shared.press(.down)
        }
        record("Round3 failed native tab to content focus actual state")
        XCTAssertTrue(contentOwnsFocus(), "A real Down input must transfer focus out of the native tab bar before undimmed content proof")
    }
    private func nativeDestinationIsVisible(_ name: String) -> Bool {
        let window = app.windows.firstMatch.frame
        func visible(_ element: XCUIElement) -> Bool { element.exists && !element.frame.isEmpty && window.contains(element.frame) }
        switch name {
        case "Home":
            guard let id = config["resumeID"] else { return false }
            return visible(app.staticTexts["Continue watching"]) && app.buttons["item-" + id].firstMatch.exists
        case "Libraries":
            guard let ids = config["libraryIDs"]?.split(separator: ","), ids.count >= 3 else { return false }
            let heading = app.navigationBars.allElementsBoundByIndex.contains { bar in
                let title = bar.staticTexts["Libraries"].firstMatch
                return visible(bar) && visible(title)
            }
            return heading && !app.staticTexts["Continue watching"].exists && ids.allSatisfy { app.buttons["item-" + $0].firstMatch.exists }
        case "Search": return visible(app.textFields["searchField"])
        case "Settings": return visible(node("settingsTitle")) && node("maxBitrate").exists
        default: return false
        }
    }
    #endif
    private func ownsFocus(_ element: XCUIElement) -> Bool {
        func contains(_ node: XCUIElementSnapshot) -> Bool { node.hasFocus || node.children.contains(where: contains) }
        return element.hasFocus || (try? element.snapshot()).map(contains) == true
    }
    @MainActor private func focusOnly(_ element: XCUIElement) {
        reveal(element)
        #if os(tvOS)
        func deepest(_ node: XCUIElementSnapshot) -> XCUIElementSnapshot? { node.children.compactMap(deepest).first ?? (node.hasFocus ? node : nil) }
        for _ in 0..<45 {
            if ownsFocus(element) { return }
            guard let current = (try? app.snapshot()).flatMap(deepest)?.frame else { XCUIRemote.shared.press(.up); continue }
            let target = element.frame
            if target.maxY <= current.minY + 12 { XCUIRemote.shared.press(.up) }
            else if target.minY >= current.maxY - 12 { XCUIRemote.shared.press(.down) }
            else { XCUIRemote.shared.press(target.midX < current.midX ? .left : .right) }
        }
        record("Round3 failed native focus actual state")
        XCTFail("Native remote did not focus the actual element: " + element.label)
        #endif
    }
    @MainActor private func activate(_ element: XCUIElement) {
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        focusOnly(element); guard ownsFocus(element) else { return }; XCUIRemote.shared.press(.select)
        #else
        reveal(element); XCTAssertTrue(element.isHittable); element.tap()
        #endif
    }
    @MainActor private func reveal(_ element: XCUIElement) {
        for _ in 0..<24 {
            #if os(tvOS)
            // Native TV tabs are remote targets, not touch hit points. This is
            // restricted to the actual visible native bar; focus still must move.
            if element.exists && visibleNativeTabBarOwning(element) != nil { return }
            #endif
            if element.exists && element.isHittable && element.frame.intersects(app.windows.firstMatch.frame) { return }
            #if os(tvOS)
            XCUIRemote.shared.press(.down)
            #else
            app.swipeUp()
            #endif
        }
    }
    @MainActor private func dismissKeyboard() {
        #if os(tvOS)
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #else
        if app.keyboards.firstMatch.exists {
            let done = app.buttons["dismissSignInKeyboard"]
            if done.exists && !done.frame.isEmpty && done.frame.intersects(app.frame) && done.isHittable { done.tap() }
            else if app.keyboards.buttons["Search"].exists { app.keyboards.buttons["Search"].tap() }
            else { app.swipeDown() }
        }
        #endif
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5))
    }
    @MainActor private func clearSearch(_ field: XCUIElement) throws {
        let clear = app.buttons["clearSearch"].exists ? app.buttons["clearSearch"] : field.buttons["Clear text"]
        XCTAssertTrue(clear.exists, "Use the actual custom/native clear control, never reset state by relaunch")
        activate(clear)
    }
    @MainActor private func settledCapture(_ name: String) {
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        // Keep the explicit render settlement demanded after the observed native
        // TV keyboard fade. No product/readiness deadline is extended.
        RunLoop.current.run(until: Date().addingTimeInterval(1.1))
        XCTAssertFalse(app.keyboards.firstMatch.exists)
        record(name)
    }
    @MainActor private func assertViewerCopy() {
        let text = app.staticTexts.allElementsBoundByIndex.map(\.label).joined(separator: " ").lowercased()
        for forbidden in ["unsigned build", "server hls", "stream index", "stream 0", "loading your library..."] {
            XCTAssertFalse(text.contains(forbidden), "Viewer-facing copy contains implementation wording: " + forbidden)
        }
    }
    @MainActor private func wait(_ seconds: TimeInterval, _ condition: () -> Bool) -> Bool {
        let end = ProcessInfo.processInfo.systemUptime + seconds
        repeat { if condition() { return true }; RunLoop.current.run(until: Date().addingTimeInterval(0.1)) } while ProcessInfo.processInfo.systemUptime < end
        return condition()
    }
    @MainActor private func record(_ suppliedName: String) {
        var name = suppliedName
        if landscapeAuthentication {
            let selected: Set<String> = ["Round3 authentication address keyboard", "Round3 authentication password keyboard",
                                         "Round3 authentication wrong password recoverable error", "Round3 Quick Connect cancelled password route retained"]
            guard selected.contains(name) || name.contains("failed") else { return }
            name = "Round3 phone landscape " + name.dropFirst("Round3 ".count)
        }
        if requestedLargeSettings { name = "Round3 TV requested larger app font " + name.dropFirst("Round3 ".count) }
        let screen = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); screen.name = name; screen.lifetime = .keepAlways; add(screen)
        let ax = XCTAttachment(string: app.debugDescription + "\nACTUAL_WINDOW " + String(describing: app.windows.firstMatch.frame)); ax.name = name + " accessibility"; ax.lifetime = .keepAlways; add(ax)
        print("ROUND3_CAPTURE name=\(name) window=\(app.windows.firstMatch.frame)")
    }
    #if os(tvOS)
    /// Authentic retained raw pixels + paired AX bounds are supplied privately.
    /// This invokes the SAME measurement function as the live AA assertion;
    /// a tab/setup failure is never accepted as the contrast negative control.
    @MainActor func testTVSettingsContrastRejectsRetainedPreFixPixels() throws {
        let path = try required("preFixSettingsPNG")
        let data = try Data(contentsOf: URL(fileURLWithPath: path))
        let sha = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(sha, try required("preFixSettingsSHA256"), "Authenticate the unchanged historical original before using it as a negative control")
        let image = try XCTUnwrap(UIImage(data: data)?.cgImage)
        XCTAssertEqual(image.width, 1920); XCTAssertEqual(image.height, 1080)
        for key in ["preFixHeaderFrame", "preFixFooterFrame"] {
            let values = try required(key).split(separator: ",").compactMap { Double($0) }
            XCTAssertEqual(values.count, 4)
            let rect = CGRect(x: values[0], y: values[1], width: values[2], height: values[3])
            let measured = try textContrast(image, pixels: rect)
            XCTAssertLessThan(measured, 4.5, "The authenticated old raw text must fail the exact unchanged AA measurement")
            print("ROUND3_NEGATIVE_CONTROL source=\(path) bounds=\(rect) ratio=\(measured) rejectsAA=\(measured < 4.5)")
        }
    }
    @MainActor private func assertRenderedWords(_ text: String, in element: XCUIElement) throws {
        let image = try XCTUnwrap(XCUIScreen.main.screenshot().image.cgImage)
        let crop = try XCTUnwrap(image.cropping(to: pixelFrame(element.frame, image: image)))
        let request = VNRecognizeTextRequest(); request.recognitionLevel = .accurate
        request.recognitionLanguages = ["en-US"]; request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: crop, options: [:]).perform([request])
        let words = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
        // Native tab icons can share an OCR observation with their label
        // (actual house icon + Home was read as "• Home"). Match a complete
        // rendered word, never a substring that could accept a truncated label.
        let renderedWords = words.flatMap { $0.components(separatedBy: CharacterSet.letters.inverted) }.filter { !$0.isEmpty }
        XCTAssertTrue(renderedWords.contains(where: { $0.caseInsensitiveCompare(text) == .orderedSame }), "The actual native tab must render its WHOLE word, not a middle-truncated AX-only label: \(text); pixels read \(words)")
    }
    @MainActor private func assertTextAA(_ element: XCUIElement, expected: String, name: String) throws {
        XCTAssertTrue(element.exists && !element.frame.isEmpty)
        XCTAssertTrue(element.label.localizedCaseInsensitiveContains(expected))
        XCTAssertTrue(app.frame.contains(element.frame), "Sample visible text, not an offscreen surrogate")
        record(name)
        let source = try XCTUnwrap(XCUIScreen.main.screenshot().image.cgImage)
        let bounds = pixelFrame(element.frame, image: source)
        let ratio = try textContrast(source, pixels: bounds)
        print("ROUND3_SETTINGS_AA name=\(name) expected=\(expected) actualAX=\(element.frame) pixels=\(bounds) ratio=\(ratio)")
        XCTAssertGreaterThanOrEqual(ratio, 4.5, "Actual small Settings text must meet AA on its rendered navy background")
    }
    @MainActor private func pixelFrame(_ frame: CGRect, image: CGImage) -> CGRect {
        CGRect(x: (frame.minX - app.frame.minX) * CGFloat(image.width) / app.frame.width,
               y: (frame.minY - app.frame.minY) * CGFloat(image.height) / app.frame.height,
               width: frame.width * CGFloat(image.width) / app.frame.width,
               height: frame.height * CGFloat(image.height) / app.frame.height).integral
    }
    /// Uses the actual tight StaticText frame. No icon/checkmark, passing-ink
    /// prefilter, Theme colour, expected RGB, or production view structure enters
    /// this measurement. Same function measures live and pre-fix source pixels.
    private func textContrast(_ image: CGImage, pixels: CGRect) throws -> Double {
        let crop = try XCTUnwrap(image.cropping(to: pixels.integral))
        let width = crop.width, height = crop.height
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        let rendered = rgba.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: width, height: height, bitsPerComponent: 8,
                bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue | CGBitmapInfo.byteOrder32Big.rawValue) else { return false }
            context.draw(crop, in: CGRect(x: 0, y: 0, width: width, height: height)); return true
        }
        XCTAssertTrue(rendered)
        func linear(_ byte: UInt8) -> Double {
            let c = Double(byte) / 255
            return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4)
        }
        let values = stride(from: 0, to: rgba.count, by: 4).map {
            0.2126 * linear(rgba[$0]) + 0.7152 * linear(rgba[$0 + 1]) + 0.0722 * linear(rgba[$0 + 2])
        }.sorted()
        let groups = Dictionary(grouping: values, by: { Int($0 * 128) })
        let backgroundGroup = try XCTUnwrap(groups.max(by: { $0.value.count < $1.value.count })?.value.sorted())
        let background = backgroundGroup[backgroundGroup.count / 2]
        let ink = values.filter { abs($0 - background) > 0.02 }
        XCTAssertGreaterThanOrEqual(ink.count, max(12, values.count / 500), "Require actual text pixels before judging AA")
        let foreground = ink.isEmpty ? background : ink[ink.count / 2]
        return (max(foreground, background) + 0.05) / (min(foreground, background) + 0.05)
    }
    #endif
}

/// Synthetic sign-in only, never a household/demo mutation. It records the real
/// HTTP attempts and returns a genuine401; Quick Connect is pending, not approved.
private final class RoundThreeAuthenticationFixture {
    private let listener: NWListener
    private let queue = DispatchQueue(label: "pictureshow.round3-auth-fixture")
    private let lock = NSLock()
    private var connections: [NWConnection] = []
    private var auth = 0
    private var quick = 0
    private var savedPort: UInt16 = 0
    var address: String { "http://127.0.0.1:\(savedPort)" }
    var authenticationRequests: Int { lock.lock(); defer { lock.unlock() }; return auth }
    var quickInitiations: Int { lock.lock(); defer { lock.unlock() }; return quick }
    init() throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters, on: .any)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { connection.cancel(); return }
            self.lock.lock(); self.connections.append(connection); self.lock.unlock()
            connection.start(queue: self.queue); self.receive(connection, data: Data())
        }
        listener.start(queue: queue)
        guard ready.wait(timeout: .now() + 5) == .success, let port = listener.port else { throw NSError(domain: "Round3AuthFixture", code: 1) }
        savedPort = port.rawValue
    }
    func stop() {
        listener.cancel(); lock.lock(); let open = connections; connections = []; lock.unlock()
        open.forEach { $0.cancel() }
    }
    private func receive(_ connection: NWConnection, data: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 32_768) { [weak self] chunk, _, complete, error in
            guard let self else { connection.cancel(); return }
            var data = data; if let chunk { data.append(chunk) }
            guard let end = data.range(of: Data("\r\n\r\n".utf8)) else {
                if complete || error != nil { connection.cancel() } else { self.receive(connection, data: data) }; return
            }
            let request = String(decoding: data[..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n").first ?? ""
            let pieces = request.split(separator: " ")
            guard pieces.count >= 2, let url = URL(string: "http://fixture" + pieces[1]) else { connection.cancel(); return }
            let body: Any; var status = "200 OK"
            switch url.path {
            case "/System/Info/Public": body = ["ProductName": "Jellyfin Server", "Version": "12.0.0", "Id": "round3-auth", "ServerName": "Round three fixture"]
            case "/Users/Public": body = [] as [String]
            case "/QuickConnect/Enabled": body = true
            case "/QuickConnect/Initiate":
                self.lock.lock(); self.quick += 1; self.lock.unlock()
                body = ["Code": "132999", "Secret": "synthetic-round3-pending", "Authenticated": false]
            case "/QuickConnect/Connect": body = ["Code": "132999", "Secret": "synthetic-round3-pending", "Authenticated": false]
            case "/Users/AuthenticateByName":
                self.lock.lock(); self.auth += 1; self.lock.unlock()
                status = "401 Unauthorized"; body = ["Error": "Synthetic invalid credentials"]
            default: status = "404 Not Found"; body = ["Error": "Unexpected auth fixture route"]
            }
            guard let payload = try? JSONSerialization.data(withJSONObject: body, options: [.fragmentsAllowed]) else { connection.cancel(); return }
            var response = Data("HTTP/1.1 \(status)\r\nContent-Type: application/json\r\nContent-Length: \(payload.count)\r\nConnection: close\r\n\r\n".utf8)
            response.append(payload)
            connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
        }
    }
}
