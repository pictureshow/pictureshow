// SPDX-License-Identifier: MPL-2.0
// Copyright (c) Legitimate LLC.
import XCTest
import UIKit

/// Explicitly selected private pilot cases. Credentials and demo IDs arrive only
/// through a local xctestrun; no account, server address or artwork is embedded here.
final class RoundTwoCaptureUITests: XCTestCase {
    private var app: XCUIApplication!
    private var config: [String: String] = [:]

    override func setUpWithError() throws {
        continueAfterFailure = false
        let json = try XCTUnwrap(ProcessInfo.processInfo.environment["PICTURESHOW_PILOT_JSON"], "Supply the private Round2 pilot configuration")
        config = try JSONDecoder().decode([String: String].self, from: Data(json.utf8))
        app = XCUIApplication()
        for (key, environment) in [("server", "PICTURESHOW_TEST_SERVER"), ("username", "PICTURESHOW_TEST_USERNAME"), ("password", "PICTURESHOW_TEST_PASSWORD")] {
            app.launchEnvironment[environment] = try XCTUnwrap(config[key])
        }
        app.launch()
    }

    override func tearDownWithError() throws { app?.terminate() }

    /// Covers the real sign-in/Quick Connect screens, then leaves no pending code.
    /// One-time codes are retained only in the private capture bundle, never printed.
    @MainActor func testSignInAndQuickConnectScreens() throws {
        if authenticated {
            home()
            activate(app.buttons["signOut"])
        }
        if app.buttons["addServer"].waitForExistence(timeout: 2) { activate(app.buttons["addServer"]) }
        let connect = app.buttons["connect"]
        if connect.waitForExistence(timeout: 3) {
            XCTAssertTrue(app.textFields["serverAddress"].exists)
            record("Round2 server address sign-in")
            activate(connect)
        }
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 25))
        dismissKeyboard()
        record("Round2 password sign-in")
        let quick = app.buttons["quickConnect"]
        activate(quick)
        XCTAssertTrue(app.staticTexts["quickCode"].waitForExistence(timeout: 15))
        XCTAssertTrue(app.buttons["cancelQuick"].exists)
        record("Round2 Quick Connect awaiting approval private ephemeral code")
        activate(app.buttons["cancelQuick"])
        XCTAssertTrue(app.staticTexts["quickCode"].waitForNonExistence(timeout: 10))
        XCTAssertTrue(app.buttons["signIn"].exists)
        record("Round2 Quick Connect cancelled password restored")
        submitSignIn()
        home()
        record("Round2 signed-in initial Home focus")
    }

    /// Uses server-supplied artwork, resume data and empty library, not a mock feed.
    @MainActor func testRealArtworkHomeLibrariesSearchAndEmptyStates() throws {
        try signIn()
        home()
        let resume = app.buttons["item-" + (try required("resumeID"))].firstMatch
        XCTAssertTrue(resume.waitForExistence(timeout: 20), "The prepared pilot resume item must appear on Home")
        XCTAssertTrue(app.staticTexts["Continue watching"].exists)
        record("Round2 Home continue watching real artwork initial focus")
        #if os(tvOS)
        let focused = try XCTUnwrap(app.descendants(matching: .any).allElementsBoundByIndex.first(where: \.hasFocus))
        XCTAssertTrue(focused.frame.intersects(app.frame), "Initial Home focus must be visible")
        XCUIRemote.shared.press(.right)
        record("Round2 Home artwork focus after one Right")
        XCUIRemote.shared.press(.left)
        record("Round2 Home artwork focus after one Left")
        #endif
        try libraries()
        #if os(tvOS)
        record("Round2 Home Your libraries shelf real artwork")
        #else
        record("Round2 Libraries real artwork")
        #endif
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        XCTAssertTrue(sortAnchor.waitForExistence(timeout: 20))
        let artworkItem = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        reveal(artworkItem)
        XCTAssertTrue(artworkItem.exists)
        record("Round2 real artwork library grid")
        activate(artworkItem)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["play"].exists)
        record("Round2 real artwork detail")
        try libraries()
        activate(app.buttons["item-" + (try required("emptyLibraryID"))].firstMatch)
        XCTAssertTrue(app.staticTexts["No titles are available."].waitForExistence(timeout: 20))
        XCTAssertTrue(app.buttons["Refresh"].exists)
        record("Round2 empty library recoverable state")
        home()
        openSection("Search", identifier: "openSearch")
        let field = app.textFields["searchField"]
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        record("Round2 Search initial state")
        activate(field)
        field.typeText(try required("searchQuery"))
        let result = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        XCTAssertTrue(result.waitForExistence(timeout: 20))
        dismissKeyboard()
        record("Round2 Search real artwork results")
        activate(app.buttons["clearSearch"])
        activate(field)
        field.typeText("pictureshow132-no-matching-title-20261111")
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "No results for")).firstMatch.waitForExistence(timeout: 20))
        dismissKeyboard()
        record("Round2 Search no results")
    }

    #if os(iOS)
    @MainActor func testPhoneLandscapeCatalogAndDetail() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .phone, "Select this case only for a phone")
        try signIn()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(wait(10) { self.app.windows.firstMatch.frame.width > self.app.windows.firstMatch.frame.height })
        try captureConfiguredCatalogAndDetail(prefix: "Round2 phone native landscape")
    }

    /// The harness must first resize the actual iPad OS window. This test measures
    /// that window and fails on a full-width substitute; no app width override exists.
    @MainActor func testIPadNativeCompactWindowCatalogAndDetail() throws {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad, "Select this case only for an iPad")
        try signIn()
        let maximum = try XCTUnwrap(Double(try required("nativeWindowMaximumWidth")))
        XCTAssertLessThanOrEqual(app.windows.firstMatch.frame.width, CGFloat(maximum), "Resize the actual OS window before selecting this case")
        try captureConfiguredCatalogAndDetail(prefix: "Round2 iPad actual compact OS window")
    }

    @MainActor private func captureConfiguredCatalogAndDetail(prefix: String) throws {
        try libraries()
        record(prefix + " Libraries")
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        XCTAssertTrue(sortAnchor.waitForExistence(timeout: 20))
        record(prefix + " catalog")
        activate(app.buttons["item-" + (try required("artworkItemID"))].firstMatch)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        record(prefix + " detail")
    }
    #endif

    @MainActor func testSettingsNativeSubpickers() throws {
        try signIn()
        home()
        openSection("Settings", identifier: "openSettings")
        XCTAssertTrue(node("settingsTitle").waitForExistence(timeout: 10))
        record("Round2 Settings native root")
        let bitrate = node("maxBitrate")
        #if os(iOS)
        assertPickerDefault(bitrate, expected: "Auto (200 Mbps)")
        #endif
        activate(bitrate)
        XCTAssertTrue(app.staticTexts["Auto (200 Mbps)"].waitForExistence(timeout: 10) || app.buttons["Auto (200 Mbps)"].exists)
        record("Round2 Settings bitrate native picker")
        #if os(iOS)
        selectCurrentPickerValue("Auto (200 Mbps)")
        XCTAssertTrue(node("settingsTitle").waitForExistence(timeout: 10), "Closing the native picker menu keeps Settings open")
        assertPickerDefault(bitrate, expected: "Auto (200 Mbps)")
        #else
        back()
        #endif
        let subtitle = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Subtitle size")).firstMatch
        #if os(iOS)
        assertPickerDefault(subtitle, expected: "System")
        #endif
        activate(subtitle)
        XCTAssertTrue(app.staticTexts["Large"].waitForExistence(timeout: 10) || app.buttons["Large"].exists)
        record("Round2 Settings subtitle size native picker")
        #if os(iOS)
        selectCurrentPickerValue("System")
        XCTAssertTrue(node("settingsTitle").waitForExistence(timeout: 10), "Closing the native picker menu keeps Settings open")
        assertPickerDefault(subtitle, expected: "System")
        #else
        back()
        #endif
    }

    #if os(iOS)
    @MainActor private func assertPickerDefault(_ picker: XCUIElement, expected: String) {
        XCTAssertTrue(picker.waitForExistence(timeout: 10))
        let value = picker.value as? String
        XCTAssertTrue(value == expected || picker.label.hasSuffix(expected) || picker.staticTexts[expected].exists,
                      "Fresh-install picker must display its actual default before opening: expected \(expected), value \(value ?? "<none>"), label \(picker.label)")
    }
    @MainActor private func selectCurrentPickerValue(_ value: String) {
        let button = app.buttons[value].firstMatch
        if button.waitForExistence(timeout: 2) { activate(button) }
        else { activate(app.staticTexts[value].firstMatch) }
    }
    #endif

    private func required(_ key: String) throws -> String { try XCTUnwrap(config[key], "Missing private pilot key: " + key) }
    private func node(_ id: String) -> XCUIElement { app.descendants(matching: .any).matching(identifier: id).firstMatch }
    private var sortAnchor: XCUIElement {
        #if os(tvOS)
        return node("sort-SortName")
        #else
        return node("librarySort")
        #endif
    }
    // Native tvOS toolbar buttons preserve their visible label while SwiftUI
    // can omit the NavigationLink identifier from the exposed button snapshot.
    private var homeControlsVisible: Bool {
        app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "openSearch", "Search")).firstMatch.exists &&
            app.buttons.matching(NSPredicate(format: "identifier == %@ OR label == %@", "openSettings", "Settings")).firstMatch.exists
    }
    private var authenticated: Bool { homeControlsVisible || app.buttons["Home"].exists }
    @MainActor private func signIn() throws {
        if authenticated { return }
        if app.buttons["addServer"].waitForExistence(timeout: 2) { activate(app.buttons["addServer"]) }
        if app.buttons["connect"].waitForExistence(timeout: 3) { activate(app.buttons["connect"]) }
        XCTAssertTrue(app.buttons["signIn"].waitForExistence(timeout: 25))
        dismissKeyboard()
        submitSignIn()
    }
    @MainActor private func submitSignIn() {
        #if os(iOS)
        let username = app.textFields["username"]
        XCTAssertEqual(username.value as? String, config["username"], "Submit the actual prepared pilot username")
        let password = app.secureTextFields["password"]
        XCTAssertTrue(password.exists && !(password.value as? String ?? "").isEmpty, "Prepared password must remain present")
        // Sign-in options and Quick Connect cancellation can establish focus after
        // the form first exists. Establish and dismiss that real keyboard before
        // submitting so a native keyboard overlay cannot receive the button tap.
        username.tap()
        let done = app.buttons["dismissSignInKeyboard"]
        XCTAssertTrue(wait(5) {
            self.app.keyboards.firstMatch.exists && done.exists && !done.frame.isEmpty &&
                done.frame.intersects(self.app.frame) && done.isHittable
        }, "The focused native field must expose its visible keyboard dismissal control")
        done.tap()
        XCTAssertTrue(app.keyboards.firstMatch.waitForNonExistence(timeout: 5), "Dismiss the native keyboard before submitting")
        #else
        dismissKeyboard()
        #endif
        activate(app.buttons["signIn"])
        let signedIn = wait(30) { self.authenticated }
        if !signedIn { record("Round2 sign-in failure native state") }
        XCTAssertTrue(signedIn, "Actual password submission must reach the authenticated native app")
    }
    @MainActor private func home() {
        #if os(iOS)
        if app.buttons["Home"].exists { activate(app.buttons["Home"]) }
        #endif
        for _ in 0..<10 {
            if homeControlsVisible { return }
            back()
        }
        XCTAssertTrue(homeControlsVisible)
    }
    @MainActor private func libraries() throws {
        home()
        #if os(iOS)
        activate(app.buttons["Libraries"].firstMatch)
        for _ in 0..<8 {
            if app.buttons["item-" + (try required("libraryID"))].firstMatch.exists { break }
            back()
        }
        #else
        // TV Home exposes the same server-provided libraries shelf in its native stack.
        let library = app.buttons["item-" + (try required("libraryID"))].firstMatch
        reveal(library)
        #endif
        XCTAssertTrue(app.buttons["item-" + (try required("libraryID"))].firstMatch.waitForExistence(timeout: 15))
    }
    @MainActor private func openSection(_ name: String, identifier: String) {
        if app.buttons[identifier].exists { activate(app.buttons[identifier]) }
        else { activate(app.buttons[name]) }
    }
    @MainActor private func dismissKeyboard() {
        #if os(iOS)
        let done = app.buttons["dismissSignInKeyboard"]
        if app.keyboards.firstMatch.exists, done.exists,
           !done.frame.isEmpty, done.frame.intersects(app.frame), done.isHittable { done.tap() }
        else if app.keyboards.buttons["Search"].exists { app.keyboards.buttons["Search"].tap() }
        #else
        if app.keyboards.firstMatch.exists { XCUIRemote.shared.press(.menu) }
        #endif
    }
    @MainActor private func back() {
        #if os(tvOS)
        XCUIRemote.shared.press(.menu)
        #else
        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.exists)
        back.tap()
        #endif
    }
    @MainActor private func reveal(_ element: XCUIElement) {
        for _ in 0..<24 {
            if element.exists && element.isHittable && element.frame.intersects(app.frame) { return }
            #if os(tvOS)
            XCUIRemote.shared.press(.down)
            #else
            app.swipeUp()
            #endif
        }
    }
    @MainActor private func activate(_ element: XCUIElement) {
        reveal(element)
        XCTAssertTrue(element.waitForExistence(timeout: 10))
        #if os(tvOS)
        for _ in 0..<45 {
            if element.hasFocus { XCUIRemote.shared.press(.select); return }
            let current = app.descendants(matching: .any).allElementsBoundByIndex.first(where: \.hasFocus)?.frame
            if let current, current.contains(CGPoint(x: element.frame.midX, y: element.frame.midY)), current.height < element.frame.height * 3 { XCUIRemote.shared.press(.select); return }
            guard let current else { XCUIRemote.shared.press(.down); continue }
            let target = element.frame
            if target.maxY <= current.minY + 12 { XCUIRemote.shared.press(.up) }
            else if target.minY >= current.maxY - 12 { XCUIRemote.shared.press(.down) }
            else { XCUIRemote.shared.press(target.midX < current.midX ? .left : .right) }
        }
        XCTFail("Native remote could not focus configured target: " + element.identifier)
        #else
        XCTAssertTrue(element.isHittable)
        element.tap()
        #endif
    }
    @MainActor private func wait(_ timeout: TimeInterval, _ condition: () -> Bool) -> Bool {
        let deadline = ProcessInfo.processInfo.systemUptime + timeout
        repeat { if condition() { return true }; RunLoop.current.run(until: Date().addingTimeInterval(0.1)) } while ProcessInfo.processInfo.systemUptime < deadline
        return condition()
    }
    @MainActor private func record(_ name: String) {
        let screenshot = XCTAttachment(screenshot: XCUIScreen.main.screenshot()); screenshot.name = name; screenshot.lifetime = .keepAlways; add(screenshot)
        let text = XCTAttachment(string: app.debugDescription); text.name = name + " accessibility"; text.lifetime = .keepAlways; add(text)
        print("ROUND2_CAPTURE name=\(name) window=\(app.windows.firstMatch.frame)")
    }
}
