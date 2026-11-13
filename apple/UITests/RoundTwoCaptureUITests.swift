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
        let window = app.windows.firstMatch
        let before = window.frame
        record("Round2 iPad native OS window before compact gesture")
        if before.width > CGFloat(maximum) {
            // The actual iPadOS corner handle is visible in the full-screen
            // capture. This is an OS touch drag, never an app width override.
            let handle = window.coordinate(withNormalizedOffset: CGVector(dx: 1, dy: 1)).withOffset(CGVector(dx: -5, dy: -5))
            let destination = window.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: CGFloat(maximum) - 20, dy: before.height * 0.8))
            handle.press(forDuration: 0.5, thenDragTo: destination)
            let resized = wait(10) { window.frame.width <= CGFloat(maximum) }
            record("Round2 iPad native OS compact gesture result")
            XCTAssertTrue(resized, "One native corner drag must produce an actual compact OS window")
        }
        print("ROUND2_NATIVE_WINDOW before=\(before) after=\(window.frame) maximum=\(maximum) source=actualOS")
        XCTAssertLessThanOrEqual(window.frame.width, CGFloat(maximum), "Measure the actual OS window, including after its native corner drag")
        try captureConfiguredCatalogAndDetail(prefix: "Round2 iPad actual compact OS window", pointer: true)
    }

    @MainActor private func captureConfiguredCatalogAndDetail(prefix: String, pointer: Bool = false) throws {
        try libraries()
        record(prefix + " Libraries")
        activate(app.buttons["item-" + (try required("libraryID"))].firstMatch)
        XCTAssertTrue(sortAnchor.waitForExistence(timeout: 20))
        record(prefix + " catalog")
        let artwork = app.buttons["item-" + (try required("artworkItemID"))].firstMatch
        if pointer { captureNativePointerHover(artwork, name: prefix + " catalog") }
        activate(artwork)
        XCTAssertTrue(app.staticTexts["detailTitle"].waitForExistence(timeout: 20))
        record(prefix + " detail")
        if pointer { captureNativePointerHover(app.buttons["play"], name: prefix + " detail") }
    }
    @MainActor private func captureNativePointerHover(_ target: XCUIElement, name: String) {
        XCTAssertEqual(UIDevice.current.userInterfaceIdiom, .pad)
        XCTAssertTrue(target.exists && target.isHittable)
        let window = app.windows.firstMatch
        let windowFrame = window.frame
        let wasDetail = app.staticTexts["detailTitle"].exists
        window.coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.1)).hover()
        record(name + " native pointer away")
        target.hover()
        record(name + " native pointer over")
        print("ROUND2_POINTER api=XCUIElement.hover target=\(target.identifier) frame=\(target.frame) window=\(window.frame) noTap=true")
        XCTAssertEqual(app.staticTexts["detailTitle"].exists, wasDetail, "Hover must retain the current route without activating the target")
        XCTAssertEqual(window.frame, windowFrame, "Pointer movement cannot substitute a resized app view")
    }
    #endif

    @MainActor func testSettingsNativeSubpickers() throws {
        try signIn()
        home()
        openSection("Settings", identifier: "openSettings")
        XCTAssertTrue(node("settingsTitle").waitForExistence(timeout: 10))
        record("Round2 Settings native root")
        #if os(tvOS)
        try captureTelevisionSettingsChoices()
        #else
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
        let subtitle = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Subtitle size")).firstMatch
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
        #endif
    }

    #if os(tvOS)
    @MainActor private func captureTelevisionSettingsChoices() throws {
        let bitrate = node("maxBitrate")
        let subtitle = app.buttons["subtitleSize"]
        func shows(_ picker: XCUIElement, _ value: String) -> Bool {
            picker.value as? String == value || picker.label.hasSuffix(value) || picker.staticTexts[value].exists
        }
        func choose(_ picker: XCUIElement, _ value: String, current: String, choices: [String], name: String) {
            activate(picker)
            for choice in choices {
                XCTAssertTrue(app.otherElements.matching(NSPredicate(format: "label == %@", choice)).firstMatch.waitForExistence(timeout: 5), "The actual native picker must expose its choice: \(choice)")
            }
            let selected = app.otherElements.matching(NSPredicate(format: "label == %@", current)).firstMatch
            XCTAssertTrue(selected.isSelected, "The native checkmark must identify the actual saved selection")
            record(name)
            activate(app.otherElements.matching(NSPredicate(format: "label == %@", value)).firstMatch)
            XCTAssertTrue(wait(10) { shows(picker, value) }, "The native selection must save and return to Settings")
            record(name + " saved selection " + value)
        }
        XCTAssertTrue(bitrate.waitForExistence(timeout: 10))
        XCTAssertTrue(bitrate.label.contains("Max streaming bitrate"))
        XCTAssertTrue(app.staticTexts["Max streaming bitrate"].exists)
        XCTAssertTrue(app.staticTexts["Subtitle size"].exists)
        XCTAssertTrue(app.staticTexts["Subtitle background"].exists)
        XCTAssertTrue(subtitle.label.contains("Subtitle size"))
        XCTAssertTrue(shows(bitrate, "Auto (200 Mbps)"))
        let rates = ["Auto (200 Mbps)", "120 Mbps", "80 Mbps", "40 Mbps", "20 Mbps", "10 Mbps", "4 Mbps"]
        choose(bitrate, "40 Mbps", current: "Auto (200 Mbps)", choices: rates, name: "Round2 Settings bitrate native picker")
        choose(bitrate, "Auto (200 Mbps)", current: "40 Mbps", choices: rates, name: "Round2 Settings bitrate reopened with saved value")
        XCTAssertTrue(subtitle.exists && shows(subtitle, "System"))
        let sizes = ["System", "Small", "Normal", "Large", "ExtraLarge"]
        choose(subtitle, "Large", current: "System", choices: sizes, name: "Round2 Settings subtitle size native picker")
        choose(subtitle, "System", current: "Large", choices: sizes, name: "Round2 Settings subtitle size reopened with saved value")
    }
    #endif

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
    #if os(iOS)
    private func visibleTouchTab(_ name: String) -> XCUIElement? {
        // sidebarAdaptable uses real Buttons for tabs and real Cells for an
        // expanded sidebar; hidden duplicates cannot establish the route.
        let visible = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", name)).allElementsBoundByIndex.filter {
            ($0.elementType == .button || $0.elementType == .cell) && $0.isHittable &&
                !$0.frame.isEmpty && $0.frame.intersects(app.windows.firstMatch.frame)
        }
        return visible.first(where: \.isSelected) ?? visible.first
    }
    private var nativeHomeIdentityLoaded: Bool {
        guard let resumeID = config["resumeID"], let home = visibleTouchTab("Home") else { return false }
        return home.isSelected && app.staticTexts["Continue watching"].exists && app.buttons["item-" + resumeID].firstMatch.exists
    }
    private var nativeHomeContentVisible: Bool {
        nativeHomeIdentityLoaded && app.staticTexts["Continue watching"].frame.intersects(app.windows.firstMatch.frame)
    }
    @MainActor private func activateVisibleTouchTab(_ name: String) {
        let target = visibleTouchTab(name)
        if target == nil { record("Round2 missing visible native tab " + name) }
        guard let target else { XCTFail("The actual native tab must be visible and hittable: " + name); return }
        activate(target)
    }
    #endif
    @MainActor private func home() {
        #if os(iOS)
        if nativeHomeContentVisible { return }
        activateVisibleTouchTab("Home")
        // Home data arrives asynchronously after native tab selection. Wait for
        // its own heading and configured identity, without backing out of Home.
        let loaded = wait(20) { self.nativeHomeIdentityLoaded }
        if !loaded { record("Round2 Home content identity did not load") }
        XCTAssertTrue(loaded, "Selected native Home must load Continue watching and the configured resume identity")
        guard loaded else { return }
        for _ in 0..<10 {
            if nativeHomeContentVisible { return }
            app.swipeDown()
        }
        XCTAssertTrue(nativeHomeContentVisible, "The actual Home heading must be visible; a resume card may remain offscreen in its native row")
        #else
        for _ in 0..<10 {
            if homeControlsVisible { return }
            back()
        }
        XCTAssertTrue(homeControlsVisible)
        #endif
    }
    @MainActor private func libraries() throws {
        home()
        #if os(iOS)
        activateVisibleTouchTab("Libraries")
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
        #if os(iOS)
        if app.buttons[identifier].exists && app.buttons[identifier].isHittable { activate(app.buttons[identifier]) }
        else { activateVisibleTouchTab(name) }
        #else
        if app.buttons[identifier].exists { activate(app.buttons[identifier]) }
        else { activate(app.buttons[name]) }
        #endif
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
